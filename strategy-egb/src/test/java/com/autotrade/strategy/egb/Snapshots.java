package com.autotrade.strategy.egb;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.BookFeatures;
import com.autotrade.features.snapshot.BreadthFeatures;
import com.autotrade.features.snapshot.CasFeatures;
import com.autotrade.features.snapshot.CompressionFeatures;
import com.autotrade.features.snapshot.GammaFeatures;
import com.autotrade.features.snapshot.RetestFeatures;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.FuturesFeatures;
import com.autotrade.features.snapshot.LevelFeatures;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.snapshot.PremiumFeatures;
import com.autotrade.features.snapshot.RegimeFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.features.snapshot.VolatilityFeatures;

/**
 * Builds feature snapshots for tests: every field defaults to "unknown" (NaN / 0 / false / null)
 * and a test sets only what it needs, e.g. {@code set("structure.distOrhAtr", 0.05)}.
 */
final class Snapshots {

    static final LocalDate SESSION = LocalDate.of(2026, 9, 22);

    private final Map<String, Object> values = new HashMap<>();

    Snapshots set(String path, Object value) {
        values.put(path, value);
        return this;
    }

    /**
     * Expiry day, 12:40: NIFTY compressed and coiling just under the ORH (23116) with bullish structure
     * and accelerating futures, gamma paying; ATM and 1-ITM calls quoted.
     */
    static Snapshots bullishCoil() {
        return new Snapshots()
                .set("spot", 23115.0)
                .set("regime.dteTradingDays", 0)
                .set("regime.minutesToExpiryClose", 170L)
                .set("structure.orComplete", true)
                .set("structure.orHigh", 23116.0)
                .set("structure.orLow", 23030.0)
                .set("structure.pdh", 23200.0)
                .set("structure.pdl", 22950.0)
                .set("structure.atr3m", 10.0)
                .set("structure.distOrhAtr", -0.1)                   // (23115 − 23116) / 10
                .set("structure.distOrlAtr", 8.4)
                .set("structure.distPdhAtr", -8.6)
                .set("structure.distPdlAtr", 16.4)
                .set("structure.vwapSpotProxy", 23100.0)
                .set("structure.ema9", 23110.0)
                .set("structure.ema20", 23106.0)
                .set("structure.ema9Slope", 1.5)
                .set("structure.higherLows", true)
                .set("structure.lastBarClose", 23113.0)
                .set("structure.lastSwingLow", 23104.0)
                .set("compression.rangeVsSession", 0.5)
                .set("compression.emaGapAtr", 0.2)
                .set("compression.volumeRateRatio", 0.8)
                .set("compression.vwapCrosses", 0)
                .set("futures.momentum1m", 6.0)
                .set("futures.acceleration1m", 3.0)
                .set("futures.rvolSlope", 0.2)
                .set("futures.oiState", "FRESH_LONG")
                .set("breadth.moverBreadth", 60.0)
                .set("levels.roomAbovePoints", 84.0)
                .set("levels.roomBelowPoints", 10.0)
                .set("gamma.horizonMin", 5)
                .set("gamma.gammaRegime", "HIGH")
                .set("gamma.breakevenCe", 20.0)
                .set("gamma.breakevenPe", 20.0)
                .set("gamma.gammaPnlCe", 0.5)
                .set("gamma.gammaPnlPe", -0.5)
                .set("gamma.atmCeDelta", 0.48)
                .set("gamma.atmCeSpreadPct", 0.3)
                .set("gamma.itmCeDelta", 0.66)
                .set("gamma.itmCeSpreadPct", 0.3)
                .set("gamma.atmPeDelta", -0.52)
                .set("gamma.atmPeSpreadPct", 0.3)
                .set("gamma.itmPeDelta", -0.7)
                .set("gamma.itmPeSpreadPct", 0.3)
                .set("retest.orhState", "NONE")
                .set("retest.orlState", "NONE")
                .set("retest.pdhState", "NONE")
                .set("retest.pdlState", "NONE");
    }

    /** The ORH broken on a strong, high-volume 3-minute close; compression has ended (range expanding). */
    Snapshots breakout() {
        return set("spot", 23126.0)
                .set("structure.distOrhAtr", 1.0)
                .set("structure.orhClosesAbove", 1)
                .set("structure.lastBarClose", 23125.0)
                .set("structure.lastBarBodyRatio", 0.8)
                .set("structure.lastBarCloseLocation", 0.9)
                .set("structure.lastBarUpperWickRatio", 0.05)
                .set("levels.breakoutBarVolumeRatio", 1.8)
                .set("levels.roomAbovePoints", 74.0)
                .set("compression.rangeVsSession", 1.4)
                .set("compression.volumeRateRatio", 1.9)
                .set("retest.orhState", "BROKEN")
                .set("options.premiumResponseCe", 1.1);
    }

    FeatureSnapshot at(String time) {
        Instant instant = SESSION.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant();
        LocalTime t = LocalTime.parse(time);
        String phase = t.isBefore(LocalTime.of(11, 15)) ? "TREND_WINDOW" : t.isBefore(LocalTime.of(13, 15)) ? "MIDDAY"
                : t.isBefore(LocalTime.of(14, 30)) ? "AFTERNOON" : t.isBefore(LocalTime.of(15, 15)) ? "LATE" : "CAS_REFERENCE";
        return new FeatureSnapshot(instant, "NIFTY", SESSION, (String) values.getOrDefault("phase", phase),
                (double) values.getOrDefault("spot", Double.NaN), 0, 0, 0,
                build(StructureFeatures.class, "structure."), build(FuturesFeatures.class, "futures."),
                build(OptionsFeatures.class, "options."), build(BreadthFeatures.class, "breadth."),
                build(RegimeFeatures.class, "regime."), build(CasFeatures.class, "cas."),
                build(LevelFeatures.class, "levels."), build(BookFeatures.class, "book."),
                build(PremiumFeatures.class, "premium."), build(VolatilityFeatures.class, "volatility."),
                build(CompressionFeatures.class, "compression."), build(GammaFeatures.class, "gamma."),
                build(RetestFeatures.class, "retest."), "f", "e");
    }

    private <T extends Record> T build(Class<T> type, String prefix) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            Class<?> t = components[i].getType();
            types[i] = t;
            Object value = values.get(prefix + components[i].getName());
            if (value == null) {
                value = t == double.class ? Double.NaN : t == int.class ? 0 : t == long.class ? 0L
                        : t == boolean.class ? false : null;
            } else if (t == double.class && value instanceof Number n) {
                value = n.doubleValue();
            } else if (t == int.class && value instanceof Number n) {
                value = n.intValue();
            } else if (t == long.class && value instanceof Number n) {
                value = n.longValue();
            }
            args[i] = value;
        }
        try {
            Constructor<T> constructor = type.getDeclaredConstructor(types);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
