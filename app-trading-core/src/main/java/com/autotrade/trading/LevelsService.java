package com.autotrade.trading;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.autotrade.core.time.MarketTime;

/**
 * Price levels for display and alert context, each with the minute it became knowable, so nothing is drawn
 * before it existed (no hindsight levels):
 * <ul>
 * <li>prior day: PDH, PDL, previous close, prior opening-range high / low (the live features at the open), and
 *     the 2-day high / low when both previous sessions are in the own capture — known from 09:15;</li>
 * <li>today: the day open (09:15), ORH / ORL (09:30);</li>
 * <li>lines: the session VWAP proxy the strategies use, and a 20-period EMA of minute closes;</li>
 * <li>tested zones: swing highs (lows) on minute bars, confirmed 3 minutes later, clustered within a tolerance;
 *     a cluster is a zone from its second touch's confirmation, and ends at a minute close beyond it.</li>
 * </ul>
 * Context for a human, not a strategy input: zone-based rules showed no edge (A-017, A-018, A-025).
 */
@Component
class LevelsService {

    static final double TOLERANCE = 0.0004;          // 0.04 % of price: about 9 NIFTY / 29 SENSEX points
    static final int PIVOT = 3;                      // a swing needs 3 minutes either side
    static final int TOUCH_GAP_MIN = 5;              // touches closer than this are one touch
    static final int BASIS_WINDOW = 15;              // minutes of basis averaged for the chart's VWAP
    static final LocalTime ZONES_UNTIL = LocalTime.of(15, 15);   // no new zones in the closing minutes
    private static final String IST = "Asia/Kolkata";

    record Bar(LocalTime t, double open, double high, double low, double close) {
    }

    record Level(String name, String group, double price, String from) {
    }

    record Zone(String kind, double lo, double hi, int touches, String from, String until) {
    }

    private record Cached(long at, Map<String, Object> value) {
    }

    private final JdbcTemplate jdbc;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    LevelsService(DataSource target) {
        this.jdbc = new JdbcTemplate(target);
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
        List<Level> levels = new ArrayList<>();
        Map<String, Double> open = openingFeatures(day, underlying);
        add(levels, "PDH", "prior", open.get("structure.pdh"), "09:15");
        add(levels, "PDL", "prior", open.get("structure.pdl"), "09:15");
        add(levels, "Prev close", "prior", open.get("structure.prevClose"), "09:15");
        add(levels, "Prior ORH", "prior", open.get("structure.prevOrHigh"), "09:15");
        add(levels, "Prior ORL", "prior", open.get("structure.prevOrLow"), "09:15");
        twoDay(day, underlying, open.get("structure.pdh"), open.get("structure.pdl"), levels);
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
        Map<String, Object> lines = new LinkedHashMap<>();
        lines.put("vwap", vwap(day, underlying));
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

    /** The first live feature snapshot of the day: previous-session levels as the strategies saw them. */
    private Map<String, Double> openingFeatures(LocalDate day, String underlying) {
        Map<String, Double> out = new LinkedHashMap<>();
        jdbc.query("select features->>'structure.pdh', features->>'structure.pdl', features->>'structure.prevClose', "
                        + "features->>'structure.prevOrHigh', features->>'structure.prevOrLow' from feat.snapshot s "
                        + "join research.run r on r.id = s.run_id where r.kind = 'LIVE_FEATURES' and s.session_date = ? "
                        + "and s.underlying = ? order by s.run_id desc, s.snap_time limit 1",
                rs -> {
                    String[] keys = {"structure.pdh", "structure.pdl", "structure.prevClose", "structure.prevOrHigh",
                            "structure.prevOrLow"};
                    for (int i = 0; i < keys.length; i++) {
                        out.put(keys[i], parse(rs.getString(i + 1)));
                    }
                }, day, underlying);
        return out;
    }

    /**
     * The 2-day high / low: the previous session's high / low (as the features saw them) with the session before
     * it, only when the own capture holds both and its previous session agrees with the features' PDH / PDL.
     */
    private void twoDay(LocalDate day, String underlying, Double pdh, Double pdl, List<Level> levels) {
        if (pdh == null || pdl == null) {
            return;
        }
        List<double[]> prior = jdbc.query("select max(price), min(price) from md.index_tick where underlying = ? "
                        + "and session_date < ? and (recv_ts at time zone '" + IST + "')::time between '09:15' and '15:30' "
                        + "group by session_date order by session_date desc limit 2",
                (rs, i) -> new double[] {rs.getDouble(1), rs.getDouble(2)}, underlying, day);
        if (prior.size() < 2 || Math.abs(prior.get(0)[0] - pdh) > 1 || Math.abs(prior.get(0)[1] - pdl) > 1) {
            return;
        }
        add(levels, "2-day high", "prior", Math.max(pdh, prior.get(1)[0]), "09:15");
        add(levels, "2-day low", "prior", Math.min(pdl, prior.get(1)[1]), "09:15");
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
