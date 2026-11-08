package com.autotrade.groww;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Groww's Trade API as a {@link BrokerConnection} (live adapters plan, step B3), modelled on the Upstox adapter.
 * <ul>
 *   <li><b>Orders</b> ({@code POST /v1/order/create}): LIMIT or SL (stop-limit, with a trigger) only, segment FNO,
 *       product from the config (MIS), validity DAY, the contract's exchange and {@code trading_symbol} from Groww's
 *       instrument CSV (never built), quantity in units and a whole number of lots, prices on the contract's tick (a buy
 *       rounded up, a sell down), our tag as {@code order_reference_id} ({@link #referenceId}: Groww wants 8-20
 *       characters, ours are 1-20 alphanumerics). MARKET and SL-M are never sent. In this build order calls to Groww's
 *       own host are refused outright: orders only ever reach a simulator.</li>
 *   <li><b>Idempotent place</b>: a tag already placed by this gateway is ignored. Groww refuses a repeated reference id
 *       (GA007; its scope, day or all time, UNCONFIRMED): such a refusal, and a place whose outcome is unknown (timeout,
 *       dropped connection, 5xx, GA000 / GA003, an answer without Groww's envelope), is never sent again: the order is
 *       looked up by reference after {@code placeLookupWait} (three tries; the order list, then status by reference),
 *       adopted when found, else reported {@link Kind#UNKNOWN}. A 429 or a refused connection never reached Groww and is
 *       retried after a short wait. A create that Groww answers SUCCESS with order_status FAILED or REJECTED is a
 *       rejection ({@link Kind#TERMINAL}, Groww's remark as the reason).</li>
 *   <li><b>Modify</b>: Groww allows {@link #MAX_MODIFICATIONS} modifications of an order; this gateway counts its own and
 *       refuses the next one ({@link Kind#TERMINAL}) without sending it.</li>
 *   <li><b>Books</b>: {@link #poll()} pulls the order list (paged; the truth) and emits an {@link OrderUpdate} for each
 *       change of status or filled quantity. Groww has no day-wide trade book: {@link #trades()} reads the trades of each
 *       of the day's orders with a filled quantity, and keeps those of finished orders. Positions are the configured
 *       product's only. The order list and positions name a contract by exchange and trading symbol, mapped back to our
 *       key through the checked instrument list.</li>
 *   <li><b>Quotes</b>: Groww's quote API is not called in this build. {@link #quote} answers from the supplied quote
 *       source (the simulator's book in STORED DRY_RUN and tests; our own live capture when reading the real broker),
 *       and without one refuses with a {@link BrokerException}, which the venue treats as no quote: no order is sent.</li>
 * </ul>
 */
public final class GrowwGateway implements BrokerConnection {

    private static final Logger log = LoggerFactory.getLogger(GrowwGateway.class);
    /** Our tags: the OMS's 1-20 alphanumerics. */
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9]{1,20}");
    static final int REFERENCE_MIN = 8, REFERENCE_MAX = 20;
    /** The padding of a short tag: tag + "-" + zeros ({@link #referenceId}). */
    private static final Pattern PADDING = Pattern.compile("-0+$");
    /** The OMS's own tags (BrokerReconciler.OUR_TAG): only such a reference id is read back as one of ours. */
    private static final Pattern OUR_TAG = Pattern.compile("F\\d+[A-Z].*");
    /** Groww: at most 25 modifications of one order (docs). */
    static final int MAX_MODIFICATIONS = 25;
    private static final int LOOKUPS = 3;
    private static final int MAX_PAGES = 50;
    /** Statuses after which an order never changes. */
    static final Set<String> TERMINAL_STATUSES = Set.of("EXECUTED", "COMPLETED", "REJECTED", "FAILED", "CANCELLED");
    /** Every status in Groww's docs, plus "OPEN" seen in its examples. */
    static final Set<String> KNOWN_STATUSES = Set.of("NEW", "ACKED", "TRIGGER_PENDING", "APPROVED", "REJECTED", "FAILED",
            "EXECUTED", "DELIVERY_AWAITED", "CANCELLED", "CANCELLATION_REQUESTED", "MODIFICATION_REQUESTED", "COMPLETED",
            "OPEN");
    private static final Set<String> loggedUnknownStatuses = ConcurrentHashMap.newKeySet();

    private final GrowwConfig config;
    private final GrowwHttp http;
    private final GrowwInstruments instruments;
    private final Function<String, Quote> quotes;
    private final GrowwHttp.Limiter limiter;
    private final List<Consumer<OrderUpdate>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> brokerIdByTag = new ConcurrentHashMap<>();
    private final Map<String, Integer> modificationsByTag = new ConcurrentHashMap<>();
    private final Map<String, BrokerOrder> lastSeen = new ConcurrentHashMap<>();
    private final Map<String, List<BrokerTrade>> tradesOfFinished = new ConcurrentHashMap<>();

    /**
     * @param quotes the executable book by our instrument key ({@code null}: no quote source; {@link #quote} refuses)
     */
    public GrowwGateway(GrowwConfig config, GrowwHttp http, GrowwInstruments instruments, Function<String, Quote> quotes) {
        this.config = config;
        this.http = http;
        this.instruments = instruments;
        this.quotes = quotes;
        this.limiter = new GrowwHttp.Limiter(config.maxOrdersPerSecond(), config.maxOrdersPerMinute(),
                Duration.ofMinutes(1));
    }

    @Override
    public String name() {
        return "GROWW";
    }

    @Override
    public BrokerCapabilities capabilities() {
        // Groww: orders only from the client's registered static IP, one per client (family members may share it)
        return new BrokerCapabilities(true, true, config.maxOrdersPerSecond(), 20, false,
                BrokerCapabilities.UpdateChannel.POLLING, true, BrokerCapabilities.LoginFlow.DAILY_REQUEST_TOKEN, 1);
    }

    @Override
    public void onUpdate(Consumer<OrderUpdate> listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ tags

    /**
     * Our tag as Groww's {@code order_reference_id}: a tag of 8 or more characters as it is; a shorter one padded to
     * Groww's minimum as tag + "-" + one or more zeros ({@code F12E1} → {@code F12E1-00}; {@code F12E1N1} →
     * {@code F12E1N1-0}). Our tags never contain a hyphen, so the padded and unpadded forms never meet and
     * {@link #tagOf} reverses it exactly.
     */
    public static String referenceId(String tag) {
        if (tag == null || !TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException("tag must be 1-20 alphanumerics: " + tag);
        }
        if (tag.length() >= REFERENCE_MIN) {
            return tag;
        }
        return tag + "-" + "0".repeat(Math.max(1, REFERENCE_MIN - 1 - tag.length()));
    }

    /** Groww's rule for an {@code order_reference_id} (docs): 8-20 characters, alphanumerics and at most two hyphens. */
    public static boolean validReference(String reference) {
        return reference != null && reference.length() >= REFERENCE_MIN && reference.length() <= REFERENCE_MAX
                && reference.chars().allMatch(ch -> ch == '-' || (ch < 128 && Character.isLetterOrDigit(ch)))
                && reference.chars().filter(ch -> ch == '-').count() <= 2;
    }

    /**
     * Groww's {@code order_reference_id} back to our tag: the padding stripped, and only when the result is one of the
     * OMS's tags ({@code F\d+[A-Z]...}) whose own reference id is exactly this one (review 8 Nov 2026: any reference, a
     * hand order's or another app's, was read as a tag of ours); null otherwise.
     */
    public static String tagOf(String referenceId) {
        String candidate = unpadded(referenceId);
        if (candidate == null || !ours(candidate)) {
            return null;
        }
        try {
            return referenceId(candidate).equals(referenceId.trim()) ? candidate : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The reference id with its padding stripped (no check that it is ours); null for none. */
    static String unpadded(String referenceId) {
        if (referenceId == null) {
            return null;
        }
        String r = referenceId.trim();
        if (r.isEmpty() || r.equalsIgnoreCase("null")) {
            return null;
        }
        return PADDING.matcher(r).replaceFirst("");
    }

    /** One of the OMS's tags (1-20 alphanumerics of the form {@code F<intent><letter>...}). */
    static boolean ours(String tag) {
        return tag != null && TAG.matcher(tag).matches() && OUR_TAG.matcher(tag).matches();
    }

    // ------------------------------------------------------------------ orders

    /** This build sends no order to Groww itself (DRY_RUN only; LIVE needs READ_LIVE, the static IP and the canary). */
    private void refuseRealOrders(String call) {
        if (config.ordersReachGroww()) {
            throw new BrokerException(Kind.TERMINAL, call + " refused: this build has no LIVE Groww connection (orders "
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
        GrowwInstruments.Contract c = contract(r.instrumentKey());
        if (r.quantity() % c.lotSize() != 0) {
            throw new BrokerException(Kind.TERMINAL, "place " + tag + ": quantity " + r.quantity()
                    + " is not a whole number of lots of " + c.lotSize());
        }
        if (r.quantity() > Integer.MAX_VALUE) {
            throw new BrokerException(Kind.TERMINAL, "place " + tag + ": quantity too large");
        }
        boolean stop = r.type() == OrderType.STOP_LIMIT;
        ObjectNode body = http.object();
        body.put("trading_symbol", c.tradingSymbol());
        body.put("quantity", (int) r.quantity());
        body.put("price", price(r.limitPrice(), c.tickSize(), r.side()));
        if (stop) {
            body.put("trigger_price", price(r.triggerPrice(), c.tickSize(), null));
        } else {
            body.putNull("trigger_price");                              // as the SDK sends it for a LIMIT
        }
        body.put("validity", "DAY");
        body.put("exchange", c.exchange());
        body.put("segment", GrowwHttp.SEGMENT);
        body.put("product", config.product());
        body.put("order_type", stop ? "SL" : "LIMIT");
        body.put("transaction_type", r.side() == OrderSide.BUY ? "BUY" : "SELL");
        body.put("order_reference_id", referenceId(tag));
        for (int attempt = 1; ; attempt++) {
            try {
                limiter.acquire();
                JsonNode payload = http.post(GrowwHttp.PLACE, body).path("payload");
                String id = payload.path("groww_order_id").asString("").trim();
                if (id.isEmpty()) {
                    throw new GrowwException(Kind.UNKNOWN, "place " + tag + ": Groww answered SUCCESS without an order "
                            + "id", 200, "", true, null);
                }
                brokerIdByTag.put(tag, id);
                String status = payload.path("order_status").asString("").trim().toUpperCase(Locale.ROOT);
                if (status.equals("FAILED") || status.equals("REJECTED")) {
                    String remark = payload.path("remark").asString("").trim();
                    throw new BrokerException(Kind.TERMINAL, "place " + tag + ": Groww " + status.toLowerCase(Locale.ROOT)
                            + " the order" + (remark.isEmpty() || remark.equalsIgnoreCase("null") ? "" : ": " + remark));
                }
                return;
            } catch (GrowwException e) {
                if (e.kind() == Kind.RETRYABLE && !e.mayHaveReached() && attempt < 3) {
                    sleep(200L * attempt);                              // 429 or refused connection: not at the broker
                    continue;
                }
                boolean duplicate = GrowwHttp.DUPLICATE_REFERENCE.equals(e.errorCode());
                if (duplicate && !ours(tag)) {
                    // not one of the OMS's tags: the order holding this reference cannot be told to be ours
                    throw new BrokerException(Kind.TERMINAL, "place " + tag + ": Groww refused the reference id as a "
                            + "duplicate (GA007) and the tag is not one of ours: not adopted", e);
                }
                if (duplicate || e.kind() == Kind.UNKNOWN || (e.kind() == Kind.RETRYABLE && e.mayHaveReached())) {
                    Optional<BrokerOrder> found;
                    try {
                        found = lookUp(tag);
                    } catch (BrokerException lost) {
                        // the session went during the look-up (as the Kite hotfix): the place itself may have happened,
                        // so it is never reported as not sent
                        throw new BrokerException(Kind.UNKNOWN, "place " + tag + ": outcome unknown; the look-up by "
                                + "reference failed (" + lost.kind() + ": " + lost.getMessage() + ")", lost);
                    }
                    if (found.isPresent()) {
                        brokerIdByTag.put(tag, found.get().brokerOrderId());
                        log.warn("place {} {}; found at Groww as {}", tag, duplicate ? "refused as a duplicate reference"
                                : "had an unknown outcome", found.get().brokerOrderId());
                        return;
                    }
                    // never placed again here (as the Kite and Upstox adapters): the caller records the order as unknown;
                    // a later attempt with the same tag reads the book first
                    throw new BrokerException(Kind.UNKNOWN, "place " + tag + ": " + (duplicate ? "Groww refused the "
                            + "reference id as a duplicate (GA007)" : "outcome unknown") + ", not found at Groww by "
                            + "reference", e);
                }
                throw e;
            }
        }
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
     * Changes a working order: the order id, segment, order type, price and (for a stop) trigger, and always a quantity
     * (quantity 0 sends the order's own; review 8 Nov 2026: none was sent). Groww's {@code quantity} is taken to be the
     * order's total, not what remains (UNCONFIRMED), so a partly filled order is never re-sized here: that is refused
     * ({@link Kind#TERMINAL}, cancel and re-place) until the canary proves which. The {@link #MAX_MODIFICATIONS}+1st
     * modification of an order is refused here.
     */
    @Override
    public void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
        refuseRealOrders("modify");
        if (modificationsByTag.getOrDefault(clientOrderId, 0) >= MAX_MODIFICATIONS) {
            throw new BrokerException(Kind.TERMINAL, "modify " + clientOrderId + " refused: Groww allows at most "
                    + MAX_MODIFICATIONS + " modifications of an order");
        }
        BrokerOrder order = known(clientOrderId);
        if (order.filledQuantity() > 0 && quantity > 0 && quantity != order.quantity()) {
            // whether Groww's quantity is the total or what remains is UNCONFIRMED until the canary: never guessed
            throw new BrokerException(Kind.TERMINAL, "modify " + clientOrderId + " refused: resize a partly filled order "
                    + "by cancel and re-place (" + order.filledQuantity() + " of " + order.quantity() + " filled)");
        }
        GrowwInstruments.Contract c = contract(order.instrumentKey());
        boolean stop = order.type() == OrderType.STOP_LIMIT;
        ObjectNode body = http.object();
        body.put("groww_order_id", order.brokerOrderId());
        body.put("order_type", stop ? "SL" : "LIMIT");
        body.put("segment", GrowwHttp.SEGMENT);
        long sent = quantity > 0 ? quantity : order.quantity();         // Groww's modify carries the quantity
        if (sent > Integer.MAX_VALUE || sent <= 0) {
            throw new BrokerException(Kind.TERMINAL, "modify " + clientOrderId + ": no valid quantity (" + sent + ")");
        }
        body.put("quantity", (int) sent);
        body.put("price", price(limitPrice > 0 ? limitPrice : order.limitPrice(), c.tickSize(), order.side()));
        if (stop) {
            body.put("trigger_price", price(triggerPrice > 0 ? triggerPrice : order.triggerPrice(), c.tickSize(), null));
        }
        limiter.acquire();
        modificationsByTag.merge(clientOrderId, 1, Integer::sum);       // counted when sent: Groww may count a refusal
        http.post(GrowwHttp.MODIFY, body);
    }

    /** Modifications this gateway has sent for the order with this tag. */
    int modifications(String clientOrderId) {
        return modificationsByTag.getOrDefault(clientOrderId, 0);
    }

    @Override
    public void cancel(String clientOrderId) {
        refuseRealOrders("cancel");
        String id = brokerIdByTag.get(clientOrderId);              // the place's id when the book does not show it yet
        if (id == null || id.isEmpty()) {
            id = known(clientOrderId).brokerOrderId();
        }
        ObjectNode body = http.object();
        body.put("segment", GrowwHttp.SEGMENT);
        body.put("groww_order_id", id);
        limiter.acquire();
        http.post(GrowwHttp.CANCEL, body);
    }

    private BrokerOrder known(String tag) {
        return orderByTag(tag).orElseThrow(() -> new BrokerException(Kind.TERMINAL, "no order with tag " + tag
                + " at Groww"));
    }

    // ------------------------------------------------------------------ reads

    /**
     * The options buying balance ({@code payload.fno_margin_details.option_buy_balance_available}: the field the Groww
     * login reads; UNCONFIRMED on a real account). An answer without it is refused (the entry is refused as funds
     * unknown), never read as zero or as unlimited.
     */
    @Override
    public double availableFunds() {
        JsonNode fno = http.get(GrowwHttp.MARGINS).path("payload").path("fno_margin_details");
        double v = GrowwHttp.number(fno.path("option_buy_balance_available"), Double.NaN);
        if (Double.isNaN(v)) {
            throw new BrokerException(Kind.TERMINAL, "Groww margins answer has no "
                    + "fno_margin_details.option_buy_balance_available");
        }
        return v;
    }

    @Override
    public Quote quote(String instrumentKey) {
        if (quotes == null) {
            throw new BrokerException(Kind.TERMINAL, "Groww quotes are not implemented in this build and this connection "
                    + "has no quote source: no order in " + instrumentKey);
        }
        Quote q = quotes.apply(instrumentKey);
        if (q == null) {
            throw new BrokerException(Kind.RETRYABLE, "no quote for " + instrumentKey);
        }
        return q;
    }

    /**
     * The day's FNO orders, every page until an empty one (review 8 Nov 2026: a page shorter than asked was taken as the
     * last, but Groww may serve fewer than asked; at most {@link #MAX_PAGES}); a row seen on an earlier page (the list
     * moved while it was read) is kept once, by {@code groww_order_id}.
     */
    @Override
    public List<BrokerOrder> orders() {
        Map<String, BrokerOrder> orders = new java.util.LinkedHashMap<>();
        String firstOfPrevious = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode list = http.get(GrowwHttp.LIST + "?segment=" + GrowwHttp.SEGMENT + "&page=" + page + "&page_size="
                    + config.pageSize()).path("payload").path("order_list");
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            String first = list.path(0).path("groww_order_id").asString("");
            if (first.equals(firstOfPrevious)) {
                log.warn("Groww order list: page {} repeats the previous page; reading stopped", page);
                break;                                              // a server that ignores page: never loop
            }
            firstOfPrevious = first;
            for (JsonNode o : list) {
                BrokerOrder row = order(o, null);
                orders.putIfAbsent(row.brokerOrderId().isEmpty() ? "#" + orders.size() : row.brokerOrderId(), row);
            }
            if (page == MAX_PAGES - 1) {
                log.warn("Groww order list: more than {} pages; the rest is not read", MAX_PAGES);
            }
        }
        return new ArrayList<>(orders.values());
    }

    /**
     * Our order with this tag: Groww's status by reference id, then the order's detail (two reads; review 8 Nov 2026: the
     * whole paged list was read first); when Groww does not know the reference (GA004), the day's order list as well (an
     * index that lags). A tag that is not one of the OMS's is never ours at Groww.
     */
    @Override
    public Optional<BrokerOrder> orderByTag(String clientTag) {
        if (!ours(clientTag)) {
            return Optional.empty();
        }
        String reference = referenceId(clientTag);
        try {
            JsonNode status = http.get(GrowwHttp.STATUS_BY_REFERENCE + GrowwHttp.encode(reference) + "?segment="
                    + GrowwHttp.SEGMENT).path("payload");
            String id = status.path("groww_order_id").asString("").trim();
            if (!id.isEmpty()) {
                JsonNode detail = http.get(GrowwHttp.DETAIL + GrowwHttp.encode(id) + "?segment=" + GrowwHttp.SEGMENT)
                        .path("payload");
                BrokerOrder o = order(detail, null);
                if (clientTag.equals(o.clientTag())) {
                    return Optional.of(o);
                }
            }
        } catch (GrowwException e) {
            if (!(GrowwHttp.NOT_FOUND.equals(e.errorCode()) || e.httpStatus() == 404)) {
                throw e;
            }
        }
        return orders().stream().filter(o -> clientTag.equals(o.clientTag())).findFirst();
    }

    /**
     * Today's fills. Groww has no day-wide trade book: each of the day's orders with a filled quantity has its trades
     * read ({@code /v1/order/trades/{id}}, paged); a finished order's trades are kept and not read again. A trade carries
     * its order's key (an order outside the checked list: exchange and trading symbol, so the reconciliation shows it).
     */
    @Override
    public List<BrokerTrade> trades() {
        List<BrokerTrade> trades = new ArrayList<>();
        for (BrokerOrder o : orders()) {
            if (o.filledQuantity() <= 0) {
                continue;
            }
            List<BrokerTrade> kept = tradesOfFinished.get(o.brokerOrderId());
            if (kept != null) {
                trades.addAll(kept);
                continue;
            }
            List<BrokerTrade> read = tradesOf(o);
            long traded = read.stream().mapToLong(BrokerTrade::quantity).sum();
            if (o.status().isTerminal() && traded == o.filledQuantity()) {
                // kept only when complete (review 8 Nov 2026: a trade list read before Groww had all the fills was kept)
                tradesOfFinished.put(o.brokerOrderId(), List.copyOf(read));
            }
            trades.addAll(read);
        }
        return trades;
    }

    private List<BrokerTrade> tradesOf(BrokerOrder o) {
        List<BrokerTrade> out = new ArrayList<>();
        String key = o.instrumentKey() != null ? o.instrumentKey() : o.symbol();
        int size = config.tradePageSize();
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode list = http.get(GrowwHttp.TRADES + GrowwHttp.encode(o.brokerOrderId()) + "?segment="
                    + GrowwHttp.SEGMENT + "&page=" + page + "&page_size=" + size).path("payload").path("trade_list");
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            for (JsonNode t : list) {
                String side = t.path("transaction_type").asString("");
                String id = t.path("groww_trade_id").asString("");
                if (!id.isEmpty() && out.stream().anyMatch(x -> id.equals(x.tradeId()))) {
                    continue;                                       // the same fill on two pages: once
                }
                out.add(new BrokerTrade(t.path("groww_trade_id").asString(""), o.brokerOrderId(), key,
                        o.symbol(), side.isBlank() ? o.side() : side(side), GrowwHttp.whole(t.path("quantity")),
                        GrowwHttp.number(t.path("price"), 0), GrowwHttp.time(t.path("trade_date_time").asString(""))));
            }
            if (list.size() < size) {
                break;
            }
        }
        return out;
    }

    /**
     * Today's positions of the configured product: {@code quantity} is the net, {@code credit_*} the day's buys and
     * {@code debit_*} its sells (UNCONFIRMED; prices typed int in Groww's schema but rupees in its examples: read as
     * decimals). The net is checked against the buys less the sells (with or without the carried-forward quantities):
     * a row where they disagree refuses the read ({@link Kind#RETRYABLE}; review 8 Nov 2026), so a misread field never
     * becomes a position.
     */
    @Override
    public List<BrokerPosition> positions() {
        List<BrokerPosition> positions = new ArrayList<>();
        JsonNode list = http.get(GrowwHttp.POSITIONS + "?segment=" + GrowwHttp.SEGMENT).path("payload").path("positions");
        for (JsonNode p : list.isArray() ? list : List.<JsonNode>of()) {
            if (!config.product().equalsIgnoreCase(p.path("product").asString(config.product()))) {
                continue;
            }
            String key = key(p);
            long buy = GrowwHttp.whole(p.path("credit_quantity")), sell = GrowwHttp.whole(p.path("debit_quantity"));
            long net = GrowwHttp.whole(p.path("quantity"));
            long carried = GrowwHttp.whole(p.path("carry_forward_credit_quantity"))
                    - GrowwHttp.whole(p.path("carry_forward_debit_quantity"));
            if (net != buy - sell && net != buy - sell + carried) {
                throw new BrokerException(Kind.RETRYABLE, "Groww position " + p.path("trading_symbol").asString("")
                        + ": quantity " + net + " is not credit " + buy + " less debit " + sell
                        + (carried != 0 ? " (carried " + carried + ")" : "") + "; positions not read");
            }
            double buyPrice = GrowwHttp.number(p.path("credit_price"), Double.NaN);
            double sellPrice = GrowwHttp.number(p.path("debit_price"), Double.NaN);
            double buyValue = Double.isNaN(buyPrice) ? Double.NaN : buyPrice * buy;
            double sellValue = Double.isNaN(sellPrice) ? Double.NaN : sellPrice * sell;
            double average = buy > 0 && !Double.isNaN(buyPrice) ? buyPrice : GrowwHttp.number(p.path("net_price"), 0);
            positions.add(new BrokerPosition(token(key), p.path("trading_symbol").asString(""), net, average, key, buy,
                    sell, buyValue, sellValue));
        }
        return positions;
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

    private GrowwInstruments.Contract contract(String instrumentKey) {
        return instruments.byInstrumentKey(instrumentKey).orElseThrow(() -> new BrokerException(Kind.TERMINAL,
                "instrument " + instrumentKey + " is not in Groww's checked instrument list"));
    }

    /** Our key of a row: its exchange and trading symbol through the checked list; null when not in it. */
    private String key(JsonNode row) {
        return instruments.bySymbol(row.path("exchange").asString(""), row.path("trading_symbol").asString(""))
                .map(GrowwInstruments.Contract::instrumentKey).orElse(null);
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

    /**
     * One row of the order list or an order's detail. A row of a contract outside the checked list has no key; its
     * symbol is kept as {@code EXCHANGE:trading_symbol}, so the trades built from it still name it.
     */
    private BrokerOrder order(JsonNode o, String knownTag) {
        String raw = o.path("order_status").asString("").trim();
        long filled = GrowwHttp.whole(o.path("filled_quantity"));
        String tag = tagOf(o.path("order_reference_id").asString(""));
        if (tag == null && knownTag != null && ours(knownTag)
                && referenceId(knownTag).equals(o.path("order_reference_id").asString("").trim())) {
            tag = knownTag;
        }
        long quantity = GrowwHttp.whole(o.path("quantity"));
        String type = o.path("order_type").asString("LIMIT").trim().toUpperCase(Locale.ROOT);
        String exchange = o.path("exchange").asString("").trim().toUpperCase(Locale.ROOT);
        String symbol = o.path("trading_symbol").asString("").trim();
        String key = key(o);
        Instant updated = GrowwHttp.time(o.path("exchange_time").asString(""));
        String remark = o.path("remark").asString("").trim();
        return new BrokerOrder(tag, o.path("groww_order_id").asString(""), key,
                key != null || exchange.isEmpty() ? symbol : exchange + ":" + symbol,
                side(o.path("transaction_type").asString("")),
                type.startsWith("SL") || type.startsWith("STOP") ? OrderType.STOP_LIMIT : OrderType.LIMIT,
                quantity, filled, GrowwHttp.number(o.path("average_fill_price"), 0),
                GrowwHttp.number(o.path("price"), 0), GrowwHttp.number(o.path("trigger_price"), 0),
                status(raw, filled, quantity),
                raw, remark.isEmpty() || remark.equalsIgnoreCase("null") ? null : remark,
                updated != null ? updated : GrowwHttp.time(o.path("created_at").asString("")));
    }

    /** Groww's {@code transaction_type}: BUY or SELL; anything else (missing, unknown) is no side (null), never a buy. */
    static OrderSide side(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "BUY", "B" -> OrderSide.BUY;
            case "SELL", "S" -> OrderSide.SELL;
            default -> null;
        };
    }

    /** Whether the gateway recognises this raw Groww order status (the READ_LIVE probe counts the others). */
    public static boolean knownStatus(String raw) {
        return raw != null && KNOWN_STATUSES.contains(raw.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Groww's order statuses onto ours (UNCONFIRMED mapping): EXECUTED and COMPLETED are filled, REJECTED and FAILED
     * rejected, CANCELLED cancelled (all terminal); TRIGGER_PENDING is a waiting stop; OPEN, ACKED and the requested
     * modification or cancellation are working; NEW, APPROVED and DELIVERY_AWAITED are pending. A partial fill is a
     * filled quantity on any of the live ones. An unknown status is never taken as terminal: it is pending (or partial)
     * and logged once. Whatever the status word, an order whose filled quantity reaches its quantity is filled (review
     * 8 Nov 2026: DELIVERY_AWAITED and unknown words with everything filled read as partial).
     */
    static OrderStatus status(String raw, long filled, long quantity) {
        if (quantity > 0 && filled >= quantity) {
            String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (!KNOWN_STATUSES.contains(s) && loggedUnknownStatuses.add(s)) {
                log.warn("Groww order status '{}' is not known: everything filled, taken as filled", s);
            }
            return OrderStatus.FILLED;
        }
        return status(raw, filled);
    }

    /** {@link #status(String, long, long)} without the order's quantity (no all-filled rule). */
    static OrderStatus status(String raw, long filled) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_STATUSES.contains(s) && loggedUnknownStatuses.add(s)) {
            log.warn("Groww order status '{}' is not known: taken as working", s);
        }
        return switch (s) {
            case "EXECUTED", "COMPLETED" -> OrderStatus.FILLED;
            case "REJECTED", "FAILED" -> OrderStatus.REJECTED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            case "TRIGGER_PENDING" -> filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.TRIGGER_PENDING;
            case "OPEN", "ACKED", "MODIFICATION_REQUESTED", "CANCELLATION_REQUESTED" -> filled > 0
                    ? OrderStatus.PARTIALLY_FILLED : OrderStatus.OPEN;
            default -> filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.PENDING;
        };
    }

    static boolean terminal(String raw) {
        return raw != null && TERMINAL_STATUSES.contains(raw.trim().toUpperCase(Locale.ROOT));
    }

    /** A price on the tick as a JSON number with two decimals: a buy rounds up, a sell down, a trigger to the nearest. */
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
