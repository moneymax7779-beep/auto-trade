package com.autotrade.exec.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;

/**
 * The broker secret store, as files (live plan section 6; the VM keeps them owner-only, never in the image, the
 * database or a log):
 * <ul>
 *   <li>the Kite app's API key and secret, read from {@code secretsDir} by reference ({@code file:<name>}, the
 *       credential profile's {@code api_key_ref} / {@code api_secret_ref});</li>
 *   <li>the day's access token of each broker account, written to {@code sessionsDir} as {@code kite-<id>.token}
 *       (owner read/write only, replaced atomically) and deleted when the session expires.</li>
 * </ul>
 * Passwords and TOTP secrets are never stored anywhere.
 */
final class BrokerSecrets {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final Path secretsDir;
    private final Path sessionsDir;

    BrokerSecrets(Path secretsDir, Path sessionsDir) {
        this.secretsDir = secretsDir;
        this.sessionsDir = sessionsDir;
    }

    /** The value behind {@code file:<name>}; never logged. */
    String read(String ref) {
        if (ref == null || !ref.startsWith("file:") || !NAME.matcher(ref.substring(5)).matches()) {
            throw new IllegalArgumentException("secret reference must be file:<name> ([A-Za-z0-9._-]), got " + ref);
        }
        Path file = secretsDir.resolve(ref.substring(5));
        try {
            String value = Files.readString(file).strip();
            if (value.isEmpty()) {
                throw new IllegalStateException("secret " + ref + " is empty");
            }
            return value;
        } catch (IOException e) {
            throw new IllegalStateException("secret " + ref + " is not readable in " + secretsDir + " ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /** The token reference stored in acct.broker_session (a name, not the token). */
    static String tokenRef(long accountId) {
        return "session-file:kite-" + accountId + ".token";
    }

    void writeToken(long accountId, String content) {
        try {
            Files.createDirectories(sessionsDir);
            Path target = sessionsDir.resolve("kite-" + accountId + ".token");
            Path tmp = Files.createTempFile(sessionsDir, "kite-" + accountId + "-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(tmp, content);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store the session of account " + accountId + " in " + sessionsDir, e);
        }
    }

    String readToken(long accountId) {
        try {
            Path file = sessionsDir.resolve("kite-" + accountId + ".token");
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void deleteToken(long accountId) {
        try {
            Files.deleteIfExists(sessionsDir.resolve("kite-" + accountId + ".token"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
