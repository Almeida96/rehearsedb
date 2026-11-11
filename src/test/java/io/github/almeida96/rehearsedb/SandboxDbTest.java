package io.github.almeida96.rehearsedb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxDbTest {
    @TempDir Path dir;

    private Connection seeded(Path path) throws SQLException {
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + path.toUri().toASCIIString());
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE orders(id INTEGER PRIMARY KEY, note TEXT UNIQUE)");
            s.executeUpdate("INSERT INTO orders VALUES(1, 'original')");
        }
        return db;
    }

    private Path script(String sql) throws IOException {
        return Files.writeString(dir.resolve("migration.sql"), sql);
    }

    private static String query(Connection db, String query) throws SQLException {
        try (Statement s = db.createStatement(); ResultSet rows = s.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    @Test void capturesCommittedWalWhileOriginalConnectionIsOpen() throws Exception {
        Path source = dir.resolve("orders #1.db");
        Path copy = dir.resolve("copy.db");
        try (Connection original = seeded(source); Statement s = original.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA wal_autocheckpoint=0");
            s.executeUpdate("INSERT INTO orders VALUES (2, 'committed in WAL')");
            original.setAutoCommit(false);
            s.executeUpdate("INSERT INTO orders VALUES (3, 'uncommitted')");
            assertTrue(Files.size(Path.of(source + "-wal")) > 0);
            SandboxDb.copySnapshot(source, copy);
            try (Connection db = SandboxDb.openCopy(copy)) {
                assertEquals("2", query(db, "SELECT count(*) FROM orders"));
                SandboxDb.apply(db, script("DELETE FROM orders WHERE id=2"));
                assertEquals("1", query(db, "SELECT count(*) FROM orders"));
            }
            original.rollback();
            assertEquals("2", query(original, "SELECT count(*) FROM orders"));
        }
    }

    @Test void leavesExistingFilesAloneAndRejectsMissingSource() throws Exception {
        Path source = dir.resolve("source.db");
        Path target = dir.resolve("target.db");
        Files.writeString(target, "preserve me");
        try (Connection original = seeded(source)) {
            assertThrows(FileAlreadyExistsException.class, () -> SandboxDb.copySnapshot(source, target));
            assertThrows(FileAlreadyExistsException.class, () -> SandboxDb.copySnapshot(source, source));
            assertEquals("original", query(original, "SELECT note FROM orders"));
        }
        assertEquals("preserve me", Files.readString(target));
        Path missing = dir.resolve("missing.db");
        Path output = dir.resolve("new.db");
        assertThrows(IOException.class, () -> SandboxDb.copySnapshot(missing, output));
        assertThrows(IOException.class, () -> SandboxDb.openCopy(missing));
        assertFalse(Files.exists(missing));
        assertFalse(Files.exists(output));
        Files.writeString(source, "corrupt test input");
        assertThrows(SQLException.class, () -> SandboxDb.copySnapshot(source, output));
        assertFalse(Files.exists(output));
    }

    @Test void executesTriggersQuotedSemicolonsAndAllStatements() throws Exception {
        Path source = dir.resolve("sample.db");
        try (Connection ignored = seeded(source); Connection db = SandboxDb.openCopy(source)) {
            SandboxDb.apply(db, script("""
                    /* header; */ CREATE TABLE "audit;log"(payload TEXT);
                    CREATE TRIGGER audit_order AFTER INSERT ON orders BEGIN
                      INSERT INTO "audit;log" VALUES(CASE WHEN NEW.id > 0 THEN 'ok;done' ELSE 'no' END);
                      -- a comment containing ; END; and 'unfinished
                      INSERT INTO "audit;log" VALUES('it''s;recorded');
                    END;
                    INSERT INTO orders VALUES(2, 'first;order');
                    UPDATE orders SET note = 'updated' WHERE id = 2
                    """));
            assertEquals("ok;done|it's;recorded", query(db, "SELECT group_concat(payload,'|') FROM \"audit;log\""));
            assertEquals("updated", query(db, "SELECT note FROM orders WHERE id=2"));
        }
    }

    @Test void rollsBackTheWholeScriptAndReportsFailingLine() throws Exception {
        Path file = dir.resolve("sample.db");
        try (Connection ignored = seeded(file); Connection db = SandboxDb.openCopy(file)) {
            SQLException failure = assertThrows(SQLException.class, () -> SandboxDb.apply(db, script("""
                    CREATE TABLE intermediate(x);
                    -- following row duplicates the primary key
                    INSERT INTO orders VALUES(1,'duplicate');
                    """)));
            assertTrue(failure.getMessage().contains("migration.sql:3:"), failure::getMessage);
            assertEquals("0", query(db, "SELECT count(*) FROM sqlite_schema WHERE name='intermediate'"));
            SandboxDb.apply(db, script("INSERT INTO orders VALUES(2,'retry')"));
            assertEquals("2", query(db, "SELECT count(*) FROM orders"));
        }
    }

    @Test void migrationCannotOpenExternalFilesOrManageTheTransaction() throws Exception {
        Path file = dir.resolve("sample.db");
        Path escaped = dir.resolve("escaped.db");
        String filename = "'" + escaped.toString().replace("'", "''") + "'";
        List<String> forbidden = List.of("ATTACH DATABASE " + filename + " AS other",
                "VACUUM INTO " + filename, "COMMIT", "PRAGMA writable_schema=ON",
                "RESTORE FROM " + filename, "CREATE TEMP TABLE x(y)",
                "CREATE TABLE \"temp\".x(y)", "CREATE VIRTUAL TABLE x USING fts5(y)",
                "SELECT load_extension(" + filename + ")",
                "SELECT $parameter(comment--); COMMIT; VACUUM INTO " + filename);
        try (Connection ignored = seeded(file); Connection db = SandboxDb.openCopy(file)) {
            for (String command : forbidden) {
                assertThrows(SQLException.class,
                        () -> SandboxDb.apply(db, script("CREATE TABLE transient_table(x);\n" + command)), command);
                assertEquals("0", query(db, "SELECT count(*) FROM sqlite_schema WHERE name='transient_table'"));
                assertFalse(Files.exists(escaped));
            }
        }
    }

    @Test void malformedAndEmptyInputCannotCommitPrecedingWork() throws Exception {
        Path file = dir.resolve("sample.db");
        try (Connection ignored = seeded(file); Connection db = SandboxDb.openCopy(file)) {
            for (String tail : List.of("/* missing end", "INSERT INTO orders VALUES (3, 'unfinished",
                    "CREATE TRIGGER accidental AFTER INSERT ON orders BEGIN SELECT 1;")) {
                assertThrows(SQLException.class,
                        () -> SandboxDb.apply(db, script("UPDATE orders SET note='modified';\n" + tail)));
                assertEquals("original", query(db, "SELECT note FROM orders"));
            }
            assertThrows(SQLException.class, () -> SandboxDb.apply(db, script("-- no migration\n;;")));
        }
    }
}
