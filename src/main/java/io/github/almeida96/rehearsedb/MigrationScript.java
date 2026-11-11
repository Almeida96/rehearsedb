package io.github.almeida96.rehearsedb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/** Splits reviewed SQLite migrations at statement boundaries, preserving strings and trigger bodies. */
final class MigrationScript {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Set<String> COMMANDS = Set.of(
            "CREATE", "ALTER", "DROP", "INSERT", "REPLACE", "UPDATE", "DELETE", "WITH", "SELECT",
            "REINDEX", "ANALYZE");
    private static final Set<String> CREATE_TYPES = Set.of("TABLE", "UNIQUE", "INDEX", "TRIGGER", "VIEW");
    private final String sql;
    private final String file;
    private int offset;
    private int line = 1;

    @FunctionalInterface
    interface Executor {
        void run(String statement) throws SQLException;
    }

    private MigrationScript(String sql, String file) {
        this.sql = sql;
        this.file = file;
    }

    static MigrationScript read(Path path) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Migration exceeds 4 MiB: " + path.getFileName());
        }
        String sql = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (sql.indexOf('\0') >= 0) {
            throw new IOException("NUL byte in migration: " + path.getFileName());
        }
        if (sql.startsWith("\uFEFF")) sql = sql.substring(1);
        return new MigrationScript(sql, path.getFileName().toString());
    }

    void execute(Executor executor) throws SQLException {
        int executed = 0;
        while (true) {
            skipTrivia();
            if (offset == sql.length()) break;
            if (sql.charAt(offset) == ';') {
                advance();
                continue;
            }
            int start = offset;
            int startLine = line;
            String command = token();
            if (!COMMANDS.contains(command)) {
                throw failure(startLine, "Expected a migration DDL or DML statement");
            }
            consumeStatement(command);
            try {
                executor.run(sql.substring(start, offset));
            } catch (SQLException e) {
                throw new SQLException(file + ":" + startLine + ": " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
            }
            executed++;
        }
        if (executed == 0) throw failure(1, "Migration contains no statements");
    }

    private void consumeStatement(String command) throws SQLException {
        boolean needCreateType = command.equals("CREATE");
        boolean inTrigger = false;
        boolean afterSemicolon = false;
        boolean afterTriggerEnd = false;
        boolean afterTemp = false;
        while (true) {
            skipTrivia();
            if (offset == sql.length()) return;
            int tokenLine = line;
            String part = token();
            if (needCreateType) {
                if (!CREATE_TYPES.contains(part)) {
                    throw failure(tokenLine, "Supported CREATE targets are TABLE, INDEX, VIEW and TRIGGER");
                }
                inTrigger = part.equals("TRIGGER");
                needCreateType = false;
            }
            if (afterTemp && part.equals(".")) {
                throw failure(tokenLine, "The temp schema is outside the migration scope");
            }
            afterTemp = part.equals("TEMP") || part.equals("QUOTED_TEMP");
            if (part.equals(";")) {
                if (!inTrigger || afterTriggerEnd) return;
                afterSemicolon = true;
                afterTriggerEnd = false;
            } else {
                // In SQLite a trigger ends with a statement semicolon followed by END.
                // END within CASE expressions therefore never ends the trigger.
                afterTriggerEnd = inTrigger && afterSemicolon && part.equals("END");
                afterSemicolon = false;
            }
        }
    }

    private String token() throws SQLException {
        char first = sql.charAt(offset);
        int start = offset;
        int startLine = line;
        advance();
        // Standalone migrations have no parameter bindings. SQLite's Tcl-style
        // $name(suffix) parameters can contain punctuation and comment markers.
        if (first == '$' || first == '@' || first == ':' || first == '?' || first == '#') {
            throw failure(startLine, "Parameter placeholders are unsupported in migration files");
        }
        if (first == '\'' || first == '"' || first == '`' || first == '[') {
            char closing = first == '[' ? ']' : first;
            while (offset < sql.length()) {
                char c = sql.charAt(offset);
                advance();
                if (c != closing) continue;
                if (first != '[' && offset < sql.length() && sql.charAt(offset) == closing) {
                    advance();
                    continue;
                }
                return offset - start == 6 && sql.regionMatches(true, start + 1, "temp", 0, 4)
                        ? "QUOTED_TEMP" : "QUOTED";
            }
            throw failure(startLine, "Unterminated quoted value or identifier");
        }
        if (!isWord(first)) return String.valueOf(first);
        while (offset < sql.length() && isWord(sql.charAt(offset))) advance();
        return sql.substring(start, offset).toUpperCase(Locale.ROOT);
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c >= 128;
    }

    private void skipTrivia() throws SQLException {
        while (offset < sql.length()) {
            char c = sql.charAt(offset);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f') {
                advance();
            } else if (sql.startsWith("--", offset)) {
                while (offset < sql.length() && sql.charAt(offset) != '\n') advance();
            } else if (sql.startsWith("/*", offset)) {
                int startLine = line;
                offset += 2;
                while (offset < sql.length() && !sql.startsWith("*/", offset)) advance();
                if (offset == sql.length()) throw failure(startLine, "Unterminated block comment");
                offset += 2;
            } else if (c == '\uFEFF') {
                throw failure(line, "Unexpected byte-order mark within a migration");
            } else {
                return;
            }
        }
    }

    private void advance() {
        if (sql.charAt(offset++) == '\n') line++;
    }

    private SQLException failure(int atLine, String message) {
        return new SQLException(file + ":" + atLine + ": " + message);
    }
}
