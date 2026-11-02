package com.autotrade.execution;

import java.io.IOException;
import java.util.function.Consumer;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.broker.BrokerConnection;

/**
 * One broker's order adapter in the registry ({@link BrokerConnections}, live plan section 2a): how an account of that
 * broker gets its connection. A new broker is one more adapter: its gateway (a {@link BrokerConnection}), its
 * instrument mapping, and a local simulator for DRY_RUN; the venue, stops, recovery, reconciliation, kill switches and
 * limits are shared. A LIVE connection (a real session, the registered static IP, the user's own switch) exists only for
 * an adapter that offers one (this build: Zerodha) and is always behind a {@link LiveGuard}.
 */
public interface BrokerAdapter {

    /** A DRY_RUN connection to the adapter's local simulator, the hook run before each order, and what to close. */
    record DryRun(BrokerConnection connection, Consumer<FollowerVenue.OrderSpec> beforeOrder, String description,
                  AutoCloseable resource) {
    }

    BrokerType broker();

    /**
     * Opens the DRY_RUN connection of an account: orders always go to the adapter's simulator, never to a real broker;
     * {@code rules} say where its book comes from ({@link OrderRules#brokerMarket()}: the broker's own read API).
     */
    DryRun dryRun(BrokerAccount account, OrderRules rules) throws IOException;

    /** A LIVE connection to the real broker (already behind its {@link LiveGuard}) and what it is. */
    record Live(BrokerConnection connection, String description) {
    }

    /**
     * Opens the LIVE connection of an account (the registry has checked the account's LIVE switch and its risk file's
     * {@code live} limits); null when this adapter has no LIVE connection.
     */
    default Live live(BrokerAccount account, FollowerRisk.LiveLimits limits) {
        return null;
    }
}
