package com.autotrade.trading;

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

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autotrade.core.time.MarketTime;

/**
 * The OI tape (display only, every recorded index): the index, its near-month future and the option OI of the nearest
 * expiry in 30-second steps from 09:15 to 15:30 IST, from the own capture (md.*), for any day it holds.
 * <ul>
 *   <li>{@code /api/oi-tape}: per step the index, the future's price and OI, the ATM strike, the call and put OI of
 *       the band ATM ± {@value #BAND} strikes, the <b>flow</b> (the band's OI change from the step before, summed
 *       over strikes that had OI in both steps, so a strike the capture starts recording mid-day adds no false jump)
 *       and the PCR of the opening chain: put OI over call OI of the strikes recorded on both sides in the first step,
 *       a fixed set for the day, so the ratio moves only when OI does (a band that follows the money also moves with
 *       the price, by 0.4 in a 30 s step at SENSEX's open, and jumps when a round strike with a large OI enters it).
 *       The flow band is narrow on purpose: the
 *       capture records about ATM ± 16 strikes and re-centres it as the market moves (19 Oct: NIFTY strikes below
 *       22,700 first appear at 12:04), so a wider band would count a moving set of strikes.</li>
 *   <li>{@code /api/oi-tape/strikes}: every strike within ATM ± {@value #STRIKES} at a step ({@code at}: the step's
 *       start, as the tape labels it; the values are those at its end), its call and put OI then and {@code back} seconds
 *       earlier (null where the capture had none yet).</li>
 * </ul>
 * Each value is the last one the exchange had published by the end of its step, carried forward (NIFTY publishes OI
 * about once a minute, BSE every 2½-3 minutes). Before 09:15 the last pre-open OI is the starting point.
 * <p>
 * Cost control, as {@link PcrController}: one pass over the day's ticks kept in memory (a few hundred KB per index and
 * day); today read incrementally (each call reads only ticks since the last), an earlier day once and not at all during
 * market hours (09:00-15:50 IST weekdays) unless already kept. In {@code --mode=auto} a warmer reads every recorded
 * index every 30 s of the session, so a page's read stays small.
 */
@RestController
@RequestMapping("/api/oi-tape")
class OiTapeController implements org.springframework.beans.factory.DisposableBean {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OiTapeController.class);

    static final LocalTime PREOPEN = LocalTime.of(9, 0), OPEN = LocalTime.of(9, 15), CLOSE = LocalTime.of(15, 30);
    static final int STEP = 30;
    static final int STEPS = (int) (java.time.Duration.between(OPEN, CLOSE).toSeconds() / STEP);   // 750
    static final int BAND = 5, STRIKES = 15;
    private static final int KEPT_DAYS = 16;

    /** One index and day: per step the index, the future and every option contract's last OI in that step (NaN = none). */
    static final class Day {
        final LocalDate date;
        final LocalDate expiry;
        final double[] spot = nan(), futPx = nan(), futOi = nan();
        final Map<Double, double[]> calls = new TreeMap<>(), puts = new TreeMap<>();
        final Map<Double, Double> seedCalls = new HashMap<>(), seedPuts = new HashMap<>();   // last pre-open OI
        String futExpiry;
        Instant readUntil;
        int steps;                           // steps read so far (complete 30 s buckets)
        boolean complete;

        Day(LocalDate date, LocalDate expiry) {
            this.date = date;
            this.expiry = expiry;
            this.readUntil = date.atTime(PREOPEN).atZone(MarketTime.IST).toInstant();
        }

        double strikeStep() {
            TreeSet<Double> ks = new TreeSet<>(calls.keySet());
            ks.addAll(puts.keySet());
            double step = Double.MAX_VALUE;
            Double prev = null;
            for (double k : ks) {
                if (prev != null && k - prev > 0) {
                    step = Math.min(step, k - prev);
                }
                prev = k;
            }
            return step == Double.MAX_VALUE ? 50 : step;
        }
    }

    private final JdbcTemplate jdbc;
    private final Map<String, Day> days = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Day> eldest) {
            return size() > KEPT_DAYS;
        }
    });
    private final List<String> underlyings;
    private final org.springframework.boot.ApplicationArguments args;
    private java.util.concurrent.ScheduledExecutorService warmer;

    OiTapeController(DataSource target, TradingProperties properties, org.springframework.boot.ApplicationArguments args) {
        this.jdbc = new JdbcTemplate(target);
        this.underlyings = List.copyOf(properties.trading().underlyings());
        this.args = args;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void startWarmer() {
        String mode = args.containsOption("mode") && !args.getOptionValues("mode").isEmpty()
                ? args.getOptionValues("mode").getFirst() : "live";
        if (!"auto".equals(mode)) {
            return;
        }
        warmer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("oi-tape-warmer").daemon(true).factory());
        warmer.scheduleAtFixedRate(() -> {
            ZonedDateTime now = ZonedDateTime.now(MarketTime.IST);
            if (now.getDayOfWeek().getValue() > 5 || now.toLocalTime().isBefore(OPEN.plusSeconds(STEP))
                    || now.toLocalTime().isAfter(CLOSE.plusMinutes(2))) {
                return;
            }
            for (String u : underlyings) {
                try {
                    day(now.toLocalDate(), u);
                } catch (RuntimeException e) {
                    log.debug("oi tape warmer {}: {}", u, e.getMessage());
                }
            }
        }, 20, STEP, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        if (warmer != null) {
            warmer.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ API

    @GetMapping
    Map<String, Object> tape(@RequestParam(required = false) String date, @RequestParam String underlying) {
        LocalDate day = date == null ? LocalDate.now(MarketTime.IST) : LocalDate.parse(date);
        String u = underlying.toUpperCase(Locale.ROOT);
        Map<String, Object> out = header(day, u);
        if (deferred(day, u)) {
            out.put("deferred", true);
            out.put("series", List.of());
            return out;
        }
        Day d = day(day, u);
        if (d == null) {
            out.put("series", List.of());
            return out;
        }
        synchronized (d) {
            double step = d.strikeStep();
            out.put("expiry", d.expiry.toString());
            out.put("futuresExpiry", d.futExpiry);
            out.put("strikeStep", step);
            out.put("band", BAND);
            out.put("stepSeconds", STEP);
            out.put("complete", d.complete);
            out.put("series", series(d, step));
        }
        return out;
    }

    @GetMapping("/strikes")
    Map<String, Object> strikes(@RequestParam(required = false) String date, @RequestParam String underlying,
                                @RequestParam String at, @RequestParam(defaultValue = "300") int back) {
        LocalDate day = date == null ? LocalDate.now(MarketTime.IST) : LocalDate.parse(date);
        String u = underlying.toUpperCase(Locale.ROOT);
        Map<String, Object> out = header(day, u);
        if (deferred(day, u)) {
            out.put("deferred", true);
            out.put("rows", List.of());
            return out;
        }
        Day d = day(day, u);
        if (d == null) {
            out.put("rows", List.of());
            return out;
        }
        synchronized (d) {
            int i = Math.max(0, Math.min(d.steps - 1, index(LocalTime.parse(at))));
            int j = Math.max(-1, i - Math.max(0, back) / STEP);
            if (d.steps == 0) {
                out.put("rows", List.of());
                return out;
            }
            double step = d.strikeStep(), spot = carried(d.spot, i);
            double atm = Double.isNaN(spot) ? Double.NaN : Math.round(spot / step) * step;
            out.put("expiry", d.expiry.toString());
            out.put("strikeStep", step);
            out.put("at", time(i));
            out.put("from", j < 0 ? "pre-open" : time(j));
            out.put("spot", Double.isNaN(spot) ? null : spot);
            out.put("atm", Double.isNaN(atm) ? null : atm);
            List<Object> rows = new ArrayList<>();
            TreeSet<Double> ks = new TreeSet<>(d.calls.keySet());
            ks.addAll(d.puts.keySet());
            for (double k : ks.descendingSet()) {
                if (!Double.isNaN(atm) && Math.abs(k - atm) > STRIKES * step + 1e-6) {
                    continue;
                }
                rows.add(Arrays.asList(k, num(value(d.calls, d.seedCalls, k, i)), num(value(d.calls, d.seedCalls, k, j)),
                        num(value(d.puts, d.seedPuts, k, i)), num(value(d.puts, d.seedPuts, k, j))));
            }
            out.put("rows", rows);
        }
        return out;
    }

    // ------------------------------------------------------------------ the day in memory

    private Map<String, Object> header(LocalDate day, String u) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("underlying", u);
        return out;
    }

    /** An earlier day not yet in memory is read after the close only, so it never competes with the live session. */
    private boolean deferred(LocalDate day, String u) {
        ZonedDateTime now = ZonedDateTime.now(MarketTime.IST);
        boolean marketHours = now.getDayOfWeek().getValue() <= 5 && !now.toLocalTime().isBefore(PREOPEN)
                && now.toLocalTime().isBefore(LocalTime.of(15, 50));
        return marketHours && !day.equals(now.toLocalDate()) && !days.containsKey(day + "|" + u);
    }

    /** The day, read up to now (today) or to the close; null when the capture has no options for it. */
    Day day(LocalDate day, String u) {
        String key = day + "|" + u;
        Day d = days.get(key);
        if (d == null) {
            List<LocalDate> expiries = jdbc.query("select min(expiry) from md.option_tick where session_date = ? and "
                            + "underlying = ? and expiry >= ? and recv_ts >= ? and recv_ts < ?",
                    (rs, n) -> rs.getObject(1, LocalDate.class), day, u, day, ts(day, PREOPEN), ts(day, CLOSE));
            if (expiries.isEmpty() || expiries.getFirst() == null) {
                return null;
            }
            d = days.computeIfAbsent(key, k -> new Day(day, expiries.getFirst()));
        }
        synchronized (d) {
            if (!d.complete) {
                Instant end = ts(day, CLOSE).toInstant();
                Instant now = Instant.now();
                Instant until = now.isBefore(end) ? Instant.ofEpochSecond(now.getEpochSecond() / STEP * STEP) : end;
                read(d, u, until);
                d.complete = !until.isBefore(end);
            }
        }
        return d;
    }

    /** Reads the ticks from where the day was left up to {@code until} (whole 30 s steps). */
    private void read(Day d, String u, Instant until) {
        if (!until.isAfter(d.readUntil)) {
            return;
        }
        Timestamp from = Timestamp.from(d.readUntil), to = Timestamp.from(until);
        Instant open = ts(d.date, OPEN).toInstant();
        jdbc.query("select time_bucket('30 seconds', recv_ts) b, strike, option_type, last(oi, recv_ts) from md.option_tick "
                        + "where session_date = ? and underlying = ? and expiry = ? and recv_ts >= ? and recv_ts < ? and oi > 0 "
                        + "group by 1, 2, 3",
                rs -> {
                    Instant b = rs.getTimestamp(1).toInstant();
                    double k = rs.getDouble(2), oi = rs.getDouble(4);
                    boolean put = "PE".equals(rs.getString(3));
                    if (b.isBefore(open)) {                                   // pre-open: the starting point
                        (put ? d.seedPuts : d.seedCalls).merge(k, oi, (a, c) -> c);
                        return;
                    }
                    int i = (int) ((b.getEpochSecond() - open.getEpochSecond()) / STEP);
                    if (i >= 0 && i < STEPS) {
                        (put ? d.puts : d.calls).computeIfAbsent(k, x -> nan())[i] = oi;
                    }
                }, d.date, u, d.expiry, from, to);
        jdbc.query("select time_bucket('30 seconds', recv_ts) b, last(price, recv_ts) from md.index_tick where session_date = ? "
                        + "and underlying = ? and recv_ts >= ? and recv_ts < ? and price > 0 group by 1",
                rs -> {
                    int i = (int) ((rs.getTimestamp(1).toInstant().getEpochSecond() - open.getEpochSecond()) / STEP);
                    if (i >= 0 && i < STEPS) {
                        d.spot[i] = rs.getDouble(2);
                    }
                }, d.date, u, from, to);
        if (d.futExpiry == null) {
            List<String> e = jdbc.queryForList("select min(expiry)::text from md.future_tick where session_date = ? "
                    + "and underlying = ?", String.class, d.date, u);
            d.futExpiry = e.isEmpty() ? null : e.getFirst();
        }
        if (d.futExpiry != null) {
            jdbc.query("select time_bucket('30 seconds', recv_ts) b, last(price, recv_ts), last(oi, recv_ts) from md.future_tick "
                            + "where session_date = ? and underlying = ? and expiry = ?::date and recv_ts >= ? and recv_ts < ? "
                            + "and price > 0 group by 1",
                    rs -> {
                        int i = (int) ((rs.getTimestamp(1).toInstant().getEpochSecond() - open.getEpochSecond()) / STEP);
                        if (i >= 0 && i < STEPS) {
                            d.futPx[i] = rs.getDouble(2);
                            double oi = rs.getDouble(3);
                            d.futOi[i] = oi > 0 ? oi : Double.NaN;
                        }
                    }, d.date, u, d.futExpiry, from, to);
        }
        d.readUntil = until;
        d.steps = (int) Math.max(0, Math.min(STEPS, (until.getEpochSecond() - open.getEpochSecond()) / STEP));
    }

    /** Per step: [time, spot, fut price, fut OI, ATM, band call OI, band put OI, call flow, put flow, band PCR]. */
    static List<Object> series(Day d, double step) {
        List<Object> rows = new ArrayList<>(d.steps);
        Map<Double, double[]> callsCarried = carriedAll(d.calls, d.seedCalls, d.steps);
        Map<Double, double[]> putsCarried = carriedAll(d.puts, d.seedPuts, d.steps);
        double[] spot = carry(d.spot, d.steps), futPx = carry(d.futPx, d.steps), futOi = carry(d.futOi, d.steps);
        List<Double> chain = new ArrayList<>();                    // strikes with a call and a put by the end of step 0
        if (d.steps > 0) {
            callsCarried.forEach((k, v) -> {
                double[] p = putsCarried.get(k);
                if (p != null && !Double.isNaN(v[1]) && !Double.isNaN(p[1])) {
                    chain.add(k);
                }
            });
        }
        for (int i = 0; i < d.steps; i++) {
            if (Double.isNaN(spot[i])) {
                continue;
            }
            double atm = Math.round(spot[i] / step) * step;
            double ce = 0, pe = 0, dce = 0, dpe = 0, chainCe = 0, chainPe = 0;
            for (Map.Entry<Double, double[]> e : callsCarried.entrySet()) {
                if (Math.abs(e.getKey() - atm) <= BAND * step + 1e-6) {
                    double[] v = e.getValue();
                    ce += nz(v[i + 1]);
                    if (!Double.isNaN(v[i + 1]) && !Double.isNaN(v[i])) {
                        dce += v[i + 1] - v[i];
                    }
                }
            }
            for (Map.Entry<Double, double[]> e : putsCarried.entrySet()) {
                if (Math.abs(e.getKey() - atm) <= BAND * step + 1e-6) {
                    double[] v = e.getValue();
                    pe += nz(v[i + 1]);
                    if (!Double.isNaN(v[i + 1]) && !Double.isNaN(v[i])) {
                        dpe += v[i + 1] - v[i];
                    }
                }
            }
            for (double k : chain) {                                                   // PCR of the opening chain
                chainCe += callsCarried.get(k)[i + 1];
                chainPe += putsCarried.get(k)[i + 1];
            }
            rows.add(Arrays.asList(time(i), spot[i], num(futPx[i]), num(futOi[i]), atm, Math.round(ce), Math.round(pe),
                    Math.round(dce), Math.round(dpe), chainCe > 0 ? Math.round(chainPe / chainCe * 1000) / 1000.0 : null));
        }
        return rows;
    }

    /** Each contract carried forward: index 0 = the pre-open value, index i + 1 = the value at the end of step i. */
    private static Map<Double, double[]> carriedAll(Map<Double, double[]> steps, Map<Double, Double> seed, int n) {
        Map<Double, double[]> out = new TreeMap<>();
        TreeSet<Double> ks = new TreeSet<>(steps.keySet());
        ks.addAll(seed.keySet());
        for (double k : ks) {
            double[] raw = steps.get(k), v = new double[n + 1];
            double last = seed.getOrDefault(k, Double.NaN);
            v[0] = last;
            for (int i = 0; i < n; i++) {
                if (raw != null && !Double.isNaN(raw[i])) {
                    last = raw[i];
                }
                v[i + 1] = last;
            }
            out.put(k, v);
        }
        return out;
    }

    /** A contract's last OI by the end of step {@code i} (-1 = the pre-open value), NaN when none yet. */
    private static double value(Map<Double, double[]> steps, Map<Double, Double> seed, double k, int i) {
        double v = seed.getOrDefault(k, Double.NaN);
        double[] raw = steps.get(k);
        if (raw != null) {
            for (int s = 0; s <= i && s < raw.length; s++) {
                if (!Double.isNaN(raw[s])) {
                    v = raw[s];
                }
            }
        }
        return v;
    }

    private static double[] carry(double[] raw, int n) {
        double[] v = new double[n];
        double last = Double.NaN;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(raw[i])) {
                last = raw[i];
            }
            v[i] = last;
        }
        return v;
    }

    private static double carried(double[] raw, int i) {
        for (int s = i; s >= 0; s--) {
            if (!Double.isNaN(raw[s])) {
                return raw[s];
            }
        }
        return Double.NaN;
    }

    static int index(LocalTime t) {
        return (int) Math.floorDiv(java.time.Duration.between(OPEN, t).toSeconds(), STEP);
    }

    static String time(int i) {
        return OPEN.plusSeconds((long) i * STEP).toString().length() == 5 ? OPEN.plusSeconds((long) i * STEP) + ":00"
                : OPEN.plusSeconds((long) i * STEP).toString();
    }

    private static double[] nan() {
        double[] a = new double[STEPS];
        Arrays.fill(a, Double.NaN);
        return a;
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private static Double num(double v) {
        return Double.isNaN(v) ? null : v;
    }

    private static Timestamp ts(LocalDate day, LocalTime time) {
        return Timestamp.from(day.atTime(time).atZone(MarketTime.IST).toInstant());
    }
}
