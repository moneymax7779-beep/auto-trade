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
 *
 * <p>Strategy v4 (files with the {@code vol_regime} section) adds:
 * <ul>
 *   <li>the volatility regime (LOW / NORMAL / HIGH / EXTREME), which scales size, raises the
 *       confirmation threshold, widens the premium stop and can disallow early probes;</li>
 *   <li>order-book imbalance persistence as a small plus or minus on the confirm score;</li>
 *   <li>breakout-bar volume in the breakout quality; runner promotion only when the option premium
 *       responds efficiently, and on expiry only without an opposite wall forming and with RVOL still
 *       elevated; expiry runners also exit when that changes or futures momentum turns;</li>
 *   <li>the closing-auction mode: at 15:15 a RUNNER switches to CAS_RUNNER management (held while the
 *       CAS score agrees, closed when it falls to NO_TRADE or at {@code exit_by}); other positions
 *       close as before. New CAS entries are taken in the entry window by CAS score band, only with
 *       per-stock auction data and futures agreeing, one per side; stricter on expiry day.</li>
 * </ul>
 */
final class EarlyConfirmRunnerStrategy implements Strategy {

    private static final String ID = "early-confirm-runner";

    private final EcrConfig config;
    private final EcrExtensions ext;
    private final Scorer scorer;
    private final CasScorer casScorer;
    private final Map<OptionSide, Stage> stages = new EnumMap<>(OptionSide.class);
    private final Map<OptionSide, Integer> campaigns = new EnumMap<>(OptionSide.class);
    private final Map<OptionSide, Integer> casCampaigns = new EnumMap<>(OptionSide.class);
    private final Map<OptionSide, Boolean> casManaged = new EnumMap<>(OptionSide.class);
    private Instant stageSince;
    // v9: a box break per side, frozen at the break; whether the open position was entered on it alone
    private final Map<OptionSide, Double> boxLevel = new EnumMap<>(OptionSide.class);
    private final Map<OptionSide, Instant> boxBreakAt = new EnumMap<>(OptionSide.class);
    private final Map<OptionSide, Boolean> boxOnlyNow = new EnumMap<>(OptionSide.class);
    private final Map<OptionSide, Boolean> enteredOnBox = new EnumMap<>(OptionSide.class);
    // v10: a level that opened a position cannot trigger again until a 1-minute close back inside it
    private final Map<OptionSide, Double> usedLevel = new EnumMap<>(OptionSide.class);
    private double bestSpotInRunner = Double.NaN;
    private Instant bestSpotTime;

    EarlyConfirmRunnerStrategy(EcrConfig config) {
        this.config = config;
        this.ext = config.ext();
        this.scorer = new Scorer(config);
        this.casScorer = ext == null ? null : new CasScorer(ext.cas());
        for (OptionSide side : OptionSide.values()) {
            stages.put(side, Stage.IDLE);
            campaigns.put(side, 0);
            casCampaigns.put(side, 0);
            casManaged.put(side, false);
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

    /** Everything decided once per snapshot that both sides share. */
    private record Context(LocalTime time, String dteRegime, String volRegime, boolean continuous, boolean casPhase,
                           EcrExtensions.Adjustment adjustment) {

        boolean expiry() {
            return "EXPIRY".equals(dteRegime);
        }
    }

    @Override
    public Decision decide(FeatureSnapshot snapshot, PositionView position) {
        LocalTime time = snapshot.time().atZone(MarketTime.IST).toLocalTime();
        SessionPhase phase = SessionPhase.valueOf(snapshot.phase());
        String dteRegime = regime(snapshot.regime().dteTradingDays());
        String volRegime = ext == null ? null : VolatilityRegime.classify(snapshot, ext).regime();
        Context ctx = new Context(time, dteRegime, volRegime, phase.isContinuous(),
                phase.isCas() || phase == SessionPhase.DERIVATIVES_ONLY,
                ext == null ? null : ext.adjustment(volRegime));
        List<OrderIntent> orders = new ArrayList<>();
        Map<OptionSide, SideView> views = new EnumMap<>(OptionSide.class);
        Map<OptionSide, Map<String, Double>> subs = new EnumMap<>(OptionSide.class);
        for (OptionSide side : OptionSide.values()) {
            SideFeatures f = new SideFeatures(side, snapshot, ext != null && ext.v6() != null && ext.v6().moverBreadth());
            Map<String, Double> sub = scorer.subScores(f);
            subs.put(side, sub);
            Map<String, Boolean> conditions = conditions(f, dteRegime);
            if (config.boxLevels() || config.zoneLevels()) {
                boxBreak(side, snapshot, conditions);
            }
            double early = earlyScore(conditions);
            double confirm = scorer.confirmScore(sub, dteRegime);
            double required = config.requiredConfirmScore(time);
            if (ext != null) {
                required += ctx.adjustment().confirmAdd();
                boolean favourable = f.bookFavourable(ext.bookImbalanceMin());
                boolean against = f.bookAgainst(ext.bookImbalanceMin());
                if (ext.v6() != null) {
                    // v6: bid/ask changes count alongside imbalance persistence (still one small confirmation).
                    boolean flowFor = f.flowFavourable(ext.v6().flowEdgePct());
                    boolean flowAgainst = f.flowAgainst(ext.v6().flowEdgePct());
                    conditions.put("book_flow_favourable", flowFor);
                    conditions.put("book_flow_against", flowAgainst);
                    conditions.put("liquidity_ok", !f.liquidityDropped(ext.v6().liquidityDropPct()));
                    favourable = favourable || flowFor;
                    against = against || flowAgainst;
                }
                conditions.put("book_favourable", favourable);
                conditions.put("book_against", against);
                // A small confirmation only: a few points either way, never a trigger by itself.
                confirm += favourable && !against ? ext.bookConfirmPoints() : against && !favourable
                        ? -ext.bookConfirmPoints() : 0;
                conditions.put("breakout_volume", f.breakoutVolumeRatio() >= ext.breakoutVolumeRatioMin());
                double response = f.premiumResponse();
                conditions.put("premium_response_ok", Double.isNaN(response) || response >= ext.premiumResponseRatioMin());
                conditions.put("target_reachable", !(f.expectedReach() < 1));
                conditions.put("opposite_wall", f.oppositeWallForming(ext.wallScoreMin()));
                conditions.put("rvol_elevated", f.rvol() >= ext.expiryRunnerRvolMin());
                conditions.put("early_allowed", ctx.adjustment().allowEarly());
            }
            double runner = scorer.runnerScore(sub);
            boolean breakout = conditions.get("breakout_bar") && (ext == null || conditions.get("breakout_volume"));
            boolean confirmed = conditions.get("broke_level") && conditions.get("rvol_confirms") && breakout
                    && confirm >= required;
            conditions.put("confirm_score_ok", confirm >= required);
            conditions.put("runner_score_ok", runner >= config.runnerScoreMin());

            CasScorer.Score cas = ctx.casPhase() && casScorer != null && ext.cas().enabled()
                    ? casScorer.score(side, snapshot) : null;
            if (cas != null) {
                conditions.put("cas_constituent_data", cas.constituentData());
                conditions.put("cas_futures_agree", cas.futuresAlignment() >= ext.cas().minFuturesAlignment());
            }

            boolean mine = position.open() && position.side() == side;
            if (mine) {
                manage(side, f, snapshot, ctx, confirmed, runner, conditions, cas, orders);
            } else if (!position.open()) {
                if (!casEntry(side, snapshot, ctx, cas, conditions, orders)) {
                    open(side, snapshot, ctx, conditions, early, confirmed, orders);
                }
            } else if (!stages.get(side).holdsPosition() && stages.get(side) != Stage.EXITED) {
                stages.put(side, watchStage(conditions));
            }
            views.put(side, new SideView(side, stages.get(side), early, confirm, runner,
                    cas == null ? Double.NaN : cas.value(), Collections.unmodifiableMap(conditions)));
        }
        String regimeLabel = volRegime == null ? dteRegime : dteRegime + "/" + volRegime;
        return new Decision(snapshot.time(), snapshot.underlying(), marketState(snapshot, subs, regimeLabel),
                views.get(OptionSide.CE), views.get(OptionSide.PE), List.copyOf(orders));
    }

    private void open(OptionSide side, FeatureSnapshot snapshot, Context ctx, Map<String, Boolean> conditions,
                      double early, boolean confirmed, List<OrderIntent> orders) {
        Stage stage = stages.get(side);
        if (stage.holdsPosition()) {
            // The executor reports flat (e.g. the resting premium stop filled): the campaign is over.
            stages.put(side, Stage.EXITED);
            casManaged.put(side, false);
            return;
        }
        if (stage == Stage.EXITED) {
            if (config.maxCampaignsPerSide() != 0 && campaigns.get(side) >= config.maxCampaignsPerSide()) {
                return;
            }
            stages.put(side, Stage.IDLE);                     // v10: flat again, a new campaign may start
        }
        boolean campaignsLeft = config.maxCampaignsPerSide() == 0 || campaigns.get(side) < config.maxCampaignsPerSide();
        boolean entryWindow = ctx.continuous() && !ctx.time().isBefore(config.earliestEntry())
                && ctx.time().isBefore(config.lastNewEntry()) && snapshot.structure().orComplete() && campaignsLeft
                && conditions.getOrDefault("liquidity_ok", true)
                && (!config.expiryDaysOnly() || ctx.expiry());       // v8: expiry days only
        Stage next = watchStage(conditions);
        int lots = intendedLots(ctx);
        int[] tranches = config.tranches(ctx.expiry(), lots);
        boolean scaling = config.scaling(lots);
        boolean earlyAllowed = ext == null || ctx.adjustment().allowEarly();
        if (entryWindow && scaling && earlyAllowed && early >= config.earlyMin()) {
            orders.add(intent(OrderIntent.Action.ENTER, side, tranches[0], Stage.EARLY_ENTRY, "EARLY", ctx));
            next = Stage.EARLY_ENTRY;
        } else if (entryWindow && confirmed) {
            int entryLots = scaling ? tranches[1] + (earlyAllowed ? 0 : tranches[0]) : lots;
            orders.add(intent(OrderIntent.Action.ENTER, side, entryLots, Stage.CONFIRMED, "CONFIRMED", ctx));
            next = Stage.CONFIRMED;
        }
        if (next.holdsPosition()) {
            enteredOnBox.put(side, next == Stage.CONFIRMED && boxOnlyNow.getOrDefault(side, false));
            if (enteredOnBox.get(side) && config.maxCampaignsPerSide() == 0) {   // v10 only: v9 trades once a side
                usedLevel.put(side, boxLevel.get(side));
            }
            campaigns.merge(side, 1, Integer::sum);
            stageSince = snapshot.time();
        }
        stages.put(side, next);
    }

    /**
     * A new closing-auction entry: inside the CAS entry window, CAS score in an entry band, per-stock
     * auction data present (if required) and futures agreeing with the auction. Returns true when this
     * snapshot is handled by CAS logic (so the continuous-session rules do not run).
     */
    private boolean casEntry(OptionSide side, FeatureSnapshot snapshot, Context ctx, CasScorer.Score cas,
                             Map<String, Boolean> conditions, List<OrderIntent> orders) {
        if (cas == null) {
            return false;
        }
        if (stages.get(side).holdsPosition()) {
            stages.put(side, Stage.EXITED);
            casManaged.put(side, false);
        }
        EcrExtensions.Cas c = ext.cas();
        boolean window = !ctx.time().isBefore(c.entryFrom()) && ctx.time().isBefore(c.entryTo());
        if (!window || casCampaigns.get(side) >= 1 || Double.isNaN(cas.value())) {
            return true;
        }
        if (c.entriesRequireConstituents() && !cas.constituentData()) {
            return true;
        }
        if (!(cas.futuresAlignment() >= c.minFuturesAlignment()) || !conditions.getOrDefault("liquidity_ok", true)) {
            return true;
        }
        double add = ctx.expiry() ? c.expiryBandAdd() : 0;
        int lots = intendedLots(ctx);
        int[] tranches = config.tranches(ctx.expiry(), lots);
        int entryLots;
        String reason;
        if (cas.value() >= c.highConvictionMin() + add) {
            entryLots = ctx.expiry() ? tranches[0] + tranches[1] : lots;
            reason = "CAS_HIGH_CONVICTION";
        } else if (cas.value() >= c.confirmedMin() + add) {
            entryLots = ctx.expiry() ? tranches[0] : tranches[0] + tranches[1];
            reason = "CAS_CONFIRMED";
        } else if (cas.value() >= c.earlySmallMin() + add) {
            entryLots = tranches[0];
            reason = "CAS_EARLY_SMALL";
        } else {
            return true;
        }
        entryLots = Math.max(1, entryLots);
        orders.add(intent(OrderIntent.Action.ENTER, side, entryLots, Stage.CONFIRMED, reason, ctx));
        stages.put(side, Stage.CONFIRMED);
        casManaged.put(side, true);
        casCampaigns.merge(side, 1, Integer::sum);
        stageSince = snapshot.time();
        return true;
    }

    private void manage(OptionSide side, SideFeatures f, FeatureSnapshot snapshot, Context ctx, boolean confirmed,
                        double runner, Map<String, Boolean> conditions, CasScorer.Score cas, List<OrderIntent> orders) {
        Stage stage = stages.get(side);
        if (!ctx.time().isBefore(config.flatBy()) && !casManaged.get(side)) {
            if (ext != null && ext.cas().enabled() && stage == Stage.RUNNER) {
                // The design's CAS_RUNNER: a runner is not closed at 15:15 but managed by the auction.
                casManaged.put(side, true);
            } else {
                exit(side, stage, "FLAT_BY", orders);
                return;
            }
        }
        if (casManaged.get(side)) {
            manageCas(side, stage, ctx, cas, orders);
            return;
        }
        boolean expiry = ctx.expiry();
        int[] tranches = config.tranches(expiry, intendedLots(ctx));
        switch (stage) {
            case EARLY_ENTRY -> {
                if (confirmed) {
                    orders.add(intent(OrderIntent.Action.ADD, side, tranches[1], Stage.CONFIRMED, "CONFIRMED", ctx));
                    stages.put(side, Stage.CONFIRMED);
                    stageSince = snapshot.time();
                } else if (f.levelDistance() < -config.probeFailAtr()) {
                    exit(side, stage, "PROBE_FAILED", orders);
                } else if (Duration.between(stageSince, snapshot.time()).toMinutes() >= config.probeMaxMinutes()) {
                    exit(side, stage, "PROBE_TIMEOUT", orders);
                }
            }
            case CONFIRMED -> {
                if (invalidated(side, f, snapshot, ctx)) {
                    exit(side, stage, "INVALIDATED", orders);
                } else if (runner >= config.runnerScoreMin() && runnerGates(conditions, expiry)) {
                    if (config.scaling(intendedLots(ctx)) && tranches[2] > 0) {
                        orders.add(intent(OrderIntent.Action.ADD, side, tranches[2], Stage.RUNNER, "RUNNER", ctx));
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
                if (invalidated(side, f, snapshot, ctx)) {
                    exit(side, stage, "INVALIDATED", orders);
                } else if (!f.trailHolds()) {
                    exit(side, stage, "RUNNER_TRAIL", orders);
                } else if (exitStates.get(side).contains(f.rawFuturesState())) {
                    exit(side, stage, "FUTURES_REVERSAL", orders);
                } else if (expiry && Duration.between(bestSpotTime, snapshot.time()).toMinutes()
                        >= config.expiryStallMinutes()) {
                    exit(side, stage, "EXPIRY_STALL", orders);
                } else if (ext != null && expiry) {
                    expiryRunnerExit(side, f, stage, conditions, orders);
                }
            }
            default -> {
            }
        }
    }

    /** v4 runner promotion gates: efficient premium response; on expiry also no opposite wall and RVOL elevated. */
    private boolean runnerGates(Map<String, Boolean> conditions, boolean expiry) {
        if (ext == null) {
            return true;
        }
        boolean gates = conditions.get("premium_response_ok");
        if (expiry) {
            gates = gates && !conditions.get("opposite_wall") && conditions.get("rvol_elevated");
        }
        return gates;
    }

    /** The design's expiry-runner requirements, checked while holding (v4). */
    private void expiryRunnerExit(OptionSide side, SideFeatures f, Stage stage, Map<String, Boolean> conditions,
                                  List<OrderIntent> orders) {
        if (conditions.get("opposite_wall")) {
            exit(side, stage, "OPPOSITE_WALL", orders);
        } else if (!conditions.get("premium_response_ok")) {
            exit(side, stage, "PREMIUM_LAGGING", orders);
        } else if (!f.futuresMomentum() && !f.futuresAcceleration()) {
            exit(side, stage, "EXPIRY_MOMENTUM_LOST", orders);
        }
    }

    /** CAS_RUNNER / CAS entry management: hold while the auction agrees, close when it disagrees or at exit_by. */
    private void manageCas(OptionSide side, Stage stage, Context ctx, CasScorer.Score cas, List<OrderIntent> orders) {
        EcrExtensions.Cas c = ext.cas();
        double holdMin = ctx.expiry() ? c.expiryRunnerHoldMin() : c.runnerHoldMin();
        if (!ctx.time().isBefore(c.exitBy())) {
            exit(side, stage, "CAS_END", orders);
        } else if (cas != null && !Double.isNaN(cas.value()) && cas.value() < holdMin) {
            exit(side, stage, "CAS_DISAGREES", orders);
        }
    }

    private OrderIntent intent(OrderIntent.Action action, OptionSide side, int lots, Stage stage, String reason,
                               Context ctx) {
        double stop = ext == null ? Double.NaN : ctx.adjustment().premiumStopPct();
        int strike = ext == null || action != OrderIntent.Action.ENTER ? 0 : ctx.adjustment().strikeOffset();
        return new OrderIntent(action, side, lots, stage, reason, stop, strike);
    }

    /** Intended lots for the campaign: the file's lots, scaled by the volatility regime in v4. */
    private int intendedLots(Context ctx) {
        if (ext == null) {
            return config.intendedLots();
        }
        return Math.max(1, (int) Math.round(config.intendedLots() * ctx.adjustment().sizeFactor()));
    }

    /**
     * A 3-minute close back through the level by more than the invalidation band: the structure stop.
     * v6 widens it per volatility regime (the design's "wide structure SL" on high-volatility days).
     */
    private boolean invalidated(OptionSide side, SideFeatures f, FeatureSnapshot snapshot, Context ctx) {
        double band = config.invalidationAtr();
        if (ctx.adjustment() != null && Double.isFinite(ctx.adjustment().structureStopAtr())) {
            band = ctx.adjustment().structureStopAtr();
        }
        Double box = boxLevel.get(side);
        if (enteredOnBox.getOrDefault(side, false) && box != null) {
            // v9, entered on a box break: a 1-minute close back inside the box by more than the band
            double close = snapshot.structure().lastMinuteClose();
            double atr = snapshot.structure().atr3m();
            return Double.isFinite(close) && atr > 0 && side.sign() * (close - box) / atr < -band;
        }
        return f.closesBeyond() == 0 && f.levelDistance() < -band;
    }

    /**
     * v9: a 1-minute close beyond a compressed trailing box freezes that edge as this side's box level
     * for {@code break_window_min}; while it holds (spot still beyond it) {@code broke_level} is also true.
     * Only when the side holds nothing, so an open position keeps the level it was entered on.
     */
    private void boxBreak(OptionSide side, FeatureSnapshot snapshot, Map<String, Boolean> conditions) {
        var st = snapshot.structure();
        Instant now = snapshot.time();
        boolean zone = config.zoneLevels();
        boolean compressed;
        double edge;
        int window;
        if (zone) {
            // v10: the tested zone (its low for PE, high for CE) with enough separate touches
            edge = side == OptionSide.CE ? st.zoneHigh() : st.zoneLow();
            int touches = side == OptionSide.CE ? st.zoneHighTouches() : st.zoneLowTouches();
            compressed = Double.isFinite(edge) && touches >= config.zoneMinTouches();
            window = config.zoneBreakWindowMin();
        } else {
            compressed = Double.isFinite(st.boxHigh()) && Double.isFinite(st.boxLow()) && st.atr3m() > 0
                    && st.boxHigh() - st.boxLow() <= config.boxMaxRangeAtr() * st.atr3m();
            edge = side == OptionSide.CE ? st.boxHigh() : st.boxLow();
            window = config.boxBreakWindowMin();
        }
        boolean closedBeyond = Double.isFinite(st.lastMinuteClose()) && side.sign() * (st.lastMinuteClose() - edge) > 0;
        Double used = usedLevel.get(side);
        if (used != null && Double.isFinite(st.lastMinuteClose()) && side.sign() * (st.lastMinuteClose() - used) <= 0) {
            usedLevel.remove(side);                            // reclaimed: that level may trigger again
            used = null;
        }
        boolean fresh = used == null || edge != used;
        Instant at = boxBreakAt.get(side);
        boolean windowOpen = at != null && Duration.between(at, now).toMinutes() <= window;
        if (!stages.get(side).holdsPosition() && !windowOpen && compressed && closedBeyond && fresh) {
            boxLevel.put(side, edge);
            boxBreakAt.put(side, now);
            windowOpen = true;
        }
        Double level = boxLevel.get(side);
        // a level that already opened a position counts again only after it was reclaimed (v10)
        boolean broke = windowOpen && level != null && !level.equals(usedLevel.get(side))
                && side.sign() * (snapshot.spot() - level) > 0;
        conditions.put(zone ? "zone_tested" : "box_compressed", compressed);
        conditions.put(zone ? "zone_broke" : "box_broke", broke);
        boolean orhOrl = conditions.get("broke_level");
        boxOnlyNow.put(side, broke && !orhOrl);
        conditions.put("broke_level", orhOrl || broke);
    }

    private void exit(OptionSide side, Stage stage, String reason, List<OrderIntent> orders) {
        orders.add(OrderIntent.exit(side, stage, reason));
        stages.put(side, Stage.EXITED);
        casManaged.put(side, false);
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
        String label = null;
        if (ext != null && ext.v6() != null) {
            if (direction >= ext.v6().trendMin() && structure >= ext.v6().trendMin()) {
                label = "TREND_UP";
            } else if (direction <= -ext.v6().trendMin() && structure <= -ext.v6().trendMin()) {
                label = "TREND_DOWN";
            } else if (Math.abs(direction) < ext.v6().rangeMax()) {
                label = "RANGE";
            } else {
                label = "TRANSITION";
            }
        }
        return new MarketState(direction, participation, structure, continuation, regime, label);
    }
}
