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
 *       the band ATM ± {@value #BAND} strikes (the {@code band} parameter, 1 to {@value #MAX_BAND}), the <b>flow</b> (the band's OI change from the step before, summed
 *       over strikes that had OI in both steps, so a strike the capture starts recording mid-day adds no false jump)
 *       and the PCR of the opening chain: put OI over call OI of the strikes recorded on both sides in the first step,
 *       a fixed set for the day, so the ratio moves only when OI does (a band that follows the money also moves with
 *       the price, by 0.4 in a 30 s step at SENSEX's open, and jumps when a round strike with a large OI enters it).
 *       The flow band is narrow on purpose: the
 *       capture records about ATM ± 16 strikes and re-centres it as the market moves (19 Oct: NIFTY strikes below
 *       22,700 first appear at 12:04), so a wider band would count a moving set of strikes.</li>
 *   <li>{@code /api/oi-tape/strikes}: every strike within ATM ± {@value #STRIKES} at a step ({@code at}: the step's
 *       start, as the tape labels it; the values are those at its end), its call and put OI then and {@code back} seconds
 *       earlier (null where the capture had none yet), or since the day's first OI print or the previous close
 *       ({@code since}).</li>
 *   <li>{@code /api/oi-tape/max-pain} (user, 5 Nov 2026): the nearest expiry's max pain as the day goes, the strike
 *       at which the option buyers' payout at expiry (calls' and puts' OI times their intrinsic value) is smallest,
 *       over every strike the capture has OI for by then. The capture holds about ATM ± 16 strikes (re-centred as the
 *       market moves), so far out-of-the-money OI is left out. Context only: OI floors and ceilings held no better than
 *       chance at expiry (A-154).</li>
 * </ul>
 * Each value is the last one the exchange had published by the end of its step, carried forward (NIFTY publishes OI
 * about once a minute, BSE every 2½-3 minutes). Before 09:15 the last pre-open OI is the starting point.
 * <p>
 * Cost control, as {@link PcrController}: one pass over the day's ticks kept in memory (a few hundred KB per index and
 * day); today read incrementally (each call reads only ticks since the last), an earlier day once and not at all during
 * market hours (09:00-15:50 IST weekdays) unless already kept. In {@code --mode=auto} a warmer reads every recorded
 * index every 30 s of the session, so a page's read stays small. A complete day is also kept in md.chart_day_cache
 * ({@link ChartDb}, 28 Oct 2026), so a restart or deploy does not read its ticks again, and a kept day shows at any hour.
 * All reads use the charts pool, never the engine's.
 */
@RestController
@RequestMapping("/api/oi-tape")
class OiTapeController implements org.springframework.beans.factory.DisposableBean {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OiTapeController.class);

    static final LocalTime PREOPEN = LocalTime.of(9, 0), OPEN = LocalTime.of(9, 15), CLOSE = LocalTime.of(15, 30);
    static final int STEP = 30;
    static final int STEPS = (int) (java.time.Duration.between(OPEN, CLOSE).toSeconds() / STEP);   // 750
    static final int BAND = 5, STRIKES = 15;
    /** The widest band a caller may ask for (the capture records about ATM ± 16, re-centred as the market moves). */
    static final int MAX_BAND = 10;
    /**
     * Today is read only up to this long ago: the live capture writes its ticks every 5 s ({@code LiveCapture.flush}),
     * and a step is read once, so a step read before its last ticks were written would keep them out for good.
     */
    static final java.time.Duration WRITE_LAG = java.time.Duration.ofSeconds(15);
    /** How much of today already read is read again (late-committed ticks). */
    static final java.time.Duration REREAD = java.time.Duration.ofSeconds(60);
    private static final int KEPT_DAYS = 16;

    /** One index and day: per step the index, the future and every option contract's last OI in that step (NaN = none). */
    static final class Day {
        final LocalDate date;
        final LocalDate expiry;
        final double[] spot = nan(), futPx = nan(), futOi = nan();
        final Map<Double, double[]> calls = new TreeMap<>(), puts = new TreeMap<>();
        final Map<Double, Double> seedCalls = new HashMap<>(), seedPuts = new HashMap<>();   // last pre-open OI
        /** Per contract and step: the last traded price, the feed's implied volatility and the day's volume so far. */
        final Map<Double, float[]> callPx = new TreeMap<>(), putPx = new TreeMap<>(), callIv = new TreeMap<>(),
                putIv = new TreeMap<>(), callVol = new TreeMap<>(), putVol = new TreeMap<>();
        /** False for a day kept before prices, IV and volume were (format 1): rebuilt from the ticks outside market hours. */
        boolean extras = true;
        String futExpiry;
        Instant readUntil;
        int steps;                           // steps read so far (complete 30 s buckets)
        boolean complete;

        Day(LocalDate date, LocalDate expiry) {
            this.date = date;
            this.expiry = expiry;
            this.readUntil = date.atTime(PREOPEN).atZone(MarketTime.IST).toInstant();
        }

        List<Map<Double, float[]>> extraSides() {
            return List.of(callPx, putPx, callIv, putIv, callVol, putVol);
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
    private final ChartDb db;
    private final Map<String, Day> days = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Day> eldest) {
            return size() > KEPT_DAYS;
        }
    });
    private final List<String> underlyings;
    private final org.springframework.boot.ApplicationArguments args;
    private final MarketHours market;
    private java.util.concurrent.ScheduledExecutorService warmer;

    OiTapeController(ChartDb db, TradingProperties properties, org.springframework.boot.ApplicationArguments args,
                     MarketHours market) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.underlyings = List.copyOf(properties.trading().underlyings());
        this.args = args;
        this.market = market;
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
            if (!market.tradingDay(now.toLocalDate()) || now.toLocalTime().isBefore(OPEN.plusSeconds(STEP))
                    || now.toLocalTime().isAfter(CLOSE.plusMinutes(2))) {
                return;
            }
            for (String u : underlyings) {
                try {
                    day(now.toLocalDate(), u);
                } catch (Throwable e) {                               // never let one failure cancel the schedule
                    warn(u, e);
                }
            }
        }, 20, STEP, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        if (warmer != null) {
            warmer.shutdownNow();
        }
        synchronized (this) {
            if (rebuilder != null) {
                rebuilder.shutdownNow();
            }
        }
    }

    // ------------------------------------------------------------------ API

    @GetMapping
    Map<String, Object> tape(@RequestParam(required = false) String date, @RequestParam String underlying,
                             @RequestParam(defaultValue = "" + BAND) int band) {
        int b = Math.max(1, Math.min(MAX_BAND, band));
        LocalDate day = date == null ? LocalDate.now(MarketTime.IST) : LocalDate.parse(date);
        String u = known(underlying);
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
            out.put("band", b);
            out.put("stepSeconds", STEP);
            out.put("complete", d.complete);
            out.put("series", series(d, step, b));
        }
        return out;
    }

    /**
     * Every day the own capture holds (an ACTIVE md.load_manifest entry, newest first): the days the tape can show. A
     * failed or superseded load holds no ticks.
     */
    @GetMapping("/days")
    List<String> days() {
        return jdbc.queryForList("select distinct session_date::text from md.load_manifest where status = 'ACTIVE' "
                + "order by 1 desc", String.class);
    }

    /**
     * The ladder at {@code at} against {@code back} seconds earlier (the pre-open OI when that is before the open), or,
     * with {@code since} (user, 6 Nov 2026), against a fixed point of the day: {@code open}, the first OI the exchange
     * published after 09:15 (so the change is today's trading after that print), or {@code close}, the pre-open OI, which
     * is the exchange's closing OI of the day before (today's whole build-up). Not the previous session's last captured
     * OI: the next morning's opening OI differs from it by up to a third at some strikes (2-3 Nov 2026).
     */
    @GetMapping("/strikes")
    Map<String, Object> strikes(@RequestParam(required = false) String date, @RequestParam String underlying,
                                @RequestParam String at, @RequestParam(defaultValue = "300") int back,
                                @RequestParam(required = false) String since) {
        LocalDate day = date == null ? LocalDate.now(MarketTime.IST) : LocalDate.parse(date);
        String u = known(underlying);
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
            String from = j < 0 ? "pre-open" : null;
            if ("close".equals(since)) {
                j = -1;
                from = "previous close";
            } else if ("open".equals(since)) {
                j = openingStep(d, i, step);
            }
            out.put("expiry", d.expiry.toString());
            out.put("strikeStep", step);
            out.put("at", time(i));
            out.put("from", from != null ? from : time(j));
            out.put("since", since);
            out.put("spot", Double.isNaN(spot) ? null : spot);
            out.put("atm", Double.isNaN(atm) ? null : atm);
            // a window reaching the pre-open OI has no IV or premium then: the longer ones take the open's for who traded
            out.putAll(ladder(d, i, j, step, market::tradingDay, since != null || back > WINDOWS[WINDOWS.length - 1]));
            out.put("rebuilding", rebuilding(day, u));
        }
        return out;
    }

    @GetMapping("/max-pain")
    Map<String, Object> maxPain(@RequestParam(required = false) String date, @RequestParam String underlying) {
        LocalDate day = date == null ? LocalDate.now(MarketTime.IST) : LocalDate.parse(date);
        String u = known(underlying);
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
            out.put("expiry", d.expiry.toString());
            out.put("stepSeconds", STEP);
            out.put("series", maxPainSeries(d));
        }
        return out;
    }

    /** [time, max-pain strike, strikes with OI] at the first step, each step where it changes, and the last step. */
    static List<Object> maxPainSeries(Day d) {
        Map<Double, double[]> calls = carriedAll(d.calls, d.seedCalls, d.steps);
        Map<Double, double[]> puts = carriedAll(d.puts, d.seedPuts, d.steps);
        TreeSet<Double> strikes = new TreeSet<>(calls.keySet());
        strikes.addAll(puts.keySet());
        List<Object> rows = new ArrayList<>();
        double last = Double.NaN;
        for (int i = 0; i < d.steps; i++) {
            double best = Double.NaN, least = Double.POSITIVE_INFINITY;
            int counted = 0;
            boolean anyCall = false, anyPut = false;
            for (double k : strikes) {
                double c = calls.containsKey(k) ? calls.get(k)[i + 1] : Double.NaN;
                double p = puts.containsKey(k) ? puts.get(k)[i + 1] : Double.NaN;
                anyCall |= !Double.isNaN(c);
                anyPut |= !Double.isNaN(p);
                counted += Double.isNaN(c) && Double.isNaN(p) ? 0 : 1;
            }
            if (!anyCall || !anyPut) {
                continue;
            }
            for (double settle : strikes) {
                double pain = 0;
                for (double k : strikes) {
                    double c = calls.containsKey(k) ? nz(calls.get(k)[i + 1]) : 0;
                    double p = puts.containsKey(k) ? nz(puts.get(k)[i + 1]) : 0;
                    pain += c * Math.max(0, settle - k) + p * Math.max(0, k - settle);
                }
                if (pain < least) {
                    least = pain;
                    best = settle;
                }
            }
            if (best != last || i == d.steps - 1) {
                rows.add(Arrays.asList(time(i), best, counted));
                last = best;
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ the day in memory

    // ------------------------------------------------------------------ the strike ladder and its structure

    /** Strikes either side of spot the near ceiling and near floor look at (user, 5 Nov 2026: the writers' nearest walls). */
    static final int NEAR = 6;
    /** A wall moves to another strike only when that strike's OI is this much larger (no flicker between near-equal strikes). */
    static final double WALL_MARGIN = 1.05;
    /** An OI change smaller than this share of the strike's earlier OI gets no who-traded reading. */
    static final double MIN_OI_SHARE = 0.005;
    /** An IV change smaller than this (0.1 volatility point) is too small to say who traded. */
    static final double MIN_IV_CHANGE = 0.001;
    private static final double TRADING_MINUTES = 375;
    /** The windows the ladder's "changes 1-30 min" column shows side by side (user, 5 Nov 2026), in seconds. */
    static final int[] WINDOWS = {60, 300, 900, 1800};

    /**
     * The ladder at step {@code i} against step {@code j} (-1 = pre-open): per strike within ATM ± {@value #STRIKES}
     * [strike, call OI, call OI then, put OI, put OI then, call IV, call IV then, put IV, put IV then, call price, call
     * price then, put price, put price then, call volume in the window, put volume in the window, who traded the calls
     * (by IV), who traded the puts, the calls' gamma × OI, the puts' gamma × OI (each side from its own IV, else the
     * other side's), [the call OI change over each of
     * {@link #WINDOWS} then the put OI change over each] (null where the earlier OI is not known)]; the OI prints in the
     * window; and the
     * structure: the call wall, near ceiling, near floor and put wall (each with the step it took its strike and the
     * strike before), the ATM IV and one standard deviation of the index to the close.
     */
    static Map<String, Object> ladder(Day d, int i, int j, double step, java.util.function.Predicate<LocalDate> tradingDay) {
        return ladder(d, i, j, step, tradingDay, false);
    }

    /**
     * As {@link #ladder(Day, int, int, double, java.util.function.Predicate)}; with {@code openPrices} a window from the
     * pre-open ({@code j} < 0) takes each contract's first IV and premium of the day as its IV and premium then (none is
     * recorded before the open), so who traded reads the OI change since the previous close against the IV change since
     * the open.
     */
    static Map<String, Object> ladder(Day d, int i, int j, double step, java.util.function.Predicate<LocalDate> tradingDay,
                                      boolean openPrices) {
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.function.BiFunction<Map<Double, float[]>, Double, Double> then =
                (side, k) -> j < 0 && openPrices ? firstAt(side, k, i) : at(side, k, j);
        Map<Double, double[]> calls = carriedAll(d.calls, d.seedCalls, d.steps), puts = carriedAll(d.puts, d.seedPuts, d.steps);
        double[] spots = carry(d.spot, d.steps);
        double spot = spots[i];
        double atm = Double.isNaN(spot) ? Double.NaN : Math.round(spot / step) * step;
        double years = yearsToExpiry(d.date, d.expiry, i);
        List<Object> rows = new ArrayList<>();
        TreeSet<Double> ks = new TreeSet<>(calls.keySet());
        ks.addAll(puts.keySet());
        for (double k : ks.descendingSet()) {
            if (!Double.isNaN(atm) && Math.abs(k - atm) > STRIKES * step + 1e-6) {
                continue;
            }
            double c = oiAt(calls, k, i), c0 = oiAt(calls, k, j), p = oiAt(puts, k, i), p0 = oiAt(puts, k, j);
            double cIv = at(d.callIv, k, i), cIv0 = then.apply(d.callIv, k), pIv = at(d.putIv, k, i), pIv0 = then.apply(d.putIv, k);
            double cPx = at(d.callPx, k, i), cPx0 = then.apply(d.callPx, k), pPx = at(d.putPx, k, i), pPx0 = then.apply(d.putPx, k);
            // each side's gamma from its own IV (a side with none recorded borrows the other's: the two are close)
            double cG = gamma(spot, k, Double.isNaN(cIv) ? pIv : cIv, years) * nz(c);
            double pG = gamma(spot, k, Double.isNaN(pIv) ? cIv : pIv, years) * nz(p);
            rows.add(Arrays.asList(k, num(c), num(c0), num(p), num(p0), num(cIv), num(cIv0), num(pIv), num(pIv0),
                    num(cPx), num(cPx0), num(pPx), num(pPx0), num(windowVolume(d.callVol, k, i, j)),
                    num(windowVolume(d.putVol, k, i, j)),
                    who(c, c0, cIv, cIv0, MIN_IV_CHANGE), who(p, p0, pIv, pIv0, MIN_IV_CHANGE), num(cG), num(pG),
                    windowChanges(calls, puts, k, i)));
        }
        out.put("rows", rows);
        out.put("extras", d.extras);
        int[] pr = prints(calls, puts, spots, step, j, i);
        out.put("prints", pr[0]);
        out.put("lastPrint", pr[1] < 0 ? null : time(pr[1]));
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("callWall", wall(calls, spots, step, i, j, true, false));
        structure.put("nearCeiling", wall(calls, spots, step, i, j, true, true));
        structure.put("nearFloor", wall(puts, spots, step, i, j, false, true));
        structure.put("putWall", wall(puts, spots, step, i, j, false, false));
        double cAtm = at(d.callIv, atm, i), pAtm = at(d.putIv, atm, i);
        double atmIv = Double.isNaN(cAtm) ? pAtm : Double.isNaN(pAtm) ? cAtm : (cAtm + pAtm) / 2;
        structure.put("atmIv", num(atmIv));
        structure.put("sigmaClose", num(sigmaToClose(spot, atmIv, d.date, d.expiry, i, tradingDay)));
        out.put("structure", structure);
        return out;
    }

    /**
     * Who most likely traded a contract between two steps, from its OI change and the change in its IV (not its premium,
     * which mostly follows the index: OI rising in a rally would read as call buying and put writing whoever traded):
     * OI up as IV falls = writers opening ("WRITING"), OI up as IV rises = buyers opening ("BUYING"), OI down as IV rises
     * = writers buying back ("COVERING"), OI down as IV falls = buyers selling out ("CLOSING"). Null when either change
     * is too small to read or a value is missing. An inference from prices, not a record of who traded.
     */
    static String who(double oi, double oi0, double x, double x0, double xMin) {
        if (Double.isNaN(oi) || Double.isNaN(oi0) || Double.isNaN(x) || Double.isNaN(x0) || oi0 <= 0) {
            return null;
        }
        double dOi = oi - oi0, dx = x - x0;
        if (Math.abs(dOi) < MIN_OI_SHARE * oi0 || Math.abs(dx) < xMin) {
            return null;
        }
        return dOi > 0 ? (dx < 0 ? "WRITING" : "BUYING") : (dx > 0 ? "COVERING" : "CLOSING");
    }

    /**
     * A wall at step {@code i}: the strike with the largest OI on its side of spot, the call wall and put wall within ATM ±
     * {@value #STRIKES}, the near ceiling and near floor within {@value #NEAR} strikes of spot. Followed from the open so
     * the step it took its strike is known; it moves only when another strike's OI is {@value #WALL_MARGIN} times its own
     * or it leaves the zone. Null when no strike qualifies.
     */
    static Map<String, Object> wall(Map<Double, double[]> side, double[] spots, double step, int i, int j, boolean above,
                                    boolean near) {
        Double cur = null, from = null;
        int since = -1;
        Double gone = null, goneFrom = null;            // a wall that left its zone with none to replace it, and its own past
        int goneSince = -1;
        for (int s = 0; s <= i; s++) {
            double sp = spots[s];
            if (Double.isNaN(sp)) {
                continue;
            }
            double atm = Math.round(sp / step) * step;
            Double best = null;
            double bestOi = 0;
            for (Map.Entry<Double, double[]> e : side.entrySet()) {
                double k = e.getKey(), oi = e.getValue()[s + 1];
                if (inZone(k, sp, atm, step, above, near) && !Double.isNaN(oi) && oi > bestOi) {
                    best = k;
                    bestOi = oi;
                }
            }
            if (best == null) {
                if (cur != null && !inZone(cur, sp, atm, step, above, near)) {   // nothing on that side any more
                    gone = cur;
                    goneFrom = from;
                    goneSince = since;
                    cur = null;
                }
                continue;
            }
            if (cur == null && gone != null) {
                if (best.equals(gone)) {                       // back on the same strike after the gap: no move
                    cur = gone;
                    from = goneFrom;
                    since = goneSince;
                } else {                                       // a new strike: moved from the one before the gap
                    from = gone;
                    cur = best;
                    since = s;
                }
                gone = null;
                continue;
            }
            if (best.equals(cur)) {
                continue;
            }
            double curOi = cur == null ? Double.NaN : side.get(cur)[s + 1];
            if (cur == null || !inZone(cur, sp, atm, step, above, near) || Double.isNaN(curOi) || bestOi > WALL_MARGIN * curOi) {
                from = cur;
                cur = best;
                since = s;
            }
        }
        if (cur == null) {
            return null;
        }
        Map<String, Object> w = new LinkedHashMap<>();
        double oi = side.get(cur)[i + 1], oi0 = oiAt(side, cur, j);
        w.put("strike", cur);
        w.put("oi", num(oi));
        w.put("change", Double.isNaN(oi0) ? null : oi - oi0);
        w.put("since", time(since));
        w.put("from", from);
        return w;
    }

    private static boolean inZone(double k, double spot, double atm, double step, boolean above, boolean near) {
        double reach = (near ? NEAR : STRIKES) * step + 1e-6;
        return above ? k > spot && k - (near ? spot : atm) <= reach : k < spot && (near ? spot : atm) - k <= reach;
    }

    /**
     * [the exchange's OI prints in ({@code j}, {@code i}], the last step up to {@code i} in which near-the-money OI
     * changed, or -1], read per contract within ATM ± {@value #BAND} of step {@code i}: a contract's OI changes once per
     * print, so the window held as many prints as the most-changed contract changed. A print's ticks arrive over several
     * seconds (NIFTY about :25-:32 past each minute), so a window that starts inside one can count its tail as one more.
     * NIFTY prints about once a minute, BSE every 2½-3 minutes, so a short window can hold none.
     */
    static int[] prints(Map<Double, double[]> calls, Map<Double, double[]> puts, double[] spots, double step, int j, int i) {
        double sp = spots[i];
        if (Double.isNaN(sp)) {
            return new int[] {0, -1};
        }
        double atm = Math.round(sp / step) * step;
        List<double[]> band = new ArrayList<>();
        for (Map<Double, double[]> side : List.of(calls, puts)) {
            side.forEach((k, v) -> {
                if (Math.abs(k - atm) <= BAND * step + 1e-6) {
                    band.add(v);
                }
            });
        }
        int n = 0, last = -1;
        for (double[] v : band) {
            int count = 0, lastChange = -1;
            for (int s = i; s >= 0; s--) {
                if (changed(v, s)) {
                    if (lastChange < 0) {
                        lastChange = s;
                    }
                    if (s > j) {
                        count++;
                    }
                }
                if (s <= j + 1 && lastChange >= 0) {
                    break;
                }
            }
            n = Math.max(n, count);
            last = Math.max(last, lastChange);
        }
        return new int[] {n, last};
    }

    /**
     * The step that ends the day's first OI print after 09:15, read within ATM ± {@value #BAND} of step {@code i} as
     * {@link #prints}, or -1 when none has come by then: the first step in which a contract's OI moved from its
     * pre-open value, or the next one when the print's ticks ran on into it (other contracts change there, none of them a
     * second time). When the capture holds no pre-open OI near the money, the step its first values came in.
     */
    /** The step a since-the-open window starts from at step {@code i}: {@link #openingPrint}, or {@code i} before it. */
    static int openingStep(Day d, int i, double step) {
        int f = openingPrint(carriedAll(d.calls, d.seedCalls, d.steps), carriedAll(d.puts, d.seedPuts, d.steps),
                carry(d.spot, d.steps), step, i);
        return f < 0 ? i : f;                                          // no print yet: nothing has changed since the open
    }

    static int openingPrint(Map<Double, double[]> calls, Map<Double, double[]> puts, double[] spots, double step, int i) {
        double sp = spots[i];
        if (Double.isNaN(sp)) {
            return -1;
        }
        double atm = Math.round(sp / step) * step;
        List<double[]> band = new ArrayList<>();
        for (Map<Double, double[]> side : List.of(calls, puts)) {
            side.forEach((k, v) -> {
                if (Math.abs(k - atm) <= BAND * step + 1e-6) {
                    band.add(v);
                }
            });
        }
        if (band.stream().allMatch(v -> Double.isNaN(v[0]))) {         // no opening OI: the first values recorded
            for (int s = 0; s <= i; s++) {
                final int t = s;
                if (band.stream().anyMatch(v -> !Double.isNaN(v[t + 1]))) {
                    return s;
                }
            }
            return -1;
        }
        for (int s = 0; s <= i; s++) {
            final int t = s;
            if (band.stream().noneMatch(v -> changed(v, t))) {
                continue;
            }
            boolean tail = s < i && band.stream().anyMatch(v -> changed(v, t + 1))
                    && band.stream().noneMatch(v -> changed(v, t) && changed(v, t + 1));
            return tail ? s + 1 : s;
        }
        return -1;
    }

    private static boolean changed(double[] carried, int s) {
        return !Double.isNaN(carried[s]) && !Double.isNaN(carried[s + 1]) && carried[s + 1] != carried[s];
    }

    /**
     * One standard deviation of the index from the end of step {@code i} to 15:30, from the ATM IV: the option's variance
     * to expiry (IV² × calendar years, as the market prices it) shared over the trading minutes left to expiry, so the
     * nights and holidays carry none of it (an upper estimate for the rest of today).
     */
    static double sigmaToClose(double spot, double iv, LocalDate date, LocalDate expiry, int i,
                               java.util.function.Predicate<LocalDate> tradingDay) {
        if (Double.isNaN(spot) || Double.isNaN(iv) || iv <= 0 || expiry.isBefore(date)) {
            return Double.NaN;
        }
        double left = minutesLeft(i);
        if (left <= 0) {
            return 0;
        }
        double trading = left;
        for (LocalDate t = date.plusDays(1); !t.isAfter(expiry); t = t.plusDays(1)) {
            if (tradingDay.test(t)) {
                trading += TRADING_MINUTES;
            }
        }
        return spot * iv * Math.sqrt(yearsToExpiry(date, expiry, i) * left / trading);
    }

    private static double minutesLeft(int i) {
        return java.time.Duration.between(OPEN.plusSeconds((long) (i + 1) * STEP), CLOSE).toSeconds() / 60.0;
    }

    /** Calendar years from the end of step {@code i} to 15:30 on the expiry day. */
    static double yearsToExpiry(LocalDate date, LocalDate expiry, int i) {
        long secs = java.time.Duration.between(date.atTime(OPEN.plusSeconds((long) (i + 1) * STEP)), expiry.atTime(CLOSE)).toSeconds();
        return secs / (365.0 * 86_400);
    }

    /** Black-Scholes gamma per point of the index (rates left out: days to a weekly expiry). NaN when it cannot be had. */
    static double gamma(double spot, double strike, double iv, double years) {
        if (Double.isNaN(spot) || Double.isNaN(iv) || iv <= 0 || years <= 0) {
            return Double.NaN;
        }
        double sd = iv * Math.sqrt(years), d1 = (Math.log(spot / strike) + 0.5 * sd * sd) / sd;
        return Math.exp(-0.5 * d1 * d1) / Math.sqrt(2 * Math.PI) / (spot * sd);
    }

    /** [call OI change over each of {@link #WINDOWS}, then put OI change over each] up to step {@code i}. */
    private static List<Double> windowChanges(Map<Double, double[]> calls, Map<Double, double[]> puts, double k, int i) {
        List<Double> out = new ArrayList<>(2 * WINDOWS.length);
        for (Map<Double, double[]> side : List.of(calls, puts)) {
            double now = oiAt(side, k, i);
            for (int w : WINDOWS) {
                double then = oiAt(side, k, Math.max(-1, i - w / STEP));
                out.add(Double.isNaN(now) || Double.isNaN(then) ? null : now - then);
            }
        }
        return out;
    }

    /** A carried contract's OI at step {@code s} (-1 = pre-open), NaN when none. */
    private static double oiAt(Map<Double, double[]> side, double k, int s) {
        double[] v = side.get(k);
        return v == null ? Double.NaN : v[s + 1];
    }

    /** The last value of a contract up to step {@code s}, NaN when none (or {@code s} < 0: nothing kept before the open). */
    private static double at(Map<Double, float[]> side, double k, int s) {
        float[] v = side.get(k);
        if (v == null) {
            return Double.NaN;
        }
        for (int t = s; t >= 0; t--) {
            if (!Float.isNaN(v[t])) {
                return v[t];
            }
        }
        return Double.NaN;
    }

    /** The first value of a contract in steps 0 to {@code i}, NaN when none. */
    private static double firstAt(Map<Double, float[]> side, double k, int i) {
        float[] v = side.get(k);
        if (v != null) {
            for (int t = 0; t <= i; t++) {
                if (!Float.isNaN(v[t])) {
                    return v[t];
                }
            }
        }
        return Double.NaN;
    }

    /** Contracts traded between the ends of steps {@code j} and {@code i} (from the open when {@code j} < 0). */
    private static double windowVolume(Map<Double, float[]> side, double k, int i, int j) {
        double now = at(side, k, i);
        if (Double.isNaN(now)) {
            return Double.NaN;
        }
        double then = j < 0 ? 0 : at(side, k, j);
        return Double.isNaN(then) ? Double.NaN : Math.max(0, now - then);
    }

    private Map<String, Object> header(LocalDate day, String u) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("underlying", u);
        return out;
    }

    /**
     * An earlier day not yet in memory is read only outside market hours (holidays included), so it never competes with
     * the live session.
     */
    private boolean deferred(LocalDate day, String u) {
        ZonedDateTime now = ZonedDateTime.now(MarketTime.IST);
        return market.busy(now) && !day.equals(now.toLocalDate()) && !days.containsKey(day + "|" + u) && kept(day, u) == null;
    }

    /** The day, read up to now (today) or to the close; null when the capture has no options for it. */
    Day day(LocalDate day, String u) {
        String key = day + "|" + u;
        Day d = days.get(key);
        if (d == null) {
            d = kept(day, u);
        }
        if (d != null && !d.extras && !market.busy(ZonedDateTime.now(MarketTime.IST)) && rebuildTried.add(key)) {
            rebuild(key, d, u);
        }
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
                Instant until = readableUntil(Instant.now(), end);
                read(d, u, until);
                d.complete = !until.isBefore(end);
                if (d.complete) {
                    db.save(KIND, day, u, encode(d));
                }
            }
        }
        return d;
    }

    /** Days and indices whose format-1 rebuild has been started (once each, whatever the outcome), and those running. */
    private final java.util.Set<String> rebuildTried = java.util.concurrent.ConcurrentHashMap.newKeySet(),
            rebuilding = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private java.util.concurrent.ExecutorService rebuilder;

    /**
     * A day kept in format 1 (no prices, IV or volume) read again from its ticks, outside market hours, on a background
     * thread (the request that finds it is answered from the old day at once); kept as it is when the ticks are gone.
     * Re-checked for market hours when it runs, so one queued just before 09:00 does not read a whole day after it.
     */
    private void rebuild(String key, Day old, String u) {
        synchronized (this) {
            if (rebuilder == null) {
                rebuilder = java.util.concurrent.Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().name("oi-tape-rebuild").daemon(true).factory());
            }
        }
        rebuilding.add(key);
        rebuilder.execute(() -> {
            try {
                if (market.busy(ZonedDateTime.now(MarketTime.IST))) {   // queued before 09:00, run after it: not now
                    rebuildTried.remove(key);
                    return;
                }
                Day fresh = new Day(old.date, old.expiry);
                synchronized (fresh) {
                    read(fresh, u, ts(old.date, CLOSE).toInstant());
                    fresh.complete = true;
                }
                // kept when the ticks gave anything new (prices, IV or volume); the IV alone may be missing on old days
                if (fresh.extraSides().stream().anyMatch(m -> !m.isEmpty())) {
                    db.save(KIND, old.date, u, encode(fresh));
                    days.put(key, fresh);
                    log.info("oi tape: {} rebuilt with prices, IV and volume", key);
                }
            } catch (Throwable e) {
                log.warn("oi tape: rebuilding {} failed: {}", key, e.toString());
            } finally {
                rebuilding.remove(key);
            }
        });
    }

    /** Whether the day's prices, IV and volume are being read again now (the page can wait for them). */
    boolean rebuilding(LocalDate day, String u) {
        return rebuilding.contains(day + "|" + u);
    }

    static final String KIND = "oi-tape";
    /** 2 (5 Nov 2026): with each contract's price, IV and volume per step; a format-1 day still reads, without them. */
    private static final int FORMAT = 2;

    /** The kept complete day, now in memory; null when none is kept. */
    private Day kept(LocalDate day, String u) {
        byte[] payload = db.load(KIND, day, u);
        Day d = payload == null ? null : decode(payload);
        return d == null ? null : days.computeIfAbsent(day + "|" + u, k -> d);
    }

    /** A complete day: every per-step array and the pre-open seeds, as read. */
    static byte[] encode(Day d) {
        return ChartDb.encode(out -> {
            out.writeInt(FORMAT);
            out.writeLong(d.date.toEpochDay());
            out.writeLong(d.expiry.toEpochDay());
            out.writeBoolean(d.futExpiry != null);
            if (d.futExpiry != null) {
                out.writeUTF(d.futExpiry);
            }
            out.writeInt(d.steps);
            ChartDb.writeDoubles(out, d.spot);
            ChartDb.writeDoubles(out, d.futPx);
            ChartDb.writeDoubles(out, d.futOi);
            for (Map<Double, double[]> side : List.of(d.calls, d.puts)) {
                out.writeInt(side.size());
                for (Map.Entry<Double, double[]> e : side.entrySet()) {
                    out.writeDouble(e.getKey());
                    ChartDb.writeDoubles(out, e.getValue());
                }
            }
            for (Map<Double, Double> seed : List.of(d.seedCalls, d.seedPuts)) {
                out.writeInt(seed.size());
                for (Map.Entry<Double, Double> e : seed.entrySet()) {
                    out.writeDouble(e.getKey());
                    out.writeDouble(e.getValue());
                }
            }
            for (Map<Double, float[]> side : d.extraSides()) {
                out.writeInt(side.size());
                for (Map.Entry<Double, float[]> e : side.entrySet()) {
                    out.writeDouble(e.getKey());
                    for (float v : e.getValue()) {
                        out.writeFloat(v);
                    }
                }
            }
        });
    }

    static Day decode(byte[] payload) {
        return ChartDb.decode(payload, in -> {
            int format = in.readInt();
            if (format != 1 && format != FORMAT) {
                return null;
            }
            Day d = new Day(LocalDate.ofEpochDay(in.readLong()), LocalDate.ofEpochDay(in.readLong()));
            d.futExpiry = in.readBoolean() ? in.readUTF() : null;
            d.steps = in.readInt();
            System.arraycopy(ChartDb.readDoubles(in, STEPS), 0, d.spot, 0, STEPS);
            System.arraycopy(ChartDb.readDoubles(in, STEPS), 0, d.futPx, 0, STEPS);
            System.arraycopy(ChartDb.readDoubles(in, STEPS), 0, d.futOi, 0, STEPS);
            for (Map<Double, double[]> side : List.of(d.calls, d.puts)) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    side.put(in.readDouble(), ChartDb.readDoubles(in, STEPS));
                }
            }
            for (Map<Double, Double> seed : List.of(d.seedCalls, d.seedPuts)) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    seed.put(in.readDouble(), in.readDouble());
                }
            }
            if (format == 1) {
                d.extras = false;
            } else {
                for (Map<Double, float[]> side : d.extraSides()) {
                    int n = in.readInt();
                    for (int i = 0; i < n; i++) {
                        double k = in.readDouble();
                        float[] v = new float[STEPS];
                        for (int s = 0; s < STEPS; s++) {
                            v[s] = in.readFloat();
                        }
                        side.put(k, v);
                    }
                }
            }
            d.readUntil = ts(d.date, CLOSE).toInstant();
            d.complete = true;
            return d;
        });
    }

    /** How far a day can be read at {@code now}: whole steps written by the capture ({@link #WRITE_LAG}), or the close. */
    static Instant readableUntil(Instant now, Instant end) {
        Instant written = now.minus(WRITE_LAG);
        return written.isBefore(end) ? Instant.ofEpochSecond(Math.floorDiv(written.getEpochSecond(), STEP) * STEP) : end;
    }

    /** An underlying this service does not capture is the caller's error (400); it never reaches the database. */
    private String known(String underlying) {
        String u = underlying.toUpperCase(Locale.ROOT);
        if (!underlyings.contains(u)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "unknown underlying " + underlying);
        }
        return u;
    }

    private volatile Instant lastWarn = Instant.EPOCH;

    /** Warmer failures at WARN, at most one every 10 minutes (a persistent failure shows, without flooding the log). */
    private void warn(String u, Throwable e) {
        Instant now = Instant.now();
        if (now.isAfter(lastWarn.plus(java.time.Duration.ofMinutes(10)))) {
            lastWarn = now;
            log.warn("oi tape warmer {}: {}", u, e.toString());
        } else {
            log.debug("oi tape warmer {}: {}", u, e.toString());
        }
    }

    /** A malformed {@code date} or {@code at} is the caller's error (400), not the server's. */
    @org.springframework.web.bind.annotation.ExceptionHandler(java.time.format.DateTimeParseException.class)
    org.springframework.http.ResponseEntity<Map<String, Object>> badTime(java.time.format.DateTimeParseException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", "bad date or time: " + e.getParsedString()));
    }

    /** Reads the ticks from where the day was left up to {@code until} (whole 30 s steps). */
    private void read(Day d, String u, Instant until) {
        if (!until.isAfter(d.readUntil)) {
            return;
        }
        Instant open = ts(d.date, OPEN).toInstant();
        // re-read the last minute already read: ticks the capture committed late (a slow flush) still land; the steps are
        // written by index, so reading them twice is harmless
        Instant overlap = d.readUntil.minus(REREAD);
        Timestamp from = Timestamp.from(overlap.isAfter(open) ? overlap : d.readUntil), to = Timestamp.from(until);
        // volume is the day's running total, so the bucket's largest is its last
        jdbc.query("select time_bucket('30 seconds', recv_ts) b, strike, option_type, last(oi, recv_ts), last(ltp, recv_ts), "
                        + "last(iv, recv_ts) filter (where iv > 0), max(volume) from md.option_tick "
                        + "where session_date = ? and underlying = ? and expiry = ? and recv_ts >= ? and recv_ts < ? and oi > 0 "
                        + "group by 1, 2, 3 order by 1",                // in time order: the last pre-open bucket seeds
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
                        double px = rs.getDouble(5), iv = rs.getDouble(6);
                        boolean ivKnown = !rs.wasNull();
                        long vol = rs.getLong(7);
                        boolean volKnown = !rs.wasNull();
                        if (px > 0) {
                            (put ? d.putPx : d.callPx).computeIfAbsent(k, x -> fnan())[i] = (float) px;
                        }
                        if (ivKnown) {
                            (put ? d.putIv : d.callIv).computeIfAbsent(k, x -> fnan())[i] = (float) iv;
                        }
                        if (volKnown) {
                            (put ? d.putVol : d.callVol).computeIfAbsent(k, x -> fnan())[i] = (float) vol;
                        }
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
        return series(d, step, BAND);
    }

    /** As {@link #series(Day, double)} with the flow / OI band ATM ± {@code band} strikes (the opening-chain PCR does not depend on it). */
    static List<Object> series(Day d, double step, int band) {
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
                if (Math.abs(e.getKey() - atm) <= band * step + 1e-6) {
                    double[] v = e.getValue();
                    ce += nz(v[i + 1]);
                    if (!Double.isNaN(v[i + 1]) && !Double.isNaN(v[i])) {
                        dce += v[i + 1] - v[i];
                    }
                }
            }
            for (Map.Entry<Double, double[]> e : putsCarried.entrySet()) {
                if (Math.abs(e.getKey() - atm) <= band * step + 1e-6) {
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

    private static float[] fnan() {
        float[] a = new float[STEPS];
        Arrays.fill(a, Float.NaN);
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
