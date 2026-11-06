package com.autotrade.trading;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autotrade.core.time.MarketTime;

/**
 * The index pages' zoomable chart (6 Nov 2026, user: "I want this zoomable expandable chart", a TradingView screenshot):
 * the last {@code days} sessions up to {@code until} as one continuous minute series per day (the own tick capture, else
 * the stored one-minute bars), each minute with the near-month futures volume traded in it (an index has none), and the
 * primary live PAPER account's trades on the index in that range. The client aggregates to 3, 5 or 15 minutes. Finished
 * days are kept in the chart cache; read on the charts pool only.
 */
@RestController
class IndexBarsController {

    private static final String KIND = "index-bars-v1";
    static final int MAX_DAYS = 10;
    private static final int LOOKBACK = 25;                  // calendar days searched for that many sessions

    private final JdbcTemplate jdbc;
    private final ChartDb db;
    private final String account;
    private final Map<String, Cached> today = new ConcurrentHashMap<>();

    private record Cached(long at, Map<String, Object> value) {
    }

    IndexBarsController(ChartDb db, @Value("${autotrade.trading.account:PAPER-1}") String account) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.account = account;
    }

    @GetMapping("/api/index-bars")
    Map<String, Object> indexBars(@RequestParam String underlying, @RequestParam(defaultValue = "3") int days,
                                  @RequestParam(required = false) String until) {
        String u = underlying.trim().toUpperCase(java.util.Locale.ROOT);
        int want = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDate last = until == null || until.isBlank() ? LocalDate.now(MarketTime.IST) : LocalDate.parse(until);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int back = 0; back < LOOKBACK && out.size() < want; back++) {
            LocalDate day = last.minusDays(back);
            if (day.getDayOfWeek().getValue() >= 6) {
                continue;
            }
            Map<String, Object> d = day(day, u);
            if (!((List<?>) d.get("bars")).isEmpty()) {
                out.addFirst(d);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("underlying", u);
        result.put("days", out);
        result.put("trades", out.isEmpty() ? List.of() : trades(u, LocalDate.parse((String) out.getFirst().get("date")),
                LocalDate.parse((String) out.getLast().get("date"))));
        return result;
    }

    /** One session: kept when finished, else at most 15 s old (a running day grows every minute). */
    Map<String, Object> day(LocalDate day, String u) {
        if (ChartDb.finished(day)) {
            Map<String, Object> kept = db.loadJson(KIND, day, u);
            if (kept != null) {
                return kept;
            }
            Map<String, Object> value = compute(day, u);
            if ("ticks".equals(value.get("source")) && !((List<?>) value.get("bars")).isEmpty()) {
                db.saveJson(KIND, day, u, value);        // kept against the day's tick load (bar days are cheap)
            }
            return value;
        }
        String key = day + "|" + u;
        Cached hit = today.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at() < 15_000) {
            return hit.value();
        }
        Map<String, Object> value = compute(day, u);
        today.put(key, new Cached(now, value));
        return value;
    }

    private Map<String, Object> compute(LocalDate day, String u) {
        LevelsService.MinuteBars minutes = LevelsService.minuteBars(jdbc, day, u);
        Map<LocalTime, Long> volume = minutes.fromBars() ? barVolume(day, u) : tickVolume(day, u);
        List<Object[]> bars = new ArrayList<>();
        for (LevelsService.Bar b : minutes.bars()) {
            bars.add(new Object[] {b.t().toString(), b.open(), b.high(), b.low(), b.close(), volume.getOrDefault(b.t(), 0L)});
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("source", minutes.fromBars() ? "bars" : "ticks");
        out.put("bars", bars);
        return out;
    }

    private static Timestamp at(LocalDate day, int h, int m) {
        return Timestamp.from(day.atTime(h, m).atZone(MarketTime.IST).toInstant());
    }

    /**
     * Near-month futures volume per minute from the own capture: the cumulative day volume at each minute's last tick
     * less the previous minute's (the first minute: less the session's first tick).
     */
    private Map<LocalTime, Long> tickVolume(LocalDate day, String u) {
        Map<LocalTime, Long> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList("with near as (select min(expiry) e from md.future_tick where "
                + "underlying = ? and recv_ts >= ? and recv_ts < ?), m as (select date_trunc('minute', recv_ts) t, "
                + "(array_agg(volume order by recv_ts desc))[1] last, (array_agg(volume order by recv_ts))[1] first "
                + "from md.future_tick, near where underlying = ? and expiry = near.e and recv_ts >= ? and recv_ts < ? "
                + "and volume is not null group by 1) select (t at time zone 'Asia/Kolkata')::time t, "
                + "last - coalesce(lag(last) over (order by t), first) v from m order by t",
                u, at(day, 9, 15), at(day, 15, 30), u, at(day, 9, 15), at(day, 15, 30));
        for (Map<String, Object> r : rows) {
            long v = r.get("v") == null ? 0 : ((Number) r.get("v")).longValue();
            out.put(((java.sql.Time) r.get("t")).toLocalTime(), Math.max(0, v));
        }
        return out;
    }

    /** Near-month futures volume per minute from the stored one-minute bars (bar days). */
    private Map<LocalTime, Long> barVolume(LocalDate day, String u) {
        Map<LocalTime, Long> out = new LinkedHashMap<>();
        jdbc.query("select (bar_start at time zone 'Asia/Kolkata')::time t, volume from hist.candle where underlying = ? "
                + "and kind = 'FUTURE' and bar_start >= ? and bar_start < ? and expiry = (select min(expiry) from hist.candle "
                + "where underlying = ? and kind = 'FUTURE' and bar_start >= ? and bar_start < ?) order by bar_start",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getObject(1, LocalTime.class), rs.getLong(2)),
                u, at(day, 9, 15), at(day, 15, 30), u, at(day, 9, 15), at(day, 15, 30));
        return out;
    }

    /** The primary live PAPER account's filled trades on the index between the two days (IST minutes). */
    private List<Map<String, Object>> trades(String u, LocalDate from, LocalDate to) {
        return jdbc.queryForList("select s.session_date::text date, p.strategy_id strategy, p.option_side side, p.symbol, "
                + "to_char(p.opened_at at time zone 'Asia/Kolkata', 'HH24:MI') opened, "
                + "to_char(p.closed_at at time zone 'Asia/Kolkata', 'HH24:MI') closed, round(p.net::numeric, 2) net "
                + "from trade.position p join trade.session s on s.id = p.session_id where s.mode = 'PAPER_LIVE' "
                + "and s.account = ? and s.session_date between ? and ? and p.underlying = ? and p.quantity > 0 "
                + "order by p.opened_at", account, from, to, u);
    }
}
