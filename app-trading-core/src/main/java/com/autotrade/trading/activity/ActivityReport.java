package com.autotrade.trading.activity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the activity pages read: who is signed in now, on which devices and browsers, with how many tabs, and the time
 * on screen per day (stored totals, with today worked out live from the minutes). Reads only.
 */
public final class ActivityReport {

    /** A sign-in counts as online while it made a request this recently. */
    static final Duration ONLINE = Duration.ofMinutes(2);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ActivityReport(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    private static final String LOGIN_COLUMNS = "l.id, l.signed_in_at, l.last_seen_at, l.ended_at, l.end_reason, "
            + "case when masklen(l.ip) = case when family(l.ip) = 4 then 32 else 128 end then host(l.ip) "
            + "else text(l.ip) end as ip, l.browser, l.browser_version, l.os, l.os_version, l.device_type, l.installed_app";

    /** Every user seen in the last {@code days} days: online now, open sign-ins with their tabs, time per day. */
    public Map<String, Object> overview(int days) {
        int n = Math.max(1, Math.min(days, 180));
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ActivityRecorder.IST);
        Timestamp since = Timestamp.from(now.minus(Duration.ofDays(n)));
        List<Map<String, Object>> users = jdbc.queryForList("select s.subject, coalesce(u.display_name, s.subject) as name "
                + "from (select subject from acct.user_login where last_seen_at > ? union "
                + "select subject from acct.user_activity_day where day > ?) s "
                + "left join acct.app_user u on u.external_subject = s.subject order by 1", since, today.minusDays(n));
        List<Map<String, Object>> open = jdbc.queryForList("select l.subject, " + LOGIN_COLUMNS + ", "
                + "(select count(*) from acct.user_tab t where t.login_id = l.id and t.closed_at is null "
                + " and t.last_beat_at > ?) as tabs, "
                + "(select string_agg(distinct t.last_path, ', ') from acct.user_tab t where t.login_id = l.id "
                + " and t.closed_at is null and t.last_beat_at > ?) as paths "
                + "from acct.user_login l where l.ended_at is null order by l.last_seen_at desc",
                Timestamp.from(now.minus(ActivityRecorder.TAB_GONE)), Timestamp.from(now.minus(ActivityRecorder.TAB_GONE)));
        Map<String, Object> lastSeenBy = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("select subject, max(last_seen_at) as last_seen from acct.user_login "
                + "group by subject")) {
            lastSeenBy.put((String) r.get("subject"), r.get("last_seen"));
        }
        Map<String, Map<String, Object>> todayBy = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(ActivityRecorder.DAY_TOTALS, today, today)) {
            todayBy.put((String) r.get("subject"), r);
        }
        Map<String, List<Map<String, Object>>> daysBy = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("select subject, day, logins, devices, tabs, visible_seconds, "
                + "active_seconds from acct.user_activity_day where day > ? and day < ? order by day", today.minusDays(n),
                today)) {
            Map<String, Object> d = new LinkedHashMap<>(r);
            d.remove("subject");
            d.put("day", asDate(r.get("day")).toString());
            daysBy.computeIfAbsent((String) r.get("subject"), k -> new ArrayList<>()).add(d);
        }
        Instant onlineSince = now.minus(ONLINE);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> u : users) {
            String subject = (String) u.get("subject");
            List<Map<String, Object>> mine = open.stream().filter(o -> subject.equals(o.get("subject"))).toList();
            Map<String, Object> t = todayBy.get(subject);
            List<Map<String, Object>> series = new ArrayList<>(daysBy.getOrDefault(subject, List.of()));
            if (t != null) {
                Map<String, Object> live = new LinkedHashMap<>();
                live.put("day", today.toString());
                for (String k : List.of("logins", "devices", "tabs", "visible_seconds", "active_seconds")) {
                    live.put(k, t.get(k));
                }
                series.add(live);
            }
            String weekFrom = today.minusDays(6).toString();
            long week = series.stream().filter(r -> ((String) r.get("day")).compareTo(weekFrom) >= 0)
                    .mapToLong(r -> ((Number) r.get("visible_seconds")).longValue()).sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subject", subject);
            row.put("name", u.get("name"));
            row.put("online", mine.stream().anyMatch(o -> ((Timestamp) o.get("last_seen_at")).toInstant().isAfter(onlineSince)));
            row.put("openSignIns", mine.size());
            row.put("openTabs", mine.stream().mapToLong(o -> ((Number) o.get("tabs")).longValue()).sum());
            row.put("todayVisibleSeconds", t == null ? 0 : ((Number) t.get("visible_seconds")).longValue());
            row.put("todayActiveSeconds", t == null ? 0 : ((Number) t.get("active_seconds")).longValue());
            row.put("weekVisibleSeconds", week);
            row.put("lastSeen", lastSeenBy.get(subject));
            row.put("signIns", mine.stream().map(ActivityReport::withoutSubject).toList());
            row.put("days", series);
            out.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asOf", now);
        result.put("days", n);
        result.put("users", out);
        return result;
    }

    /** One user's sign-ins in the last {@code days} days, newest first, with their tabs' time. */
    public List<Map<String, Object>> signIns(String subject, int days, boolean withChain) {
        Instant now = clock.instant();
        return jdbc.queryForList("select " + LOGIN_COLUMNS + (withChain ? ", l.forwarded_chain" : "")
                + ", count(t.id) as tabs, coalesce(sum(t.visible_seconds), 0) as visible_seconds, "
                + "coalesce(sum(t.active_seconds), 0) as active_seconds from acct.user_login l "
                + "left join acct.user_tab t on t.login_id = l.id where l.subject = ? and l.last_seen_at > ? "
                + "group by l.id order by l.signed_in_at desc limit 200",
                subject, Timestamp.from(now.minus(Duration.ofDays(Math.max(1, Math.min(days, 180))))));
    }

    /** The signed-in user's own sign-ins (30 days), the current one marked. */
    public List<Map<String, Object>> devices(String subject, String currentKey) {
        Instant now = clock.instant();
        return jdbc.queryForList("select " + LOGIN_COLUMNS + ", (l.login_key = ?) as current "
                + "from acct.user_login l where l.subject = ? and l.last_seen_at > ? order by l.signed_in_at desc limit 50",
                currentKey == null ? "" : currentKey, subject, Timestamp.from(now.minus(Duration.ofDays(30))));
    }

    private static Map<String, Object> withoutSubject(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.remove("subject");
        return m;
    }

    private static Object asDate(Object d) {
        return d instanceof java.sql.Date s ? s.toLocalDate() : d;
    }

    /** The day's live totals per user (today's row of the overview), for tests and the status page. */
    List<Map<String, Object>> dayTotals(LocalDate day) {
        return jdbc.queryForList(ActivityRecorder.DAY_TOTALS, day, day);
    }
}
