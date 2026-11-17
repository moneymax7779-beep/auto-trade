package com.autotrade.execution;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One signal decision becomes one trade batch and one trade intent per follower that subscribes to its strategy
 * (zt-tiger-v2's pipeline, docs/LIVE-TRADING-PLAN.md section 1). Committed before any execution; idempotent: a decision
 * is batched once (key SIG:decision key) and each account gets one intent (key decision key:account). Eligible
 * followers: role FOLLOWER_TRADE, enabled, an enabled subscription of the group for the strategy, and a tenant entitled
 * to the group (its own, or one it is shared with: {@code acct.copy_group_share}; each such tenant gets its own batch).
 * The intent's mode is the subscription's override, else the account's trade mode; a LIVE intent also needs the
 * account's live flag. An ENTER or ADD reaches a follower only when its subscription was enabled before the decision
 * ({@code enabled_at}, V27: no entry hours late at the signal's old price); exits always do. A subscription made after
 * the session's day (a replay, a back-test of followers) takes the whole day.
 * <p>
 * <b>DAILY_SWITCH workspaces</b> (trader workspaces, V29, {@link TradingDay}): an ENTER goes to one account only, the
 * workspace's entry target as of the decision's own time ({@code acct.entry_target}: the chosen broker's real account on
 * a LIVE day with a session, else its paper twin, none when Off), and at most one ENTER intent is ever made for the
 * workspace's batch, so a switch landing between two ticks cannot send the same entry to both. An ENTER whose batch was
 * made before a switch of the workspace gets no intent after it (17 Nov 2026): before a workspace's first switch the
 * target is its default broker as it is now, so the first switch would otherwise hand the day's earlier entries, which
 * had no account, to the new broker hours late at their old price. A switch between a batch and its intent in the same
 * fan-out drops that one entry. ADD and EXIT go to every subscribed account as above (an account holding nothing skips
 * them).
 * <p>
 * <b>Manual orders</b> ({@link ManualOrders#STRATEGY}, 9 Nov 2026): never fanned out; followers do not copy a trader's
 * BUY CE / BUY PE.
 */
public final class FanOut {

    private final JdbcTemplate jdbc;
    /** The clock a subscription's {@code enabled_at} is read from; null: the database's now() (tests set their own). */
    private java.time.Clock clock;

    public FanOut(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Tests: stamps {@code enabled_at} from {@code clock}, the clock their decisions' {@code decided_at} comes from (the
     * database container's clock runs a few ms off the JVM's, so a decision made right after subscribing could fall
     * "before" it and never fan out).
     */
    FanOut withClock(java.time.Clock clock) {
        this.clock = clock;
        return this;
    }

    /** Fans out every not yet batched decision of the group's day; returns the number of intents created. */
    public int fanOut(long copyGroupId, LocalDate day) {
        Date date = Date.valueOf(day);
        // one batch per decision and entitled tenant: the group's own and each tenant it is shared with (V27)
        jdbc.update("insert into exec.trade_batch (tenant_id, copy_group_id, signal_decision_id, source, idempotency_key, "
                + "status) select t.tenant_id, d.copy_group_id, d.id, 'SIGNAL', 'SIG:' || d.idempotency_key, 'CREATED' "
                + "from exec.signal_decision d join (select g.id, g.tenant_id from acct.copy_group g "
                + "union select s.copy_group_id, s.tenant_id from acct.copy_group_share s) t on t.id = d.copy_group_id "
                + "where d.copy_group_id = ? and d.session_date = ? and d.strategy_id <> '" + ManualOrders.STRATEGY + "' "
                + "on conflict (tenant_id, idempotency_key) do nothing", copyGroupId, date);
        // one intent per subscribed, eligible follower
        // the accounts that may take a decision of the group's day (the switch rules of a DAILY_SWITCH entry come after)
        String eligible = "from exec.trade_batch b join exec.signal_decision d on d.id = b.signal_decision_id "
                + "join acct.tenant tn on tn.id = b.tenant_id "
                + "join acct.strategy_subscription s on s.copy_group_id = d.copy_group_id and s.strategy_id = d.strategy_id "
                + "and s.enabled join acct.broker_account a on a.id = s.broker_account_id "
                + "where d.copy_group_id = ? and d.session_date = ? and a.account_role = 'FOLLOWER_TRADE' and a.enabled "
                // a trader's manual order is never copied: its own batch and intent are written by ManualOrders
                + "and d.strategy_id <> '" + ManualOrders.STRATEGY + "' "
                + "and a.tenant_id = b.tenant_id "
                + "and (coalesce(s.mode_override, a.trade_mode) <> 'LIVE' or a.live_trading_enabled) "
                + "and (d.action = 'EXIT' or s.enabled_at <= d.decided_at "
                + "or (s.enabled_at at time zone 'Asia/Kolkata')::date > d.session_date) ";
        // a DAILY_SWITCH workspace's entry: its target as of the decision, once per batch
        String target = "a.id = acct.entry_target(b.tenant_id, d.decided_at) and not exists (select 1 from exec.trade_intent x "
                + "where x.trade_batch_id = b.id and x.action = 'ENTER') ";
        // ... and never one made late by a switch after its batch (17 Nov 2026: a first switch re-pointed the as-of target
        // of earlier decisions, which had no account until then, to the new broker's twin)
        String switchedAfter = "exists (select 1 from acct.trading_day_event e where e.tenant_id = b.tenant_id "
                + "and e.changed_at > b.created_at)";
        int created = jdbc.update("insert into exec.trade_intent (tenant_id, trade_batch_id, broker_account_id, broker_type, "
                + "execution_mode, strategy_id, priority, underlying, action, option_side, instrument_token, symbol, state, "
                + "idempotency_key) "
                + "select b.tenant_id, b.id, a.id, a.broker_type, coalesce(s.mode_override, a.trade_mode), d.strategy_id, "
                + "d.priority, d.underlying, d.action, d.option_side, d.instrument_token, d.symbol, 'CREATED', "
                + "d.idempotency_key || ':' || a.id " + eligible
                + "and (tn.trading_control <> 'DAILY_SWITCH' or d.action <> 'ENTER' or (" + target + "and not " + switchedAfter
                + ")) "
                + "order by d.decided_at, d.priority, d.id, a.id "                 // intent ids in decision order
                + "on conflict (tenant_id, broker_account_id, idempotency_key) do nothing", copyGroupId, date);
        // an entry the switch rule held back is said once (review 17 Nov 2026: it was dropped in silence)
        jdbc.update("insert into exec.audit_log (tenant_id, broker_account_id, actor, action, detail) "
                + "select distinct on (b.id) b.tenant_id, a.id, 'fan-out', 'ENTRY_DROPPED_SWITCH', jsonb_build_object("
                + "'batch', b.id, 'decision', d.id, 'strategy', d.strategy_id, 'symbol', d.symbol, 'decidedAt', d.decided_at, "
                + "'reason', 'the day''s broker or mode changed after this signal: no entry is made late') " + eligible
                + "and tn.trading_control = 'DAILY_SWITCH' and d.action = 'ENTER' and " + target + "and " + switchedAfter + " "
                + "and not exists (select 1 from exec.audit_log l where l.tenant_id = b.tenant_id "
                + "and l.action = 'ENTRY_DROPPED_SWITCH' and l.detail->>'batch' = b.id::text) order by b.id, a.id",
                copyGroupId, date);
        jdbc.update("update exec.trade_batch b set status = 'FANNED_OUT' from exec.signal_decision d "
                + "where d.id = b.signal_decision_id and d.copy_group_id = ? and d.session_date = ? and b.status = 'CREATED'",
                copyGroupId, date);
        return created;
    }

    /**
     * Subscribes a follower account to strategies of a copy group (enabled), once per strategy. A subscription that was
     * off counts from now ({@code enabled_at}); one already on keeps its time.
     */
    public void subscribe(long copyGroupId, long brokerAccountId, List<String> strategyIds, double sizeMultiplier) {
        java.sql.Timestamp at = clock == null ? null : java.sql.Timestamp.from(clock.instant());
        for (String strategyId : strategyIds) {
            jdbc.update("insert into acct.strategy_subscription (copy_group_id, broker_account_id, strategy_id, enabled, "
                    + "size_multiplier, enabled_at) values (?, ?, ?, true, ?, coalesce(cast(? as timestamptz), now())) "
                    + "on conflict (copy_group_id, broker_account_id, strategy_id) "
                    + "do update set enabled = true, size_multiplier = excluded.size_multiplier, enabled_at = case "
                    + "when acct.strategy_subscription.enabled then acct.strategy_subscription.enabled_at "
                    + "else excluded.enabled_at end",
                    copyGroupId, brokerAccountId, strategyId, sizeMultiplier, at);
        }
    }
}
