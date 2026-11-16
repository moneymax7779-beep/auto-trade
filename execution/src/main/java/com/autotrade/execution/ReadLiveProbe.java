package com.autotrade.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.account.BrokerAccount;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;

/**
 * READ_LIVE's proof of the books (code review 8 Nov 2026): a READ_LIVE route sends its orders, books and positions to
 * its simulator, so without this the adapter's real readers would never run before LIVE. Once when the route opens and
 * then every {@link Settings#every()}, on its own daemon thread, the probe calls the real session's {@code orders()},
 * {@code trades()} and {@code positions()} (reads only: it is handed the read-only side of the route and never places,
 * modifies or cancels), checks every row (an instrument key, a known status, a side, quantities not negative, a parsed
 * time), and writes a {@code READ_LIVE_PROBE} audit row per account with the counts and what did not parse. When given a
 * directory it also saves the answers' bodies, redacted ({@link ReadLiveRedaction}: credentials, the holder's identity,
 * contact and bank fields, the session's own values; never headers), under {@code <dir>/<broker>/<account>/<date>/}, one
 * file per distinct answer: tomorrow's contract tests; and an account named for the {@link ReadLiveRecorder} has every
 * distinct answer of its real session kept per endpoint and day. Nothing it does can affect trading: every failure is
 * caught, logged and audited.
 * <p>
 * The same thread warms the route up first (the day's instrument list), so that download is off the order path.
 */
public final class ReadLiveProbe implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReadLiveProbe.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HHmmss");
    private static final int EXAMPLES = 5;
    /**
     * How often the probe runs ({@code every} null: never, only the warm-up runs), where it saves the redacted answers
     * ({@code dir} null: nowhere, the audit row only) and the opt-in {@link ReadLiveRecorder} (null: none; 16 Nov 2026,
     * D2: each distinct answer per endpoint and day of the accounts it names, the probe's and every other read of the
     * route's real session).
     */
    public record Settings(Duration every, Path dir, ReadLiveRecorder recorder) {

        /** No probe (tests that do not ask for one): the warm-up still runs. */
        public static final Settings OFF = new Settings(null, null, null);

        public Settings(Duration every, Path dir) {
            this(every, dir, null);
        }

        public boolean probes() {
            return every != null && !every.isZero() && !every.isNegative();
        }
    }

    /** One probe's findings (also the audit row's detail). */
    public record Summary(int orders, int trades, int positions, List<String> unknownStatuses, int missingKey,
                         int missingSide, int negativeQuantity, int missingTime, List<String> examples,
                         Map<String, String> errors, int saved) {

        public boolean clean() {
            return errors.isEmpty() && unknownStatuses.isEmpty() && missingKey == 0 && missingSide == 0
                    && negativeQuantity == 0 && missingTime == 0;
        }

        Map<String, Object> detail(String broker) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("broker", broker);
            d.put("orders", orders);
            d.put("trades", trades);
            d.put("positions", positions);
            d.put("unknownStatuses", unknownStatuses);
            d.put("missingKey", missingKey);
            d.put("missingSide", missingSide);
            d.put("negativeQuantity", negativeQuantity);
            d.put("missingTime", missingTime);
            d.put("examples", examples);
            d.put("errors", errors);
            d.put("saved", saved);
            d.put("clean", clean());
            return d;
        }
    }

    private final DataSource dataSource;
    private final BrokerAccount account;
    private final String broker;
    private final BrokerConnection reads;
    private final Predicate<String> knownStatus;
    private final Settings settings;
    private final Supplier<List<String>> secrets;
    private final ThreadLocal<List<String[]>> capture = new ThreadLocal<>();
    private final Map<String, Integer> savedHashes = new HashMap<>();
    private volatile ScheduledExecutorService executor;

    /**
     * @param reads       the route's real-broker side (read-only: only its reads are called)
     * @param knownStatus whether a raw status is one the adapter knows
     * @param secrets     the session's own values (token, sid, key) to take out of saved bodies; read at each save
     */
    public ReadLiveProbe(DataSource dataSource, BrokerAccount account, String broker, BrokerConnection reads,
                         Predicate<String> knownStatus, Settings settings, Supplier<List<String>> secrets) {
        this.dataSource = dataSource;
        this.account = account;
        this.broker = broker;
        this.reads = ReadLiveConnection.readOnly(reads);
        this.knownStatus = knownStatus;
        this.settings = settings == null ? Settings.OFF : settings;
        this.secrets = secrets == null ? List::of : secrets;
    }

    /**
     * What the adapter's HTTP client hands each answer to: kept for a probe running on this thread; any other read of the
     * route's real session (funds, a quote, the instrument list's JSON) goes to the recorder when the account is
     * recorded, on the route's own thread, never on the caller's (16 Nov 2026: an entry's funds read is not slowed by a
     * file write).
     */
    public BiConsumer<String, String> tap() {
        return (call, body) -> {
            List<String[]> c = capture.get();
            if (c != null) {
                if (c.size() < 64) {
                    c.add(new String[] {call, body});
                }
                return;
            }
            ScheduledExecutorService e = executor;
            if (recording() && e != null && !e.isShutdown()) {
                try {
                    e.execute(() -> record(call, body));
                } catch (RuntimeException rejected) {
                    // closing: the answer is not recorded
                }
            }
        };
    }

    private boolean recording() {
        return settings.recorder() != null && settings.recorder().records(account.id());
    }

    private void record(String call, String body) {
        try {
            settings.recorder().record(broker, account.id(), call, body, secrets.get());
        } catch (RuntimeException e) {
            log.warn("account {} ({}): READ_LIVE answer not recorded: {}", account.id(), account.displayName(),
                    e.getMessage());
        }
    }

    /**
     * Starts the route's thread: {@code warmUp} at once (failures logged), then a probe at once and every
     * {@link Settings#every()} when probing is on.
     */
    public synchronized void start(Runnable warmUp) {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true)
                .name("read-live-" + broker.toLowerCase(Locale.ROOT) + "-" + account.id()).factory());
        executor.execute(() -> {
            try {
                warmUp.run();
            } catch (RuntimeException e) {
                log.warn("account {} ({}): {} warm-up failed: {}", account.id(), account.displayName(), broker,
                        e.getMessage());
            }
        });
        if (settings.probes()) {
            long every = settings.every().toMillis();
            executor.scheduleWithFixedDelay(this::probeQuietly, 0, every, TimeUnit.MILLISECONDS);
        }
    }

    private void probeQuietly() {
        try {
            probe();
        } catch (Throwable e) {                                         // a scheduled task that throws stops repeating
            log.warn("account {} ({}): {} READ_LIVE probe failed: {}", account.id(), account.displayName(), broker,
                    e.toString());
        }
    }

    /** One probe now, on the calling thread: the three reads, the checks, the saved answers and the audit row. */
    public Summary probe() {
        List<String[]> raw = new ArrayList<>();
        capture.set(raw);
        Map<String, String> errors = new LinkedHashMap<>();
        List<BrokerOrder> orders = List.of();
        List<BrokerTrade> trades = List.of();
        List<BrokerPosition> positions = List.of();
        try {
            try {
                orders = reads.orders();
            } catch (RuntimeException e) {
                errors.put("orders", message(e));
            }
            try {
                trades = reads.trades();
            } catch (RuntimeException e) {
                errors.put("trades", message(e));
            }
            try {
                positions = reads.positions();
            } catch (RuntimeException e) {
                errors.put("positions", message(e));
            }
        } finally {
            capture.remove();
        }
        Set<String> unknown = new LinkedHashSet<>();
        List<String> examples = new ArrayList<>();
        int missingKey = 0, missingSide = 0, negative = 0, missingTime = 0;
        for (BrokerOrder o : orders) {
            if (o == null) {
                continue;
            }
            if (o.instrumentKey() == null) {
                missingKey++;
                example(examples, "order " + o.brokerOrderId() + " " + o.symbol() + ": no instrument key");
            }
            if (o.rawStatus() == null || !knownStatus.test(o.rawStatus())) {
                unknown.add(String.valueOf(o.rawStatus()));
            }
            if (o.side() == null) {
                missingSide++;
                example(examples, "order " + o.brokerOrderId() + ": no side");
            }
            if (o.quantity() < 0 || o.filledQuantity() < 0 || o.filledQuantity() > Math.max(o.quantity(), 0)) {
                negative++;
                example(examples, "order " + o.brokerOrderId() + ": quantity " + o.quantity() + ", filled "
                        + o.filledQuantity());
            }
            if (o.updatedAt() == null) {
                missingTime++;
                example(examples, "order " + o.brokerOrderId() + ": no time");
            }
        }
        for (BrokerTrade t : trades) {
            if (t == null) {
                continue;
            }
            if (t.instrumentKey() == null) {
                missingKey++;
                example(examples, "trade " + t.tradeId() + " " + t.symbol() + ": no instrument key");
            }
            if (t.side() == null) {
                missingSide++;
                example(examples, "trade " + t.tradeId() + ": no side");
            }
            if (t.quantity() <= 0 || !(t.price() >= 0)) {
                negative++;
                example(examples, "trade " + t.tradeId() + ": quantity " + t.quantity() + " at " + t.price());
            }
            if (t.time() == null) {
                missingTime++;
                example(examples, "trade " + t.tradeId() + ": no time");
            }
        }
        for (BrokerPosition p : positions) {
            if (p == null) {
                continue;
            }
            if (p.instrumentKey() == null) {
                missingKey++;
                example(examples, "position " + p.symbol() + ": no instrument key");
            }
            if (p.dayBuyQuantity() < 0 || p.daySellQuantity() < 0) {
                negative++;
                example(examples, "position " + p.symbol() + ": day buy " + p.dayBuyQuantity() + ", sell "
                        + p.daySellQuantity());
            }
        }
        int saved = save(raw);
        Summary summary = new Summary(orders.size(), trades.size(), positions.size(), List.copyOf(unknown), missingKey,
                missingSide, negative, missingTime, examples, errors, saved);
        try {
            new KillSwitches(dataSource).audit(account, "execution", "READ_LIVE_PROBE", summary.detail(broker));
        } catch (RuntimeException e) {
            log.warn("account {} ({}): READ_LIVE probe not audited: {}", account.id(), account.displayName(),
                    e.getMessage());
        }
        if (summary.clean()) {
            log.info("account {} ({}): {} READ_LIVE probe: {} orders, {} trades, {} positions, all parsed", account.id(),
                    account.displayName(), broker, summary.orders(), summary.trades(), summary.positions());
        } else {
            log.warn("account {} ({}): {} READ_LIVE probe: {}", account.id(), account.displayName(), broker,
                    summary.detail(broker));
        }
        return summary;
    }

    private static void example(List<String> examples, String line) {
        if (examples.size() < EXAMPLES) {
            examples.add(line);
        }
    }

    private static String message(RuntimeException e) {
        String m = e.getClass().getSimpleName() + ": " + e.getMessage();
        return m.length() > 300 ? m.substring(0, 300) : m;
    }

    /** The answers, redacted, one file per distinct body of a call today; the number written. */
    private int save(List<String[]> raw) {
        if (recording()) {
            raw.forEach(r -> record(r[0], r[1]));
        }
        if (settings.dir() == null || raw.isEmpty()) {
            return 0;
        }
        int written = 0;
        try {
            LocalDateTime now = LocalDateTime.now(IST);
            Path day = settings.dir().resolve(broker.toLowerCase(Locale.ROOT)).resolve(Long.toString(account.id()))
                    .resolve(now.toLocalDate().toString());
            List<String> values = secrets.get();
            for (int i = 0; i < raw.size(); i++) {
                String call = raw.get(i)[0];
                String body = redact(raw.get(i)[1], values);
                String slug = call.replaceAll("\\?.*$", "").replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
                int hash = body.hashCode();
                Integer before;
                synchronized (savedHashes) {
                    before = savedHashes.put(now.toLocalDate() + slug, hash);
                }
                if (before != null && before == hash) {
                    continue;                                           // the same answer as last time: kept once
                }
                Files.createDirectories(day);
                Files.writeString(day.resolve(STAMP.format(now) + "-" + i + "-" + slug + ".json"), body,
                        StandardCharsets.UTF_8);
                written++;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("account {} ({}): READ_LIVE answers not saved: {}", account.id(), account.displayName(),
                    e.getMessage());
        }
        return written;
    }

    /**
     * A body without secrets (16 Nov 2026: the recorder's rules, {@link ReadLiveRedaction}, which also take out the
     * account holder's name, contact, PAN and bank fields): by field name at any depth, then the session's own
     * {@code values} (token, sid, key) wherever they still appear; a body that is not JSON gets the second step only.
     */
    static String redact(String body, List<String> values) {
        return ReadLiveRedaction.redact(body, values);
    }

    /** Stops the thread (a running probe's HTTP call is interrupted). */
    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Whether the route's thread is running (tests). */
    boolean running() {
        ScheduledExecutorService e = executor;
        return e != null && !e.isShutdown();
    }
}
