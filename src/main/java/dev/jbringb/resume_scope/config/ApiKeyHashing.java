package dev.jbringb.resume_scope.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// SHA-256 avalanches any single-bit change across ~50% of output bits, so an index lookup on the
// hash doesn't leak timing information about the original secret the way comparing raw bytes would.
final class ApiKeyHashing {

    private ApiKeyHashing() {}

    static String sha256Hex(String raw) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
