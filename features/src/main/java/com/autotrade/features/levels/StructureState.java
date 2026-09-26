package com.autotrade.features.levels;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.autotrade.core.history.DailyBar;
import com.autotrade.features.bars.Bar;
import com.autotrade.features.bars.BarSeries;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.indicators.Ema;
import com.autotrade.features.indicators.SwingTracker;
import com.autotrade.features.indicators.TimedSeries;
import com.autotrade.features.indicators.WilderAtr;
import com.autotrade.features.snapshot.LevelFeatures;
import com.autotrade.features.snapshot.StructureFeatures;

/**
 * Spot-index structure over continuous trading only (closing-auction values are excluded so they
 * cannot distort ATR, EMA or levels). Bars align to the session open.
 */
public final class StructureState {

    private static final int RANGE_AVERAGE_BARS = 10;
    private static final int CLOSES_KEPT = 120;

    /** Futures volume, for volume after a level break and breakout-bar volume (v3). */
    public interface FuturesVolume {
        /** Futures volume traded in the completed minutes of [from, to); NaN when no future is tracked. */
        double volume(Instant from, Instant to);

        /** Volume in [from, to) over the historical median volume of the same minutes; NaN when unknown. */
        double todRatio(Instant from, Instant to);
    }

    private final FeatureConfig config;
    private final Instant sessionOpen;
    private final Instant openingRangeEnd;
    private final BarSeries oneMinute;
    private final BarSeries threeMinute;
    private final WilderAtr atr1m;
    private final WilderAtr atr3m;
    private final Ema ema9;
    private final Ema ema20;
    private final SwingTracker swings;
    private final TimedSeries spotSeries = new TimedSeries(Duration.ofMinutes(15));
    private final Deque<Double> recentRanges = new ArrayDeque<>();
    private final double pdh;
    private final double pdl;
    private final LevelAcceptance pdhAcceptance;
    private final LevelAcceptance pdlAcceptance;

    private LevelAcceptance orhAcceptance;
    private LevelAcceptance orlAcceptance;
    private double dayOpen = Double.NaN;
    private double orHigh = Double.NEGATIVE_INFINITY;
    private double orLow = Double.POSITIVE_INFINITY;
    private double dayHigh = Double.NEGATIVE_INFINITY;
    private double dayLow = Double.POSITIVE_INFINITY;
    private double prevClose = Double.NaN;
    private double closeSum;
    private int closeCount;
    private double lastSpot = Double.NaN;
    private Bar lastThreeMinuteBar;
    private double lastBarRangeVsAvg = Double.NaN;
    private double vwapProxy = Double.NaN;
    private double previousOneMinuteClose = Double.NaN;
    private final Deque<long[]> ladderCrossings = new ArrayDeque<>();
    private final Deque<Bar> oneMinuteBars = new ArrayDeque<>();

    public StructureState(FeatureConfig config, Instant sessionOpen, Optional<DailyBar> previousSession) {
        this.config = config;
        this.sessionOpen = sessionOpen;
        this.openingRangeEnd = sessionOpen.plus(Duration.ofMinutes(config.openingRangeMinutes()));
        this.oneMinute = new BarSeries(Duration.ofMinutes(1), sessionOpen, 400);
        this.threeMinute = new BarSeries(Duration.ofMinutes(3), sessionOpen, 200);
        this.atr1m = new WilderAtr(config.atrPeriod());
        this.atr3m = new WilderAtr(config.atrPeriod());
        this.ema9 = new Ema(config.emaFast(), config.emaSlopeBars() + 1);
        this.ema20 = new Ema(config.emaSlow(), config.emaSlopeBars() + 1);
        this.swings = new SwingTracker(config.swingStrength());
        this.pdh = previousSession.map(DailyBar::high).orElse(Double.NaN);
        this.pdl = previousSession.map(DailyBar::low).orElse(Double.NaN);
        this.pdhAcceptance = Double.isNaN(pdh) ? null : new LevelAcceptance(pdh);
        this.pdlAcceptance = Double.isNaN(pdl) ? null : new LevelAcceptance(pdl);
        oneMinute.onClose(this::onOneMinuteClose);
        threeMinute.onClose(this::onThreeMinuteClose);
    }

    /** A continuous-trading spot observation. */
    public void onSpot(Instant time, double price, Double feedPreviousClose) {
        if (feedPreviousClose != null && feedPreviousClose > 0) {
            prevClose = feedPreviousClose;
        }
        if (Double.isNaN(dayOpen)) {
            dayOpen = price;
        }
        if (time.isBefore(openingRangeEnd)) {
            orHigh = Math.max(orHigh, price);
            orLow = Math.min(orLow, price);
        }
        dayHigh = Math.max(dayHigh, price);
        dayLow = Math.min(dayLow, price);
        oneMinute.update(time, price, 0);
        threeMinute.update(time, price, 0);
        spotSeries.add(time, price);
        lastSpot = price;
    }

    /** Closes bars that ended at or before {@code time}; call before taking a snapshot. */
    public void advanceTo(Instant time) {
        oneMinute.advanceTo(time);
        threeMinute.advanceTo(time);
        if (orhAcceptance == null && !time.isBefore(openingRangeEnd) && Double.isFinite(orHigh)) {
            orhAcceptance = new LevelAcceptance(orHigh);
            orlAcceptance = new LevelAcceptance(orLow);
        }
    }

    public TimedSeries spotSeries() {
        return spotSeries;
    }

    public double atr1m() {
        return atr1m.value();
    }

    public double atr3m() {
        return atr3m.value();
    }

    public double dayOpen() {
        return dayOpen;
    }

    public double dayRange() {
        return Double.isFinite(dayHigh) ? dayHigh - dayLow : Double.NaN;
    }

    private void onOneMinuteClose(Bar bar) {
        atr1m.update(bar);
        closeSum += bar.close();
        closeCount++;
        if (!Double.isNaN(previousOneMinuteClose)) {
            for (double level : ladder(bar.end()).values()) {
                if (Double.isNaN(level)) {
                    continue;
                }
                if (previousOneMinuteClose <= level && bar.close() > level) {
                    ladderCrossings.addLast(new long[] {bar.end().toEpochMilli(), 1});
                } else if (previousOneMinuteClose >= level && bar.close() < level) {
                    ladderCrossings.addLast(new long[] {bar.end().toEpochMilli(), -1});
                }
            }
        }
        previousOneMinuteClose = bar.close();
        oneMinuteBars.addLast(bar);
        if (oneMinuteBars.size() > CLOSES_KEPT) {
            oneMinuteBars.removeFirst();
        }
    }

    /** The spot VWAP proxy (futures VWAP − basis) used by the ladder; set by the engine as it changes. */
    public void setVwapProxy(double vwapProxy) {
        this.vwapProxy = vwapProxy;
    }

    /** Completed 1-minute bars of continuous trading, oldest first (at most the last {@value #CLOSES_KEPT}). */
    public List<Bar> oneMinuteBars() {
        return List.copyOf(oneMinuteBars);
    }

    /** The level ladder at {@code time}: opening range (once complete), previous day, anchors, swings. */
    private Map<String, Double> ladder(Instant time) {
        boolean orComplete = !time.isBefore(openingRangeEnd) && Double.isFinite(orHigh);
        SwingTracker.Swing swingHigh = swings.lastHigh();
        SwingTracker.Swing swingLow = swings.lastLow();
        Map<String, Double> ladder = new LinkedHashMap<>();
        ladder.put("ORH", orComplete ? orHigh : Double.NaN);
        ladder.put("ORL", orComplete ? orLow : Double.NaN);
        ladder.put("PDH", pdh);
        ladder.put("PDL", pdl);
        ladder.put("PREV_CLOSE", prevClose);
        ladder.put("DAY_OPEN", dayOpen);
        ladder.put("VWAP", vwapProxy);
        ladder.put("EMA20", ema20.value());
        ladder.put("SESSION_MEAN", closeCount == 0 ? Double.NaN : closeSum / closeCount);
        ladder.put("SWING_HIGH", swingHigh == null ? Double.NaN : swingHigh.price());
        ladder.put("SWING_LOW", swingLow == null ? Double.NaN : swingLow.price());
        return ladder;
    }

    /**
     * Level acceptance extras, ladder and room (features v3).
     *
     * @param expectedRemaining the expected remaining move for today, in points (NaN if unknown)
     */
    public LevelFeatures levelFeatures(Instant time, FuturesVolume futures, int ladderWindowMin,
                                       int breakoutVolumeAverageBars, double expectedRemaining) {
        double atr = atr3m.value();
        long windowStart = time.minus(Duration.ofMinutes(ladderWindowMin)).toEpochMilli();
        while (!ladderCrossings.isEmpty() && ladderCrossings.peekFirst()[0] < windowStart) {
            ladderCrossings.removeFirst();
        }
        int reclaims = 0;
        int losses = 0;
        for (long[] crossing : ladderCrossings) {
            if (crossing[1] > 0) {
                reclaims++;
            } else {
                losses++;
            }
        }
        int below = 0;
        int above = 0;
        double roomAbove = Double.NaN;
        double roomBelow = Double.NaN;
        if (!Double.isNaN(lastSpot)) {
            for (double level : ladder(time).values()) {
                if (Double.isNaN(level)) {
                    continue;
                }
                if (level < lastSpot) {
                    below++;
                    roomBelow = Double.isNaN(roomBelow) ? lastSpot - level : Math.min(roomBelow, lastSpot - level);
                } else if (level > lastSpot) {
                    above++;
                    roomAbove = Double.isNaN(roomAbove) ? level - lastSpot : Math.min(roomAbove, level - lastSpot);
                }
            }
        }
        double barVolumeRatio = Double.NaN;
        boolean barVolumeRising = false;
        Bar last = lastThreeMinuteBar;
        if (last != null) {
            Duration length = Duration.between(last.start(), last.end());
            double current = futures.volume(last.start(), last.end());
            double sum = 0;
            int bars = 0;
            for (int i = 1; i <= breakoutVolumeAverageBars; i++) {
                Instant start = last.start().minus(length.multipliedBy(i));
                if (start.isBefore(sessionOpen)) {
                    break;
                }
                double volume = futures.volume(start, start.plus(length));
                if (!Double.isNaN(volume)) {
                    sum += volume;
                    bars++;
                }
            }
            double previous = futures.volume(last.start().minus(length), last.start());
            barVolumeRatio = bars > 0 && sum > 0 && !Double.isNaN(current) ? current / (sum / bars) : Double.NaN;
            barVolumeRising = !Double.isNaN(current) && !Double.isNaN(previous) && current > previous;
        }
        return new LevelFeatures(
                orhAcceptance == null ? 0 : orhAcceptance.minutesAbove(time), travel(orhAcceptance, true, atr),
                afterBreak(orhAcceptance, true, time, futures),
                orlAcceptance == null ? 0 : orlAcceptance.minutesBelow(time), travel(orlAcceptance, false, atr),
                afterBreak(orlAcceptance, false, time, futures),
                pdhAcceptance == null ? 0 : pdhAcceptance.minutesAbove(time), travel(pdhAcceptance, true, atr),
                afterBreak(pdhAcceptance, true, time, futures), pdhAcceptance != null && pdhAcceptance.retestHeldAbove(),
                pdlAcceptance == null ? 0 : pdlAcceptance.minutesBelow(time), travel(pdlAcceptance, false, atr),
                afterBreak(pdlAcceptance, false, time, futures), pdlAcceptance != null && pdlAcceptance.retestHeldBelow(),
                below, above, reclaims, losses, barVolumeRatio, barVolumeRising, roomAbove, roomBelow,
                roomAbove > 0 ? expectedRemaining / roomAbove : Double.NaN,
                roomBelow > 0 ? expectedRemaining / roomBelow : Double.NaN);
    }

    private static double travel(LevelAcceptance level, boolean up, double atr) {
        if (level == null || !(atr > 0)) {
            return Double.NaN;
        }
        Instant broke = up ? level.brokeUpAt() : level.brokeDownAt();
        return broke == null ? Double.NaN : (up ? level.maxAboveAfterBreak() : level.maxBelowAfterBreak()) / atr;
    }

    private static double afterBreak(LevelAcceptance level, boolean up, Instant time, FuturesVolume futures) {
        if (level == null) {
            return Double.NaN;
        }
        Instant broke = up ? level.brokeUpAt() : level.brokeDownAt();
        return broke == null ? Double.NaN : futures.todRatio(broke, time);
    }

    private void onThreeMinuteClose(Bar bar) {
        atr3m.update(bar);
        ema9.update(bar.close());
        ema20.update(bar.close());
        swings.update(bar);
        double band = atr3m.ready() ? config.retestBandAtr() * atr3m.value() : 0;
        // The opening-range levels exist only once the range is complete; a bar that closes exactly
        // at the range end is the first one they see.
        if (orhAcceptance == null && !bar.end().isBefore(openingRangeEnd) && Double.isFinite(orHigh)) {
            orhAcceptance = new LevelAcceptance(orHigh);
            orlAcceptance = new LevelAcceptance(orLow);
        }
        if (orhAcceptance != null && bar.start().compareTo(openingRangeEnd) >= 0) {
            orhAcceptance.update(bar, band);
            orlAcceptance.update(bar, band);
        }
        if (pdhAcceptance != null) {
            pdhAcceptance.update(bar, band);
            pdlAcceptance.update(bar, band);
        }
        double average = recentRanges.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        lastBarRangeVsAvg = average > 0 ? bar.range() / average : Double.NaN;
        recentRanges.addLast(bar.range());
        if (recentRanges.size() > RANGE_AVERAGE_BARS) {
            recentRanges.removeFirst();
        }
        lastThreeMinuteBar = bar;
    }

    public StructureFeatures snapshot(Instant time, double vwapSpotProxy) {
        boolean orComplete = !time.isBefore(openingRangeEnd) && Double.isFinite(orHigh);
        double orh = orComplete ? orHigh : Double.NaN;
        double orl = orComplete ? orLow : Double.NaN;
        double atr = atr3m.value();
        double sessionMean = closeCount == 0 ? Double.NaN : closeSum / closeCount;
        SwingTracker.Swing swingHigh = swings.lastHigh();
        SwingTracker.Swing swingLow = swings.lastLow();

        this.vwapProxy = vwapSpotProxy;
        Map<String, Double> ladder = ladder(time);
        String above = null;
        String below = null;
        double aboveGap = Double.POSITIVE_INFINITY;
        double belowGap = Double.POSITIVE_INFINITY;
        for (Map.Entry<String, Double> level : ladder.entrySet()) {
            double price = level.getValue();
            if (Double.isNaN(price) || Double.isNaN(lastSpot)) {
                continue;
            }
            if (price > lastSpot && price - lastSpot < aboveGap) {
                aboveGap = price - lastSpot;
                above = level.getKey();
            } else if (price < lastSpot && lastSpot - price < belowGap) {
                belowGap = lastSpot - price;
                below = level.getKey();
            }
        }
        Bar last = lastThreeMinuteBar;
        return new StructureFeatures(
                dayOpen, orh, orl, orComplete, prevClose, pdh, pdl, sessionMean, vwapSpotProxy,
                ema9.value(), ema20.value(), ema9.slope(config.emaSlopeBars()), atr1m.value(), atr,
                swingHigh == null ? Double.NaN : swingHigh.price(), swingLow == null ? Double.NaN : swingLow.price(),
                swings.higherLows(3), swings.lowerHighs(3),
                distance(orh, atr), distance(orl, atr), distance(pdh, atr), distance(pdl, atr),
                distance(vwapSpotProxy, atr), distance(ema20.value(), atr),
                above, above == null ? Double.NaN : -aboveGap / atr,
                below, below == null ? Double.NaN : belowGap / atr,
                orhAcceptance == null ? 0 : orhAcceptance.closesAbove(),
                orhAcceptance != null && orhAcceptance.retestHeldAbove(),
                orlAcceptance == null ? 0 : orlAcceptance.closesBelow(),
                orlAcceptance != null && orlAcceptance.retestHeldBelow(),
                pdhAcceptance == null ? 0 : pdhAcceptance.closesAbove(),
                pdlAcceptance == null ? 0 : pdlAcceptance.closesBelow(),
                last == null ? Double.NaN : last.close(),
                last == null || last.range() == 0 ? Double.NaN : last.body() / last.range(),
                last == null ? Double.NaN : last.closeLocation(),
                last == null || last.range() == 0 ? Double.NaN : last.upperWick() / last.range(),
                last == null || last.range() == 0 ? Double.NaN : last.lowerWick() / last.range(),
                lastBarRangeVsAvg,
                spotSeries.change(time, Duration.ofSeconds(30)),
                spotSeries.change(time, Duration.ofMinutes(1)),
                spotSeries.change(time, Duration.ofMinutes(3)),
                Double.isFinite(dayHigh) ? dayHigh : Double.NaN,
                Double.isFinite(dayLow) ? dayLow : Double.NaN);
    }

    /** Signed (spot − level) / ATR. */
    private double distance(double level, double atr) {
        if (Double.isNaN(level) || Double.isNaN(lastSpot) || !(atr > 0)) {
            return Double.NaN;
        }
        return (lastSpot - level) / atr;
    }
}
