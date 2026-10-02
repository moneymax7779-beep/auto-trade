package com.autotrade.trading;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autotrade.core.time.MarketTime;

/**
 * One strike's call and put by minute from the own capture (md.option_tick), for the surge chart's
 * premium pane: the last mid (best bid / ask) and last traded price of each minute. Display only.
 * Days before the own capture began have no rows; zt-tiger-v2 is not read here.
 */
@RestController
class PremiumController {

    private static final String IST = "Asia/Kolkata";

    private record Cached(long at, Map<String, Object> value) {
    }

    private final JdbcTemplate jdbc;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    PremiumController(DataSource target) {
        this.jdbc = new JdbcTemplate(target);
    }

    @GetMapping("/api/premium")
    Map<String, Object> premium(@RequestParam(required = false) String date, @RequestParam String underlying,
                                @RequestParam String expiry, @RequestParam double strike) {
        LocalDate day = date == null ? LocalDate.now(MarketTime.IST) : LocalDate.parse(date);
        String u = underlying.toUpperCase(Locale.ROOT);
        String key = day + "|" + u + "|" + expiry + "|" + strike;
        boolean today = day.equals(LocalDate.now(MarketTime.IST));
        long now = System.currentTimeMillis();
        Cached hit = cache.get(key);
        if (hit != null && (!today || now - hit.at() < 30_000)) {
            return hit.value();
        }
        Map<String, Object> value = compute(day, u, LocalDate.parse(expiry), strike);
        if (cache.size() > 500) {
            cache.clear();
        }
        cache.put(key, new Cached(now, value));
        return value;
    }

    private Map<String, Object> compute(LocalDate day, String underlying, LocalDate expiry, double strike) {
        Map<String, List<Object>> sides = new LinkedHashMap<>();
        sides.put("CE", new ArrayList<>());
        sides.put("PE", new ArrayList<>());
        var open = day.atTime(LocalTime.of(9, 15)).atZone(MarketTime.IST).toOffsetDateTime();
        var close = day.atTime(LocalTime.of(15, 31)).atZone(MarketTime.IST).toOffsetDateTime();
        // recv_ts range (not session_date) so the (underlying, expiry, strike, type, recv_ts) index serves it
        jdbc.query("select to_char(date_trunc('minute', recv_ts at time zone '" + IST + "'), 'HH24:MI') m, option_type, "
                        + "(array_agg(case when bid_px[1] > 0 and ask_px[1] > 0 then (bid_px[1] + ask_px[1]) / 2 end "
                        + "order by recv_ts desc))[1] mid, (array_agg(ltp order by recv_ts desc))[1] ltp "
                        + "from md.option_tick where underlying = ? and expiry = ? and strike = ? and option_type in ('CE', 'PE') "
                        + "and recv_ts >= ? and recv_ts < ? group by 1, 2 order by 1",
                rs -> {
                    double mid = rs.getDouble("mid");
                    boolean noMid = rs.wasNull();
                    double ltp = rs.getDouble("ltp");
                    sides.get(rs.getString("option_type")).add(List.of(rs.getString("m"),
                            LevelsService.round(noMid ? ltp : mid), LevelsService.round(ltp)));
                }, underlying, expiry, strike, open, close);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("underlying", underlying);
        out.put("expiry", expiry.toString());
        out.put("strike", strike);
        out.putAll(sides);
        return out;
    }
}
