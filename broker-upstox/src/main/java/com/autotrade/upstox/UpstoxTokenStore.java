package com.autotrade.upstox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the day's access token in a local file readable only by the owner (until Phase 5 moves
 * broker secrets to Vault). Written atomically; never logged.
 */
public final class UpstoxTokenStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path file;

    public UpstoxTokenStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public void save(UpstoxToken token) throws IOException {
        Files.createDirectories(file.getParent());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("accessToken", token.accessToken());
        values.put("userId", token.userId());
        values.put("issuedAt", token.issuedAt().toString());
        values.put("expiresAt", token.expiresAt().toString());
        Path temp = Files.createTempFile(file.getParent(), "token", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(temp, JSON.writeValueAsString(values));
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The stored token if it exists and is still valid at {@code now}. */
    public Optional<UpstoxToken> valid(Instant now) throws IOException {
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        JsonNode node = JSON.readTree(Files.readString(file));
        UpstoxToken token = new UpstoxToken(node.get("accessToken").asString(), node.get("userId").asString(),
                Instant.parse(node.get("issuedAt").asString()), Instant.parse(node.get("expiresAt").asString()));
        return token.validAt(now) ? Optional.of(token) : Optional.empty();
    }
}
