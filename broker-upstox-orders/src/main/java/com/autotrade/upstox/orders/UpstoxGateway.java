package com.autotrade.upstox.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
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
import com.autotrade.broker.RateLimiter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Upstox's order API as a {@link BrokerConnection} (live adapters plan, step B2), modelled on the Kite and Kotak adapters.
 * <ul>
 *   <li><b>Orders</b> (v3, api-hft): LIMIT or SL (stop-limit, with a trigger) only, product from the config (I), validity
 *       DAY, our tag in {@code tag} (Upstox allows 40 characters; ours are at most 20 alphanumerics), the contract's
 *       {@code instrument_token} (= our key), quantity in units and a whole number of lots, prices on the contract's
 *       tick (a buy rounded up, a sell down), {@code is_amo} and {@code slice} false. MARKET and SL-M are never sent.
 *       In this build order calls to Upstox's own host are refused outright: orders only ever reach a simulator.</li>
 *   <li><b>Idempotent place</b>: a tag already placed by this gateway is ignored. Upstox does not refuse a repeated tag
 *       (it groups orders; UNCONFIRMED either way), so a place whose outcome is unknown (timeout, dropped connection,
 *       5xx, an answer without Upstox's envelope) is never sent again: the order is looked up by tag after
 *       {@code placeLookupWait} (three tries; the order book, then order history by tag), adopted when found, else
 *       reported {@link Kind#UNKNOWN}. A 429 or a refused connection never reached Upstox and is retried after a short
 *       wait.</li>
 *   <li><b>Books</b>: {@link #poll()} pulls the order book (the truth) and emits an {@link OrderUpdate} for each change of
 *       status or filled quantity; a repeated or older report changes nothing. Positions are those of the configured
 *       product only.</li>
 *   <li><b>Quotes</b>: Upstox's quote API is not called in this build. {@link #quote} answers from the supplied quote
 *       source (the simulator's book in STORED DRY_RUN and tests; our own live capture when reading the real broker),
 *       and without one refuses with a {@link BrokerException}, which the venue treats as no quote: no order is sent.</li>
 * </ul>
 */
public final class UpstoxGateway implements BrokerConnection {

    private static final Logger log = LoggerFactory.getLogger(UpstoxGateway.class);
    /** Our tags: the OMS's 1-20 alphanumerics (Upstox's limit on {@code tag} is 40). */
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9]{1,20}");
    static final int UPSTOX_TAG_MAX = 40;
    private static final int LOOKUPS = 3;
    /** Statuses after which an order never changes (docs' status list). */
    static final Set<String> TERMINAL_STATUSES = Set.of("complete", "rejected", "cancelled",
            "cancelled after market order");
    /**
     * Every status in Upstox's docs (lower case). "not cancelled" and "not modified" are a cancel or modify that failed:
     * the order is still live. Partial fills have no status of their own: filled_quantity &gt; 0 on a live status.
     */
    static final Set<String> KNOWN_STATUSES = Set.of("validation pending", "modify pending", "trigger pending",
            "put order req received", "modify after market order req received", "cancelled after market order", "open",
            "complete", "modify validation pending", "after market order req received", "modified", "not cancelled",
            "cancel pending", "rejected", "cancelled", "open pending", "not modified");
    private static final Set<String> loggedUnknownStatuses = ConcurrentHashMap.newKeySet();

    private final UpstoxConfig config;
    private final UpstoxHttp http;
    private final UpstoxInstruments instruments;
    private final Function<String, Quote> quotes;
    private final RateLimiter limiter;
    private final List<Consumer<OrderUpdate>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> brokerIdByTag = new ConcurrentHashMap<>();
    private final Map<String, BrokerOrder> lastSeen = new ConcurrentHashMap<>();

    /**
     * @param quotes the executable book by our instrument key ({@code null}: no quote source; {@link #quote} refuses)
     */
    public UpstoxGateway(UpstoxConfig config, UpstoxHttp http, UpstoxInstruments instruments,
                         Function<String, Quote> quotes) {
        this.config = config;
        this.http = http;
        this.instruments = instruments;
        this.quotes = quotes;
        this.limiter = new RateLimiter(config.maxOrdersPerSecond());
    }

    @Override
    public String name() {
        return "UPSTOX";
    }

    @Override
    public BrokerCapabilities capabilities() {
        // Upstox: a primary and a secondary static IP per user (a change revokes every token)
        return new BrokerCapabilities(true, true, config.maxOrdersPerSecond(), 20, false,
                BrokerCapabilities.UpdateChannel.POLLING, true, BrokerCapabilities.LoginFlow.DAILY_OAUTH, 2);
    }

    @Override
    public void onUpdate(Consumer<OrderUpdate> listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ orders

    /**
     * This build sends no order to Upstox itself (DRY_RUN only; LIVE needs READ_LIVE, the static IP and the canary): an
     * order call is allowed only on a config made by {@link UpstoxConfig#simulator} (review 8 Nov 2026: a marker, not the
     * host, so a read config pointed anywhere can never send one).
     */
    private void refuseRealOrders(String call) {
        if (!config.ordersAllowed()) {
            throw new BrokerException(Kind.TERMINAL, call + " refused: this build has no LIVE Upstox connection (orders "
                    + "go only to a simulator)");
        }
    }

    @Override
    public void place(OrderRequest r) {
        refuseRealOrders("place");
        String tag = r.clientOrderId();
        if (!TAG.matcher(tag).matches()) {
            throw new BrokerException(Kind.TERMINAL, "tag must be 1-20 alphanumerics: " + tag);
        }
        if (brokerIdByTag.containsKey(tag)) {
            return;                                                     // idempotent: this tag is already at the broker
        }
        UpstoxInstruments.Contract c = contract(r.instrumentKey());
        if (r.quantity() % c.lotSize() != 0) {
            throw new BrokerException(Kind.TERMINAL, "place " + tag + ": quantity " + r.quantity()
                    + " is not a whole number of lots of " + c.lotSize());
        }
        if (r.quantity() > Integer.MAX_VALUE) {
            throw new BrokerException(Kind.TERMINAL, "place " + tag + ": quantity too large");
        }
        boolean stop = r.type() == OrderType.STOP_LIMIT;
        ObjectNode body = http.object();
        body.put("quantity", (int) r.quantity());
        body.put("product", config.product());
        body.put("validity", "DAY");
        body.put("price", price(r.limitPrice(), c.tickSize(), r.side()));
        body.put("tag", tag);
        body.put("instrument_token", c.instrumentKey());
        body.put("order_type", stop ? "SL" : "LIMIT");
        body.put("transaction_type", r.side() == OrderSide.BUY ? "BUY" : "SELL");
        body.put("disclosed_quantity", 0);
        body.put("trigger_price", stop ? price(r.triggerPrice(), c.tickSize(), null) : BigDecimal.ZERO);
        body.put("is_amo", false);
        body.put("slice", false);
        for (int attempt = 1; ; attempt++) {
            try {
                limiter.acquire();
                JsonNode answer = http.send("POST", UpstoxHttp.PLACE, body);
                JsonNode ids = answer.path("data").path("order_ids");
                String id = ids.isArray() && !ids.isEmpty() ? ids.path(0).asString("") : "";
                if (id.isEmpty()) {
                    throw new UpstoxException(Kind.UNKNOWN, "place " + tag + ": Upstox answered success without an order "
                            + "id", 200, "", true, null);
                }
                if (ids.size() > 1) {
                    log.warn("place {}: Upstox returned {} order ids (slice is off); the first is tracked", tag,
                            ids.size());
                }
                brokerIdByTag.put(tag, id);
                return;
            } catch (BrokerException e) {
                if (e.kind() == Kind.RETRYABLE && !mayHaveReached(e) && attempt < 3) {
                    sleep(200L * attempt);                              // 429 or refused connection: not at the broker
                    continue;
                }
                if (e.kind() == Kind.UNKNOWN || (e.kind() == Kind.RETRYABLE && mayHaveReached(e))) {
                    Optional<BrokerOrder> found;
                    try {
                        found = lookUp(tag);
                    } catch (BrokerException lookup) {
                        // the session ended while the order was being looked up (review 8 Nov 2026, as the Kite hotfix):
                        // the place may still have reached Upstox, so it is UNKNOWN (the caller reconciles), never SESSION
                        // (which reads as "nothing was sent")
                        BrokerException unknown = new BrokerException(Kind.UNKNOWN, "place " + tag + ": outcome unknown; "
                                + "the order could not be looked up (" + lookup.getMessage() + ")", e);
                        unknown.addSuppressed(lookup);
                        throw unknown;
                    }
                    if (found.isPresent()) {
                        brokerIdByTag.put(tag, found.get().brokerOrderId());
                        log.warn("place {} had an unknown outcome; found at Upstox as {}", tag, found.get().brokerOrderId());
                        return;
                    }
                    // never placed again here (as the Kite adapter, code review 3 Nov 2026): the caller records the order
                    // as unknown; a later attempt with the same tag reads the book first
                    throw new BrokerException(Kind.UNKNOWN, "place " + tag + ": outcome unknown, not found at Upstox by "
                            + "tag", e);
                }
                throw e;
            }
        }
    }

    private static boolean mayHaveReached(BrokerException e) {
        return !(e instanceof UpstoxException u) || u.mayHaveReached();
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

    /**
     * Changes a working order: v3 modify carries the order id, validity, price, order type and trigger, and the quantity
     * only when the caller changes it (quantity 0 keeps the order's; Upstox's {@code quantity} is taken to be the order's
     * total, not what remains: UNCONFIRMED).
     */
    @Override
    public void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
        refuseRealOrders("modify");
        BrokerOrder order = known(clientOrderId);
        if (order.filledQuantity() > 0 && quantity > 0 && quantity != order.quantity()) {
            // whether Upstox's modify quantity is the order's total or what remains is not proven (canary first): a
            // partly filled order is never re-sized by modify (review 8 Nov 2026); the venue cancels and re-places it
            throw new BrokerException(Kind.TERMINAL, "modify " + clientOrderId + " refused: resize a partly filled order "
                    + "by cancel and re-place (" + order.filledQuantity() + " of " + order.quantity() + " filled, asked "
                    + quantity + ")");
        }
        UpstoxInstruments.Contract c = contract(order.instrumentKey());
        boolean stop = order.type() == OrderType.STOP_LIMIT;
        ObjectNode body = http.object();
        body.put("order_id", order.brokerOrderId());
        body.put("validity", "DAY");
        body.put("price", price(limitPrice > 0 ? limitPrice : order.limitPrice(), c.tickSize(), order.side()));
        body.put("order_type", stop ? "SL" : "LIMIT");
        body.put("trigger_price", stop ? price(triggerPrice > 0 ? triggerPrice : order.triggerPrice(), c.tickSize(), null)
                : BigDecimal.ZERO);
        if (quantity > 0) {
            body.put("quantity", (int) quantity);
        }
        body.put("disclosed_quantity", 0);
        limiter.acquire();
        http.send("PUT", UpstoxHttp.MODIFY, body);
    }

    @Override
    public void cancel(String clientOrderId) {
        refuseRealOrders("cancel");
        String id = brokerIdByTag.get(clientOrderId);              // the place's id when the book does not show it yet
        if (id == null || id.isEmpty()) {
            id = known(clientOrderId).brokerOrderId();
        }
        limiter.acquire();
        http.delete(UpstoxHttp.CANCEL + "?" + UpstoxHttp.query("order_id", id));
    }

    private BrokerOrder known(String tag) {
        return orderByTag(tag).orElseThrow(() -> new BrokerException(Kind.TERMINAL, "no order with tag " + tag
                + " at Upstox"));
    }

    // ------------------------------------------------------------------ reads

    /**
     * Funds available to trade (v3: {@code data.available_to_trade.total}, as the SDK's model has it; UNCONFIRMED on a
     * real account); a v2-shaped answer ({@code data.equity.available_margin}) is read too. Upstox's funds service is down
     * 00:00-05:30 IST (UDAPI100072, HTTP 423: retryable). An answer of neither shape is refused (the entry is refused as
     * funds unknown), never read as zero or as unlimited.
     */
    @Override
    public double availableFunds() {
        double funds;
        try {
            funds = availableFrom(http.get(UpstoxHttp.FUNDS).path("data"));
        } catch (UpstoxException e) {
            if (e.httpStatus() != 404) {
                throw e;
            }
            funds = Double.NaN;                     // no v3 funds here: v2 below (review 8 Nov 2026)
        }
        if (Double.isNaN(funds)) {
            // v3 missing or of an unknown shape: v2, the call the login reads (one parse for both: availableFrom)
            funds = availableFrom(http.get(UpstoxHttp.FUNDS_V2).path("data"));
        }
        if (Double.isNaN(funds)) {
            throw new BrokerException(Kind.TERMINAL, "Upstox funds answer has neither available_to_trade.total nor "
                    + "equity.available_margin (v3 and v2)");
        }
        return funds;
    }

    /**
     * The funds available to trade in a funds answer's {@code data}: v3 {@code available_to_trade.total}, else v2
     * {@code equity.available_margin}; NaN when it has neither (never zero, never unlimited). The one reading of Upstox's
     * funds, used by this gateway and by the daily login.
     */
    public static double availableFrom(JsonNode data) {
        if (data == null) {
            return Double.NaN;
        }
        double v3 = UpstoxHttp.number(data.path("available_to_trade").path("total"), Double.NaN);
        return !Double.isNaN(v3) ? v3 : UpstoxHttp.number(data.path("equity").path("available_margin"), Double.NaN);
    }

    @Override
    public Quote quote(String instrumentKey) {
        if (quotes == null) {
            throw new BrokerException(Kind.TERMINAL, "Upstox quotes are not implemented in this build and this connection "
                    + "has no quote source: no order in " + instrumentKey);
        }
        Quote q = quotes.apply(instrumentKey);
        if (q == null) {
            throw new BrokerException(Kind.RETRYABLE, "no quote for " + instrumentKey);
        }
        return q;
    }

    @Override
    public List<BrokerOrder> orders() {
        List<BrokerOrder> orders = new ArrayList<>();
        for (JsonNode o : rows(UpstoxHttp.ORDERS)) {
            orders.add(order(o));
        }
        return orders;
    }

    /**
     * Our order with this tag: the day's order book first; if it is not there (yet), Upstox's order history by tag (its
     * newest entry). An order history that has no such tag (UDAPI100010) is no order.
     */
    @Override
    public Optional<BrokerOrder> orderByTag(String clientTag) {
        Optional<BrokerOrder> inBook = orders().stream().filter(o -> clientTag.equals(o.clientTag())).findFirst();
        if (inBook.isPresent()) {
            return inBook;
        }
        try {
            JsonNode data = http.get(UpstoxHttp.HISTORY + "?" + UpstoxHttp.query("tag", clientTag)).path("data");
            BrokerOrder newest = null;
            for (JsonNode h : data.isArray() ? data : List.<JsonNode>of()) {
                BrokerOrder o = order(h);
                if (clientTag.equals(o.clientTag()) && (newest == null || later(o, newest))) {
                    newest = o;
                }
            }
            return Optional.ofNullable(newest);
        } catch (UpstoxException e) {
            if (UpstoxHttp.NOT_FOUND.equals(e.errorCode())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static boolean later(BrokerOrder a, BrokerOrder b) {
        if (a.filledQuantity() != b.filledQuantity()) {
            return a.filledQuantity() > b.filledQuantity();
        }
        if (a.status().isTerminal() != b.status().isTerminal()) {
            return a.status().isTerminal();
        }
        return a.updatedAt() != null && (b.updatedAt() == null || !a.updatedAt().isBefore(b.updatedAt()));
    }

    /** Today's fills; the trade book carries the instrument_token (= our key). */
    @Override
    public List<BrokerTrade> trades() {
        List<BrokerTrade> trades = new ArrayList<>();
        for (JsonNode t : rows(UpstoxHttp.TRADES)) {
            Instant time = UpstoxHttp.time(t.path("exchange_timestamp").asString(""));
            trades.add(new BrokerTrade(t.path("trade_id").asString(""), t.path("order_id").asString(""),
                    key(t), symbol(t), side(t.path("transaction_type").asString("")), UpstoxHttp.whole(t.path("quantity")),
                    UpstoxHttp.number(t.path("average_price"), 0),
                    time != null ? time : UpstoxHttp.time(t.path("order_timestamp").asString(""))));
        }
        return trades;
    }

    /**
     * Today's positions of the configured product (short-term positions, {@code product} I): {@code quantity} is the net,
     * with the day's buy and sell quantities and values.
     */
    @Override
    public List<BrokerPosition> positions() {
        List<BrokerPosition> positions = new ArrayList<>();
        for (JsonNode p : rows(UpstoxHttp.POSITIONS)) {
            if (!config.product().equalsIgnoreCase(p.path("product").asString(config.product()))) {
                continue;
            }
            String key = key(p);
            long buy = UpstoxHttp.whole(p.path("day_buy_quantity")), sell = UpstoxHttp.whole(p.path("day_sell_quantity"));
            double buyValue = UpstoxHttp.number(p.path("day_buy_value"), Double.NaN);
            double sellValue = UpstoxHttp.number(p.path("day_sell_value"), Double.NaN);
            long net = UpstoxHttp.whole(p.path("quantity"));
            positions.add(new BrokerPosition(token(key), symbol(p), net, average(p, net, buy, sell, buyValue, sellValue),
                    key, buy, sell, buyValue, sellValue));
        }
        return positions;
    }

    /**
     * A position's average entry: a long is entered by its buys (the day's buy value / quantity), a short by its sells
     * (review 8 Nov 2026: the buy side was used for a net short too); Upstox's own {@code average_price} when the day's
     * side is missing.
     */
    static double average(JsonNode p, long net, long buy, long sell, double buyValue, double sellValue) {
        if (net < 0) {
            return sell > 0 && !Double.isNaN(sellValue) ? sellValue / sell
                    : UpstoxHttp.number(p.path("sell_price"), UpstoxHttp.number(p.path("average_price"), 0));
        }
        return buy > 0 && !Double.isNaN(buyValue) ? buyValue / buy : UpstoxHttp.number(p.path("average_price"), 0);
    }

    /** A book's rows ({@code data}); an absent or null {@code data} is no rows. */
    private Iterable<JsonNode> rows(String path) {
        JsonNode data = http.get(path).path("data");
        return data.isArray() ? data : List.of();
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
            if (before != null && before.status().isTerminal() && !now.status().isTerminal()) {
                lastSeen.put(now.brokerOrderId(), before);              // a finished order never reopens
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

    private UpstoxInstruments.Contract contract(String instrumentKey) {
        return instruments.byInstrumentKey(instrumentKey).orElseThrow(() -> new BrokerException(Kind.TERMINAL,
                "instrument " + instrumentKey + " is not in Upstox's checked instrument list"));
    }

    /** Our key of a book row: its {@code instrument_token} (Upstox's key is ours); null when absent. */
    private static String key(JsonNode row) {
        String k = row.path("instrument_token").asString("").trim();
        return k.isEmpty() ? null : k;
    }

    private static String symbol(JsonNode row) {
        String s = row.path("trading_symbol").asString("");
        return s.isEmpty() ? row.path("tradingsymbol").asString("") : s;
    }

    private static long token(String key) {
        if (key == null || key.indexOf('|') < 0) {
            return 0;
        }
        try {
            return Long.parseLong(key.substring(key.indexOf('|') + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private BrokerOrder order(JsonNode o) {
        String raw = o.path("status").asString("").trim();
        long filled = UpstoxHttp.whole(o.path("filled_quantity"));
        String tag = o.path("tag").asString("").trim();
        String type = o.path("order_type").asString("LIMIT").trim().toUpperCase(Locale.ROOT);
        Instant updated = UpstoxHttp.time(o.path("exchange_timestamp").asString(""));
        String reason = o.path("status_message").asString("").trim();
        return new BrokerOrder(tag.isEmpty() || tag.equalsIgnoreCase("null") ? null : tag, o.path("order_id").asString(""),
                key(o), symbol(o), side(o.path("transaction_type").asString("")),
                type.equals("SL") || type.equals("SL-L") ? OrderType.STOP_LIMIT : OrderType.LIMIT,
                UpstoxHttp.whole(o.path("quantity")), filled, UpstoxHttp.number(o.path("average_price"), 0),
                UpstoxHttp.number(o.path("price"), 0), UpstoxHttp.number(o.path("trigger_price"), 0), status(raw, filled),
                raw, reason.isEmpty() || reason.equalsIgnoreCase("null") ? null : reason,
                updated != null ? updated : UpstoxHttp.time(o.path("order_timestamp").asString("")));
    }

    /** BUY or SELL; a missing or unrecognised side is null (never read as a buy), for the READ_LIVE probe to flag. */
    static OrderSide side(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "BUY", "B" -> OrderSide.BUY;
            case "SELL", "S" -> OrderSide.SELL;
            default -> null;
        };
    }

    /**
     * Upstox's order statuses (lower case) onto ours: complete, rejected, cancelled and cancelled after market order are
     * terminal; a trigger-pending stop waits; open, modified and the failed requests "not cancelled" / "not modified" are
     * working; the "... pending" and "... received" states are pending. A partial fill is a filled quantity on any of the
     * live ones. An unknown status is never taken as terminal: it is pending (or partial) and logged once.
     */
    static OrderStatus status(String raw, long filled) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!KNOWN_STATUSES.contains(s) && loggedUnknownStatuses.add(s)) {
            log.warn("Upstox order status '{}' is not known: taken as working", s);
        }
        return switch (s) {
            case "complete" -> OrderStatus.FILLED;
            case "rejected" -> OrderStatus.REJECTED;
            case "cancelled", "cancelled after market order" -> OrderStatus.CANCELLED;
            case "trigger pending" -> filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.TRIGGER_PENDING;
            case "open", "modified", "not cancelled", "not modified" -> filled > 0 ? OrderStatus.PARTIALLY_FILLED
                    : OrderStatus.OPEN;
            default -> filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.PENDING;
        };
    }

    /** True when {@code raw} is one of Upstox's documented order statuses (any case and spacing around it). */
    public static boolean knownStatus(String raw) {
        return raw != null && KNOWN_STATUSES.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    static boolean terminal(String raw) {
        return raw != null && TERMINAL_STATUSES.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * A price on the tick as a JSON number with two decimals: a buy rounds up, a sell down, a trigger to the nearest.
     */
    static BigDecimal price(double price, double tick, OrderSide side) {
        double t = tick > 0 ? tick : 0.05;
        double ticks = price / t;
        double rounded = side == OrderSide.BUY ? Math.ceil(ticks - 1e-9) : side == OrderSide.SELL
                ? Math.floor(ticks + 1e-9) : Math.rint(ticks);
        return new BigDecimal(rounded * t).setScale(2, RoundingMode.HALF_UP);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
