package com.autotrade.execution;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.autotrade.account.AccountRole;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.TradeMode;

import tools.jackson.databind.ObjectMapper;

/**
 * The trader's chart orders ({@code exec.chart_order}, V38; plan docs/plans/2026-11-14-chart-line-trading-plan.md, P0):
 * an entry, a stop and a target line on the index chart, armed for one follower account. This class arms, moves,
 * cancels and records them; {@link ChartOrderEngine} watches the index and acts. Every change raises the order's
 * version and is written to {@code exec.chart_order_event} with who made it and the index at that moment.
 * <p>
 * P0 rules: PAPER accounts only; one working chart order per account and index (the manual path holds one manual
 * position per index); 1 to {@link ManualOrders#MAX_LOTS} lots; entries until {@code valid_until} (14:45 by default,
 * never after the last manual buy time); a refusal is an IllegalArgumentException with the reason for the trader.
 */
public final class ChartOrders {

    /** The statuses of an order still at work (the unique index allows one per account and index). */
    public static final List<String> WORKING = List.of("ARMED", "TRIGGERED", "OPEN", "EXITING");
    public static final LocalTime DEFAULT_VALID_UNTIL = LocalTime.of(14, 45);
    /** The index must have ticked this recently for a line to be armed or moved against it. */
    static final Duration SPOT_MAX_AGE = Duration.ofSeconds(60);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One chart order as stored. */
    public record Order(long id, long tenantId, long accountId, long copyGroupId, LocalDate day, String createdBy,
                        String underlying, String side, ChartLines.Kind kind, double entry, double stop, double target,
                        double buffer, double noChase, int lots, LocalTime validUntil, String status, String reason,
                        int version, String symbol, Long entryIntentId, Long exitIntentId, Long positionId,
                        Instant triggeredAt, String exitTrigger) {

        public ChartLines.Lines lines() {
            return new ChartLines.Lines(side, entry, stop, target, buffer, noChase);
        }
    }

    /**
     * What the trader arms; {@code validUntil}, {@code buffer} and {@code noChase} null = the defaults; {@code expectKind}
     * the entry kind the ticket showed (null: not checked), refused when the lines arm as another now (D7).
     */
    public record Arm(String underlying, String side, double entry, double stop, double target, int lots,
                      LocalTime validUntil, Double buffer, Double noChase, String expectKind) {

        public Arm(String underlying, String side, double entry, double stop, double target, int lots,
                   LocalTime validUntil, Double buffer, Double noChase) {
            this(underlying, side, entry, stop, target, lots, validUntil, buffer, noChase, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final KillSwitches kills;
    private final ManualOrders manual;
    private Clock clock = Clock.systemUTC();

    public ChartOrders(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.kills = new KillSwitches(dataSource);
        this.manual = new ManualOrders(dataSource);
    }

    /** Tests: the clock of trading hours, valid-until and the spot's age. */
    public ChartOrders withClock(Clock clock) {
        this.clock = clock;
        manual.withClock(clock);
        return this;
    }

    // ------------------------------------------------------------------------------------------------- the trader

    /** Arms a chart order on {@code account} for {@code day}; returns it as stored. */
    public Order arm(BrokerAccount account, long copyGroupId, Arm a, String actor, LocalDate day) {
        String u = a.underlying() == null ? "" : a.underlying().trim().toUpperCase(java.util.Locale.ROOT);
        String side = a.side() == null ? "" : a.side().trim().toUpperCase(java.util.Locale.ROOT);
        if (!ManualOrders.UNDERLYINGS.contains(u)) {
            throw new IllegalArgumentException("index: NIFTY or SENSEX");
        }
        if (account.role() != AccountRole.FOLLOWER_TRADE || !account.enabled()) {
            throw new IllegalArgumentException(account.displayName() + " is not an enabled follower account");
        }
        if (account.tradeMode() != TradeMode.PAPER) {
            throw new IllegalArgumentException("chart orders are PAPER only in this first version; " + account.displayName()
                    + " is " + account.tradeMode());
        }
        if (a.lots() < 1 || a.lots() > ManualOrders.MAX_LOTS) {
            throw new IllegalArgumentException("lots: 1 to " + ManualOrders.MAX_LOTS);
        }
        LocalTime last = ManualOrders.LAST_BUY;
        LocalTime until = a.validUntil() == null ? (DEFAULT_VALID_UNTIL.isBefore(last) ? DEFAULT_VALID_UNTIL : last)
                : a.validUntil();
        if (until.isAfter(last)) {
            throw new IllegalArgumentException("valid until " + until + ": entries end at " + last + " IST");
        }
        if (day.equals(LocalDate.ofInstant(clock.instant(), IST))) {
            LocalTime now = LocalTime.ofInstant(clock.instant(), IST);
            if (now.isBefore(ManualOrders.FIRST_BUY)) {
                throw new IllegalArgumentException("chart orders can be armed from " + ManualOrders.FIRST_BUY + " IST");
            }
            if (!now.isBefore(until)) {
                throw new IllegalArgumentException("valid until " + until + " has passed");
            }
        }
        String notTarget = manual.notEntryTarget(account, day);
        if (notTarget != null) {
            throw new IllegalArgumentException(notTarget);
        }
        String killed = kills.engaged(account, ManualOrders.STRATEGY, null);
        if (killed != null) {
            throw new IllegalArgumentException("kill switch engaged (" + killed + "): nothing armed");
        }
        requireEngine(day, "nothing armed");
        List<String> held = jdbc.queryForList("select symbol from exec.position where broker_account_id = ? and strategy_id = ? "
                + "and underlying = ? and status <> 'CLOSED'", String.class, account.id(), ManualOrders.STRATEGY, u);
        if (!held.isEmpty()) {
            throw new IllegalArgumentException(account.displayName() + " already holds manual " + held.getFirst()
                    + ": exit it first");
        }
        double buffer = a.buffer() == null ? ChartLines.defaultBuffer(u) : a.buffer();
        double noChase = a.noChase() == null ? ChartLines.defaultNoChase(u) : a.noChase();
        ChartLines.Lines lines = new ChartLines.Lines(side, a.entry(), a.stop(), a.target(), buffer, noChase);
        double spot = spot(u, day);
        ChartLines.Kind kind = ChartLines.validate(lines, spot);
        ChartLines.requireKind(kind, a.expectKind(), spot);
        long id;
        try {
            id = jdbc.queryForObject("insert into exec.chart_order (tenant_id, broker_account_id, copy_group_id, "
                    + "session_date, created_by, underlying, option_side, entry_kind, entry_price, stop_price, target_price, "
                    + "buffer_points, no_chase_points, lots, valid_until, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                    + "?, ?, 'ARMED') returning id", Long.class, account.tenantId(), account.id(), copyGroupId,
                    Date.valueOf(day), actor, u, side, kind.name(), a.entry(), a.stop(), a.target(), buffer, noChase,
                    a.lots(), Time.valueOf(until));
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException(account.displayName() + " already has a working " + u
                    + " chart order: cancel or move it");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("side", side);
        detail.put("kind", kind.name());
        detail.put("entry", a.entry());
        detail.put("stop", a.stop());
        detail.put("target", a.target());
        detail.put("lots", a.lots());
        detail.put("valid_until", until.toString());
        event(id, "ARMED", actor, spot, detail);
        kills.audit(account, actor, "CHART_ORDER_ARMED", Map.of("chart_order", id, "underlying", u, "side", side,
                "lots", a.lots()));
        return byId(id).orElseThrow();
    }

    /**
     * Moves lines of a waiting order (any of the three) or an open one (stop and target; its entry is filled). {@code
     * version}: the version the trader's screen showed (refused when the order changed since: reload). Null = unchanged.
     */
    public Order move(BrokerAccount account, long id, int version, Double entry, Double stop, Double target, String actor,
                      LocalDate day) {
        Order o = owned(account, id);
        if (o.version() != version) {
            throw new IllegalArgumentException("chart order #" + id + " changed meanwhile (now " + o.status()
                    + "): look again and move it from there");
        }
        if ("ARMED".equals(o.status())) {
            // second review: only a waiting order needs the engine to be moved; an open position's stop or target is read
            // by the engine when it starts again, so a tighter stop is never refused while it is down
            requireEngine(day, "the line was not moved");
        }
        double e = entry == null ? o.entry() : entry, s = stop == null ? o.stop() : stop, t = target == null ? o.target() : target;
        ChartLines.Lines lines = new ChartLines.Lines(o.side(), e, s, t, o.buffer(), o.noChase());
        double spot = spot(o.underlying(), day);
        ChartLines.Kind kind = o.kind();
        switch (o.status()) {
            case "ARMED" -> kind = ChartLines.validateArmedMove(lines, o.kind(), spot, e != o.entry());
            case "OPEN" -> {
                if (e != o.entry()) {
                    throw new IllegalArgumentException("the entry of an open position is where it filled: move the stop or "
                            + "the target");
                }
                ChartLines.validateOpen(lines, spot);
            }
            default -> throw new IllegalArgumentException("chart order #" + id + " is " + o.status()
                    + ": its lines cannot move now");
        }
        int moved = jdbc.update("update exec.chart_order set entry_price = ?, stop_price = ?, target_price = ?, entry_kind = ?, "
                + "version = version + 1, updated_at = now() where id = ? and version = ? and status = ?", e, s, t,
                kind.name(), id, version, o.status());
        if (moved == 0) {
            throw new IllegalArgumentException("chart order #" + id + " changed meanwhile: look again and move it from there");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", o.status());
        if (e != o.entry()) {
            detail.put("entry", List.of(o.entry(), e));
        }
        if (s != o.stop()) {
            detail.put("stop", List.of(o.stop(), s));
        }
        if (t != o.target()) {
            detail.put("target", List.of(o.target(), t));
        }
        if (kind != o.kind()) {
            detail.put("kind", kind.name());
        }
        event(id, "MOVED", actor, spot, detail);
        return byId(id).orElseThrow();
    }

    /** Cancels a waiting order (nothing was bought). A triggered or open one is ended with Exit, not Cancel. */
    public Order cancel(BrokerAccount account, long id, String actor) {
        Order o = owned(account, id);
        if (!"ARMED".equals(o.status())) {
            throw new IllegalArgumentException("chart order #" + id + " is " + o.status() + (WORKING.contains(o.status())
                    ? ": the buy is written, use Exit now" : ": nothing to cancel"));
        }
        if (!transition(id, o.version(), "ARMED", "CANCELLED", "cancelled by the trader", Map.of())) {
            throw new IllegalArgumentException("chart order #" + id + " changed meanwhile: look again");
        }
        event(id, "CANCELLED", actor, null, Map.of());
        return byId(id).orElseThrow();
    }

    /**
     * Exit now, before the sale (code review 15 Nov 2026): a waiting order is cancelled and true is returned (nothing to
     * sell). A triggered or open one is marked {@code exit_trigger = TRADER_EXIT} and false is returned; the caller then
     * runs the manual EXIT of its index (cancels a buy not yet run, sells the position) and {@link #exitRan}. The status
     * is not changed here: the order stays watched with its stop until the engine sees the position closed, so an Exit
     * that did not sell (no broker route, a queued lane, a refused sell) never leaves a position without its lines. A buy
     * written after this mark is sold by the engine as soon as it fills ({@link ChartOrderEngine}).
     */
    public boolean exitNow(BrokerAccount account, long id, String actor) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Order o = owned(account, id);
            if (!WORKING.contains(o.status())) {
                throw new IllegalArgumentException("chart order #" + id + " is " + o.status() + ": nothing to exit");
            }
            if (!"ARMED".equals(o.status())) {
                jdbc.update("update exec.chart_order set exit_trigger = 'TRADER_EXIT', updated_at = now() where id = ? and "
                        + "status in ('TRIGGERED', 'OPEN')", id);
                return false;
            }
            if (transition(id, o.version(), "ARMED", "CANCELLED", "Exit now before the entry", Map.of())) {
                event(id, "EXIT_NOW", actor, null, Map.of("result", List.of("cancelled before the entry")));
                return true;
            }
            // changed meanwhile (the engine fired, or a line moved): look again
        }
        throw new IllegalArgumentException("chart order #" + id + " keeps changing: look again and Exit from there");
    }

    /** Exit now, after the manual EXIT ran: its result lines are recorded. */
    public Order exitRan(BrokerAccount account, long id, String actor, List<String> result) {
        owned(account, id);
        event(id, "EXIT_NOW", actor, null, Map.of("result", result));
        return byId(id).orElseThrow();
    }

    /** The account's chart orders of {@code day}, newest first, each with its events. */
    public List<Map<String, Object>> today(long accountId, LocalDate day) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Order o : jdbc.query(SELECT + " where broker_account_id = ? and session_date = ? order by id desc limit 50", ROW,
                accountId, Date.valueOf(day))) {
            Map<String, Object> m = view(o);
            m.put("events", jdbc.queryForList("select to_char(at at time zone 'Asia/Kolkata', 'HH24:MI:SS') at, kind, actor, "
                    + "index_price \"index\", detail::text detail from exec.chart_order_event where chart_order_id = ? "
                    + "order by id", o.id()));
            if (o.positionId() != null) {
                jdbc.queryForList("select quantity_open open, average_entry entry, status, exit_reason, realised_gross gross, "
                        + "charges from exec.position where id = ?", o.positionId()).stream().findFirst()
                        .ifPresent(p -> m.put("position", p));
            }
            out.add(m);
        }
        return out;
    }

    /** An order as the API shows it. */
    public static Map<String, Object> view(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id());
        m.put("accountId", o.accountId());
        m.put("underlying", o.underlying());
        m.put("side", o.side());
        m.put("kind", o.kind().name());
        m.put("entry", o.entry());
        m.put("stop", o.stop());
        m.put("target", o.target());
        m.put("buffer", o.buffer());
        m.put("noChase", o.noChase());
        m.put("lots", o.lots());
        m.put("validUntil", o.validUntil().toString());
        m.put("status", o.status());
        m.put("reason", o.reason());
        m.put("version", o.version());
        m.put("symbol", o.symbol());
        m.put("createdBy", o.createdBy());
        return m;
    }

    // ------------------------------------------------------------------------------------------------- the engine

    /** Orders still at work on {@code day} (ARMED, TRIGGERED, OPEN, EXITING), oldest first. */
    public List<Order> working(LocalDate day) {
        return jdbc.query(SELECT + " where session_date = ? and status in ('ARMED', 'TRIGGERED', 'OPEN', 'EXITING') order by id",
                ROW, Date.valueOf(day));
    }

    /** Working orders of days before {@code day} (positions the end-of-day square-off closed, waiting to be seen). */
    public List<Order> workingBefore(LocalDate day) {
        return jdbc.query(SELECT + " where session_date < ? and status in ('TRIGGERED', 'OPEN', 'EXITING') order by id", ROW,
                Date.valueOf(day));
    }

    public Optional<Order> byId(long id) {
        return jdbc.query(SELECT + " where id = ?", ROW, id).stream().findFirst();
    }

    /**
     * Moves the order from {@code from} to {@code to} if it is still at {@code version} (any version when negative);
     * {@code sets}: other columns to set with it (column name to value). False when it changed meanwhile.
     */
    public boolean transition(long id, int version, String from, String to, String reason, Map<String, Object> sets) {
        // code review 15 Nov 2026: the reason is the new status's own (null clears it: an EXITING order showed the reason of
        // the exit attempt before it)
        StringBuilder sql = new StringBuilder("update exec.chart_order set status = ?, status_reason = ?, "
                + "version = version + 1, updated_at = now()");
        List<Object> args = new ArrayList<>(List.of(to));
        args.add(reason);
        for (Map.Entry<String, Object> e : sets.entrySet()) {
            if (!e.getKey().matches("[a-z_]+")) {
                throw new IllegalArgumentException("column " + e.getKey());
            }
            sql.append(", ").append(e.getKey()).append(" = ?");
            args.add(e.getValue() instanceof Instant i ? Timestamp.from(i) : e.getValue());
        }
        sql.append(" where id = ? and status = ?");
        args.add(id);
        args.add(from);
        if (version >= 0) {
            sql.append(" and version = ?");
            args.add(version);
        }
        return jdbc.update(sql.toString(), args.toArray()) == 1;
    }

    /**
     * The engine's repair of a change it began but did not finish (code review 15 Nov 2026: a crash, or a write that
     * failed, between a status change and its intent): {@code from} → {@code to} only while the order is still at
     * {@code version}, {@code column} (its intent) is still unset, and nothing changed it for {@code quietSeconds} by the
     * database's clock. False when any of that does not hold.
     */
    boolean repair(long id, int version, String from, String to, String reason, String column, int quietSeconds) {
        if (!column.matches("[a-z_]+")) {
            throw new IllegalArgumentException("column " + column);
        }
        return jdbc.update("update exec.chart_order set status = ?, status_reason = ?, version = version + 1, updated_at = "
                + "now() where id = ? and status = ? and version = ? and " + column + " is null and updated_at < now() - "
                + "make_interval(secs => ?)", to, reason, id, from, version, quietSeconds) == 1;
    }

    /** The trade intent written under an idempotency key (the decision's key + ":" + the account), or null. */
    public Long intentByKey(String key) {
        return jdbc.queryForList("select id from exec.trade_intent where idempotency_key = ?", Long.class, key).stream()
                .findFirst().orElse(null);
    }

    /** Sets columns of an order without a status change (the entry's intent and symbol once written). */
    void set(long id, String column, Object value) {
        if (!column.matches("[a-z_]+")) {
            throw new IllegalArgumentException("column " + column);
        }
        jdbc.update("update exec.chart_order set " + column + " = ?, updated_at = now() where id = ?", value, id);
    }

    /** Records one event of an order. */
    public void event(long id, String kind, String actor, Double index, Map<String, Object> detail) {
        String json;
        try {
            json = JSON.writeValueAsString(detail);
        } catch (RuntimeException e) {
            json = "{}";
        }
        jdbc.update("insert into exec.chart_order_event (chart_order_id, kind, actor, index_price, detail) values (?, ?, ?, ?, "
                + "?::jsonb)", id, kind, actor, index == null || index.isNaN() ? null : index, json);
    }

    /** The latest index tick of {@code underlying} on {@code day} no older than {@link #SPOT_MAX_AGE}. */
    public double spot(String underlying, LocalDate day) {
        Instant now = clock.instant();
        return jdbc.queryForList("select price from md.index_tick where underlying = ? and session_date = ? and recv_ts > ? "
                + "and recv_ts <= ? order by recv_ts desc limit 1", Double.class, underlying, Date.valueOf(day),
                Timestamp.from(now.minus(SPOT_MAX_AGE)), Timestamp.from(now.plusSeconds(5))).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no " + underlying + " index price in the last "
                        + SPOT_MAX_AGE.toSeconds() + " s: the market feed is not running"));
    }

    /** How old the engine's heartbeat may be for arm and move (it beats about once a second). */
    static final java.time.Duration ENGINE_STALE = java.time.Duration.ofSeconds(10);

    /**
     * When the chart-order engine last synced for {@code day} (the database's clock), and whether that is recent enough
     * to arm or move against; never: null. Review 16 Nov 2026 (D6).
     */
    public record Engine(boolean running, Instant lastSeen) {
    }

    public Engine engine(LocalDate day) {
        Map<String, Object> row = jdbc.queryForMap("select (select beat_at from exec.chart_engine_beat where session_date = ?) "
                + "as beat, (select beat_at > now() - make_interval(secs => ?) from exec.chart_engine_beat where session_date "
                + "= ?) as fresh", Date.valueOf(day), (double) ENGINE_STALE.toSeconds(), Date.valueOf(day));
        Timestamp beat = (Timestamp) row.get("beat");
        return new Engine(Boolean.TRUE.equals(row.get("fresh")), beat == null ? null : beat.toInstant());
    }

    /** Refuses when no engine is watching the day's chart orders: an armed or moved line would never fire. */
    private void requireEngine(LocalDate day, String outcome) {
        Engine e = engine(day);
        if (!e.running()) {
            throw new IllegalArgumentException("the chart-order engine is not running ("
                    + (e.lastSeen() == null ? "not seen today" : "last seen " + LocalTime.ofInstant(e.lastSeen(), IST)
                            .withNano(0) + " IST") + "; it runs inside the trading session): " + outcome);
        }
    }

    private Order owned(BrokerAccount account, long id) {
        Order o = byId(id).orElseThrow(() -> new IllegalArgumentException("no chart order " + id));
        if (o.accountId() != account.id()) {
            throw new IllegalArgumentException("no chart order " + id + " on " + account.displayName());
        }
        return o;
    }

    private static final String SELECT = "select id, tenant_id, broker_account_id, copy_group_id, session_date, created_by, "
            + "underlying, option_side, entry_kind, entry_price, stop_price, target_price, buffer_points, no_chase_points, lots, "
            + "valid_until, status, status_reason, version, symbol, entry_intent_id, exit_intent_id, position_id, triggered_at, "
            + "exit_trigger from exec.chart_order";

    private static final RowMapper<Order> ROW = ChartOrders::row;

    private static Order row(ResultSet rs, int n) throws SQLException {
        Timestamp triggered = rs.getTimestamp("triggered_at");
        return new Order(rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("broker_account_id"),
                rs.getLong("copy_group_id"), rs.getDate("session_date").toLocalDate(), rs.getString("created_by"),
                rs.getString("underlying"), rs.getString("option_side"), ChartLines.Kind.valueOf(rs.getString("entry_kind")),
                rs.getDouble("entry_price"), rs.getDouble("stop_price"), rs.getDouble("target_price"),
                rs.getDouble("buffer_points"), rs.getDouble("no_chase_points"), rs.getInt("lots"),
                rs.getTime("valid_until").toLocalTime(), rs.getString("status"), rs.getString("status_reason"),
                rs.getInt("version"), rs.getString("symbol"), nullable(rs, "entry_intent_id"), nullable(rs, "exit_intent_id"),
                nullable(rs, "position_id"), triggered == null ? null : triggered.toInstant(), rs.getString("exit_trigger"));
    }

    private static Long nullable(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
