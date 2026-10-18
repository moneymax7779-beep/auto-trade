package com.autotrade.account;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * acct.* reads and writes. Every user-facing read is scoped by the app user's tenant memberships: a user only sees
 * accounts of tenants they belong to. Secrets are never read or written here (only secret-store references exist).
 */
public final class AccountStore {

    private static final String ACCOUNT_COLUMNS = "a.id, a.tenant_id, a.app_user_id, a.broker_type, a.display_name, "
            + "a.legacy_account_label, a.account_role, a.trade_mode, a.live_trading_enabled, a.allocated_capital, "
            + "a.equity_mode, a.equity_from, a.risk_file, a.size_step_pct, a.enabled";

    private final JdbcTemplate jdbc;

    public AccountStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** The tenant's id, created on first use. */
    public long ensureTenant(String name) {
        jdbc.update("insert into acct.tenant (name) values (?) on conflict (name) do nothing", name);
        return jdbc.queryForObject("select id from acct.tenant where name = ?", Long.class, name);
    }

    /** The app user's id (by Keycloak subject), created on first use. */
    public long ensureUser(String externalSubject, String displayName) {
        jdbc.update("insert into acct.app_user (external_subject, display_name) values (?, ?) "
                + "on conflict (external_subject) do nothing", externalSubject, displayName);
        return jdbc.queryForObject("select id from acct.app_user where external_subject = ?", Long.class, externalSubject);
    }

    public void ensureMembership(long tenantId, long appUserId, String role) {
        jdbc.update("insert into acct.tenant_membership (tenant_id, app_user_id, role) values (?, ?, ?) "
                + "on conflict (tenant_id, app_user_id) do nothing", tenantId, appUserId, role);
    }

    /**
     * Registers an existing PAPER account label (PAPER-1) as a broker account, once: PAPER broker, PAPER mode, role
     * DISABLED (it receives no intents; its own session keeps trading exactly as before). Returns the account id.
     */
    public long registerLegacyPaperAccount(long tenantId, long appUserId, String displayName, String legacyLabel,
                                           double allocatedCapital, LocalDate equityFrom, String riskFile) {
        jdbc.update("insert into acct.broker_account (tenant_id, app_user_id, broker_type, display_name, "
                        + "legacy_account_label, account_role, connection_status, trade_mode, allocated_capital, "
                        + "equity_mode, equity_from, risk_file, enabled) values (?, ?, 'PAPER', ?, ?, 'DISABLED', 'ACTIVE', "
                        + "'PAPER', ?, 'COMPOUNDING', ?, ?, true) on conflict (legacy_account_label) do nothing",
                tenantId, appUserId, displayName, legacyLabel, allocatedCapital, Date.valueOf(equityFrom), riskFile);
        return jdbc.queryForObject("select id from acct.broker_account where legacy_account_label = ?", Long.class,
                legacyLabel);
    }

    /**
     * The virtual signal-source account of a copy group (PAPER broker, role SIGNAL_SOURCE), created once under its
     * label (SIGNAL-1). Its capital is the reference capital the strategy budgets are published against.
     */
    public long ensureSignalSourceAccount(long tenantId, long appUserId, String label, double referenceCapital,
                                          LocalDate equityFrom, String riskFile) {
        jdbc.update("insert into acct.broker_account (tenant_id, app_user_id, broker_type, display_name, "
                        + "legacy_account_label, account_role, connection_status, trade_mode, allocated_capital, "
                        + "equity_mode, equity_from, risk_file, enabled) values (?, ?, 'PAPER', ?, ?, 'SIGNAL_SOURCE', "
                        + "'ACTIVE', 'PAPER', ?, 'FIXED', ?, ?, true) on conflict (legacy_account_label) do nothing",
                tenantId, appUserId, label, label, referenceCapital, Date.valueOf(equityFrom), riskFile);
        return jdbc.queryForObject("select id from acct.broker_account where legacy_account_label = ?", Long.class, label);
    }

    /**
     * A follower account (role FOLLOWER_TRADE, enabled) created once under its label; PAPER broker accounts are PAPER
     * mode, real-broker accounts start in DRY_RUN or PAPER and are never created live (live is a separate, explicit step).
     */
    public long ensureFollowerAccount(long tenantId, long appUserId, String label, BrokerType broker, TradeMode mode,
                                      double allocatedCapital, EquityMode equityMode, LocalDate equityFrom,
                                      String riskFile, double sizeStepPct) {
        if (mode == TradeMode.LIVE) {
            throw new IllegalArgumentException("a follower is never created live");
        }
        jdbc.update("insert into acct.broker_account (tenant_id, app_user_id, broker_type, display_name, "
                        + "legacy_account_label, account_role, connection_status, trade_mode, allocated_capital, equity_mode, "
                        + "equity_from, risk_file, size_step_pct, enabled) values (?, ?, ?, ?, ?, 'FOLLOWER_TRADE', ?, ?, ?, ?, "
                        + "?, ?, ?, true) on conflict (legacy_account_label) do nothing",
                tenantId, appUserId, broker.name(), label, label, broker == BrokerType.PAPER ? "ACTIVE" : "PENDING_CONNECT",
                mode.name(), allocatedCapital, equityMode.name(), Date.valueOf(equityFrom), riskFile, sizeStepPct);
        return jdbc.queryForObject("select id from acct.broker_account where legacy_account_label = ?", Long.class, label);
    }

    /** Enabled follower accounts subscribed to a copy group (any strategy). */
    public List<BrokerAccount> followers(long copyGroupId) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from acct.broker_account a where a.account_role = 'FOLLOWER_TRADE' "
                + "and a.enabled and exists (select 1 from acct.strategy_subscription s where s.broker_account_id = a.id "
                + "and s.copy_group_id = ? and s.enabled) order by a.id", AccountStore::account, copyGroupId);
    }

    public Optional<Long> copyGroupId(long tenantId, String name) {
        return jdbc.queryForList("select id from acct.copy_group where tenant_id = ? and name = ?", Long.class, tenantId,
                name).stream().findFirst();
    }

    /** The copy group's id, created once (enabled) for the given signal-source account. */
    public long ensureCopyGroup(long tenantId, String name, long signalSourceAccountId) {
        jdbc.update("insert into acct.copy_group (tenant_id, name, signal_source_account_id, enabled) values (?, ?, ?, true) "
                + "on conflict (tenant_id, name) do nothing", tenantId, name, signalSourceAccountId);
        return jdbc.queryForObject("select id from acct.copy_group where tenant_id = ? and name = ?", Long.class,
                tenantId, name);
    }

    public Optional<BrokerAccount> byId(long id) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from acct.broker_account a where a.id = ?", AccountStore::account,
                id).stream().findFirst();
    }

    /** The broker connection status of each account of a tenant (acct.broker_account.connection_status). */
    public java.util.Map<Long, String> connectionStatuses(long tenantId) {
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        jdbc.query("select id, connection_status from acct.broker_account where tenant_id = ?",
                rs -> { out.put(rs.getLong(1), rs.getString(2)); }, tenantId);
        return out;
    }

    /** Sets the broker connection status (ACTIVE after the day's login, TOKEN_EXPIRED on a session error, ...). */
    public void setConnectionStatus(long accountId, String status) {
        jdbc.update("update acct.broker_account set connection_status = ?, updated_at = now() where id = ?", status,
                accountId);
    }

    /** A tenant's accounts (the API's per-tenant view). */
    public List<BrokerAccount> ofTenant(long tenantId) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from acct.broker_account a where a.tenant_id = ? order by a.id",
                AccountStore::account, tenantId);
    }

    public Optional<BrokerAccount> byLegacyLabel(String legacyLabel) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from acct.broker_account a where a.legacy_account_label = ?",
                AccountStore::account, legacyLabel).stream().findFirst();
    }

    /** The accounts a user may see: those of every tenant the user is a member of. */
    public List<BrokerAccount> visibleTo(String externalSubject) {
        return jdbc.query("select " + ACCOUNT_COLUMNS + " from acct.broker_account a "
                + "join acct.tenant_membership m on m.tenant_id = a.tenant_id "
                + "join acct.app_user u on u.id = m.app_user_id "
                + "where u.external_subject = ? and u.status = 'ACTIVE' order by a.id", AccountStore::account, externalSubject);
    }

    /** Sum of the account's capital movements effective on or before {@code until}. */
    public double capitalMovements(long brokerAccountId, LocalDate until) {
        Double sum = jdbc.queryForObject("select coalesce(sum(amount), 0) from acct.capital_movement "
                + "where broker_account_id = ? and effective_date <= ?", Double.class, brokerAccountId, Date.valueOf(until));
        return sum == null ? 0 : sum;
    }

    public void addCapitalMovement(long brokerAccountId, LocalDate effective, double amount, String reason,
                                   Long byAppUserId) {
        jdbc.update("insert into acct.capital_movement (broker_account_id, effective_date, amount, reason, by_app_user_id) "
                + "values (?, ?, ?, ?, ?)", brokerAccountId, Date.valueOf(effective), amount, reason, byAppUserId);
    }

    /** Writes (or rewrites) one account day; an existing acknowledgement is cleared when the figures change. */
    public void writeLedgerDay(LedgerDay day) {
        jdbc.update("insert into acct.ledger_day (broker_account_id, session_date, opening_equity, realised_gross, charges, "
                        + "net, closing_equity, trades, broker_net, broker_charges, difference) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (broker_account_id, session_date) do update set "
                        + "opening_equity = excluded.opening_equity, realised_gross = excluded.realised_gross, "
                        + "charges = excluded.charges, net = excluded.net, closing_equity = excluded.closing_equity, "
                        + "trades = excluded.trades, broker_net = excluded.broker_net, broker_charges = excluded.broker_charges, "
                        + "difference = excluded.difference, acknowledged_at = null, acknowledged_by = null",
                day.brokerAccountId(), Date.valueOf(day.sessionDate()), day.openingEquity(), day.realisedGross(),
                day.charges(), day.net(), day.closingEquity(), day.trades(), day.brokerNet(), day.brokerCharges(),
                day.difference());
    }

    /** True when any closed day of the account still needs a person's acknowledgement (blocks the next live start). */
    public boolean hasUnacknowledgedDifference(long brokerAccountId) {
        Integer open = jdbc.queryForObject("select count(*) from acct.ledger_day where broker_account_id = ? "
                + "and acknowledged_at is null and ((difference is not null and abs(difference) >= 1) "
                + "or (difference is null and trades > 0))", Integer.class, brokerAccountId);
        return open != null && open > 0;
    }

    private static BrokerAccount account(ResultSet rs, int row) throws SQLException {
        return new BrokerAccount(rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("app_user_id"),
                BrokerType.valueOf(rs.getString("broker_type")), rs.getString("display_name"),
                rs.getString("legacy_account_label"), AccountRole.valueOf(rs.getString("account_role")),
                TradeMode.valueOf(rs.getString("trade_mode")), rs.getBoolean("live_trading_enabled"),
                rs.getDouble("allocated_capital"), EquityMode.valueOf(rs.getString("equity_mode")),
                rs.getDate("equity_from").toLocalDate(), rs.getString("risk_file"), rs.getDouble("size_step_pct"),
                rs.getBoolean("enabled"));
    }
}
