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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.AccountRole;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.broker.BrokerException;

/**
 * The trader's manual first target on an open position (V54; user 24 Nov 2026: "I should be able to manually enter the 1st
 * target in % once the position is opened ... so in case it reverses should close at the target entered manually").
 * Answers the same day: a <b>lock</b>, the <b>whole</b> position, <b>beside</b> the strategy's own exits, PAPER and LIVE,
 * every broker ("also do it for real Kotak orders").
 * <ul>
 *   <li><b>WAITING</b>: the price is the position's average entry x (1 + %), to the 0.05 tick ({@link #priceOf}). Nothing is
 *       sold while the captured bid has not reached it. The highest bid since the last look (or since the target was set,
 *       never before) is read each look, with a few seconds of overlap for ticks written late, so a touch between two
 *       looks counts. A crossed quote (bid above a quoted ask) is not a price.</li>
 *   <li><b>ARMED</b>: the bid reached the price; the price is <b>fixed</b> then ({@code armed_price}: an add that moves the
 *       average entry later does not move it). From the next look on, a bid of the last {@link #BID_MAX_AGE} (by the
 *       database's clock) at or below it <b>triggers</b> the sale; above it the position rides on under its strategy.</li>
 *   <li><b>FIRING</b>: a sale runs (a compare-and-set claims it: one sale at a time per target, across workers) through
 *       {@link Exiter} (the per-position Exit path, exit reason {@link #EXIT_REASON}), on its own thread, so a slow broker
 *       never holds up the other targets. Then <b>DONE</b> (sold by the target), <b>ENDED</b> (closed by something else
 *       first) or <b>SELLING</b>: triggered but not all sold (a partial fill, a lost session, a stop not released yet).
 *       A SELLING target is sold again at {@code next_try_at} <b>whatever the bid</b>, like a stop order: once the bid came
 *       back to the target, the rest goes. Failed tries back off from {@link #RETRY_EVERY} to {@link #RETRY_MAX}; after
 *       {@link #MAX_TRIES} it is <b>FAILED</b> (the trader's Exit is still there).</li>
 *   <li>Not a try (user 24 Nov 2026, Kotak's TOTP/MPIN login is daily): a lost broker session or a route that cannot open
 *       (log in again), tried every {@link #SESSION_RETRY}; another worker's account; a sale still running on the account's
 *       lane after its wait (left FIRING; a FIRING target untouched for {@link #STUCK_AFTER} goes back to SELLING).</li>
 *   <li>Sales trigger only between {@link #FIRST} and {@link #LAST} IST, never on a pre-open or after-close quote.</li>
 * </ul>
 * Every change the watcher makes checks the target's {@code generation}: a target set again meanwhile (a new %) is never
 * armed or sold by a look at the old one. The strategy, its stop, the trader's Exit and the square-off are untouched:
 * whichever comes first closes the position. A paper sale fills at the stored bid like any paper exit; a LIVE one is a real
 * sell at the account's broker (stop cancelled first, then a limit sell in the broker's product, MIS for Kotak), so in a
 * fast fall it can fill below the target price.
 * <p>
 * The admin's target (V55, user 25 Nov 2026): the platform admin may set or remove a target on a member's position from the
 * exit desk ({@link #set(BrokerAccount, long, double, String, Map)} with the admin's audit detail: a reason, by_admin, the
 * role and the member's consent). It is the same lock; the row says {@code by_admin} and why, the TARGET_SET audit carries
 * the detail, and its sale's POSITION_EXIT audit says by_admin with the reason (the member's in-app notice).
 */
public final class PositionTargets {

    private static final Logger log = LoggerFactory.getLogger(PositionTargets.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** The position's exit reason when the target sells it. */
    public static final String EXIT_REASON = "MANUAL_TARGET";
    /** Who the watcher's sales and audits name. */
    public static final String ACTOR = "target-watch";
    public static final double MAX_PCT = 1000;
    /** A bid older than this (by the database's clock) is no reason to sell. */
    static final Duration BID_MAX_AGE = Duration.ofSeconds(15);
    /** The arming window reaches back this far before the last look (ticks are written in batches, a little late). */
    static final Duration OVERLAP = Duration.ofSeconds(5);
    /** A read by token (a contract not in ref.instrument: no index) reaches back at most this far. */
    static final Duration TOKEN_WINDOW = Duration.ofMinutes(10);
    /** The first retry after a failed try, doubled each time up to {@link #RETRY_MAX}. */
    static final Duration RETRY_EVERY = Duration.ofSeconds(5);
    static final Duration RETRY_MAX = Duration.ofSeconds(60);
    /** About 25 minutes of failed tries. */
    static final int MAX_TRIES = 30;
    /** A lost broker session or a route that cannot open: not a try, tried again this often (log in again). */
    static final Duration SESSION_RETRY = Duration.ofSeconds(30);
    /** Another worker holds the account (its watcher sells it): looked at again after this. */
    static final Duration ELSEWHERE_RETRY = Duration.ofSeconds(10);
    /** A sale waits at most 120 s on the account's lane; a FIRING target untouched this long was left by a stopped worker. */
    static final Duration STUCK_AFTER = Duration.ofMinutes(3);
    /** Sales trigger only in market hours (IST). */
    static final LocalTime FIRST = LocalTime.of(9, 15), LAST = LocalTime.of(15, 30);

    /** Sells the whole open position now; returns what it did, one line per sale (as the trader's Exit says it). */
    @FunctionalInterface
    public interface Exiter {
        List<String> exit(long accountId, long positionId, Map<String, ?> audit) throws Exception;

        /** Whether this worker sells for the account (false: another worker holds it and its watcher sells). */
        default boolean mine(long accountId) {
            return true;
        }
    }

    /** One position's target as stored; {@code price} is the armed price once armed, else entry x (1 + %) now. */
    public record View(long positionId, long accountId, double pct, double price, String status, String setBy, Instant setAt,
                       Instant armedAt, Double armedBid, Instant firedAt, Double firedBid, int tries, String lastResult,
                       Instant nextTryAt, boolean byAdmin, String adminReason) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pct", pct);
            m.put("price", price);
            m.put("status", status);
            m.put("setBy", setBy);
            m.put("setAt", setAt == null ? null : setAt.toString());
            m.put("armedAt", armedAt == null ? null : armedAt.toString());
            m.put("armedBid", armedBid);
            m.put("firedAt", firedAt == null ? null : firedAt.toString());
            m.put("firedBid", firedBid);
            m.put("tries", tries);
            m.put("lastResult", lastResult);
            m.put("nextTryAt", nextTryAt == null ? null : nextTryAt.toString());
            m.put("byAdmin", byAdmin);                     // set by the platform admin from the exit desk (V55)
            m.put("adminReason", adminReason);
            return m;
        }
    }

    /** What one look at an armed or waiting target says to do. */
    enum Step { NONE, ARM, SELL }

    /** The newest tick looked at for a target's high, per generation (a new target starts its own window). */
    private record Seen(int generation, Timestamp upTo) {
    }

    private final JdbcTemplate jdbc;
    private final KillSwitches audit;
    private Clock clock = Clock.systemUTC();
    /** Where a claimed sale runs: a virtual thread each (tests: the calling thread). */
    private Executor sales = r -> Thread.ofVirtual().name("target-sale").start(r);
    private final Map<Long, Seen> lookedTo = new ConcurrentHashMap<>();
    /** The contract (expiry, strike, type) of an instrument key; an empty array: not in ref.instrument when looked up. */
    private final Map<String, Object[]> contracts = new ConcurrentHashMap<>();
    private final Map<String, Instant> unknownSince = new ConcurrentHashMap<>();

    public PositionTargets(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.audit = new KillSwitches(dataSource);
    }

    /** Tests: the clock of the market-hours gate. */
    public PositionTargets withClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /** Tests: runs between a look's read of a waiting target and its arming (to set the target again in between). */
    private Runnable beforeArm = () -> { };

    PositionTargets beforeArm(Runnable hook) {
        this.beforeArm = hook;
        return this;
    }

    /** Tests: run claimed sales here (e.g. {@code Runnable::run}: on the watching thread). */
    public PositionTargets withSales(Executor sales) {
        this.sales = sales;
        return this;
    }

    /** Entry x (1 + pct %), to the nearest 0.05. */
    /** A % as people read it: "20", "12.5". */
    public static String pctText(double pct) {
        return pct == Math.rint(pct) ? String.valueOf((long) pct) : String.valueOf(pct);
    }

    public static double priceOf(double entry, double pct) {
        return Math.round(entry * (1 + pct / 100) * 20) / 20.0;
    }

    /** The % as stored (two decimals), or null when it is not a target: not above 0 after rounding, or above the max. */
    public static Double pct(Double pct) {
        if (pct == null || !Double.isFinite(pct)) {
            return null;
        }
        double rounded = Math.round(pct * 100) / 100.0;
        return rounded > 0 && rounded <= MAX_PCT ? rounded : null;
    }

    /**
     * The lock's rule for one look. {@code armed}: the bid reached the price at an earlier look; {@code high}: the highest
     * bid since the last look (null: no tick); {@code bid}: the newest fresh bid (null: none fresh).
     */
    static Step decide(boolean armed, Double high, Double bid, double price) {
        if (!armed) {
            return high != null && high >= price ? Step.ARM : Step.NONE;
        }
        return bid != null && bid <= price ? Step.SELL : Step.NONE;
    }

    /** The failed tries' back-off: 5, 10, 20, 40, then 60 s. */
    static Duration backOff(int tries) {
        long seconds = RETRY_EVERY.toSeconds() << Math.min(Math.max(tries - 1, 0), 10);
        return Duration.ofSeconds(Math.min(seconds, RETRY_MAX.toSeconds()));
    }

    // ------------------------------------------------------------------------------------------------ the trader's side

    /**
     * Sets (or replaces) the target of an open position in {@code account}'s book (its PAPER entries for a paper account,
     * its LIVE ones for a broker account) at {@code pct} % above its average entry; it starts WAITING. The same % again
     * changes nothing (an armed lock stays armed), unless it FAILED. IllegalArgumentException when refused.
     */
    public View set(BrokerAccount account, long positionId, double pct, String actor) {
        return set(account, positionId, pct, actor, null);
    }

    /**
     * As above; {@code admin}: the audit detail of the platform admin acting from outside the workspace (by_admin true,
     * admin_reason, actor_role, consent; null: the workspace's own people). It marks the target as the admin's (V55) and
     * goes into the TARGET_SET audit. The same % again by someone else changes nothing either (an armed lock is kept).
     */
    public View set(BrokerAccount account, long positionId, double pct, String actor, Map<String, ?> admin) {
        boolean byAdmin = admin != null && Boolean.TRUE.equals(admin.get("by_admin"));
        String adminReason = byAdmin && admin.get("admin_reason") != null ? String.valueOf(admin.get("admin_reason")) : null;
        if (account.role() == AccountRole.SIGNAL_SOURCE) {
            throw new IllegalArgumentException(account.displayName() + " is a signal source: its positions are not orders");
        }
        Double rounded = pct(pct);
        if (rounded == null) {
            throw new IllegalArgumentException("the target: more than 0 % and at most " + (int) MAX_PCT + " % above the entry");
        }
        Map<String, Object> p = openPosition(account, positionId);
        Optional<View> now = view(positionId);
        if (now.isPresent() && now.get().pct() == rounded
                && List.of("WAITING", "ARMED", "FIRING", "SELLING").contains(now.get().status())) {
            if (now.get().byAdmin() == byAdmin) {
                return now.get();                               // unchanged: an armed lock is not thrown away
            }
            // review 25 Nov 2026: the same % by the other side (the owner over the admin's, or the admin over the owner's)
            // makes it theirs, keeping the lock as it stands (its window, its armed price): only who and why change
            int took = jdbc.update("update exec.position_target set set_by = ?, by_admin = ?, admin_reason = ?, updated_at = "
                    + "now() where position_id = ? and generation = ? and status in ('WAITING', 'ARMED', 'SELLING')", actor,
                    byAdmin, adminReason, positionId, generation(positionId));
            if (took == 0) {
                throw new IllegalArgumentException("the target is selling this position now: wait for the sale");
            }
            Map<String, Object> detail = new LinkedHashMap<>(Map.of("position", positionId, "symbol", p.get("symbol"),
                    "pct", rounded, "replaced", now.get().status(), "taken_over_from", now.get().byAdmin() ? "admin"
                            : "owner"));
            if (admin != null) {
                detail.putAll(admin);
            }
            audit.audit(account, actor, "TARGET_SET", detail);
            return view(positionId).orElseThrow();
        }
        int changed = jdbc.update("insert into exec.position_target (position_id, broker_account_id, target_pct, status, set_by, "
                + "set_at, by_admin, admin_reason) values (?, ?, ?, 'WAITING', ?, now(), ?, ?) on conflict (position_id) do "
                + "update set target_pct = excluded.target_pct, generation = exec.position_target.generation + 1, status = "
                + "'WAITING', set_by = excluded.set_by, set_at = now(), by_admin = excluded.by_admin, admin_reason = "
                + "excluded.admin_reason, armed_at = null, armed_bid = null, armed_price = null, armed_entry = null, "
                + "fired_at = null, fired_bid = null, last_try_at = null, next_try_at = null, tries = 0, last_result = null, "
                + "updated_at = now() where exec.position_target.status <> 'FIRING'", positionId, account.id(), rounded, actor,
                byAdmin, adminReason);
        if (changed == 0) {
            throw new IllegalArgumentException("the target is selling this position now: wait for the sale");
        }
        double entry = ((Number) p.get("average_entry")).doubleValue();
        Map<String, Object> detail = new LinkedHashMap<>(Map.of("position", positionId, "symbol", p.get("symbol"), "pct",
                rounded, "entry", entry, "price", priceOf(entry, rounded), "replaced", now.map(View::status).orElse("none")));
        if (admin != null) {
            detail.putAll(admin);
        }
        audit.audit(account, actor, "TARGET_SET", detail);
        return view(positionId).orElseThrow();
    }

    /** Whether a set or clear changed anything between two reads of a position's target (what to tell, what to count). */
    public static boolean changed(Optional<View> before, Optional<View> after) {
        if (before.isEmpty() || after.isEmpty()) {
            return before.isPresent() != after.isPresent();
        }
        View b = before.get(), a = after.get();
        return b.pct() != a.pct() || b.byAdmin() != a.byAdmin() || !b.status().equals(a.status())
                || !java.util.Objects.equals(b.setAt(), a.setAt());
    }

    private int generation(long positionId) {
        return jdbc.queryForObject("select generation from exec.position_target where position_id = ?", Integer.class,
                positionId);
    }

    /** Removes the target (nothing more is sold by it, a pending sale of the rest included); false when there was none. */
    public boolean clear(BrokerAccount account, long positionId, String actor) {
        return clear(account, positionId, actor, null);
    }

    /** As above; {@code admin}: the platform admin's audit detail (reason, by_admin; see {@link #set}), into TARGET_CLEARED. */
    public boolean clear(BrokerAccount account, long positionId, String actor, Map<String, ?> admin) {
        openPosition(account, positionId);
        int changed = jdbc.update("update exec.position_target set status = 'CLEARED', generation = generation + 1, "
                + "updated_at = now() where position_id = ? and broker_account_id = ? and status in ('WAITING', 'ARMED', "
                + "'SELLING', 'FAILED')", positionId, account.id());
        if (changed == 0) {
            if (view(positionId).map(v -> "FIRING".equals(v.status())).orElse(false)) {
                throw new IllegalArgumentException("the target is selling this position now: it cannot be removed");
            }
            return false;
        }
        Map<String, Object> detail = new LinkedHashMap<>(Map.of("position", positionId));
        if (admin != null) {
            detail.putAll(admin);
        }
        audit.audit(account, actor, "TARGET_CLEARED", detail);
        return true;
    }

    /** An open position of the account's own book (a paper account's PAPER entries, a broker account's LIVE ones). */
    private Map<String, Object> openPosition(BrokerAccount account, long positionId) {
        String book = account.brokerType() == BrokerType.PAPER ? "PAPER" : "LIVE";
        return jdbc.queryForList("select p.id, p.symbol, p.average_entry, p.quantity_open from exec.position p join "
                        + "exec.trade_intent i on i.id = p.entry_trade_intent_id where p.id = ? and p.broker_account_id = ? "
                        + "and p.status = 'OPEN' and p.quantity_open > 0 and i.execution_mode = ?", positionId, account.id(),
                        book).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("position " + positionId
                        + " is not an open " + book + " position of " + account.displayName()));
    }

    /** The account's targets on open positions (not removed), by position id. */
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
        return jdbc.queryForList(SELECT + "where t.position_id = ?", positionId).stream().findFirst().map(PositionTargets::viewOf);
    }

    private static final String SELECT = "select t.position_id, t.broker_account_id, t.target_pct, t.status, t.set_by, "
            + "t.set_at, t.armed_at, t.armed_bid, t.armed_price, t.fired_at, t.fired_bid, t.tries, t.last_result, "
            + "t.next_try_at, t.by_admin, t.admin_reason, p.average_entry from exec.position_target t join exec.position p on p.id = t.position_id ";

    private static View viewOf(Map<String, Object> r) {
        double pct = ((Number) r.get("target_pct")).doubleValue();
        Double armed = dbl(r.get("armed_price"));
        return new View(((Number) r.get("position_id")).longValue(), ((Number) r.get("broker_account_id")).longValue(), pct,
                armed != null ? armed : priceOf(((Number) r.get("average_entry")).doubleValue(), pct),
                (String) r.get("status"), (String) r.get("set_by"), instant(r.get("set_at")), instant(r.get("armed_at")),
                dbl(r.get("armed_bid")), instant(r.get("fired_at")), dbl(r.get("fired_bid")),
                ((Number) r.get("tries")).intValue(), (String) r.get("last_result"), instant(r.get("next_try_at")),
                Boolean.TRUE.equals(r.get("by_admin")), (String) r.get("admin_reason"));
    }

    private static Instant instant(Object o) {
        return o instanceof Timestamp t ? t.toInstant() : null;
    }

    private static Double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    // ------------------------------------------------------------------------------------------------ the watcher

    /**
     * One look at every live target of an open position (the worker calls it every second): arms those whose bid reached
     * the price; claims a sale for those armed whose newest fresh bid is back at or below it, and for those SELLING whose
     * next try is due; each claimed sale runs on its own ({@link #withSales}). Returns one line per thing done or begun.
     */
    public List<String> watch(Exiter exiter) {
        // a sale left by a stopped worker; targets of positions closed by something else
        jdbc.update("update exec.position_target set status = 'SELLING', next_try_at = now(), last_result = 'the sale was "
                + "left unfinished (the worker stopped?): sold again', updated_at = now() where status = 'FIRING' and "
                + "last_try_at < now() - make_interval(secs => ?)", (double) STUCK_AFTER.toSeconds());
        jdbc.update("update exec.position_target t set status = 'ENDED', generation = generation + 1, last_result = coalesce("
                + "t.last_result || ' · ', '') || 'the position was closed by ' || coalesce(p.exit_reason, 'another path'), "
                + "updated_at = now() from exec.position p where p.id = t.position_id and t.status in ('WAITING', 'ARMED', "
                + "'SELLING') and (p.status <> 'OPEN' or p.quantity_open <= 0)");
        boolean marketOpen = marketOpen();
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList("select t.position_id, t.broker_account_id, t.target_pct, "
                + "t.generation, t.status, t.set_at, t.armed_price, t.tries, t.last_result, t.by_admin, t.admin_reason, "
                + "(t.next_try_at is null or "
                + "t.next_try_at <= now()) due, now() db_now, p.average_entry, p.instrument_token, p.instrument_key, "
                + "p.underlying, p.symbol, p.tenant_id from exec.position_target t join exec.position p on p.id = "
                + "t.position_id where t.status in ('WAITING', 'ARMED', 'SELLING') and p.status = 'OPEN' and "
                + "p.quantity_open > 0 order by t.position_id");
        java.util.Set<Long> live = new java.util.HashSet<>();
        for (Map<String, Object> r : rows) {
            long positionId = ((Number) r.get("position_id")).longValue();
            live.add(positionId);
            try {
                if (!exiter.mine(((Number) r.get("broker_account_id")).longValue())) {
                    continue;                                 // another worker holds the account: its watcher looks
                }
                String line = look(r, positionId, marketOpen, exiter);
                if (line != null) {
                    lines.add(line);
                }
            } catch (RuntimeException e) {
                log.warn("target of position {}: {}", positionId, e.toString());
            }
        }
        lookedTo.keySet().retainAll(live);
        return lines;
    }

    private boolean marketOpen() {
        LocalTime t = LocalTime.ofInstant(clock.instant(), IST);
        return !t.isBefore(FIRST) && t.isBefore(LAST);
    }

    private String look(Map<String, Object> r, long positionId, boolean marketOpen, Exiter exiter) {
        int generation = ((Number) r.get("generation")).intValue();
        String status = (String) r.get("status");
        long token = ((Number) r.get("instrument_token")).longValue();
        String key = (String) r.get("instrument_key"), underlying = (String) r.get("underlying");
        long account = ((Number) r.get("broker_account_id")).longValue();
        long tenant = ((Number) r.get("tenant_id")).longValue();
        double entry = ((Number) r.get("average_entry")).doubleValue();
        double pct = ((Number) r.get("target_pct")).doubleValue();
        if ("WAITING".equals(status)) {
            double price = priceOf(entry, pct);
            Timestamp dbNow = (Timestamp) r.get("db_now");
            Timestamp setAt = (Timestamp) r.get("set_at");
            Seen seen = lookedTo.get(positionId);
            Timestamp from = setAt;
            if (seen != null && seen.generation() == generation) {
                Timestamp back = Timestamp.from(seen.upTo().toInstant().minus(OVERLAP));
                from = back.after(setAt) ? back : setAt;
            }
            Map<String, Object> range = high(key, underlying, token, from, dbNow);
            Double high = dbl(range.get("hi"));
            Instant upTo = dbNow.toInstant().minus(OVERLAP);
            if (range.get("t") instanceof Timestamp t && t.toInstant().isAfter(upTo)) {
                upTo = t.toInstant();
            }
            lookedTo.put(positionId, new Seen(generation, Timestamp.from(upTo)));
            if (decide(false, high, null, price) != Step.ARM) {
                return null;
            }
            beforeArm.run();
            int changed = jdbc.update("update exec.position_target set status = 'ARMED', armed_at = now(), armed_bid = ?, "
                    + "armed_price = ?, armed_entry = ?, updated_at = now() where position_id = ? and status = 'WAITING' "
                    + "and generation = ?", high, price, entry, positionId, generation);
            if (changed != 1) {
                return null;                                   // set again or removed meanwhile
            }
            audit.audit(tenant, account, ACTOR, "TARGET_ARMED", Map.of("position", positionId, "symbol", r.get("symbol"),
                    "price", price, "bid", high));
            return String.format(Locale.ROOT, "position %d: target %.2f reached (bid %.2f): a bid back at or below it "
                    + "sells", positionId, price, high);
        }
        if (!marketOpen) {
            return null;
        }
        double price = ((Number) r.get("armed_price")).doubleValue();
        Double bid = null;
        if ("ARMED".equals(status)) {
            bid = latestBid(key, underlying, token);
            if (decide(true, null, bid, price) != Step.SELL) {
                return null;
            }
        } else if (!Boolean.TRUE.equals(r.get("due"))) {
            return null;                                       // SELLING: the next try is not due yet
        }
        int claimed = jdbc.update("update exec.position_target set status = 'FIRING', fired_at = coalesce(fired_at, now()), "
                + "fired_bid = coalesce(fired_bid, ?), last_try_at = now(), updated_at = now() where position_id = ? and "
                + "status = ? and generation = ?", bid, positionId, status, generation);
        if (claimed != 1) {
            return null;                                       // set again, removed or claimed by another worker meanwhile
        }
        String why = bid != null ? String.format(Locale.ROOT, "bid %.2f back at or below the target %.2f", bid, price)
                : String.format(Locale.ROOT, "the rest of the triggered target %.2f", price);
        Map<String, Object> detail = new LinkedHashMap<>(Map.of("target_pct", pct, "target_price", price, "bid",
                bid == null ? "-" : bid, "why", why));
        if (Boolean.TRUE.equals(r.get("by_admin"))) {
            // the admin's target (V55): its sale is the admin's exit in the audit, with the reason (the member's notice)
            detail.put("by_admin", true);
            detail.put("admin_target", true);
            if (r.get("admin_reason") != null) {
                detail.put("admin_reason", r.get("admin_reason"));
            }
        }
        Sale sale = new Sale(positionId, account, tenant, generation, (String) r.get("symbol"), price, bid,
                ((Number) r.get("tries")).intValue(), (String) r.get("last_result"));
        sales.execute(() -> sell(sale, exiter, detail));
        return "position " + positionId + ": " + why + ": selling";
    }

    private record Sale(long positionId, long account, long tenant, int generation, String symbol, double price, Double bid,
                        int tries, String lastResult) {
    }

    /** One claimed sale, on its own thread; then the target's next state. */
    private void sell(Sale s, Exiter exiter, Map<String, ?> detail) {
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
            log.warn("target of position {}: the sale's outcome was not written ({}); repaired after {}", s.positionId(),
                    e.toString(), STUCK_AFTER);
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
            jdbc.update("update exec.position_target set last_result = ?, updated_at = now() where position_id = ? and "
                    + "status = 'FIRING' and generation = ?", truncate("still selling on the account's lane: " + said),
                    s.positionId(), s.generation());
            return;
        } else if (failure != null && sessionLost(failure) || result.stream().anyMatch(l -> l.startsWith("not flattened: "))) {
            next = "SELLING";                                  // log in again (or the route cannot open): not a try
            wait = SESSION_RETRY;
            said = "not sold, the broker cannot be reached (" + said + "): log in to the broker again; the rest is sold "
                    + "after the login";
        } else if (!result.isEmpty() && result.stream().allMatch(l -> l.startsWith("not flattened here"))) {
            next = "SELLING";                                  // another worker holds the account: its watcher sells
            wait = ELSEWHERE_RETRY;
        } else {
            tries++;
            next = tries >= MAX_TRIES ? "FAILED" : "SELLING";
            wait = backOff(tries);
        }
        int updated = jdbc.update("update exec.position_target set status = ?, tries = ?, last_result = ?, next_try_at = "
                + "now() + make_interval(secs => ?), updated_at = now() where position_id = ? and status = 'FIRING' and "
                + "generation = ?", next, tries, truncate(said), (double) wait.toSeconds(), s.positionId(), s.generation());
        if (updated == 1 && (!"SELLING".equals(next) || !said.equals(s.lastResult()))) {
            // one audit row per change, not one per 30 s retry while the session is gone
            audit.audit(s.tenant(), s.account(), ACTOR, "TARGET_SELL", Map.of("position", s.positionId(), "symbol",
                    s.symbol(), "price", s.price(), "bid", s.bid() == null ? "-" : s.bid(), "result", said, "status", next));
        }
        log.info("manual target: position {}: {} ({})", s.positionId(), said, next);
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /** A lost broker session anywhere in the cause chain (the sale's lane wraps what the venue threw). */
    static boolean sessionLost(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof BrokerException b && b.kind() == BrokerException.Kind.SESSION) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String s) {
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    // ------------------------------------------------------------------------------------------------ captured quotes

    /** A tick whose bid is a price: above 0 and not above a quoted ask (a crossed quote is bad data). */
    private static final String SANE = "bid_px[1] > 0 and (ask_px[1] is null or ask_px[1] <= 0 or ask_px[1] >= bid_px[1])";

    /** The contract of a key (expiry, strike, type), or null when ref.instrument does not know it (asked again hourly). */
    private Object[] contract(String key, long token) {
        if (key == null) {
            return null;
        }
        Object[] known = contracts.get(key);
        if (known != null) {
            return known;
        }
        Instant missed = unknownSince.get(key);
        if (missed != null && missed.isAfter(Instant.now().minus(Duration.ofHours(1)))) {
            return null;
        }
        Object[] found = jdbc.queryForList("select expiry, strike, type from ref.instrument where instrument_key = ? and type "
                        + "in ('CE', 'PE') order by snapshot_id desc limit 1", InstrumentKeys.normalize(key, token)).stream()
                .map(c -> new Object[] {c.get("expiry"), c.get("strike"), c.get("type")}).findFirst().orElse(null);
        if (found != null) {
            contracts.put(key, found);
            unknownSince.remove(key);
        } else {
            unknownSince.put(key, Instant.now());
        }
        return found;
    }

    /** The highest bid (hi) and the newest tick time (t) after {@code from} up to {@code now} + 5 s. */
    private Map<String, Object> high(String key, String underlying, long token, Timestamp from, Timestamp now) {
        Timestamp to = Timestamp.from(now.toInstant().plusSeconds(5));
        Object[] c = contract(key, token);
        if (c == null) {
            Timestamp floor = Timestamp.from(now.toInstant().minus(TOKEN_WINDOW));   // no index by token: bounded
            return jdbc.queryForMap("select max(bid_px[1]) hi, max(recv_ts) t from md.option_tick where instrument_token = ? "
                    + "and recv_ts > ? and recv_ts <= ? and " + SANE, token, from.after(floor) ? from : floor, to);
        }
        return jdbc.queryForMap("select max(bid_px[1]) hi, max(recv_ts) t from md.option_tick where underlying = ? and "
                + "expiry = ? and strike = ? and option_type = ? and recv_ts > ? and recv_ts <= ? and " + SANE,
                underlying, c[0], c[1], c[2], from, to);
    }

    /** The newest sane bid of the last {@link #BID_MAX_AGE} by the database's clock, or null. */
    private Double latestBid(String key, String underlying, long token) {
        double age = BID_MAX_AGE.toSeconds();
        Object[] c = contract(key, token);
        List<Double> bid = c == null
                ? jdbc.queryForList("select bid_px[1] from md.option_tick where instrument_token = ? and recv_ts > now() - "
                        + "make_interval(secs => ?) and recv_ts <= now() + interval '5 seconds' and " + SANE
                        + " order by recv_ts desc limit 1", Double.class, token, age)
                : jdbc.queryForList("select bid_px[1] from md.option_tick where underlying = ? and expiry = ? and strike = ? "
                        + "and option_type = ? and recv_ts > now() - make_interval(secs => ?) and recv_ts <= now() + "
                        + "interval '5 seconds' and " + SANE + " order by recv_ts desc limit 1", Double.class, underlying,
                        c[0], c[1], c[2], age);
        return bid.isEmpty() ? null : bid.getFirst();
    }
}
