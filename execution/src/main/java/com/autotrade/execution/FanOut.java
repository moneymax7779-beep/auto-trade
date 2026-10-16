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
 * followers: role FOLLOWER_TRADE, enabled, an enabled subscription of the group for the strategy. The intent's mode is
 * the subscription's override, else the account's trade mode; a LIVE intent also needs the account's live flag.
 */
public final class FanOut {

    private final JdbcTemplate jdbc;

    public FanOut(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Fans out every not yet batched decision of the group's day; returns the number of intents created. */
    public int fanOut(long copyGroupId, LocalDate day) {
        Date date = Date.valueOf(day);
        // one batch per decision
        jdbc.update("insert into exec.trade_batch (tenant_id, copy_group_id, signal_decision_id, source, idempotency_key, "
                + "status) select g.tenant_id, d.copy_group_id, d.id, 'SIGNAL', 'SIG:' || d.idempotency_key, 'CREATED' "
                + "from exec.signal_decision d join acct.copy_group g on g.id = d.copy_group_id "
                + "where d.copy_group_id = ? and d.session_date = ? "
                + "on conflict (tenant_id, idempotency_key) do nothing", copyGroupId, date);
        // one intent per subscribed, eligible follower
        int created = jdbc.update("insert into exec.trade_intent (tenant_id, trade_batch_id, broker_account_id, broker_type, "
                + "execution_mode, strategy_id, priority, underlying, action, option_side, instrument_token, symbol, state, "
                + "idempotency_key) "
                + "select b.tenant_id, b.id, a.id, a.broker_type, coalesce(s.mode_override, a.trade_mode), d.strategy_id, "
                + "d.priority, d.underlying, d.action, d.option_side, d.instrument_token, d.symbol, 'CREATED', "
                + "d.idempotency_key || ':' || a.id "
                + "from exec.trade_batch b join exec.signal_decision d on d.id = b.signal_decision_id "
                + "join acct.strategy_subscription s on s.copy_group_id = d.copy_group_id and s.strategy_id = d.strategy_id "
                + "and s.enabled join acct.broker_account a on a.id = s.broker_account_id "
                + "where d.copy_group_id = ? and d.session_date = ? and a.account_role = 'FOLLOWER_TRADE' and a.enabled "
                + "and a.tenant_id = b.tenant_id "
                + "and (coalesce(s.mode_override, a.trade_mode) <> 'LIVE' or a.live_trading_enabled) "
                + "on conflict (tenant_id, broker_account_id, idempotency_key) do nothing", copyGroupId, date);
        jdbc.update("update exec.trade_batch b set status = 'FANNED_OUT' from exec.signal_decision d "
                + "where d.id = b.signal_decision_id and d.copy_group_id = ? and d.session_date = ? and b.status = 'CREATED'",
                copyGroupId, date);
        return created;
    }

    /** Subscribes a follower account to strategies of a copy group (enabled), once per strategy. */
    public void subscribe(long copyGroupId, long brokerAccountId, List<String> strategyIds, double sizeMultiplier) {
        for (String strategyId : strategyIds) {
            jdbc.update("insert into acct.strategy_subscription (copy_group_id, broker_account_id, strategy_id, enabled, "
                    + "size_multiplier) values (?, ?, ?, true, ?) on conflict (copy_group_id, broker_account_id, strategy_id) "
                    + "do update set enabled = true, size_multiplier = excluded.size_multiplier",
                    copyGroupId, brokerAccountId, strategyId, sizeMultiplier);
        }
    }
}
