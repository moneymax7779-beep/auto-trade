package com.autotrade.strategy.egb;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** Builds the setup families (A-036 to A-040, A-064, A-065, A-066, A-076, A-081, A-082) from their versioned files. */
public final class SetupFactory implements StrategyFactory {

    static final Map<String, Function<ThresholdConfig, Strategy>> FAMILIES = Map.ofEntries(
            Map.entry("opening-reclaim", OpeningReclaim::new),
            Map.entry("break-retest", BreakRetest::new),
            Map.entry("failed-breakout", FailedBreakout::new),
            Map.entry("range-fade", RangeFade::new),
            Map.entry("auction-pressure", AuctionPressure::new),
            Map.entry("break-retest-runner", BreakRetest::new),   // A-065: brt-v1's entry, runner exits from its file
            Map.entry("trend-day-rider", TrendDayRider::new),   // A-064
            Map.entry("ma-cross", MaCross::new),   // A-066
            Map.entry("opening-range-continuation", OpeningRangeContinuation::new),   // A-076
            Map.entry("trend-state", TrendState::new),   // A-081
            Map.entry("opening-range-breakout", OpeningRangeBreakout::new),   // A-082
            Map.entry("opening-drive-momentum", OpeningDriveMomentum::new),   // A-085
            Map.entry("zone-break-flow", ZoneBreakFlow::new),   // A-131 (zbf-v10 live PAPER from 19 Oct, A-148)
            Map.entry("atm-oi-burst", AtmOiBurst::new),   // A-158
            Map.entry("market-phase-squeeze", SetupFactory::marketPhase),   // A-239 (v1), A-240 (v2 relaxed)
            Map.entry("market-phase-expansion", SetupFactory::marketPhase),
            Map.entry("market-phase-avalanche", SetupFactory::marketPhase),
            Map.entry("playbook-squeeze", c -> new Playbook(c, "NIFTY")),   // A-244 (T1 / RUNNER parts per file)
            Map.entry("playbook-squeeze-runner", c -> new Playbook(c, "NIFTY")),
            Map.entry("playbook-avalanche", c -> new Playbook(c, "NIFTY")),
            Map.entry("playbook-avalanche-runner", c -> new Playbook(c, "NIFTY")),
            Map.entry("playbook-vwap", c -> new Playbook(c, "NIFTY")),
            Map.entry("playbook-vwap-runner", c -> new Playbook(c, "NIFTY")),
            Map.entry("playbook-box", c -> new Playbook(c, "NIFTY")),
            Map.entry("playbook-mac-squeeze", c -> new Playbook(c, "NIFTY")),   // A-246
            Map.entry("playbook-mac-squeeze-runner", c -> new Playbook(c, "NIFTY")),
            Map.entry("playbook-orl-retest", c -> new Playbook(c, "NIFTY")),
            Map.entry("playbook-stand-aside-probe", c -> new Playbook(c, "NIFTY")),
            Map.entry("oi-burst-cross", c -> new OiBurstCross(c, "NIFTY")),   // A-251 (per-index threshold)
            Map.entry("oi-burst-allday", c -> new OiBurstCross(c, "NIFTY")));   // A-258: obc's signal all day

    /** A-240: a file with {@code setup.relaxed: true} is the relaxed rows (per-index parameters); else A-239's. */
    private static boolean relaxed(ThresholdConfig c) {
        return c.has("setup.relaxed") && c.getBoolean("setup.relaxed");
    }

    private static Strategy marketPhase(ThresholdConfig c) {
        return relaxed(c) ? new MarketPhaseRelaxed(c, "NIFTY") : new MarketPhase(c);
    }

    private final ThresholdConfig config;
    private final Function<ThresholdConfig, Strategy> family;

    public SetupFactory(ThresholdConfig config) {
        this.family = FAMILIES.get(config.strategy());
        if (family == null) {
            throw new IllegalArgumentException("not a setup-family file: " + config.sourceName());
        }
        this.config = config;
        family.apply(config);                                         // fail at load on a missing key, not mid-session
        if (config.strategy().startsWith("market-phase-") && relaxed(config)) {
            new MarketPhaseRelaxed(config, "SENSEX");                    // A-240: the SENSEX keys too
        }
        if (config.strategy().startsWith("playbook-")) {
            new Playbook(config, "SENSEX");                              // A-244: the SENSEX keys too
        }
        if (config.strategy().startsWith("oi-burst-")) {
            new OiBurstCross(config, "SENSEX");                          // A-251: the SENSEX threshold too
        }
    }

    @Override
    public String id() {
        return config.strategy();
    }

    @Override
    public String configHash() {
        return config.contentHash();
    }

    @Override
    public String version() {
        return config.version();
    }

    @Override
    public double premiumStopPct() {
        return config.getDouble("exits.premium_stop_pct");
    }

    @Override
    public double premiumBudget() {
        return config.getDouble("position.premium_budget");
    }

    @Override
    public int intendedLots() {
        return config.getInt("position.intended_lots");
    }

    @Override
    public Strategy create(String underlying, LocalDate session) {
        if (config.strategy().startsWith("market-phase-") && relaxed(config)) {
            return new MarketPhaseRelaxed(config, underlying);
        }
        if (config.strategy().startsWith("playbook-")) {
            return new Playbook(config, underlying);                     // A-244: per-index candles and buffers
        }
        if (config.strategy().startsWith("oi-burst-")) {
            return new OiBurstCross(config, underlying);                 // A-251: per-index threshold
        }
        return family.apply(config);
    }
}
