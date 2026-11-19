package com.autotrade.exec.app;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Mirror accounts (V49, 19 Nov 2026, user on DEMO-1: "it should be as same as PAPER-1", answer "Mirror PAPER-1"): a PAPER
 * account with {@code acct.broker_account.mirror_of} set takes no trades of its own and every view of its trades (book,
 * Today, account details, positions by day, P&amp;L by day, ledger, My accounts) reads the mirrored engine account's
 * PAPER_LIVE trades instead. The guards (no open position before a switch, and so on) keep reading the account's own rows.
 * <p>
 * The engine writes a trade.position row only when the position closes, so the database has the mirrored account's
 * <b>closed</b> trades only; its open positions are the running session's, which members already read from
 * {@code /api/status} (openPositions), and the UI adds them (review 19 Nov 2026).
 */
final class Mirrors {

    private Mirrors() {
    }

    /** The engine session account a PAPER account mirrors, or null. */
    static String of(JdbcTemplate jdbc, long accountId) {
        return jdbc.queryForList("select mirror_of from acct.broker_account where id = ?", String.class, accountId)
                .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * A mirror account's trades in exec.position's columns (id, strategy_id, underlying, option_side, symbol,
     * quantity_open = 0, average_entry, opened_at, closed_at, exit_reason, realised_gross, charges, status = CLOSED) plus
     * quantity, lot_size and average_exit, as a subquery with one parameter: the mirror account's id. Only the mirrored
     * account's PAPER_LIVE sessions from the mirror account's own start ({@code equity_from}) count, so its ledger, its
     * "since start" and its equity agree with each other; an entry that never filled (ENTRY_NOT_FILLED, quantity 0) is
     * not a trade, as PAPER-1's own pages leave it out (review 19 Nov 2026).
     */
    static final String SOURCE = "(select p.id, p.strategy_id, p.underlying, p.option_side, p.symbol, 0::bigint quantity_open, "
            + "p.quantity, p.lot_size, p.average_cost average_entry, p.average_exit, p.opened_at, p.closed_at, p.exit_reason, "
            + "p.net + p.costs realised_gross, p.costs charges, 'CLOSED'::text status "
            + "from trade.position p join trade.session s on s.id = p.session_id join acct.broker_account ba "
            + "on ba.mirror_of = s.account and s.session_date >= ba.equity_from "
            + "where ba.id = ? and s.mode = 'PAPER_LIVE' and p.closed_at is not null and p.quantity > 0)";
}
