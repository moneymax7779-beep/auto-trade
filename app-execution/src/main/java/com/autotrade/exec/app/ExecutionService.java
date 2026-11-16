package com.autotrade.exec.app;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.autotrade.execution.BrokerAdapter;
import com.autotrade.execution.BrokerRules;
import com.autotrade.execution.ExecutionLoop;
import com.autotrade.execution.ReadLiveProbe;
import com.autotrade.execution.StoredQuotes;

/**
 * Runs the {@link ExecutionLoop} every poll interval and whenever {@link SignalWakeup} hears a new signal decision.
 * Refuses to start unless the schema is at least V23 (this service
 * never migrates: trading-core owns the schema). Retry quotes come from today's own capture (md.option_tick).
 */
@Service
class ExecutionService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int REQUIRED_SCHEMA = 40;          // V40: chart engine heartbeat (V39: Telegram commands)

    private final DataSource dataSource;
    private final ExecutionProperties properties;
    private final KiteSessionSource kiteSessions;
    private final KotakSessionSource kotakSessions;
    private final UpstoxSessionSource upstoxSessions;
    private final GrowwSessionSource growwSessions;
    private final KiteLoginProperties kite;
    private final String probeEvery;
    private final String probeDir;
    private final String probeAccounts;
    /** The broker rules (16 Nov 2026, A1/A3); null only in tests that never start the loop. */
    private final BrokerRules rules;
    /** The worker's adapters, one per broker: the loop's, and what {@link BrokerSetup}'s LiveBrokers is built from. */
    private final List<BrokerAdapter> brokerAdapters;
    /**
     * The brokers configured LIVE ({@code autotrade.execution.live-brokers}) with an adapter that offers LIVE: one instance
     * for the loop's routes and the pages (review 16 Nov 2026: the routes ignored the setting).
     */
    private final com.autotrade.execution.LiveBrokers liveBrokers;
    private final com.autotrade.execution.AccountEgress egress;
    private final UpstoxDenyList upstoxDenied;
    private final String recordAccounts;
    private final BrokerLoginProperties brokers;
    private volatile ExecutionLoop loop;
    private volatile java.time.Instant lastTick;

    /** Tests: no real broker sessions and no broker rules (the loop is not started). */
    ExecutionService(DataSource dataSource, ExecutionProperties properties) {
        this(dataSource, properties, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    ExecutionService(DataSource dataSource, ExecutionProperties properties, KiteSessionSource kiteSessions,
                     KotakSessionSource kotakSessions, UpstoxSessionSource upstoxSessions,
                     GrowwSessionSource growwSessions, KiteLoginProperties kite,
                     @org.springframework.beans.factory.annotation.Value("${autotrade.execution.read-live-probe.every:}")
                     String probeEvery,
                     @org.springframework.beans.factory.annotation.Value("${autotrade.execution.read-live-probe.dir:}")
                     String probeDir,
                     @org.springframework.beans.factory.annotation.Value("${autotrade.execution.read-live-probe.accounts:}")
                     String probeAccounts,
                     @org.springframework.beans.factory.annotation.Value("${autotrade.execution.read-live-record.accounts:}")
                     String recordAccounts,
                     BrokerLoginProperties brokers, com.autotrade.execution.AccountEgress egress,
                     UpstoxDenyList upstoxDenied, BrokerRules rules,
                     @org.springframework.beans.factory.annotation.Value("${autotrade.execution.live-brokers:"
                             + com.autotrade.execution.LiveBrokers.DEFAULT + "}") String liveBrokerNames) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.kiteSessions = kiteSessions;
        this.kotakSessions = kotakSessions;
        this.upstoxSessions = upstoxSessions;
        this.growwSessions = growwSessions;
        this.kite = kite;
        this.probeEvery = probeEvery;
        this.probeDir = probeDir;
        this.probeAccounts = probeAccounts;
        this.recordAccounts = recordAccounts;
        this.brokers = brokers;
        this.egress = egress == null ? com.autotrade.execution.AccountEgress.NONE : egress;
        this.upstoxDenied = upstoxDenied == null ? UpstoxDenyList.NONE : upstoxDenied;
        this.rules = rules;
        this.brokerAdapters = adapters(dataSource, properties.brokerFunds(), kiteSessions, kotakSessions, upstoxSessions,
                growwSessions, kite == null ? com.autotrade.zerodha.KiteConfig.REAL_API : java.net.URI.create(kite.apiBase()),
                null, null, probeSettings(), this.egress, this.upstoxDenied, null, null, rules);
        this.liveBrokers = com.autotrade.execution.LiveBrokers.of(liveBrokerNames == null
                ? com.autotrade.execution.LiveBrokers.DEFAULT : liveBrokerNames, brokerAdapters);
    }

    /** The brokers an account may trade LIVE on this worker (the setting, and an adapter that offers LIVE). */
    com.autotrade.execution.LiveBrokers liveBrokers() {
        return liveBrokers;
    }

    /** The worker's broker adapters (one per broker). */
    List<BrokerAdapter> brokerAdapters() {
        return brokerAdapters;
    }

    /**
     * The worker's adapters, one per broker (8 Nov 2026): Zerodha (DRY_RUN and the LIVE pilot, unchanged), and Kotak Neo,
     * Upstox and Groww (DRY_RUN; their LIVE connections exist since 16 Nov 2026 but they are not in LiveBrokers, and
     * without an egress address the static-IP gate refuses them). Each reads its own
     * accounts' sessions; Upstox's and Groww's READ_LIVE reads go to {@code upstox} / {@code groww} (their own hosts when
     * null), with the member's own session, never zt-tiger-v2's; a READ_LIVE route's probe runs as {@code probe} says.
     */
    static java.util.List<com.autotrade.execution.BrokerAdapter> adapters(DataSource dataSource, double brokerFunds,
                                                                         KiteSessionSource kiteSessions,
                                                                         KotakSessionSource kotakSessions,
                                                                         UpstoxSessionSource upstoxSessions,
                                                                         GrowwSessionSource growwSessions,
                                                                         java.net.URI kiteApiBase,
                                                                         com.autotrade.upstox.orders.UpstoxConfig upstox,
                                                                         com.autotrade.groww.GrowwConfig groww,
                                                                         ReadLiveProbe.Settings probe) {
        return adapters(dataSource, brokerFunds, kiteSessions, kotakSessions, upstoxSessions, growwSessions, kiteApiBase,
                upstox, groww, probe, com.autotrade.execution.AccountEgress.NONE, UpstoxDenyList.NONE, null, null, null);
    }

    /**
     * As above, with each account's egress address and the static-IP LIVE gate ({@code egress}, production gaps B1/B2,
     * 16 Nov 2026: every adapter's real-broker clients of an account leave from its address), the Upstox users never
     * switched LIVE ({@code upstoxDenied}: zt-tiger-v2's always), the LIVE configs of Upstox and Groww (their own hosts
     * when null; a simulator in tests), and the Kite product from the broker rules (A1; null rules: the Kite config's
     * default, MIS; the other adapters' products are checked against the rules at start, {@link BrokerRules#requireProducts}).
     * Kotak, Upstox and Groww have LIVE connections, reachable only once their adapter offers LIVE and their broker is
     * configured in LiveBrokers (neither in this build).
     */
    static java.util.List<com.autotrade.execution.BrokerAdapter> adapters(DataSource dataSource, double brokerFunds,
                                                                         KiteSessionSource kiteSessions,
                                                                         KotakSessionSource kotakSessions,
                                                                         UpstoxSessionSource upstoxSessions,
                                                                         GrowwSessionSource growwSessions,
                                                                         java.net.URI kiteApiBase,
                                                                         com.autotrade.upstox.orders.UpstoxConfig upstox,
                                                                         com.autotrade.groww.GrowwConfig groww,
                                                                         ReadLiveProbe.Settings probe,
                                                                         com.autotrade.execution.AccountEgress egress,
                                                                         UpstoxDenyList upstoxDenied,
                                                                         com.autotrade.upstox.orders.UpstoxConfig upstoxLive,
                                                                         com.autotrade.groww.GrowwConfig growwLive,
                                                                         BrokerRules rules) {
        java.util.Map<com.autotrade.account.BrokerType, com.autotrade.execution.BrokerSessions> byBroker =
                new java.util.EnumMap<>(com.autotrade.account.BrokerType.class);
        if (kiteSessions != null) {
            byBroker.put(com.autotrade.account.BrokerType.ZERODHA, kiteSessions);
        }
        if (kotakSessions != null) {
            byBroker.put(com.autotrade.account.BrokerType.KOTAK_NEO, kotakSessions);
        }
        if (upstoxSessions != null) {
            byBroker.put(com.autotrade.account.BrokerType.UPSTOX, upstoxSessions);
        }
        if (growwSessions != null) {
            byBroker.put(com.autotrade.account.BrokerType.GROWW, growwSessions);
        }
        com.autotrade.execution.BrokerSessions sessions = new BrokerSessionRouter(byBroker);
        com.autotrade.execution.CapturedQuotes captured = new com.autotrade.execution.CapturedQuotes(dataSource);
        return java.util.List.of(
                new com.autotrade.execution.ZerodhaDryRunAdapter(dataSource, brokerFunds, sessions, kiteApiBase,
                        rules == null ? null : rules.rule(com.autotrade.account.BrokerType.ZERODHA).product(), egress,
                        probe),
                new com.autotrade.execution.KotakDryRunAdapter(dataSource, brokerFunds, sessions, captured, probe, egress),
                new com.autotrade.execution.UpstoxDryRunAdapter(dataSource, brokerFunds, sessions,
                        upstox == null ? com.autotrade.upstox.orders.UpstoxConfig.real() : upstox, captured, probe,
                        upstoxLive == null ? com.autotrade.upstox.orders.UpstoxConfig.live() : upstoxLive, egress,
                        upstoxDenied.deniedUserIds()),
                new com.autotrade.execution.GrowwDryRunAdapter(dataSource, brokerFunds, sessions,
                        groww == null ? com.autotrade.groww.GrowwConfig.real() : groww, captured, probe,
                        growwLive == null ? com.autotrade.groww.GrowwConfig.live() : growwLive, egress));
    }

    /**
     * The READ_LIVE probe of this worker: every {@code autotrade.execution.read-live-probe.every} (blank = off), saving
     * redacted answers under {@code ...dir} (blank = audit rows only).
     */
    /**
     * The idle accounts that keep a READ_LIVE probe ({@code autotrade.execution.read-live-probe.accounts}: account ids,
     * comma-separated; blank = none). Reads only: see ExecutionLoop#probeOnly.
     */
    java.util.Set<Long> probeAccounts() {
        return ids(probeAccounts);
    }

    /**
     * The accounts whose real broker answers are recorded ({@code autotrade.execution.read-live-record.accounts}: ids,
     * comma-separated; blank = none, the default; 16 Nov 2026, D2), redacted, under {@code <storeDir>/read-live}.
     */
    java.util.Set<Long> recordAccounts() {
        return ids(recordAccounts);
    }

    private static java.util.Set<Long> ids(String list) {
        String ids = list == null ? "" : list.trim();
        if (ids.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<Long> set = new java.util.TreeSet<>();
        for (String id : ids.split(",")) {
            if (!id.isBlank()) {
                set.add(Long.parseLong(id.trim()));
            }
        }
        return set;
    }

    ReadLiveProbe.Settings probeSettings() {
        String every = probeEvery == null ? "" : probeEvery.trim();
        String dir = probeDir == null ? "" : probeDir.trim();
        java.util.Set<Long> recorded = recordAccounts();
        com.autotrade.execution.ReadLiveRecorder recorder = recorded.isEmpty() || brokers == null ? null
                : new com.autotrade.execution.ReadLiveRecorder(Path.of(brokers.storeDir()).resolve("read-live"), recorded,
                com.autotrade.execution.ReadLiveRecorder.Limits.DEFAULT, java.time.Clock.systemUTC());
        return new ReadLiveProbe.Settings(every.isEmpty() ? null : java.time.Duration.parse(every),
                dir.isEmpty() ? null : Path.of(dir), recorder);
    }

    /**
     * An account's LIVE connection from this worker's adapter for its broker, behind its LIVE guard with {@code limits}
     * (null: the adapter offers none, or the worker has not started). The caller closes it. Used only by the canary
     * (16 Nov 2026, D1), on the user's explicit request; the trading route opens its own through the broker registry.
     */
    com.autotrade.execution.BrokerAdapter.Live openLive(com.autotrade.account.BrokerAccount account,
                                                        com.autotrade.execution.FollowerRisk.LiveLimits limits) {
        for (com.autotrade.execution.BrokerAdapter a : brokerAdapters) {   // the canary opens LIVE through the loop's adapters
            if (a.broker() == account.brokerType()) {
                return a.live(account, limits);
            }
        }
        return null;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        Integer version = new JdbcTemplate(dataSource).queryForObject(
                "select max(version::int) from flyway_schema_history where success", Integer.class);
        if (version == null || version < REQUIRED_SCHEMA) {
            throw new IllegalStateException("schema version " + version + " < " + REQUIRED_SCHEMA
                    + ": start trading-core with this code first (it owns the migrations)");
        }
        if (rules == null) {
            throw new IllegalStateException("no broker rules (autotrade.execution.broker-rules-file)");
        }
        rules.requireProducts(brokerAdapters);
        rules.describe().forEach(line -> log.info("broker rules {}: {}", rules.version(), line));
        loop = new ExecutionLoop(dataSource, properties.tenant(), properties.copyGroup(), properties.workerId(), rules,
                new StoredQuotes(dataSource, null), properties.brokerFunds(), brokerAdapters).liveBrokers(liveBrokers)
                .probeOnly(probeAccounts());
        if (!probeAccounts().isEmpty()) {
            log.warn("READ_LIVE probe (reads only, no order path) for idle accounts {}", probeAccounts());
        }
        if (!recordAccounts().isEmpty()) {
            log.warn("READ_LIVE recorder: real broker answers of accounts {} saved redacted under {}", recordAccounts(),
                    brokers == null ? "(no store dir)" : Path.of(brokers.storeDir()).resolve("read-live"));
        }
        log.info("execution worker {} serving copy group {} (tenant {}), every {} ms", properties.workerId(),
                properties.copyGroup(), properties.tenant(), properties.pollMs());
        if (properties.day() != null) {
            log.warn("DRILL: serving session date {} instead of today", properties.day());
        }
    }

    /** Every poll interval, and at once when a signal decision is stored ({@link SignalWakeup}); one tick at a time. */
    @Scheduled(fixedDelayString = "${autotrade.execution.poll-ms:1000}", initialDelay = 5000)
    synchronized void tick() {
        ExecutionLoop current = loop;
        if (current != null) {
            current.tick(day(), properties.pollMs() * 5);
            lastTick = java.time.Instant.now();
        }
    }

    /** Today, or the drill day (a replayed day streamed through the worker). */
    LocalDate day() {
        return properties.day() == null ? LocalDate.now(IST) : LocalDate.parse(properties.day());
    }

    /**
     * Every minute from 15:00 to 15:30 IST, weekdays: each broker's follower positions still open are flattened without a
     * kill switch once its square-off time in the broker rules has come (code review 24 Oct 2026: a stopped signal source
     * sends no EXIT). 16 Nov 2026 (A1): this was one 15:20 cron ({@code autotrade.execution.square-off-cron}, removed)
     * for every broker; ZERODHA's time stays 15:20. Each minute's square-off runs on its own thread, so a slow one does
     * not hold back the next broker's time; a worker started late squares every broker already due at its first minute.
     */
    @Scheduled(cron = "0 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    void squareOff() {
        ExecutionLoop current = loop;
        if (current == null) {
            return;
        }
        if (lastTick == null) {
            // review 16 Nov 2026: a worker started in the window has no account leases before its first tick; a broker
            // marked done now would never be squared off. The next minute's call (to 15:30) catches up.
            log.warn("end-of-day square-off: the loop has not ticked yet; tried again next minute");
            return;
        }
        LocalDate day = day();
        // the scheduled minute, not the clock (a cron that fires a few ms early must not move 15:20 to 15:21)
        java.time.LocalTime now = java.time.LocalTime.now(IST).plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        Thread.ofVirtual().name("exec-square-off-due").start(() -> {
            try {
                current.squareOffDue(day, now).forEach(line -> log.warn("end-of-day square-off: {}", line));
            } catch (RuntimeException e) {
                log.error("end-of-day square-off failed", e);
            }
        });
    }

    /** After the close (15:45 IST, weekdays): every broker-routed account this worker holds is reconciled. */
    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
    void endOfDay() {
        ExecutionLoop current = loop;
        if (current != null) {
            current.endOfDay(day()).forEach(d -> {
                log.info("end of day account {}: difference {}, {} contracts differ", d.ledger().brokerAccountId(),
                        d.ledger().difference(), d.differences().size());
                d.differences().forEach(line -> log.warn("end of day account {}: {}", d.ledger().brokerAccountId(), line));
            });
        }
    }

    List<String> flatten(long accountId, String actor, String reason, LocalDate day) throws Exception {
        ExecutionLoop current = loop;
        if (current == null) {
            return List.of("the worker has not started");
        }
        return current.flatten(accountId, actor, reason, day);
    }

    List<String> exitPosition(long accountId, long positionId, String actor, LocalDate day) throws Exception {
        ExecutionLoop current = loop;
        if (current == null) {
            return List.of("the worker has not started");
        }
        return current.exitPosition(accountId, positionId, actor, day);
    }

    String bookManualExit(long accountId, long positionId, double price, java.time.Instant at, String actor,
                          boolean unchecked) throws Exception {
        ExecutionLoop current = loop;
        if (current == null) {
            throw new IllegalArgumentException("the worker has not started");
        }
        return current.bookManualExit(accountId, positionId, price, at, actor, unchecked);
    }

    com.autotrade.execution.ManualOrders.Pick manualQuote(long accountId, String underlying, String side) {
        return started().manualQuote(accountId, underlying, side, day());
    }

    com.autotrade.execution.ManualOrders.Placed manualBuy(long accountId, String underlying, String side, String symbol,
                                                          String actor) {
        return started().manualBuy(accountId, underlying, side, symbol, actor, day());
    }

    List<String> manualExit(long accountId, String underlying, String actor) throws Exception {
        return started().manualExit(accountId, underlying, actor, day());
    }

    List<java.util.Map<String, Object>> manualToday(long accountId) {
        return new com.autotrade.execution.ManualOrders(dataSource).today(accountId, day());
    }

    /** The copy group a manual or chart order of the account is written in (one this worker serves). */
    long manualGroup(long accountId) {
        return started().manualGroup(accountId);
    }

    private ExecutionLoop started() {
        ExecutionLoop current = loop;
        if (current == null) {
            throw new IllegalArgumentException("the worker has not started");
        }
        return current;
    }

    /** When the loop last finished a tick (null before the first): the watchdog's heartbeat. */
    java.time.Instant lastTick() {
        return lastTick;
    }

    List<ExecutionLoop.AccountStatus> status() {
        ExecutionLoop current = loop;
        return current == null ? List.of() : current.status();
    }

    @Override
    public void destroy() {
        if (loop != null) {
            loop.close();                       // releases every account lease
        }
    }
}
