package com.autotrade.trading;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.risk.RiskLimits;

/**
 * The account's equity for sizing (risk v8): the starting capital plus the realised net of every finished live
 * PAPER session on the account. Read once at session start; replays and shadow runs use the starting capital.
 */
final class EquityLedger {

    private final JdbcTemplate jdbc;

    EquityLedger(DataSource target) {
        this.jdbc = new JdbcTemplate(target);
    }

    /**
     * Realised net of the account's finished live sessions (DONE or STOPPED), rupees; 0 when there are none.
     * {@code ??} is the JDBC escape for the jsonb "has key" operator ({@code ?} alone would be a parameter).
     */
    double realisedNet(String account) {
        Double net = jdbc.queryForObject("select coalesce(sum((summary->>'net')::numeric), 0) from trade.session "
                + "where account = ? and mode = 'PAPER_LIVE' and status in ('DONE', 'STOPPED') and summary ?? 'net'",
                Double.class, account);
        return net == null ? 0 : net;
    }

    /** The limits to trade with: {@code limits} sized to the equity when in equity mode, else unchanged. */
    static RiskLimits apply(RiskLimits limits, double realisedNet) {
        if (!limits.equityMode()) {
            return limits;
        }
        return limits.withEquity(Math.max(0, limits.startingCapital() + realisedNet));
    }
}
