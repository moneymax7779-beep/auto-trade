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
 * limits are shared. Only DRY_RUN exists in this build: a LIVE connection (a real session, the registered IP, the
 * user's go-ahead) is refused before any adapter is asked.
 */
public interface BrokerAdapter {

    /** A DRY_RUN connection to the adapter's local simulator, the hook run before each order, and what to close. */
    record DryRun(BrokerConnection connection, Consumer<FollowerVenue.OrderSpec> beforeOrder, String description,
                  AutoCloseable resource) {
    }

    BrokerType broker();

    /** Opens the DRY_RUN connection of an account (never a real broker). */
    DryRun dryRun(BrokerAccount account) throws IOException;
}
