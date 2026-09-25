package com.autotrade.strategy.ecr;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.time.SessionPhase;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.MarketState;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.SideView;
import com.autotrade.strategy.Stage;
import com.autotrade.strategy.Strategy;

/**
 * Early → Confirm → Runner on the opening range (CE at the ORH, PE at the ORL), one campaign per
 * side per session and one open position per underlying.
 *
 * <ul>
 *   <li>WATCH: spot within {@code watch_distance_atr} of the level.</li>
 *   <li>ARMED: WATCH plus VWAP side, EMA9/EMA20 alignment and futures momentum.</li>
 *   <li>EARLY_ENTRY: every early-entry condition of the design holds; buys the early tranche.</li>
 *   <li>CONFIRMED: a 3-minute close through the level, time-of-day RVOL, a strong breakout bar and
 *       the regime-weighted confirm score above the window's minimum; buys the confirm tranche.</li>
 *   <li>RUNNER: runner score above its minimum while confirmed; buys the runner tranche and
 *       switches to the EMA9 trail.</li>
 * </ul>
 */
final class EarlyConfirmRunnerStrategy implements Strategy {

    private static final String ID = "early-confirm-runner";

    private final EcrConfig config;
    private final Scorer scorer;
    private final Map<OptionSide, Stage> stages = new EnumMap<>(OptionSide.class);
    private final Map<OptionSide, Integer> campaigns = new EnumMap<>(OptionSide.class);
    private Instant stageSince;
    private double bestSpotInRunner = Double.NaN;
    private Instant bestSpotTime;

    EarlyConfirmRunnerStrategy(EcrConfig config) {
        this.config = config;
        this.scorer = new Scorer(config);
        for (OptionSide side : OptionSide.values()) {
            stages.put(side, Stage.IDLE);
            campaigns.put(side, 0);
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String configHash() {
        return config.hash();
    }

    @Override
    public Decision decide(FeatureSnapshot snapshot, PositionView position) {
        LocalTime time = snapshot.time().atZone(MarketTime.IST).toLocalTime();
        String regime = regime(snapshot.regime().dteTradingDays());
        boolean continuous = SessionPhase.valueOf(snapshot.phase()).isContinuous();
        List<OrderIntent> orders = new ArrayList<>();
        Map<OptionSide, SideView> views = new EnumMap<>(OptionSide.class);
        Map<OptionSide, Map<String, Double>> subs = new EnumMap<>(OptionSide.class);
        for (OptionSide side : OptionSide.values()) {
            SideFeatures f = new SideFeatures(side, snapshot);
            Map<String, Double> sub = scorer.subScores(f);
            subs.put(side, sub);
            Map<String, Boolean> conditions = conditions(f, regime);
            double early = earlyScore(conditions);
            double confirm = scorer.confirmScore(sub, regime);
            double runner = scorer.runnerScore(sub);
            boolean confirmed = conditions.get("broke_level") && conditions.get("rvol_confirms")
                    && conditions.get("breakout_bar") && confirm >= config.requiredConfirmScore(time);
            conditions.put("confirm_score_ok", confirm >= config.requiredConfirmScore(time));
            conditions.put("runner_score_ok", runner >= config.runnerScoreMin());

            boolean mine = position.open() && position.side() == side;
            if (mine) {
                manage(side, f, snapshot, time, regime, confirmed, runner, orders);
            } else if (!position.open()) {
                open(side, f, snapshot, time, regime, continuous, conditions, early, confirmed, orders);
            } else if (!stages.get(side).holdsPosition() && stages.get(side) != Stage.EXITED) {
                stages.put(side, watchStage(conditions));
            }
            views.put(side, new SideView(side, stages.get(side), early, confirm, runner,
                    Collections.unmodifiableMap(conditions)));
        }
        return new Decision(snapshot.time(), snapshot.underlying(), marketState(snapshot, subs, regime),
                views.get(OptionSide.CE), views.get(OptionSide.PE), List.copyOf(orders));
    }

    private void open(OptionSide side, SideFeatures f, FeatureSnapshot snapshot, LocalTime time, String regime,
                      boolean continuous, Map<String, Boolean> conditions, double early, boolean confirmed,
                      List<OrderIntent> orders) {
        Stage stage = stages.get(side);
        if (stage.holdsPosition()) {
            // The executor reports flat (e.g. the resting premium stop filled): the campaign is over.
            stages.put(side, Stage.EXITED);
            return;
        }
        if (stage == Stage.EXITED) {
            return;
        }
        boolean entryWindow = continuous && !time.isBefore(config.earliestEntry()) && time.isBefore(config.lastNewEntry())
                && snapshot.structure().orComplete() && campaigns.get(side) < 1;
        Stage next = watchStage(conditions);
        boolean expiry = "EXPIRY".equals(regime);
        int[] tranches = config.tranches(expiry);
        if (entryWindow && config.scaling() && early >= config.earlyMin()) {
            orders.add(new OrderIntent(OrderIntent.Action.ENTER, side, tranches[0], Stage.EARLY_ENTRY, "EARLY"));
            next = Stage.EARLY_ENTRY;
        } else if (entryWindow && confirmed) {
            int lots = config.scaling() ? tranches[1] : config.intendedLots();
            orders.add(new OrderIntent(OrderIntent.Action.ENTER, side, lots, Stage.CONFIRMED, "CONFIRMED"));
            next = Stage.CONFIRMED;
        }
        if (next.holdsPosition()) {
            campaigns.merge(side, 1, Integer::sum);
            stageSince = snapshot.time();
        }
        stages.put(side, next);
    }

    private void manage(OptionSide side, SideFeatures f, FeatureSnapshot snapshot, LocalTime time, String regime,
                        boolean confirmed, double runner, List<OrderIntent> orders) {
        Stage stage = stages.get(side);
        if (!time.isBefore(config.flatBy())) {
            exit(side, stage, "FLAT_BY", orders);
            return;
        }
        boolean expiry = "EXPIRY".equals(regime);
        int[] tranches = config.tranches(expiry);
        switch (stage) {
            case EARLY_ENTRY -> {
                if (confirmed) {
                    orders.add(new OrderIntent(OrderIntent.Action.ADD, side, tranches[1], Stage.CONFIRMED, "CONFIRMED"));
                    stages.put(side, Stage.CONFIRMED);
                    stageSince = snapshot.time();
                } else if (f.levelDistance() < -config.probeFailAtr()) {
                    exit(side, stage, "PROBE_FAILED", orders);
                } else if (Duration.between(stageSince, snapshot.time()).toMinutes() >= config.probeMaxMinutes()) {
                    exit(side, stage, "PROBE_TIMEOUT", orders);
                }
            }
            case CONFIRMED -> {
                if (invalidated(f)) {
                    exit(side, stage, "INVALIDATED", orders);
                } else if (runner >= config.runnerScoreMin()) {
                    if (config.scaling() && tranches[2] > 0) {
                        orders.add(new OrderIntent(OrderIntent.Action.ADD, side, tranches[2], Stage.RUNNER, "RUNNER"));
                    }
                    stages.put(side, Stage.RUNNER);
                    stageSince = snapshot.time();
                    bestSpotInRunner = f.spot();
                    bestSpotTime = snapshot.time();
                }
            }
            case RUNNER -> {
                if (side.sign() * (f.spot() - bestSpotInRunner) > 0) {
                    bestSpotInRunner = f.spot();
                    bestSpotTime = snapshot.time();
                }
                Map<OptionSide, Set<String>> exitStates = Map.of(
                        OptionSide.CE, config.ceRunnerExitStates(), OptionSide.PE, config.peRunnerExitStates());
                if (invalidated(f)) {
                    exit(side, stage, "INVALIDATED", orders);
                } else if (!f.trailHolds()) {
                    exit(side, stage, "RUNNER_TRAIL", orders);
                } else if (exitStates.get(side).contains(f.rawFuturesState())) {
                    exit(side, stage, "FUTURES_REVERSAL", orders);
                } else if (expiry && Duration.between(bestSpotTime, snapshot.time()).toMinutes()
                        >= config.expiryStallMinutes()) {
                    exit(side, stage, "EXPIRY_STALL", orders);
                }
            }
            default -> {
            }
        }
    }

    /** A 3-minute close back through the level by more than the invalidation band. */
    private boolean invalidated(SideFeatures f) {
        return f.closesBeyond() == 0 && f.levelDistance() < -config.invalidationAtr();
    }

    private void exit(OptionSide side, Stage stage, String reason, List<OrderIntent> orders) {
        orders.add(OrderIntent.exit(side, stage, reason));
        stages.put(side, Stage.EXITED);
    }

    private Stage watchStage(Map<String, Boolean> c) {
        if (!c.get("near_level")) {
            return Stage.IDLE;
        }
        boolean armed = c.get("vwap_side") && c.get("ema_aligned") && c.get("futures_momentum");
        return armed ? Stage.ARMED : Stage.WATCH;
    }

    /** Every named condition, in the design's words. The first ten are the early-entry AND list. */
    private Map<String, Boolean> conditions(SideFeatures f, String regime) {
        double maxBelow = "EXPIRY".equals(regime) ? config.earlyDistanceExpiry() : config.earlyDistanceNormal();
        double distance = f.levelDistance();
        Map<String, Boolean> c = new LinkedHashMap<>();
        c.put("early_distance", !Double.isNaN(distance) && distance >= -maxBelow
                && distance <= config.earlyMaxBeyondAtr());
        c.put("vwap_side", f.vwapOk());
        c.put("ema_aligned", f.emaAligned());
        c.put("ema9_slope", f.emaSlopeOk());
        c.put("swing_intact", f.swingIntact());
        c.put("trend_swings", f.trendSwings());
        c.put("futures_momentum", f.futuresMomentum());
        c.put("futures_acceleration", f.futuresAcceleration());
        c.put("rvol_rising", f.rvolRising());
        c.put("breadth", !Double.isNaN(f.breadth()) && f.breadth() >= config.breadthMin());
        c.put("near_level", !Double.isNaN(distance) && distance >= -config.watchDistanceAtr());
        c.put("broke_level", f.closesBeyond() >= 1 && f.closesBeyond() <= 2 && distance > 0);
        c.put("rvol_confirms", !Double.isNaN(f.rvol()) && f.rvol() >= config.confirmRvolMin());
        c.put("breakout_bar", f.breakoutBar(config));
        return c;
    }

    private static final List<String> EARLY_CONDITIONS = List.of("early_distance", "vwap_side", "ema_aligned",
            "ema9_slope", "swing_intact", "trend_swings", "futures_momentum", "futures_acceleration", "rvol_rising",
            "breadth");

    private static double earlyScore(Map<String, Boolean> conditions) {
        long met = EARLY_CONDITIONS.stream().filter(conditions::get).count();
        return 100.0 * met / EARLY_CONDITIONS.size();
    }

    static String regime(int dteTradingDays) {
        if (dteTradingDays < 0) {
            return "UNKNOWN";
        }
        return dteTradingDays == 0 ? "EXPIRY" : dteTradingDays <= 2 ? "NEAR" : "NORMAL";
    }

    /**
     * Direction and structure: CE sub-score minus PE sub-score (−100..+100). Participation: volume
     * and breadth strength regardless of side (0..100). Continuation: the leading side's runner
     * score, signed by that side.
     */
    private MarketState marketState(FeatureSnapshot snapshot, Map<OptionSide, Map<String, Double>> subs,
                                    String regime) {
        Map<String, Double> ce = subs.get(OptionSide.CE);
        Map<String, Double> pe = subs.get(OptionSide.PE);
        double direction = 100 * ((ce.get("futures") + ce.get("breadth") + ce.get("structure"))
                - (pe.get("futures") + pe.get("breadth") + pe.get("structure"))) / 3;
        double structure = 100 * (ce.get("structure_runner") - pe.get("structure_runner"));
        double rvol = snapshot.futures().rvolTod();
        double breadth = Math.abs(snapshot.breadth().momentumBreadth());
        double participation = 100 * (0.5 * (Double.isNaN(rvol) ? 0.5 : Math.min(1, rvol / config.volumeFullAtRvol()))
                + 0.25 * (ce.get("volume") + pe.get("volume")) / 2
                + 0.25 * (Double.isNaN(breadth) ? 0.5 : Math.min(1, breadth / 100)));
        double ceRunner = scorer.runnerScore(ce);
        double peRunner = scorer.runnerScore(pe);
        double continuation = direction >= 0 ? ceRunner : -peRunner;
        return new MarketState(direction, participation, structure, continuation, regime);
    }
}
