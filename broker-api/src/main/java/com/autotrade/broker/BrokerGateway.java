package com.autotrade.broker;

import java.util.List;
import java.util.function.Consumer;

/**
 * One broker account. Calls return quickly; outcomes arrive as {@link OrderUpdate}s on the
 * registered listener, in order, for every order state change and fill.
 */
public interface BrokerGateway {

    String name();

    BrokerCapabilities capabilities();

    void onUpdate(Consumer<OrderUpdate> listener);

    /** Places an order; a repeated {@code clientOrderId} is ignored (idempotent). */
    void place(OrderRequest request);

    /** Changes a working order's quantity, limit and trigger (quantity 0 keeps the current one). */
    void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice);

    void cancel(String clientOrderId);

    List<BrokerPosition> positions();
}
