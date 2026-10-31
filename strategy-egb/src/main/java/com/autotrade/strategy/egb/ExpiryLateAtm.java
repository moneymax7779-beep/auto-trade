package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.strategy.OptionSide;

/**
 * Late expiry one-sided ATM buy (A-283, user 30 Oct 2026: "after 3.20PM buying one side which has direction CE or PE with
 * 30% target"; "run the replay I want to see it"). The expiring index on its expiry day only. On each snapshot of the entry
 * window the synthetic spot S (strike + call - put of the expiring series; the index feed is frozen after 15:15) is compared
 * with the previous snapshot's: a move of at least {@code setup.min_move_pct} % buys the option that way (call up, put down)
 * at the strike nearest S. The order manager sells it on the quote that reaches {@code exits.premium_target_pct} above or
 * {@code exits.premium_stop_pct} below the average cost; otherwise {@code flat_by}. One trade per index per day.
 * <p>
 * The engine snapshots once a minute, so the trigger is a minute-to-minute move; the A-283 study checks every second.
 * <p>
 * A-284 ({@code setup.trend_minutes}, off when absent): the trigger is a trend instead, S up at least
 * {@code setup.min_trend_pct} % over that many minutes, every one-minute step up, and S above its 15:15 value (mirrored down).
 */
final class ExpiryLateAtm extends SetupStrategy {

    private final double minMovePct;
    private final int trendMinutes;
    private final double minTrendPct;
    /** A-284: the synthetic spot per snapshot minute (minute of day). */
    private final java.util.Map<Integer, Double> byMinute = new java.util.HashMap<>();
    private double previousSynthetic = Double.NaN;
    private boolean taken;
    private double entryStrikeOffsetPoints = Double.NaN;

    ExpiryLateAtm(ThresholdConfig c) {
        super(c);
        minMovePct = c.has("setup.min_move_pct") ? c.getDouble("setup.min_move_pct") : Double.NaN;
        trendMinutes = c.has("setup.trend_minutes") ? c.getInt("setup.trend_minutes") : 0;
        minTrendPct = c.has("setup.min_trend_pct") ? c.getDouble("setup.min_trend_pct") : Double.NaN;
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        double synthetic = s.options().syntheticSpot(), before = previousSynthetic;
        if (Double.isFinite(synthetic)) {
            previousSynthetic = synthetic;
        }
        boolean expiring = s.regime().dteTradingDays() == 0;
        int minute = time.getHour() * 60 + time.getMinute();
        if (Double.isFinite(synthetic)) {
            byMinute.put(minute, synthetic);
        }
        boolean up, down;
        if (trendMinutes > 0) {                                                       // A-284
            int t = trend(minute);
            up = t > 0;
            down = t < 0;
        } else {
            double move = Double.isFinite(synthetic) && Double.isFinite(before) ? (synthetic / before - 1) * 100 : Double.NaN;
            up = move >= minMovePct;
            down = move <= -minMovePct;
        }
        c.put("expiry_day", expiring);
        c.put("move_up", up);
        c.put("move_down", down);
        if (!canEnter || taken || !expiring || !(up || down)) {
            return null;
        }
        taken = true;
        entryStrikeOffsetPoints = synthetic - s.options().atmStrike();
        return new Entry(up ? OptionSide.CE : OptionSide.PE, Double.NaN, Double.NaN, up ? "LATE_MOVE_UP" : "LATE_MOVE_DOWN");
    }

    /** A-284: +1 / -1 when the trend holds at {@code minute}, else 0. */
    private int trend(int minute) {
        Double ref = byMinute.get(15 * 60 + 15);
        double[] pts = new double[trendMinutes + 1];
        for (int i = 0; i <= trendMinutes; i++) {
            Double v = byMinute.get(minute - trendMinutes + i);
            if (v == null) {
                return 0;
            }
            pts[i] = v;
        }
        if (ref == null) {
            return 0;
        }
        double move = (pts[trendMinutes] / pts[0] - 1) * 100;
        boolean rising = true, falling = true;
        for (int i = 1; i <= trendMinutes; i++) {
            rising &= pts[i] > pts[i - 1];
            falling &= pts[i] < pts[i - 1];
        }
        if (move >= minTrendPct && rising && pts[trendMinutes] > ref) {
            return 1;
        }
        return move <= -minTrendPct && falling && pts[trendMinutes] < ref ? -1 : 0;
    }

    /** The strike nearest the synthetic spot, in the frame's ITM-positive offset from the (frozen) ATM strike. */
    @Override
    protected int strikeOffset(Entry entry, FeatureSnapshot s) {
        double step = s.options().strikeStep();
        if (!(step > 0) || !Double.isFinite(entryStrikeOffsetPoints)) {
            return 0;
        }
        int strikes = (int) Math.round(entryStrikeOffsetPoints / step);   // + = the nearest strike is above the ATM one
        return entry.side() == OptionSide.CE ? -strikes : strikes;
    }
}
