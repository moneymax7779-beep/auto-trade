package com.autotrade.execution;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;

/**
 * The day's broker and its PAPER/LIVE switch of a {@code DAILY_SWITCH} workspace (trader workspaces, user 7 Nov 2026:
 * "if member not logged in to broker account, it should be PAPER trading ... change the flag to LIVE and then only after
 * any signal should be traded ... not allowed for splitting"; V29, plan section 10).
 * <ul>
 *   <li>The state is an append-only event log ({@code acct.trading_day_event}); {@code acct.trading_state} reads it at any
 *       moment: the broker carries over, the mode only within its IST day, so every day starts PAPER.</li>
 *   <li>An entry goes to one account ({@code acct.entry_target}, as of the decision's own time): the chosen broker's real
 *       account when the day is LIVE there and it can trade LIVE now, else its paper twin; none when Off.</li>
 *   <li>A real broker account that is not today's target and holds nothing is <b>idle</b>: the worker gives it no turn
 *       (no broker connection, no lease, no reconciliation) and closes its exits and adds as NOT_HELD.</li>
 *   <li>The member's daily loss limit counts the LIVE realised net of all their accounts on the day.</li>
 * </ul>
 * {@code ACCOUNT_MODE} workspaces (the owner's) are not touched by any of this.
 */
public final class TradingDay {

    public static final String DAILY_SWITCH = "DAILY_SWITCH";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public enum Mode { PAPER, LIVE }

    /** The workspace's state at a moment: the chosen account (null: Off or nothing chosen), its mode, since when. */
    public record State(Long brokerAccountId, Mode mode, Instant since) {
    }

    /** Orders still working at a broker (any of these may still fill or be cancelled). */
    static final String WORKING_ORDER_STATES = "('CREATED', 'SUBMITTED', 'ACKNOWLEDGED', 'OPEN', 'PARTIALLY_FILLED', "
            + "'CANCEL_PENDING', 'UNKNOWN', 'RECONCILIATION_REQUIRED')";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KillSwitches audit;

    public TradingDay(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.audit = new KillSwitches(dataSource);
    }

    /**
     * Locks the workspace's row for the current transaction: every change of its day (a person's switch, the worker's
     * session-lost fallback) runs one at a time, each reading the state the previous one left (code review 7 Nov 2026).
     */
    public void lock(long tenantId) {
        jdbc.queryForObject("select id from acct.tenant where id = ? for update", Long.class, tenantId);
    }

    public boolean dailySwitch(long tenantId) {
        return DAILY_SWITCH.equals(jdbc.queryForList("select trading_control from acct.tenant where id = ?", String.class,
                tenantId).stream().findFirst().orElse(null));
    }

    public State state(long tenantId, Instant at) {
        return jdbc.query("select broker_account_id, mode, since from acct.trading_state(?, ?)", (rs, i) -> {
            long id = rs.getLong(1);
            Long account = rs.wasNull() ? null : id;
            Timestamp since = rs.getTimestamp(3);
            return new State(account, Mode.valueOf(rs.getString(2)), since == null ? null : since.toInstant());
        }, tenantId, Timestamp.from(at)).stream().findFirst().orElse(new State(null, Mode.PAPER, null));
    }

    /** The account an entry decided at {@code at} goes to; null when none (Off, or nothing chosen). */
    public Long entryTarget(long tenantId, Instant at) {
        return jdbc.queryForObject("select acct.entry_target(?, ?)", Long.class, tenantId, Timestamp.from(at));
    }

    /**
     * The entry target now by the database's clock, the clock the day's events are stamped with (code review 7 Nov 2026:
     * a JVM clock behind the database's would not yet see a switch just made). {@code clock_timestamp()}, not
     * {@code now()}: inside a transaction {@code now()} is its start, before a switch that committed while it waited for
     * the workspace's lock (second review, 7 Nov 2026).
     */
    public Long entryTargetNow(long tenantId) {
        return jdbc.queryForObject("select acct.entry_target(?, clock_timestamp())", Long.class, tenantId);
    }

    /** The state now by the database's clock (the statement's time, see {@link #entryTargetNow}). */
    public State stateNow(long tenantId) {
        return jdbc.query("select broker_account_id, mode, since from acct.trading_state(?, clock_timestamp())", (rs, i) -> {
            long id = rs.getLong(1);
            Long account = rs.wasNull() ? null : id;
            Timestamp since = rs.getTimestamp(3);
            return new State(account, Mode.valueOf(rs.getString(2)), since == null ? null : since.toInstant());
        }, tenantId).stream().findFirst().orElse(new State(null, Mode.PAPER, null));
    }

    /** Appends one event (a person's choice or switch, or the system's); returns its id. */
    public long append(long tenantId, Long brokerAccountId, Mode mode, String reason, Long byAppUserId) {
        return jdbc.queryForObject("insert into acct.trading_day_event (tenant_id, broker_account_id, mode, reason, "
                + "changed_by) values (?, ?, ?, ?, ?) returning id", Long.class, tenantId, brokerAccountId, mode.name(), reason,
                byAppUserId);
    }

    /** The paper twin of a real broker account; null when it has none (or is itself on the PAPER broker). */
    public Long paperTwin(long brokerAccountId) {
        return jdbc.queryForList("select id from acct.broker_account where paper_of_account_id = ?", Long.class,
                brokerAccountId).stream().findFirst().orElse(null);
    }

    /** The member's rupee loss limit across their accounts (LIVE only); null when not set. */
    public Double dailyLossLimit(long tenantId) {
        return jdbc.queryForList("select daily_loss_limit::float8 from acct.tenant where id = ? and daily_loss_limit "
                + "is not null", Double.class, tenantId).stream().findFirst().orElse(null);
    }

    /**
     * Realised net (gross less charges) of the tenant's LIVE positions closed on {@code day} (IST), every broker together:
     * what the member's loss limit counts. Paper positions never count.
     */
    public double liveNet(long tenantId, LocalDate day) {
        Double net = jdbc.queryForObject("select coalesce(sum(p.realised_gross - coalesce(p.charges, 0)), 0) from "
                + "exec.position p join exec.trade_intent i on i.id = p.entry_trade_intent_id where p.tenant_id = ? "
                + "and i.execution_mode = 'LIVE' and p.status = 'CLOSED' "
                + "and (p.closed_at at time zone 'Asia/Kolkata')::date = ?", Double.class, tenantId, Date.valueOf(day));
        return net == null ? 0 : net;
    }

    /** True when the member's loss limit is set and today's LIVE net has reached it. */
    public boolean dailyLossReached(long tenantId, LocalDate day) {
        Double limit = dailyLossLimit(tenantId);
        return limit != null && liveNet(tenantId, day) <= -limit;
    }

    /**
     * The worker lost a real account's broker session. When that account is the workspace's LIVE broker now, the day
     * goes back to PAPER (later entries go to the paper twin; open LIVE positions keep their broker stop and their exits
     * wait for the next login). Audited once per loss. Nothing for an ACCOUNT_MODE workspace.
     */
    public void sessionLost(BrokerAccount account, String actor) {
        if (account.brokerType() == BrokerType.PAPER || !dailySwitch(account.tenantId())) {
            return;
        }
        // under the workspace's lock, read again: a LIVE switch committing meanwhile is seen (and sent back to PAPER), and
        // a broker change made meanwhile is never undone (code review 7 Nov 2026)
        State before = tx.execute(t -> {
            lock(account.tenantId());
            State now = stateNow(account.tenantId());
            if (now.mode() == Mode.LIVE && Long.valueOf(account.id()).equals(now.brokerAccountId())) {
                append(account.tenantId(), account.id(), Mode.PAPER, "SESSION_LOST", null);
                return now;
            }
            return null;
        });
        if (before != null) {
            audit.audit(account, actor, "MODE_PAPER_SESSION_LOST", Map.of("since", String.valueOf(before.since())));
        }
    }

    /**
     * A new broker session began on a real account (a login, also one made while already logged in). A LIVE switch counts
     * only during the session it was made in ({@code acct.entry_target}), so a LIVE day on this account is recorded as
     * PAPER now, matching where entries go: the member switches LIVE again when they want (second review, 7 Nov 2026: the
     * page said LIVE while entries went to the paper twin). Returns true when the day went back to PAPER.
     */
    public boolean sessionStarted(BrokerAccount account, String actor) {
        if (account.brokerType() == BrokerType.PAPER || !dailySwitch(account.tenantId())) {
            return false;
        }
        State before = tx.execute(t -> {
            lock(account.tenantId());
            State now = stateNow(account.tenantId());
            if (now.mode() == Mode.LIVE && Long.valueOf(account.id()).equals(now.brokerAccountId())) {
                append(account.tenantId(), account.id(), Mode.PAPER, "NEW_SESSION", null);
                return now;
            }
            return null;
        });
        if (before != null) {
            audit.audit(account, actor, "MODE_PAPER_NEW_SESSION", Map.of("since", String.valueOf(before.since())));
        }
        return before != null;
    }

    /**
     * A person was disabled: every DAILY_SWITCH workspace of theirs that is LIVE now goes back to PAPER (they can no longer
     * sign in to stop it). Returns the workspaces changed.
     */
    public List<Long> personDisabled(long appUserId, String actor) {
        List<Long> changed = new java.util.ArrayList<>();
        for (long tenant : jdbc.queryForList("select m.tenant_id from acct.tenant_membership m join acct.tenant t on "
                + "t.id = m.tenant_id where m.app_user_id = ? and t.trading_control = 'DAILY_SWITCH' and m.role in "
                + "('OWNER', 'TRADER')", Long.class, appUserId)) {
            Boolean live = tx.execute(t -> {
                lock(tenant);
                State now = stateNow(tenant);
                if (now.mode() == Mode.LIVE) {
                    append(tenant, now.brokerAccountId(), Mode.PAPER, "MEMBER_DISABLED", null);
                    return true;
                }
                return false;
            });
            if (Boolean.TRUE.equals(live)) {
                audit.audit(tenant, null, actor, "MODE_PAPER_MEMBER_DISABLED", Map.of("appUser", appUserId));
                changed.add(tenant);
            }
        }
        return changed;
    }

    /**
     * A real broker account of a DAILY_SWITCH workspace with nothing to do: not today's entry target now, no position
     * that is not closed, no order working at its broker (of the last day), no entry still pending today. The worker
     * leaves it alone. Adds do not count (code review 7 Nov 2026: every add is fanned out to every subscribed account, a
     * LIVE-eligible one in LIVE mode, and an add needs an open position, which is counted already).
     */
    public boolean idle(BrokerAccount account) {
        if (account.brokerType() == BrokerType.PAPER || !dailySwitch(account.tenantId())) {
            return false;
        }
        if (Long.valueOf(account.id()).equals(entryTargetNow(account.tenantId()))) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("select not exists (select 1 from exec.position where "
                + "broker_account_id = ? and status <> 'CLOSED') and not exists (select 1 from exec.order_execution where "
                + "broker_account_id = ? and status in " + WORKING_ORDER_STATES + " and created_at > now() - interval "
                + "'1 day') and not exists (select 1 from exec.trade_intent where broker_account_id = ? and action = 'ENTER' "
                + "and state in ('CREATED', 'VALIDATED', 'EXECUTING', 'RECONCILIATION_REQUIRED') and execution_mode = 'LIVE' "
                + "and (created_at at time zone 'Asia/Kolkata')::date = (now() at time zone 'Asia/Kolkata')::date)",
                Boolean.class, account.id(), account.id(), account.id()));
    }

    /**
     * An idle account's exits and adds: it holds nothing they could apply to, so they are closed now (an old add must
     * never attach to a later position). Returns how many.
     */
    public int closeIdleIntents(long brokerAccountId) {
        return jdbc.update("update exec.trade_intent set state = 'SKIPPED', reject_code = 'NOT_HELD', reject_reason = "
                + "'the account holds nothing (not the day''s broker)', updated_at = now() where broker_account_id = ? "
                + "and state = 'CREATED' and action in ('EXIT', 'ADD')", brokerAccountId);
    }

    /** Accounts of the workspace that hold a LIVE position or have an order working at a broker now. */
    public List<Long> liveExposure(long tenantId) {
        return jdbc.queryForList("select distinct a.id from acct.broker_account a where a.tenant_id = ? "
                + "and a.broker_type <> 'PAPER' and (exists (select 1 from exec.position p where p.broker_account_id = a.id "
                + "and p.status <> 'CLOSED') or exists (select 1 from exec.order_execution e where e.broker_account_id = a.id "
                + "and e.status in " + WORKING_ORDER_STATES + ")) order by a.id", Long.class, tenantId);
    }

    public static LocalDate today() {
        return LocalDate.now(IST);
    }
}
