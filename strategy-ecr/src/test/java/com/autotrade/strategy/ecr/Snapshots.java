package com.autotrade.strategy.ecr;

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

    static final LocalDate SESSION = LocalDate.of(2026, 9, 18);

    private final Map<String, Object> values = new HashMap<>();

    Snapshots set(String path, Object value) {
        values.put(path, value);
        return this;
    }

    /** A bullish setup at the ORH where every early-entry condition holds. */
    static Snapshots bullishEarly() {
        return new Snapshots()
                .set("spot", 23110.0)
                .set("structure.orComplete", true)
                .set("structure.distOrhAtr", -0.05)
                .set("structure.distOrlAtr", 5.0)
                .set("structure.vwapSpotProxy", 23090.0)
                .set("structure.ema9", 23105.0)
                .set("structure.ema20", 23100.0)
                .set("structure.ema9Slope", 2.0)
                .set("structure.lastSwingLow", 23095.0)
                .set("structure.higherLows", true)
                .set("futures.momentum1m", 6.0)
                .set("futures.acceleration1m", 3.0)
                .set("futures.rvolSlope", 0.1)
                .set("futures.rvolTod", 1.0)
                .set("futures.oiState", "NEUTRAL")
                .set("breadth.momentumBreadth", 60.0)
                .set("regime.dteTradingDays", 3)
                .set("options.atmStrike", 23100.0);
    }

    /** The same setup after a strong 3-minute close through the ORH with volume. */
    Snapshots confirmedBreakout() {
        return set("structure.distOrhAtr", 0.6)
                .set("structure.orhClosesAbove", 1)
                .set("structure.lastBarBodyRatio", 0.8)
                .set("structure.lastBarCloseLocation", 0.9)
                .set("structure.lastBarUpperWickRatio", 0.05)
                .set("structure.lastBarRangeVsAvg", 1.6)
                .set("structure.lastBarClose", 23125.0)
                .set("futures.rvolTod", 1.6)
                .set("futures.basisChange3m", 1.0)
                .set("futures.oiState", "FRESH_LONG")
                .set("options.ceOiFlow", "UNWIND")
                .set("options.peOiFlow", "BUILD")
                .set("options.premiumResponseCe", 1.0)
                .set("options.atmIvChange3m", 0.001)
                .set("options.atmCeSpreadPct", 0.2);
    }

    FeatureSnapshot at(String time) {
        Instant instant = SESSION.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant();
        String phase = LocalTime.parse(time).isBefore(LocalTime.of(15, 15)) ? "TREND_WINDOW" : "CAS_REFERENCE";
        return new FeatureSnapshot(instant, "NIFTY", SESSION, (String) values.getOrDefault("phase", phase),
                (double) values.getOrDefault("spot", Double.NaN), 0, 0, 0,
                build(StructureFeatures.class, "structure."), build(FuturesFeatures.class, "futures."),
                build(OptionsFeatures.class, "options."), build(BreadthFeatures.class, "breadth."),
                build(RegimeFeatures.class, "regime."), build(CasFeatures.class, "cas."),
                build(LevelFeatures.class, "levels."), build(BookFeatures.class, "book."),
                build(PremiumFeatures.class, "premium."), build(VolatilityFeatures.class, "volatility."), "f", "e");
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
