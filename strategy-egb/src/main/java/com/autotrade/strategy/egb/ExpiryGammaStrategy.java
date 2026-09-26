package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Expiry-day gamma breakout → retest → runner (user's design, 2026-09-26; rules in
 * {@code docs/CALIBRATION-egb.md}). Expiry day only. Per side (CE at ORH/PDH, PE at ORL/PDL):
 *
 * <pre>
 * IDLE → COMPRESSION → ARMED → EARLY_ENTRY (25%) → CONFIRMED (+45%) → RETEST → RUNNER (+30%) → EXITED
 * </pre>
 *
 * One campaign per side per day and one position per underlying for this strategy. The level a side
 * is armed on stays its level while armed and for the whole campaign once entered. An entry the
 * executor refuses (risk limits, no quote) is not a campaign: the side returns to watching and may
 * try again after {@link #REFUSED_RETRY}.
 */
final class ExpiryGammaStrategy implements Strategy {

    static final String ID = "expiry-gamma-breakout";
    static final Duration REFUSED_RETRY = Duration.ofMinutes(3);

    /** A side's campaign state. */
    private static final class Side {
        Stage stage = Stage.IDLE;
        String level;
        Instant stageSince;
        Instant entryAt;
        boolean filled;
        Instant retryAfter;
        double entrySpot = Double.NaN;
        double thetaBreakeven = Double.NaN;
        boolean thetaChecked;
        double best = Double.NaN;
        Instant bestAt;
        boolean done;
    }

    private final EgbConfig config;
    private final Map<OptionSide, Side> sides = new EnumMap<>(OptionSide.class);
    private Instant compressionUntil;

    ExpiryGammaStrategy(EgbConfig config) {
        this.config = config;
        for (OptionSide side : OptionSide.values()) {
            sides.put(side, new Side());
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
        Instant now = snapshot.time();
        LocalTime time = now.atZone(MarketTime.IST).toLocalTime();
        boolean expiryDay = snapshot.regime().dteTradingDays() == config.dte();
        boolean continuous = SessionPhase.valueOf(snapshot.phase()).isContinuous();
        boolean compressedNow = compressedNow(snapshot);
        if (compressedNow) {
            compressionUntil = now.plus(Duration.ofMinutes(config.compressionMemoryMin()));
        }
        boolean compressedRecently = compressionUntil != null && !now.isAfter(compressionUntil);
        List<OrderIntent> orders = new ArrayList<>();
        Map<OptionSide, SideView> views = new EnumMap<>(OptionSide.class);
        Map<OptionSide, Double> accel = new EnumMap<>(OptionSide.class);
        Map<OptionSide, Boolean> bias = new EnumMap<>(OptionSide.class);
        for (OptionSide sideKey : OptionSide.values()) {
            EgbSide f = new EgbSide(sideKey, snapshot);
            Side side = sides.get(sideKey);
            String level = side.level != null ? side.level : nearestLevel(f);
            Map<String, Boolean> c = conditions(f, level, compressedNow, compressedRecently);
            double accelerationIndex = f.accelerationIndex(config.breadthMin());
            accel.put(sideKey, accelerationIndex);
            bias.put(sideKey, c.get("structure_bias"));
            boolean mine = position.open() && position.side() == sideKey;
            if (mine) {
                side.filled = true;
                manage(sideKey, side, f, c, time, now, orders);
            } else if (!position.open()) {
                open(sideKey, side, f, c, level, accelerationIndex, time, now, expiryDay, continuous,
                        compressedRecently, orders);
            } else if (!side.stage.holdsPosition() && side.stage != Stage.EXITED) {
                side.stage = watchStage(c, compressedRecently);
                side.level = side.stage == Stage.ARMED ? level : null;
            }
            views.put(sideKey, new SideView(sideKey, side.stage, 100 * accelerationIndex, score(c, CONFIRM),
                    score(c, RUNNER), Double.NaN, Collections.unmodifiableMap(c)));
        }
        return new Decision(now, snapshot.underlying(), marketState(snapshot, accel, bias, expiryDay),
                views.get(OptionSide.CE), views.get(OptionSide.PE), List.copyOf(orders));
    }

    // ---------------------------------------------------------------------------------- entries

    private void open(OptionSide sideKey, Side side, EgbSide f, Map<String, Boolean> c, String level,
                      double accelerationIndex, LocalTime time, Instant now, boolean expiryDay, boolean continuous,
                      boolean compressedRecently, List<OrderIntent> orders) {
        if (side.stage.holdsPosition()) {
            if (side.filled) {
                // The executor reports flat after holding (e.g. the resting stop filled): the campaign is over.
                finish(side);
                return;
            }
            // The entry was refused before any position existed: back to watching, retry later.
            side.stage = Stage.IDLE;
            side.level = null;
            side.retryAfter = now.plus(REFUSED_RETRY);
        }
        if (side.stage == Stage.EXITED || side.done) {
            return;
        }
        boolean window = expiryDay && continuous && f.orComplete() && !time.isBefore(config.earliestEntry())
                && time.isBefore(config.lastNewEntry()) && (side.retryAfter == null || !now.isBefore(side.retryAfter));
        boolean compressionOk = !config.compressionRequired() || compressedRecently;
        boolean clean = c.get("chop_ok") && c.get("slope_ok") && c.get("spread_ok");
        int[] tranches = config.tranches();
        Stage next = watchStage(c, compressedRecently);
        if (window && compressionOk && level != null && clean) {
            if (next == Stage.ARMED && accelerationIndex >= config.accelerationMin()) {
                enter(sideKey, side, f, level, tranches[0], Stage.EARLY_ENTRY, "EARLY", now, orders);
                return;
            }
            if (c.get("confirmed") && c.get("structure_bias")) {
                enter(sideKey, side, f, level, tranches[1], Stage.CONFIRMED, "CONFIRMED", now, orders);
                return;
            }
        }
        side.stage = next;
        // Keep the armed level only while armed; otherwise re-pick each snapshot (a just-broken level first).
        side.level = next == Stage.ARMED ? level : null;
    }

    private void enter(OptionSide sideKey, Side side, EgbSide f, String level, int lots, Stage stage, String reason,
                       Instant now, List<OrderIntent> orders) {
        int strikeOffset = f.chooseStrikeOffset(config.targetAbsDelta(), config.maxSpreadPct());
        orders.add(new OrderIntent(OrderIntent.Action.ENTER, sideKey, lots, stage, reason, config.premiumStopPct(),
                strikeOffset));
        side.stage = stage;
        side.level = level;
        side.stageSince = now;
        side.entryAt = now;
        side.filled = false;
        side.entrySpot = f.spot();
        side.thetaBreakeven = f.directionalBreakeven(strikeOffset, config.thetaStopMinutes());
        side.thetaChecked = false;
        side.best = f.spot();
        side.bestAt = now;
    }

    // ---------------------------------------------------------------------------------- management

    private void manage(OptionSide sideKey, Side side, EgbSide f, Map<String, Boolean> c, LocalTime time, Instant now,
                        List<OrderIntent> orders) {
        if (!time.isBefore(config.flatBy())) {
            exit(sideKey, side, "FLAT_BY", orders);
            return;
        }
        if (sideKey.sign() * (f.spot() - side.best) > 0) {
            side.best = f.spot();
            side.bestAt = now;
        }
        int[] tranches = config.tranches();
        String retest = f.retestState(side.level);
        double levelDistance = f.levelDistance(side.level);
        if (side.stage != Stage.RUNNER && !side.thetaChecked
                && Duration.between(side.entryAt, now).toMinutes() >= config.thetaStopMinutes()) {
            side.thetaChecked = true;
            double favourable = sideKey.sign() * (f.spot() - side.entrySpot);
            if (!Double.isNaN(side.thetaBreakeven) && favourable < side.thetaBreakeven) {
                exit(sideKey, side, "THETA_STOP", orders);
                return;
            }
        }
        switch (side.stage) {
            case EARLY_ENTRY -> {
                if (c.get("confirmed")) {
                    orders.add(new OrderIntent(OrderIntent.Action.ADD, sideKey, tranches[1], Stage.CONFIRMED,
                            "CONFIRMED", config.premiumStopPct()));
                    move(side, Stage.CONFIRMED, now);
                } else if (levelDistance < -config.probeFailAtr()) {
                    exit(sideKey, side, "PROBE_FAILED", orders);
                } else if (Duration.between(side.stageSince, now).toMinutes() >= config.probeMaxMinutes()) {
                    exit(sideKey, side, "PROBE_TIMEOUT", orders);
                }
            }
            case CONFIRMED, RETEST -> {
                if (invalidated(f, side)) {
                    exit(sideKey, side, "INVALIDATED", orders);
                } else if ("FAILED".equals(retest)) {
                    exit(sideKey, side, "RETEST_FAILED", orders);
                } else if (config.exitFuturesStates().get(sideKey).contains(f.futuresState())) {
                    exit(sideKey, side, "FUTURES_REVERSAL", orders);
                } else if (runnerReady(f, side, c)) {
                    if (tranches[2] > 0) {
                        orders.add(new OrderIntent(OrderIntent.Action.ADD, sideKey, tranches[2], Stage.RUNNER, "RUNNER",
                                config.premiumStopPct()));
                    }
                    move(side, Stage.RUNNER, now);
                } else if ("RETESTING".equals(retest) && side.stage == Stage.CONFIRMED) {
                    move(side, Stage.RETEST, now);
                }
            }
            case RUNNER -> {
                if (invalidated(f, side)) {
                    exit(sideKey, side, "INVALIDATED", orders);
                } else if (f.trailBroken(side.level)) {
                    exit(sideKey, side, "RUNNER_TRAIL", orders);
                } else if (f.closedThroughEma9() && !f.accelerationFavourable()) {
                    exit(sideKey, side, "EMA9_BREAK", orders);
                } else if (config.exitFuturesStates().get(sideKey).contains(f.futuresState())) {
                    exit(sideKey, side, "FUTURES_REVERSAL", orders);
                } else if (Duration.between(side.bestAt, now).toMinutes() >= config.stallMinutes()
                        && !f.accelerationFavourable()) {
                    exit(sideKey, side, "MOMENTUM_FADE", orders);
                }
            }
            default -> {
            }
        }
    }

    /** The design's runner test: retest held (or, in the no-retest variant, a second close and new extreme). */
    private boolean runnerReady(EgbSide f, Side side, Map<String, Boolean> c) {
        boolean base = c.get("futures_oi_ok") && c.get("ema9_side") && c.get("premium_ok");
        if (!config.requireRetest()) {
            return base && f.closesBeyond(side.level) >= 2 && side.best == f.spot();
        }
        return base && "HELD".equals(f.retestState(side.level)) && c.get("pullback_volume_ok");
    }

    /** A 3-minute close back through the traded level by more than the invalidation band. */
    private boolean invalidated(EgbSide f, Side side) {
        return f.closesBeyond(side.level) == 0 && f.levelDistance(side.level) < -config.invalidationAtr();
    }

    private void move(Side side, Stage stage, Instant now) {
        side.stage = stage;
        side.stageSince = now;
    }

    private void exit(OptionSide sideKey, Side side, String reason, List<OrderIntent> orders) {
        orders.add(OrderIntent.exit(sideKey, side.stage, reason));
        finish(side);
    }

    private void finish(Side side) {
        side.stage = Stage.EXITED;
        side.done = true;
    }

    // ---------------------------------------------------------------------------------- conditions

    private boolean compressedNow(FeatureSnapshot s) {
        return s.compression().rangeVsSession() <= config.rangeVsSessionMax()
                && s.compression().emaGapAtr() <= config.emaGapAtrMax()
                && s.compression().volumeRateRatio() <= config.volumeRateRatioMax();
    }

    /**
     * The level this side would trade: a level it has just broken (first or second close through it)
     * first, else the nearest level ahead of spot (or at most the early band through it).
     */
    private String nearestLevel(EgbSide f) {
        for (String level : config.triggerLevels().get(f.side())) {
            int closes = f.closesBeyond(level);
            if (closes >= 1 && closes <= 2 && f.levelDistance(level) > 0) {
                return level;
            }
        }
        String best = null;
        double bestAbs = Double.POSITIVE_INFINITY;
        for (String level : config.triggerLevels().get(f.side())) {
            double d = f.levelDistance(level);
            if (!Double.isNaN(d) && d <= config.earlyMaxBeyondAtr() && Math.abs(d) < bestAbs) {
                bestAbs = Math.abs(d);
                best = level;
            }
        }
        return best;
    }

    private static final List<String> CONFIRM = List.of("broke_level", "breakout_bar", "breakout_volume",
            "futures_momentum", "room_ok", "premium_ok", "wick_ok");
    private static final List<String> RUNNER = List.of("retest_held", "pullback_volume_ok", "futures_oi_ok",
            "ema9_side", "premium_ok");

    private Map<String, Boolean> conditions(EgbSide f, String level, boolean compressedNow, boolean compressedRecently) {
        Map<String, Boolean> c = new LinkedHashMap<>();
        double d = f.levelDistance(level);
        c.put("near_level", !Double.isNaN(d) && d >= -1.0);
        c.put("compressed", compressedNow);
        c.put("compressed_recently", compressedRecently);
        c.put("trend_swings", f.trendSwings());
        c.put("structure_bias", f.structureBias(config.requireTrendSwings()));
        c.put("armed_distance", !Double.isNaN(d) && d >= -config.armedDistanceAtr() && d <= config.earlyMaxBeyondAtr());
        int closes = f.closesBeyond(level);
        c.put("broke_level", closes >= 1 && closes <= 2 && d > 0);
        c.put("breakout_bar", f.breakoutBar(config.bodyToRangeMin(), config.closeLocationTopPct(), config.upperWickMaxPct()));
        c.put("breakout_volume", f.breakoutVolumeRatio() >= config.volumeRatioMin());
        c.put("futures_momentum", f.futuresMomentumFavourable());
        c.put("room_ok", f.roomAtr() >= config.roomMinAtr());
        double response = f.premiumResponse();
        c.put("premium_ok", Double.isNaN(response) || response >= config.premiumResponseMin());
        c.put("wick_ok", !(f.breakoutWick() > config.breakoutWickMaxPct()));
        c.put("confirmed", c.get("broke_level") && c.get("breakout_bar") && c.get("breakout_volume")
                && c.get("futures_momentum") && c.get("room_ok") && c.get("premium_ok") && c.get("wick_ok"));
        c.put("retest_held", "HELD".equals(f.retestState(level)));
        double pullback = f.pullbackVolumeRatio(level);
        c.put("pullback_volume_ok", Double.isNaN(pullback) || pullback <= config.pullbackVolumeRatioMax());
        c.put("futures_oi_ok", !config.runnerRejectStates().get(f.side()).contains(f.futuresState()));
        c.put("ema9_side", f.onTradeSideOfEma9());
        c.put("chop_ok", f.vwapCrosses() <= config.vwapCrossesMax());
        c.put("slope_ok", f.emaSlopeFavourable());
        c.put("spread_ok", f.chooseStrikeOffset(config.targetAbsDelta(), config.maxSpreadPct()) >= 0);
        return c;
    }

    private Stage watchStage(Map<String, Boolean> c, boolean compressedRecently) {
        boolean compressionOk = !config.compressionRequired() || compressedRecently;
        if (compressionOk && c.get("structure_bias") && c.get("armed_distance")) {
            return Stage.ARMED;
        }
        if (compressedRecently) {
            return Stage.COMPRESSION;
        }
        return c.get("near_level") ? Stage.WATCH : Stage.IDLE;
    }

    private static double score(Map<String, Boolean> c, List<String> names) {
        long met = names.stream().filter(n -> Boolean.TRUE.equals(c.get(n))).count();
        return 100.0 * met / names.size();
    }

    /**
     * Direction from the two sides' acceleration indices, structure from their structure bias (+100 CE
     * only, −100 PE only, 0 both or neither); continuation is not defined by this design (NaN).
     * Regime = EXPIRY/gamma regime.
     */
    private MarketState marketState(FeatureSnapshot s, Map<OptionSide, Double> accel, Map<OptionSide, Boolean> bias,
                                    boolean expiryDay) {
        double ce = accel.get(OptionSide.CE);
        double pe = accel.get(OptionSide.PE);
        double direction = Double.isNaN(ce) || Double.isNaN(pe) ? Double.NaN : 100 * (ce - pe);
        double rvol = s.futures().rvolTod();
        double participation = Double.isNaN(rvol) ? Double.NaN : 100 * Math.min(1, rvol / 1.5);
        String regime = expiryDay ? "EXPIRY/" + s.gamma().gammaRegime() : "NOT_EXPIRY";
        double structure = (bias.get(OptionSide.CE) ? 100 : 0) - (bias.get(OptionSide.PE) ? 100 : 0);
        return new MarketState(direction, participation, structure, Double.NaN, regime, null);
    }
}
