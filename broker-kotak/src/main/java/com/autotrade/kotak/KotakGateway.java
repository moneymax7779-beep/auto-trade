package com.autotrade.kotak;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import com.autotrade.kotak.KotakHttp.Call;

import tools.jackson.databind.JsonNode;

/**
 * Kotak Neo's Trade API as a {@link BrokerConnection} (live adapters plan, step B1), modelled on the Kite adapter.
 * <ul>
 *   <li><b>Orders</b>: LIMIT ({@code pt=L}) or stop-limit ({@code pt=SL}, with a trigger) only, product from the config
 *       (MIS), validity DAY, our tag in {@code ig} (echoed back as {@code GuiOrdId}: UNCONFIRMED, see {@link #TAG_FIELD});
 *       prices on the contract's tick, a buy rounded up and a sell down. Market orders and SL-M are never sent.</li>
 *   <li><b>Idempotent place</b>: a tag already placed by this gateway is ignored, and a tag this gateway has not placed
 *       is first looked up in Kotak's order book (a restarted worker: whether Kotak refuses a repeated {@code ig} is
 *       UNCONFIRMED, so idempotency never rests on that refusal). A place whose outcome is unknown
 *       (timeout, dropped connection, 5xx, stCode 1005) is never sent again: the order is looked up by tag after
 *       {@code placeLookupWait} (three tries), adopted when found, else reported {@link Kind#UNKNOWN}. A 429 or a refused
 *       connection never reached Kotak and is retried after a short wait. Kotak refusing a tag it already has
 *       ({@link #DUPLICATE_TAG}) is a place that already happened: it is looked up and adopted.</li>
 *   <li><b>Books</b>: {@link #poll()} pulls the order book (the truth) and emits an {@link OrderUpdate} for each change of
 *       status or filled quantity; a repeated or older report changes nothing. Positions are those of the configured
 *       product only.</li>
 *   <li><b>Quotes</b>: Kotak's quote API is out of scope in this build. {@link #quote} answers from the supplied quote
 *       source (the simulator's book in STORED DRY_RUN and tests; our own live capture when reading the real broker),
 *       and without one refuses with a {@link BrokerException} (never an unchecked crash on the account's lane), which
 *       the venue treats as no quote: no order is sent.</li>
 * </ul>
 */
public final class KotakGateway implements BrokerConnection {

    private static final Logger log = LoggerFactory.getLogger(KotakGateway.class);
    /** Our tags: the OMS's 1-20 alphanumerics. Kotak's limit on {@code ig} is not documented (UNCONFIRMED). */
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9]{1,20}");
    /** The place field our tag goes in (OpenAlgo's plugin, the SDK's optional {@code ig}). UNCONFIRMED by Kotak's docs. */
    static final String TAG_FIELD = "ig";
    /** The order/trade book field that echoes it (OpenAlgo). UNCONFIRMED by Kotak's docs. */
    static final String TAG_ECHO = "GuiOrdId";
    /** Kotak's refusal of a repeated {@code ig} (reported by OpenAlgo users). UNCONFIRMED wording. */
    static final String DUPLICATE_TAG = "client orderid already exists";
    /**
     * A book with no rows comes back {@code Not_Ok} with exactly this message instead of an empty list (UNCONFIRMED);
     * any other failed read is RETRYABLE, never an empty book.
     */
    static final String NO_DATA = "No Data";
    /** The OMS's own tags (as {@code BrokerReconciler.OUR_TAG}): a book row's {@code GuiOrdId} is ours only if it is one. */
    static final Pattern OUR_TAG = Pattern.compile("F\\d+[A-Z].*");
    private static final int LOOKUPS = 3;
    private static final Set<String> TERMINAL_STATUSES = Set.of("complete", "rejected", "cancelled");
    private static final Set<String> KNOWN_STATUSES = Set.of("put order req received", "validation pending",
            "open pending", "open", "trigger pending", "modified", "complete", "rejected", "cancelled",
            "after market order req received", "modify pending", "cancel pending", "modify validation pending",
            "partially filled");
    private static final Set<String> loggedUnknownStatuses = ConcurrentHashMap.newKeySet();

    private final KotakConfig config;
    private final KotakHttp http;
    private final KotakInstruments instruments;
    private final Function<String, Quote> quotes;
    private final RateLimiter limiter;
    private final List<Consumer<OrderUpdate>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> brokerIdByTag = new ConcurrentHashMap<>();
    private final Map<String, BrokerOrder> lastSeen = new ConcurrentHashMap<>();

    /**
     * @param quotes the executable book by our instrument key ({@code null}: no quote source; {@link #quote} refuses)
     */
    public KotakGateway(KotakConfig config, KotakHttp http, KotakInstruments instruments, Function<String, Quote> quotes) {
        this.config = config;
        this.http = http;
        this.instruments = instruments;
        this.quotes = quotes;
        this.limiter = new RateLimiter(config.maxOrdersPerSecond());
    }

    @Override
    public String name() {
        return "KOTAK_NEO";
    }

    @Override
    public BrokerCapabilities capabilities() {
        // one static IP per account (Kotak: the session must be created from the IP that sends orders; slots UNCONFIRMED)
        return new BrokerCapabilities(true, true, config.maxOrdersPerSecond(), 20, false,
                BrokerCapabilities.UpdateChannel.POLLING, true, BrokerCapabilities.LoginFlow.DAILY_TOTP_MPIN, 1);
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
        KotakInstruments.Contract c = contract(r.instrumentKey());
        Optional<BrokerOrder> already;                                  // a restart: Kotak may accept a repeated ig
        try {
            already = orderByTag(tag);
        } catch (BrokerException e) {
            if (e.kind() == Kind.SESSION) {
                throw e;
            }
            throw new BrokerException(Kind.RETRYABLE, "place " + tag + ": Kotak's order book could not be read to check "
                    + "the tag first (" + e.getMessage() + "); not sent", e);
        }
        if (already.isPresent()) {
            brokerIdByTag.put(tag, already.get().brokerOrderId());
            log.warn("place {}: already in Kotak's book as {}; adopted, not placed again", tag,
                    already.get().brokerOrderId());
            return;
        }
        Map<String, String> j = new LinkedHashMap<>();
        j.put("am", "NO");
        j.put("dq", "0");
        j.put("es", c.segment());
        j.put("mp", "0");
        j.put("pc", config.product());
        j.put("pf", "N");
        j.put("pr", price(r.limitPrice(), c.tickSize(), r.side()));
        j.put("pt", r.type() == OrderType.STOP_LIMIT ? "SL" : "L");
        j.put("qt", Long.toString(r.quantity()));
        j.put("rt", "DAY");
        j.put("tp", r.type() == OrderType.STOP_LIMIT ? price(r.triggerPrice(), c.tickSize(), null) : "0");
        j.put("ts", c.tradingSymbol());
        j.put("tt", r.side() == OrderSide.BUY ? "B" : "S");
        j.put(TAG_FIELD, tag);
        for (int attempt = 1; ; attempt++) {
            try {
                limiter.acquire();
                JsonNode body = http.post(KotakHttp.PLACE, j, Call.ORDER);
                String id = body.path("nOrdNo").asString("");
                if (id.isEmpty()) {
                    throw new KotakException(Kind.UNKNOWN, "place " + tag + ": Kotak answered Ok without an order number",
                            200, 0, true, null);
                }
                brokerIdByTag.put(tag, id);
                return;
            } catch (BrokerException e) {
                if (e.kind() == Kind.RETRYABLE && !mayHaveReached(e) && attempt < 3) {
                    sleep(200L * attempt);                              // 429 or refused connection: not at the broker
                    continue;
                }
                boolean duplicate = e.kind() == Kind.TERMINAL && e.getMessage() != null
                        && e.getMessage().toLowerCase(Locale.ROOT).contains(DUPLICATE_TAG);
                if (duplicate || e.kind() == Kind.UNKNOWN || (e.kind() == Kind.RETRYABLE && mayHaveReached(e))) {
                    Optional<BrokerOrder> found;
                    try {
                        found = lookUp(tag);
                    } catch (BrokerException lookup) {
                        // the session ended while the order may be at Kotak: never reported as not placed (SESSION
                        // would let the caller treat it as refused); unknown, with what happened (code review 8 Nov 2026)
                        BrokerException unknown = new BrokerException(Kind.UNKNOWN, "place " + tag + ": outcome unknown, "
                                + "and the lookup by tag failed: " + lookup.getMessage(), e);
                        unknown.addSuppressed(lookup);
                        throw unknown;
                    }
                    if (found.isPresent()) {
                        brokerIdByTag.put(tag, found.get().brokerOrderId());
                        log.warn("place {} had an unknown outcome; found at Kotak as {}", tag, found.get().brokerOrderId());
                        return;
                    }
                    // never placed again here (as the Kite adapter, code review 3 Nov 2026): the caller records the order
                    // as unknown; a later attempt with the same tag reads the book first
                    throw new BrokerException(Kind.UNKNOWN, "place " + tag + ": outcome unknown, not found at Kotak by "
                            + "tag", e);
                }
                throw e;
            }
        }
    }

    private static boolean mayHaveReached(BrokerException e) {
        return !(e instanceof KotakException k) || k.mayHaveReached();
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
     * Changes a working order (UNCONFIRMED field set: {@code tk, dq, es, mp, dd, vd, pc, pr, pt, qt, tp, ts, no, tt, am};
     * sources differ). Kotak's modify carries the whole order, so what is not changed is sent as the book shows it;
     * {@code qt} is the order's total quantity (UNCONFIRMED: total, not remaining).
     */
    @Override
    public void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
        BrokerOrder order = known(clientOrderId);
        if (order.filledQuantity() > 0 && quantity > 0 && quantity != order.quantity()) {
            // whether Kotak's qt is the total or the remaining quantity is UNCONFIRMED: until the canary proves it, a
            // partly filled order is never re-sized here (the venue cancels and places again)
            throw new BrokerException(Kind.TERMINAL, "resize a partly filled order by cancel and re-place: " + clientOrderId
                    + " has " + order.filledQuantity() + " of " + order.quantity() + " filled");
        }
        KotakInstruments.Contract c = contract(order.instrumentKey());
        boolean stop = order.type() == OrderType.STOP_LIMIT;
        Map<String, String> j = new LinkedHashMap<>();
        j.put("tk", Long.toString(c.exchangeToken()));
        j.put("dq", "0");
        j.put("es", c.segment());
        j.put("mp", "0");
        j.put("dd", "NA");
        j.put("vd", "DAY");
        j.put("pc", config.product());
        j.put("pr", price(limitPrice > 0 ? limitPrice : order.limitPrice(), c.tickSize(), order.side()));
        j.put("pt", stop ? "SL" : "L");
        j.put("qt", Long.toString(quantity > 0 ? quantity : order.quantity()));
        j.put("tp", stop ? price(triggerPrice > 0 ? triggerPrice : order.triggerPrice(), c.tickSize(), null) : "0");
        j.put("ts", c.tradingSymbol());
        j.put("no", order.brokerOrderId());
        j.put("tt", order.side() == OrderSide.BUY ? "B" : "S");
        j.put("am", "NO");
        limiter.acquire();
        http.post(KotakHttp.MODIFY, j, Call.ORDER);
    }

    @Override
    public void cancel(String clientOrderId) {
        String id = brokerIdByTag.get(clientOrderId);              // the place's id when the book does not show it yet
        if (id == null || id.isEmpty()) {
            id = known(clientOrderId).brokerOrderId();
        }
        Map<String, String> j = new LinkedHashMap<>();
        j.put("on", id);
        j.put("am", "NO");
        limiter.acquire();
        http.post(KotakHttp.CANCEL, j, Call.ORDER);
    }

    private BrokerOrder known(String tag) {
        return orderByTag(tag).orElseThrow(() -> new BrokerException(Kind.TERMINAL, "no order with tag " + tag
                + " at Kotak"));
    }

    // ------------------------------------------------------------------ reads

    /** Kotak's limits ({@code POST /quick/user/limits}, all segments): the {@code Net} figure, as the login reads it. */
    @Override
    public double availableFunds() {
        Map<String, String> j = new LinkedHashMap<>();
        j.put("seg", "ALL");
        j.put("exch", "ALL");
        j.put("prod", "ALL");
        JsonNode limits = http.post(KotakHttp.LIMITS, j, Call.READ);
        return KotakHttp.number(limits.path("Net"), Double.NaN);
    }

    @Override
    public Quote quote(String instrumentKey) {
        if (quotes == null) {
            throw new BrokerException(Kind.TERMINAL, "Kotak quotes are not implemented in this build and this connection "
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
        for (JsonNode o : rows(KotakHttp.ORDERS)) {
            orders.add(order(o));
        }
        return orders;
    }

    /** Today's fills. Kotak's trade book has no token ({@code tok} empty): the key comes from the symbol or the order. */
    @Override
    public List<BrokerTrade> trades() {
        List<BrokerTrade> trades = new ArrayList<>();
        Map<String, String> keyByOrder = null;
        for (JsonNode t : rows(KotakHttp.TRADES)) {
            String key = key(t);
            String orderId = t.path("nOrdNo").asString("");
            if (key == null) {
                if (keyByOrder == null) {
                    keyByOrder = new HashMap<>();
                    for (BrokerOrder o : orders()) {
                        keyByOrder.put(o.brokerOrderId(), o.instrumentKey());
                    }
                }
                key = keyByOrder.get(orderId);
            }
            Instant time = KotakHttp.time((t.path("flDt").asString("") + " " + t.path("flTm").asString("")).trim());
            trades.add(new BrokerTrade(t.path("flId").asString(""), orderId, key, t.path("trdSym").asString(""),
                    side(t.path("trnsTp").asString("")), KotakHttp.whole(t.path("fldQty")),
                    KotakHttp.number(t.path("avgPrc"), 0), time));
        }
        return trades;
    }

    /**
     * Today's positions of the configured product: net = (today's and carried-forward buys) − (sells), the day's buy and
     * sell quantities and values; every field arrives as a string.
     */
    @Override
    public List<BrokerPosition> positions() {
        List<BrokerPosition> positions = new ArrayList<>();
        for (JsonNode p : rows(KotakHttp.POSITIONS)) {
            if (!config.product().equalsIgnoreCase(p.path("prod").asString(config.product()))) {
                continue;
            }
            long buy = KotakHttp.whole(p.path("flBuyQty")), sell = KotakHttp.whole(p.path("flSellQty"));
            long cfBuy = KotakHttp.whole(p.path("cfBuyQty")), cfSell = KotakHttp.whole(p.path("cfSellQty"));
            double buyValue = KotakHttp.number(p.path("buyAmt"), Double.NaN);
            double sellValue = KotakHttp.number(p.path("sellAmt"), Double.NaN);
            String key = key(p);
            long token = key == null ? 0 : Long.parseLong(key.substring(key.indexOf('|') + 1));
            long net = buy + cfBuy - sell - cfSell;
            // a net short is averaged on its sells, a long on its buys
            double average = net < 0 ? (sell > 0 && !Double.isNaN(sellValue) ? sellValue / sell : 0)
                    : (buy > 0 && !Double.isNaN(buyValue) ? buyValue / buy : 0);
            positions.add(new BrokerPosition(token, p.path("trdSym").asString(""), net, average, key, buy, sell, buyValue,
                    sellValue));
        }
        return positions;
    }

    /**
     * A book's rows ({@code data}). Only Kotak's exact {@code Not_Ok} "No Data" is an empty book; an Ok answer without a
     * {@code data} array, or any other failure, is RETRYABLE (an error read as an empty book would let a restart place
     * again and the reconciliation see a flat account).
     */
    private Iterable<JsonNode> rows(String path) {
        JsonNode data;
        try {
            data = http.get(path, Call.READ).path("data");
        } catch (KotakException e) {
            if (e.notOkMessage() != null && e.notOkMessage().trim().equalsIgnoreCase(NO_DATA)) {
                return List.of();
            }
            throw e;
        }
        if (!data.isArray()) {
            throw new KotakException(Kind.RETRYABLE, "GET " + path + ": Kotak answered Ok without a data list", 200, 0, true,
                    null);
        }
        return data;
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

    private KotakInstruments.Contract contract(String instrumentKey) {
        return instruments.byInstrumentKey(instrumentKey).orElseThrow(() -> new BrokerException(Kind.TERMINAL,
                "instrument " + instrumentKey + " is not in Kotak's scrip master"));
    }

    /**
     * Our key of a book row: its segment with its token ({@code tok}) when given, else its segment and trading symbol;
     * null when the segment is missing (never assumed nse_fo: a share's token would read as an option) or nothing maps.
     */
    private String key(JsonNode row) {
        String segment = row.path("exSeg").asString("").trim().toLowerCase(Locale.ROOT);
        if (segment.isEmpty()) {
            return null;
        }
        String tok = row.path("tok").asString("").trim();
        if (!tok.isEmpty() && tok.chars().allMatch(Character::isDigit)) {
            return KotakInstruments.instrumentKey(segment, Long.parseLong(tok));
        }
        return instruments.bySymbol(segment, row.path("trdSym").asString("")).map(KotakInstruments.Contract::instrumentKey)
                .orElse(null);
    }

    private BrokerOrder order(JsonNode o) {
        String raw = o.path("ordSt").asString("").trim();
        long filled = KotakHttp.whole(o.path("fldQty"));
        String tag = o.path(TAG_ECHO).asString("").trim();
        String type = o.path("prcTp").asString("L").trim().toUpperCase(Locale.ROOT);
        Instant updated = KotakHttp.time(o.path("hsUpTm").asString(""));
        String reason = o.path("rejRsn").asString("").trim();
        long quantity = KotakHttp.whole(o.path("qty"));
        // GuiOrdId echoes whatever ig an order carried (other apps', Kotak's own): ours only when it is an OMS tag
        return new BrokerOrder(OUR_TAG.matcher(tag).matches() ? tag : null,
                o.path("nOrdNo").asString(""), key(o), o.path("trdSym").asString(""), side(o.path("trnsTp").asString("")),
                type.equals("SL") ? OrderType.STOP_LIMIT : OrderType.LIMIT, quantity, filled,
                KotakHttp.number(o.path("avgPrc"), 0), KotakHttp.number(o.path("prc"), 0),
                KotakHttp.number(o.path("trgPrc"), 0), status(raw, filled, quantity), raw,
                reason.isEmpty() || reason.equalsIgnoreCase("NA") ? null : reason,
                updated != null ? updated : KotakHttp.time(o.path("ordDtTm").asString("")));
    }

    /** B / BUY and S / SELL; anything else (missing, unknown) is null, never taken as a buy. */
    static OrderSide side(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "B", "BUY" -> OrderSide.BUY;
            case "S", "SELL" -> OrderSide.SELL;
            default -> null;
        };
    }

    /** Whether Kotak's raw status is one this gateway knows (the READ_LIVE probe counts the others). */
    public static boolean knownStatus(String raw) {
        return raw != null && KNOWN_STATUSES.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Kotak's order statuses (lower case, an open set) onto ours: complete, rejected and cancelled are terminal; a
     * trigger-pending stop waits; any other status with a filled quantity is a partial fill; the rest (open, modified,
     * the "... pending" and "... received" states) are working. An unknown status is never taken as terminal: it is
     * working (or partial) and logged once.
     */
    static OrderStatus status(String raw, long filled) {
        return status(raw, filled, 0);
    }

    /** As above, and an order filled to its quantity is FILLED whatever the status word (code review 8 Nov 2026). */
    static OrderStatus status(String raw, long filled, long quantity) {
        if (quantity > 0 && filled >= quantity) {
            return OrderStatus.FILLED;
        }
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!KNOWN_STATUSES.contains(s) && loggedUnknownStatuses.add(s)) {
            log.warn("Kotak order status '{}' is not known: taken as working", s);
        }
        return switch (s) {
            case "complete" -> OrderStatus.FILLED;
            case "rejected" -> OrderStatus.REJECTED;
            case "cancelled" -> OrderStatus.CANCELLED;
            case "trigger pending" -> filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.TRIGGER_PENDING;
            case "open", "modified", "partially filled" -> filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.OPEN;
            default -> filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.PENDING;
        };
    }

    static boolean terminal(String raw) {
        return raw != null && TERMINAL_STATUSES.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * A price on the tick as Kotak wants it (a string, no exponent): a buy rounds up, a sell down, a trigger to the
     * nearest; zero is "0".
     */
    static String price(double price, double tick, OrderSide side) {
        double t = tick > 0 ? tick : 0.05;
        double ticks = price / t;
        double rounded = side == OrderSide.BUY ? Math.ceil(ticks - 1e-9) : side == OrderSide.SELL
                ? Math.floor(ticks + 1e-9) : Math.rint(ticks);
        java.math.BigDecimal p = new java.math.BigDecimal(rounded * t).setScale(2, java.math.RoundingMode.HALF_UP);
        return p.signum() == 0 ? "0" : p.toPlainString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
