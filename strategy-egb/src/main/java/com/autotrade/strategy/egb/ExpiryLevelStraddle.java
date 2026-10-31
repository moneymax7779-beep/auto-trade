package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
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
 * Expiry-day level / compression break straddle with a target ladder (A-285, user 30 Oct 2026: "on expiry day NIFTY only
 * for now, when it crosses any of the level or compression break out buy both ... almost same premium PE and CE very near
 * the spot price and try 10% of total premium 20% 30% 50% 90%"). On the file's underlying's own expiry day, the first
 * snapshot in the entry window whose spot crossed a level since the previous snapshot (ORH, ORL, PDH, PDL, VWAP, previous
 * week / month high and low, a multiple of {@code setup.round_step}), or left the trailing box within the compression memory
 * (compression as ebs), buys a call and a put: of ATM - 1, ATM, ATM + 1 strikes the pair with the closest asks
 * ({@link OrderIntent#BALANCED_PAIR}). The executor sells both at the combined target (and the stop, 100 % = none); this
 * strategy sells them at {@code flat_by}, or {@code exits.time_stop_min} minutes after the position opened when that is set
 * (A-286; 0 or absent = none). One a day.
 */
final class ExpiryLevelStraddle implements Strategy {

    private final ThresholdConfig config;
    private final String underlying;
    private final LocalTime entryFrom, entryUntil, flatBy;
    private final double roundStep, rangeVsSessionMax, emaGapAtrMax, volumeRateRatioMax, budget, targetPct, stopPct;
    private final int memoryMin, timeStopMin;
    private double previousSpot = Double.NaN, previousBoxHigh = Double.NaN, previousBoxLow = Double.NaN;
    private Instant compressionUntil;
    private boolean asked, held, done;

    ExpiryLevelStraddle(ThresholdConfig c) {
        config = c;
        underlying = c.getString("scope.underlying");
        entryFrom = c.getTime("scope.entry_from");
        entryUntil = c.getTime("scope.entry_until");
        flatBy = c.getTime("scope.flat_by");
        roundStep = c.getDouble("setup.round_step");
        rangeVsSessionMax = c.getDouble("compression.range_vs_session_max");
        emaGapAtrMax = c.getDouble("compression.ema_gap_atr_max");
        volumeRateRatioMax = c.getDouble("compression.volume_rate_ratio_max");
        memoryMin = c.getInt("compression.memory_min");
        budget = c.getDouble("position.premium_budget");
        targetPct = c.getDouble("exits.target_pct");
        stopPct = c.getDouble("exits.premium_stop_pct");
        timeStopMin = c.has("exits.time_stop_min") ? c.getInt("exits.time_stop_min") : 0;
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
    public Decision decide(FeatureSnapshot s, PositionView position) {
        Instant now = s.time();
        LocalTime time = now.atZone(MarketTime.IST).toLocalTime();
        StructureFeatures st = s.structure();
        double spot = s.spot();
        boolean mine = underlying.equals(s.underlying()) && s.regime().dteTradingDays() == 0;
        boolean continuous = SessionPhase.valueOf(s.phase()).isContinuous();
        boolean compressedNow = s.compression().rangeVsSession() <= rangeVsSessionMax && s.compression().emaGapAtr() <= emaGapAtrMax
                && s.compression().volumeRateRatio() <= volumeRateRatioMax;
        if (compressedNow) {
            compressionUntil = now.plus(Duration.ofMinutes(memoryMin));
        }
        boolean compressedRecently = compressionUntil != null && !now.isAfter(compressionUntil);
        String crossed = crossed(st, previousSpot, spot);
        boolean boxBreak = compressedRecently && Double.isFinite(previousSpot) && Double.isFinite(spot)
                && (previousSpot <= previousBoxHigh && spot > previousBoxHigh || previousSpot >= previousBoxLow && spot < previousBoxLow);
        boolean window = !time.isBefore(entryFrom) && time.isBefore(entryUntil);
        boolean trigger = crossed != null || boxBreak;
        if (Double.isFinite(spot)) {
            previousSpot = spot;
        }
        previousBoxHigh = st.boxHigh();
        previousBoxLow = st.boxLow();

        Map<String, Boolean> c = new LinkedHashMap<>();
        c.put("own_expiry_day", mine);
        c.put("entry_window", window);
        c.put("compressed_recently", compressedRecently);
        c.put("level_cross", crossed != null);
        c.put("box_break", boxBreak);

        List<OrderIntent> orders = List.of();
        if (position.open()) {
            held = true;
            if (!time.isBefore(flatBy)) {
                orders = List.of(OrderIntent.exit(position.side(), Stage.CONFIRMED, "FLAT_BY"));
                done = true;
            } else if (timeStopMin > 0 && position.openedAt() != null
                    && !now.isBefore(position.openedAt().plus(Duration.ofMinutes(timeStopMin)))) {
                orders = List.of(OrderIntent.exit(position.side(), Stage.CONFIRMED, "TIME_STOP"));
                done = true;
            }
        } else if (held || position.closedToday() > 0) {
            held = false;                                    // the target closed it, possibly before this strategy saw
            done = true;                                     // it open (inside the entry minute on bars): one a day
        } else if (asked) {
            asked = false;                                   // refused before any position existed: the next trigger may try
        } else if (!done && mine && continuous && st.orComplete() && window && trigger) {
            orders = List.of(OrderIntent.straddle(OrderIntent.BALANCED_PAIR, budget, targetPct, stopPct, Stage.CONFIRMED,
                    crossed != null ? "CROSS_" + crossed : "BOX_BREAK"));
            asked = true;
        }
        Stage stage = done ? Stage.EXITED : position.open() || asked ? Stage.CONFIRMED
                : trigger && mine && window ? Stage.ARMED : mine ? Stage.WATCH : Stage.IDLE;
        Map<String, Boolean> conditions = java.util.Collections.unmodifiableMap(c);
        SideView view = new SideView(OptionSide.CE, stage, Double.NaN, Double.NaN, Double.NaN, conditions);
        SideView putView = new SideView(OptionSide.PE, stage, Double.NaN, Double.NaN, Double.NaN, conditions);
        MarketState state = new MarketState(Double.NaN, Double.NaN, Double.NaN, Double.NaN, mine ? "EXPIRY" : "NOT_EXPIRY", null);
        return new Decision(now, s.underlying(), state, view, putView, orders);
    }

    /** The name of a level the spot crossed from {@code before} to {@code now} (touching it counts), or null. */
    private String crossed(StructureFeatures st, double before, double now) {
        if (!Double.isFinite(before) || !Double.isFinite(now) || before == now) {
            return null;
        }
        Map<String, Double> levels = new LinkedHashMap<>();
        levels.put("ORH", st.orComplete() ? st.orHigh() : Double.NaN);
        levels.put("ORL", st.orComplete() ? st.orLow() : Double.NaN);
        levels.put("PDH", st.pdh());
        levels.put("PDL", st.pdl());
        levels.put("VWAP", st.vwapSpotProxy());
        levels.put("PWH", st.pwh());
        levels.put("PWL", st.pwl());
        levels.put("PMH", st.pmh());
        levels.put("PML", st.pml());
        for (Map.Entry<String, Double> e : levels.entrySet()) {
            double l = e.getValue();
            if (Double.isFinite(l) && (before < l && now >= l || before > l && now <= l)) {
                return e.getKey();
            }
        }
        double lo = Math.min(before, now), hi = Math.max(before, now);
        double round = Math.ceil(lo / roundStep) * roundStep;
        return round <= hi && round > lo ? "ROUND_" + Math.round(round) : null;
    }
}
