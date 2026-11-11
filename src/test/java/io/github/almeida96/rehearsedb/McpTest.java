package io.github.almeida96.rehearsedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class McpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    private static final String REPORT = """
        {"formatVersion":1,"status":"FORWARD_ONLY","stages":{
          "forward":{"status":"PASS","health":{"ok":true},"changes":[
            {"kind":"table","name":"orders","change":"modified","before":{"rows":1},"after":{"rows":2}},
            {"kind":"view","name":"orders view","change":"added","before":null,"after":{"kind":"view"}}]},
          "rollback":{"status":"NOT_RUN","reason":"No rollback script supplied"}}}
        """;

    private static String request(String method, ObjectNode params) {
        return JSON.createObjectNode().put("jsonrpc", "2.0").put("id", "request").put("method", method)
                .set("params", params).toString();
    }

    private static String init(String version) {
        ObjectNode params = JSON.createObjectNode().put("protocolVersion", version);
        params.putObject("clientInfo").put("name", "test").put("version", "1");
        params.putObject("capabilities");
        return request("initialize", params);
    }

    private static String call(String tool, String args) throws Exception {
        ObjectNode params = JSON.createObjectNode().put("name", tool);
        params.set("arguments", JSON.readTree(args));
        return request("tools/call", params);
    }

    private static List<JsonNode> exchange(JsonNode report, byte[] frames) throws Exception {
        var out = new ByteArrayOutputStream();
        Mcp.serve(report, new ByteArrayInputStream(frames), out);
        var messages = new ArrayList<JsonNode>();
        for (String frame : out.toString(UTF_8).lines().toList()) messages.add(JSON.readTree(frame));
        return messages;
    }

    private static List<JsonNode> session(JsonNode report, String... frames) throws Exception {
        String batch = init("2025-11-25") + "\n" + INITIALIZED + "\n" + String.join("\n", frames) + "\n";
        var messages = exchange(report, batch.getBytes(UTF_8));
        assertEquals("2025-11-25", messages.removeFirst().at("/result/protocolVersion").asText());
        return messages;
    }

    @Test void negotiatesVersionAndWaitsForClientReadiness() throws Exception {
        String inventory = request("tools/list", JSON.createObjectNode());
        var frames = List.of(inventory, init("2025-06-18"), inventory, INITIALIZED,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}", inventory, init("2025-06-18"));
        var messages = exchange(JSON.readTree(REPORT), String.join("\r\n", frames).getBytes(UTF_8));
        assertEquals(5, messages.size());
        assertEquals(-32600, messages.get(0).at("/error/code").asInt());
        assertEquals("2025-06-18", messages.get(1).at("/result/protocolVersion").asText());
        assertEquals(-32600, messages.get(2).at("/error/code").asInt());
        var tools = messages.get(3).at("/result/tools");
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> {
            names.add(tool.path("name").asText());
            assertTrue(tool.at("/annotations/readOnlyHint").booleanValue());
            assertFalse(tool.at("/annotations/openWorldHint").booleanValue());
        });
        assertEquals(List.of("rehearsal_summary", "list_changes", "inspect_change"), names);
        assertEquals(-32600, messages.get(4).at("/error/code").asInt());
        var fallback = exchange(JSON.readTree(REPORT), init("future").getBytes(UTF_8));
        assertEquals("2025-11-25", fallback.getFirst().at("/result/protocolVersion").asText());
    }

    @Test void pagesIdentifiersAndReturnsExactSnapshots() throws Exception {
        JsonNode report = JSON.readTree(REPORT);
        var replies = session(report, call("rehearsal_summary", "{}"),
                call("list_changes", "{\"stage\":\"forward\",\"limit\":1}"),
                call("list_changes", "{\"stage\":\"forward\",\"offset\":1}"),
                call("list_changes", "{\"stage\":\"forward\",\"offset\":2147483647}"),
                call("inspect_change", "{\"stage\":\"forward\",\"kind\":\"view\",\"name\":\"orders view\"}"));
        JsonNode summary = replies.get(0).at("/result/structuredContent/stages");
        assertEquals(2, summary.at("/forward/changeCount").intValue());
        assertTrue(summary.at("/rollback/changeCount").isNull());
        assertEquals("No rollback script supplied", summary.at("/rollback/reason").textValue());
        JsonNode first = replies.get(1).at("/result/structuredContent");
        assertEquals(2, first.get("total").intValue());
        assertEquals(1, first.get("nextOffset").intValue());
        assertEquals(JSON.readTree("{\"kind\":\"table\",\"name\":\"orders\",\"change\":\"modified\"}"),
                first.at("/changes/0"));
        assertTrue(replies.get(2).at("/result/structuredContent/nextOffset").isNull());
        assertTrue(replies.get(3).at("/result/structuredContent/changes").isEmpty());
        JsonNode inspected = replies.get(4).at("/result/structuredContent");
        assertEquals(report.at("/stages/forward/changes/1"), inspected.path("change"));
        assertEquals(inspected, JSON.readTree(replies.get(4).at("/result/content/0/text").textValue()));
    }

    @Test void distinguishesMissingEvidenceFromCompletedEmptyComparison() throws Exception {
        ObjectNode report = (ObjectNode) JSON.readTree(REPORT);
        ((ObjectNode) report.at("/stages")).remove("rollback");
        ObjectNode forward = (ObjectNode) report.at("/stages/forward");
        forward.putArray("changes");
        forward.put("status", "FAIL");
        var results = session(report, call("list_changes", "{\"stage\":\"rollback\"}"),
                call("list_changes", "{\"stage\":\"forward\"}"), call("rehearsal_summary", "{}"));
        assertTrue(results.get(0).at("/result/isError").booleanValue());
        assertTrue(results.get(0).at("/result/content/0/text").textValue().contains("NOT_RUN"));
        assertEquals(0, results.get(1).at("/result/structuredContent/total").intValue());
        assertEquals("NOT_RUN", results.get(2).at("/result/structuredContent/stages/rollback/status").textValue());
        forward.remove("changes");
        assertTrue(session(report, call("list_changes", "{\"stage\":\"forward\"}"))
                .getFirst().at("/result/isError").booleanValue());
    }

    @Test void rejectsInvalidToolsAndArgumentsWithoutLosingSession() throws Exception {
        var messages = session(JSON.readTree(REPORT), call("run_sql", "{}"),
                request("database/open", JSON.createObjectNode()),
                request("tools/call", JSON.createObjectNode().put("name", "list_changes").put("arguments", "bad")),
                call("list_changes", "{\"stage\":\"forward\",\"offset\":-1}"),
                call("list_changes", "{\"stage\":\"forward\",\"limit\":101}"),
                call("list_changes", "{\"stage\":\"forward\",\"limit\":1.5}"),
                call("list_changes", "{\"stage\":\"forward\",\"offset\":2147483648}"),
                call("list_changes", "{\"stage\":\"forward\",\"path\":\"other.db\"}"),
                call("inspect_change", "{\"stage\":\"forward\",\"kind\":\"table\",\"name\":\"Orders\"}"),
                request("ping", JSON.createObjectNode()));
        assertEquals(-32602, messages.get(0).at("/error/code").intValue());
        assertEquals(-32601, messages.get(1).at("/error/code").intValue());
        assertEquals(-32602, messages.get(2).at("/error/code").intValue());
        for (JsonNode message : messages.subList(3, 9)) assertTrue(message.at("/result/isError").booleanValue());
        assertEquals(JSON.createObjectNode(), messages.getLast().get("result"));
    }

    @Test void rejectsMalformedAndOversizedFramesThenResynchronizes() throws Exception {
        var input = new ByteArrayOutputStream();
        input.write((" ".repeat(1024 * 1024 + 1) + "\n").getBytes(UTF_8));
        input.write(new byte[] {(byte) 0xc3, 10});
        input.write("{}{}\n{\"jsonrpc\":\"1\",\"jsonrpc\":\"2\"}\n".getBytes(UTF_8));
        input.write(request("ping", JSON.createObjectNode()).getBytes(UTF_8));
        var messages = exchange(JSON.readTree(REPORT), input.toByteArray());
        assertEquals(5, messages.size());
        for (JsonNode message : messages.subList(0, 4)) {
            assertEquals(-32700, message.at("/error/code").intValue());
            assertTrue(message.get("id").isNull());
        }
        assertEquals("request", messages.getLast().path("id").textValue());
    }
}
