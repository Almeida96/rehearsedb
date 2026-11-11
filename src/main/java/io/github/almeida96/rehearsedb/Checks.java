package io.github.almeida96.rehearsedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

record Checks(List<Rule> forward, List<Rule> rollback) {
    record Rule(String type, String table, String column) {}

    static Checks parse(JsonNode config) {
        fields(config, Set.of("version", "forward", "rollback"));
        if (!config.path("version").isIntegralNumber() || !config.get("version").asText().equals("1")) {
            throw new IllegalArgumentException("Expected checks version 1");
        }
        var checks = new Checks(rules(config.get("forward")), rules(config.get("rollback")));
        if (checks.forward.isEmpty() && checks.rollback.isEmpty()) throw new IllegalArgumentException("Checks file has no rules");
        return checks;
    }

    private static List<Rule> rules(JsonNode values) {
        if (values == null) return List.of();
        if (!values.isArray() || values.size() > 64) throw new IllegalArgumentException("Expected up to 64 rules per stage");
        var rules = new ArrayList<Rule>();
        for (JsonNode item : values) {
            String type = name(item, "type");
            String table = name(item, "table");
            switch (type) {
                case "row-count-unchanged" -> {
                    fields(item, Set.of("type", "table"));
                    rules.add(new Rule(type, table, null));
                }
                case "no-nulls", "unique" -> {
                    fields(item, Set.of("type", "table", "column"));
                    rules.add(new Rule(type, table, name(item, "column")));
                }
                default -> throw new IllegalArgumentException("Unknown check: " + type);
            }
        }
        return List.copyOf(rules);
    }

    private static void fields(JsonNode value, Set<String> allowed) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException("Expected a checks object");
        value.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown checks field: " + key);
        });
    }

    private static String name(JsonNode item, String field) {
        JsonNode node = item.path(field);
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > 255
                || node.textValue().indexOf(0) >= 0) throw new IllegalArgumentException("Invalid checks field: " + field);
        return node.textValue();
    }

    static ArrayNode run(Connection db, List<Rule> rules, ObjectNode baseline, ObjectNode current) throws SQLException {
        var results = Fileset.JSON.createArrayNode();
        for (Rule rule : rules) {
            var result = results.addObject().put("type", rule.type).put("table", rule.table);
            JsonNode table = table(current, rule.table);
            if (rule.column != null) result.put("column", rule.column);
            if (table == null) {
                result.put("ok", false).put("reason", "Table absent from this stage");
                continue;
            }
            if (rule.type.equals("row-count-unchanged")) {
                JsonNode before = table(baseline, rule.table);
                if (before == null) result.put("ok", false).put("reason", "Table absent from baseline");
                else {
                    long expected = before.required("rows").longValue(), actual = table.required("rows").longValue();
                    result.put("expectedRows", expected).put("actualRows", actual).put("ok", expected == actual);
                }
                continue;
            }
            try (var stmt = db.prepareStatement("SELECT 1 FROM pragma_table_xinfo(?) WHERE name = ?")) {
                stmt.setString(1, rule.table);
                stmt.setString(2, rule.column);
                try (var rows = stmt.executeQuery()) {
                    if (!rows.next()) {
                        result.put("ok", false).put("reason", "Column absent from this stage");
                        continue;
                    }
                }
            }
            String from = " FROM main." + quote(rule.table);
            String column = quote(rule.column);
            String sql = rule.type.equals("no-nulls")
                    ? "SELECT count(*)" + from + " WHERE " + column + " IS NULL"
                    : "SELECT count(*) FROM (SELECT 1" + from + " WHERE " + column + " IS NOT NULL GROUP BY " + column + " HAVING count(*) > 1)";
            try (var stmt = db.createStatement(); var rows = stmt.executeQuery(sql)) {
                rows.next();
                long count = rows.getLong(1);
                result.put("violations", count).put("ok", count == 0);
            }
        }
        return results;
    }

    private static JsonNode table(ObjectNode snapshot, String name) {
        for (JsonNode object : snapshot.required("objects")) {
            if (object.path("kind").asText().equals("table") && object.path("name").asText().equals(name)) return object;
        }
        return null;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
