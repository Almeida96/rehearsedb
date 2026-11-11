package io.github.almeida96.rehearsedb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** A stdio MCP session bound to one report loaded by the operator. */
public final class Mcp {
    private static final ObjectMapper JSON = Fileset.JSON;
    private static final String LATEST = "2025-11-25";
    private static final int MAX_FRAME = 1024 * 1024;
    private static final int PAGE_LIMIT = 100;
    private static final List<String> STAGES = List.of("forward", "rollback");
    private static final List<String> TOOLS = List.of("rehearsal_summary", "list_changes", "inspect_change");
    private final JsonNode report;
    private boolean negotiated;
    private boolean ready;

    private Mcp(JsonNode report) {
        if (report == null || !report.path("formatVersion").isIntegralNumber()
                || !report.path("formatVersion").canConvertToInt() || report.path("formatVersion").intValue() != 1
                || !Set.of("PASS", "FAIL", "FORWARD_ONLY").contains(report.path("status").asText(""))
                || !report.path("stages").isObject()) {
            throw new IllegalArgumentException("Expected a RehearseDB formatVersion 1 report");
        }
        this.report = report;
    }

    public static void serve(JsonNode report, InputStream input, OutputStream output) throws IOException {
        var session = new Mcp(report);
        var stream = new BufferedInputStream(input);
        byte[] frame = new byte[MAX_FRAME];
        int used = 0;
        boolean oversized = false;
        int next;
        while ((next = stream.read()) != -1) {
            if (next == '\n') {
                session.respond(frame, used, oversized, output);
                used = 0;
                oversized = false;
            } else if (used < MAX_FRAME) {
                frame[used++] = (byte) next;
            } else {
                oversized = true;
            }
        }
        if (used != 0 || oversized) session.respond(frame, used, oversized, output);
    }

    private void respond(byte[] frame, int used, boolean oversized, OutputStream output) throws IOException {
        ObjectNode reply;
        if (oversized) {
            reply = error(null, -32700, "Message exceeds 1 MiB");
        } else {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(frame, 0, used)).toString();
                JsonNode request = JSON.readTree(text);
                reply = request == null || request.isMissingNode()
                        ? error(null, -32700, "Empty JSON message") : handle(request);
            } catch (JsonProcessingException | CharacterCodingException invalid) {
                reply = error(null, -32700, "Invalid UTF-8 JSON message");
            }
        }
        if (reply == null) return;
        byte[] bytes = JSON.writeValueAsBytes(reply);
        if (bytes.length > MAX_FRAME) {
            bytes = JSON.writeValueAsBytes(error(reply.get("id"), -32603, "Result exceeds 1 MiB"));
        }
        output.write(bytes);
        output.write('\n');
        output.flush();
    }

    private ObjectNode handle(JsonNode request) {
        JsonNode id = request.get("id");
        if (!request.isObject() || !"2.0".equals(request.path("jsonrpc").textValue())
                || !request.path("method").isTextual()
                || (id != null && !(id.isIntegralNumber() || id.isTextual()))
                || (id != null && id.toString().length() > 1024)) {
            return error(null, -32600, "Invalid JSON-RPC request");
        }
        String method = request.path("method").textValue();
        if (id == null) {
            if ("notifications/initialized".equals(method) && negotiated) ready = true;
            return null;
        }
        JsonNode params = request.has("params") ? request.get("params") : JSON.createObjectNode();
        if (!params.isObject()) return error(id, -32602, "params must be an object");
        try {
            if ("ping".equals(method)) return success(id, JSON.createObjectNode());
            if ("initialize".equals(method)) {
                if (negotiated) return error(id, -32600, "Session already initialized");
                String version = string(params, "protocolVersion");
                if (!params.path("capabilities").isObject()) throw invalid("Missing client capabilities");
                string(params.path("clientInfo"), "name");
                string(params.path("clientInfo"), "version");
                ObjectNode result = JSON.createObjectNode();
                result.put("protocolVersion", "2025-06-18".equals(version) ? version : LATEST);
                result.putObject("serverInfo").put("name", "rehearsedb").put("version", Main.VERSION);
                result.putObject("capabilities").putObject("tools").put("listChanged", false);
                negotiated = true;
                return success(id, result);
            }
            if (!ready) return error(id, -32600, "Complete initialization first");
            if ("tools/list".equals(method)) {
                fields(params, Set.of("_meta"));
                return success(id, inventory());
            }
            if (!"tools/call".equals(method)) return error(id, -32601, "Method not found");
            fields(params, Set.of("name", "arguments", "_meta"));
            JsonNode args = params.has("arguments") ? params.get("arguments") : JSON.createObjectNode();
            if (!args.isObject()) throw invalid("arguments must be an object");
            String name = string(params, "name");
            if (!TOOLS.contains(name)) return error(id, -32602, "Unknown tool");
            ObjectNode data;
            try {
                data = call(name, args);
            } catch (IllegalArgumentException e) {
                ObjectNode failure = JSON.createObjectNode().put("isError", true);
                failure.putArray("content").addObject().put("type", "text").put("text", e.getMessage());
                return success(id, failure);
            }
            ObjectNode result = JSON.createObjectNode().put("isError", false);
            result.set("structuredContent", data);
            result.putArray("content").addObject().put("type", "text").put("text", data.toString());
            return success(id, result);
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        }
    }

    private ObjectNode call(String name, JsonNode args) {
        if ("rehearsal_summary".equals(name)) {
            fields(args, Set.of());
            ObjectNode result = JSON.createObjectNode();
            copyFields(report, result, "formatVersion", "status", "baseline");
            ObjectNode summary = result.putObject("stages");
            for (String stage : STAGES) {
                JsonNode source = report.path("stages").path(stage);
                ObjectNode item = summary.putObject(stage);
                item.put("status", source.path("status").asText("NOT_RUN"));
                if (hasEvidence(source)) item.put("changeCount", source.get("changes").size());
                else item.putNull("changeCount");
                copyFields(source, item, "health", "checks", "matchesBaseline", "reason", "error", "sqliteCode");
            }
            return result;
        }
        boolean listing = "list_changes".equals(name);
        fields(args, listing ? Set.of("stage", "offset", "limit") : Set.of("stage", "kind", "name"));
        String stage = string(args, "stage");
        if (!STAGES.contains(stage)) throw invalid("stage must be forward or rollback");
        int offset = listing ? integer(args, "offset", 0, 0, Integer.MAX_VALUE) : 0;
        int limit = listing ? integer(args, "limit", 25, 1, PAGE_LIMIT) : 0;
        String kind = listing ? null : string(args, "kind");
        String objectName = listing ? null : string(args, "name");
        JsonNode snapshot = report.path("stages").path(stage);
        String status = snapshot.path("status").asText("NOT_RUN");
        if (!hasEvidence(snapshot)) throw invalid("No comparison evidence for " + stage + " (" + status + ")");
        JsonNode changes = snapshot.get("changes");
        ObjectNode result = JSON.createObjectNode().put("stage", stage).put("status", status);
        if (listing) {
            int start = Math.min(offset, changes.size());
            int end = start + Math.min(limit, changes.size() - start);
            result.put("total", changes.size()).put("offset", offset);
            if (end < changes.size()) result.put("nextOffset", end);
            else result.putNull("nextOffset");
            ArrayNode rows = result.putArray("changes");
            for (int i = start; i < end; i++) copyFields(changes.get(i), rows.addObject(), "kind", "name", "change");
            return result;
        }
        for (JsonNode change : changes) {
            if (kind.equals(change.path("kind").textValue()) && objectName.equals(change.path("name").textValue())) {
                copyFields(change, result.putObject("change"), "kind", "name", "change", "before", "after");
                return result;
            }
        }
        throw invalid("No matching object in this stage's changes");
    }

    private static boolean hasEvidence(JsonNode stage) {
        String status = stage.path("status").asText("NOT_RUN");
        return !Set.of("NOT_RUN", "SKIPPED").contains(status) && stage.path("changes").isArray();
    }

    private static ObjectNode inventory() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode list = result.putArray("tools");
        for (String name : TOOLS) {
            ObjectNode tool = list.addObject().put("name", name);
            tool.put("description", switch (name) {
                case "rehearsal_summary" -> "Read the report verdict, stage status, health and change counts. A null count means comparison evidence is unavailable.";
                case "list_changes" -> "Page through changed object identities. Forward compares the migrated copy to its baseline. Rollback compares the restored copy to that same baseline.";
                default -> "Read the before and after evidence for one exact kind and name from list_changes.";
            });
            tool.putObject("annotations").put("readOnlyHint", true).put("destructiveHint", false)
                    .put("idempotentHint", true).put("openWorldHint", false);
            tool.putObject("outputSchema").put("type", "object");
            ObjectNode schema = tool.putObject("inputSchema").put("type", "object").put("additionalProperties", false);
            ObjectNode properties = schema.putObject("properties");
            if ("rehearsal_summary".equals(name)) continue;
            properties.putObject("stage").put("type", "string").putArray("enum").add("forward").add("rollback");
            ArrayNode required = schema.putArray("required").add("stage");
            if ("list_changes".equals(name)) {
                properties.putObject("offset").put("type", "integer").put("minimum", 0).put("maximum", Integer.MAX_VALUE);
                properties.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", PAGE_LIMIT);
            } else {
                for (String field : List.of("kind", "name")) {
                    properties.putObject(field).put("type", "string").put("minLength", 1);
                    required.add(field);
                }
            }
        }
        return result;
    }

    private static void copyFields(JsonNode source, ObjectNode destination, String... keys) {
        for (String key : keys) if (source.has(key)) destination.set(key, source.get(key));
    }

    private static String string(JsonNode params, String key) {
        JsonNode value = params.path(key);
        if (!value.isTextual() || value.textValue().isEmpty()) throw invalid("Expected a nonempty string for " + key);
        return value.textValue();
    }

    private static int integer(JsonNode node, String key, int fallback, int min, int max) {
        if (!node.has(key)) return fallback;
        JsonNode value = node.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw invalid(key + " must be an integer between " + min + " and " + max);
        return value.intValue();
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw invalid("Unexpected parameter");
        });
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static ObjectNode envelope(JsonNode id) {
        ObjectNode reply = JSON.createObjectNode().put("jsonrpc", "2.0");
        return reply.set("id", id);
    }

    private static ObjectNode success(JsonNode id, ObjectNode data) {
        return envelope(id).set("result", data);
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode reply = envelope(id);
        reply.putObject("error").put("code", code).put("message", message);
        return reply;
    }
}
