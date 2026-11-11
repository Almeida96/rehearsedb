package io.github.almeida96.rehearsedb;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Main {
    static final String VERSION = "0.1.0";
    private static final String HELP = """
            RehearseDB — rehearse SQLite migrations on a disposable snapshot

              rehearsedb run --db FILE --up FILE [--down FILE] [--checks FILE] --out NEW_DIR
              rehearsedb demo --out NEW_DIR
              rehearsedb mcp --report REPORT_JSON
              rehearsedb --version

            run exit codes: 0 verified rollback, 1 failed rehearsal, 2 input or I/O error,
                            3 forward-only rehearsal.
            """;

    private Main() {}

    public static void main(String[] args) {
        System.exit(execute(args, System.in, System.out, System.err));
    }

    static int execute(String[] args, InputStream in, PrintStream out, PrintStream err) {
        try {
            if (args.length == 1 && args[0].equals("--version")) {
                out.println(VERSION);
                return 0;
            }
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
                out.print(HELP);
                return args.length == 0 ? 2 : 0;
            }
            switch (args[0]) {
                case "run" -> {
                    var options = options(args, Set.of("db", "up", "down", "checks", "out"));
                    Path db = required(options, "db"), up = required(options, "up"), dest = required(options, "out");
                    String[] status = new String[1];
                    Output.publish(dest, staging -> {
                        var report = runInScratch(db, up, options.get("down"), options.get("checks"));
                        Output.report(staging, report);
                        status[0] = report.required("status").asText();
                    });
                    out.println(status[0] + "  " + dest.resolve("report.json"));
                    return switch (status[0]) { case "PASS" -> 0; case "FORWARD_ONLY" -> 3; default -> 1; };
                }
                case "demo" -> {
                    Path dest = required(options(args, Set.of("out")), "out");
                    Output.publish(dest, Main::demo);
                    out.println("PASS  " + dest.resolve("reversible/report.html"));
                    out.println("FAIL  " + dest.resolve("lossy/report.html") + " (expected data loss)");
                    return 0;
                }
                case "mcp" -> {
                    Path path = required(options(args, Set.of("report")), "report");
                    var report = Fileset.json(path, Fileset.REPORT_LIMIT);
                    Mcp.serve(report, in, out);
                    return 0;
                }
                default -> throw new IllegalArgumentException("Unknown command: " + args[0] + ". See --help");
            }
        } catch (Exception error) {
            err.println("rehearsedb: " + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()));
            return 2;
        }
    }

    private static ObjectNode runInScratch(Path db, Path up, Path down, Path checks) throws Exception {
        Path work = Files.createTempDirectory("rehearsedb-");
        try {
            return Rehearsal.run(db, up, down, checks, work);
        } finally {
            Output.clean(work);
        }
    }

    private static void demo(Path dest) throws Exception {
        Path inputs = Files.createDirectory(dest.resolve("inputs"));
        for (String filename : List.of("baseline.sql", "up.sql", "down-safe.sql", "down-lossy.sql", "checks.json")) {
            try (InputStream resource = Main.class.getResourceAsStream("/shipping/" + filename)) {
                if (resource == null) throw new IOException("Missing bundled demo input: " + filename);
                Files.copy(resource, inputs.resolve(filename));
            }
        }
        Path baseline = Files.createFile(inputs.resolve("shipping.sqlite"));
        try (var db = SandboxDb.openCopy(baseline)) {
            SandboxDb.apply(db, inputs.resolve("baseline.sql"));
        }
        Path checks = inputs.resolve("checks.json");
        for (String variant : List.of("safe", "lossy")) {
            boolean reversible = variant.equals("safe");
            var report = runInScratch(baseline, inputs.resolve("up.sql"), inputs.resolve("down-" + variant + ".sql"), checks);
            if (!report.path("status").asText().equals(reversible ? "PASS" : "FAIL")
                    || !report.at("/stages/forward/status").asText().equals("PASS")
                    || !report.at("/stages/rollback").has("matchesBaseline")) {
                throw new IllegalStateException("Shipping example did not reach expected verdict for " + variant + ": " + report.path("stages"));
            }
            Output.report(Files.createDirectory(dest.resolve(reversible ? "reversible" : "lossy")), report);
        }
    }

    private static Map<String, Path> options(String[] args, Set<String> allowed) {
        var result = new HashMap<String, Path>();
        for (int i = 1; i < args.length; i += 2) {
            String key = args[i];
            if (!key.startsWith("--") || !allowed.contains(key.substring(2))) throw new IllegalArgumentException("Unknown option: " + key);
            if (i + 1 >= args.length || args[i + 1].isBlank() || args[i + 1].startsWith("--")) throw new IllegalArgumentException("Missing value for " + key);
            if (result.putIfAbsent(key.substring(2), Path.of(args[i + 1])) != null) throw new IllegalArgumentException("Repeated option: " + key);
        }
        return result;
    }

    private static Path required(Map<String, Path> options, String key) {
        Path value = options.get(key);
        if (value == null) throw new IllegalArgumentException("Missing --" + key);
        return value;
    }
}
