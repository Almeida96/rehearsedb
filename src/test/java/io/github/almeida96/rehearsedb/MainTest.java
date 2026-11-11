package io.github.almeida96.rehearsedb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path temp;

    private int cli(String... args) {
        var buffer = new ByteArrayOutputStream();
        try (var capture = new PrintStream(buffer)) {
            return Main.execute(args, new ByteArrayInputStream(new byte[0]), capture, capture);
        }
    }

    @Test void demoDistinguishesRestoredColumnsFromRestoredValues() throws Exception {
        Path dir = temp.resolve("demo");
        assertEquals(0, cli("demo", "--out", dir.toString()));
        var safe = Fileset.json(dir.resolve("reversible/report.json"), Fileset.REPORT_LIMIT);
        var lossy = Fileset.json(dir.resolve("lossy/report.json"), Fileset.REPORT_LIMIT);
        assertEquals("PASS", safe.path("status").asText());
        assertTrue(safe.at("/stages/rollback/changes").isEmpty());
        assertEquals("FAIL", lossy.path("status").asText());
        var changes = lossy.at("/stages/rollback/changes");
        assertEquals(1, changes.size());
        var change = changes.get(0);
        assertEquals("shipments", change.path("name").asText());
        assertEquals(change.at("/before/schemaSha256"), change.at("/after/schemaSha256"));
        assertEquals(3, change.at("/after/rows").asInt());
        assertNotEquals(change.at("/before/dataSha256"), change.at("/after/dataSha256"));
        assertFalse(lossy.toString().contains("Use loading bay 3"));
        assertTrue(Files.readString(dir.resolve("lossy/report.html")).contains("row contents"));
    }

    @Test void distinguishesStageFailuresFromMissingInputsAndProtectsOutput() throws Exception {
        Path fixture = temp.resolve("fixture.db");
        try (var db = SandboxDb.openCopy(Files.createFile(fixture)); var sql = db.createStatement()) {
            sql.execute("CREATE TABLE items(id INTEGER PRIMARY KEY, tag TEXT)");
            sql.execute("INSERT INTO items VALUES(1, 'x'), (2,'x'), (3,NULL)");
        }
        byte[] original = Files.readAllBytes(fixture);
        Path up = Files.writeString(temp.resolve("up.sql"), "CREATE INDEX by_tag ON items(tag);");
        Path down = Files.writeString(temp.resolve("down.sql"), "DROP INDEX by_tag;");
        Path checks = Files.writeString(temp.resolve("checks.json"), """
                {"version":1,"forward":[{"type":"unique","table":"items","column":"tag"},
                {"type":"no-nulls","table":"items","column":"tag"},
                {"type":"no-nulls","table":"items","column":"not_a_column"}]}
                """);
        Path out = temp.resolve("checked");
        assertEquals(1, cli("run","--db",fixture.toString(),"--up",up.toString(),"--down",down.toString(),"--checks",checks.toString(),"--out",out.toString()));
        var report = Fileset.json(out.resolve("report.json"), Fileset.REPORT_LIMIT);
        assertEquals("checks.json", report.at("/inputs/checks/file").asText());
        assertEquals(Fileset.digest(Files.readAllBytes(checks)), report.at("/inputs/checks/sha256").asText());
        assertEquals("FAIL", report.at("/stages/forward/status").asText());
        assertEquals("PASS", report.at("/stages/rollback/status").asText());
        assertEquals(1, report.at("/stages/forward/checks/0/violations").asInt());
        assertEquals(1, report.at("/stages/forward/checks/1/violations").asInt());
        assertFalse(report.at("/stages/forward/checks/2/ok").asBoolean());
        byte[] saved = Files.readAllBytes(out.resolve("report.json"));
        assertEquals(2, cli("run","--db",fixture.toString(),"--up",up.toString(),"--out",out.toString()));
        assertArrayEquals(saved, Files.readAllBytes(out.resolve("report.json")));
        Path only = temp.resolve("forward-only");
        assertEquals(3, cli("run","--db",fixture.toString(),"--up",up.toString(),"--out",only.toString()));
        assertEquals("FORWARD_ONLY", Fileset.json(only.resolve("report.json"),Fileset.REPORT_LIMIT).path("status").asText());
        assertArrayEquals(original, Files.readAllBytes(fixture));
    }

    @Test void abortedMigrationDoesNotProduceRollbackEvidence() throws Exception {
        Path dbFile = Files.createFile(temp.resolve("db.sqlite"));
        try (var db = SandboxDb.openCopy(dbFile); var st = db.createStatement()) { st.execute("CREATE TABLE t(x)"); }
        Path up = Files.writeString(temp.resolve("up.sql"), "CREATE TABLE a(x); INSERT INTO missing VALUES(1);");
        Path down = Files.writeString(temp.resolve("down.sql"), "DROP TABLE a;");
        Path out = temp.resolve("out");
        assertEquals(1, cli("run","--db",dbFile.toString(),"--up",up.toString(),"--down",down.toString(),"--out",out.toString()));
        var report = Fileset.json(out.resolve("report.json"),Fileset.REPORT_LIMIT);
        assertTrue(report.at("/stages/forward").has("error"));
        assertEquals("NOT_RUN",report.at("/stages/rollback/status").asText());
    }
}
