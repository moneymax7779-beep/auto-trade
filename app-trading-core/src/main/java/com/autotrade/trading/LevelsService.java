package com.autotrade.trading;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.autotrade.core.time.MarketTime;

/**
 * Price levels for display and alert context, each with the minute it became knowable, so nothing is drawn
 * before it existed (no hindsight levels):
 * <ul>
 * <li>prior day: PDH, PDL, previous close, prior opening-range high / low (the live features at the open), and
 *     the 2-day high / low when both previous trading days are held — known from 09:15;</li>
 * <li>today: the day open (09:15), ORH / ORL (09:30);</li>
 * <li>lines: the spot VWAP (futures VWAP minus the 15-minute mean basis), and a 20-period EMA of minute closes;</li>
 * <li>tested zones: swing highs (lows) on minute bars, confirmed 3 minutes later, clustered within a tolerance;
 *     a cluster is a zone from its second touch's confirmation, and ends at a minute close beyond it;</li>
 * <li>period: the previous ISO week's and the previous calendar month's high / low (PWH, PWL, PMH, PML; features
 *     v12 at the open, else the stored one-minute bars), known from 09:15.</li>
 * </ul>
 * Days without the own tick capture fall back to the stored one-minute bars (hist.candle) for the chart and the
 * nearest futures contract's VWAP minus its trailing mean basis; {@code source} says which was used. Days without
 * live features (replays, bar and archive days) take the prior-day and period levels from the previous sessions in
 * the own capture, else the bars, and only from the actual previous trading day (exchange calendar): bars that end
 * before the own capture begins would otherwise put a weeks-old session on the chart (24 Oct, session 14723).
 * Context for a human, not a strategy input: zone-based rules showed no edge (A-017, A-018, A-025).
 */
@Component
class LevelsService {

    static final double TOLERANCE = 0.0004;          // 0.04 % of price: about 9 NIFTY / 29 SENSEX points
    static final int PIVOT = 3;                      // a swing needs 3 minutes either side
    static final int TOUCH_GAP_MIN = 5;              // touches closer than this are one touch
    static final int BASIS_WINDOW = 15;              // minutes of basis averaged for the chart's VWAP
    static final LocalTime ZONES_UNTIL = LocalTime.of(15, 15);   // no new zones in the closing minutes
    static final LocalTime PDH_PDL_UNTIL = LocalTime.of(15, 15); // continuous trading only, as structure.pdh_pdl_until
    static final LocalTime OR_UNTIL = LocalTime.of(9, 30);      // the 15-minute opening range
    static final int MAX_GAP_DAYS = 10;                          // never look further back for the previous session
    private static final String IST = "Asia/Kolkata";

    record Bar(LocalTime t, double open, double high, double low, double close) {
    }

    record Level(String name, String group, double price, String from) {
    }

    record Zone(String kind, double lo, double hi, int touches, String from, String until) {
    }

    private record Cached(long at, Map<String, Object> value) {
    }

    private record CachedPrior(long at, List<DailyRange> value) {
    }

    private final JdbcTemplate jdbc;
    private final MarketHours hours;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, CachedPrior> priorCache = new ConcurrentHashMap<>();

    LevelsService(DataSource target, MarketHours hours) {
        this.jdbc = new JdbcTemplate(target);
        this.hours = hours;
    }

    /** The day's levels; a finished day is computed once, today at most every 30 seconds. */
    Map<String, Object> levels(LocalDate day, String underlying) {
        String key = day + "|" + underlying;
        boolean today = day.equals(LocalDate.now(MarketTime.IST));
        Cached hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && (!today || now - hit.at() < 30_000)) {
            return hit.value();
        }
        Map<String, Object> value = compute(day, underlying);
        cache.put(key, new Cached(now, value));
        return value;
    }

    private Map<String, Object> compute(LocalDate day, String underlying) {
        List<Bar> bars = bars(day, underlying);
        boolean fromBars = bars.isEmpty();
        if (fromBars) {
            bars = candleBars(day, underlying);
        }
        List<Level> levels = new ArrayList<>();
        Map<String, Double> open = openingFeatures(day, underlying);
        List<DailyRange> prior = priorSessions(day, underlying, open.get("structure.pwh") != null);
        DailyRange previous = previousSession(day, prior, hours::knownTradingDay);
        if (open.get("structure.pdh") == null && previous != null) {
            open.put("structure.pdh", previous.high());
            open.put("structure.pdl", previous.low());
            open.put("structure.prevClose", previous.close());
            open.put("structure.prevOrHigh", previous.orHigh());
            open.put("structure.prevOrLow", previous.orLow());
        }
        add(levels, "PDH", "prior", open.get("structure.pdh"), "09:15");
        add(levels, "PDL", "prior", open.get("structure.pdl"), "09:15");
        add(levels, "Prev close", "prior", open.get("structure.prevClose"), "09:15");
        add(levels, "Prior ORH", "prior", open.get("structure.prevOrHigh"), "09:15");
        add(levels, "Prior ORL", "prior", open.get("structure.prevOrLow"), "09:15");
        twoDay(prior, previous, open.get("structure.pdh"), open.get("structure.pdl"), hours::knownTradingDay, levels);
        periodLevels(day, open, prior, hours::knownTradingDay, levels);
        if (!bars.isEmpty()) {
            add(levels, "Day open", "today", bars.getFirst().open(), bars.getFirst().t().toString());
        }
        double orh = Double.NaN, orl = Double.NaN;
        for (Bar b : bars) {
            if (b.t().isBefore(LocalTime.of(9, 30))) {
                orh = Double.isNaN(orh) ? b.high() : Math.max(orh, b.high());
                orl = Double.isNaN(orl) ? b.low() : Math.min(orl, b.low());
            }
        }
        if (bars.stream().anyMatch(b -> !b.t().isBefore(LocalTime.of(9, 30)))) {
            add(levels, "ORH", "today", orh, "09:30");
            add(levels, "ORL", "today", orl, "09:30");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("underlying", underlying);
        out.put("levels", levels);
        out.put("source", fromBars ? "bars" : "ticks");
        Map<String, Object> lines = new LinkedHashMap<>();
        List<Object> vwap = vwap(day, underlying);
        lines.put("vwap", vwap.isEmpty() ? candleVwap(day, underlying) : vwap);
        lines.put("ema20", ema(bars, 20));
        out.put("lines", lines);
        out.put("zones", zones(bars));
        out.put("tolerancePct", TOLERANCE * 100);
        return out;
    }

    // ------------------------------------------------------------------------------------------ inputs

    private List<Bar> bars(LocalDate day, String underlying) {
        Instant from = day.atTime(9, 15).atZone(MarketTime.IST).toInstant();
        Instant to = day.atTime(15, 30).atZone(MarketTime.IST).toInstant();
        return jdbc.query("select (date_trunc('minute', recv_ts) at time zone '" + IST + "')::time m, "
                        + "(array_agg(price order by recv_ts, src_seq))[1] o, max(price) h, min(price) l, "
                        + "(array_agg(price order by recv_ts desc, src_seq desc))[1] c from md.index_tick "
                        + "where underlying = ? and recv_ts >= ? and recv_ts < ? group by 1 order by 1",
                (rs, i) -> new Bar(rs.getObject(1, LocalTime.class), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5)),
                underlying, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
    }

    /** Index minute bars from the stored one-minute history (bar days, archive days): 09:15-15:29. */
    private List<Bar> candleBars(LocalDate day, String underlying) {
        return jdbc.query("select (bar_start at time zone '" + IST + "')::time, open, high, low, close from hist.candle "
                        + "where underlying = ? and kind = 'INDEX' and bar_start >= ? and bar_start < ? order by bar_start",
                (rs, i) -> new Bar(rs.getObject(1, LocalTime.class), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5)),
                underlying, ts(day, LocalTime.of(9, 15)), ts(day, LocalTime.of(15, 30)));
    }

    /** A previous session's range (high / low to 15:15, close, opening range), from the own capture or the bars. */
    record DailyRange(LocalDate day, double high, double low, double close, double orHigh, double orLow) {
    }

    /**
     * Sessions before {@code day}, newest first: the own tick capture (md.index_tick) and the stored one-minute bars
     * (hist.candle), the own capture on a date both hold. Back to the start of the previous calendar month when the
     * period levels need them, else two weeks. Cached for 10 minutes: earlier sessions do not change during the day.
     */
    private List<DailyRange> priorSessions(LocalDate day, String underlying, boolean periodFromFeatures) {
        String key = day + "|" + underlying + "|" + periodFromFeatures;
        CachedPrior hit = priorCache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at() < 600_000) {
            return hit.value();
        }
        LocalDate weekStart = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate from = periodFromFeatures ? day.minusWeeks(2)
                : java.util.Collections.min(List.of(day.withDayOfMonth(1).minusMonths(1), weekStart.minusWeeks(1)));
        List<DailyRange> value = merge(ownSessions(day, underlying, from), candleSessions(day, underlying, from));
        priorCache.put(key, new CachedPrior(now, value));
        return value;
    }

    /** Own-capture sessions in [from, day): high / low before 15:15, the last tick as the close (the official close). */
    private List<DailyRange> ownSessions(LocalDate day, String underlying, LocalDate from) {
        String time = "(t.recv_ts at time zone '" + IST + "')::time";
        return jdbc.query("select t.session_date, max(t.price) filter (where " + time + " >= '09:15' and " + time + " < ?), "
                        + "min(t.price) filter (where " + time + " >= '09:15' and " + time + " < ?), "
                        + "(array_agg(t.price order by t.recv_ts desc, t.src_seq desc))[1], "
                        + "max(t.price) filter (where " + time + " >= '09:15' and " + time + " < ?), "
                        + "min(t.price) filter (where " + time + " >= '09:15' and " + time + " < ?) "
                        + "from md.index_tick t join md.load_manifest l on l.id = t.manifest_id and l.status = 'ACTIVE' "
                        + "where t.underlying = ? and t.recv_ts >= ? and t.recv_ts < ? and t.session_date < ? "
                        + "group by 1 having count(*) filter (where " + time + " >= '09:15' and " + time + " < ?) > 0 "
                        + "order by 1 desc",
                (rs, i) -> new DailyRange(rs.getObject(1, LocalDate.class), rs.getDouble(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)),
                PDH_PDL_UNTIL, PDH_PDL_UNTIL, OR_UNTIL, OR_UNTIL, underlying, ts(from, LocalTime.MIN),
                ts(day, LocalTime.MIN), day, PDH_PDL_UNTIL);
    }

    /** Bar sessions in [from, day): high / low of the bars before 15:15, the 15:29 bar's close. */
    private List<DailyRange> candleSessions(LocalDate day, String underlying, LocalDate from) {
        String time = "(bar_start at time zone '" + IST + "')::time";
        return jdbc.query("select (bar_start at time zone '" + IST + "')::date d, max(high) filter (where " + time + " < ?), "
                        + "min(low) filter (where " + time + " < ?), (array_agg(close order by bar_start desc))[1], "
                        + "max(high) filter (where " + time + " < ?), min(low) filter (where " + time + " < ?) "
                        + "from hist.candle where underlying = ? and kind = 'INDEX' and bar_start >= ? and bar_start < ? "
                        + "and " + time + " between '09:15' and '15:29' group by 1 "
                        + "having count(*) filter (where " + time + " < ?) > 0 order by 1 desc",
                (rs, i) -> new DailyRange(rs.getObject(1, LocalDate.class), rs.getDouble(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)),
                PDH_PDL_UNTIL, PDH_PDL_UNTIL, OR_UNTIL, OR_UNTIL, underlying, ts(from, LocalTime.MIN),
                ts(day, LocalTime.MIN), PDH_PDL_UNTIL);
    }

    /** Both sources' sessions, newest first; the own capture on a date both hold (it is what live features read). */
    static List<DailyRange> merge(List<DailyRange> own, List<DailyRange> bars) {
        java.util.TreeMap<LocalDate, DailyRange> byDay = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        bars.forEach(r -> byDay.put(r.day(), r));
        own.forEach(r -> byDay.put(r.day(), r));
        return List.copyOf(byDay.values());
    }

    /**
     * The newest session before {@code day} if it is the actual previous trading day: no known trading day lies
     * between them (a gap in both sources, as when the bars end weeks before the own capture holds the day), and it
     * is at most {@link #MAX_GAP_DAYS} back. Null otherwise: no line beats a stale line.
     */
    static DailyRange previousSession(LocalDate day, List<DailyRange> prior, Predicate<LocalDate> knownTradingDay) {
        if (prior.isEmpty() || !adjacent(prior.getFirst().day(), day, knownTradingDay)) {
            return null;
        }
        return prior.getFirst();
    }

    /** No known trading day strictly between {@code earlier} and {@code later}, and within {@link #MAX_GAP_DAYS}. */
    static boolean adjacent(LocalDate earlier, LocalDate later, Predicate<LocalDate> knownTradingDay) {
        if (!earlier.isBefore(later) || earlier.plusDays(MAX_GAP_DAYS).isBefore(later)) {
            return false;
        }
        for (LocalDate d = earlier.plusDays(1); d.isBefore(later); d = d.plusDays(1)) {
            if (knownTradingDay.test(d)) {
                return false;
            }
        }
        return true;
    }

    /**
     * PWH / PWL / PMH / PML: the live features' values at the open (v12), else the previous ISO week (Monday-Friday
     * before this week) and previous calendar month from the prior sessions, as {@code PeriodLevels} defines them.
     * A period with a known trading day missing from the sessions is left out rather than drawn from part of it.
     */
    static void periodLevels(LocalDate day, Map<String, Double> open, List<DailyRange> prior,
                             Predicate<LocalDate> knownTradingDay, List<Level> levels) {
        Double pwh = open.get("structure.pwh"), pwl = open.get("structure.pwl");
        Double pmh = open.get("structure.pmh"), pml = open.get("structure.pml");
        if (pwh == null && pmh == null) {
            LocalDate weekStart = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate monthStart = day.withDayOfMonth(1);
            for (DailyRange r : prior) {
                if (!r.day().isBefore(weekStart.minusWeeks(1)) && r.day().isBefore(weekStart)) {
                    pwh = pwh == null ? r.high() : Math.max(pwh, r.high());
                    pwl = pwl == null ? r.low() : Math.min(pwl, r.low());
                }
                if (!r.day().isBefore(monthStart.minusMonths(1)) && r.day().isBefore(monthStart)) {
                    pmh = pmh == null ? r.high() : Math.max(pmh, r.high());
                    pml = pml == null ? r.low() : Math.min(pml, r.low());
                }
            }
            if (!complete(weekStart.minusWeeks(1), weekStart, prior, knownTradingDay)) {
                pwh = pwl = null;
            }
            if (!complete(monthStart.minusMonths(1), monthStart, prior, knownTradingDay)) {
                pmh = pml = null;
            }
        }
        add(levels, "PWH", "period", pwh, "09:15");
        add(levels, "PWL", "period", pwl, "09:15");
        add(levels, "PMH", "period", pmh, "09:15");
        add(levels, "PML", "period", pml, "09:15");
    }

    /** The first live feature snapshot of the day: previous-session levels as the strategies saw them. */
    private Map<String, Double> openingFeatures(LocalDate day, String underlying) {
        Map<String, Double> out = new LinkedHashMap<>();
        String[] keys = {"structure.pdh", "structure.pdl", "structure.prevClose", "structure.prevOrHigh",
                "structure.prevOrLow", "structure.pwh", "structure.pwl", "structure.pmh", "structure.pml"};
        StringBuilder cols = new StringBuilder();
        for (String k : keys) {
            cols.append(cols.isEmpty() ? "" : ", ").append("features->>'").append(k).append("'");
        }
        jdbc.query("select " + cols + " from feat.snapshot s "
                        + "join research.run r on r.id = s.run_id where r.kind = 'LIVE_FEATURES' and s.session_date = ? "
                        + "and s.underlying = ? order by s.run_id desc, s.snap_time limit 1",
                rs -> {
                    for (int i = 0; i < keys.length; i++) {
                        Double v = parse(rs.getString(i + 1));
                        if (v != null && Double.isFinite(v)) {
                            out.put(keys[i], v);
                        }
                    }
                }, day, underlying);
        return out;
    }

    /** Every known trading day in [from, until) has a session in {@code prior}. */
    static boolean complete(LocalDate from, LocalDate until, List<DailyRange> prior, Predicate<LocalDate> knownTradingDay) {
        java.util.Set<LocalDate> have = new java.util.HashSet<>();
        prior.forEach(r -> have.add(r.day()));
        for (LocalDate d = from; d.isBefore(until); d = d.plusDays(1)) {
            if (knownTradingDay.test(d) && !have.contains(d)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The 2-day high / low: the previous session's high / low (as the features saw them) with the session before it,
     * only when {@code previous} is the actual previous trading day and agrees with the features' PDH / PDL, and the
     * session before it is that day's actual previous trading day.
     */
    static void twoDay(List<DailyRange> prior, DailyRange previous, Double pdh, Double pdl,
                       Predicate<LocalDate> knownTradingDay, List<Level> levels) {
        if (pdh == null || pdl == null || previous == null || prior.size() < 2
                || Math.abs(previous.high() - pdh) > 1 || Math.abs(previous.low() - pdl) > 1) {
            return;
        }
        DailyRange before = prior.get(1);
        if (!adjacent(before.day(), previous.day(), knownTradingDay)) {
            return;
        }
        add(levels, "2-day high", "prior", Math.max(pdh, before.high()), "09:15");
        add(levels, "2-day low", "prior", Math.min(pdl, before.low()), "09:15");
    }

    /**
     * The spot VWAP for the chart. The feature's proxy is the futures VWAP minus the latest tick basis,
     * and that basis jumps (SENSEX futures trade thinly: about 18 points a minute, median), so the chart
     * subtracts the basis averaged over the last {@link #BASIS_WINDOW} minutes instead. Strategies keep
     * reading the feature unchanged.
     */
    private List<Object> vwap(LocalDate day, String underlying) {
        List<String> times = new ArrayList<>();
        List<Double> proxy = new ArrayList<>();
        List<Double> basis = new ArrayList<>();
        jdbc.query("select to_char(s.snap_time at time zone '" + IST + "', 'HH24:MI'), "
                        + "s.features->>'structure.vwapSpotProxy', s.features->>'futures.basis' "
                        + "from feat.snapshot s join research.run r on r.id = s.run_id "
                        + "where r.kind = 'LIVE_FEATURES' and s.session_date = ? and s.underlying = ? "
                        + "and s.run_id = (select max(s2.run_id) from feat.snapshot s2 join research.run r2 on r2.id = s2.run_id "
                        + "where r2.kind = 'LIVE_FEATURES' and s2.session_date = ? and s2.underlying = ?) order by s.snap_time",
                rs -> {
                    times.add(rs.getString(1));
                    proxy.add(parse(rs.getString(2)));
                    basis.add(parse(rs.getString(3)));
                }, day, underlying, day, underlying);
        return smoothVwap(times, proxy, basis, BASIS_WINDOW);
    }

    /**
     * The chart VWAP from the stored one-minute bars: the nearest futures contract's cumulative VWAP (typical price x
     * volume) minus the futures-index basis averaged over the trailing {@link #BASIS_WINDOW} minutes.
     */
    private List<Object> candleVwap(LocalDate day, String underlying) {
        List<String> times = new ArrayList<>();
        List<Double> proxy = new ArrayList<>();
        List<Double> basis = new ArrayList<>();
        double[] pv = {0, 0};
        jdbc.query("with near as (select min(expiry) e from hist.candle where underlying = ? and kind = 'FUTURE' "
                        + "and expiry >= ? and bar_start >= ? and bar_start < ?) "
                        + "select to_char(f.bar_start at time zone '" + IST + "', 'HH24:MI'), f.high, f.low, f.close, "
                        + "coalesce(f.volume, 0), i.close from hist.candle f left join hist.candle i on i.underlying = f.underlying "
                        + "and i.kind = 'INDEX' and i.bar_start = f.bar_start where f.underlying = ? and f.kind = 'FUTURE' "
                        + "and f.expiry = (select e from near) and f.bar_start >= ? and f.bar_start < ? order by f.bar_start",
                rs -> {
                    double typical = (rs.getDouble(2) + rs.getDouble(3) + rs.getDouble(4)) / 3;
                    long volume = rs.getLong(5);
                    pv[0] += typical * volume;
                    pv[1] += volume;
                    double spot = rs.getDouble(6);
                    if (rs.wasNull() || pv[1] == 0) {
                        return;
                    }
                    double b = rs.getDouble(4) - spot;
                    times.add(rs.getString(1));
                    proxy.add(pv[0] / pv[1] - b);
                    basis.add(b);
                }, underlying, day, ts(day, LocalTime.of(9, 15)), ts(day, LocalTime.of(15, 30)),
                underlying, ts(day, LocalTime.of(9, 15)), ts(day, LocalTime.of(15, 30)));
        return smoothVwap(times, proxy, basis, BASIS_WINDOW);
    }

    /** Futures VWAP (proxy + its basis) minus the trailing mean basis; causal: only minutes up to t. */
    static List<Object> smoothVwap(List<String> times, List<Double> proxy, List<Double> basis, int window) {
        List<Object> out = new ArrayList<>();
        java.util.ArrayDeque<Double> recent = new java.util.ArrayDeque<>();
        double sum = 0;
        for (int i = 0; i < times.size(); i++) {
            Double p = proxy.get(i), b = basis.get(i);
            if (b != null) {
                recent.addLast(b);
                sum += b;
                if (recent.size() > window) {
                    sum -= recent.removeFirst();
                }
            }
            if (p == null) {
                continue;
            }
            double v = b == null || recent.isEmpty() ? p : p + b - sum / recent.size();
            out.add(List.of(times.get(i), round(v)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ derived

    static List<Object> ema(List<Bar> bars, int period) {
        List<Object> out = new ArrayList<>();
        double k = 2.0 / (period + 1);
        Double e = null;
        int n = 0;
        for (Bar b : bars) {
            e = e == null ? b.close() : e + (b.close() - e) * k;
            if (++n >= period) {                         // shown once it has seen a full period
                out.add(List.of(b.t().toString(), round(e)));
            }
        }
        return out;
    }

    /** A cluster of swing pivots of one kind (resistance: highs, support: lows). */
    private static final class Cluster {
        final String kind;
        final List<Double> prices = new ArrayList<>();
        final List<LocalTime> touches = new ArrayList<>();
        LocalTime formed;                // confirmation of the second touch: the zone exists from here
        LocalTime broken;                // a minute close beyond it: the zone ends here

        Cluster(String kind, double price, LocalTime at) {
            this.kind = kind;
            prices.add(price);
            touches.add(at);
        }

        double mean() {
            return prices.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        }

        double lo() {
            return prices.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
        }

        double hi() {
            return prices.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        }
    }

    /** Tested zones from swing pivots on minute bars, each from the minute it became knowable. */
    static List<Zone> zones(List<Bar> bars) {
        List<Cluster> clusters = new ArrayList<>();
        for (int i = PIVOT; i < bars.size(); i++) {
            Bar now = bars.get(i);
            int p = i - PIVOT;                           // a pivot at p is confirmed at the close of bar i
            if (p >= PIVOT) {
                Bar pivot = bars.get(p);
                boolean high = true, low = true;
                for (int j = p - PIVOT; j <= p + PIVOT; j++) {
                    if (j < p) {                         // strictly beyond the bars before it: no flat-tape pivots
                        high &= pivot.high() > bars.get(j).high();
                        low &= pivot.low() < bars.get(j).low();
                    } else if (j > p) {
                        high &= pivot.high() >= bars.get(j).high();
                        low &= pivot.low() <= bars.get(j).low();
                    }
                }
                high &= pivot.t().isBefore(ZONES_UNTIL);
                low &= pivot.t().isBefore(ZONES_UNTIL);
                if (high) {
                    touch(clusters, "resistance", pivot.high(), pivot.t(), now.t());
                }
                if (low) {
                    touch(clusters, "support", pivot.low(), pivot.t(), now.t());
                }
            }
            for (Cluster c : clusters) {                 // a formed zone ends at a minute close beyond it
                if (c.formed == null || c.broken != null || !now.t().isAfter(c.formed)) {
                    continue;
                }
                double tol = TOLERANCE * now.close();
                if (c.kind.equals("resistance") ? now.close() > c.hi() + tol : now.close() < c.lo() - tol) {
                    c.broken = now.t();
                }
            }
        }
        List<Zone> out = new ArrayList<>();
        for (Cluster c : clusters) {
            if (c.formed != null) {
                out.add(new Zone(c.kind, round(c.lo()), round(c.hi()), c.touches.size(), c.formed.toString(),
                        c.broken == null ? null : c.broken.toString()));
            }
        }
        return out;
    }

    private static void touch(List<Cluster> clusters, String kind, double price, LocalTime pivotAt, LocalTime confirmedAt) {
        double tol = TOLERANCE * price;
        for (Cluster c : clusters) {
            if (c.kind.equals(kind) && c.broken == null && Math.abs(price - c.mean()) <= tol) {
                if (!pivotAt.isBefore(c.touches.getLast().plusMinutes(TOUCH_GAP_MIN))) {
                    c.prices.add(price);
                    c.touches.add(pivotAt);
                    if (c.formed == null) {
                        c.formed = confirmedAt;
                    }
                }
                return;
            }
        }
        clusters.add(new Cluster(kind, price, pivotAt));
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static void add(List<Level> levels, String name, String group, Double price, String from) {
        if (price != null && Double.isFinite(price) && price > 0) {
            levels.add(new Level(name, group, round(price), from));
        }
    }

    private static java.sql.Timestamp ts(LocalDate day, LocalTime time) {
        return java.sql.Timestamp.from(day.atTime(time).atZone(MarketTime.IST).toInstant());
    }

    private static Double parse(String s) {
        try {
            return s == null ? null : Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
