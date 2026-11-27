package com.autotrade.execution;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.AccountRole;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;

/**
 * The trader's spot exit on an open position (V56; user 27 Nov 2026: "I should exit from LIVE open position when it reaches
 * the spot not just on the percentage target"). The trader gives a level of the position's index; the <b>whole</b> position
 * is sold when the captured index reaches it: a CE when the index is at or above the level (direction UP), a PE when it is
 * at or below it (DOWN). A take-profit at a touch, not a lock (the % target, {@link PositionTargets}, is the lock). It sits
 * beside the % target, the strategy's own exits, the stop, Exit and the square-off: whichever comes first.
 * <ul>
 *   <li><b>WAITING</b>: each look reads the index ticks since the last look (with {@link #OVERLAP}; the first look after the
 *       worker starts at most {@link #FIRST_LOOK} back), never before the level was set or before 09:15:00 IST; their high
 *       (UP) or low (DOWN) at or beyond the level triggers the sale, so a touch between two looks counts. A tick more than
 *       {@link #BAD_TICK} from the median of the window and the minute before it is bad data and never triggers.</li>
 *   <li><b>FIRING</b>: a sale runs (a compare-and-set claims it, one at a time per position, across workers) through the
 *       per-position Exit path, exit reason {@link #EXIT_REASON}, on its own thread. Then <b>DONE</b>, <b>ENDED</b> (closed
 *       by something else first) or <b>SELLING</b> (not all sold: sold again at {@code next_try_at} whatever the index,
 *       like a stop order), <b>FAILED</b> after {@link PositionTargets#MAX_TRIES} tries (the trader's Exit is still there).
 *       A lost broker session, a route that cannot open and another worker's account are not tries, as for the % target.</li>
 *   <li>Sales trigger only between 09:15 and 15:30 IST, never on a pre-open or after-close tick.</li>
 *   <li>Setting it: refused when the index is already at or beyond the level (that is an Exit now), more than
 *       {@link #MAX_AWAY} from the index (a typo such as 2610 for 26100), when no index price is known, when in market
 *       hours the newest one is older than {@link #STALE} (the feed is not recording: nothing would watch it), and while a
 *       triggered sale of the rest is pending (SELLING: Exit, or remove it first).</li>
 *   <li>A sale triggered on an earlier day is not sent again at the next open whatever the index: it is FAILED (Exit).</li>
 * </ul>
 * A paper sale fills at a fresh stored bid like any person's exit; a LIVE one is a real sell at the account's broker (stop
 * cancelled first), so the fill is the option's price then, not a price fixed by the level.
 */
public final class SpotTargets {

    private static final Logger log = LoggerFactory.getLogger(SpotTargets.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** The position's exit reason when the spot exit sells it. */
    public static final String EXIT_REASON = "SPOT_TARGET";
    /** Who the watcher's sales and audits name. */
    public static final String ACTOR = "spot-watch";
    /** The look's window reaches back this far before the last look (ticks are written in batches, a little late). */
    static final Duration OVERLAP = Duration.ofSeconds(5);
    /** The first look at a level after the worker starts reaches back at most this far (a longer outage loses touches). */
    static final Duration FIRST_LOOK = Duration.ofMinutes(10);
    /** A tick further than this from the median of the window and the minute before it is bad data, not a touch. */
    public static final double BAD_TICK = 0.02;
    /** In market hours a level is refused when the index's newest captured price is older than this (nothing to watch). */
    public static final Duration STALE = Duration.ofMinutes(2);
    /** A level further than this from the index when it is set is refused (a typo). */
    public static final double MAX_AWAY = 0.10;
    /** The index price shown and checked at set time is the newest of this window (a weekend included). */
    static final Duration LAST_KNOWN = Duration.ofDays(5);
    static final LocalTime FIRST = LocalTime.of(9, 15), LAST = LocalTime.of(15, 30);
    private static final List<String> LIVE = List.of("WAITING", "FIRING", "SELLING");

    /** The index's newest captured price, when it was received, and its age by the database's clock (seconds). */
    public record Spot(double price, Instant at, double ageSeconds) {
    }

    /** How far a level's ticks were looked at, per generation (a new level starts its own window). */
    private record Seen(int generation, Instant upTo) {
    }

    private final Map<Long, Seen> lookedTo = new java.util.concurrent.ConcurrentHashMap<>();

    public enum Direction { UP, DOWN }

    /** One position's spot exit as stored. */
    public record View(long positionId, long accountId, String underlying, Direction direction, double level, String status,
                       String setBy, Instant setAt, Double spotAtSet, Instant firedAt, Double firedSpot, int tries,
                       String lastResult, Instant nextTryAt, boolean byAdmin, String adminReason) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("underlying", underlying);
            m.put("direction", direction.name());
            m.put("level", level);
            m.put("status", status);
            m.put("setBy", setBy);
            m.put("setAt", setAt == null ? null : setAt.toString());
            m.put("spotAtSet", spotAtSet);
            m.put("firedAt", firedAt == null ? null : firedAt.toString());
            m.put("firedSpot", firedSpot);
            m.put("tries", tries);
            m.put("lastResult", lastResult);
            m.put("nextTryAt", nextTryAt == null ? null : nextTryAt.toString());
            m.put("byAdmin", byAdmin);
            m.put("adminReason", adminReason);
            return m;
        }
    }

    private final JdbcTemplate jdbc;
    private final KillSwitches audit;
    private Clock clock = Clock.systemUTC();
    private Executor sales = r -> Thread.ofVirtual().name("spot-sale").start(r);

    public SpotTargets(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.audit = new KillSwitches(dataSource);
    }

    /** Tests: the clock of the market-hours gate. */
    public SpotTargets withClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /**
     * The first instant a tick may count: today's 09:15:00 IST by the gate's clock. Without it, at 09:15:00 the last
     * first look would still read pre-open ticks, and a level the pre-open crossed would sell at the first second.
     */
    private java.util.function.Supplier<Instant> sessionOpen = () ->
            java.time.LocalDate.ofInstant(clock.instant(), IST).atTime(FIRST).atZone(IST).toInstant();

    /** Tests: where the session starts (ticks before it never count). */
    SpotTargets withSessionOpen(java.util.function.Supplier<Instant> sessionOpen) {
        this.sessionOpen = sessionOpen;
        return this;
    }

    /** Tests: run claimed sales here (e.g. {@code Runnable::run}). */
    public SpotTargets withSales(Executor sales) {
        this.sales = sales;
        return this;
    }

    /** The level as stored (two decimals), or null when it is not one. */
    public static Double level(Double level) {
        if (level == null || !Double.isFinite(level)) {
            return null;
        }
        double rounded = Math.round(level * 100) / 100.0;
        return rounded > 0 ? rounded : null;
    }

    /** CE: the index rising to the level is the profit; PE: falling to it. Null for anything else. */
    public static Direction directionOf(String optionSide) {
        if ("CE".equals(optionSide)) {
            return Direction.UP;
        }
        if ("PE".equals(optionSide)) {
            return Direction.DOWN;
        }
        return null;
    }

    /** Whether an index price is at or beyond the level in the position's direction. */
    public static boolean reached(Direction d, double spot, double level) {
        return d == Direction.UP ? spot >= level : spot <= level;
    }

    /** Why a level is refused against the index now ({@code spot} null: not known), or null. */
    public static String refusal(String underlying, Direction d, double level, Double spot) {
        if (spot == null) {
            return "no " + underlying + " price has been captured lately: the level cannot be checked or watched";
        }
        if (reached(d, spot, level)) {
            return String.format(Locale.ROOT, "%s is %.2f now, already %s %.2f: use Exit to sell now", underlying, spot,
                    d == Direction.UP ? "at or above" : "at or below", level);
        }
        if (Math.abs(level / spot - 1) > MAX_AWAY) {
            return String.format(Locale.ROOT, "%.2f is more than %d %% away from %s (%.2f): check the level", level,
                    Math.round(MAX_AWAY * 100), underlying, spot);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ the trader's side

    /** As {@link #set(BrokerAccount, long, double, String, Map)} for the workspace's own people. */
    public View set(BrokerAccount account, long positionId, double level, String actor) {
        return set(account, positionId, level, actor, null);
    }

    /**
     * Sets (or replaces) the spot exit of an open position in {@code account}'s book at {@code level}; it starts WAITING.
     * The same level again changes nothing (the same who), unless it FAILED. {@code admin}: the platform admin's audit
     * detail (by_admin, admin_reason, ...; null: the workspace's own people). IllegalArgumentException when refused.
     */
    public View set(BrokerAccount account, long positionId, double level, String actor, Map<String, ?> admin) {
        boolean byAdmin = admin != null && Boolean.TRUE.equals(admin.get("by_admin"));
        String adminReason = byAdmin && admin.get("admin_reason") != null ? String.valueOf(admin.get("admin_reason")) : null;
        if (account.role() == AccountRole.SIGNAL_SOURCE) {
            throw new IllegalArgumentException(account.displayName() + " is a signal source: its positions are not orders");
        }
        Double rounded = level(level);
        if (rounded == null) {
            throw new IllegalArgumentException("the spot level: an index price above 0");
        }
        Map<String, Object> p = openPosition(account, positionId);
        String underlying = (String) p.get("underlying");
        Direction d = directionOf((String) p.get("option_side"));
        if (d == null) {
            throw new IllegalArgumentException("position " + positionId + " is not a CE or PE: no spot direction");
        }
        Spot latest = latest(underlying);
        Double spot = latest == null ? null : latest.price();
        String refused = refusal(underlying, d, rounded, spot);
        if (refused != null) {
            throw new IllegalArgumentException(refused);
        }
        if (marketOpen() && latest.ageSeconds() > STALE.toSeconds()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "the newest %s price (%.2f) is %d min old: the "
                    + "feed is not recording it, so nothing would watch the level; use the %% target or Exit", underlying,
                    spot, Math.round(latest.ageSeconds() / 60)));
        }
        Optional<View> now = view(positionId);
        if (now.isPresent() && "SELLING".equals(now.get().status())) {
            throw new IllegalArgumentException("the spot exit was triggered and the rest is being sold: use Exit, or remove "
                    + "the spot exit first");
        }
        if (now.isPresent() && now.get().level() == rounded && now.get().byAdmin() == byAdmin
                && LIVE.contains(now.get().status())) {
            return now.get();                                  // unchanged
        }
        int changed = jdbc.update("insert into exec.position_spot_target (position_id, broker_account_id, underlying, "
                + "direction, spot_level, status, set_by, set_at, spot_at_set, by_admin, admin_reason) values (?, ?, ?, ?, ?, "
                + "'WAITING', ?, now(), ?, ?, ?) on conflict (position_id) do update set underlying = excluded.underlying, "
                + "direction = excluded.direction, spot_level = excluded.spot_level, generation = "
                + "exec.position_spot_target.generation + 1, status = 'WAITING', set_by = excluded.set_by, set_at = now(), "
                + "spot_at_set = excluded.spot_at_set, by_admin = excluded.by_admin, admin_reason = excluded.admin_reason, "
                + "fired_at = null, fired_spot = null, last_try_at = null, next_try_at = null, tries = 0, last_result = null, "
                + "updated_at = now() where exec.position_spot_target.status not in ('FIRING', 'SELLING')", positionId, account.id(),
                underlying, d.name(), rounded, actor, spot, byAdmin, adminReason);
        if (changed == 0) {
            throw new IllegalArgumentException("the spot exit is selling this position now: wait for the sale");
        }
        lookedTo.remove(positionId);
        Map<String, Object> detail = new LinkedHashMap<>(Map.of("position", positionId, "symbol", p.get("symbol"),
                "underlying", underlying, "direction", d.name(), "level", rounded, "spot", spot == null ? "-" : spot,
                "replaced", now.map(View::status).orElse("none")));
        if (admin != null) {
            detail.putAll(admin);
        }
        audit.audit(account, actor, "SPOT_TARGET_SET", detail);
        return view(positionId).orElseThrow();
    }

    /** Whether a set or clear changed anything between two reads (what to tell, what to count). */
    public static boolean changed(Optional<View> before, Optional<View> after) {
        if (before.isEmpty() || after.isEmpty()) {
            return before.isPresent() != after.isPresent();
        }
        View b = before.get(), a = after.get();
        return b.level() != a.level() || b.byAdmin() != a.byAdmin() || !b.status().equals(a.status())
                || !java.util.Objects.equals(b.setAt(), a.setAt());
    }

    /** Removes the spot exit (a pending sale of the rest included); false when there was none. */
    public boolean clear(BrokerAccount account, long positionId, String actor) {
        return clear(account, positionId, actor, null);
    }

    public boolean clear(BrokerAccount account, long positionId, String actor, Map<String, ?> admin) {
        openPosition(account, positionId);
        int changed = jdbc.update("update exec.position_spot_target set status = 'CLEARED', generation = generation + 1, "
                + "updated_at = now() where position_id = ? and broker_account_id = ? and status in ('WAITING', 'SELLING', "
                + "'FAILED')", positionId, account.id());
        if (changed == 0) {
            if (view(positionId).map(v -> "FIRING".equals(v.status())).orElse(false)) {
                throw new IllegalArgumentException("the spot exit is selling this position now: it cannot be removed");
            }
            return false;
        }
        Map<String, Object> detail = new LinkedHashMap<>(Map.of("position", positionId));
        if (admin != null) {
            detail.putAll(admin);
        }
        audit.audit(account, actor, "SPOT_TARGET_CLEARED", detail);
        return true;
    }

    /** An open position of the account's own book (a paper account's PAPER entries, a broker account's LIVE ones). */
    private Map<String, Object> openPosition(BrokerAccount account, long positionId) {
        String book = account.brokerType() == BrokerType.PAPER ? "PAPER" : "LIVE";
        return jdbc.queryForList("select p.id, p.symbol, p.underlying, p.option_side, p.quantity_open from exec.position p "
                        + "join exec.trade_intent i on i.id = p.entry_trade_intent_id where p.id = ? and p.broker_account_id = ? "
                        + "and p.status = 'OPEN' and p.quantity_open > 0 and i.execution_mode = ?", positionId, account.id(),
                        book).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("position " + positionId
                        + " is not an open " + book + " position of " + account.displayName()));
    }

    /** The account's spot exits on open positions (not removed), by position id. */
    public Map<Long, View> forAccount(long accountId) {
        Map<Long, View> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(SELECT + "where t.broker_account_id = ? and t.status <> 'CLEARED' "
                + "and p.status = 'OPEN'", accountId)) {
            View v = viewOf(r);
            out.put(v.positionId(), v);
        }
        return out;
    }

    public Optional<View> view(long positionId) {
        return jdbc.queryForList(SELECT + "where t.position_id = ?", positionId).stream().findFirst().map(SpotTargets::viewOf);
    }

    private static final String SELECT = "select t.position_id, t.broker_account_id, t.underlying, t.direction, t.spot_level, "
            + "t.status, t.set_by, t.set_at, t.spot_at_set, t.fired_at, t.fired_spot, t.tries, t.last_result, t.next_try_at, "
            + "t.by_admin, t.admin_reason from exec.position_spot_target t join exec.position p on p.id = t.position_id ";

    private static View viewOf(Map<String, Object> r) {
        return new View(((Number) r.get("position_id")).longValue(), ((Number) r.get("broker_account_id")).longValue(),
                (String) r.get("underlying"), Direction.valueOf((String) r.get("direction")),
                ((Number) r.get("spot_level")).doubleValue(), (String) r.get("status"), (String) r.get("set_by"),
                instant(r.get("set_at")), dbl(r.get("spot_at_set")), instant(r.get("fired_at")), dbl(r.get("fired_spot")),
                ((Number) r.get("tries")).intValue(), (String) r.get("last_result"), instant(r.get("next_try_at")),
                Boolean.TRUE.equals(r.get("by_admin")), (String) r.get("admin_reason"));
    }

    /** The index's newest captured price of the last {@link #LAST_KNOWN} and when it came, or null. */
    public Spot latest(String underlying) {
        return jdbc.queryForList("select price, recv_ts, extract(epoch from now() - recv_ts) age from md.index_tick "
                + "where underlying = ? and recv_ts > now() - "
                + "make_interval(days => ?) and recv_ts <= now() + interval '5 seconds' and price > 0 order by recv_ts desc "
                + "limit 1", underlying, (int) LAST_KNOWN.toDays()).stream().findFirst()
                .map(r -> new Spot(((Number) r.get("price")).doubleValue(), ((Timestamp) r.get("recv_ts")).toInstant(),
                        Math.max(0, ((Number) r.get("age")).doubleValue())))
                .orElse(null);
    }

    private static Instant instant(Object o) {
        return o instanceof Timestamp t ? t.toInstant() : null;
    }

    private static Double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    // ------------------------------------------------------------------------------------------------ the watcher

    /**
     * One look at every live spot exit of an open position (the worker calls it every second): claims a sale for those
     * whose index reached the level since the last look (never before it was set or before 09:15:00 IST), and for those SELLING
     * whose next try is due; each claimed sale runs on its own. Returns one line per thing begun.
     */
    public List<String> watch(PositionTargets.Exiter exiter) {
        jdbc.update("update exec.position_spot_target set status = 'SELLING', next_try_at = now(), last_result = 'the sale "
                + "was left unfinished (the worker stopped?): sold again', updated_at = now() where status = 'FIRING' and "
                + "last_try_at < now() - make_interval(secs => ?)", (double) PositionTargets.STUCK_AFTER.toSeconds());
        jdbc.update("update exec.position_spot_target t set status = 'ENDED', generation = generation + 1, last_result = "
                + "coalesce(t.last_result || ' · ', '') || 'the position was closed by ' || coalesce(p.exit_reason, 'another "
                + "path'), updated_at = now() from exec.position p where p.id = t.position_id and t.status in ('WAITING', "
                + "'SELLING') and (p.status <> 'OPEN' or p.quantity_open <= 0)");
        if (!marketOpen()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList("select t.position_id, t.broker_account_id, t.underlying, "
                + "t.direction, t.spot_level, t.generation, t.status, t.set_at, t.tries, t.last_result, t.by_admin, "
                + "t.admin_reason, t.fired_at, (t.next_try_at is null or t.next_try_at <= now()) due, now() db_now, p.symbol, "
                + "p.tenant_id from "
                + "exec.position_spot_target t join exec.position p on p.id = t.position_id where t.status in ('WAITING', "
                + "'SELLING') and p.status = 'OPEN' and p.quantity_open > 0 order by t.position_id");
        java.util.Set<Long> live = new java.util.HashSet<>();
        for (Map<String, Object> r : rows) {
            long positionId = ((Number) r.get("position_id")).longValue();
            live.add(positionId);
            try {
                if (!exiter.mine(((Number) r.get("broker_account_id")).longValue())) {
                    continue;                                 // another worker holds the account: its watcher looks
                }
                String line = look(r, positionId, exiter);
                if (line != null) {
                    lines.add(line);
                }
            } catch (RuntimeException e) {
                log.warn("spot exit of position {}: {}", positionId, e.toString());
            }
        }
        lookedTo.keySet().retainAll(live);
        return lines;
    }

    private boolean marketOpen() {
        LocalTime t = LocalTime.ofInstant(clock.instant(), IST);
        return !t.isBefore(FIRST) && t.isBefore(LAST);
    }

    private String look(Map<String, Object> r, long positionId, PositionTargets.Exiter exiter) {
        int generation = ((Number) r.get("generation")).intValue();
        String status = (String) r.get("status");
        String underlying = (String) r.get("underlying");
        Direction d = Direction.valueOf((String) r.get("direction"));
        double level = ((Number) r.get("spot_level")).doubleValue();
        long account = ((Number) r.get("broker_account_id")).longValue();
        long tenant = ((Number) r.get("tenant_id")).longValue();
        Double spot = null;
        Instant open = sessionOpen.get();
        if ("WAITING".equals(status)) {
            Instant dbNow = ((Timestamp) r.get("db_now")).toInstant();
            Instant from = ((Timestamp) r.get("set_at")).toInstant();
            Seen seen = lookedTo.get(positionId);
            Instant back = seen != null && seen.generation() == generation ? seen.upTo().minus(OVERLAP)
                    : dbNow.minus(FIRST_LOOK);
            from = latest(from, back, open);
            // the window's extremes, each tick checked against the median of the window and the minute before it
            Map<String, Object> range = jdbc.queryForMap("with w as (select price, recv_ts from md.index_tick where "
                    + "underlying = ? and recv_ts > ? and recv_ts <= now() + interval '5 seconds' and price > 0), ref as ("
                    + "select percentile_cont(0.5) within group (order by price) med from md.index_tick where underlying = ? "
                    + "and recv_ts > ? and recv_ts <= now() + interval '5 seconds' and price > 0) select max(w.price) filter "
                    + "(where abs(w.price / ref.med - 1) <= ?) hi, min(w.price) filter (where abs(w.price / ref.med - 1) <= ?) "
                    + "lo, max(w.recv_ts) t, count(*) filter (where abs(w.price / ref.med - 1) > ?) bad from w cross join ref",
                    underlying, Timestamp.from(from), underlying, Timestamp.from(from.minusSeconds(60)), BAD_TICK, BAD_TICK,
                    BAD_TICK);
            Instant upTo = dbNow.minus(OVERLAP);
            if (range.get("t") instanceof Timestamp t && t.toInstant().isAfter(upTo)) {
                upTo = t.toInstant();
            }
            lookedTo.put(positionId, new Seen(generation, upTo));
            if (range.get("bad") instanceof Number bad && bad.longValue() > 0) {
                log.warn("spot exit of position {}: {} {} tick(s) more than {} % from the median ignored", positionId,
                        bad, underlying, Math.round(BAD_TICK * 100));
            }
            spot = dbl(range.get(d == Direction.UP ? "hi" : "lo"));
            if (spot == null || !reached(d, spot, level)) {
                return null;
            }
        } else if (r.get("fired_at") instanceof Timestamp fired && fired.toInstant().isBefore(open)) {
            // triggered on an earlier day: not sold at this open whatever the index (a gap may have turned it); the trader's Exit
            int ended = jdbc.update("update exec.position_spot_target set status = 'FAILED', last_result = coalesce("
                    + "last_result || ' · ', '') || 'triggered on an earlier day and not all sold before the close: not sold "
                    + "again at the open; use Exit', updated_at = now() where position_id = ? and status = 'SELLING' and "
                    + "generation = ?", positionId, generation);
            if (ended == 1) {
                audit.audit(tenant, account, ACTOR, "SPOT_TARGET_SELL", Map.of("position", positionId, "symbol",
                        r.get("symbol"), "level", level, "result", "triggered on an earlier day: not sold again", "status",
                        "FAILED"));
            }
            return null;
        } else if (!Boolean.TRUE.equals(r.get("due"))) {
            return null;                                       // SELLING: the next try is not due yet
        }
        int claimed = jdbc.update("update exec.position_spot_target set status = 'FIRING', fired_at = coalesce(fired_at, "
                + "now()), fired_spot = coalesce(fired_spot, ?), last_try_at = now(), updated_at = now() where position_id = ? "
                + "and status = ? and generation = ?", spot, positionId, status, generation);
        if (claimed != 1) {
            return null;                                       // set again, removed or claimed by another worker meanwhile
        }
        String why = spot != null ? String.format(Locale.ROOT, "%s %.2f reached the spot exit %.2f", underlying, spot, level)
                : String.format(Locale.ROOT, "the rest of the triggered spot exit %s %.2f", underlying, level);
        Map<String, Object> detail = new LinkedHashMap<>(Map.of("spot_level", level, "underlying", underlying, "spot",
                spot == null ? "-" : spot, "why", why));
        if (Boolean.TRUE.equals(r.get("by_admin"))) {
            detail.put("by_admin", true);
            detail.put("admin_target", true);
            if (r.get("admin_reason") != null) {
                detail.put("admin_reason", r.get("admin_reason"));
            }
        }
        Sale sale = new Sale(positionId, account, tenant, generation, (String) r.get("symbol"), level, spot,
                ((Number) r.get("tries")).intValue(), (String) r.get("last_result"));
        sales.execute(() -> sell(sale, exiter, detail));
        return "position " + positionId + ": " + why + ": selling";
    }

    private static Instant latest(Instant a, Instant b, Instant c) {
        Instant m = a.isAfter(b) ? a : b;
        return m.isAfter(c) ? m : c;
    }

    private record Sale(long positionId, long account, long tenant, int generation, String symbol, double level, Double spot,
                        int tries, String lastResult) {
    }

    private void sell(Sale s, PositionTargets.Exiter exiter, Map<String, ?> detail) {
        List<String> result;
        Throwable failure = null;
        try {
            result = exiter.exit(s.account(), s.positionId(), detail);
        } catch (Exception e) {
            failure = e;
            result = List.of("not sold: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        try {
            finish(s, result, failure);
        } catch (RuntimeException e) {
            log.warn("spot exit of position {}: the sale's outcome was not written ({}); repaired after {}", s.positionId(),
                    e.toString(), PositionTargets.STUCK_AFTER);
        }
    }

    private void finish(Sale s, List<String> result, Throwable failure) {
        String said = String.join(" · ", result);
        Map<String, Object> pos = jdbc.queryForMap("select status, quantity_open, exit_reason from exec.position where id = ?",
                s.positionId());
        boolean closed = "CLOSED".equals(pos.get("status")) || ((Number) pos.get("quantity_open")).longValue() <= 0;
        String next;
        int tries = s.tries();
        Duration wait = Duration.ZERO;
        if (closed) {
            next = EXIT_REASON.equals(pos.get("exit_reason")) ? "DONE" : "ENDED";
            if ("ENDED".equals(next)) {
                said = "the position was closed by " + pos.get("exit_reason") + " first";
            }
        } else if (failure != null && causedBy(failure, java.util.concurrent.TimeoutException.class)) {
            // the lane did not answer in time: the sale may still run there; left FIRING (repaired after STUCK_AFTER)
            jdbc.update("update exec.position_spot_target set last_result = ?, updated_at = now() where position_id = ? and "
                    + "status = 'FIRING' and generation = ?", truncate("still selling on the account's lane: " + said),
                    s.positionId(), s.generation());
            return;
        } else if (failure != null && PositionTargets.sessionLost(failure)
                || result.stream().anyMatch(l -> l.startsWith("not flattened: "))) {
            next = "SELLING";                                  // log in again (or the route cannot open): not a try
            wait = PositionTargets.SESSION_RETRY;
            said = "not sold, the broker cannot be reached (" + said + "): log in to the broker again; the rest is sold "
                    + "after the login";
        } else if (!result.isEmpty() && result.stream().allMatch(l -> l.startsWith("not flattened here"))) {
            next = "SELLING";                                  // another worker holds the account: its watcher sells
            wait = PositionTargets.ELSEWHERE_RETRY;
        } else {
            tries++;
            next = tries >= PositionTargets.MAX_TRIES ? "FAILED" : "SELLING";
            wait = PositionTargets.backOff(tries);
        }
        int updated = jdbc.update("update exec.position_spot_target set status = ?, tries = ?, last_result = ?, next_try_at = "
                + "now() + make_interval(secs => ?), updated_at = now() where position_id = ? and status = 'FIRING' and "
                + "generation = ?", next, tries, truncate(said), (double) wait.toSeconds(), s.positionId(), s.generation());
        if (updated == 1 && (!"SELLING".equals(next) || !said.equals(s.lastResult()))) {
            audit.audit(s.tenant(), s.account(), ACTOR, "SPOT_TARGET_SELL", Map.of("position", s.positionId(), "symbol",
                    s.symbol(), "level", s.level(), "spot", s.spot() == null ? "-" : s.spot(), "result", said, "status",
                    next));
        }
        log.info("spot exit: position {}: {} ({})", s.positionId(), said, next);
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String s) {
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
