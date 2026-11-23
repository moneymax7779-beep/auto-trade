package com.autotrade.trading.activity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Records who is signed in, from which browser, with how many tabs open and for how long the app was on screen (plan
 * docs/plans/2026-11-23-user-activity-logging-plan.md; user 23 Nov 2026). trading-core also runs the PAPER engine, so
 * nothing here touches a request's or the engine's time: callers only append to a bounded queue (full: the event is
 * dropped and counted) or overwrite a "last seen" stamp in memory, and one background thread writes everything every
 * {@link #FLUSH} in short statements. A database outage loses activity rows, never anything else.
 * <p>
 * Time comes from the page (the seconds it was on screen and in use since its last beat) and is clamped here to the
 * wall time since that tab's previous beat, at most {@link #MAX_BEAT_SECONDS}, so a broken or edited page cannot add time.
 * Each beat's seconds are spread over the minutes it covers into {@code acct.user_minute}, capped at 60 a minute, so a
 * user's tabs side by side count once.
 */
public final class ActivityRecorder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ActivityRecorder.class);

    public enum EndReason { LOGOUT, IDLE, MAX_AGE, REVOKED }

    /** The HttpSession attribute holding the sign-in's key. */
    public static final String SESSION_KEY = "autotrade.activity.login";
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int MAX_BEAT_SECONDS = 90;
    static final int QUEUE = 10_000;
    static final Duration FLUSH = Duration.ofSeconds(5);
    /** A tab with no beat for this long has closed (pages beat every 60 s, hidden ones throttled to about that). */
    static final Duration TAB_GONE = Duration.ofMinutes(3);
    /** New tab ids a sign-in may start in a day (code review: made-up ids would each add a first beat's time). */
    static final int MAX_TABS_A_DAY = 40;
    /** Days of daily totals worked out again from the raw rows at start (they are kept {@code rawDays}). */
    static final int BACKFILL_DAYS = 7;

    record SignIn(String key, String subject, Instant signedInAt, Instant at, String ip, String chain, String userAgent,
                  UserAgents.Device device) {
    }

    record Beat(String key, String subject, UUID tab, Instant at, int visible, int active, String path, boolean standalone,
                boolean closing) {
    }

    record End(String key, EndReason reason, Instant at) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ActivityProperties props;
    private final boolean enabled;
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE);
    private final ConcurrentHashMap<String, Instant> seen = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastBeat = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> newTabs = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final ScheduledExecutorService writer;
    private Instant swept = Instant.EPOCH;
    private Instant rolled = Instant.EPOCH;
    private LocalDate retained;

    private ActivityRecorder(DataSource dataSource, ActivityProperties props, Clock clock, boolean enabled, boolean thread) {
        this.jdbc = dataSource == null ? null : new JdbcTemplate(dataSource);
        this.props = props;
        this.clock = clock;
        this.enabled = enabled;
        if (thread) {
            writer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "activity-writer");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            writer.scheduleWithFixedDelay(this::flushQuietly, FLUSH.toMillis(), FLUSH.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            writer = null;
        }
    }

    /** Recording, with its own writer thread. */
    public static ActivityRecorder start(DataSource dataSource, ActivityProperties props, Clock clock) {
        return new ActivityRecorder(dataSource, props, clock, true, true);
    }

    /** Recording without a thread: tests call {@link #flush()} themselves. */
    public static ActivityRecorder manual(DataSource dataSource, ActivityProperties props, Clock clock) {
        return new ActivityRecorder(dataSource, props, clock, true, false);
    }

    /** Off (sign-in off): every call does nothing. */
    public static ActivityRecorder disabled() {
        return new ActivityRecorder(null, new ActivityProperties(false, null, null, null, null, null), Clock.systemUTC(),
                false, false);
    }

    public boolean enabled() {
        return enabled;
    }

    Instant now() {
        return clock.instant();
    }

    /** The sign-in's key: sha256 of Keycloak's SSO session id, else of the user and the time they authenticated. */
    public static String loginKey(String sid, String subject, Instant authTime) {
        String basis = sid != null && !sid.isBlank() ? "sid:" + sid : "auth:" + subject + "|" + authTime;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------- callers

    void signIn(String key, String subject, Instant signedInAt, String ip, String chain, String userAgent,
                UserAgents.Device device) {
        if (enabled) {
            offer(new SignIn(key, subject, signedInAt == null ? now() : signedInAt, now(), ip, chain,
                    cut(userAgent, 400), device));
        }
    }

    /** A request of the sign-in (any tab, polls too): only its time, kept in memory until the next write. */
    void seen(String key) {
        if (enabled && key != null) {
            seen.put(key, now());
        }
    }

    /**
     * A tab's beat: the seconds on screen ({@code visible}) and in use ({@code active}) since its last beat, as the page
     * counted them; clamped here. Returns the seconds credited (visible, active).
     */
    int[] beat(String key, String subject, UUID tab, int visible, int active, String path, boolean standalone,
               boolean closing) {
        if (!enabled || key == null || tab == null) {
            return new int[] {0, 0};
        }
        Instant at = now();
        String tabKey = key + "|" + tab;
        if (!lastBeat.containsKey(tabKey)
                && newTabs.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet()
                > MAX_TABS_A_DAY) {
            dropped.incrementAndGet();
            return new int[] {0, 0};
        }
        Instant prev = lastBeat.put(tabKey, at);
        long since = prev == null ? MAX_BEAT_SECONDS : Math.max(0, Duration.between(prev, at).toSeconds());
        int cap = (int) Math.min(MAX_BEAT_SECONDS, since);
        int v = Math.max(0, Math.min(visible, cap));
        int a = Math.max(0, Math.min(active, v));
        offer(new Beat(key, subject, tab, at, v, a, path(path), standalone, closing));
        return new int[] {v, a};
    }

    void end(String key, EndReason reason) {
        if (enabled && key != null) {
            offer(new End(key, reason, now()));
        }
    }

    private void offer(Object event) {
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
        }
    }

    /** Counters for the status page and tests. */
    public Map<String, Object> counters() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("queued", queue.size());
        m.put("written", written.get());
        m.put("dropped", dropped.get());
        m.put("failedWrites", failed.get());
        return m;
    }

    // ------------------------------------------------------------------------------------------------- writer

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            log.warn("activity: write failed ({}); the rows of this round are lost", e.toString());
        }
    }

    /** Writes everything queued, then the housekeeping that is due. One thread only (the writer, or a test). */
    synchronized void flush() {
        if (!enabled) {
            return;
        }
        List<Object> events = new ArrayList<>();
        queue.drainTo(events);
        for (Object e : events) {                           // sign-ins first: a beat of the same round finds its row
            if (e instanceof SignIn s) {
                isolated(e, () -> writeSignIn(s));
            }
        }
        for (Object e : events) {                           // then in the order they came
            if (e instanceof Beat b) {
                isolated(e, () -> writeBeat(b));
            } else if (e instanceof End x) {
                isolated(e, () -> writeEnd(x));
            }
        }
        for (String key : List.copyOf(seen.keySet())) {
            Instant at = seen.remove(key);
            if (at != null) {
                jdbc.update("update acct.user_login set last_seen_at = greatest(last_seen_at, ?), "
                        + "ended_at = case when end_reason = 'IDLE' then null else ended_at end, "
                        + "end_reason = case when end_reason = 'IDLE' then null else end_reason end where login_key = ?",
                        Timestamp.from(at), key);
            }
        }
        written.addAndGet(events.size());
        housekeeping(now());
    }

    /** One event's statements: a failure loses that event only (code review), counted and logged at most once a minute. */
    private void isolated(Object event, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            Instant now = now();
            if (Duration.between(lastFailLog, now).toSeconds() >= 60) {
                lastFailLog = now;
                log.warn("activity: {} not written ({}); {} failed so far", event.getClass().getSimpleName(), e.toString(),
                        failed.get());
            }
        }
    }

    private Instant lastFailLog = Instant.EPOCH;

    private void writeSignIn(SignIn s) {
        UserAgents.Device d = s.device();
        jdbc.update("insert into acct.user_login (subject, login_key, signed_in_at, last_seen_at, ip, forwarded_chain, "
                + "user_agent, browser, browser_version, os, os_version, device_type) "
                + "values (?, ?, ?, ?, cast(? as inet), ?, ?, ?, ?, ?, ?, ?) on conflict (login_key) do update set "
                + "last_seen_at = greatest(acct.user_login.last_seen_at, excluded.last_seen_at), "
                + "ended_at = case when acct.user_login.end_reason = 'IDLE' then null else acct.user_login.ended_at end, "
                + "end_reason = case when acct.user_login.end_reason = 'IDLE' then null else acct.user_login.end_reason end, "
                + "ip = coalesce(excluded.ip, acct.user_login.ip), "
                + "forwarded_chain = coalesce(excluded.forwarded_chain, acct.user_login.forwarded_chain), "
                + "user_agent = coalesce(excluded.user_agent, acct.user_login.user_agent), "
                + "browser = excluded.browser, browser_version = excluded.browser_version, os = excluded.os, "
                + "os_version = excluded.os_version, device_type = excluded.device_type",
                s.subject(), s.key(), Timestamp.from(s.signedInAt()), Timestamp.from(s.at()), s.ip(), s.chain(), s.userAgent(),
                d == null ? null : d.browser(), d == null ? null : d.browserVersion(), d == null ? null : d.os(),
                d == null ? null : d.osVersion(), d == null ? null : d.deviceType());
    }

    /** The sign-in's row id (made when a beat comes before its sign-in was recorded, e.g. after this service restarted). */
    private long loginId(String key, String subject, Instant at) {
        return jdbc.queryForObject("insert into acct.user_login (subject, login_key, signed_in_at, last_seen_at) "
                + "values (?, ?, ?, ?) on conflict (login_key) do update set "
                + "last_seen_at = greatest(acct.user_login.last_seen_at, excluded.last_seen_at), "
                + "ended_at = case when acct.user_login.end_reason = 'IDLE' then null else acct.user_login.ended_at end, "
                + "end_reason = case when acct.user_login.end_reason = 'IDLE' then null else acct.user_login.end_reason end "
                + "returning id", Long.class, subject, key, Timestamp.from(at), Timestamp.from(at));
    }

    private void writeBeat(Beat b) {
        long login = loginId(b.key(), b.subject(), b.at());
        Timestamp at = Timestamp.from(b.at());
        jdbc.update("insert into acct.user_tab (login_id, tab_id, opened_at, last_beat_at, closed_at, visible_seconds, "
                + "active_seconds, last_path) values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (login_id, tab_id) do update set "
                + "last_beat_at = greatest(acct.user_tab.last_beat_at, excluded.last_beat_at), "
                + "closed_at = excluded.closed_at, "
                + "visible_seconds = acct.user_tab.visible_seconds + excluded.visible_seconds, "
                + "active_seconds = acct.user_tab.active_seconds + excluded.active_seconds, "
                + "last_path = coalesce(excluded.last_path, acct.user_tab.last_path)",
                login, b.tab(), Timestamp.from(b.at().minusSeconds(b.visible())), at, b.closing() ? at : null, b.visible(),
                b.active(), b.path());
        if (b.standalone()) {
            jdbc.update("update acct.user_login set installed_app = true where id = ? and not installed_app", login);
        }
        for (Map.Entry<Instant, int[]> m : spread(b.at(), b.visible(), b.active()).entrySet()) {
            jdbc.update("insert into acct.user_minute (subject, minute, visible_seconds, active_seconds) values (?, ?, ?, ?) "
                    + "on conflict (subject, minute) do update set "
                    + "visible_seconds = least(60, acct.user_minute.visible_seconds + excluded.visible_seconds), "
                    + "active_seconds = least(60, acct.user_minute.active_seconds + excluded.active_seconds)",
                    b.subject(), Timestamp.from(m.getKey()), m.getValue()[0], m.getValue()[1]);
        }
    }

    /**
     * The beat's seconds per minute: {@code visible} seconds ending at {@code at}, and {@code active} the last of them,
     * each minute's share being its overlap with that span.
     */
    static Map<Instant, int[]> spread(Instant at, int visible, int active) {
        Map<Instant, int[]> out = new LinkedHashMap<>();
        if (visible <= 0) {
            return out;
        }
        long end = at.getEpochSecond(), from = end - visible, activeFrom = end - Math.min(active, visible);
        for (long minute = Math.floorDiv(from, 60) * 60; minute < end; minute += 60) {
            long lo = Math.max(minute, from), hi = Math.min(minute + 60, end);
            long alo = Math.max(minute, activeFrom);
            int v = (int) Math.max(0, hi - lo), a = (int) Math.max(0, hi - alo);
            if (v > 0) {
                out.put(Instant.ofEpochSecond(minute), new int[] {v, Math.min(a, v)});
            }
        }
        return out;
    }

    private void writeEnd(End e) {
        Timestamp at = Timestamp.from(e.at());
        jdbc.update("update acct.user_tab set closed_at = ? where closed_at is null and login_id = "
                + "(select id from acct.user_login where login_key = ?)", at, e.key());
        jdbc.update("update acct.user_login set ended_at = ?, end_reason = ?, last_seen_at = greatest(last_seen_at, ?) "
                + "where login_key = ? and (ended_at is null or end_reason = 'IDLE')", at, e.reason().name(), at, e.key());
    }

    // ------------------------------------------------------------------------------------------------- housekeeping

    private void housekeeping(Instant now) {
        if (Duration.between(swept, now).toSeconds() >= 60) {
            swept = now;
            jdbc.update("update acct.user_login set ended_at = last_seen_at, end_reason = 'IDLE' "
                    + "where ended_at is null and last_seen_at < ?", Timestamp.from(now.minus(props.idle())));
            jdbc.update("update acct.user_tab set closed_at = last_beat_at where closed_at is null and last_beat_at < ?",
                    Timestamp.from(now.minus(TAB_GONE)));
            Instant old = now.minus(Duration.ofDays(1));
            lastBeat.values().removeIf(t -> t.isBefore(old));
        }
        if (Duration.between(rolled, now).toMinutes() >= 10) {
            LocalDate today = LocalDate.ofInstant(now, IST);
            // at start, the last week again (code review: a day whose last minutes came after its last roll-up, e.g.
            // the VMs stopped that evening, is put right; well inside the raw rows' {@code rawDays}); then today and
            // yesterday every 10 minutes
            int back = rolled.equals(Instant.EPOCH) ? Math.max(1, Math.min(BACKFILL_DAYS, props.rawDays() - 2)) : 1;
            rolled = now;
            for (int d = back; d >= 0; d--) {
                rollUp(today.minusDays(d));
            }
        }
        LocalDate today = LocalDate.ofInstant(now, IST);
        if (!today.equals(retained)) {
            retained = today;
            newTabs.clear();
            retain(now);
        }
    }

    /** The day's totals per user (IST), as stored by {@link #rollUp} and shown live for today. */
    static final String DAY_TOTALS = """
            with b as (select cast(? as date)::timestamp at time zone 'Asia/Kolkata' as d0,
                              (cast(? as date) + 1)::timestamp at time zone 'Asia/Kolkata' as d1),
            m as (select u.subject, sum(u.visible_seconds) v, sum(u.active_seconds) a, min(u.minute) f,
                         max(u.minute) + interval '1 minute' l
                  from acct.user_minute u, b where u.minute >= b.d0 and u.minute < b.d1 group by u.subject),
            lg as (select l.subject, count(*) n,
                          count(distinct coalesce(l.browser, '?') || '|' || coalesce(l.os, '?') || '|' || coalesce(l.device_type, '?')) dev,
                          min(greatest(l.signed_in_at, b.d0)) f, max(least(l.last_seen_at, b.d1)) ls
                   from acct.user_login l, b where l.last_seen_at >= b.d0 and l.signed_in_at < b.d1 group by l.subject),
            tb as (select l.subject, count(*) n from acct.user_tab t join acct.user_login l on l.id = t.login_id, b
                   where t.last_beat_at >= b.d0 and t.opened_at < b.d1 group by l.subject),
            br as (select x.subject, jsonb_object_agg(x.k, x.s) j from (
                     select l.subject, coalesce(l.browser, 'Other') || ' / ' || coalesce(l.os, 'Other') k, sum(t.visible_seconds) s
                     from acct.user_tab t join acct.user_login l on l.id = t.login_id, b
                     where t.last_beat_at >= b.d0 and t.opened_at < b.d1 group by 1, 2) x group by x.subject)
            select coalesce(lg.subject, m.subject) subject, coalesce(lg.n, 0) logins, coalesce(lg.dev, 0) devices,
                   coalesce(tb.n, 0) tabs, coalesce(m.v, 0) visible_seconds, coalesce(m.a, 0) active_seconds,
                   least(m.f, lg.f) first_seen, greatest(m.l, lg.ls) last_seen, coalesce(br.j, '{}'::jsonb) by_browser
            from lg full join m on m.subject = lg.subject
            left join tb on tb.subject = coalesce(lg.subject, m.subject)
            left join br on br.subject = coalesce(lg.subject, m.subject)
            """;

    void rollUp(LocalDate day) {
        jdbc.update("insert into acct.user_activity_day (subject, day, logins, devices, tabs, visible_seconds, "
                + "active_seconds, first_seen, last_seen, by_browser) select t.subject, cast(? as date), t.logins, t.devices, "
                + "t.tabs, t.visible_seconds, t.active_seconds, t.first_seen, t.last_seen, t.by_browser from ("
                + DAY_TOTALS + ") t on conflict (subject, day) do update set logins = excluded.logins, "
                + "devices = excluded.devices, tabs = excluded.tabs, visible_seconds = excluded.visible_seconds, "
                + "active_seconds = excluded.active_seconds, first_seen = excluded.first_seen, "
                + "last_seen = excluded.last_seen, by_browser = excluded.by_browser", day, day, day);
    }

    private void retain(Instant now) {
        Timestamp raw = Timestamp.from(now.minus(Duration.ofDays(props.rawDays())));
        jdbc.update("delete from acct.user_minute where minute < ?", raw);
        jdbc.update("delete from acct.user_tab where coalesce(closed_at, last_beat_at) < ?", raw);
        jdbc.update("delete from acct.user_login where coalesce(ended_at, last_seen_at) < ?",
                Timestamp.from(now.minus(Duration.ofDays(props.loginDays()))));
        jdbc.update("update acct.user_login set ip = set_masklen(network(set_masklen(ip, case when family(ip) = 4 then 24 "
                + "else 48 end)), case when family(ip) = 4 then 24 else 48 end), forwarded_chain = null "
                + "where signed_in_at < ? and ip is not null and masklen(ip) = case when family(ip) = 4 then 32 else 128 end",
                Timestamp.from(now.minus(Duration.ofDays(props.ipDays()))));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /** The route only: no query string or fragment, at most 120 characters. */
    static String path(String p) {
        if (p == null || p.isBlank()) {
            return null;
        }
        String s = p.split("[?#]", 2)[0];
        return s.length() <= 120 ? s : s.substring(0, 120);
    }

    static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    @Override
    public void close() {
        if (writer != null) {
            writer.shutdown();
            try {
                writer.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            flushQuietly();                                        // what was still queued
        }
    }
}
