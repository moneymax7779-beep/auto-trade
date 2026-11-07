package com.autotrade.exec.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;

/**
 * The broker secret store (live plan section 6; on the VM owner-only, never in the image, the
 * database or a log):
 * <ul>
 *   <li>the Kite app's API key and secret, by the credential profile's reference ({@code api_key_ref} /
 *       {@code api_secret_ref}): {@code env:<NAME>}, an environment variable of the execution container (on GCP from
 *       the root-only .env, user's choice 24 Oct 2026), or {@code file:<name>} in {@code secretsDir};</li>
 *   <li>the keys a member types on the Brokers page (trader workspaces, 7 Nov 2026): {@code store:<name>}, one file per
 *       value in the writable {@code storeDir}, written atomically owner-only and never read back to anyone but the
 *       login that needs it;</li>
 *   <li>the day's access token of each broker account, written to {@code sessionsDir} as {@code <broker>-<id>.token}
 *       ({@code kite-<id>.token} for Zerodha; owner read/write only, replaced atomically) and deleted when the session
 *       expires.</li>
 * </ul>
 * Passwords, MPINs and TOTP secrets are never stored anywhere.
 */
final class BrokerSecrets {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /** Only broker app variables (code review 24 Oct 2026: an env: reference must not reach e.g. the DB password). */
    private static final Pattern ENV = Pattern.compile("KITE_[A-Z0-9_]{1,59}");

    private final Path secretsDir;
    private final Path sessionsDir;
    private final Path storeDir;                  // null: no store: references (tests, an old configuration)

    private final java.util.function.Function<String, String> environment;

    BrokerSecrets(Path secretsDir, Path sessionsDir) {
        this(secretsDir, sessionsDir, null, System::getenv);
    }

    BrokerSecrets(Path secretsDir, Path sessionsDir, Path storeDir) {
        this(secretsDir, sessionsDir, storeDir, System::getenv);
    }

    BrokerSecrets(Path secretsDir, Path sessionsDir, java.util.function.Function<String, String> environment) {
        this(secretsDir, sessionsDir, null, environment);
    }

    BrokerSecrets(Path secretsDir, Path sessionsDir, Path storeDir,
                  java.util.function.Function<String, String> environment) {
        this.secretsDir = secretsDir;
        this.sessionsDir = sessionsDir;
        this.storeDir = storeDir;
        this.environment = environment;
    }

    /** The reference of a stored value of a broker app ({@code store:t<tenant>-p<profile>-<field>}). */
    static String storeRef(long tenantId, long profileId, String field) {
        return "store:t" + tenantId + "-p" + profileId + "-" + field;
    }

    /** Writes a value behind a {@code store:} reference (atomically, owner-only); the value is never logged. */
    void write(String ref, String value) {
        Path file = storeFile(ref);
        try {
            if (!Files.isDirectory(storeDir)) {
                Files.createDirectories(storeDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(
                        "rwx------")));
            }
            Path tmp = Files.createTempFile(storeDir, "w-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(tmp, value);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store " + ref + " in " + storeDir, e);
        }
    }

    /** Deletes the value behind a {@code store:} reference (a deleted broker app's keys); other references are left alone. */
    void delete(String ref) {
        if (ref == null || !ref.startsWith("store:") || storeDir == null) {
            return;
        }
        try {
            Files.deleteIfExists(storeFile(ref));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + ref + " in " + storeDir, e);
        }
    }

    /** True when a {@code store:} reference has a value. */
    boolean stored(String ref) {
        return ref != null && ref.startsWith("store:") && storeDir != null && Files.isRegularFile(storeFile(ref));
    }

    private Path storeFile(String ref) {
        if (storeDir == null) {
            throw new IllegalStateException("no secret store is configured on the execution service");
        }
        if (ref == null || !ref.startsWith("store:") || !NAME.matcher(ref.substring(6)).matches()
                || ref.substring(6).startsWith(".")) {
            throw new IllegalArgumentException("not a store reference: " + ref);
        }
        return storeDir.resolve(ref.substring(6));
    }

    /** The value behind {@code env:<NAME>}, {@code file:<name>} or {@code store:<name>}; never logged. */
    String read(String ref) {
        if (ref != null && ref.startsWith("store:")) {
            Path file = storeFile(ref);
            try {
                String value = Files.isRegularFile(file) ? Files.readString(file).strip() : "";
                if (value.isEmpty()) {
                    throw new IllegalStateException("secret " + ref + " is not set: enter it on the Brokers page");
                }
                return value;
            } catch (IOException e) {
                throw new IllegalStateException("secret " + ref + " is not readable (" + e.getClass().getSimpleName() + ")");
            }
        }
        if (ref != null && ref.startsWith("env:") && ENV.matcher(ref.substring(4)).matches()) {
            String value = environment.apply(ref.substring(4));
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("secret " + ref + " is not set in the execution container's environment");
            }
            return value.strip();
        }
        if (ref == null || !ref.startsWith("file:") || !NAME.matcher(ref.substring(5)).matches()
                || ref.substring(5).startsWith(".")) {
            throw new IllegalArgumentException("secret reference must be env:KITE_<NAME>, file:<name> or store:<name>, got "
                    + ref);
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
        return tokenRef("kite", accountId);
    }

    /** {@code broker}: kite, upstox, kotak, groww. */
    static String tokenRef(String broker, long accountId) {
        return "session-file:" + broker + "-" + accountId + ".token";
    }

    void writeToken(long accountId, String content) {
        writeToken("kite", accountId, content);
    }

    String readToken(String broker, long accountId) {
        try {
            Path file = sessionsDir.resolve(broker + "-" + accountId + ".token");
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void writeToken(String broker, long accountId, String content) {
        try {
            Files.createDirectories(sessionsDir);
            Path target = sessionsDir.resolve(broker + "-" + accountId + ".token");
            Path tmp = Files.createTempFile(sessionsDir, broker + "-" + accountId + "-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(tmp, content);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);              // never a stray copy of a token (gone after the move)
            }
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

    /** Deletes the account's token file, whichever broker wrote it. */
    void deleteToken(long accountId) {
        try {
            for (String broker : java.util.List.of("kite", "upstox", "kotak", "groww")) {
                Files.deleteIfExists(sessionsDir.resolve(broker + "-" + accountId + ".token"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
