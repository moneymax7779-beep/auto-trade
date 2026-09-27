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

    SessionRunner(TradingProperties properties, DataSource target, ConfigurableApplicationContext context) {
        this.properties = properties;
        this.target = target;
        this.context = context;
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
                startScheduler();
            }
            case "replay" -> {
                LocalDate session = LocalDate.parse(first(args, "session", LocalDate.now(MarketTime.IST).toString()));
                Running replay = start(session, true);
                if (replay == null) {
                    exit(0);
                    return;
                }
                replay.worker().join();
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
        int closed = new TradeStore(target).closeOrphanedLiveSessions(properties.trading().account());
        if (closed > 0) {
            log.warn("marked {} live session(s) left RUNNING by an earlier crash or restart as STOPPED", closed);
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
        ThresholdConfig riskFile = ThresholdConfig.load(Path.of(t.riskFile()));
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
        Map<String, String> hashes = new LinkedHashMap<>();
        List<ThresholdConfig> files = new ArrayList<>(strategyFiles);
        files.addAll(List.of(featuresFile, exchangeFile, costsFile, riskFile));
        for (ThresholdConfig file : files) {
            hashes.put(file.sourceName(), file.contentHash());
        }

        HikariDataSource source = ZtReadOnlyDataSource.open(new ZtReadOnlyDataSource.Settings(
                properties.source().url(), properties.source().username(), properties.source().password(),
                properties.source().passwordFile(), properties.source().passwordKey(), "autotrade-trading-core", 6));
        InstrumentMaster instruments = new InstrumentStore(target).latest(session);
        if (instruments.size() == 0) {
            log.warn("no contract master loaded for {}; using quote facts and default tick/freeze limits", session);
        }
        LiveFeed feed = null;
        if (replay) {
            feed = new ReplayAsLiveFeed(new ZtSessionSource(source, false), session, t.underlyings());
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
        ReferenceStore referenceStore = new ReferenceStore(target);
        LiveReference liveReference = replay ? null : new LiveReference(referenceStore, session, new UpstoxCandles());
        SessionHistory history = new CombinedSessionHistory(new ZtSessionHistory(source),
                new OwnSessionHistory(target), replay ? referenceStore : liveReference);
        TradingSession.Settings settings = new TradingSession.Settings(
                replay ? TradingSession.Mode.PAPER_REPLAY : TradingSession.Mode.PAPER_LIVE, t.account(), session,
                t.underlyings(), features, history, strategies,
                RiskLimits.from(riskFile), CostModel.from(costsFile), FillModel.from(costsFile, t.fillModel()),
                instruments, hashes, CodeVersion.current());
        TradingSession trading = new TradingSession(settings, feed, new TradeStore(target));
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
        if (!replay) {
            // Live feature snapshots: the decision evidence, and IV history for later sessions.
            snapshotRun = runs.start("LIVE_FEATURES", CodeVersion.current(), feed.name(), List.of(session),
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
            source.close();
            log.info("session summary: {}", summary);
        });
        Running started = new Running(session, trading, worker, timer);
        running = started;
        return started;
    }

    /**
     * Today's Upstox token: zt-tiger-v2's (token-source zt, read-only, never re-issued by auto-trade),
     * else auto-trade's own token file; checked against the profile API before use.
     */
    private java.util.Optional<UpstoxToken> upstoxToken(DataSource ztDatabase) throws IOException {
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
