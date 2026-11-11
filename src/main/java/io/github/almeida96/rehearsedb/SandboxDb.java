package io.github.almeida96.rehearsedb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteLimits;
import org.sqlite.SQLiteOpenMode;
import org.sqlite.core.DB;

final class SandboxDb {
    private SandboxDb() {}

    static void copySnapshot(Path source, Path destination) throws IOException, SQLException {
        Path output = destination.toAbsolutePath().normalize();
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            if (Files.exists(Path.of(output + suffix), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Destination already has a SQLite sidecar: " + output.getFileName() + suffix);
            }
        }
        boolean reserved = false;
        try (Connection input = connectExisting(source, true)) {
            // Hold one read snapshot so a busy WAL writer cannot restart each backup step.
            input.setAutoCommit(false);
            try (Statement pin = input.createStatement();
                 ResultSet schema = pin.executeQuery("SELECT rootpage FROM main.sqlite_schema LIMIT 1")) {
                schema.next();
            }
            // Reserve the name before handing it to sqlite3_backup, which can overwrite files.
            Files.createFile(output);
            reserved = true;
            DB database = input.unwrap(SQLiteConnection.class).getDatabase();
            int rc = database.backup("main", output.toString(), null, 100, 50, 256);
            if (rc != 0) {
                throw DB.newSQLException(rc, "SQLite online backup failed");
            }
        } catch (SQLException | IOException | RuntimeException e) {
            if (reserved) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
    }

    static Connection openCopy(Path existingDisposableCopy) throws SQLException, IOException {
        Connection connection = connectExisting(existingDisposableCopy, false);
        try {
            SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
            sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_ATTACHED, 0);
            try (Statement config = connection.createStatement()) {
                config.execute("PRAGMA trusted_schema = OFF");
                config.execute("PRAGMA foreign_keys = ON");
                config.execute("PRAGMA temp_store = FILE");
            }
            return connection;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException close) {
                e.addSuppressed(close);
            }
            throw e;
        }
    }

    private static Connection connectExisting(Path path, boolean readOnly) throws IOException, SQLException {
        Path resolved = path.toRealPath();
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("Expected an existing database file: " + path.getFileName());
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(readOnly);
        config.resetOpenMode(SQLiteOpenMode.CREATE);
        config.enableLoadExtension(false);
        config.setBusyTimeout(5000);
        return config.createConnection("jdbc:sqlite:" + resolved.toUri().toASCIIString());
    }

    static void apply(Connection connection, Path script) throws SQLException, IOException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("The migration runner requires its own transaction");
        }
        MigrationScript parsed = MigrationScript.read(script);
        DB database = connection.unwrap(SQLiteConnection.class).getDatabase();
        // Use one prepared statement per migration operation, including a whole trigger.
        // Only the runner's transaction controls reach sqlite3_exec.
        execute(database, "BEGIN IMMEDIATE");
        try {
            parsed.execute(sql -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    if (statement.execute()) {
                        try (ResultSet result = statement.getResultSet()) {
                            while (result.next()) {
                                // Step through SELECT and RETURNING so deferred errors surface.
                            }
                        }
                    }
                }
            });
            execute(database, "COMMIT");
        } catch (SQLException | RuntimeException e) {
            try {
                execute(database, "ROLLBACK");
            } catch (SQLException rollback) {
                // An ON CONFLICT ROLLBACK clause may already have ended the transaction.
                e.addSuppressed(rollback);
            }
            throw e;
        }
    }

    private static void execute(DB database, String sql) throws SQLException {
        int rc = database._exec(sql);
        if (rc != 0) {
            throw DB.newSQLException(rc, "Executing SQLite statement");
        }
    }
}
