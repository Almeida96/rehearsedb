package io.github.almeida96.rehearsedb;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Fileset {
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final int SCRIPT_LIMIT = 4 * 1024 * 1024;
    static final int POLICY_LIMIT = 128 * 1024;
    static final int REPORT_LIMIT = 32 * 1024 * 1024;

    private Fileset() {}

    static byte[] read(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Expected a regular file: " + path);
        try (var stream = Files.newInputStream(path)) {
            byte[] data = stream.readNBytes(limit + 1);
            if (data.length > limit) throw new IOException(path.getFileName() + " exceeds " + limit + " bytes");
            return data;
        }
    }

    static JsonNode json(Path path, int limit) throws IOException {
        JsonNode value = JSON.readTree(read(path, limit));
        if (value == null || !value.isObject()) throw new IOException("Expected a JSON object in " + path);
        return value;
    }

    static String digest(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
