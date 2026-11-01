package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.greeks.BlackScholes;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.PositionView;

/**
 * Market-data exits for a long expiry straddle (A-287, user 1 Nov 2026: "the exit should be based on the data OI changes,
 * future volume and other S&R if needed level break outs and false breakouts, shouldn't be 10% or 30 minutes holding";
 * "premiums evaporate on late exit"). Checked once a minute, on the first snapshot after a minute closes, with the closed
 * minute's index close, high and low (built here from the snapshots' spot: the engine's own one-minute close is only kept
 * when the features file sets a box or zone, which v12 does not) and futures volume; any rule sells both legs. "Up" is the side of the trigger level the
 * index closed on at the entry; the move's side at a minute is the side of the trigger level that minute closed on.
 * <ul>
 *   <li>{@code THETA}: the index moved less over the last {@code theta.rv_minutes} minutes than the options charge for,
 *       once the position is {@code theta.min_age_min} minutes old. Realized volatility = the one-minute log returns'
 *       (annualised on calendar minutes, as the engine's IV). {@code theta.basis} {@code ATM_IV} (absent: A-287 as run):
 *       realized below {@code theta.rv_vs_iv_min} x the ATM IV. {@code HELD_FAIR_VALUE}: the held legs priced
 *       (Black-Scholes, to the options' close) at the realized volatility are worth less than
 *       {@code theta.rv_vs_iv_min} x what their bids pay now, i.e. the market charges the actual strikes more time value
 *       than the index's movement earns.</li>
 *   <li>{@code FALSE_BREAK}: {@code false_break.closes} minute closes in a row back on the entry's wrong side of the
 *       trigger level, each on futures volume at or below the session's median minute (a return on heavy volume is a
 *       reversal, which the other leg is for).</li>
 *   <li>{@code REJECTED_<level>} (in profit): since the entry the index reached a level beyond the trigger in the move's
 *       direction (ORH, ORL, PDH, PDL, VWAP, previous week / month high and low, a multiple of {@code round_step}, the
 *       call OI wall above / put OI wall below) and the minute closed back across it while that side's near-ATM option
 *       OI was being built (writers defending it).</li>
 *   <li>{@code CLIMAX} (in profit): a minute's futures volume at least {@code climax.volume_x_median} x the session
 *       median that closed in the third of its range against the move (absorption).</li>
 *   <li>{@code FUT_OI_AGAINST} (in profit): the futures price / OI state against the move ({@code FRESH_SHORT} or
 *       {@code LONG_UNWIND} for up, {@code FRESH_LONG} or {@code SHORT_COVERING} for down) on
 *       {@code fut_oi.minutes} minute checks in a row.</li>
 *   <li>{@code SWING_TRAIL} (in profit): a minute close beyond the last swing low (up; high for down). {@code swing_trail}
 *       {@code true} (A-287 as run): the engine's last swing, if it lies beyond the trigger level (it can predate the entry).
 *       {@code ENTRY_PIVOTS}: the last pivot of the minute bars since the entry (a low / high under / over the two minutes
 *       on each side of it), so only a swing of the trade's own move counts.</li>
 * </ul>
 * "In profit" = the legs' combined bid above the combined premium paid ({@link PositionView#heldValueRatio()}).
 * A rule whose config block is absent is off.
 */
final class StraddleExits {

    private static final double CALENDAR_MINUTES_PER_YEAR = 365.0 * 24 * 60;
    private static final LocalTime OPTIONS_CLOSE = LocalTime.of(15, 30);
    private static final int PIVOT_SPAN = 2;
    private static final Set<String> BUILDING = Set.of("BUILD", "ACCELERATING_BUILD", "FADING_BUILD");

    private final int rvMinutes, thetaMinAgeMin, falseBreakCloses, futOiMinutes;
    private final double rvVsIvMin, climaxVolumeX, roundStep;
    private final boolean theta, falseBreak, rejection, climax, futOi, swingTrail, heldFairValue, entryPivots;

    private final List<Double> closes = new ArrayList<>();          // one-minute index closes of the session
    private final List<Long> volumes = new ArrayList<>();
    private final List<double[]> sinceEntry = new ArrayList<>();      // the closed minutes' {high, low} since the entry
    private LocalTime minute;
    private double minuteHigh = Double.NaN, minuteLow = Double.NaN, minuteClose = Double.NaN;
    private double extremeHigh = Double.NaN, extremeLow = Double.NaN;
    private int wrongSideCloses, futOiAgainst;

    StraddleExits(ThresholdConfig c) {
        roundStep = c.getDouble("setup.round_step");
        theta = c.has("exits.signals.theta");
        rvMinutes = theta ? c.getInt("exits.signals.theta.rv_minutes") : 0;
        rvVsIvMin = theta ? c.getDouble("exits.signals.theta.rv_vs_iv_min") : Double.NaN;
        thetaMinAgeMin = theta ? c.getInt("exits.signals.theta.min_age_min") : 0;
        heldFairValue = theta && c.has("exits.signals.theta.basis")
                && basis(c.getString("exits.signals.theta.basis"), "ATM_IV", "HELD_FAIR_VALUE").equals("HELD_FAIR_VALUE");
        falseBreak = c.has("exits.signals.false_break");
        falseBreakCloses = falseBreak ? c.getInt("exits.signals.false_break.closes") : 0;
        rejection = c.has("exits.signals.rejection") && c.getBoolean("exits.signals.rejection");
        climax = c.has("exits.signals.climax");
        climaxVolumeX = climax ? c.getDouble("exits.signals.climax.volume_x_median") : Double.NaN;
        futOi = c.has("exits.signals.fut_oi");
        futOiMinutes = futOi ? c.getInt("exits.signals.fut_oi.minutes") : 0;
        Object swing = c.has("exits.signals.swing_trail") ? c.get("exits.signals.swing_trail") : Boolean.FALSE;
        entryPivots = swing instanceof String text && basis(text, "ENTRY_PIVOTS").equals("ENTRY_PIVOTS");
        swingTrail = entryPivots || Boolean.TRUE.equals(swing);
    }

    private static String basis(String value, String... allowed) {
        for (String a : allowed) {
            if (a.equalsIgnoreCase(value)) {
                return a;
            }
        }
        throw new IllegalArgumentException("unknown exit basis '" + value + "', expected one of " + List.of(allowed));
    }

    static boolean configured(ThresholdConfig c) {
        return c.has("exits.signals");
    }

    /**
     * Records {@code s} (every snapshot of the session, held or not) and, when a minute just closed and {@code position}
     * is open, returns the first rule that fires, else null. {@code trigger} is the level the entry crossed (the box edge
     * for a box break), {@code entryUp} whether the index was above it at the entry.
     */
    String update(FeatureSnapshot s, PositionView position, double trigger, boolean entryUp) {
        LocalTime now = s.time().atZone(MarketTime.IST).toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        double spot = s.spot();
        boolean closed = minute != null && now.isAfter(minute) && Double.isFinite(minuteClose);
        LocalTime closedMinute = minute;
        double high = minuteHigh, low = minuteLow, close = minuteClose;
        if (closed) {
            closes.add(close);
            volumes.add(s.futures().volumeLastMinute());
        }
        if (minute == null || now.isAfter(minute)) {
            minute = now;
            minuteHigh = minuteLow = minuteClose = Double.NaN;
        }
        boolean open = position.open() && position.openedAt() != null;
        if (Double.isFinite(spot)) {
            minuteHigh = Double.isNaN(minuteHigh) ? spot : Math.max(minuteHigh, spot);
            minuteLow = Double.isNaN(minuteLow) ? spot : Math.min(minuteLow, spot);
            minuteClose = spot;
            if (open) {                                      // the move since the entry, not the entry minute's start
                extremeHigh = Double.isNaN(extremeHigh) ? spot : Math.max(extremeHigh, spot);
                extremeLow = Double.isNaN(extremeLow) ? spot : Math.min(extremeLow, spot);
            }
        }
        if (!open) {
            extremeHigh = extremeLow = Double.NaN;
            wrongSideCloses = futOiAgainst = 0;
            sinceEntry.clear();
            return null;
        }
        if (!closed || !Double.isFinite(trigger)) {
            return null;
        }
        LocalTime entryMinute = position.openedAt().atZone(MarketTime.IST).toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        if (!closedMinute.isBefore(entryMinute)) {
            sinceEntry.add(new double[] {high, low});                // the entry's own minute onwards
        }
        long volume = volumes.getLast();
        boolean up = close != trigger ? close > trigger : entryUp;
        double median = median();
        boolean inProfit = position.heldValueRatio() > 1;
        long ageMin = Duration.between(position.openedAt(), s.time()).toMinutes();

        if (falseBreak) {
            boolean wrongSide = entryUp ? close < trigger : close > trigger;
            wrongSideCloses = wrongSide && volume <= median ? wrongSideCloses + 1 : 0;
            if (wrongSideCloses >= falseBreakCloses) {
                return "FALSE_BREAK";
            }
        }
        if (theta && ageMin >= thetaMinAgeMin && thetaWinning(s, position)) {
            return "THETA";
        }
        if (futOi) {
            String state = s.futures().oiState();
            boolean against = up ? "FRESH_SHORT".equals(state) || "LONG_UNWIND".equals(state)
                    : "FRESH_LONG".equals(state) || "SHORT_COVERING".equals(state);
            futOiAgainst = against ? futOiAgainst + 1 : 0;
        }
        if (!inProfit) {
            return null;
        }
        if (rejection) {
            String level = rejectedLevel(s, trigger, up, close);
            if (level != null) {
                return "REJECTED_" + level;
            }
        }
        if (climax && volume >= climaxVolumeX * median && median > 0 && high > low) {
            double location = (close - low) / (high - low);
            if (up ? location <= 1.0 / 3 : location >= 2.0 / 3) {
                return "CLIMAX";
            }
        }
        if (futOi && futOiAgainst >= futOiMinutes) {
            return "FUT_OI_AGAINST";
        }
        if (swingTrail) {
            double swing;
            if (entryPivots) {
                swing = lastPivot(up);
            } else {
                StructureFeatures st = s.structure();
                swing = up ? st.lastSwingLow() : st.lastSwingHigh();
                swing = up ? (swing > trigger ? swing : Double.NaN) : (swing < trigger ? swing : Double.NaN);
            }
            if (Double.isFinite(swing) && (up ? close < swing : close > swing)) {
                return "SWING_TRAIL";
            }
        }
        return null;
    }

    /** Whether realized movement is not paying for the options' time value (see {@code THETA}). */
    private boolean thetaWinning(FeatureSnapshot s, PositionView position) {
        double rv = realizedVol();
        if (!Double.isFinite(rv)) {
            return false;
        }
        if (!heldFairValue) {
            double iv = s.options().atmIv();
            return iv > 0 && rv < rvVsIvMin * iv;
        }
        double vol = Math.max(rv, 1e-4);                     // a flat index: the legs are worth their intrinsic value
        LocalDate expiry = s.options().expiry() == null ? null : LocalDate.parse(s.options().expiry());
        if (expiry == null || position.legs().isEmpty() || !(s.spot() > 0)) {
            return false;
        }
        Instant close = expiry.atTime(OPTIONS_CLOSE).atZone(MarketTime.IST).toInstant();
        double years = Math.max(60, Duration.between(s.time(), close).toSeconds()) / (CALENDAR_MINUTES_PER_YEAR * 60);
        double fair = 0;
        double paid = 0;
        for (PositionView.Leg leg : position.legs()) {
            if (!Double.isFinite(leg.strike()) || !Double.isFinite(leg.bid())) {
                return false;                                 // a leg we cannot value: no theta verdict
            }
            fair += leg.quantity() * BlackScholes.price(leg.side() == OptionSide.CE, s.spot(), leg.strike(), years, 0, vol);
            paid += leg.quantity() * leg.bid();
        }
        return paid > 0 && fair < rvVsIvMin * paid;
    }

    /**
     * The latest pivot low ({@code low} true) or high among the closed minutes since the entry: a minute whose low (high)
     * is under (over) the {@link #PIVOT_SPAN} minutes on each side of it; NaN when none has formed.
     */
    private double lastPivot(boolean low) {
        int k = low ? 1 : 0;
        for (int i = sinceEntry.size() - 1 - PIVOT_SPAN; i >= PIVOT_SPAN; i--) {
            double v = sinceEntry.get(i)[k];
            boolean pivot = true;
            for (int j = i - PIVOT_SPAN; j <= i + PIVOT_SPAN && pivot; j++) {
                if (j != i) {
                    double w = sinceEntry.get(j)[k];
                    pivot = low ? v < w : v > w;
                }
            }
            if (pivot) {
                return v;
            }
        }
        return Double.NaN;
    }

    /**
     * The farthest level beyond {@code trigger} in the move's direction that the index reached since the entry and has
     * just closed back across, if that side's near-ATM option OI is being built; else null.
     */
    private String rejectedLevel(FeatureSnapshot s, double trigger, boolean up, double close) {
        OptionsFeatures o = s.options();
        String flow = up ? o.ceOiFlow() : o.peOiFlow();
        if (flow == null || !BUILDING.contains(flow)) {
            return null;
        }
        StructureFeatures st = s.structure();
        String[] names = {"ORH", "ORL", "PDH", "PDL", "VWAP", "PWH", "PWL", "PMH", "PML", up ? "CALL_WALL" : "PUT_WALL"};
        double[] levels = {st.orComplete() ? st.orHigh() : Double.NaN, st.orComplete() ? st.orLow() : Double.NaN,
                st.pdh(), st.pdl(), st.vwapSpotProxy(), st.pwh(), st.pwl(), st.pmh(), st.pml(),
                up ? o.callBarrierStrike() : o.putSupportStrike()};
        double reached = up ? extremeHigh : extremeLow;
        String best = null;
        double bestLevel = Double.NaN;
        for (int i = 0; i < levels.length; i++) {
            double l = levels[i];
            boolean beyond = up ? l > trigger && l <= reached && close < l : l < trigger && l >= reached && close > l;
            if (Double.isFinite(l) && beyond && (best == null || (up ? l > bestLevel : l < bestLevel))) {
                best = names[i];
                bestLevel = l;
            }
        }
        double round = up ? Math.floor(reached / roundStep) * roundStep : Math.ceil(reached / roundStep) * roundStep;
        boolean roundBeyond = up ? round > trigger && close < round : round < trigger && close > round;
        if (roundBeyond && (best == null || (up ? round > bestLevel : round < bestLevel))) {
            best = "ROUND_" + Math.round(round);
        }
        return best;
    }

    /** Realized volatility of the last {@code rvMinutes} one-minute log returns, annualised on calendar minutes. */
    private double realizedVol() {
        if (closes.size() < rvMinutes + 1) {
            return Double.NaN;
        }
        List<Double> window = closes.subList(closes.size() - rvMinutes - 1, closes.size());
        double sum = 0;
        double sumSq = 0;
        for (int i = 1; i < window.size(); i++) {
            double a = window.get(i - 1), b = window.get(i);
            if (!(a > 0) || !(b > 0)) {
                return Double.NaN;
            }
            double r = Math.log(b / a);
            sum += r;
            sumSq += r * r;
        }
        int n = window.size() - 1;
        double variance = (sumSq - sum * sum / n) / (n - 1);
        return Math.sqrt(Math.max(0, variance) * CALENDAR_MINUTES_PER_YEAR);
    }

    /** The session's median one-minute futures volume so far. */
    private double median() {
        long[] v = volumes.stream().mapToLong(Long::longValue).toArray();
        if (v.length == 0) {
            return Double.NaN;
        }
        Arrays.sort(v);
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2.0;
    }
}
