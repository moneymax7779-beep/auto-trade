package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * EMA9 / EMA20 cross with a swing stop (A-066): the minute the fast average crosses the slow one, buy the ATM option in
 * the cross's direction; stop at the last swing low (high); target twice the stop distance. The SetupStrategy frame
 * handles the window, the expiring index, the exits and the size.
 * <p>
 * v2 (A-079, {@code setup.require_vwap_side: true}): a cross up enters only with the index above the session VWAP, a
 * cross down only below it (a cross against the VWAP is a bounce inside the day's trend). Absent = v1.
 */
final class MaCross extends SetupStrategy {

    private static final Logger log = LoggerFactory.getLogger(MaCross.class);
    private final java.util.ArrayDeque<java.time.Instant> crosses = new java.util.ArrayDeque<>();
    private final double maxStopAtr;
    private final double rewardToRisk;
    private final boolean requireVwapSide;
    private double previousDiff = Double.NaN;

    MaCross(ThresholdConfig c) {
        super(c);
        maxStopAtr = c.getDouble("setup.max_stop_atr");
        rewardToRisk = c.getDouble("setup.reward_to_risk");
        requireVwapSide = c.has("setup.require_vwap_side") && c.getBoolean("setup.require_vwap_side");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        double diff = st.ema9() - st.ema20();
        boolean crossUp = Double.isFinite(diff) && Double.isFinite(previousDiff) && previousDiff < 0 && diff > 0;
        boolean crossDown = Double.isFinite(diff) && Double.isFinite(previousDiff) && previousDiff > 0 && diff < 0;
        previousDiff = Double.isFinite(diff) ? diff : previousDiff;
        c.put("cross_up", crossUp);
        c.put("cross_down", crossDown);
        if (crossUp || crossDown) {
            // A-332 anatomy: every cross with its context (one line, parsed by .local/a332); no behaviour change
            while (!crosses.isEmpty() && crosses.peekFirst().isBefore(s.time().minusSeconds(3600))) {
                crosses.pollFirst();
            }
            double sw = crossUp ? st.lastSwingLow() : st.lastSwingHigh();
            log.info("A332CROSS {} {} {} canEnter={} spot={} ema9={} ema20={} slope={} atr3m={} atr1m={} swing={} riskAtr={} "
                    + "vwapAtr={} orhAtr={} orlAtr={} ema20Atr={} dayHigh={} dayLow={} open={} body={} rangeVsAvg={} chg3m={} "
                    + "chg1m={} higherLows={} lowerHighs={} prior60={} pdhAtr={} pdlAtr={}", s.underlying(), time,
                    crossUp ? "UP" : "DOWN", canEnter, s.spot(), st.ema9(), st.ema20(), st.ema9Slope(), st.atr3m(), st.atr1m(),
                    sw, (crossUp ? s.spot() - sw : sw - s.spot()) / st.atr3m(), st.distVwapAtr(), st.distOrhAtr(),
                    st.distOrlAtr(), st.distEma20Atr(), st.dayHigh(), st.dayLow(), st.dayOpen(), st.lastBarBodyRatio(),
                    st.lastBarRangeVsAvg(), st.spotChange3m(), st.spotChange1m(), st.higherLows(), st.lowerHighs(),
                    crosses.size(), st.distPdhAtr(), st.distPdlAtr());
            crosses.addLast(s.time());
        }
        if (!canEnter || !(crossUp || crossDown)) {
            return null;
        }
        double spot = s.spot(), atr = st.atr3m();
        if (requireVwapSide) {
            double vwap = st.vwapSpotProxy();
            boolean withVwap = crossUp ? spot > vwap : spot < vwap;   // NaN compares false: no VWAP, no entry
            c.put("vwap_side", withVwap);
            if (!withVwap) {
                return null;
            }
        }
        double stop = crossUp ? st.lastSwingLow() : st.lastSwingHigh();
        double risk = crossUp ? spot - stop : stop - spot;
        boolean stopOk = Double.isFinite(stop) && atr > 0 && risk > 0 && risk <= maxStopAtr * atr;
        c.put("swing_stop_ok", stopOk);
        if (!stopOk) {
            return null;
        }
        double target = crossUp ? spot + rewardToRisk * risk : spot - rewardToRisk * risk;
        return new Entry(crossUp ? OptionSide.CE : OptionSide.PE, stop, target, crossUp ? "EMA_CROSS_UP" : "EMA_CROSS_DOWN");
    }
}
