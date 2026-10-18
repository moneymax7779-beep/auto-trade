package com.autotrade.zerodha;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.broker.BrokerCapabilities;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerException;
import com.autotrade.broker.BrokerException.Kind;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderStatus;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.broker.Quote;

import tools.jackson.databind.JsonNode;

/**
 * Zerodha Kite Connect v3 as a {@link BrokerConnection} (live plan sections 3 and 12).
 * <ul>
 *   <li><b>Orders</b>: regular variety, LIMIT or SL (stop-limit) only, product from the config (MIS), validity DAY, the
 *       OMS's tag (at most 20 alphanumerics) on every order; prices rounded to the tick, a buy up and a sell down (so a
 *       marketable limit stays marketable). The broker's autoslice is not used: the caller slices.</li>
 *   <li><b>Idempotent place</b>: a tag already placed by this gateway is ignored. A place whose outcome is unknown
 *       (timeout, dropped connection, 5xx) is never retried blindly: the order is looked up by tag after
 *       {@code placeLookupWait} (three tries), adopted when found, and placed again only when it is not there. A 429 or
 *       a refused connection did not reach the broker and is retried after a short wait.</li>
 *   <li><b>Rate</b>: places, modifies and cancels go through a limiter below Kite's 10 a second; quote calls through
 *       one of a call a second (Kite's quote limit).</li>
 *   <li><b>State</b>: {@link #poll()} pulls the order book (the truth) and emits an {@link OrderUpdate} for each change
 *       of status or filled quantity; a repeated or reordered report changes nothing. A WebSocket or postback can call
 *       {@code poll()} sooner; it is never the only source.</li>
 * </ul>
 */
public final class KiteGateway implements BrokerConnection {

    private static final Logger log = LoggerFactory.getLogger(KiteGateway.class);
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9]{1,20}");
    private static final int LOOKUPS = 3;

    private final KiteConfig config;
    private final KiteHttp http;
    private final KiteInstruments instruments;
    private final RateLimiter limiter;
    private final RateLimiter quotes = new RateLimiter(1);          // Kite's quote API: one call a second
    private final List<Consumer<OrderUpdate>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> brokerIdByTag = new ConcurrentHashMap<>();
    private final Map<String, BrokerOrder> lastSeen = new ConcurrentHashMap<>();

    public KiteGateway(KiteConfig config, KiteHttp http, KiteInstruments instruments) {
        this.config = config;
        this.http = http;
        this.instruments = instruments;
        this.limiter = new RateLimiter(config.maxOrdersPerSecond());
    }

    @Override
    public String name() {
        return "ZERODHA";
    }

    @Override
    public BrokerCapabilities capabilities() {
        return new BrokerCapabilities(true, true, config.maxOrdersPerSecond(), 20, false,
                BrokerCapabilities.UpdateChannel.POLLING, true, BrokerCapabilities.LoginFlow.DAILY_REQUEST_TOKEN, 2);
    }

    @Override
    public void onUpdate(Consumer<OrderUpdate> listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ orders

    @Override
    public void place(OrderRequest r) {
        String tag = r.clientOrderId();
        if (!TAG.matcher(tag).matches()) {
            throw new BrokerException(Kind.TERMINAL, "tag must be 1-20 alphanumerics: " + tag);
        }
        if (brokerIdByTag.containsKey(tag)) {
            return;                                                     // idempotent: this tag is already at the broker
        }
        KiteInstruments.Contract c = contract(r.instrumentKey());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("tradingsymbol", c.tradingSymbol());
        form.put("exchange", c.exchange());
        form.put("transaction_type", r.side().name());
        form.put("order_type", r.type() == OrderType.STOP_LIMIT ? "SL" : "LIMIT");
        form.put("quantity", Long.toString(r.quantity()));
        form.put("product", config.product());
        form.put("price", price(r.limitPrice(), c.tickSize(), r.side()));
        if (r.type() == OrderType.STOP_LIMIT) {
            form.put("trigger_price", price(r.triggerPrice(), c.tickSize(), null));
        }
        form.put("validity", "DAY");
        form.put("tag", tag);
        for (int attempt = 1; ; attempt++) {
            try {
                limiter.acquire();
                JsonNode data = http.post("/orders/regular", form);
                brokerIdByTag.put(tag, data.path("order_id").asString());
                return;
            } catch (BrokerException e) {
                if (e.kind() == Kind.RETRYABLE && !mayHaveReached(e) && attempt < 3) {
                    sleep(200L * attempt);                              // 429 or refused connection: not at the broker
                    continue;
                }
                if (e.kind() == Kind.UNKNOWN || (e.kind() == Kind.RETRYABLE && mayHaveReached(e))) {
                    Optional<BrokerOrder> found = lookUp(tag);
                    if (found.isPresent()) {
                        brokerIdByTag.put(tag, found.get().brokerOrderId());
                        log.warn("place {} had an unknown outcome; found at the broker as {}", tag,
                                found.get().brokerOrderId());
                        return;
                    }
                    if (attempt < 2) {
                        log.warn("place {} had an unknown outcome and is not at the broker; placing once more", tag);
                        continue;
                    }
                    throw new BrokerException(Kind.UNKNOWN, "place " + tag + ": outcome unknown after " + attempt
                            + " attempts", e);
                }
                throw e;
            }
        }
    }

    /** A 5xx or a timeout may have been processed; a 429 or a refused connection was not. */
    private static boolean mayHaveReached(BrokerException e) {
        return !(e instanceof KiteException k) || k.mayHaveReached();
    }

    private Optional<BrokerOrder> lookUp(String tag) {
        for (int i = 0; i < LOOKUPS; i++) {
            sleep(config.placeLookupWait().toMillis());
            try {
                Optional<BrokerOrder> found = orderByTag(tag);
                if (found.isPresent()) {
                    return found;
                }
            } catch (BrokerException e) {
                if (e.kind() == Kind.SESSION) {
                    throw e;
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
        BrokerOrder order = known(clientOrderId);
        KiteInstruments.Contract c = contract(order.instrumentKey());
        Map<String, String> form = new LinkedHashMap<>();
        if (quantity > 0) {
            form.put("quantity", Long.toString(quantity));
        }
        if (limitPrice > 0) {
            form.put("price", price(limitPrice, c.tickSize(), order.side()));
        }
        if (triggerPrice > 0) {
            form.put("trigger_price", price(triggerPrice, c.tickSize(), null));
        }
        form.put("order_type", order.type() == OrderType.STOP_LIMIT ? "SL" : "LIMIT");
        limiter.acquire();
        http.put("/orders/regular/" + order.brokerOrderId(), form);
    }

    @Override
    public void cancel(String clientOrderId) {
        BrokerOrder order = known(clientOrderId);
        limiter.acquire();
        http.delete("/orders/regular/" + order.brokerOrderId());
    }

    private BrokerOrder known(String tag) {
        return orderByTag(tag).orElseThrow(() -> new BrokerException(Kind.TERMINAL, "no order with tag " + tag
                + " at the broker"));
    }

    // ------------------------------------------------------------------ reads

    @Override
    public double availableFunds() {
        JsonNode equity = http.get("/user/margins/equity", null);
        JsonNode live = equity.path("available").path("live_balance");
        return live.isMissingNode() || live.isNull() ? equity.path("net").asDouble(Double.NaN) : live.asDouble();
    }

    @Override
    public Quote quote(String instrumentKey) {
        KiteInstruments.Contract c = contract(instrumentKey);
        String key = c.exchange() + ":" + c.tradingSymbol();
        quotes.acquire();
        JsonNode q = http.getRepeated("/quote", "i", List.of(key)).path(key);
        if (q.isMissingNode() || q.isNull()) {
            throw new BrokerException(Kind.RETRYABLE, "no quote for " + key);
        }
        JsonNode buy = q.path("depth").path("buy").path(0), sell = q.path("depth").path("sell").path(0);
        Instant time = KiteHttp.time(q.path("timestamp").asString(""));
        return new Quote(instrumentKey, buy.path("price").asDouble(0), buy.path("quantity").asLong(0),
                sell.path("price").asDouble(0), sell.path("quantity").asLong(0), q.path("last_price").asDouble(0),
                time == null ? Instant.now() : time);
    }

    @Override
    public List<BrokerOrder> orders() {
        List<BrokerOrder> orders = new ArrayList<>();
        for (JsonNode o : http.get("/orders", null)) {
            orders.add(order(o));
        }
        return orders;
    }

    @Override
    public List<BrokerTrade> trades() {
        List<BrokerTrade> trades = new ArrayList<>();
        for (JsonNode t : http.get("/trades", null)) {
            trades.add(new BrokerTrade(t.path("trade_id").asString(), t.path("order_id").asString(),
                    KiteInstruments.instrumentKey(t.path("exchange").asString(), exchangeToken(t)),
                    t.path("tradingsymbol").asString(), OrderSide.valueOf(t.path("transaction_type").asString()),
                    t.path("quantity").asLong(), t.path("average_price").asDouble(),
                    KiteHttp.time(t.path("fill_timestamp").asString(t.path("exchange_timestamp").asString("")))));
        }
        return trades;
    }

    @Override
    public List<BrokerPosition> positions() {
        List<BrokerPosition> positions = new ArrayList<>();
        for (JsonNode p : http.get("/portfolio/positions", null).path("net")) {
            if (!config.product().equals(p.path("product").asString(config.product()))) {
                continue;
            }
            positions.add(new BrokerPosition(p.path("instrument_token").asLong(), p.path("tradingsymbol").asString(),
                    p.path("quantity").asLong(), p.path("average_price").asDouble(),
                    KiteInstruments.instrumentKey(p.path("exchange").asString(), exchangeToken(p)),
                    p.path("day_buy_quantity").asLong(p.path("buy_quantity").asLong()),
                    p.path("day_sell_quantity").asLong(p.path("sell_quantity").asLong()),
                    p.path("day_buy_value").asDouble(p.path("buy_value").asDouble(Double.NaN)),
                    p.path("day_sell_value").asDouble(p.path("sell_value").asDouble(Double.NaN))));
        }
        return positions;
    }

    /** Kite reports its own instrument token; the exchange token is its low 8 bits shifted out (token >> 8). */
    private long exchangeToken(JsonNode n) {
        JsonNode exchangeToken = n.path("exchange_token");
        if (!exchangeToken.isMissingNode() && !exchangeToken.isNull() && !exchangeToken.asString("").isEmpty()) {
            return exchangeToken.asLong();
        }
        return instruments.bySymbol(n.path("exchange").asString(), n.path("tradingsymbol").asString())
                .map(KiteInstruments.Contract::exchangeToken).orElse(n.path("instrument_token").asLong() >> 8);
    }

    @Override
    public int poll() {
        int emitted = 0;
        for (BrokerOrder now : orders()) {
            BrokerOrder before = lastSeen.put(now.brokerOrderId(), now);
            if (before != null && before.status() == now.status() && before.filledQuantity() == now.filledQuantity()) {
                continue;
            }
            if (before != null && now.filledQuantity() < before.filledQuantity()) {
                lastSeen.put(now.brokerOrderId(), before);              // an older report arrived late: keep the newer
                continue;
            }
            long prevFilled = before == null ? 0 : before.filledQuantity();
            double prevValue = before == null ? 0 : before.averagePrice() * before.filledQuantity();
            long delta = now.filledQuantity() - prevFilled;
            double lastPrice = delta > 0 ? (now.averagePrice() * now.filledQuantity() - prevValue) / delta : 0;
            OrderUpdate update = new OrderUpdate(now.clientTag(), now.brokerOrderId(), now.status(), now.filledQuantity(),
                    now.averagePrice(), delta, lastPrice, now.updatedAt() == null ? Instant.now() : now.updatedAt(),
                    now.message());
            listeners.forEach(l -> l.accept(update));
            emitted++;
        }
        return emitted;
    }

    // ------------------------------------------------------------------ mapping

    private KiteInstruments.Contract contract(String instrumentKey) {
        return instruments.byInstrumentKey(instrumentKey).orElseThrow(() -> new BrokerException(Kind.TERMINAL,
                "instrument " + instrumentKey + " is not in Kite's instrument list"));
    }

    private BrokerOrder order(JsonNode o) {
        String raw = o.path("status").asString("");
        long filled = o.path("filled_quantity").asLong(0);
        String type = o.path("order_type").asString("LIMIT");
        String tag = o.path("tag").asString("");
        String exchange = o.path("exchange").asString("");
        Instant updated = KiteHttp.time(o.path("exchange_update_timestamp").asString(""));
        return new BrokerOrder(tag.isEmpty() || tag.equals("null") ? null : tag, o.path("order_id").asString(),
                KiteInstruments.instrumentKey(exchange, exchangeToken(o)), o.path("tradingsymbol").asString(),
                OrderSide.valueOf(o.path("transaction_type").asString("BUY")),
                type.equals("SL") ? OrderType.STOP_LIMIT : OrderType.LIMIT, o.path("quantity").asLong(0), filled,
                o.path("average_price").asDouble(0), o.path("price").asDouble(0), o.path("trigger_price").asDouble(0),
                status(raw, filled), raw, o.path("status_message").asString(null),
                updated != null ? updated : KiteHttp.time(o.path("order_timestamp").asString("")));
    }

    /** Kite's order statuses onto ours; the many transient ones ("VALIDATION PENDING", "MODIFY PENDING", ...) are PENDING. */
    static OrderStatus status(String raw, long filled) {
        return switch (raw) {
            case "COMPLETE" -> OrderStatus.FILLED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            case "REJECTED" -> OrderStatus.REJECTED;
            case "OPEN" -> filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.OPEN;
            case "TRIGGER PENDING" -> OrderStatus.TRIGGER_PENDING;
            default -> OrderStatus.PENDING;
        };
    }

    /** A price on the tick: a buy rounds up, a sell down, a trigger to the nearest. */
    static String price(double price, double tick, OrderSide side) {
        double t = tick > 0 ? tick : 0.05;
        double ticks = price / t;
        double rounded = side == OrderSide.BUY ? Math.ceil(ticks - 1e-9) : side == OrderSide.SELL
                ? Math.floor(ticks + 1e-9) : Math.rint(ticks);
        return new java.math.BigDecimal(rounded * t).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** For tests: what this gateway has placed. */
    Map<String, String> placed() {
        return new HashMap<>(brokerIdByTag);
    }
}
