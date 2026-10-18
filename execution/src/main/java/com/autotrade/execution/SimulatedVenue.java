package com.autotrade.execution;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.BrokerAccount;

/**
 * Fills every order in place, completely, at the given price (L1's model): PAPER followers, and DRY_RUN followers with
 * no broker connection (their broker funds are {@code simulatedBrokerFunds}). The order row records what would have
 * been sent; no stop rests anywhere.
 */
public final class SimulatedVenue implements FollowerVenue {

    private final JdbcTemplate jdbc;
    private final double simulatedBrokerFunds;

    public SimulatedVenue(DataSource dataSource, double simulatedBrokerFunds) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.simulatedBrokerFunds = simulatedBrokerFunds;
    }

    @Override
    public boolean broker() {
        return false;
    }

    @Override
    public Fill execute(OrderSpec o) {
        jdbc.update("insert into exec.order_execution (tenant_id, trade_intent_id, broker_account_id, broker_type, role, "
                        + "client_tag, attempt_no, order_side, order_type, quantity_requested, quantity_filled, limit_price, "
                        + "average_fill_price, status, raw_status, lease_epoch) values (?, ?, ?, ?, ?, ?, ?, ?, 'LIMIT', ?, ?, "
                        + "?, ?, 'FILLED', ?, ?)", o.account().tenantId(), o.intentId(), o.account().id(),
                o.account().brokerType().name(), o.role(), o.tag(), o.attempt(), o.side(), o.quantity(), o.quantity(),
                o.price(), o.price(), o.mode().equals("DRY_RUN") ? "DRY_RUN_SIMULATED" : "PAPER_SIMULATED", o.leaseEpoch());
        return Fill.of(java.util.List.of(new Part(o.quantity(), o.price())), o.at(), "FILLED", null);
    }

    @Override
    public double funds(BrokerAccount account) {
        return simulatedBrokerFunds;
    }
}
