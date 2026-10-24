package com.autotrade.trading;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.build.CodeVersion;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.time.SessionClock;
import com.autotrade.instruments.InstrumentMaster;
import com.autotrade.instruments.InstrumentStore;
import com.autotrade.instruments.UpstoxInstrumentFile;
import com.autotrade.md.live.LiveFeed;
import com.autotrade.md.live.ReplayAsLiveFeed;
import com.autotrade.md.live.ZtTailFeed;
import com.autotrade.md.zt.ZtReadOnlyDataSource;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.history.CombinedSessionHistory;
import com.autotrade.core.history.IndexWeightFiles;
import com.autotrade.md.store.LiveCapture;
import com.autotrade.md.store.BarSessionHistory;
import com.autotrade.md.store.OwnSessionHistory;
import com.autotrade.md.store.ReferenceStore;
import com.autotrade.md.store.Runs;
import com.autotrade.md.store.SnapshotWriter;
import com.autotrade.md.zt.ZtSessionHistory;
import com.autotrade.upstox.UpstoxCandles;
import com.autotrade.md.zt.ZtSessionSource;
import com.autotrade.risk.RiskLimits;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.FillModel;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.upstox.UpstoxFeed;
import com.autotrade.upstox.UpstoxFeeds;
import com.autotrade.upstox.UpstoxLogin;
import com.autotrade.upstox.ZtTigerTokenSource;
import com.autotrade.upstox.UpstoxToken;
import com.autotrade.upstox.UpstoxTokenStore;
import com.autotrade.upstox.UpstoxUniverse;
import com.zaxxer.hikari.HikariDataSource;

/** Builds and runs the trading session selected on the command line. */
@Component
class SessionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionRunner.class);

    private static final int MAX_STARTS_PER_DAY = 3;
    private static final Duration RESTART_BACKOFF = Duration.ofMinutes(1);

    /** A running session and what it owns. */
    private record Running(LocalDate date, TradingSession session, Thread worker, ScheduledExecutorService timer) {
    }

    private final TradingProperties properties;
    private final DataSource target;
    private final ConfigurableApplicationContext context;
    private volatile TradingSession current;
    private volatile Running running;
    private volatile String mode = "live";
    private SessionClock calendar;
    private final Map<LocalDate, Integer> startsPerDay = new HashMap<>();
    /** Days on which the Upstox feed failed; later starts that day use the zt-tiger-v2 tail. */
    private final java.util.Set<LocalDate> upstoxFailed = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Instant lastStart;

    private final AlertService alerts;
    private volatile Thread shadowThread;
    private volatile LocalDate shadowTriedOn;          // the day this process finished (or found nothing left of) a shadow run
    // a shadow run that failed (a file, or the run) is retried: at most SHADOW_ATTEMPTS a day, SHADOW_RETRY apart
    static final int SHADOW_ATTEMPTS = 3;
    static final java.time.Duration SHADOW_RETRY = java.time.Duration.ofMinutes(10);
    private volatile LocalDate shadowFailedOn;
    private volatile int shadowFailures;
    private volatile Instant shadowRetryAt;

    private volatile Thread signalWorker;                 // live execution L1: the signal source alongside, if any

    SessionRunner(TradingProperties properties, DataSource target, ConfigurableApplicationContext context,
                  AlertService alerts) {
        this.properties = properties;
        this.target = target;
        this.context = context;
        this.alerts = alerts;
    }

    /** True on exchange trading days; false when the scheduler is off (not --mode=auto). */
    boolean tradingDay(LocalDate day) {
        return calendar != null && calendar.isTradingDay(day);
    }

    TradingSession current() {
        return current;
    }

    /** Scheduler state for the UI when no session is running. */
    Map<String, Object> schedule() {
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("mode", mode);
        if ("auto".equals(mode) && calendar != null) {
            schedule.put("nextStart", nextStart(ZonedDateTime.now(MarketTime.IST)).toString());
            schedule.put("dailyWindow", properties.trading().startAt() + "-" + properties.trading().stopAfter() + " IST");
        }
        return schedule;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        mode = first(args, "mode", "live");
        switch (mode) {
            case "serve" -> log.info("serving the UI and history API only (no trading session); open http://127.0.0.1:{}",
                    context.getEnvironment().getProperty("server.port", "8095"));
            case "auto" -> {
                closeOrphanedLiveSessions();
                closeOrphanedShadowSessions();
                startScheduler();
            }
            case "surge-preview" -> {
                // SurgeAlerter prints the day's surge alerts; no session here
                log.info("surge alert preview (nothing is sent)");
                Thread.ofPlatform().start(() -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    exit(0);
                });
            }
            case "fetch-bars" -> {
                // historical one-minute bars from Upstox into hist.candle: --from=YYYY-MM-DD --to=YYYY-MM-DD
                // [--underlyings=NIFTY,SENSEX] [--parts=index,equities,futures,options]
                LocalDate from = LocalDate.parse(first(args, "from", LocalDate.now(MarketTime.IST).minusDays(7).toString()));
                LocalDate to = LocalDate.parse(first(args, "to", LocalDate.now(MarketTime.IST).minusDays(1).toString()));
                List<String> underlyings = List.of(first(args, "underlyings", String.join(",", properties.trading().tradeUnderlyingList())).split(","));
                java.util.Set<BarFetcher.Part> parts = new java.util.LinkedHashSet<>();
                for (String p : first(args, "parts", "index,equities,futures,options").split(",")) {
                    parts.add(BarFetcher.Part.valueOf(p.trim().toUpperCase(java.util.Locale.ROOT)));
                }
                java.util.Optional<UpstoxToken> token = java.util.Optional.empty();
                if (parts.contains(BarFetcher.Part.FUTURES) || parts.contains(BarFetcher.Part.OPTIONS)) {
                    // one short-lived connection for the token (the reader role's connection cap is small)
                    try (HikariDataSource zt = ZtReadOnlyDataSource.open(new ZtReadOnlyDataSource.Settings(
                            properties.source().url(), properties.source().username(), properties.source().password(),
                            properties.source().passwordFile(), properties.source().passwordKey(), "autotrade-bars", 1))) {
                        token = upstoxToken(zt);
                    } catch (Exception e) {
                        log.warn("zt-tiger-v2 token lookup failed ({}); trying the token file", e.getMessage());
                        token = new UpstoxTokenStore(Path.of(properties.upstox().tokenFile())).valid(Instant.now());
                    }
                    log.info("Upstox token: {}", token.isPresent() ? "valid (user " + token.get().userId() + ")" : "none");
                }
                InstrumentMaster master = new InstrumentStore(target).latest(to);
                BarFetcher.Summary summary = new BarFetcher(target, Path.of(properties.upstox().instrumentDir()),
                        Path.of("config", "reference", "weights")).fetch(from, to, underlyings, parts, token, master);
                summary.candles().forEach((what, n) -> log.info("fetched {}: {} candles", what, n));
                summary.problems().forEach(problem -> log.warn("fetch: {}", problem));
                log.info("fetch-bars {}..{} done: {} candles, {} problems", from, to,
                        summary.candles().values().stream().mapToInt(Integer::intValue).sum(), summary.problems().size());
                exit(summary.problems().isEmpty() ? 0 : 2);
            }
            case "fetch-cues" -> {
                // GIFT Nifty quotes from NSE IX, once (the scheduler polls them when autotrade.cues.enabled)
                int added = new CueFetcher(new com.autotrade.md.store.ReferenceStore(target)).fetchGiftNifty();
                log.info("fetch-cues: {} new GIFT Nifty quotes stored", added);
                exit(0);
            }
            case "fetch-participant-oi" -> {
                // NSE participant-wise OI for each weekday in --from..--to (default: the last 10 days); 404 = no file
                LocalDate from = LocalDate.parse(first(args, "from", LocalDate.now(MarketTime.IST).minusDays(10).toString()));
                LocalDate to = LocalDate.parse(first(args, "to", LocalDate.now(MarketTime.IST).toString()));
                CueFetcher fetcher = new CueFetcher(new com.autotrade.md.store.ReferenceStore(target));
                int days = 0, missing = 0, failed = 0;
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    if (d.getDayOfWeek().getValue() >= 6) {
                        continue;
                    }
                    try {
                        java.util.Optional<Integer> rows = fetcher.fetchParticipantOi(d);
                        if (rows.isPresent()) {
                            days++;
                            log.info("participant OI {}: {} rows", d, rows.get());
                        } else {
                            missing++;
                            log.info("participant OI {}: no file (holiday or not published)", d);
                        }
                    } catch (Exception e) {
                        failed++;
                        log.warn("participant OI {}: {}", d, e.getMessage());
                    }
                    Thread.sleep(1000);                       // a polite pace for NSE's archive
                }
                log.info("fetch-participant-oi {}..{}: {} days stored, {} without a file, {} failed", from, to, days,
                        missing, failed);
                exit(failed == 0 ? 0 : 2);
            }
            case "shadow" -> {
                // the shadow strategies on a captured day, by hand (the scheduler does this after each live session)
                LocalDate session = LocalDate.parse(first(args, "session", LocalDate.now(MarketTime.IST).toString()));
                ShadowRunner.Outcome outcome = new ShadowRunner(properties, target).run(session);
                log.info("{}", outcome.summary() == null ? "no shadow session finished" : outcome.summary());
                outcome.failed().forEach(f -> log.error("shadow failed: {}", f));
                exit(outcome.failed().isEmpty() ? 0 : 2);
            }
            case "replay" -> {
                LocalDate session = LocalDate.parse(first(args, "session", LocalDate.now(MarketTime.IST).toString()));
                Running replay = start(session, true);
                if (replay == null) {
                    exit(0);
                    return;
                }
                replay.worker().join();
                Thread signalThread = signalWorker;                // live execution L1: let the signal source drain
                if (signalThread != null) {
                    signalThread.join();
                }
                exit("DONE".equals(replay.session().status().get("status")) ? 0 : 1);
            }
            default -> {
                LocalDate session = LocalDate.parse(first(args, "session", LocalDate.now(MarketTime.IST).toString()));
                closeOrphanedLiveSessions();
                start(session, false);
            }
        }
    }

    /** This process is the only live one for the account: any live session still RUNNING was orphaned. */
    private void closeOrphanedLiveSessions() {
        TradeStore store = new TradeStore(target);
        int closed = store.closeOrphanedLiveSessions(properties.trading().account());
        TradingProperties.SignalSource signal = properties.signalSource();
        if (signal != null && signal.on()) {                  // the signal source's live session (SIGNAL-1) too
            closed += store.closeOrphanedLiveSessions(signal.accountLabel());
        }
        if (closed > 0) {
            log.warn("marked {} live session(s) left RUNNING by an earlier crash or restart as STOPPED", closed);
        }
    }

    /**
     * Only this always-on process runs the after-close shadow replays (a by-hand --mode=shadow run is a separate
     * process: don't run one across a restart of this service), so a shadow session still RUNNING at its start was
     * cut short. Marked STOPPED, its strategy file is pending again and the scheduler resumes it (21 Oct: the 15:52
     * deploy killed shadow replay 937, which stayed RUNNING and blocked that day's shadow run).
     */
    private void closeOrphanedShadowSessions() {
        int closed = new TradeStore(target).closeOrphanedShadowSessions(ShadowRunner.ACCOUNT);
        if (closed > 0) {
            log.warn("marked {} shadow session(s) left RUNNING by an earlier crash or restart as STOPPED; "
                    + "the after-close shadow run resumes them", closed);
        }
    }

    /**
     * Always-on mode: serves the UI and starts the live session itself at {@code start-at} IST on every
     * trading day (exchange holiday file), stops it at {@code stop-after}, and after a restart during
     * market hours starts it again at once (the live engine never trades on stale catch-up data).
     */
    private void startScheduler() throws Exception {
        TradingProperties.Trading t = properties.trading();
        calendar = new SessionClock(FeatureConfig.from(ThresholdConfig.load(Path.of(t.featuresFile())),
                ThresholdConfig.load(Path.of(t.exchangeFile()))));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> Thread.ofPlatform().name("session-scheduler").daemon(true).unstarted(runnable));
        scheduler.scheduleWithFixedDelay(safe(this::tick), 0, 20, TimeUnit.SECONDS);
        log.info("scheduler on: live PAPER sessions {}-{} IST on trading days; next start {}", t.startAt(), t.stopAfter(),
                nextStart(ZonedDateTime.now(MarketTime.IST)));
    }

    private synchronized void tick() {
        ZonedDateTime now = ZonedDateTime.now(MarketTime.IST);
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        LocalTime startAt = LocalTime.parse(properties.trading().startAt());
        LocalTime stopAfter = LocalTime.parse(properties.trading().stopAfter());
        boolean window = calendar.isTradingDay(today) && !time.isBefore(startAt) && time.isBefore(stopAfter);
        shadowAfterClose(today, time, stopAfter);
        dailySummaryAfterClose(today, time, stopAfter);
        Running active = running;
        boolean alive = active != null && active.worker().isAlive();
        if (!window || alive) {
            return;
        }
        boolean todayDone = active != null && active.date().equals(today)
                && "DONE".equals(active.session().status().get("status"));
        int starts = startsPerDay.getOrDefault(today, 0);
        boolean backoff = lastStart != null && Duration.between(lastStart, Instant.now()).compareTo(RESTART_BACKOFF) < 0;
        if (todayDone || starts >= MAX_STARTS_PER_DAY || backoff) {
            return;
        }
        startsPerDay.merge(today, 1, Integer::sum);
        lastStart = Instant.now();
        log.info("scheduler: starting today's live session (attempt {} of {})", starts + 1, MAX_STARTS_PER_DAY);
        try {
            start(today, false);
        } catch (Exception e) {
            log.error("scheduler: could not start today's session: {}", e.getMessage(), e);
        }
    }

    /**
     * Shadow mode: once today's live session is DONE (5 minutes after stop-after), the shadow strategies replay
     * the day from own capture on account SHADOW-1, and the result is sent with the alerts. Each strategy file runs
     * once per day: after a restart only the files without a finished session run. A run with failed files (or one that
     * failed outright) is retried for the files still pending, at most {@link #SHADOW_ATTEMPTS} times a day and
     * {@link #SHADOW_RETRY} apart, so a file that cannot start is neither lost for the day nor retried every minute.
     * Runs on its own thread; never touches the live session.
     */
    private void shadowAfterClose(LocalDate today, LocalTime time, LocalTime stopAfter) {
        List<String> files = properties.trading().shadowStrategyFiles();
        if (files == null || files.isEmpty() || !calendar.isTradingDay(today) || time.isBefore(stopAfter.plusMinutes(5))
                || today.equals(shadowTriedOn) || (shadowThread != null && shadowThread.isAlive())) {
            return;
        }
        if (today.equals(shadowFailedOn) && (shadowFailures >= SHADOW_ATTEMPTS || Instant.now().isBefore(shadowRetryAt))) {
            return;
        }
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(target);
        Integer liveDone = jdbc.queryForObject("select count(*) from trade.session where mode = 'PAPER_LIVE' "
                + "and account = ? and session_date = ? and status = 'DONE'", Integer.class, properties.trading().account(),
                today);                                             // PAPER-1's session, not the signal source's
        if (liveDone == null || liveDone == 0) {
            return;
        }
        ShadowRunner shadow = new ShadowRunner(properties, target);
        List<String> pending = shadow.pending(today);
        if (pending.isEmpty()) {
            shadowTriedOn = today;
            return;
        }
        if (pending.size() < files.size()) {
            log.info("shadow run for {} resumes {} of {} strategy files: {}", today, pending.size(), files.size(), pending);
        }
        shadowThread = Thread.ofPlatform().name("shadow-" + today).start(() -> {
            List<String> failed;
            try {
                ShadowRunner.Outcome outcome = shadow.run(today, pending);
                if (outcome.summary() != null) {
                    alerts.info(outcome.summary());
                }
                failed = outcome.failed();
            } catch (Exception e) {
                log.warn("shadow run for {} failed: {}", today, e.getMessage(), e);
                failed = List.of(String.valueOf(e.getMessage()));
            }
            if (failed.isEmpty()) {
                shadowTriedOn = today;
                return;
            }
            int attempt = today.equals(shadowFailedOn) ? shadowFailures + 1 : 1;
            shadowFailedOn = today;
            shadowFailures = attempt;
            shadowRetryAt = Instant.now().plus(SHADOW_RETRY);
            alerts.raise("shadow", AlertService.Level.WARN, "Shadow run for " + today + ", attempt " + attempt + " of "
                    + SHADOW_ATTEMPTS + ": " + failed.size() + " failed (" + String.join("; ", failed) + ")"
                    + (attempt < SHADOW_ATTEMPTS ? "; retrying the pending files in " + SHADOW_RETRY.toMinutes() + " min" : "; no more retries today"));
        });
    }

    /**
     * The daily P&amp;L summary to Telegram (admin and group), once per trading day after today's live session is DONE
     * (5 minutes after stop-after). A marker file under .local/daily-summary keeps it to one message a day, also after
     * a restart.
     */
    private void dailySummaryAfterClose(LocalDate today, LocalTime time, LocalTime stopAfter) {
        if (!calendar.isTradingDay(today) || time.isBefore(stopAfter.plusMinutes(5))) {
            return;
        }
        Path marker = Path.of(".local", "daily-summary", today + ".sent");
        if (java.nio.file.Files.exists(marker)) {
            return;
        }
        try {
            String text = new DailySummary(target).build(properties.trading().account(), today);
            if (text == null) {
                return;     // no finished live session today (yet)
            }
            alerts.dailySummary(text);
            java.nio.file.Files.createDirectories(marker.getParent());
            java.nio.file.Files.writeString(marker, java.time.Instant.now().toString());
        } catch (Exception e) {
            log.warn("daily summary for {} failed: {}", today, e.getMessage(), e);
        }
    }

    /** The next start time: today if still before the window's start, else the next trading day. */
    private ZonedDateTime nextStart(ZonedDateTime now) {
        LocalTime startAt = LocalTime.parse(properties.trading().startAt());
        LocalTime stopAfter = LocalTime.parse(properties.trading().stopAfter());
        LocalDate day = now.toLocalDate();
        if (calendar.isTradingDay(day) && now.toLocalTime().isBefore(stopAfter)) {
            return now.toLocalTime().isBefore(startAt) ? day.atTime(startAt).atZone(MarketTime.IST) : now;
        }
        do {
            day = day.plusDays(1);
        } while (!calendar.isTradingDay(day));
        return day.atTime(startAt).atZone(MarketTime.IST);
    }

    /** Builds and starts one session on a worker thread; null when {@code session} is not a trading day. */
    private Running start(LocalDate session, boolean replay) throws Exception {
        TradingProperties.Trading t = properties.trading();

        List<ThresholdConfig> strategyFiles = t.strategyFileList().stream()
                .map(file -> ThresholdConfig.load(Path.of(file))).toList();
        ThresholdConfig featuresFile = ThresholdConfig.load(Path.of(t.featuresFile()));
        ThresholdConfig exchangeFile = ThresholdConfig.load(Path.of(t.exchangeFile()));
        ThresholdConfig costsFile = ThresholdConfig.load(Path.of(t.costsFile()));
        // live execution L1: a signal-source session trades the virtual SIGNAL-1 account under signal-source.v1
        TradingProperties.SignalSource signal = properties.signalSource();
        boolean signalOn = signal != null && signal.on();
        // alongside: the signal source runs next to this session on a tee of its feed (always live; replay with tee)
        boolean alongside = signalOn && (!replay || signal.teeOn());
        boolean signalSource = signalOn && !alongside;      // replay without tee: this session is the signal source
        String account = signalSource ? signal.accountLabel() : t.account();
        ThresholdConfig riskFile = ThresholdConfig.load(Path.of(signalSource ? signal.riskFileOr() : t.riskFile()));
        FeatureConfig features = FeatureConfig.from(featuresFile, exchangeFile);
        if (!new SessionClock(features).isTradingDay(session)) {
            log.warn("{} is not a trading day (weekend or exchange holiday); no session", session);
            return null;
        }
        List<StrategyFactory> strategies = new ArrayList<>();
        for (ThresholdConfig file : strategyFiles) {
            StrategyFactory factory = Strategies.create(file);
            for (String section : factory.requiredFeatureSections()) {
                if (!featuresFile.has(section)) {
                    throw new IllegalStateException(file.sourceName() + " needs feature section '" + section
                            + "' (features file " + t.featuresFile() + " lacks it)");
                }
            }
            strategies.add(factory);
        }
        // two versions of one strategy (etr-v1 and etr-v5) run under distinct ids: <id>@<version> for the later one
        List<StrategyFactory> unique = com.autotrade.strategy.VersionedFactory.unique(strategies);
        strategies.clear();
        strategies.addAll(unique);
        Map<String, String> hashes = new LinkedHashMap<>();
        List<ThresholdConfig> files = new ArrayList<>(strategyFiles);
        files.addAll(List.of(featuresFile, exchangeFile, costsFile, riskFile));
        for (ThresholdConfig file : files) {
            hashes.put(file.sourceName(), file.contentHash());
        }

        // a bar replay reads only hist.candle: no zt-tiger-v2 connection at all (and none of its connection cap)
        boolean bars = replay && t.replayFromBars();
        HikariDataSource source = bars ? null : ZtReadOnlyDataSource.open(new ZtReadOnlyDataSource.Settings(
                properties.source().url(), properties.source().username(), properties.source().password(),
                properties.source().passwordFile(), properties.source().passwordKey(), "autotrade-trading-core", 6));
        InstrumentMaster instruments = new InstrumentStore(target).latest(session);
        if (instruments.size() == 0) {
            log.warn("no contract master loaded for {}; using quote facts and default tick/freeze limits", session);
        }
        LiveFeed feed = null;
        if (replay) {
            // replay-speed (market seconds per second; 0 = as fast as possible) lets the UI be watched
            // replay-source own: auto-trade's own live capture, the same ticks the live session saw (zt: zt-tiger-v2's)
            com.autotrade.core.event.SessionEventSource events = bars
                    ? new BarReplaySource(target, new IndexWeightFiles(Path.of("config", "reference", "weights")))
                    : t.replayFromOwnCapture() ? new OwnCaptureSource(target) : new ZtSessionSource(source, false);
            feed = new ReplayAsLiveFeed(events, session, t.underlyings(), t.replaySpeed());
        } else if ("upstox".equals(t.feed()) && !upstoxFailed.contains(session)) {
            // Upstox feed (auction data, futures book, VIX) with the token zt-tiger-v2 holds; anything
            // missing or failing falls back to tailing zt-tiger-v2's capture.
            try {
                java.util.Optional<UpstoxToken> token = upstoxToken(source);
                if (token.isEmpty()) {
                    log.warn("no valid Upstox token (sign in to Upstox in zt-tiger-v2); using the zt-tiger-v2 tail today");
                } else {
                    refreshInstruments(session, t.underlyings());
                    instruments = new InstrumentStore(target).latest(session);
                    feed = upstoxFeed(session, t.underlyings(), instruments, new ZtSessionHistory(source), token.get());
                    log.info("live feed: Upstox (user {}, token until {})", token.get().userId(), token.get().expiresAt());
                }
            } catch (Exception e) {
                log.warn("Upstox feed unavailable ({}); using the zt-tiger-v2 tail today", e.getMessage());
            }
        }
        if (feed == null) {
            feed = new ZtTailFeed(source, session, t.underlyings(), Duration.ofMillis(t.pollIntervalMs()),
                    Duration.ofMillis(t.recheckWindowMs()));
        }
        // The Upstox feed is recorded into auto-trade's own md.* tables (auction data, futures book, VIX
        // included): zt-tiger-v2 keeps only its newest sessions and none of that data.
        UpstoxFeed upstoxFeed = feed instanceof UpstoxFeed u ? u : null;
        LiveCapture capture = null;
        if (upstoxFeed != null) {
            capture = new LiveCapture(target, session, "UPSTOX_V3_LIVE", CodeVersion.current());
            feed = new CapturingFeed(feed, capture);
        }
        // live execution L1: the signal source's branch of this feed (this session first, the branch never blocks it live)
        QueueFeed signalFeed = null;
        if (alongside) {
            signalFeed = new QueueFeed(feed.name() + "+signal", replay ? QueueFeed.Overflow.BLOCK : QueueFeed.Overflow.FAIL,
                    200_000, feed.replay());
            feed = new TeeFeed(feed, signalFeed);
        }
        ReferenceStore referenceStore = new ReferenceStore(target);
        LiveReference liveReference = replay ? null : new LiveReference(referenceStore, session, new UpstoxCandles());
        SessionHistory history = bars
                ? new CombinedSessionHistory(new BarSessionHistory(target), new OwnSessionHistory(target), new BarSessionHistory(target))
                : new CombinedSessionHistory(new ZtSessionHistory(source), new OwnSessionHistory(target),
                        replay ? referenceStore : liveReference);
        // features v12 (A-090): older sessions' spot bars and the OI baseline from the stored one-minute bars
        history = com.autotrade.md.store.SessionHistories.forFeatures(history, features.barBackfill(), target);
        // risk v8: live sizes from the account's equity (starting capital + realised net of finished live sessions);
        // a replay uses the starting capital, so its results stay comparable day to day, unless replay-equity chains days
        RiskLimits baseRisk = RiskLimits.from(riskFile);
        RiskLimits risk = EquityLedger.apply(baseRisk,
                replay ? t.replayRealisedNet(baseRisk.startingCapital())
                        : new EquityLedger(target).realisedNet(account, baseRisk.equityFrom()));
        if (risk.equityMode()) {
            log.info("sizing from equity: capital {} (starting {}), daily loss limit {}, budget scale {}",
                    Math.round(risk.capital()), Math.round(risk.startingCapital()), Math.round(risk.dailyLossLimit()),
                    String.format("%.2f", risk.budgetScale()));
        }
        TradingSession.Settings settings = new TradingSession.Settings(
                replay ? TradingSession.Mode.PAPER_REPLAY : TradingSession.Mode.PAPER_LIVE, account, session,
                t.underlyings(), t.tradeUnderlyingList(), features, history, strategies,
                risk, CostModel.from(costsFile), FillModel.from(costsFile, t.fillModel()),
                instruments, hashes, CodeVersion.current());
        TradingSession trading = new TradingSession(settings, feed, new TradeStore(target));
        if (signalSource) {
            com.autotrade.account.AccountStore accounts = new com.autotrade.account.AccountStore(target);
            long tenant = accounts.ensureTenant(signal.tenantName());
            long owner = accounts.ensureUser(signal.ownerSubjectOr(), "owner");
            accounts.ensureMembership(tenant, owner, "OWNER");
            long signalAccount = accounts.ensureSignalSourceAccount(tenant, owner, account, baseRisk.startingCapital(),
                    session, signal.riskFileOr());
            long copyGroup = accounts.ensureCopyGroup(tenant, signal.copyGroupName(), signalAccount);
            trading.publishSignals(new SignalRecorder(new com.autotrade.execution.SignalDecisionStore(target), copyGroup,
                    baseRisk.startingCapital(), strategies.stream().map(StrategyFactory::id).toList()));
            log.info("signal source: account {} (risk {}), copy group {} (id {})", account, signal.riskFileOr(),
                    signal.copyGroupName(), copyGroup);
        }
        if (alongside) {
            startSignalAlongside(signal, signalFeed, session, replay, t, features, history, strategies, costsFile,
                    instruments, hashes);
        }
        current = trading;

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        if (!replay) {
            timer.scheduleAtFixedRate(safe(trading::heartbeat), 1, 1, TimeUnit.SECONDS);
            timer.scheduleAtFixedRate(safe(() -> {
                LocalTime now = ZonedDateTime.now(MarketTime.IST).toLocalTime();
                if (!now.isBefore(features.marketOpen()) && now.isBefore(features.derivativesClose().plusMinutes(1))) {
                    liveReference.poll();
                }
            }), 5, 30, TimeUnit.SECONDS);
            LocalTime stopAfter = LocalTime.parse(t.stopAfter());
            if (upstoxFeed != null) {
                UpstoxFeed upstox = upstoxFeed;
                // A silent Upstox feed in market hours fails the session; the scheduler restarts it on the tail.
                timer.scheduleAtFixedRate(safe(() -> {
                    LocalTime now = ZonedDateTime.now(MarketTime.IST).toLocalTime();
                    Instant last = upstox.lastEventTime();
                    boolean marketHours = now.isAfter(LocalTime.of(9, 16)) && now.isBefore(LocalTime.of(15, 39));
                    if (marketHours && (last == null || Duration.between(last, Instant.now()).toSeconds() > 60)) {
                        upstoxFailed.add(session);
                        upstox.fail("no market data for over 60 s (" + upstox.connection() + ")");
                    }
                }), 30, 15, TimeUnit.SECONDS);
            }
            timer.scheduleAtFixedRate(safe(() -> {
                ZonedDateTime now = ZonedDateTime.now(MarketTime.IST);
                if (!now.toLocalTime().isBefore(stopAfter) || now.toLocalDate().isAfter(session)) {
                    trading.stop();
                }
            }), 10, 10, TimeUnit.SECONDS);
        }
        boolean upstoxFeedUsed = upstoxFeed != null;
        LiveCapture recorder = capture;
        if (recorder != null) {
            timer.scheduleAtFixedRate(safe(recorder::flush), 5, 5, TimeUnit.SECONDS);
        }
        Runs runs = new Runs(target);
        Long snapshotRun = null;
        SnapshotWriter snapshots = null;
        if (!replay || t.storeReplaySnapshots()) {
            // Live feature snapshots: the decision evidence, and IV history for later sessions. A replay's (opt-in) go
            // under their own run kind, which nothing reading LIVE_FEATURES (IV history, chart levels) ever sees.
            snapshotRun = runs.start(replay ? "REPLAY_FEATURES" : "LIVE_FEATURES", CodeVersion.current(), feed.name(), List.of(session),
                    t.underlyings(), new tools.jackson.databind.json.JsonMapper().writeValueAsString(hashes));
            snapshots = new SnapshotWriter(target, snapshotRun);
            trading.recordSnapshots(snapshots);
            timer.scheduleAtFixedRate(safe(snapshots::flush), 60, 60, TimeUnit.SECONDS);
        }
        SnapshotWriter snapshotWriter = snapshots;
        Long snapshotRunId = snapshotRun;
        Thread worker = Thread.ofPlatform().name("trading-session-" + session).start(() -> {
            Map<String, Object> summary = trading.run();
            if (recorder != null) {
                recorder.close();
            }
            if (snapshotWriter != null) {
                try {
                    snapshotWriter.close();
                    runs.finish(snapshotRunId, "{\"snapshots\": " + snapshotWriter.written() + "}");
                } catch (Exception e) {
                    log.warn("live snapshot run not finalised: {}", e.getMessage());
                }
            }
            if (upstoxFeedUsed && "FAILED".equals(trading.status().get("status"))) {
                upstoxFailed.add(session);
            }
            timer.shutdownNow();
            if (source != null) {
                    source.close();                      // none for a bar replay
                }
            log.info("session summary: {}", summary);
        });
        Running started = new Running(session, trading, worker, timer);
        running = started;
        return started;
    }

    /**
     * Live execution L1: starts the copy group's signal source next to the session, on its own thread and heartbeat,
     * fed by {@code feed} (the tee's branch). It trades the virtual account (SIGNAL-1, signal-source risk) with the same
     * strategies, features and history, records decisions only (no capture, no feature snapshots) and ends when the
     * session's feed ends. A failure stops it alone; the session is never touched.
     */
    private void startSignalAlongside(TradingProperties.SignalSource signal, QueueFeed feed, LocalDate session,
                                      boolean replay, TradingProperties.Trading t, FeatureConfig features,
                                      SessionHistory history, List<StrategyFactory> strategies,
                                      ThresholdConfig costsFile, InstrumentMaster instruments,
                                      Map<String, String> hashes) {
        ThresholdConfig riskFile = ThresholdConfig.load(Path.of(signal.riskFileOr()));
        RiskLimits risk = RiskLimits.from(riskFile);
        Map<String, String> signalHashes = new LinkedHashMap<>(hashes);
        signalHashes.put(riskFile.sourceName(), riskFile.contentHash());
        TradingSession.Settings settings = new TradingSession.Settings(
                replay ? TradingSession.Mode.PAPER_REPLAY : TradingSession.Mode.PAPER_LIVE, signal.accountLabel(), session,
                t.underlyings(), t.tradeUnderlyingList(), features, history, strategies, risk, CostModel.from(costsFile),
                FillModel.from(costsFile, t.fillModel()), instruments, signalHashes, CodeVersion.current());
        TradingSession signalSession = new TradingSession(settings, feed, new TradeStore(target));
        com.autotrade.account.AccountStore accounts = new com.autotrade.account.AccountStore(target);
        long tenant = accounts.ensureTenant(signal.tenantName());
        long owner = accounts.ensureUser(signal.ownerSubjectOr(), "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signalAccount = accounts.ensureSignalSourceAccount(tenant, owner, signal.accountLabel(),
                risk.startingCapital(), session, signal.riskFileOr());
        long copyGroup = accounts.ensureCopyGroup(tenant, signal.copyGroupName(), signalAccount);
        signalSession.publishSignals(new SignalRecorder(new com.autotrade.execution.SignalDecisionStore(target), copyGroup,
                risk.startingCapital(), strategies.stream().map(StrategyFactory::id).toList()));
        ScheduledExecutorService signalTimer = Executors.newSingleThreadScheduledExecutor();
        if (!replay) {
            signalTimer.scheduleAtFixedRate(safe(signalSession::heartbeat), 1, 1, TimeUnit.SECONDS);
        }
        signalWorker = Thread.ofPlatform().name("signal-source-" + session).start(() -> {
            String failure = null;
            try {
                Map<String, Object> summary = signalSession.run();
                log.info("signal source summary: {}", summary);
                if ("FAILED".equals(String.valueOf(signalSession.status().get("status")))) {
                    failure = "the signal session failed (see the trading-core log)";
                }
            } catch (RuntimeException e) {
                failure = String.valueOf(e.getMessage());
                log.error("signal source failed (the session continues): {}", e.getMessage());
            } finally {
                signalTimer.shutdownNow();
                if (feed.failure() != null) {
                    failure = feed.failure();
                    log.error("signal source stopped: {}", feed.failure());
                }
                feed.release();                       // nothing more is queued for a branch that no longer reads
                if (failure != null && !replay && alerts != null) {   // code review 24 Oct 2026: a silent stop strands followers
                    alerts.raise("signal-source", AlertService.Level.CRITICAL, "Signal source " + signal.accountLabel()
                            + " stopped: " + failure + ". PAPER-1 continues; followers get no further signals today, so "
                            + "their open positions get no EXIT signal (the worker's end-of-day square-off closes them).");
                }
            }
        });
        log.info("signal source alongside: account {} (risk {}), copy group {} (id {}), tee {}", signal.accountLabel(),
                signal.riskFileOr(), signal.copyGroupName(), copyGroup, feed.name());
    }

    /**
     * Today's Upstox token: zt-tiger-v2's (token-source zt, read-only, never re-issued by auto-trade),
     * else auto-trade's own token file; checked against the profile API before use.
     */
    java.util.Optional<UpstoxToken> upstoxToken(DataSource ztDatabase) throws IOException {
        TradingProperties.Upstox upstox = properties.upstox();
        java.util.Optional<UpstoxToken> token = java.util.Optional.empty();
        if (!"file".equals(upstox.tokenSource())) {
            token = new ZtTigerTokenSource(ztDatabase).current(Instant.now());
        }
        if (token.isEmpty()) {
            token = new UpstoxTokenStore(Path.of(upstox.tokenFile())).valid(Instant.now());
        }
        if (token.isPresent()) {
            try {
                UpstoxLogin.verify(token.get(), java.net.http.HttpClient.newHttpClient());
            } catch (Exception e) {
                log.warn("the Upstox token was refused: {}", e.getMessage());
                return java.util.Optional.empty();
            }
        }
        return token;
    }

    /**
     * Downloads today's Upstox contract files (public, no token) unless present, and loads them into
     * ref.instrument. Keeps the previous master when the download fails.
     */
    private void refreshInstruments(LocalDate session, List<String> underlyings) {
        Path directory = Path.of(properties.upstox().instrumentDir());
        java.util.Set<String> wanted = new java.util.HashSet<>(underlyings);
        InstrumentStore store = new InstrumentStore(target);
        for (String exchange : List.of("NSE", "BSE")) {
            Path file = directory.resolve(exchange + "-" + session + ".json.gz");
            if (Files.exists(file)) {
                continue;
            }
            try {
                Files.createDirectories(directory);
                Path partial = directory.resolve(exchange + "-" + session + ".json.gz.part");
                java.net.http.HttpResponse<Path> response = java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()
                        .send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                "https://assets.upstox.com/market-quote/instruments/exchange/" + exchange + ".json.gz"))
                                .timeout(Duration.ofSeconds(60)).GET().build(),
                                java.net.http.HttpResponse.BodyHandlers.ofFile(partial));
                if (response.statusCode() != 200) {
                    Files.deleteIfExists(partial);
                    throw new IOException("HTTP " + response.statusCode());
                }
                Files.move(partial, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                long id = store.save(session, "UPSTOX:" + file.getFileName(), file,
                        UpstoxInstrumentFile.read(file, wanted));
                log.info("contract master {} loaded (snapshot {})", file.getFileName(), id);
            } catch (Exception e) {
                log.warn("could not refresh the {} contract file: {}", exchange, e.getMessage());
            }
        }
    }

    /** Upstox V3 feed: needs a valid token and the contract files. */
    private LiveFeed upstoxFeed(LocalDate session, List<String> underlyings, InstrumentMaster instruments,
                                ZtSessionHistory history, UpstoxToken token) throws Exception {
        TradingProperties.Upstox upstox = properties.upstox();
        IndexWeightFiles official = new IndexWeightFiles(Path.of("config", "reference", "weights"));
        return UpstoxFeeds.build(token, instruments, session, underlyings, Path.of(upstox.instrumentDir()),
                underlying -> {
                    Map<String, Double> weights = official.weights(underlying, session);
                    return weights.isEmpty() ? history.constituentWeights(underlying, session) : weights;
                }, upstox.strikesEachSide(), upstox.recenterStrikes());
    }

    private void exit(int code) {
        int exitCode = SpringApplication.exit(context, () -> code);
        System.exit(exitCode);
    }

    private static Runnable safe(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("scheduled task failed", e);
            }
        };
    }

    private static String first(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.getFirst();
    }
}
