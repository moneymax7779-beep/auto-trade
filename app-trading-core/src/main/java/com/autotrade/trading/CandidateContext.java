package com.autotrade.trading;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.strategy.SideView;

/**
 * The market at a candidate entry, as the snapshot measured it: what the strategy saw (its scores and
 * conditions for the side) and the context a sizing rule might use: structure, flow, volatility, breadth,
 * time. Raw values (not signed by side); non-finite values are left out.
 */
final class CandidateContext {

    private CandidateContext() {
    }

    static Map<String, Object> of(FeatureSnapshot s, SideView side) {
        Map<String, Object> c = new LinkedHashMap<>();
        LocalTime t = s.time().atZone(MarketTime.IST).toLocalTime();
        c.put("minuteOfDay", t.getHour() * 60 + t.getMinute());
        c.put("phase", s.phase());
        put(c, "dte", s.regime().dteTradingDays());
        put(c, "minutesToExpiry", s.regime().minutesToExpiryClose());
        put(c, "expectedMoveRemaining", s.regime().expectedMoveRemaining());
        put(c, "dayRangeVsExpected", s.regime().dayRangeVsExpected());
        var st = s.structure();
        put(c, "atr3m", st.atr3m());
        put(c, "distOrhAtr", st.distOrhAtr());
        put(c, "distOrlAtr", st.distOrlAtr());
        put(c, "distPdhAtr", st.distPdhAtr());
        put(c, "distPdlAtr", st.distPdlAtr());
        put(c, "distVwapAtr", st.distVwapAtr());
        put(c, "distEma20Atr", st.distEma20Atr());
        put(c, "nearestAbove", st.nearestAbove());
        put(c, "nearestAboveAtr", st.nearestAboveAtr());
        put(c, "nearestBelow", st.nearestBelow());
        put(c, "nearestBelowAtr", st.nearestBelowAtr());
        put(c, "spotChange3m", st.spotChange3m());
        put(c, "dayHigh", st.dayHigh());
        put(c, "dayLow", st.dayLow());
        put(c, "lastBarBodyRatio", st.lastBarBodyRatio());
        put(c, "lastBarCloseLocation", st.lastBarCloseLocation());
        put(c, "lastBarRangeVsAvg", st.lastBarRangeVsAvg());
        var f = s.futures();
        put(c, "futMomentum1mNorm", f.momentum1mNorm());
        put(c, "futAcceleration1m", f.acceleration1m());
        put(c, "futOiState", f.oiState());
        put(c, "futOiChange3m", f.oiChange3m());
        put(c, "futRvolTod", f.rvolTod());
        put(c, "futRvolSlope", f.rvolSlope());
        put(c, "basisChange3m", f.basisChange3m());
        var o = s.options();
        put(c, "atmIv", o.atmIv());
        put(c, "atmIvChange3m", o.atmIvChange3m());
        put(c, "straddle", o.straddle());
        put(c, "straddleChange5m", o.straddleChange5m());
        put(c, "ceOiFlow", o.ceOiFlow());
        put(c, "peOiFlow", o.peOiFlow());
        put(c, "ceOiChange5m", o.ceOiChange5m());
        put(c, "peOiChange5m", o.peOiChange5m());
        put(c, "premiumResponseCe", o.premiumResponseCe());
        put(c, "premiumResponsePe", o.premiumResponsePe());
        put(c, "ivSkew", o.ivSkew());
        put(c, "moverBreadth", s.breadth().moverBreadth());
        put(c, "momentumBreadth", s.breadth().momentumBreadth());
        put(c, "ivPercentile", s.volatility().atmIvPercentile());
        put(c, "realizedVolPercentile", s.volatility().realizedVolPercentile());
        put(c, "vix", s.volatility().vix());
        put(c, "vixChange15m", s.volatility().vixChange15m());
        if (side != null) {
            c.put("stage", side.stage().name());
            put(c, "early", side.earlyScore());
            put(c, "confirm", side.confirmScore());
            put(c, "runner", side.runnerScore());
            c.put("conditions", side.conditions());
        }
        return c;
    }

    private static void put(Map<String, Object> c, String key, Object value) {
        if (value == null || value instanceof Double d && !Double.isFinite(d)) {
            return;
        }
        c.put(key, value instanceof Double d ? Math.round(d * 10_000) / 10_000.0 : value);
    }
}
