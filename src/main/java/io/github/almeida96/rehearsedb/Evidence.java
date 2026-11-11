package io.github.almeida96.rehearsedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sqlite.Function;
import org.sqlite.core.Codes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

final class Evidence {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String ROW_HASH = "rehearsedb_hash_record";
    private static final int HASH_FANOUT = 64;
    private static final HexFormat HEX = HexFormat.of();

    private Evidence() {}

    static ObjectNode capture(Connection db) throws SQLException {
        // Hold one read snapshot while schema and table contents are examined.
        boolean startTransaction = db.getAutoCommit();
        if (startTransaction) db.setAutoCommit(false);
        try {
            Function.create(db, ROW_HASH, new RecordHash(), -1, Function.FLAG_DETERMINISTIC);
            try {
                return captureMain(db);
            } finally {
                Function.destroy(db, ROW_HASH);
            }
        } finally {
            if (startTransaction) {
                db.rollback();
                db.setAutoCommit(true);
            }
        }
    }

    private static ObjectNode captureMain(Connection db) throws SQLException {
        Map<String, Boolean> withoutRowid = new TreeMap<>();
        try (Statement stmt = db.createStatement(); ResultSet rows = stmt.executeQuery("PRAGMA main.table_list")) {
            while (rows.next()) {
                String type = rows.getString("type");
                String name = rows.getString("name");
                if ("virtual".equals(type) || "shadow".equals(type)) {
                    throw new SQLException("Virtual tables are outside the fingerprint scope: " + name);
                }
                if ("table".equals(type)) withoutRowid.put(name, rows.getBoolean("wr"));
            }
        }

        List<ObjectNode> objects = new ArrayList<>();
        objects.add(metadata(db, "application_id"));
        objects.add(metadata(db, "user_version"));

        String sql = "SELECT type, name, sql FROM main.sqlite_schema "
                + "WHERE name NOT GLOB 'sqlite_*' OR name = 'sqlite_sequence' "
                + "ORDER BY type COLLATE BINARY, name COLLATE BINARY";
        try (Statement stmt = db.createStatement(); ResultSet rows = stmt.executeQuery(sql)) {
            while (rows.next()) {
                ObjectNode object = JSON.objectNode();
                object.put("kind", rows.getString(1));
                object.put("name", rows.getString(2));
                object.put("schemaSha256", HEX.formatHex(digest().digest(rows.getBytes(3))));
                objects.add(object);
            }
        }

        ObjectNode snapshot = JSON.objectNode();
        snapshot.put("algorithm", "sqlite-logical-v1");
        ArrayNode entries = snapshot.putArray("objects");
        MessageDigest summary = digest();
        for (ObjectNode object : objects) {
            if ("table".equals(object.path("kind").asText())) {
                String name = object.path("name").asText();
                fingerprintTable(db, name, Boolean.TRUE.equals(withoutRowid.get(name)), object);
            }
            entries.add(object);
            // Each component has a fixed-size digest, so names with delimiters are unambiguous.
            summary.update(digest().digest(object.toString().getBytes(StandardCharsets.UTF_8)));
        }
        snapshot.put("sha256", HEX.formatHex(summary.digest()));
        return snapshot;
    }

    private static ObjectNode metadata(Connection db, String name) throws SQLException {
        try (Statement stmt = db.createStatement(); ResultSet row = stmt.executeQuery("PRAGMA main." + name)) {
            if (!row.next()) throw new SQLException("Cannot read database metadata: " + name);
            return JSON.objectNode().put("kind", "metadata").put("name", name).put("value", row.getLong(1));
        }
    }

    private static void fingerprintTable(Connection db, String name, boolean withoutRowid, ObjectNode object)
            throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement stmt = db.createStatement();
             ResultSet rows = stmt.executeQuery("PRAGMA main.table_xinfo(" + identifier(name) + ")")) {
            while (rows.next()) names.add(rows.getString("name"));
        }
        if (names.isEmpty()) throw new SQLException("Cannot read table columns: " + name);

        List<String> fields = new ArrayList<>();
        if (!withoutRowid) {
            String rowid = List.of("rowid", "_rowid_", "oid").stream()
                    .filter(alias -> names.stream().noneMatch(nameInSchema ->
                            nameInSchema.equalsIgnoreCase(alias) && nameInSchema.chars().allMatch(c -> c < 128)))
                    .findFirst().orElseThrow(() -> new SQLException("All rowid aliases are shadowed in table " + name));
            fields.add(identifier(rowid));
        }
        for (String column : names) fields.add(identifier(column));

        MessageDigest table = digest();
        long count = 0;
        // SQLite sorts only fixed-size hashes. The read order and the table's collations
        // cannot break ties between distinct records. Duplicate hashes remain in the stream.
        String query = "SELECT " + rowExpression(fields) + " FROM main." + identifier(name) + " ORDER BY 1";
        try (Statement stmt = db.createStatement(); ResultSet rows = stmt.executeQuery(query)) {
            while (rows.next()) {
                table.update(rows.getBytes(1));
                count++;
            }
        }
        object.put("rows", count);
        object.put("dataSha256", HEX.formatHex(table.digest()));
    }

    private static String rowExpression(List<String> inputs) {
        List<String> level = inputs;
        // Nested groups keep wide tables below SQLite's function argument limit.
        while (level.size() > HASH_FANOUT) {
            List<String> parents = new ArrayList<>();
            for (int from = 0; from < level.size(); from += HASH_FANOUT) {
                parents.add(ROW_HASH + "(" + String.join(",", level.subList(from, Math.min(from + HASH_FANOUT, level.size()))) + ")");
            }
            level = parents;
        }
        return ROW_HASH + "(" + String.join(",", level) + ")";
    }

    private static String identifier(String text) {
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class RecordHash extends Function {
        private final MessageDigest hash = digest();
        private final byte[] word = new byte[Long.BYTES];

        @Override
        protected void xFunc() throws SQLException {
            hash.reset();
            addLong(args());
            for (int i = 0; i < args(); i++) {
                int type = value_type(i);
                hash.update((byte) type);
                switch (type) {
                    case Codes.SQLITE_NULL -> { }
                    case Codes.SQLITE_INTEGER -> addLong(value_long(i));
                    case Codes.SQLITE_FLOAT -> addLong(Double.doubleToRawLongBits(value_double(i)));
                    case Codes.SQLITE_BLOB, Codes.SQLITE_TEXT -> {
                        // Read the bytes before asking SQLite to perform any text conversion.
                        byte[] bytes = value_blob(i);
                        int length = bytes == null ? 0 : bytes.length;
                        addLong(length);
                        if (length > 0) hash.update(bytes);
                    }
                    default -> throw new SQLException("Unsupported SQLite storage class: " + type);
                }
            }
            result(hash.digest());
        }

        private void addLong(long value) {
            for (int i = word.length - 1; i >= 0; i--) {
                word[i] = (byte) value;
                value >>>= Byte.SIZE;
            }
            hash.update(word);
        }
    }

    static ArrayNode diff(JsonNode before, JsonNode after) {
        Map<String, JsonNode> left = byKey(before);
        Map<String, JsonNode> right = byKey(after);
        TreeSet<String> keys = new TreeSet<>(left.keySet());
        keys.addAll(right.keySet());
        ArrayNode changes = JSON.arrayNode();
        for (String key : keys) {
            JsonNode old = left.get(key);
            JsonNode next = right.get(key);
            if (old != null && old.equals(next)) continue;
            JsonNode object = old == null ? next : old;
            ObjectNode change = changes.addObject();
            change.put("kind", object.path("kind").asText());
            change.put("name", object.path("name").asText());
            change.put("change", old == null ? "added" : next == null ? "removed" : "modified");
            change.set("before", old == null ? JSON.nullNode() : old);
            change.set("after", next == null ? JSON.nullNode() : next);
        }
        return changes;
    }

    private static Map<String, JsonNode> byKey(JsonNode snapshot) {
        Map<String, JsonNode> objects = new TreeMap<>();
        for (JsonNode obj : snapshot.path("objects")) {
            objects.put(obj.path("kind").asText() + ":" + obj.path("name").asText(), obj);
        }
        return objects;
    }

    static ObjectNode health(Connection db) throws SQLException {
        long errors = 0;
        long violations = 0;
        try (Statement stmt = db.createStatement();
             ResultSet checks = stmt.executeQuery("PRAGMA main.integrity_check(2147483647)")) {
            while (checks.next()) {
                if (!"ok".equals(checks.getString(1))) errors++;
            }
        }
        try (Statement stmt = db.createStatement(); ResultSet checks = stmt.executeQuery("PRAGMA main.foreign_key_check")) {
            while (checks.next()) violations++;
        }
        return JSON.objectNode().put("ok", errors == 0 && violations == 0)
                .put("integrityErrors", errors).put("foreignKeyViolations", violations);
    }
}
