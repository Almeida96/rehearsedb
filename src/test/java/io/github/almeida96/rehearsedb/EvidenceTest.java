package io.github.almeida96.rehearsedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceTest {
    private Connection memory() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    private void run(Connection db, String... commands) throws SQLException {
        try (Statement stmt = db.createStatement()) {
            for (String sql : commands) stmt.execute(sql);
        }
    }

    private JsonNode entry(ObjectNode snapshot, String kind, String name) {
        for (JsonNode obj : snapshot.path("objects")) {
            if (kind.equals(obj.path("kind").asText()) && name.equals(obj.path("name").asText())) return obj;
        }
        throw new AssertionError("Missing entry " + name);
    }

    @Test void storageClassesAndFramingSurviveHashing() throws Exception {
        try (Connection db = memory()) {
            run(db, "CREATE TABLE sample(a,b)", "INSERT INTO sample VALUES (NULL,'')");
            var hashes = new HashSet<String>();
            for (String update : List.of("a=NULL,b=''", "a='',b=''", "a=X'',b=''", "a=1,b=''", "a=1.0,b=''",
                    "a='1',b=''", "a=X'31',b=''", "a='a',b='bc'", "a='ab',b='c'",
                    "a=CAST(X'FF' AS TEXT),b=''", "a=CAST(X'FE' AS TEXT),b=''")) {
                run(db, "UPDATE sample SET " + update);
                var mark = entry(Evidence.capture(db), "table", "sample").get("dataSha256").asText();
                assertTrue(hashes.add(mark), "Fingerprint collision for " + update);
            }
        }
    }

    @Test void independentOfTraversalOrderWithMixedCollationsAndQuotedIdentifiers() throws Exception {
        String create = "CREATE TABLE \"odd 'table\"\"name\"(id TEXT PRIMARY KEY, val COLLATE NOCASE, "
                + "padded TEXT COLLATE RTRIM, derived TEXT GENERATED ALWAYS AS (val || padded) VIRTUAL) WITHOUT ROWID";
        String insert = "INSERT INTO \"odd 'table\"\"name\"(id,val,padded) VALUES ";
        try (Connection a = memory(); Connection b = memory()) {
            run(a, create, insert + "('x','A','q '),('y','a','q'),('z',4,'x')");
            run(b, create, insert + "('z',4,'x'),('y','a','q'),('x','A','q ')", "PRAGMA reverse_unordered_selects=ON");
            ObjectNode baseline = Evidence.capture(a);
            assertEquals(baseline, Evidence.capture(b));
            run(b, "UPDATE \"odd 'table\"\"name\" SET val='a' WHERE id='x'");
            var changes = Evidence.diff(baseline, Evidence.capture(b));
            assertEquals(1, changes.size());
            assertEquals("modified", changes.get(0).get("change").asText());
            assertEquals(3L, changes.get(0).path("before").path("rows").asLong());
        }
    }

    @Test void tracksRowidAliasesAndDuplicateMultiplicity() throws Exception {
        try (Connection db = memory()) {
            run(db, "CREATE TABLE ledger(rowid TEXT, value BLOB)", "INSERT INTO ledger VALUES('visible',X'AA')");
            var baseline = Evidence.capture(db);
            run(db, "UPDATE ledger SET _rowid_=12");
            assertFalse(Evidence.diff(baseline, Evidence.capture(db)).isEmpty());
            run(db, "UPDATE ledger SET _rowid_=1", "INSERT INTO ledger SELECT * FROM ledger");
            var change = Evidence.diff(baseline, Evidence.capture(db)).get(0);
            assertEquals(1L, change.path("before").path("rows").asLong());
            assertEquals(2L, change.path("after").path("rows").asLong());
            run(db, "DELETE FROM ledger WHERE _rowid_=2");
            assertEquals(baseline, Evidence.capture(db));
            // SQLite folds ASCII identifier letters. A dotless i does not mask the hidden rowid.
            run(db, "CREATE TABLE unicode_name(row\u0131d, _rowid_, OID)", "INSERT INTO unicode_name DEFAULT VALUES");
            var withUnicode = Evidence.capture(db);
            run(db, "UPDATE unicode_name SET rowid = 2");
            assertFalse(Evidence.diff(withUnicode, Evidence.capture(db)).isEmpty());
        }
    }

    @Test void tracksSequenceMetadataAndSchemaObjectsWithoutExportingValuesOrSql() throws Exception {
        try (Connection db = memory()) {
            run(db, "CREATE TABLE queue(id INTEGER PRIMARY KEY AUTOINCREMENT, tag TEXT DEFAULT 'secret-default')",
                    "INSERT INTO queue(tag) VALUES('secret-record')");
            var baseline = Evidence.capture(db);
            assertFalse(baseline.toString().contains("secret-"));
            run(db, "INSERT INTO queue(tag) VALUES('more')", "DELETE FROM queue WHERE id=2",
                    "CREATE INDEX ix_tag ON queue(tag)",
                    "CREATE VIEW current_queue AS SELECT * FROM queue",
                    "CREATE TRIGGER no_remove BEFORE DELETE ON queue BEGIN SELECT RAISE(ABORT,'secret-trigger'); END",
                    "PRAGMA user_version=18", "PRAGMA application_id=42");
            var after = Evidence.capture(db);
            assertFalse(after.toString().contains("secret-"));
            var changes = Evidence.diff(baseline, after);
            assertEquals(6, changes.size());
            assertNotEquals(entry(baseline, "table", "sqlite_sequence"), entry(after, "table", "sqlite_sequence"));
            assertEquals(entry(baseline, "table", "queue"), entry(after, "table", "queue"));
            assertEquals(18, entry(after, "metadata", "user_version").path("value").asInt());
            assertEquals(42, entry(after, "metadata", "application_id").path("value").asInt());
            for (JsonNode change : changes) {
                if ("added".equals(change.path("change").asText())) {
                    assertTrue(change.get("before").isNull());
                    assertTrue(change.get("after").isObject());
                }
            }
            var reversed = Evidence.diff(after, baseline);
            assertTrue(java.util.stream.StreamSupport.stream(reversed.spliterator(), false)
                    .anyMatch(change -> change.path("change").asText().equals("removed") && change.get("after").isNull()));
        }
    }

    @Test void schemaDigestChangesEvenWhenDataMatches() throws Exception {
        try (Connection db = memory()) {
            run(db, "CREATE TABLE notes(value TEXT DEFAULT 'blue')");
            var before = Evidence.capture(db);
            run(db, "DROP TABLE notes", "CREATE TABLE notes(value TEXT DEFAULT 'green')");
            JsonNode change = Evidence.diff(before, Evidence.capture(db)).get(0);
            assertEquals(change.path("before").path("dataSha256"), change.path("after").path("dataSha256"));
            assertNotEquals(change.path("before").path("schemaSha256"), change.path("after").path("schemaSha256"));
        }
    }

    @Test void supportsWideTablesAndPreservesCallerTransaction() throws Exception {
        try (Connection db = memory()) {
            String columns = IntStream.range(0, 120).mapToObj(i -> "c" + i).collect(Collectors.joining(","));
            run(db, "CREATE TABLE wide(" + columns + ")");
            var empty = Evidence.capture(db);
            assertTrue(db.getAutoCommit());
            db.setAutoCommit(false);
            run(db, "INSERT INTO wide(c119) VALUES('last')");
            var initial = Evidence.capture(db);
            assertFalse(db.getAutoCommit());
            run(db, "UPDATE wide SET c119='changed'");
            assertNotEquals(initial, Evidence.capture(db));
            db.rollback();
            assertEquals(empty, Evidence.capture(db));
        }
    }

    @Test void healthIncludesEveryForeignKeyViolationAndOmitsContents() throws Exception {
        try (Connection db = memory()) {
            run(db, "PRAGMA foreign_keys=OFF", "CREATE TABLE parent(id INTEGER PRIMARY KEY)",
                    "CREATE TABLE child(parent_id REFERENCES parent(id))", "INSERT INTO child VALUES (11),(12)");
            var health = Evidence.health(db);
            assertFalse(health.path("ok").asBoolean());
            assertEquals(2, health.path("foreignKeyViolations").asInt());
            assertEquals(0, health.path("integrityErrors").asInt());
            run(db, "INSERT INTO parent VALUES(11),(12)");
            assertTrue(Evidence.health(db).path("ok").asBoolean());
        }
    }

    @Test void rejectsVirtualTablesAndInaccessibleRowid() throws Exception {
        try (Connection db = memory()) {
            run(db, "CREATE VIRTUAL TABLE searchable USING fts5(body)");
            assertThrows(SQLException.class, () -> Evidence.capture(db));
            run(db, "DROP TABLE searchable", "CREATE TABLE masked(rowid, _rowid_, oid)");
            assertThrows(SQLException.class, () -> Evidence.capture(db));
            assertTrue(db.getAutoCommit());
        }
    }
}
