package io.github.almeida96.rehearsedb;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

final class Output {
    private Output() {}

    interface Writer {
        void write(Path staging) throws Exception;
    }

    static void publish(Path destination, Writer writer) throws Exception {
        Path target = destination.toAbsolutePath().normalize();
        if (target.getParent() == null || Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Choose a new output directory: " + target);
        }
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempDirectory(target.getParent(), ".rehearsedb-output-");
        try {
            writer.write(staging);
            Files.move(staging, target);
        } finally {
            clean(staging);
        }
    }

    static void report(Path directory, JsonNode value) throws IOException {
        byte[] bytes = Fileset.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        if (bytes.length > Fileset.REPORT_LIMIT) throw new IOException("Report exceeds 32 MiB");
        Files.write(directory.resolve("report.json"), bytes);
        Files.writeString(directory.resolve("report.html"), Html.render(value));
    }

    static void clean(Path ownedTemporaryDirectory) throws IOException {
        if (!Files.exists(ownedTemporaryDirectory)) return;
        try (var paths = Files.walk(ownedTemporaryDirectory)) {
            for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
