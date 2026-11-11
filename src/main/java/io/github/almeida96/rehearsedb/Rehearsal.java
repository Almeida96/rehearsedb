package io.github.almeida96.rehearsedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

final class Rehearsal {
    private Rehearsal() {}

    static ObjectNode run(Path source, Path up, Path down, Path policy, Path work) throws Exception {
        var report = Fileset.JSON.createObjectNode().put("formatVersion", 1).put("status", "FAIL");
        ObjectNode inputs = report.putObject("inputs");
        Path forwardFile = freeze(up, work, inputs, "forward", Fileset.SCRIPT_LIMIT);
        Path rollbackFile = down == null ? null : freeze(down, work, inputs, "rollback", Fileset.SCRIPT_LIMIT);
        Checks checks = new Checks(List.of(), List.of());
        if (policy != null) {
            Path checksFile = freeze(policy, work, inputs, "checks", Fileset.POLICY_LIMIT);
            checks = Checks.parse(Fileset.json(checksFile, Fileset.POLICY_LIMIT));
            if (!checks.rollback().isEmpty() && down == null) throw new IllegalArgumentException("Rollback checks require --down");
        }
        ObjectNode stages = report.putObject("stages");
        ObjectNode forward = stages.putObject("forward").put("status", "NOT_RUN");
        ObjectNode rollback = stages.putObject("rollback").put("status", "NOT_RUN");
        if (down == null) rollback.put("reason", "No rollback script supplied");
        Path clone = work.resolve("snapshot.sqlite");
        SandboxDb.copySnapshot(source, clone);
        try (Connection db = SandboxDb.openCopy(clone)) {
            var baseline = report.putObject("baseline");
            ObjectNode health = Evidence.health(db);
            baseline.set("health", health);
            if (!health.required("ok").asBoolean()) {
                forward.put("reason", "Baseline failed integrity checks");
                if (down != null) rollback.put("reason", "Forward migration did not run");
                return report;
            }
            ObjectNode initial = Evidence.capture(db);
            baseline.put("sha256", initial.required("sha256").textValue());
            baseline.put("objects", initial.required("objects").size());
            boolean applied = stage(db, forwardFile, initial, checks.forward(), forward, false);
            if (rollbackFile != null) {
                if (applied) stage(db, rollbackFile, initial, checks.rollback(), rollback, true);
                else rollback.put("reason", "Forward migration failed before evidence could be captured");
            }
            if (passed(forward)) {
                if (rollbackFile == null) report.put("status", "FORWARD_ONLY");
                else if (passed(rollback)) report.put("status", "PASS");
            }
        }
        return report;
    }

    private static Path freeze(Path from, Path work, ObjectNode inputs, String key, int limit) throws IOException {
        byte[] bytes = Fileset.read(from, limit);
        Path to = Files.createDirectory(work.resolve(key)).resolve(from.getFileName());
        Files.write(to, bytes);
        inputs.putObject(key).put("file", from.getFileName().toString()).put("sha256", Fileset.digest(bytes));
        return to;
    }

    private static boolean stage(Connection db, Path script, ObjectNode initial, List<Checks.Rule> rules,
                                 ObjectNode stage, boolean requireRestore) {
        stage.put("status", "FAIL");
        try {
            SandboxDb.apply(db, script);
            var health = Evidence.health(db);
            stage.set("health", health);
            var current = Evidence.capture(db);
            stage.put("sha256", current.required("sha256").textValue());
            var changes = Evidence.diff(initial, current);
            stage.set("changes", changes);
            var observations = Checks.run(db, rules, initial, current);
            stage.set("checks", observations);
            boolean ok = health.required("ok").asBoolean();
            for (JsonNode check : observations) ok &= check.required("ok").asBoolean();
            if (requireRestore) {
                boolean matches = changes.isEmpty() && initial.required("sha256").equals(current.required("sha256"));
                stage.put("matchesBaseline", matches);
                ok &= matches;
            }
            stage.put("status", ok ? "PASS" : "FAIL");
            return true;
        } catch (Exception error) {
            stage.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            if (error instanceof SQLException sql) stage.put("sqliteCode", sql.getErrorCode());
            return false;
        }
    }

    private static boolean passed(ObjectNode stage) {
        return stage.path("status").asText().equals("PASS");
    }
}
