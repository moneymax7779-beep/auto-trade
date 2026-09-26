package com.autotrade.features.snapshot;

/**
 * Level acceptance beyond "closes beyond" and "retest held" (those are in {@link StructureFeatures}),
 * the level ladder, breakout-bar volume and room to the next level (v3).
 *
 * <ul>
 *   <li>{@code *MinutesBeyond}: minutes since the last 3-minute close through the level, while price
 *       still closes beyond it (0 otherwise).</li>
 *   <li>{@code *TravelAtr}: furthest distance travelled beyond the level since that break, in ATR3m.</li>
 *   <li>{@code *VolumeAfterBreak}: futures volume since the break over the historical median volume of
 *       the same minutes (time-of-day normalised; 1 = normal).</li>
 *   <li>Ladder: how many of the ladder levels (ORH, ORL, PDH, PDL, previous close, day open, VWAP,
 *       EMA20, session mean, last swings) are below / above spot, and how many were reclaimed
 *       (crossed upward) or lost (crossed downward) on 1-minute closes in the ladder window.</li>
 *   <li>{@code breakoutBarVolumeRatio}: futures volume in the last 3-minute bar over the average of the
 *       previous bars; {@code breakoutBarVolumeRising}: more volume than the bar before.</li>
 *   <li>Room: points to the next ladder level above / below, and the expected remaining move divided
 *       by that room (at least 1 = the level is within the move the options price for today).</li>
 * </ul>
 */
public record LevelFeatures(
        double orhMinutesBeyond,
        double orhTravelAtr,
        double orhVolumeAfterBreak,
        double orlMinutesBeyond,
        double orlTravelAtr,
        double orlVolumeAfterBreak,
        double pdhMinutesBeyond,
        double pdhTravelAtr,
        double pdhVolumeAfterBreak,
        boolean pdhRetestHeld,
        double pdlMinutesBeyond,
        double pdlTravelAtr,
        double pdlVolumeAfterBreak,
        boolean pdlRetestHeld,
        int ladderLevelsBelow,
        int ladderLevelsAbove,
        int ladderReclaims,
        int ladderLosses,
        double breakoutBarVolumeRatio,
        boolean breakoutBarVolumeRising,
        double roomAbovePoints,
        double roomBelowPoints,
        double expectedReachAbove,
        double expectedReachBelow) {

    public static final LevelFeatures EMPTY = new LevelFeatures(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, Double.NaN, Double.NaN, Double.NaN,
            false, 0, 0, 0, 0, Double.NaN, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
}
