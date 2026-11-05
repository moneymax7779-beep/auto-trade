package com.autotrade.oms;

import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderUpdate;

/** Everything the OMS does, for persistence, audit and alerts. */
public interface OmsListener {

    default void orderSent(ManagedPosition position, String role, OrderRequest request) {
    }

    default void orderUpdated(ManagedPosition position, String role, OrderUpdate update) {
    }

    /** A refused intent; {@code strategyId} is the strategy it was refused for. */
    default void rejected(String strategyId, String underlying, String intent, String reason) {
    }

    default void closed(ManagedPosition position) {
    }

    /**
     * A position's entry is complete: something was bought and no entry order or slice is still working or pending
     * (risk v12 slices, freeze-quantity splits). Once per position.
     */
    default void entryComplete(ManagedPosition position) {
    }

    default void alert(String message) {
    }
}
