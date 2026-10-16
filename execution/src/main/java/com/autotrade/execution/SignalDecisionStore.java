package com.autotrade.execution;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/** exec.signal_decision: written once per idempotency key (a repeated key is ignored), read in decision order. */
public final class SignalDecisionStore {

    private final JdbcTemplate jdbc;

    public SignalDecisionStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Inserts the decision; returns false when its idempotency key was already stored. */
    public boolean insert(SignalDecision d) {
        return jdbc.update("insert into exec.signal_decision (copy_group_id, signal_session_id, session_date, decided_at, "
                        + "strategy_id, priority, underlying, action, option_side, leg_group, stage, reason, instrument_token, "
                        + "instrument_key, symbol, lot_size, reference_price, premium_budget, reference_capital, lots, "
                        + "idempotency_key) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "on conflict (idempotency_key) do nothing",
                d.copyGroupId(), d.signalSessionId(), Date.valueOf(d.sessionDate()), Timestamp.from(d.decidedAt()),
                d.strategyId(), d.priority(), d.underlying(), d.action().name(), d.optionSide(), d.legGroup(), d.stage(),
                d.reason(), d.instrumentToken(), d.instrumentKey(), d.symbol(), d.lotSize(), finite(d.referencePrice()),
                finite(d.premiumBudget()), finite(d.referenceCapital()), d.lots() > 0 ? d.lots() : null,
                d.idempotencyKey()) == 1;
    }

    /** One signal session's decisions in the order they were made. */
    public List<SignalDecision> forSession(long signalSessionId) {
        return jdbc.query("select * from exec.signal_decision where signal_session_id = ? order by decided_at, id",
                SignalDecisionStore::row, signalSessionId);
    }

    /** A copy group's decisions of one day, in order. */
    public List<SignalDecision> forDay(long copyGroupId, LocalDate day) {
        return jdbc.query("select * from exec.signal_decision where copy_group_id = ? and session_date = ? "
                + "order by decided_at, id", SignalDecisionStore::row, copyGroupId, Date.valueOf(day));
    }

    private static Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static double orNaN(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? Double.NaN : value;
    }

    private static SignalDecision row(ResultSet rs, int i) throws SQLException {
        return new SignalDecision(rs.getLong("copy_group_id"), rs.getLong("signal_session_id"),
                rs.getDate("session_date").toLocalDate(), rs.getTimestamp("decided_at").toInstant(),
                rs.getString("strategy_id"), rs.getInt("priority"), rs.getString("underlying"),
                SignalDecision.Action.valueOf(rs.getString("action")), rs.getString("option_side"),
                rs.getString("leg_group"), rs.getString("stage"), rs.getString("reason"), rs.getLong("instrument_token"),
                rs.getString("instrument_key"), rs.getString("symbol"), rs.getInt("lot_size"),
                orNaN(rs, "reference_price"), orNaN(rs, "premium_budget"), orNaN(rs, "reference_capital"),
                rs.getInt("lots"), rs.getString("idempotency_key"));
    }
}
