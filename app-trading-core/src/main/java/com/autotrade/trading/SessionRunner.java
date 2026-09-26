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
import com.autotrade.md.zt.ZtSessionHistory;
import com.autotrade.md.zt.ZtSessionSource;
import com.autotrade.risk.RiskLimits;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.FillModel;
import com.autotrade.strategy.ecr.EarlyConfirmRunnerFactory;
import com.autotrade.upstox.UpstoxFeed;
import com.autotrade.upstox.UpstoxToken;
import com.autotrade.upstox.UpstoxTokenStore;
import com.autotrade.upstox.UpstoxUniverse;
import com.zaxxer.hikari.HikariDataSource;

/** Builds and runs the trading session selected on the command line. */
@Component
class SessionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionRunner.class);

    private final TradingProperties properties;
    private final DataSource target;
    private final ConfigurableApplicationContext context;
    private volatile TradingSession current;

    SessionRunner(TradingProperties properties, DataSource target, ConfigurableApplicationContext context) {
        this.properties = properties;
        this.target = target;
        this.context = context;
    }

    TradingSession current() {
        return current;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String mode = first(args, "mode", "live");
        if (mode.equals("serve")) {
            log.info("serving the UI and history API only (no trading session); open http://127.0.0.1:{}",
                    context.getEnvironment().getProperty("server.port", "8095"));
            return;
        }
        LocalDate session = LocalDate.parse(first(args, "session", LocalDate.now(MarketTime.IST).toString()));
        TradingProperties.Trading t = properties.trading();

        ThresholdConfig strategyFile = ThresholdConfig.load(Path.of(t.strategyFile()));
        ThresholdConfig featuresFile = ThresholdConfig.load(Path.of(t.featuresFile()));
        ThresholdConfig exchangeFile = ThresholdConfig.load(Path.of(t.exchangeFile()));
        ThresholdConfig costsFile = ThresholdConfig.load(Path.of(t.costsFile()));
        ThresholdConfig riskFile = ThresholdConfig.load(Path.of(t.riskFile()));
        FeatureConfig features = FeatureConfig.from(featuresFile, exchangeFile);
        if (!new SessionClock(features).isTradingDay(session)) {
            log.warn("{} is not a trading day; nothing to do", session);
            exit(0);
            return;
        }
        EarlyConfirmRunnerFactory strategies = new EarlyConfirmRunnerFactory(strategyFile);
        Map<String, String> hashes = new LinkedHashMap<>();
        for (ThresholdConfig file : List.of(strategyFile, featuresFile, exchangeFile, costsFile, riskFile)) {
            hashes.put(file.sourceName(), file.contentHash());
        }

        HikariDataSource source = ZtReadOnlyDataSource.open(new ZtReadOnlyDataSource.Settings(
                properties.source().url(), properties.source().username(), properties.source().password(),
                properties.source().passwordFile(), properties.source().passwordKey(), "autotrade-trading-core", 6));
        InstrumentMaster instruments = new InstrumentStore(target).latest(session);
        if (instruments.size() == 0) {
            log.warn("no contract master loaded for {}; using quote facts and default tick/freeze limits", session);
        }
        boolean replay = mode.equals("replay");
        LiveFeed feed;
        if (replay) {
            feed = new ReplayAsLiveFeed(new ZtSessionSource(source, false), session, t.underlyings());
        } else if ("upstox".equals(t.feed())) {
            feed = upstoxFeed(session, t.underlyings(), instruments, new ZtSessionHistory(source));
        } else {
            feed = new ZtTailFeed(source, session, t.underlyings(), Duration.ofMillis(t.pollIntervalMs()),
                    Duration.ofMillis(t.recheckWindowMs()));
        }
        TradingSession.Settings settings = new TradingSession.Settings(
                replay ? TradingSession.Mode.PAPER_REPLAY : TradingSession.Mode.PAPER_LIVE, t.account(), session,
                t.underlyings(), features, new ZtSessionHistory(source), strategies, strategies.premiumStopPct(),
                RiskLimits.from(riskFile), CostModel.from(costsFile), FillModel.from(costsFile, t.fillModel()),
                instruments, hashes, CodeVersion.current());
        current = new TradingSession(settings, feed, new TradeStore(target));

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        if (!replay) {
            timer.scheduleAtFixedRate(safe(current::heartbeat), 1, 1, TimeUnit.SECONDS);
            LocalTime stopAfter = LocalTime.parse(t.stopAfter());
            timer.scheduleAtFixedRate(safe(() -> {
                if (!ZonedDateTime.now(MarketTime.IST).toLocalTime().isBefore(stopAfter)) {
                    current.stop();
                }
            }), 10, 10, TimeUnit.SECONDS);
        }
        Thread worker = Thread.ofPlatform().name("trading-session").start(() -> {
            Map<String, Object> summary = current.run();
            timer.shutdownNow();
            source.close();
            log.info("session summary: {}", summary);
            if (replay) {
                exit("DONE".equals(current.status().get("status")) ? 0 : 1);
            }
        });
        if (replay) {
            worker.join();
        }
    }

    /** Upstox V3 feed: needs today's token (bin/autotrade upstox-login) and the contract files. */
    private LiveFeed upstoxFeed(LocalDate session, List<String> underlyings, InstrumentMaster instruments,
                                ZtSessionHistory history) throws Exception {
        TradingProperties.Upstox upstox = properties.upstox();
        UpstoxToken token = new UpstoxTokenStore(Path.of(upstox.tokenFile())).valid(Instant.now())
                .orElseThrow(() -> new IllegalStateException("no valid Upstox token; run: bin/autotrade upstox-login"));
        if (instruments.size() == 0) {
            throw new IllegalStateException("the Upstox feed needs a contract master: bin/autotrade instruments --file ...");
        }
        Map<String, List<UpstoxUniverse.Constituent>> constituents = new LinkedHashMap<>();
        for (String underlying : underlyings) {
            String segment = underlying.equals("SENSEX") ? "BSE_EQ" : "NSE_EQ";
            Path file = newest(Path.of(upstox.instrumentDir()), underlying.equals("SENSEX") ? "BSE-" : "NSE-");
            Map<String, Double> weights = history.constituentWeights(underlying, session);
            List<UpstoxUniverse.Constituent> list = new ArrayList<>();
            for (UpstoxInstrumentFile.Listing listing : UpstoxInstrumentFile.readEquities(file, segment, weights.keySet())) {
                list.add(new UpstoxUniverse.Constituent(listing, weights.get(listing.tradingSymbol())));
            }
            log.info("{}: {} of {} constituents found in {}", underlying, list.size(), weights.size(), file.getFileName());
            constituents.put(underlying, list);
        }
        UpstoxUniverse universe = new UpstoxUniverse(instruments, session, underlyings, constituents);
        return new UpstoxFeed(token, universe, underlyings, upstox.strikesEachSide(), upstox.recenterStrikes());
    }

    private static Path newest(Path directory, String prefix) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(f -> f.getFileName().toString().startsWith(prefix)).max(Comparator.naturalOrder())
                    .orElseThrow(() -> new IllegalStateException("no " + prefix + "*.json.gz in " + directory));
        }
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
