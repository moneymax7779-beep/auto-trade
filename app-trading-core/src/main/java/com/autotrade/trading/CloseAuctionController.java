package com.autotrade.trading;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autotrade.core.history.IndexWeightFiles;
import com.autotrade.core.time.MarketTime;

/**
 * The closing auction on the charts (user, 5 Nov 2026: "draw synthetic graph accurately in CAS from 3.15 to 3.30 pm").
 * NSE and BSE freeze the index at 15:15 while each stock's close is found by auction (orders from about 15:20, the close
 * printed about 15:28:30), and the options trade on to 15:30. Every 15 s from 15:05 to 15:30, two estimates:
 * <ul>
 *   <li><b>synthetic</b>: where the options price the index. The median over the five strikes nearest the 15:15 price
 *       of strike + call mid − put mid (the forward), less the forward's premium over the index (its median 15:05-15:15).
 *       Smooth and tradable, but not the close: on 24 own-capture days (1 Oct-5 Nov 2026) its 15:28 value missed the
 *       close by a median 24 NIFTY / 77 SENSEX points (worst −104 / −297), mostly below.</li>
 *   <li><b>indicative</b>: the auction's own estimate. The frozen index moved by each constituent's indicative
 *       equilibrium price (md.auction_tick) against its last price before 15:15, weighted by the newest official weights
 *       file. Noisy until the last minute (±70-200 points at 15:22). At 15:28:00 on the same 24 days: NIFTY median miss
 *       6 points, 17 within 10, 23 within 30; SENSEX median 12, 20 within 30. Far off on a volatile expiry auction
 *       (22 Oct 2026, SENSEX expiry: −98 NIFTY, −700 SENSEX; the indicative prices came back only in the last 30 s).</li>
 * </ul>
 * Plus the frozen 15:15 value and the official close (the index's final print) with its time. Display only. A finished
 * day is kept in md.chart_day_cache; an earlier day not kept is read only outside market hours.
 */
@RestController
class CloseAuctionController {

    static final LocalTime FROM = LocalTime.of(15, 5), FREEZE = LocalTime.of(15, 15), SHOW_FROM = LocalTime.of(15, 10),
            CLOSE_FROM = LocalTime.of(15, 28), UNTIL = LocalTime.of(15, 30, 30);
    static final int STEP = 15;
    static final int BUCKETS = (int) (java.time.Duration.between(FROM, UNTIL).toSeconds() / STEP);   // 102
    static final int FREEZE_BUCKET = (int) (java.time.Duration.between(FROM, FREEZE).toSeconds() / STEP);
    static final int SHOW_BUCKET = (int) (java.time.Duration.between(FROM, SHOW_FROM).toSeconds() / STEP);
    /** Strikes either side of the 15:15 price the synthetic forward uses. */
    static final int NEAR = 2;
    /** BSE contracts' instrument tokens are 2,000,000,000 plus the scrip code the SENSEX weights file uses. */
    private static final long BSE_TOKEN_BASE = 2_000_000_000L;
    static final String KIND = "close-auction-v2";

    private final JdbcTemplate jdbc;
    private final ChartDb db;
    private final MarketHours market;
    private final IndexWeightFiles weights = new IndexWeightFiles(Path.of("config", "reference", "weights"));
    private final Map<String, Map<String, Object>> finished = new java.util.concurrent.ConcurrentHashMap<>();
    /** Today's answers by index, with when they were read. */
    private final Map<String, Map.Entry<Long, Map<String, Object>>> today = new HashMap<>();
    static final long TODAY_TTL_MS = 15_000;

    CloseAuctionController(ChartDb db, MarketHours market) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.market = market;
    }

    @GetMapping("/api/close-auction")
    Map<String, Object> closeAuction(@RequestParam(required = false) String date, @RequestParam String underlying) {
        LocalDate day = date == null ? LocalDate.now(MarketTime.IST) : LocalDate.parse(date);
        String u = underlying.toUpperCase(Locale.ROOT);
        if (!ChartDb.finished(day)) {
            String key = day + "|" + u;
            synchronized (today) {                           // one read at a time; the pages share its answer for 15 s
                Map.Entry<Long, Map<String, Object>> hit = today.get(key);
                if (hit != null && System.currentTimeMillis() - hit.getKey() < TODAY_TTL_MS) {
                    return hit.getValue();
                }
                Map<String, Object> value = read(day, u);
                today.put(key, Map.entry(System.currentTimeMillis(), value));
                return value;
            }
        }
        Map<String, Object> done = finished.get(day + "|" + u);
        if (done != null) {
            return done;
        }
        Map<String, Object> kept = db.loadJson(KIND, day, u);
        if (kept == null && market.busy(ZonedDateTime.now(MarketTime.IST)) && !day.equals(LocalDate.now(MarketTime.IST))) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("date", day.toString());
            out.put("underlying", u);
            out.put("deferred", true);
            out.put("series", List.of());
            return out;
        }
        Map<String, Object> value = kept != null ? kept : read(day, u);
        if (kept == null) {
            db.saveJson(KIND, day, u, value);
        }
        finished.put(day + "|" + u, value);
        return value;
    }

    /** A malformed {@code date} is the caller's error (400), not the server's. */
    @org.springframework.web.bind.annotation.ExceptionHandler(java.time.format.DateTimeParseException.class)
    org.springframework.http.ResponseEntity<Map<String, Object>> badDate(java.time.format.DateTimeParseException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", "bad date: " + e.getParsedString()));
    }

    /** The day's ticks from 15:00 to 15:31 (index, five strikes' quotes, constituents, auction), into {@link #build}. */
    private Map<String, Object> read(LocalDate day, String u) {
        Timestamp from = ts(day, FROM), until = ts(day, UNTIL), freezeAt = ts(day, FREEZE), refFrom = ts(day, LocalTime.of(15, 0));
        double[] index = nan(BUCKETS);
        jdbc.query("select time_bucket('15 seconds', recv_ts) b, last(price, recv_ts) from md.index_tick where session_date = ? "
                + "and underlying = ? and recv_ts >= ? and recv_ts < ? and price > 0 group by 1",
                rs -> { put(index, bucket(day, rs.getTimestamp(1)), rs.getDouble(2)); }, day, u, from, until);
        // the value the index holds while frozen: its last print up to a minute after 15:15 (the 15:15 value is published a
        // second or two after 15:15)
        List<Double> frozen = jdbc.queryForList("select last(price, recv_ts) from md.index_tick where session_date = ? "
                + "and underlying = ? and recv_ts >= ? and recv_ts < ? and price > 0", Double.class, day, u, refFrom,
                ts(day, FREEZE.plusMinutes(1)));
        double freeze = frozen.isEmpty() || frozen.getFirst() == null ? Double.NaN : frozen.getFirst();
        // the close: the index's final value, from the moment it took that value after 15:28. Until the auction's result
        // prints, the index still repeats its frozen 15:15 value, which is not the close (code review 5 Nov 2026); a close
        // equal to the frozen value is known only once the day is over
        double close = Double.NaN;
        Instant closeAt = null, lastPrint = null;
        for (Map<String, Object> r : jdbc.queryForList("select recv_ts, price from md.index_tick where session_date = ? "
                + "and underlying = ? and recv_ts >= ? and recv_ts < ? and price > 0 order by recv_ts", day, u,
                ts(day, CLOSE_FROM), ts(day, LocalTime.of(16, 0)))) {
            double p = ((Number) r.get("price")).doubleValue();
            lastPrint = ((Timestamp) r.get("recv_ts")).toInstant();
            if (Double.isNaN(close) && p == freeze) {
                continue;
            }
            if (p != close) {
                close = p;
                closeAt = lastPrint;
            }
        }
        if (closeAt == null && lastPrint != null && !Double.isNaN(freeze) && ChartDb.finished(day)) {
            close = freeze;
            closeAt = lastPrint;
        }
        // the synthetic forward: the five strikes nearest the 15:15 price, of the nearest expiry
        double[] forward = nan(BUCKETS);
        List<LocalDate> expiries = jdbc.query("select min(expiry) from md.option_tick where session_date = ? and underlying = ? "
                + "and expiry >= ? and recv_ts >= ? and recv_ts < ?", (rs, n) -> rs.getObject(1, LocalDate.class),
                day, u, day, refFrom, from);
        LocalDate expiry = expiries.isEmpty() ? null : expiries.getFirst();
        if (expiry != null && !Double.isNaN(freeze)) {
            List<Double> ks = jdbc.queryForList("select distinct strike from md.option_tick where session_date = ? and underlying = ? "
                    + "and expiry = ? and recv_ts >= ? and recv_ts < ? order by 1", Double.class, day, u, expiry, refFrom, from);
            List<Double> near = nearest(ks, freeze, NEAR);
            Map<Double, double[]> calls = new TreeMap<>(), puts = new TreeMap<>();
            if (!near.isEmpty()) {
                jdbc.query("select time_bucket('15 seconds', recv_ts) b, strike, option_type, last((bid_px[1] + ask_px[1]) / 2, recv_ts) "
                        + "from md.option_tick where session_date = ? and underlying = ? and expiry = ? and strike between ? and ? "
                        + "and recv_ts >= ? and recv_ts < ? and bid_px[1] > 0 and ask_px[1] > 0 group by 1, 2, 3",
                        rs -> {
                            Map<Double, double[]> side = "PE".equals(rs.getString(3)) ? puts : calls;
                            put(side.computeIfAbsent(rs.getDouble(2), k -> nan(BUCKETS)), bucket(day, rs.getTimestamp(1)), rs.getDouble(4));
                        }, day, u, expiry, near.getFirst(), near.getLast(), from, until);
            }
            double[] f = forward(calls, puts);
            System.arraycopy(f, 0, forward, 0, BUCKETS);
        }
        // the auction: each constituent's last price before 15:15 and its indicative price from 15:15 (banks are captured
        // under BANKNIFTY too, so every index's rows are read)
        Map<String, Double> w = weights.weights(u, day);
        Map<String, Double> p0 = new HashMap<>();
        Map<String, double[]> iep = new HashMap<>();
        if (!w.isEmpty()) {
            jdbc.query("select symbol, instrument_token, last(price, recv_ts) from md.constituent_tick where session_date = ? "
                    + "and recv_ts >= ? and recv_ts < ? and price > 0 group by 1, 2",
                    rs -> {
                        String key = key(w, rs.getString(1), rs.getLong(2));
                        if (key != null) {
                            p0.put(key, rs.getDouble(3));
                        }
                    }, day, refFrom, freezeAt);
            jdbc.query("select time_bucket('15 seconds', recv_ts) b, symbol, instrument_token, last(iep, recv_ts) from md.auction_tick "
                    + "where session_date = ? and recv_ts >= ? and recv_ts < ? and iep > 0 group by 1, 2, 3",
                    rs -> {
                        String key = key(w, rs.getString(2), rs.getLong(3));
                        if (key != null) {
                            put(iep.computeIfAbsent(key, k -> nan(BUCKETS)), bucket(day, rs.getTimestamp(1)), rs.getDouble(4));
                        }
                    }, day, freezeAt, until);
        }
        Map<String, Object> out = build(index, forward, freeze, w, p0, iep);
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("date", day.toString());
        head.put("underlying", u);
        head.put("expiry", expiry == null ? null : expiry.toString());
        head.put("freeze", num(freeze));
        head.put("close", closeAt == null ? null : close);
        head.put("closeAt", closeAt == null ? null : closeAt.atZone(MarketTime.IST).toLocalTime().withNano(0).toString());
        head.put("stepSeconds", STEP);
        head.putAll(out);
        return head;
    }

    /**
     * The two lines from the per-bucket inputs: [time, synthetic, indicative, share of the index weight with an
     * indicative price (%)] from 15:10, and the forward's premium over the index ({@code basis}).
     */
    static Map<String, Object> build(double[] index, double[] forward, double freeze, Map<String, Double> weights,
                                     Map<String, Double> p0, Map<String, double[]> iep) {
        List<Double> gaps = new ArrayList<>();
        for (int b = 0; b < FREEZE_BUCKET; b++) {
            if (!Double.isNaN(forward[b]) && !Double.isNaN(index[b])) {
                gaps.add(forward[b] - index[b]);
            }
        }
        double basis = median(gaps);
        // the constituents that count: a weight and a price before 15:15
        double totalWeight = 0;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            if (p0.containsKey(e.getKey())) {
                totalWeight += e.getValue();
            }
        }
        Map<String, Double> lastIep = new HashMap<>();
        List<Object> series = new ArrayList<>();
        for (int b = SHOW_BUCKET; b < BUCKETS; b++) {
            double synthetic = Double.isNaN(basis) || Double.isNaN(forward[b]) ? Double.NaN : forward[b] - basis;
            for (Map.Entry<String, double[]> e : iep.entrySet()) {
                if (!Double.isNaN(e.getValue()[b])) {
                    lastIep.put(e.getKey(), e.getValue()[b]);            // carried until it changes
                }
            }
            double indicative = Double.NaN, covered = 0;
            if (b >= FREEZE_BUCKET && !lastIep.isEmpty() && totalWeight > 0 && !Double.isNaN(freeze)) {
                double sum = 0;
                for (Map.Entry<String, Double> e : weights.entrySet()) {
                    Double ref = p0.get(e.getKey());
                    if (ref == null || ref <= 0) {
                        continue;
                    }
                    Double now = lastIep.get(e.getKey());
                    sum += e.getValue() * (now == null ? 1 : now / ref);
                    covered += now == null ? 0 : e.getValue();
                }
                indicative = freeze * sum / totalWeight;
            }
            if (!Double.isNaN(synthetic) || !Double.isNaN(indicative)) {
                series.add(Arrays.asList(time(b), round(synthetic), round(indicative),
                        Double.isNaN(indicative) ? null : Math.round(1000 * covered / totalWeight) / 10.0));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("basis", round(basis));
        out.put("weightsCovered", totalWeight > 0 ? Math.round(10 * totalWeight) / 10.0 : null);
        out.put("series", series);
        return out;
    }

    /** Per bucket, the median over strikes quoted on both sides of strike + call mid − put mid (mids carried forward). */
    static double[] forward(Map<Double, double[]> calls, Map<Double, double[]> puts) {
        double[] out = nan(BUCKETS);
        Map<Double, Double> callNow = new HashMap<>(), putNow = new HashMap<>();
        for (int b = 0; b < BUCKETS; b++) {
            for (Map.Entry<Double, double[]> e : calls.entrySet()) {
                if (!Double.isNaN(e.getValue()[b])) {
                    callNow.put(e.getKey(), e.getValue()[b]);
                }
            }
            for (Map.Entry<Double, double[]> e : puts.entrySet()) {
                if (!Double.isNaN(e.getValue()[b])) {
                    putNow.put(e.getKey(), e.getValue()[b]);
                }
            }
            List<Double> f = new ArrayList<>();
            for (Map.Entry<Double, Double> e : callNow.entrySet()) {
                Double p = putNow.get(e.getKey());
                if (p != null) {
                    f.add(e.getKey() + e.getValue() - p);
                }
            }
            out[b] = median(f);
        }
        return out;
    }

    /** The {@code n} strikes either side of {@code price} (the nearest first and last). */
    static List<Double> nearest(List<Double> strikes, double price, int n) {
        TreeSet<Double> ks = new TreeSet<>(strikes);
        List<Double> out = new ArrayList<>();
        Double k = ks.floor(price);
        for (int i = 0; i < n + 1 && k != null; i++, k = ks.lower(k)) {
            out.add(k);
        }
        k = ks.higher(price);
        for (int i = 0; i < n && k != null; i++, k = ks.higher(k)) {
            out.add(k);
        }
        out.sort(Double::compare);
        return out;
    }

    /**
     * The weights file's key for a stock: an NSE row's symbol (NIFTY, Nifty Bank files) or a BSE row's scrip code (the
     * SENSEX file). BSE rows carry the same trading symbols ("RELIANCE"), so a symbol alone would mix the two exchanges.
     */
    static String key(Map<String, Double> weights, String symbol, long token) {
        if (token > BSE_TOKEN_BASE) {                        // a BSE row: only its scrip code (the SENSEX file)
            String code = String.valueOf(token - BSE_TOKEN_BASE);
            return weights.containsKey(code) ? code : null;
        }
        return weights.containsKey(symbol) ? symbol : null;  // an NSE row: its symbol (the NIFTY / Nifty Bank files)
    }

    static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
    }

    static String time(int bucket) {
        return FROM.plusSeconds((long) bucket * STEP).toString().length() == 5 ? FROM.plusSeconds((long) bucket * STEP) + ":00"
                : FROM.plusSeconds((long) bucket * STEP).toString();
    }

    private static int bucket(LocalDate day, Timestamp t) {
        return (int) Math.floorDiv(java.time.Duration.between(day.atTime(FROM).atZone(MarketTime.IST).toInstant(), t.toInstant())
                .toSeconds(), STEP);
    }

    private static void put(double[] a, int i, double v) {
        if (i >= 0 && i < a.length) {
            a[i] = v;
        }
    }

    private static double[] nan(int n) {
        double[] a = new double[n];
        Arrays.fill(a, Double.NaN);
        return a;
    }

    private static Double round(double v) {
        return Double.isNaN(v) ? null : Math.round(v * 100) / 100.0;
    }

    private static Double num(double v) {
        return Double.isNaN(v) ? null : v;
    }

    private static Timestamp ts(LocalDate day, LocalTime t) {
        return Timestamp.from(day.atTime(t).atZone(MarketTime.IST).toInstant());
    }
}
