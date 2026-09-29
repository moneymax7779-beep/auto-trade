package com.autotrade.features.snapshot;

/**
 * Price structure of the spot index over continuous trading. Distances are signed
 * (price − level) in units of {@code atr3m}; NaN means not yet available. {@code prevOrHigh} /
 * {@code prevOrLow} are the previous session's opening-range high / low from its 1-minute spot bars and
 * {@code prevSessionMinutes} is how many such bars that session has (0 when none were found).
 * v8: {@code boxHigh} / {@code boxLow} span the {@code box_minutes} 1-minute bars before the latest
 * closed one, and {@code lastMinuteClose} is that latest bar's close (NaN when off or not yet full).
 * v9: {@code zoneLow} / {@code zoneHigh} are the low / high of the {@code zone_minutes} bars before the
 * latest, with the number of separate touches each had ({@code zoneLowTouches}, {@code zoneHighTouches}).
 */
public record StructureFeatures(
        double dayOpen,
        double orHigh,
        double orLow,
        boolean orComplete,
        double prevClose,
        double pdh,
        double pdl,
        double sessionMean,
        double vwapSpotProxy,
        double ema9,
        double ema20,
        double ema9Slope,
        double atr1m,
        double atr3m,
        double lastSwingHigh,
        double lastSwingLow,
        boolean higherLows,
        boolean lowerHighs,
        double distOrhAtr,
        double distOrlAtr,
        double distPdhAtr,
        double distPdlAtr,
        double distVwapAtr,
        double distEma20Atr,
        String nearestAbove,
        double nearestAboveAtr,
        String nearestBelow,
        double nearestBelowAtr,
        int orhClosesAbove,
        boolean orhRetestHeld,
        int orlClosesBelow,
        boolean orlRetestHeld,
        int pdhClosesAbove,
        int pdlClosesBelow,
        double lastBarClose,
        double lastBarBodyRatio,
        double lastBarCloseLocation,
        double lastBarUpperWickRatio,
        double lastBarLowerWickRatio,
        double lastBarRangeVsAvg,
        double spotChange30s,
        double spotChange1m,
        double spotChange3m,
        double dayHigh,
        double dayLow,
        double prevOrHigh,
        double prevOrLow,
        int prevSessionMinutes,
        double boxHigh,
        double boxLow,
        double lastMinuteClose,
        double zoneLow,
        int zoneLowTouches,
        double zoneHigh,
        int zoneHighTouches) {
}
