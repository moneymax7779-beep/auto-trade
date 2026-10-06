package com.autotrade.trading;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.core.time.MarketTime;
import com.autotrade.instruments.Instrument;
import com.autotrade.instruments.InstrumentMaster;
import com.autotrade.instruments.UpstoxInstrumentFile;
import com.autotrade.core.history.IndexWeightFiles;
import com.autotrade.upstox.UpstoxCandles;
import com.autotrade.upstox.UpstoxExpired;
import com.autotrade.upstox.UpstoxToken;
import com.autotrade.upstox.UpstoxUniverse;

/**
 * Pulls one-minute bars from Upstox into {@code hist.candle} for a range of days: the index and India VIX (public
 * candle endpoint), the constituents (public), and, with a token on the Plus plan, the futures and the options around
 * the money of every expiry in the range (expired-instruments endpoints). Idempotent: a bar already stored is updated,
 * not duplicated. Counts only are logged; never the token.
 */
final class BarFetcher {

    private static final Logger log = LoggerFactory.getLogger(BarFetcher.class);
    /** Options kept per expiry: strikes within this many steps of the index range over the contract's last week. */
    static final int STRIKES_BEYOND_RANGE = 10;
    /** Each expiry's options are fetched for its last days only (the strategies trade the nearest expiry). */
    static final int OPTION_DAYS_BEFORE_EXPIRY = 8;
    static final int FUTURE_DAYS_BEFORE_EXPIRY = 40;

    enum Part { INDEX, EQUITIES, FUTURES, OPTIONS }

    record Summary(Map<String, Integer> candles, List<String> problems) {
    }

    private final JdbcTemplate jdbc;
    private final Path instrumentDir;
    private final Path weightsDir;
    private final UpstoxCandles candles = new UpstoxCandles();

    BarFetcher(DataSource target, Path instrumentDir, Path weightsDir) {
        this.jdbc = new JdbcTemplate(target);
        this.instrumentDir = instrumentDir;
        this.weightsDir = weightsDir;
    }

    Summary fetch(LocalDate from, LocalDate to, List<String> underlyings, Set<Part> parts, Optional<UpstoxToken> token,
                  InstrumentMaster master) throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        if (parts.contains(Part.INDEX)) {
            for (String u : underlyings) {
                counts.put(u + " index", store(u, "INDEX", UpstoxCandles.indexKey(u), u, null, 0, null, 0,
                        exchange(u), publicMinutes(UpstoxCandles.indexKey(u), from, to), "upstox-v3", from, to, problems));
            }
            counts.put("INDIA_VIX", store(UpstoxUniverse.VIX_UNDERLYING, "VIX", UpstoxUniverse.VIX_KEY, "INDIA VIX", null, 0,
                    null, 0, "NSE", publicMinutes(UpstoxUniverse.VIX_KEY, from, to), "upstox-v3", from, to, problems));
        }
        if (parts.contains(Part.EQUITIES)) {
            for (String u : underlyings) {
                counts.put(u + " constituents", equities(u, from, to, problems));
            }
        }
        if (parts.contains(Part.FUTURES) || parts.contains(Part.OPTIONS)) {
            if (token.isEmpty()) {
                problems.add("futures and options need an Upstox token (Plus plan): none available, skipped");
            } else {
                UpstoxExpired expired = new UpstoxExpired(token.get());
                for (String u : underlyings) {
                    List<LocalDate> expiries = expired.expiries(UpstoxCandles.indexKey(u));
                    if (parts.contains(Part.OPTIONS)) {
                        counts.put(u + " options", options(expired, u, expiries, from, to, problems)
                                + liveOptions(u, expiries, from, to, master, problems));
                    }
                    if (parts.contains(Part.FUTURES)) {
                        counts.put(u + " futures", futures(expired, u, expiries, from, to, master, problems));
                    }
                }
            }
        }
        return new Summary(counts, problems);
    }

    // ---------------------------------------------------------------- parts

    private int equities(String u, LocalDate from, LocalDate to, List<String> problems) throws Exception {
        Map<String, Double> weights = new IndexWeightFiles(weightsDir).weights(u, to);
        if (weights.isEmpty()) {
            problems.add(u + ": no index weight file covers " + to + " in " + weightsDir + "; constituents skipped");
            return 0;
        }
        String prefix = u.equals("SENSEX") ? "BSE-" : "NSE-";
        Optional<Path> file;
        try (var files = Files.list(instrumentDir)) {
            file = files.filter(f -> f.getFileName().toString().startsWith(prefix) && f.toString().endsWith(".json.gz"))
                    .max(Comparator.naturalOrder());
        }
        if (file.isEmpty()) {
            problems.add(u + ": no " + prefix + "*.json.gz contract file in " + instrumentDir + "; constituents skipped");
            return 0;
        }
        int total = 0;
        for (UpstoxInstrumentFile.Listing l : UpstoxInstrumentFile.readEquities(file.get(),
                u.equals("SENSEX") ? "BSE_EQ" : "NSE_EQ", weights.keySet())) {
            total += store(u, "EQUITY", l.instrumentKey(), l.tradingSymbol(), l.exchangeToken(), null, 0, null, 1, exchange(u),
                    publicMinutes(l.instrumentKey(), from, to), "upstox-v3", from, to, problems);
        }
        return total;
    }

    private int options(UpstoxExpired expired, String u, List<LocalDate> expiries, LocalDate from, LocalDate to,
                        List<String> problems) throws Exception {
        int total = 0;
        for (LocalDate expiry : expiriesCovering(expiries, from, to, OPTION_DAYS_BEFORE_EXPIRY)) {
            List<UpstoxExpired.Contract> contracts = expired.optionContracts(UpstoxCandles.indexKey(u), expiry);
            if (contracts.isEmpty()) {
                problems.add(u + " " + expiry + ": no expired option contracts listed (not expired yet, or not served)");
                continue;
            }
            LocalDate start = expiry.minusDays(OPTION_DAYS_BEFORE_EXPIRY);
            double[] range = indexRange(u, start, expiry);
            if (range == null) {
                // the strike window needs the index bars of that week: fetch them first (the public endpoint)
                store(u, "INDEX", UpstoxCandles.indexKey(u), u, null, 0, null, 0, exchange(u),
                        publicMinutes(UpstoxCandles.indexKey(u), start, expiry), "upstox-v3", start, expiry, problems);
                range = indexRange(u, start, expiry);
            }
            Set<Double> strikes = strikesToKeep(contracts.stream().map(UpstoxExpired.Contract::strike).toList(), range,
                    STRIKES_BEYOND_RANGE);
            int kept = 0;
            for (UpstoxExpired.Contract c : contracts) {
                if (!strikes.contains(c.strike())) {
                    continue;
                }
                kept++;
                LocalDate cFrom = start.isBefore(from) ? from : start;
                LocalDate cTo = expiry.isAfter(to) ? to : expiry;
                if (alreadyFetched(c.expiredKey(), cFrom, cTo)) {
                    continue;
                }
                try {
                    total += store(u, "OPTION", c.expiredKey(), c.tradingSymbol(), c.expiry(), c.strike(), c.optionType(),
                            c.lotSize(), c.exchange() == null ? exchange(u) : c.exchange(),
                            expired.minutes(c.expiredKey(), cFrom, cTo), "upstox-expired-v2", start, expiry, problems);
                } catch (Exception e) {
                    problems.add(u + " OPTION " + c.tradingSymbol() + ": " + e.getMessage());
                }
            }
            log.info("{} {}: {} of {} option contracts kept (strikes within {} steps of the week's index range {})",
                    u, expiry, kept, contracts.size(), STRIKES_BEYOND_RANGE,
                    range == null ? "unknown: all strikes kept" : Math.round(range[0]) + "–" + Math.round(range[1]));
        }
        return total;
    }

    /**
     * Options of expiries the expired-instruments API does not list yet (today's expiry, and the ones still to come)
     * whose last {@link #OPTION_DAYS_BEFORE_EXPIRY} days reach into [from, to] up to today: the live instrument master
     * gives the contracts, the public endpoint (intraday for today) the bars.
     */
    private int liveOptions(String u, List<LocalDate> expiredExpiries, LocalDate from, LocalDate to, InstrumentMaster master,
                            List<String> problems) throws Exception {
        LocalDate today = LocalDate.now(MarketTime.IST);
        if (master == null || to.isBefore(today)) {
            return 0;
        }
        int total = 0;
        for (LocalDate expiry : master.optionExpiries(u, today)) {
            if (expiredExpiries.contains(expiry) || expiry.minusDays(OPTION_DAYS_BEFORE_EXPIRY).isAfter(to)) {
                continue;
            }
            LocalDate start = expiry.minusDays(OPTION_DAYS_BEFORE_EXPIRY);
            LocalDate end = expiry.isAfter(to) ? to : expiry;
            double[] range = indexRange(u, start, end);
            if (range == null) {
                store(u, "INDEX", UpstoxCandles.indexKey(u), u, null, 0, null, 0, exchange(u),
                        publicMinutes(UpstoxCandles.indexKey(u), start, end), "upstox-v3", start, end, problems);
                range = indexRange(u, start, end);
            }
            List<Instrument> listed = master.instruments(u).stream()
                    .filter(i -> i.isOption() && expiry.equals(i.expiry())).toList();
            Set<Double> strikes = strikesToKeep(listed.stream().map(Instrument::strike).toList(), range, STRIKES_BEYOND_RANGE);
            int kept = 0;
            for (Instrument i : listed) {
                if (!strikes.contains(i.strike())) {
                    continue;
                }
                kept++;
                LocalDate cFrom = start.isBefore(from) ? from : start;
                if (alreadyFetched(i.instrumentKey(), cFrom, end)) {
                    continue;
                }
                try {
                    total += store(u, "OPTION", i.instrumentKey(), i.tradingSymbol(), i.expiry(), i.strike(), i.type(), i.lotSize(),
                            i.exchange() == null ? exchange(u) : i.exchange(),
                            publicMinutes(i.instrumentKey(), cFrom, end), "upstox-v3", start, end, problems);
                } catch (Exception e) {
                    problems.add(u + " OPTION " + i.tradingSymbol() + ": " + e.getMessage());
                }
            }
            log.info("{} {} (live contracts): {} of {} option contracts kept (strikes within {} steps of the index range {})",
                    u, expiry, kept, listed.size(), STRIKES_BEYOND_RANGE,
                    range == null ? "unknown: all strikes kept" : Math.round(range[0]) + "–" + Math.round(range[1]));
        }
        return total;
    }

    private int futures(UpstoxExpired expired, String u, List<LocalDate> expiries, LocalDate from, LocalDate to,
                        InstrumentMaster master, List<String> problems) throws Exception {
        int total = 0;
        boolean any = false;
        for (LocalDate expiry : expiriesCovering(expiries, from, to, FUTURE_DAYS_BEFORE_EXPIRY)) {
            for (UpstoxExpired.Contract c : expired.futureContracts(UpstoxCandles.indexKey(u), expiry)) {
                any = true;
                LocalDate start = expiry.minusDays(FUTURE_DAYS_BEFORE_EXPIRY);
                LocalDate cFrom = start.isBefore(from) ? from : start;
                LocalDate cTo = expiry.isAfter(to) ? to : expiry;
                if (alreadyFetched(c.expiredKey(), cFrom, cTo)) {
                    continue;
                }
                try {
                    total += store(u, "FUTURE", c.expiredKey(), c.tradingSymbol(), c.expiry(), 0, null, c.lotSize(),
                            c.exchange() == null ? exchange(u) : c.exchange(),
                            expired.minutes(c.expiredKey(), cFrom, cTo), "upstox-expired-v2", start, expiry, problems);
                } catch (Exception e) {
                    problems.add(u + " FUTURE " + c.tradingSymbol() + ": " + e.getMessage());
                }
            }
        }
        // the contract that has not expired yet: the live candle endpoint with the current instrument master
        Optional<Instrument> live = master == null ? Optional.empty() : master.nearestFuture(u, to);
        if (live.isPresent()) {
            Instrument f = live.get();
            LocalDate start = f.expiry().minusDays(FUTURE_DAYS_BEFORE_EXPIRY);
            total += store(u, "FUTURE", f.instrumentKey(), f.tradingSymbol(), f.expiry(), 0, null, f.lotSize(), f.exchange(),
                    publicMinutes(f.instrumentKey(), start.isBefore(from) ? from : start, to), "upstox-v3", start, to, problems);
        } else if (!any) {
            problems.add(u + ": no future contract found for the range");
        }
        return total;
    }

    // ---------------------------------------------------------------- helpers (pure, tested)

    /** Expiries whose last {@code daysBefore} days overlap [from, to]. */
    static List<LocalDate> expiriesCovering(List<LocalDate> expiries, LocalDate from, LocalDate to, int daysBefore) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate e : expiries) {
            if (!e.isBefore(from) && !e.minusDays(daysBefore).isAfter(to)) {
                out.add(e);
            }
        }
        return out;
    }

    /** The listed strikes within {@code beyond} strike steps of the index range [lo, hi]; all of them without a range. */
    static TreeSet<Double> strikesToKeep(List<Double> listed, double[] range, int beyond) {
        TreeSet<Double> distinct = new TreeSet<>(listed);
        if (range == null || distinct.size() < 2) {
            return distinct;
        }
        double step = Double.MAX_VALUE;
        Double previous = null;
        for (double s : distinct) {
            if (previous != null && s - previous > 0) {
                step = Math.min(step, s - previous);
            }
            previous = s;
        }
        double lo = range[0] - beyond * step, hi = range[1] + beyond * step;
        TreeSet<Double> keep = new TreeSet<>();
        for (double s : distinct) {
            if (s >= lo && s <= hi) {
                keep.add(s);
            }
        }
        return keep;
    }

    /** [lowest low, highest high] of the stored index bars in the range; null when none are stored yet. */
    private double[] indexRange(String u, LocalDate from, LocalDate to) {
        var row = jdbc.queryForList("select min(low) lo, max(high) hi from hist.candle where underlying = ? and kind = 'INDEX' "
                + "and bar_start >= ? and bar_start < ?", u, ts(from), ts(to.plusDays(1)));
        Object lo = row.getFirst().get("lo"), hi = row.getFirst().get("hi");
        return lo == null || hi == null ? null : new double[] {((Number) lo).doubleValue(), ((Number) hi).doubleValue()};
    }

    private int store(String underlying, String kind, String key, String symbol, LocalDate expiry, double strike,
                      String optionType, int lotSize, String exchange, List<UpstoxCandles.Candle> bars, String source,
                      LocalDate from, LocalDate to, List<String> problems) {
        return store(underlying, kind, key, symbol, null, expiry, strike, optionType, lotSize, exchange, bars, source, from, to, problems);
    }

    private int store(String underlying, String kind, String key, String symbol, String exchangeToken, LocalDate expiry,
                      double strike, String optionType, int lotSize, String exchange, List<UpstoxCandles.Candle> bars,
                      String source, LocalDate from, LocalDate to, List<String> problems) {
        if (bars.isEmpty()) {
            jdbc.update("insert into hist.fetch_log (underlying, kind, instrument_key, from_date, to_date, candles, status) "
                    + "values (?,?,?,?,?,0,'EMPTY')", underlying, kind, key, from, to);
            problems.add(underlying + " " + kind + " " + symbol + ": no candles for " + from + "–" + to);
            return 0;
        }
        List<Object[]> rows = new ArrayList<>(bars.size());
        for (UpstoxCandles.Candle b : bars) {
            rows.add(new Object[] {key, underlying, kind, symbol, exchangeToken, exchange, expiry, strike == 0 ? null : strike, optionType,
                    lotSize == 0 ? null : lotSize, Timestamp.from(b.start().toInstant()), b.open(), b.high(), b.low(), b.close(),
                    b.volume(), Double.isNaN(b.oi()) ? null : b.oi(), source});
        }
        jdbc.batchUpdate("insert into hist.candle (instrument_key, underlying, kind, symbol, exchange_token, exchange, expiry, strike, "
                + "option_type, lot_size, bar_start, open, high, low, close, volume, oi, source) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "on conflict (instrument_key, bar_start) do update set open = excluded.open, high = excluded.high, "
                + "low = excluded.low, close = excluded.close, volume = excluded.volume, oi = excluded.oi, source = excluded.source, "
                + "loaded_at = now()", rows);
        jdbc.update("insert into hist.fetch_log (underlying, kind, instrument_key, from_date, to_date, candles, status) "
                + "values (?,?,?,?,?,?,'OK')", underlying, kind, key, from, to, bars.size());
        log.info("{} {} {}: {} candles {}–{}", underlying, kind, symbol, bars.size(), bars.getFirst().session(), bars.getLast().session());
        return bars.size();
    }

    /**
     * The public candle endpoint, paced like the token one (no documented limit; a short pause avoids 429s). The
     * historical endpoint serves a session only from the next day; today's bars come from the intraday endpoint
     * (after the close: the whole day), so a day can be replayed from bars the same evening.
     */
    private List<UpstoxCandles.Candle> publicMinutes(String key, LocalDate from, LocalDate to) throws Exception {
        LocalDate today = LocalDate.now(MarketTime.IST);
        if (to.isBefore(today)) {
            Thread.sleep(UpstoxExpired.PAUSE_MS);
            return candles.minutes(key, from, to);
        }
        List<UpstoxCandles.Candle> out = new ArrayList<>();
        if (from.isBefore(today)) {
            Thread.sleep(UpstoxExpired.PAUSE_MS);
            out.addAll(candles.minutes(key, from, today.minusDays(1)));
        }
        Thread.sleep(UpstoxExpired.PAUSE_MS);
        for (UpstoxCandles.Candle c : candles.intradayMinutes(key)) {
            if (c.session().equals(today)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * True when a completed range of this key is already stored (a fetch_log OK row covering [from, to]); a range that
     * reaches today is never considered complete. Saves the rate-limited expired-instruments calls on a re-run.
     */
    private boolean alreadyFetched(String key, LocalDate from, LocalDate to) {
        if (!to.isBefore(LocalDate.now(MarketTime.IST))) {
            return false;
        }
        Integer n = jdbc.queryForObject("select count(*) from hist.fetch_log where instrument_key = ? and status = 'OK' "
                + "and from_date <= ? and to_date >= ?", Integer.class, key, from, to);
        boolean stored = n != null && n > 0;
        if (stored) {
            log.debug("{} {}–{}: already stored, skipped", key, from, to);
        }
        return stored;
    }

    private static Timestamp ts(LocalDate day) {
        return Timestamp.from(day.atStartOfDay(MarketTime.IST).toInstant());
    }

    private static String exchange(String underlying) {
        return underlying.equals("SENSEX") ? "BSE" : "NSE";
    }
}
