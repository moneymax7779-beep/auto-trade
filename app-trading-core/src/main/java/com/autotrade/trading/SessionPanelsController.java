package com.autotrade.trading;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.indicators.Ema;
import com.autotrade.features.indicators.SwingTracker;

/**
 * The session page's analysis panels (4 Nov 2026; display only, nothing here feeds a strategy):
 * <ul>
 *   <li>structure: the index in 3-minute bars aligned to 09:15, EMA9 / EMA20 of their closes and the swing pivots
 *       (strength 2), built with the features' own {@link Ema} and {@link SwingTracker} as ma-cross sees them; each
 *       ma-cross trade's swing stop and target recomputed from those bars at its entry minute;</li>
 *   <li>premium: one traded option's minute bars that day, its entry and exit, and the best and worst premium while it
 *       was held (MFE / MAE);</li>
 *   <li>day context: India VIX by minute and its one-year percentile, the ATM straddle and the volatility it implies,
 *       the gap, the nearest expiry, the participants' index positions as published the evening before, and the day's
 *       shape (known only at the close).</li>
 * </ul>
 * Tick days read the own capture (md.*), other days the stored one-minute bars (hist.candle). Every query runs on the
 * charts pool, never the engine's. A session's answers are kept once it has ended; a running one's for 30 seconds.
 */
@RestController
@RequestMapping("/api")
class SessionPanelsController {

    private static final String IST = "Asia/Kolkata";
    private static final String VIX = "INDIA_VIX";
    static final LocalTime OPEN = LocalTime.of(9, 15);
    static final LocalTime CLOSE = LocalTime.of(15, 30);
    static final int EMA_FAST = 9, EMA_SLOW = 20, SWING_STRENGTH = 2;      // features v12 (structure.ema9 / ema20)
    static final double DEFAULT_REWARD_TO_RISK = 2.0;                       // every ma-cross version so far
    static final Map<String, Double> STRIKE_STEP = Map.of("NIFTY", 50.0, "SENSEX", 100.0, "BANKNIFTY", 100.0);

    /** {@code kept}: computed once nothing could change it any more (an ended session, a finished day). */
    private record Cached(long at, boolean kept, Map<String, Object> value) {
    }

    private final ChartDb db;
    private final JdbcTemplate jdbc;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    SessionPanelsController(ChartDb db) {
        this.db = db;
        this.jdbc = db.jdbc();                               // the charts pool, never the engine's
    }

    // ------------------------------------------------------------------------------------------ structure

    @GetMapping("/sessions/{id}/structure")
    Map<String, Object> structure(@PathVariable long id, @RequestParam String underlying) {
        String u = underlying.toUpperCase(Locale.ROOT);
        SessionInfo session = session(id);
        return cached("structure|" + id + "|" + u, session.done(), SESSION_TTL_MS, () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("trades", maCrossStops(id, u, structureOf(session.day(), u, out)));
            return out;
        });
    }

    /**
     * One day's structure without a session (6 Nov 2026, the index pages' ma-cross overlay, live and earlier days): the
     * same candles, EMAs and swings; no trades.
     */
    @GetMapping("/structure")
    Map<String, Object> dayStructure(@RequestParam String date, @RequestParam String underlying) {
        LocalDate day = LocalDate.parse(date);
        String u = underlying.toUpperCase(Locale.ROOT);
        return cached("structure-day|" + day + "|" + u, ChartDb.finished(day), SESSION_TTL_MS, () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            structureOf(day, u, out);
            out.put("trades", List.of());
            return out;
        });
    }

    /** The day's 3-minute candles, EMAs and swings, put into {@code out}; the structure for the trades' stops. */
    private Structure structureOf(LocalDate day, String u, Map<String, Object> out) {
        LevelsService.MinuteBars minutes = LevelsService.minuteBars(jdbc, day, u);
        List<LevelsService.Bar> three = threeMinute(minutes.bars(), ChartDb.finished(day));
        Structure s = structure(day, three);
        out.put("source", minutes.fromBars() ? "bars" : "ticks");
        out.put("candles", three.stream().map(b -> List.of(b.t().toString(), b.open(), b.high(), b.low(), b.close())).toList());
        out.put("ema9", s.ema9());
        out.put("ema20", s.ema20());
        out.put("swings", s.swings());
        return s;
    }

    /** One swing pivot: the bar it formed in ({@code t}), confirmed {@code SWING_STRENGTH} bars later ({@code at}). */
    record Swing(String t, String at, double price, boolean high) {
    }

    /** EMA points at each 3-minute bar's close (when it was knowable) and the confirmed swings. */
    record Structure(List<List<Object>> ema9, List<List<Object>> ema20, List<Swing> swings) {
    }

    /**
     * 3-minute bars aligned to 09:15 from minute bars, each stamped with its first minute; a day not yet finished drops
     * its last bar, which may still be forming.
     */
    static List<LevelsService.Bar> threeMinute(List<LevelsService.Bar> minutes, boolean finished) {
        TreeMap<Integer, LevelsService.Bar> slots = new TreeMap<>();
        for (LevelsService.Bar m : minutes) {
            int minute = m.t().toSecondOfDay() / 60 - OPEN.toSecondOfDay() / 60;
            if (minute < 0 || !m.t().isBefore(CLOSE)) {
                continue;
            }
            int slot = minute / 3;
            LevelsService.Bar b = slots.get(slot);
            slots.put(slot, b == null ? new LevelsService.Bar(OPEN.plusMinutes(3L * slot), m.open(), m.high(), m.low(), m.close())
                    : new LevelsService.Bar(b.t(), b.open(), Math.max(b.high(), m.high()), Math.min(b.low(), m.low()), m.close()));
        }
        List<LevelsService.Bar> out = new ArrayList<>(slots.values());
        if (!finished && !out.isEmpty()) {
            out.removeLast();
        }
        return out;
    }

    static Structure structure(LocalDate day, List<LevelsService.Bar> three) {
        Ema fast = new Ema(EMA_FAST, 1), slow = new Ema(EMA_SLOW, 1);
        SwingTracker swings = new SwingTracker(SWING_STRENGTH);
        List<List<Object>> ema9 = new ArrayList<>(), ema20 = new ArrayList<>();
        List<Swing> found = new ArrayList<>();
        for (LevelsService.Bar b : three) {
            String end = b.t().plusMinutes(3).toString();
            fast.update(b.close());
            slow.update(b.close());
            if (fast.ready()) {
                ema9.add(List.of(end, LevelsService.round(fast.value())));
            }
            if (slow.ready()) {
                ema20.add(List.of(end, LevelsService.round(slow.value())));
            }
            SwingTracker.Swing high = swings.lastHigh(), low = swings.lastLow();
            Instant start = day.atTime(b.t()).atZone(MarketTime.IST).toInstant();
            swings.update(new com.autotrade.features.bars.Bar(start, start.plusSeconds(180), b.open(), b.high(), b.low(),
                    b.close(), 0, 1));
            if (swings.lastHigh() != high) {
                found.add(swing(swings.lastHigh(), end));
            }
            if (swings.lastLow() != low) {
                found.add(swing(swings.lastLow(), end));
            }
        }
        return new Structure(ema9, ema20, found);
    }

    private static Swing swing(SwingTracker.Swing s, String confirmedAt) {
        return new Swing(s.bar().start().atZone(MarketTime.IST).toLocalTime().toString(), confirmedAt,
                LevelsService.round(s.price()), s.high());
    }

    /**
     * Each ma-cross trade on this index: the index at its entry decision, the last swing low (call) or high (put)
     * confirmed by then as its stop, and the target at the session config's reward-to-risk, as MaCross sets them.
     */
    private List<Map<String, Object>> maCrossStops(long sessionId, String underlying, Structure s) {
        Map<String, Double> rewardToRisk = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("select p.id, p.option_side, p.strategy_id, "
                + "to_char(p.closed_at at time zone '" + IST + "', 'HH24:MI') exit, c.spot, "
                + "to_char(c.at at time zone '" + IST + "', 'HH24:MI') at from trade.position p "
                + "join lateral (select spot, at from trade.candidate c where c.session_id = p.session_id "
                + "and c.underlying = p.underlying and c.strategy_id = p.strategy_id and c.action = 'ENTER' "
                + "and c.side = p.option_side and c.approved and c.at <= p.opened_at order by c.at desc limit 1) c on true "
                + "where p.session_id = ? and p.underlying = ? and p.strategy_id like 'ma-cross%' order by p.opened_at",
                sessionId, underlying)) {
            boolean call = "CE".equals(row.get("option_side"));
            double entry = number(row.get("spot"));
            String at = (String) row.get("at");
            Double configured = rewardToRisk.computeIfAbsent((String) row.get("strategy_id"), k -> rewardToRisk(sessionId, k));
            double r = Double.isNaN(configured) ? DEFAULT_REWARD_TO_RISK : configured;
            Double stop = stopAt(s.swings(), at, call);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("position", row.get("id"));
            t.put("side", row.get("option_side"));
            t.put("at", at);
            t.put("exit", row.get("exit"));
            t.put("entry", LevelsService.round(entry));
            t.put("stop", stop);
            t.put("target", stop == null ? null : LevelsService.round(entry + r * (entry - stop)));
            t.put("rewardToRisk", r);
            t.put("rewardToRiskAssumed", Double.isNaN(configured));        // the config file is not on this server
            out.add(t);
        }
        return out;
    }

    /** The last swing low (a call's stop) or high (a put's) confirmed at or before the decision minute. */
    static Double stopAt(List<Swing> swings, String at, boolean call) {
        Double stop = null;
        for (Swing w : swings) {
            if (w.high() != call && w.at().compareTo(at) <= 0) {
                stop = w.price();
            }
        }
        return stop;
    }

    /** The session's config value, or NaN when its file cannot be read here (the caller then assumes the default). */
    private double rewardToRisk(long sessionId, String strategyId) {
        try {
            String configs = jdbc.queryForObject("select configs::text from trade.session where id = ?", String.class, sessionId);
            Matcher m = Pattern.compile("\"(" + Pattern.quote(strategyId) + "\\.v[0-9a-z]+\\.yaml)\"").matcher(configs == null ? "" : configs);
            if (m.find()) {
                ThresholdConfig c = ThresholdConfig.load(Path.of("config", "strategy", m.group(1)));
                if (c.has("setup.reward_to_risk")) {
                    return c.getDouble("setup.reward_to_risk");
                }
            }
        } catch (RuntimeException e) {
            // the config file is not on this server
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------------------------------------ premium

    @GetMapping("/sessions/{id}/premium")
    Map<String, Object> premium(@PathVariable long id, @RequestParam long position) {
        SessionInfo session = session(id);
        return cached("premium|" + id + "|" + position, session.done(), SESSION_TTL_MS, () -> {
            List<Map<String, Object>> found = jdbc.queryForList("select p.underlying, p.symbol, p.quantity, p.average_cost, p.average_exit, "
                    + "to_char(p.opened_at at time zone '" + IST + "', 'HH24:MI') o, "
                    + "to_char(p.closed_at at time zone '" + IST + "', 'HH24:MI') c, s.session_date, s.feed "
                    + "from trade.position p join trade.session s on s.id = p.session_id where p.id = ? and p.session_id = ?",
                    position, id);
            if (found.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no position " + position + " in session " + id);
            }
            Map<String, Object> p = found.getFirst();
            Map<String, Object> out = new LinkedHashMap<>();
            String symbol = (String) p.get("symbol");
            out.put("symbol", symbol);
            Contract c = contract(symbol);
            if (c == null) {
                out.put("error", "not a single option contract");
                return out;
            }
            LocalDate day = ((java.sql.Date) p.get("session_date")).toLocalDate();
            String underlying = (String) p.get("underlying");
            boolean barDay = String.valueOf(p.get("feed")).contains("bars");
            List<Minute> bars = barDay ? candleOption(day, underlying, c) : tickOption(day, underlying, c);
            String source = barDay ? "bars" : "ticks";
            if (bars.isEmpty()) {
                bars = barDay ? tickOption(day, underlying, c) : candleOption(day, underlying, c);
                source = barDay ? "ticks" : "bars";
            }
            double entry = number(p.get("average_cost")), exit = number(p.get("average_exit"));
            long quantity = ((Number) p.get("quantity")).longValue();
            String opened = (String) p.get("o"), closed = (String) p.get("c");
            out.put("source", source);
            out.put("expiry", c.expiry().toString());
            out.put("strike", c.strike());
            out.put("side", c.side());
            out.put("quantity", quantity);
            out.put("entry", Map.of("t", opened, "price", LevelsService.round(entry)));
            out.put("exit", closed == null ? null : Map.of("t", closed, "price", LevelsService.round(exit)));
            out.put("bars", bars.stream().map(b -> List.of(b.t(), b.open(), b.high(), b.low(), b.close())).toList());
            out.put("iv", bars.stream().filter(b -> b.iv() != null).map(b -> List.of(b.t(), b.iv())).toList());
            out.put("excursion", excursion(bars, opened, closed, entry, quantity));
            return out;
        });
    }

    /** "NIFTY 25950 CE 30 DEC 25": the index, strike, side and expiry of one option. */
    record Contract(String underlying, double strike, String side, LocalDate expiry) {
    }

    private static final Pattern CONTRACT = Pattern.compile("^(\\S+) (\\d+(?:\\.\\d+)?) (CE|PE) (\\d{1,2} [A-Za-z]{3} \\d{2})$");
    private static final DateTimeFormatter EXPIRY = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("d MMM yy").toFormatter(Locale.ENGLISH);

    static Contract contract(String symbol) {
        Matcher m = symbol == null ? null : CONTRACT.matcher(symbol.trim());
        if (m == null || !m.matches()) {
            return null;
        }
        return new Contract(m.group(1), Double.parseDouble(m.group(2)), m.group(3), LocalDate.parse(m.group(4), EXPIRY));
    }

    /** One minute of an option: OHLC of its traded price and, from the own capture, the last implied volatility (%). */
    record Minute(String t, double open, double high, double low, double close, Double iv) {
    }

    private List<Minute> tickOption(LocalDate day, String underlying, Contract c) {
        return jdbc.query("select to_char(date_trunc('minute', recv_ts at time zone '" + IST + "'), 'HH24:MI') m, "
                        + "(array_agg(ltp order by recv_ts))[1], max(ltp), min(ltp), (array_agg(ltp order by recv_ts desc))[1], "
                        + "(array_agg(iv order by recv_ts desc) filter (where iv > 0))[1] from md.option_tick "
                        + "where underlying = ? and expiry = ? and strike = ? and option_type = ? and ltp > 0 "
                        + "and recv_ts >= ? and recv_ts < ? group by 1 order by 1",
                (rs, i) -> {
                    double iv = rs.getDouble(6);
                    boolean noIv = rs.wasNull();
                    return new Minute(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                            noIv ? null : LevelsService.round(iv < 3 ? iv * 100 : iv));    // a fraction or already %
                }, underlying, c.expiry(), c.strike(), c.side(), ts(day, OPEN), ts(day, CLOSE.plusMinutes(1)));
    }

    private List<Minute> candleOption(LocalDate day, String underlying, Contract c) {
        return jdbc.query("select to_char(bar_start at time zone '" + IST + "', 'HH24:MI'), open, high, low, close "
                        + "from hist.candle where underlying = ? and kind = 'OPTION' and expiry = ? and strike = ? "
                        + "and option_type = ? and bar_start >= ? and bar_start < ? order by bar_start",
                (rs, i) -> new Minute(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), null),
                underlying, c.expiry(), c.strike(), c.side(), ts(day, OPEN), ts(day, CLOSE.plusMinutes(1)));
    }

    /**
     * The best (MFE) and worst (MAE) premium while held, per unit and in rupees for the quantity, over the minute bars
     * from the entry minute to the exit minute inclusive (a minute's range can include seconds outside the trade).
     */
    static Map<String, Object> excursion(List<Minute> bars, String opened, String closed, double entry, long quantity) {
        Map<String, Object> out = new LinkedHashMap<>();
        String last = closed == null ? "99:99" : closed;
        Minute best = null, worst = null;
        for (Minute b : bars) {
            if (b.t().compareTo(opened) < 0 || b.t().compareTo(last) > 0) {
                continue;
            }
            if (best == null || b.high() > best.high()) {
                best = b;
            }
            if (worst == null || b.low() < worst.low()) {
                worst = b;
            }
        }
        if (best == null) {
            return out;
        }
        double mfe = Math.max(0, best.high() - entry), mae = Math.max(0, entry - worst.low());
        out.put("bestPrice", LevelsService.round(best.high()));
        out.put("bestAt", best.t());
        out.put("mfe", LevelsService.round(mfe));
        out.put("mfeRupees", Math.round(mfe * quantity));
        out.put("worstPrice", LevelsService.round(worst.low()));
        out.put("worstAt", worst.t());
        out.put("mae", LevelsService.round(mae));
        out.put("maeRupees", Math.round(mae * quantity));
        return out;
    }

    // ------------------------------------------------------------------------------------------ day context

    @GetMapping("/day-context")
    Map<String, Object> dayContext(@RequestParam String date, @RequestParam String underlying) {
        LocalDate day = LocalDate.parse(date);
        String u = underlying.toUpperCase(Locale.ROOT);
        boolean finished = ChartDb.finished(day);
        String kind = "day-context";
        if (finished) {
            Map<String, Object> kept = db.loadJson(kind, day, u);
            if (kept != null) {
                return kept;
            }
        }
        return cached("context|" + day + "|" + u, finished, CONTEXT_TTL_MS, () -> {
            Map<String, Object> value = computeContext(day, u);
            if (finished && complete(value)) {                // a day whose bars arrive later is computed again
                db.saveJson(kind, day, u, value);
            }
            return value;
        });
    }

    /** Index, VIX and straddle all present: nothing a later bar fetch could still add. */
    static boolean complete(Map<String, Object> context) {
        return context.get("index") != null && !((List<?>) context.get("vix")).isEmpty()
                && context.get("straddle") instanceof List<?> s && !s.isEmpty();
    }

    private Map<String, Object> computeContext(LocalDate day, String u) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("underlying", u);
        List<LevelsService.Bar> index = LevelsService.minuteBars(jdbc, day, u).bars();
        // VIX by minute and its daily history
        List<List<Object>> vix = vixMinutes(day);
        out.put("vix", vix);
        TreeMap<LocalDate, Double> closes = vixCloses(day);
        Map<String, Object> v = new LinkedHashMap<>();
        if (!vix.isEmpty()) {
            double open = (Double) vix.getFirst().get(1), last = (Double) vix.getLast().get(1);
            v.put("open", open);
            v.put("last", last);
            v.put("high", vix.stream().mapToDouble(p -> (Double) p.get(1)).max().orElse(open));
            v.put("low", vix.stream().mapToDouble(p -> (Double) p.get(1)).min().orElse(open));
            v.put("percentile1y", percentile(closes.values().stream().toList(), open));
        }
        if (!closes.isEmpty()) {
            v.put("prevClose", closes.lastEntry().getValue());
            v.put("prevDay", closes.lastKey().toString());
        }
        v.put("historyDays", closes.size());
        out.put("vixDay", v);
        if (index.isEmpty()) {
            return out;
        }
        // the day's index and the gap
        LevelsService.Bar first = index.getFirst(), lastBar = index.getLast();
        double high = index.stream().mapToDouble(LevelsService.Bar::high).max().orElse(first.high());
        double low = index.stream().mapToDouble(LevelsService.Bar::low).min().orElse(first.low());
        Double prevClose = prevClose(day, u);
        Map<String, Object> ix = new LinkedHashMap<>();
        ix.put("open", first.open());
        ix.put("high", high);
        ix.put("low", low);
        ix.put("last", lastBar.close());
        ix.put("lastAt", lastBar.t().toString());
        ix.put("prevClose", prevClose);
        ix.put("gapPct", prevClose == null ? null : LevelsService.round((first.open() - prevClose) / prevClose * 100));
        out.put("index", ix);
        // the expected daily move from VIX at the open (VIX / sqrt(252), one standard deviation)
        Double vixOpen = (Double) v.get("open");
        Double expected = vixOpen == null ? null : LevelsService.round(first.open() * vixOpen / 100 / Math.sqrt(252));
        out.put("expectedMoveVix", expected);
        out.put("shape", shape(first.open(), high, low, lastBar.close(), expected, ChartDb.finished(day)));
        // the nearest expiry and the ATM straddle by minute
        LocalDate expiry = nearestExpiry(day, u);
        if (expiry != null) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("expiry", expiry.toString());
            e.put("calendarDays", Duration.between(day.atStartOfDay(), expiry.atStartOfDay()).toDays());
            e.put("expiryDay", expiry.equals(day));
            out.put("expiry", e);
            double step = STRIKE_STEP.getOrDefault(u, 50.0);
            Map<String, Map<String, Double>> quotes = optionCloses(day, u, expiry, low - 2 * step, high + 2 * step);
            out.put("straddle", straddle(day, index, quotes, step, expiry));
        }
        out.put("participants", participants(day));
        return out;
    }

    private List<List<Object>> vixMinutes(LocalDate day) {
        String minute = "to_char(date_trunc('minute', recv_ts at time zone '" + IST + "'), 'HH24:MI')";
        List<List<Object>> ticks = jdbc.query("select " + minute + ", (array_agg(price order by recv_ts desc, src_seq desc))[1] "
                        + "from md.index_tick where underlying = ? and recv_ts >= ? and recv_ts < ? group by 1 order by 1",
                (rs, i) -> List.<Object>of(rs.getString(1), rs.getDouble(2)), VIX, ts(day, OPEN), ts(day, CLOSE));
        if (!ticks.isEmpty()) {
            return ticks;
        }
        List<List<Object>> bars = jdbc.query("select to_char(bar_start at time zone '" + IST + "', 'HH24:MI'), close "
                        + "from hist.candle where underlying = ? and kind = 'VIX' and bar_start >= ? and bar_start < ? order by bar_start",
                (rs, i) -> List.<Object>of(rs.getString(1), rs.getDouble(2)), VIX, ts(day, OPEN), ts(day, CLOSE));
        if (!bars.isEmpty()) {
            return bars;
        }
        return jdbc.query("select to_char(bar_start at time zone '" + IST + "', 'HH24:MI'), close from ref.index_candle "
                        + "where symbol = ? and timeframe = '1minute' and session_date = ? order by bar_start",
                (rs, i) -> List.<Object>of(rs.getString(1), rs.getDouble(2)), VIX, day);
    }

    /** VIX daily closes for the year before {@code day}: the stored one-minute bars, then the reference candles. */
    private TreeMap<LocalDate, Double> vixCloses(LocalDate day) {
        TreeMap<LocalDate, Double> closes = new TreeMap<>();
        LocalDate from = day.minusYears(1);
        jdbc.query("select distinct on (1) (bar_start at time zone '" + IST + "')::date, close from hist.candle "
                        + "where underlying = ? and kind = 'VIX' and bar_start >= ? and bar_start < ? order by 1, bar_start desc",
                rs -> {
                    closes.put(rs.getObject(1, LocalDate.class), rs.getDouble(2));
                }, VIX, ts(from, LocalTime.MIN), ts(day, LocalTime.MIN));
        jdbc.query("select distinct on (session_date) session_date, close from ref.index_candle where symbol = ? "
                        + "and session_date >= ? and session_date < ? order by session_date, timeframe, bar_start desc",
                rs -> {
                    closes.putIfAbsent(rs.getObject(1, LocalDate.class), rs.getDouble(2));
                }, VIX, from, day);
        return closes;
    }

    /** The share (%) of {@code history} at or below {@code value}; null without a year's worth of days. */
    static Double percentile(List<Double> history, double value) {
        if (history.size() < 120) {
            return null;
        }
        long below = history.stream().filter(h -> h <= value).count();
        return LevelsService.round(100.0 * below / history.size());
    }

    /** The previous session's close: as the feed sent it with the day's first index tick, else the last stored bar. */
    private Double prevClose(LocalDate day, String u) {
        List<Double> fromTicks = jdbc.queryForList("select prev_close from md.index_tick where underlying = ? and recv_ts >= ? "
                + "and recv_ts < ? and prev_close > 0 order by recv_ts limit 1", Double.class, u, ts(day, OPEN), ts(day, CLOSE));
        if (!fromTicks.isEmpty()) {
            return fromTicks.getFirst();
        }
        List<Double> fromBars = jdbc.queryForList("select close from hist.candle where underlying = ? and kind = 'INDEX' "
                + "and bar_start >= ? and bar_start < ? order by bar_start desc limit 1", Double.class, u,
                ts(day.minusDays(10), LocalTime.MIN), ts(day, LocalTime.MIN));
        return fromBars.isEmpty() ? null : fromBars.getFirst();
    }

    /**
     * The day's shape, known only at its close (hindsight; never a reason a strategy had): the body's share of the range
     * and the range against the VIX-expected move. Trend: body at least 60 % of the range and the range at least the
     * expected move; range: body under 30 %; otherwise mixed.
     */
    static Map<String, Object> shape(double open, double high, double low, double close, Double expected, boolean finished) {
        Map<String, Object> out = new LinkedHashMap<>();
        double range = high - low;
        double body = range == 0 ? 0 : Math.abs(close - open) / range;
        Double vsExpected = expected == null || expected == 0 ? null : LevelsService.round(range / expected);
        out.put("range", LevelsService.round(range));
        out.put("bodyPct", LevelsService.round(body * 100));
        out.put("rangeVsExpected", vsExpected);
        out.put("direction", close > open ? "up" : close < open ? "down" : "flat");
        String type = body >= 0.6 && vsExpected != null && vsExpected >= 1 ? "trend" : body < 0.3 ? "range" : "mixed";
        out.put("type", type);
        out.put("final", finished);
        return out;
    }

    private LocalDate nearestExpiry(LocalDate day, String u) {
        LocalDate fromTicks = jdbc.query("select min(expiry) from md.option_tick where underlying = ? and expiry >= ? "
                        + "and recv_ts >= ? and recv_ts < ?",
                rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null, u, day, ts(day, OPEN), ts(day, CLOSE));
        if (fromTicks != null) {
            return fromTicks;
        }
        return jdbc.query("select min(expiry) from hist.candle where underlying = ? and kind = 'OPTION' and expiry >= ? "
                        + "and bar_start >= ? and bar_start < ?",
                rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null, u, day, ts(day, OPEN), ts(day, CLOSE));
    }

    /** Minute closes by "strike|CE" then "HH:mm": the own capture's last traded price, else the stored bars. */
    private Map<String, Map<String, Double>> optionCloses(LocalDate day, String u, LocalDate expiry, double lo, double hi) {
        Map<String, Map<String, Double>> out = new HashMap<>();
        org.springframework.jdbc.core.RowCallbackHandler put = rs -> out
                .computeIfAbsent(rs.getDouble(2) + "|" + rs.getString(3), k -> new HashMap<>()).put(rs.getString(1), rs.getDouble(4));
        jdbc.query("select to_char(date_trunc('minute', recv_ts at time zone '" + IST + "'), 'HH24:MI'), strike, option_type, "
                        + "(array_agg(ltp order by recv_ts desc))[1] from md.option_tick where underlying = ? and expiry = ? "
                        + "and strike between ? and ? and option_type in ('CE', 'PE') and ltp > 0 and recv_ts >= ? and recv_ts < ? "
                        + "group by 1, 2, 3", put, u, expiry, lo, hi, ts(day, OPEN), ts(day, CLOSE));
        if (out.isEmpty()) {
            jdbc.query("select to_char(bar_start at time zone '" + IST + "', 'HH24:MI'), strike, option_type, close "
                            + "from hist.candle where underlying = ? and kind = 'OPTION' and expiry = ? and strike between ? and ? "
                            + "and option_type in ('CE', 'PE') and bar_start >= ? and bar_start < ?",
                    put, u, expiry, lo, hi, ts(day, OPEN), ts(day, CLOSE));
        }
        return out;
    }

    /**
     * The ATM straddle each minute (the strike nearest the index; each leg's last price at or before the minute) and the
     * volatility it implies (Brenner-Subrahmanyam: straddle ~ 0.8 S sigma sqrt(T), T in years to the 15:30 expiry close),
     * omitted in the last 30 minutes before expiry, where it explodes.
     */
    static List<List<Object>> straddle(LocalDate day, List<LevelsService.Bar> index, Map<String, Map<String, Double>> quotes,
                                       double step, LocalDate expiry) {
        Map<String, TreeMap<String, Double>> sorted = new HashMap<>();
        quotes.forEach((k, v) -> sorted.put(k, new TreeMap<>(v)));
        Instant close = expiry.atTime(CLOSE).atZone(MarketTime.IST).toInstant();
        List<List<Object>> out = new ArrayList<>();
        for (LevelsService.Bar b : index) {
            double strike = Math.round(b.close() / step) * step;
            String t = b.t().toString();
            Double ce = lastAtOrBefore(sorted.get(strike + "|CE"), t), pe = lastAtOrBefore(sorted.get(strike + "|PE"), t);
            if (ce == null || pe == null) {
                continue;
            }
            double s = ce + pe;
            double years = Duration.between(day.atTime(b.t()).atZone(MarketTime.IST).toInstant(), close).toMinutes()
                    / (365.25 * 24 * 60);
            Double iv = years * 365.25 * 24 * 60 < 30 ? null : LevelsService.round(s / (0.7979 * b.close() * Math.sqrt(years)) * 100);
            List<Object> row = new ArrayList<>(List.of(t, LevelsService.round(s), strike));
            row.add(iv);
            out.add(row);
        }
        return out;
    }

    private static Double lastAtOrBefore(TreeMap<String, Double> series, String t) {
        if (series == null) {
            return null;
        }
        Map.Entry<String, Double> e = series.floorEntry(t);
        return e == null ? null : e.getValue();
    }

    /**
     * Index futures and options positions by participant (FII, DII, Pro, Client) as published after the previous
     * session's close (NSE), with the change against the session before it.
     */
    private List<Map<String, Object>> participants(LocalDate day) {
        List<LocalDate> days = jdbc.queryForList("select distinct session_date from ref.participant_oi where session_date < ? "
                + "order by 1 desc limit 2", LocalDate.class, day);
        if (days.isEmpty()) {
            return List.of();
        }
        Map<String, double[]> before = new HashMap<>();
        if (days.size() > 1) {
            jdbc.query("select client_type, future_index_long, future_index_short from ref.participant_oi where session_date = ?",
                    rs -> {
                        before.put(rs.getString(1), new double[] {rs.getDouble(2), rs.getDouble(3)});
                    }, days.get(1));
        }
        return jdbc.query("select client_type, future_index_long, future_index_short, option_index_call_long, "
                        + "option_index_call_short, option_index_put_long, option_index_put_short from ref.participant_oi "
                        + "where session_date = ? and client_type <> 'TOTAL' order by client_type",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String who = rs.getString(1);
                    double pct = longPct(rs.getDouble(2), rs.getDouble(3));
                    double[] b = before.get(who);
                    m.put("who", who);
                    m.put("asOf", days.getFirst().toString());
                    m.put("indexFutLongPct", LevelsService.round(pct));
                    m.put("indexFutLongPctChange", b == null ? null : LevelsService.round(pct - longPct(b[0], b[1])));
                    m.put("indexCallNet", Math.round(rs.getDouble(4) - rs.getDouble(5)));
                    m.put("indexPutNet", Math.round(rs.getDouble(6) - rs.getDouble(7)));
                    return m;
                }, days.getFirst());
    }

    static double longPct(double longs, double shorts) {
        return longs + shorts == 0 ? Double.NaN : 100 * longs / (longs + shorts);
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** A session's day and whether it has ended (a replay of a past day may still be adding trades). */
    record SessionInfo(LocalDate day, boolean done) {
    }

    private SessionInfo session(long id) {
        List<SessionInfo> found = jdbc.query("select session_date, ended_at is not null from trade.session where id = ?",
                (rs, i) -> new SessionInfo(rs.getObject(1, LocalDate.class), rs.getBoolean(2)), id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no session " + id);
        }
        return found.getFirst();
    }

    static final long SESSION_TTL_MS = 30_000;
    /** Today's context reads the whole day's option ticks so far (seconds on a tick day): at most once a minute. */
    static final long CONTEXT_TTL_MS = 60_000;

    /** A finished answer is kept (bounded); one still changing for {@code ttlMs}. */
    private Map<String, Object> cached(String key, boolean keep, long ttlMs, java.util.function.Supplier<Map<String, Object>> compute) {
        long now = System.currentTimeMillis();
        Cached hit = cache.get(key);
        if (hit != null && (hit.kept() || now - hit.at() < ttlMs)) {
            return hit.value();
        }
        Map<String, Object> value = compute.get();
        if (cache.size() > 500) {
            cache.clear();
        }
        cache.put(key, new Cached(now, keep, value));
        return value;
    }

    private static double number(Object o) {
        return o == null ? Double.NaN : ((Number) o).doubleValue();
    }

    private static Timestamp ts(LocalDate day, LocalTime time) {
        return Timestamp.from(day.atTime(time).atZone(MarketTime.IST).toInstant());
    }
}
