package com.autotrade.execution;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.TradeMode;

import tools.jackson.databind.json.JsonMapper;

/**
 * The kill switches of live plan section 4 (exec.kill_switch, V24) and the audit log. An engaged switch refuses entries
 * and adds; exits are never blocked. GLOBAL_LIVE covers every LIVE account; ACCOUNT one account; STRATEGY a tenant's
 * strategy on all its accounts; INSTRUMENT one contract everywhere. Every engage and release is in exec.audit_log.
 */
public final class KillSwitches {

    private static final Logger log = LoggerFactory.getLogger(KillSwitches.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One engaged switch, for the status page and the API. */
    public record Switch(long id, Long tenantId, String scope, Long accountId, String strategyId, String instrumentKey,
                         String reason, String engagedBy, java.time.Instant engagedAt) {
    }

    private final JdbcTemplate jdbc;

    public KillSwitches(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Why an entry of {@code strategy} in {@code instrumentKey} is refused for the account, or null. */
    public String engaged(BrokerAccount account, String strategy, String instrumentKey) {
        List<String> reasons = jdbc.queryForList("select scope || ': ' || reason from exec.kill_switch where released_at is null "
                        + "and ((scope = 'GLOBAL_LIVE' and ?) or (scope = 'ACCOUNT' and broker_account_id = ?) "
                        + "or (scope = 'STRATEGY' and tenant_id = ? and strategy_id = ?) "
                        + "or (scope = 'INSTRUMENT' and instrument_key = ?)) order by engaged_at limit 1", String.class,
                account.tradeMode() == TradeMode.LIVE, account.id(), account.tenantId(), strategy, instrumentKey);
        return reasons.isEmpty() ? null : reasons.getFirst();
    }

    /** Engages the account's switch (once: an engaged one is kept, the new reason audited). */
    public void engageAccount(BrokerAccount account, String reason, String actor) {
        int engaged = jdbc.update("insert into exec.kill_switch (tenant_id, scope, broker_account_id, reason, engaged_by) "
                + "select ?, 'ACCOUNT', ?, ?, ? where not exists (select 1 from exec.kill_switch where scope = 'ACCOUNT' "
                + "and broker_account_id = ? and released_at is null)", account.tenantId(), account.id(), reason, actor,
                account.id());
        audit(account, actor, engaged > 0 ? "KILL_ACCOUNT" : "KILL_ACCOUNT_ALREADY_ENGAGED", Map.of("reason", reason));
        log.error("ACCOUNT KILL {} ({}): {}", account.id(), account.displayName(), reason);
    }

    /** Engages a switch of any scope; returns its id. */
    public long engage(Long tenantId, String scope, Long accountId, String strategyId, String instrumentKey, String reason,
                       String actor) {
        long id = jdbc.queryForObject("insert into exec.kill_switch (tenant_id, scope, broker_account_id, strategy_id, "
                        + "instrument_key, reason, engaged_by) values (?, ?, ?, ?, ?, ?, ?) returning id", Long.class, tenantId,
                scope, accountId, strategyId, instrumentKey, reason, actor);
        audit(tenantId, accountId, actor, "KILL_" + scope, Map.of("reason", reason, "switch", id));
        return id;
    }

    /** Releases a switch; false when it was not engaged (or belongs to another tenant). */
    public boolean release(long id, Long tenantId, String actor) {
        int released = jdbc.update("update exec.kill_switch set released_at = now(), released_by = ? where id = ? "
                + "and released_at is null and (?::bigint is null or tenant_id = ?)", actor, id, tenantId, tenantId);
        if (released > 0) {
            audit(tenantId, null, actor, "KILL_RELEASED", Map.of("switch", id));
        }
        return released > 0;
    }

    /** Engaged switches: a tenant's, or every one ({@code tenantId} null, ADMIN). */
    public List<Switch> list(Long tenantId) {
        return jdbc.query("select id, tenant_id, scope, broker_account_id, strategy_id, instrument_key, reason, engaged_by, "
                        + "engaged_at from exec.kill_switch where released_at is null and (?::bigint is null or tenant_id = ? "
                        + "or tenant_id is null) order by engaged_at",
                (rs, n) -> new Switch(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), (Long) rs.getObject(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getTimestamp(9).toInstant()), tenantId, tenantId);
    }

    public void audit(BrokerAccount account, String actor, String action, Map<String, ?> detail) {
        audit(account.tenantId(), account.id(), actor, action, detail);
    }

    public void audit(Long tenantId, Long accountId, String actor, String action, Map<String, ?> detail) {
        jdbc.update("insert into exec.audit_log (tenant_id, broker_account_id, actor, action, detail) values (?, ?, ?, ?, "
                + "?::jsonb)", tenantId, accountId, actor, action, JSON.writeValueAsString(detail));
    }
}
