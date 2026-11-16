package com.autotrade.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.json.JsonMapper;

/**
 * The READ_LIVE response recorder (multi-broker plan 16 Nov 2026, D2: contract tests need real, non-empty answers).
 * Opt-in by account ({@code autotrade.execution.read-live-record.accounts}, default none): each distinct answer body of a
 * broker endpoint on an IST day is saved once, redacted ({@link ReadLiveRedaction}), as
 * {@code <root>/<broker>/<yyyy-MM-dd>/<endpoint>-<seq>.json}, with a line in that folder's {@code index.jsonl} saying
 * which call it answered (method, path, query, account, time, bytes, digest; the path and query redacted too, review 16
 * Nov 2026: {@link ReadLiveRedaction#redactPath}, so a client code or user id in a URL never reaches the index or a file
 * name) so {@link ReadLiveFixture} can turn it into
 * a contract-test fixture. Distinct means a different redacted body: the same empty book every five minutes is kept
 * once. Capped per broker and day: a body over {@code maxBytes} is skipped, at most {@code maxFilesPerEndpoint} files
 * per endpoint, {@code maxFiles} files and {@code maxBytesPerDay} bytes in all. Files and folders are owner-only where
 * the file system has permissions. The folder survives a restart: the day's digests and numbers are read back from it.
 * Nothing it does affects a call: every failure is logged once and dropped.
 */
public final class ReadLiveRecorder {

    private static final Logger log = LoggerFactory.getLogger(ReadLiveRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SAVED = Pattern.compile("(.+)-(\\d+)\\.json");
    private static final Pattern ID_SEGMENT = Pattern.compile("(?=.*\\d)[A-Za-z0-9_.-]{6,}");
    static final String INDEX = "index.jsonl";

    /** The caps (per broker and IST day; a body over {@code maxBytes} is never saved). */
    public record Limits(int maxBytes, int maxFilesPerEndpoint, int maxFiles, long maxBytesPerDay) {

        public static final Limits DEFAULT = new Limits(2 * 1024 * 1024, 40, 400, 100L * 1024 * 1024);
    }

    /** What one broker's day folder already holds. */
    private static final class Day {
        final Set<String> digests = new HashSet<>();
        final Map<String, Integer> seq = new HashMap<>();
        final Map<String, Integer> perEndpoint = new HashMap<>();
        int files;
        long bytes;
        boolean capWarned;
    }

    private final Path root;
    private final Set<Long> accounts;
    private final Limits limits;
    private final Clock clock;
    private final Map<String, Day> days = new HashMap<>();

    /**
     * @param root     the recorder's folder ({@code <storeDir>/read-live})
     * @param accounts the accounts whose answers are recorded (empty: none)
     */
    public ReadLiveRecorder(Path root, Set<Long> accounts, Limits limits, Clock clock) {
        this.root = root;
        this.accounts = accounts == null ? Set.of() : Set.copyOf(accounts);
        this.limits = limits == null ? Limits.DEFAULT : limits;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Whether this account's answers are recorded. */
    public boolean records(long accountId) {
        return root != null && accounts.contains(accountId);
    }

    public Set<Long> accounts() {
        return accounts;
    }

    /**
     * Saves one answer, redacted, when the account is recorded and the body is new for this endpoint today; returns the
     * file written (empty: not recorded, a repeat, over a cap, or a failure).
     *
     * @param call    {@code "<METHOD> <path>?<query>"} as the adapter's HTTP client reports it
     * @param secrets the session's own values (token, sid, keys, the client's own id) to take out as well, from the
     *                body, the path and the query
     */
    public Optional<Path> record(String broker, long accountId, String call, String body, List<String> secrets) {
        if (!records(accountId) || broker == null || call == null || body == null) {
            return Optional.empty();
        }
        call = redactCall(call, secrets);
        try {
            if (body.length() > limits.maxBytes()) {
                log.info("READ_LIVE recorder: {} {} answer of {} chars over the {} cap, not saved", broker, endpoint(call),
                        body.length(), limits.maxBytes());
                return Optional.empty();
            }
            String redacted = ReadLiveRedaction.redact(body, secrets);
            byte[] bytes = redacted.getBytes(StandardCharsets.UTF_8);
            String endpoint = endpoint(call);
            LocalDate today = LocalDate.now(clock.withZone(IST));
            String brokerDir = broker.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
            Path dir = root.resolve(brokerDir).resolve(today.toString());
            String digest = digest(endpoint, bytes);
            synchronized (this) {
                Day day = days.computeIfAbsent(brokerDir + "/" + today, k -> load(dir));
                if (day.digests.contains(digest)) {
                    return Optional.empty();                            // the same answer today: kept once
                }
                if (day.files >= limits.maxFiles() || day.bytes + bytes.length > limits.maxBytesPerDay()
                        || day.perEndpoint.getOrDefault(endpoint, 0) >= limits.maxFilesPerEndpoint()) {
                    if (!day.capWarned) {
                        day.capWarned = true;
                        log.info("READ_LIVE recorder: {} {} reached its cap ({} files, {} bytes): no more today",
                                broker, today, day.files, day.bytes);
                    }
                    return Optional.empty();
                }
                int seq = day.seq.merge(endpoint, 1, Integer::sum);
                Path file = dir.resolve(endpoint + "-" + seq + ".json");
                ownerOnlyDirectories(dir);
                Path tmp = Files.createTempFile(dir, ".rec-", ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                ownerOnly(file);
                Map<String, Object> line = new LinkedHashMap<>();
                int space = call.indexOf(' ');
                String target = space < 0 ? call : call.substring(space + 1);
                int q = target.indexOf('?');
                line.put("file", file.getFileName().toString());
                line.put("endpoint", endpoint);
                line.put("method", space < 0 ? "GET" : call.substring(0, space));
                line.put("path", q < 0 ? target : target.substring(0, q));       // redacted by redactCall
                line.put("query", q < 0 ? null : ReadLiveRedaction.redactQuery(target.substring(q + 1), secrets));
                line.put("account", accountId);
                line.put("at", clock.instant().toString());
                line.put("bytes", bytes.length);
                line.put("sha256", digest);
                Path index = dir.resolve(INDEX);
                Files.writeString(index, JSON.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                ownerOnly(index);
                day.digests.add(digest);
                day.perEndpoint.merge(endpoint, 1, Integer::sum);
                day.files++;
                day.bytes += bytes.length;
                return Optional.of(file);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("READ_LIVE recorder: {} answer not saved: {}", broker, e.toString());
            return Optional.empty();
        }
    }

    /**
     * The call with its path redacted ({@link ReadLiveRedaction#redactPath}); the query is left for
     * {@link ReadLiveRedaction#redactQuery} (by parameter name).
     */
    static String redactCall(String call, List<String> secrets) {
        int space = call.indexOf(' ');
        String target = space < 0 ? call : call.substring(space + 1);
        int q = target.indexOf('?');
        String path = ReadLiveRedaction.redactPath(q < 0 ? target : target.substring(0, q), secrets);
        return (space < 0 ? "" : call.substring(0, space + 1)) + path + (q < 0 ? "" : target.substring(q));
    }

    /**
     * The endpoint's file name from a call: method and path, lower case, without the query, and with id-like path
     * segments (six or more characters with a digit: an order id, a reference) as {@code id}. {@code GET
     * /quick/user/orders?x=1} is {@code get-quick-user-orders}.
     */
    static String endpoint(String call) {
        int space = call.indexOf(' ');
        String method = space < 0 ? "get" : call.substring(0, space).toLowerCase(Locale.ROOT);
        String target = space < 0 ? call : call.substring(space + 1);
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        StringBuilder out = new StringBuilder(method.replaceAll("[^a-z]", ""));
        for (String segment : path.split("/")) {
            if (segment.isBlank()) {
                continue;
            }
            String s = ID_SEGMENT.matcher(segment).matches() ? "id"
                    : segment.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
            if (!s.isEmpty()) {
                out.append('-').append(s);
            }
        }
        String e = out.toString();
        return e.length() > 80 ? e.substring(0, 80) : e;
    }

    /** What the day's folder already holds (a restart in the middle of the day). */
    private static Day load(Path dir) {
        Day day = new Day();
        if (!Files.isDirectory(dir)) {
            return day;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) {
                Matcher m = SAVED.matcher(f.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                String endpoint = m.group(1);
                byte[] bytes = Files.readAllBytes(f);
                day.digests.add(digest(endpoint, bytes));
                day.seq.merge(endpoint, Integer.parseInt(m.group(2)), Math::max);
                day.perEndpoint.merge(endpoint, 1, Integer::sum);
                day.files++;
                day.bytes += bytes.length;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("READ_LIVE recorder: {} not read back: {}", dir, e.toString());
        }
        return day;
    }

    private static String digest(String endpoint, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(endpoint.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            return HexFormat.of().formatHex(md.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private void ownerOnlyDirectories(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        Files.createDirectories(dir);
        if (posix()) {
            for (Path p = dir; p != null && p.startsWith(root); p = p.getParent()) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    private static void ownerOnly(Path file) throws IOException {
        if (posix()) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
