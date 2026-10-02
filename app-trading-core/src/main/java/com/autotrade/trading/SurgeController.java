package com.autotrade.trading;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autotrade.core.time.MarketTime;
import com.autotrade.md.zt.ZtReadOnlyDataSource;
import com.autotrade.md.zt.ZtSessionHistory;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Futures-volume surges with the open-interest flow around each, for the Live page (display only;
 * no strategy reads it). A surge is a minute whose futures volume is at least 5 × the median of the
 * same minute over the previous 10 sessions (zt-tiger-v2's volume history, read-only). Prices, OI and
 * quotes come from this service's own capture (md.*), last tick of each minute; each flow compares
 * minute t − 1 with minute t + 1, so a surge is shown once minute t + 1 has closed.
 */
@RestController
@RequestMapping("/api")
class SurgeController {

    private static final Logger log = LoggerFactory.getLogger(SurgeController.class);
    private static final double SURGE_MIN = 5.0;
    private static final int HISTORY_SESSIONS = 10;
    private static final int HISTORY_MIN = 5;
    private static final LocalTime FIRST = LocalTime.of(9, 15);
    private static final LocalTime LAST = LocalTime.of(15, 29);
    private static final String IST = "Asia/Kolkata";

    private record Cached(long at, Map<String, Object> value) {
    }

    private record Quote(double bid, double ask, double oi) {
        double mid() {
            return (bid + ask) / 2;
        }
    }

    private final JdbcTemplate jdbc;
    private final TradingProperties properties;
    private final Map<String, Map<LocalTime, Double>> medians = new ConcurrentHashMap<>();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    /** A surge's flow is fixed once minute t + 1 has closed: computed once per day, underlying and minute. */
    private final Map<String, Map<String, Object>> flows = new ConcurrentHashMap<>();
    private volatile HikariDataSource zt;

    SurgeController(DataSource dataSource, TradingProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    @GetMapping("/surges")
    Map<String, Object> surges(@RequestParam(required = false) String date) {
        LocalDate day = date != null ? LocalDate.parse(date) : latestDay();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", day == null ? null : day.toString());
        List<Object> underlyings = new ArrayList<>();
        if (day != null) {
            for (String underlying : properties.trading().tradeUnderlyingList()) {
                underlyings.add(cached(day, underlying));
            }
        }
        result.put("underlyings", underlyings);
        result.put("rule", Map.of("surgeMin", SURGE_MIN, "historySessions", HISTORY_SESSIONS,
                "futuresOiMinPct", SurgeFlow.FUTURES_OI_MIN * 100, "optionsOiMinPct", SurgeFlow.OPTIONS_OI_MIN * 100));
        return result;
    }

    private LocalDate latestDay() {
        List<LocalDate> days = jdbc.query("select session_date from md.index_tick order by recv_ts desc limit 1",
                (rs, i) -> rs.getObject(1, LocalDate.class));
        return days.isEmpty() ? null : days.getFirst();
    }

    /** A finished day is computed once; today is recomputed at most every 20 seconds. */
    private Map<String, Object> cached(LocalDate day, String underlying) {
        String key = day + "|" + underlying;
        boolean today = day.equals(LocalDate.now(MarketTime.IST));
        Cached hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && (!today || now - hit.at() < 20_000)) {
            return hit.value();
        }
        Map<String, Object> value = compute(day, underlying, today);
        cache.put(key, new Cached(now, value));
        return value;
    }

    private Map<String, Object> compute(LocalDate day, String underlying, boolean today) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("underlying", underlying);
        Instant from = day.atTime(9, 14).atZone(MarketTime.IST).toInstant();
        Instant to = day.atTime(15, 31).atZone(MarketTime.IST).toInstant();
        double step = underlying.equals("NIFTY") ? 50 : 100;

        Map<LocalTime, Double> index = new TreeMap<>();
        jdbc.query("select (date_trunc('minute', recv_ts) at time zone '" + IST + "')::time m, "
                + "(array_agg(price order by recv_ts desc, src_seq desc))[1] from md.index_tick "
                + "where underlying = ? and recv_ts >= ? and recv_ts < ? group by 1 order by 1",
                rs -> { index.put(rs.getObject(1, LocalTime.class), rs.getDouble(2)); },
                underlying, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        List<Object> series = new ArrayList<>();
        index.forEach((t, p) -> {
            if (!t.isBefore(FIRST) && !t.isAfter(LocalTime.of(15, 30))) {
                series.add(List.of(t.toString(), p));
            }
        });
        out.put("index", series);

        // the nearest-expiry future, last price / OI / cumulative volume per minute
        List<LocalDate> expiries = jdbc.query("select min(expiry) from md.future_tick where underlying = ? "
                + "and recv_ts >= ? and recv_ts < ? and expiry >= ?", (rs, i) -> rs.getObject(1, LocalDate.class),
                underlying, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), day);
        LocalDate futureExpiry = expiries.isEmpty() ? null : expiries.getFirst();
        Map<LocalTime, double[]> futures = new TreeMap<>();
        if (futureExpiry != null) {
            jdbc.query("select (date_trunc('minute', recv_ts) at time zone '" + IST + "')::time m, "
                    + "(array_agg(price order by recv_ts desc, src_seq desc))[1], (array_agg(oi order by recv_ts desc, src_seq desc))[1], "
                    + "(array_agg(volume order by recv_ts desc, src_seq desc))[1] from md.future_tick "
                    + "where underlying = ? and expiry = ? and recv_ts >= ? and recv_ts < ? group by 1 order by 1",
                    rs -> { futures.put(rs.getObject(1, LocalTime.class), new double[] {rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)}); },
                    underlying, futureExpiry, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        }
        Map<LocalTime, Double> volume = new TreeMap<>();
        double previous = 0;
        for (Map.Entry<LocalTime, double[]> e : futures.entrySet()) {
            volume.put(e.getKey(), e.getValue()[2] - previous);
            previous = e.getValue()[2];
        }
        Map<LocalTime, Double> median = median(day, underlying);
        out.put("historyAvailable", !median.isEmpty());
        // futures volume per minute with the same minute's normal (median of the previous sessions), for the volume pane
        List<Object> volumes = new ArrayList<>();
        volume.forEach((t, v) -> {
            if (!t.isBefore(FIRST) && !t.isAfter(LAST)) {
                Double normal = median.get(t);
                List<Object> row = new ArrayList<>(3);
                row.add(t.toString());
                row.add(Math.round(Math.max(0, v)));
                row.add(normal == null || !(normal > 0) ? null : Math.round(normal));
                volumes.add(row);
            }
        });
        out.put("volume", volumes);

        List<LocalDate> optionExpiries = jdbc.query("select min(expiry) from md.option_tick where underlying = ? "
                + "and recv_ts >= ? and recv_ts < ? and expiry >= ?", (rs, i) -> rs.getObject(1, LocalDate.class),
                underlying, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), day);
        LocalDate optionExpiry = optionExpiries.isEmpty() ? null : optionExpiries.getFirst();
        out.put("futuresExpiry", futureExpiry == null ? null : futureExpiry.toString());
        out.put("optionsExpiry", optionExpiry == null ? null : optionExpiry.toString());

        LocalTime nowIst = LocalTime.now(MarketTime.IST);
        List<Object> surges = new ArrayList<>();
        for (Map.Entry<LocalTime, Double> e : volume.entrySet()) {
            LocalTime t = e.getKey();
            Double normal = median.get(t);
            if (t.isBefore(FIRST) || t.isAfter(LAST) || normal == null || !(normal > 0) || e.getValue() / normal < SURGE_MIN) {
                continue;
            }
            LocalTime before = t.minusMinutes(1);
            LocalTime after = t.plusMinutes(1);
            if (today && nowIst.isBefore(t.plusMinutes(2))) {
                continue;                                      // minute t + 1 has not closed yet
            }
            String flowKey = day + "|" + underlying + "|" + t;
            Map<String, Object> fixed = flows.get(flowKey);
            if (fixed != null) {
                Map<String, Object> s = new LinkedHashMap<>(fixed);
                outcome(s, index, t);
                surges.add(s);
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("t", t.toString());
            s.put("vol", Math.round(e.getValue()));
            s.put("x", Math.round(e.getValue() / normal * 10) / 10.0);
            s.put("spot", index.get(t));
            List<Integer> scores = new ArrayList<>();
            double[] f0 = futures.get(before);
            double[] f1 = futures.get(after);
            if (f0 != null && f1 != null) {
                SurgeFlow.Reading r = SurgeFlow.futures(f0[0], f0[1], f1[0], f1[1]);
                s.put("futures", Map.of("oi", Math.round(f1[1] - f0[1]), "pct", round(100 * (f1[1] - f0[1]) / f0[1], 2),
                        "px", round(f1[0] - f0[0], 2), "label", r.label(), "score", r.score()));
                scores.add(r.score());
            }
            Double spotBefore = index.get(before);
            if (optionExpiry != null && spotBefore != null) {
                double atm = Math.round(spotBefore / step) * step;
                Map<String, Quote> q = quotes(underlying, optionExpiry, day, before, after, atm, step);
                for (String type : List.of("CE", "PE")) {
                    Map<String, Object> side = optionSide(q, type, atm, step);
                    if (side != null) {
                        s.put(type, side);
                        scores.add((Integer) side.get("score"));
                    }
                }
                s.put("atm", atm);
            }
            s.put("flow", scores.size() < 2 ? "mixed" : SurgeFlow.overall(scores));
            flows.put(flowKey, new LinkedHashMap<>(s));
            outcome(s, index, t);
            surges.add(s);
        }
        out.put("surges", surges);
        return out;
    }

    private static Map<String, Object> optionSide(Map<String, Quote> q, String type, double atm, double step) {
        double oi0 = 0;
        double oi1 = 0;
        for (int i = -2; i <= 2; i++) {
            Quote a = q.get("a|" + type + "|" + (atm + i * step));
            Quote b = q.get("b|" + type + "|" + (atm + i * step));
            if (a == null || b == null) {
                return null;                                   // a strike without quotes: not evaluable
            }
            oi0 += a.oi();
            oi1 += b.oi();
        }
        Quote a = q.get("a|" + type + "|" + atm);
        Quote b = q.get("b|" + type + "|" + atm);
        SurgeFlow.Reading r = SurgeFlow.options(type.equals("CE"), oi0, oi1, a.mid(), b.mid());
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("oi", Math.round(oi1 - oi0));
        side.put("pct", round(oi0 > 0 ? 100 * (oi1 - oi0) / oi0 : 0, 2));
        side.put("mid0", round(a.mid(), 2));
        side.put("mid1", round(b.mid(), 2));
        side.put("bid", b.bid());
        side.put("ask", b.ask());
        side.put("label", r.label());
        side.put("score", r.score());
        return side;
    }

    /** Last quote of minute {@code before} ("a") and minute {@code after} ("b") of the ATM ± 2 strikes. */
    private Map<String, Quote> quotes(String underlying, LocalDate expiry, LocalDate day, LocalTime before, LocalTime after,
                                      double atm, double step) {
        Instant a0 = day.atTime(before).atZone(MarketTime.IST).toInstant();
        Instant b0 = day.atTime(after).atZone(MarketTime.IST).toInstant();
        Map<String, Quote> q = new LinkedHashMap<>();
        // one index probe per contract and minute (md.option_tick_contract_time, V14)
        String sql = "select bid_px[1], ask_px[1], oi from md.option_tick where underlying = ? and expiry = ? and strike = ? "
                + "and option_type = ? and recv_ts >= ? and recv_ts < ? order by recv_ts desc, src_seq desc limit 1";
        for (String window : List.of("a", "b")) {
            Instant start = window.equals("a") ? a0 : b0;
            for (String type : List.of("CE", "PE")) {
                for (int i = -2; i <= 2; i++) {
                    double strike = atm + i * step;
                    jdbc.query(sql, rs -> { q.put(window + "|" + type + "|" + strike, new Quote(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3))); },
                            underlying, expiry, strike, type, java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plusSeconds(60)));
                }
            }
        }
        return q;
    }

    /**
     * What the index did after the flow became known: the flow compares t−1 with t+1, so it can be acted on only
     * once minute t+1 has closed. Every horizon is measured from that close (index points): +5, +15 and +30
     * minutes later, and the best and worst minute close within the 15 minutes after it.
     */
    private static void outcome(Map<String, Object> s, Map<LocalTime, Double> index, LocalTime t) {
        LocalTime known = t.plusMinutes(1);
        s.put("from", known.toString());
        s.put("move5", move(index, known, 5));
        s.put("move15", move(index, known, 15));
        s.put("move30", move(index, known, 30));
        Double base = index.get(known);
        Double best = null, worst = null;
        for (int m = 1; m <= 15 && base != null; m++) {
            Double p = index.get(known.plusMinutes(m));
            if (p != null) {
                best = best == null ? p - base : Math.max(best, p - base);
                worst = worst == null ? p - base : Math.min(worst, p - base);
            }
        }
        s.put("best15", best == null ? null : round(best, 2));
        s.put("worst15", worst == null ? null : round(worst, 2));
    }

    private static Double move(Map<LocalTime, Double> index, LocalTime from, int minutes) {
        Double now = index.get(from);
        Double later = index.get(from.plusMinutes(minutes));
        return now == null || later == null ? null : round(later - now, 2);
    }

    /** Same-minute median futures volume of the previous sessions, fetched once per day and underlying. */
    private Map<LocalTime, Double> median(LocalDate day, String underlying) {
        String key = day + "|" + underlying;
        Map<LocalTime, Double> known = medians.get(key);
        if (known != null) {
            return known;
        }
        Map<LocalTime, Double> computed = computeMedian(day, underlying);
        if (!computed.isEmpty()) {
            medians.put(key, computed);                        // an empty result (zt down) is retried next time
        }
        return computed;
    }

    private Map<LocalTime, Double> computeMedian(LocalDate day, String underlying) {
            try {
                List<Map<LocalTime, Long>> history = new ZtSessionHistory(zt()).futuresMinuteVolumes(underlying, day,
                        HISTORY_SESSIONS);
                Map<LocalTime, List<Long>> byMinute = new TreeMap<>();
                for (Map<LocalTime, Long> session : history) {
                    session.forEach((t, v) -> {
                        if (v != null && v > 0) {
                            byMinute.computeIfAbsent(t, x -> new ArrayList<>()).add(v);
                        }
                    });
                }
                Map<LocalTime, Double> result = new TreeMap<>();
                byMinute.forEach((t, values) -> {
                    if (values.size() >= HISTORY_MIN) {
                        List<Long> sorted = values.stream().sorted().toList();
                        int n = sorted.size();
                        result.put(t, n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0);
                    }
                });
                return result;
            } catch (Exception e) {
                log.warn("futures volume history unavailable for {} {}: {}", underlying, day, e.getMessage());
                return new TreeMap<>();
            }
    }

    private HikariDataSource zt() throws java.sql.SQLException {
        HikariDataSource source = zt;
        if (source == null) {
            synchronized (this) {
                if (zt == null) {
                    TradingProperties.Source s = properties.source();
                    zt = ZtReadOnlyDataSource.open(new ZtReadOnlyDataSource.Settings(s.url(), s.username(), s.password(),
                            s.passwordFile(), s.passwordKey(), "autotrade-surges", 2));
                }
                source = zt;
            }
        }
        return source;
    }

    private static double round(double value, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(value * f) / f;
    }
}
