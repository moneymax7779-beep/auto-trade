package com.autotrade.groww;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.autotrade.broker.Quote;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A local Groww Trade API server (live adapters plan step B3; 127.0.0.1 only) standing for api.groww.in and the
 * instrument CSV's host: create / modify / cancel (JSON bodies with Groww's field names and types), the order list
 * (paged), status by reference id, order detail, per-order trades (paged), positions, margins and
 * {@code /instruments/instrument.csv} (Groww's columns). Headers {@code Authorization: Bearer}, {@code Accept},
 * {@code X-API-VERSION: 1.0} and, on a POST, {@code Content-Type} are required; answers are Groww's envelope
 * ({@code status} SUCCESS / FAILURE with {@code error.code}); statuses are Groww's upper-case ones. DRY_RUN accounts trade
 * against it, so no order can reach Groww, and the fault suite drives every failure through it.
 * <p>
 * <b>Fills</b> (as the Kite, Kotak and Upstox simulators): a limit buy fills against the asks up to its price, level by
 * level, a sell against the bids; the rest rests OPEN. An SL order waits (TRIGGER_PENDING) until the last price reaches
 * its trigger, then works as a limit. Quantities fill in whole lots. MARKET and SL_M are refused. A reference id already
 * used is refused with GA007; a 26th modification of an order with GA006.
 * <p>
 * <b>Faults</b> ({@link #failNext}): Kite's menu (429, a 500 not processed, a 500 or a timeout after the order <i>was</i>
 * processed, a timeout before processing, an expired token (HTTP 401 without a code), an input error, a funds rejection:
 * SUCCESS with order_status REJECTED), plus {@code STATIC_IP} (HTTP 403), {@code TOKEN_INVALID} (HTTP 401, GA005) and
 * {@code DUPLICATE_REF} (GA007 for a reference no order of ours has); {@link #partialFills}, {@link #fillStopOnCancel},
 * {@link #cancelLag}, {@link #staleOrderBookOnce}, {@link #ackLag}, {@link #expireSession}. Messages are the simulator's
 * own (Groww documents codes, not texts).
 */
public final class GrowwSimulator implements AutoCloseable {

    public enum Fault {
        NONE, HTTP_429, HTTP_500, HTTP_500_PROCESSED, TIMEOUT, TIMEOUT_PROCESSED, TOKEN_EXPIRED, INPUT_ERROR,
        REJECT_FUNDS, STATIC_IP, TOKEN_INVALID, DUPLICATE_REF
    }

    /** One contract the simulator lists (as its instrument CSV row); the exchange follows the key (NSE_FO: NSE). */
    public record Listing(String instrumentKey, String tradingSymbol, String underlying, LocalDate expiry, double strike,
                          String type, int lotSize, double tickSize) {

        public String exchange() {
            return GrowwInstruments.exchangeOf(instrumentKey);
        }

        public String exchangeToken() {
            return instrumentKey.substring(instrumentKey.indexOf('|') + 1);
        }

        public GrowwInstruments.Contract contract() {
            return new GrowwInstruments.Contract(instrumentKey, exchange(), tradingSymbol, underlying, expiry, strike, type,
                    lotSize, tickSize, 0);
        }
    }

    /** A day's session as the login would give it. */
    public record Session(String accessToken) {
        @Override
        public String toString() {
            return "Session[<redacted>]";
        }
    }

    private static final class Book {
        final double[] bidPx, askPx;
        final long[] bidQty, askQty;
        final double last;

        Book(List<Quote.Level> bids, List<Quote.Level> asks, double last) {
            bidPx = bids.stream().mapToDouble(Quote.Level::price).toArray();
            bidQty = bids.stream().mapToLong(Quote.Level::quantity).toArray();
            askPx = asks.stream().mapToDouble(Quote.Level::price).toArray();
            askQty = asks.stream().mapToLong(Quote.Level::quantity).toArray();
            this.last = last;
        }

        double bid() {
            return bidPx.length == 0 ? 0 : bidPx[0];
        }
    }

    private static final class Order {
        String id, reference, key, symbol, exchange, side, type, product, status = "OPEN", remark;
        long quantity, filled;
        double price, trigger, average;
        Instant placedAt, updatedAt;
        int lot, modifications;
        boolean entry;
        String shownBeforeCancel;
        int cancelHiddenReads, ackHiddenReads;

        double value() {
            return average * filled;
        }
    }

    private record Trade(String id, String orderId, String key, String symbol, String exchange, String side, long quantity,
                         double price, Instant time, String product) {
    }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    /** Groww's CSV header (the columns the docs list, in their order). */
    static final String CSV_HEADER = "exchange,exchange_token,trading_symbol,groww_symbol,name,instrument_type,segment,"
            + "series,isin,underlying_symbol,underlying_exchange_token,lot_size,expiry_date,strike_price,tick_size,"
            + "freeze_quantity,is_reserved,buy_allowed,sell_allowed";

    private final HttpServer server;
    private final ExecutorService executor;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Listing> listings = new LinkedHashMap<>();
    private final Map<String, Book> books = new HashMap<>();
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final Set<String> references = new HashSet<>();
    private final List<Trade> trades = new ArrayList<>();
    private final Map<String, Deque<Fault>> faults = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000);   // unique across instances
    private final long timeoutMillis;
    private volatile Supplier<Instant> clock = Instant::now;
    private Session session = new Session("sim-token-" + UUID.randomUUID());
    private boolean strict;
    private boolean expired;
    private double funds = 1e9;
    private double partialFraction = 1.0;
    private boolean fillStopOnCancel;
    private int cancelLagReads;
    private int ackLagReads;
    private boolean staleOnce;
    private long positionsSkew;
    private int pageCap = 100;
    private boolean pageOverlap;
    private int hideLastTradeReads;
    private List<ObjectNode> previousBook = List.of();
    private int requests;
    private int tokensSentToAssets;
    private final Map<String, Integer> requestsByOperation = new HashMap<>();

    /** Listens on 127.0.0.1 ({@code port} 0 = any free port); {@code timeoutMillis}: how long a TIMEOUT fault stalls. */
    public GrowwSimulator(int port, long timeoutMillis) throws IOException {
        this.timeoutMillis = timeoutMillis;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.executor = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("groww-sim-", 0).factory());
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();                                 // its threads end with the simulator
    }

    /** Whether {@link #close} has stopped the simulator's threads (tests). */
    public boolean isClosed() {
        return executor.isShutdown();
    }

    // ------------------------------------------------------------------ set-up and faults

    public synchronized GrowwSimulator list(Listing listing) {
        listings.put(listing.instrumentKey(), listing);
        return this;
    }

    public synchronized Listing byKey(String instrumentKey) {
        return instrumentKey == null ? null : listings.get(instrumentKey);
    }

    private Listing listing(String instrumentKey) {
        Listing l = byKey(instrumentKey);
        if (l == null) {
            throw new IllegalArgumentException("not listed: " + instrumentKey);
        }
        return l;
    }

    private Listing bySymbol(String exchange, String symbol) {
        for (Listing l : listings.values()) {
            if (l.exchange().equals(exchange) && l.tradingSymbol().equals(symbol)) {
                return l;
            }
        }
        return null;
    }

    /** The executable top of book of a contract; resting orders that it reaches fill now. */
    public synchronized void quote(String instrumentKey, double bid, long bidQuantity, double ask, long askQuantity,
                                   double last) {
        depth(instrumentKey, level(bid, bidQuantity), level(ask, askQuantity), last);
    }

    /** The book with its levels (best first); resting orders that it reaches fill now. */
    public synchronized void depth(String instrumentKey, List<Quote.Level> bids, List<Quote.Level> asks, double last) {
        listing(instrumentKey);
        books.put(instrumentKey, new Book(bids, asks, last));
        orders.values().stream().filter(o -> o.key.equals(instrumentKey)).forEach(this::match);
    }

    private static List<Quote.Level> level(double price, long quantity) {
        return price > 0 && quantity > 0 ? List.of(new Quote.Level(price, quantity)) : List.of();
    }

    /**
     * The book as a quote (in process, not over HTTP: Groww's quote API is out of scope), with what our fills left at
     * each level; null when the contract has no book.
     */
    public synchronized Quote book(String instrumentKey) {
        Book b = instrumentKey == null ? null : books.get(instrumentKey);
        if (b == null) {
            return null;
        }
        List<Quote.Level> bids = levels(b.bidPx, b.bidQty), asks = levels(b.askPx, b.askQty);
        Quote.Level bid = bids.isEmpty() ? new Quote.Level(0, 0) : bids.getFirst();
        Quote.Level ask = asks.isEmpty() ? new Quote.Level(0, 0) : asks.getFirst();
        return new Quote(instrumentKey, bid.price(), bid.quantity(), ask.price(), ask.quantity(), b.last, clock.get(), bids,
                asks);
    }

    private static List<Quote.Level> levels(double[] px, long[] qty) {
        List<Quote.Level> out = new ArrayList<>();
        for (int i = 0; i < px.length; i++) {
            if (px[i] > 0 && qty[i] > 0) {
                out.add(new Quote.Level(px[i], qty[i]));
            }
        }
        return out;
    }

    /** A holding the broker already has (a restart): a filled buy without an order, product MIS. */
    public synchronized void holding(String instrumentKey, long quantity, double price) {
        holding(instrumentKey, quantity, price, GrowwConfig.INTRADAY);
    }

    /** A holding of another product (CNC, NRML: not ours, left out of the adapter's positions). */
    public synchronized void holding(String instrumentKey, long quantity, double price, String product) {
        Listing l = listing(instrumentKey);
        trades.add(new Trade(Long.toString(ids.incrementAndGet()), "0", l.instrumentKey(), l.tradingSymbol(),
                l.exchange(), "BUY", quantity, price, clock.get(), product));
    }

    /**
     * Operations: place, modify, cancel, orders, status, detail, trades, positions, funds, instruments.
     */
    public synchronized void failNext(String operation, Fault fault) {
        faults.computeIfAbsent(operation, k -> new ArrayDeque<>()).add(fault);
    }

    public synchronized void funds(double available) {
        this.funds = available;
    }

    public synchronized void partialFills(double fraction) {
        this.partialFraction = fraction;
    }

    public synchronized void fillStopOnCancel(boolean on) {
        this.fillStopOnCancel = on;
    }

    /** A cancelled order still shows its old status in the next {@code reads} order lists. */
    public synchronized void cancelLag(int reads) {
        this.cancelLagReads = reads;
    }

    /** A new order shows NEW in the next {@code reads} order lists before its real status. */
    public synchronized void ackLag(int reads) {
        this.ackLagReads = reads;
    }

    /**
     * Fault: positions show a net {@code quantity} that is {@code skew} away from credit less debit (a misread or
     * inconsistent row); 0 = consistent (default).
     */
    public synchronized void positionsDisagree(long skew) {
        positionsSkew = skew;
    }

    /** The order list serves at most {@code rows} a page, whatever page_size asks (Groww may serve fewer). */
    public synchronized void pageCap(int rows) {
        pageCap = Math.max(1, Math.min(100, rows));
    }

    /** Fault: every page of the order list after the first starts with the previous page's last row (a moving list). */
    public synchronized void pageOverlap(boolean on) {
        pageOverlap = on;
    }

    /** Fault: the next {@code reads} trade lists leave out the order's last fill (Groww's trade list lagging its fills). */
    public synchronized void hideLastTrade(int reads) {
        hideLastTradeReads = reads;
    }

    public synchronized void staleOrderBookOnce() {
        this.staleOnce = true;
    }

    /** The token ends (HTTP 401) until the next {@link #login}. */
    public synchronized void expireSession() {
        this.expired = true;
    }

    /** Only the token issued by {@link #login} is accepted (default: any non-blank bearer token, for DRY_RUN). */
    public synchronized void strictSessions() {
        this.strict = true;
    }

    /** The day's login (Groww's approval flow): a new token; the session is valid again. */
    public synchronized Session login() {
        session = new Session("sim-token-" + UUID.randomUUID());
        expired = false;
        return session;
    }

    public void clock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    public synchronized int orderCount() {
        return orders.size();
    }

    /** Net quantity held per instrument key (buys minus sells, filled). */
    public synchronized Map<String, Long> netQuantities() {
        Map<String, Long> net = new LinkedHashMap<>();
        for (Trade t : trades) {
            net.merge(t.key(), t.side().equals("BUY") ? t.quantity() : -t.quantity(), Long::sum);
        }
        return net;
    }

    public synchronized int requests() {
        return requests;
    }

    /** Requests of one operation: place, modify, cancel, orders, status, detail, trades, positions, funds, instruments. */
    public synchronized int requests(String operation) {
        return requestsByOperation.getOrDefault(operation, 0);
    }

    /** Instrument file requests that carried an Authorization header (the file is public: must be zero). */
    public synchronized int tokensSentToAssets() {
        return tokensSentToAssets;
    }

    /** What our orders took from a contract's book since {@code since}, by price ({@code side} BUY or SELL). */
    public synchronized Map<Double, Long> taken(String instrumentKey, String side, Instant since) {
        Map<Double, Long> taken = new HashMap<>();
        String s = side.startsWith("S") ? "SELL" : "BUY";
        for (Trade t : trades) {
            if (!t.orderId().equals("0") && t.side().equals(s) && t.key().equals(instrumentKey) && t.time() != null
                    && !t.time().isBefore(since)) {
                taken.merge(t.price(), t.quantity(), Long::sum);
            }
        }
        return taken;
    }

    /** Margin in use: the premium paid net of sales, plus what open buy orders block. */
    public synchronized double usedMargin() {
        double used = 0;
        for (Order o : orders.values()) {
            if (o.side.equals("BUY") && !terminal(o)) {
                used += o.price * (o.quantity - o.filled);
            }
        }
        for (Trade t : trades) {
            used += (t.side().equals("BUY") ? 1 : -1) * t.price() * t.quantity();
        }
        return used;
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        Map<String, String> query = query(ex.getRequestURI().getRawQuery());
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String op = operation(path);
        Fault fault;
        synchronized (this) {
            requests++;
            requestsByOperation.merge(op, 1, Integer::sum);
            Deque<Fault> queue = faults.get(op);
            fault = queue == null ? null : queue.poll();
            fault = fault == Fault.NONE ? null : fault;
        }
        try {
            if (op.equals("instruments")) {                             // public file: no token
                if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                    synchronized (this) {
                        tokensSentToAssets++;
                    }
                }
                if (fault == Fault.HTTP_500) {
                    respond(ex, 500, error(GrowwHttp.INTERNAL, "Internal error"));
                    return;
                }
                byte[] bytes = instrumentFile();
                ex.getResponseHeaders().set("Content-Type", "text/csv");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bytes);
                }
                return;
            }
            if (fault == Fault.TIMEOUT) {
                Thread.sleep(timeoutMillis);
                respond(ex, 504, error(GrowwHttp.UNAVAILABLE, "Gateway timed out"));
                return;
            }
            if (fault == Fault.HTTP_429) {
                respond(ex, 429, error("", "Too many requests"));
                return;
            }
            if (fault == Fault.HTTP_500) {
                respond(ex, 500, error(GrowwHttp.INTERNAL, "Internal error, please try again"));
                return;
            }
            if (fault == Fault.TOKEN_EXPIRED) {
                respond(ex, 401, error("", "Unauthorized"));
                return;
            }
            if (fault == Fault.TOKEN_INVALID) {
                respond(ex, 401, error(GrowwHttp.NOT_AUTHORISED, "Invalid access token"));
                return;
            }
            if (fault == Fault.STATIC_IP) {
                respond(ex, 403, error(GrowwHttp.NOT_AUTHORISED, "Request not from a registered IP"));
                return;
            }
            if (fault == Fault.INPUT_ERROR) {
                respond(ex, 400, error(GrowwHttp.BAD_REQUEST, "Price is outside the circuit limits"));
                return;
            }
            if (fault == Fault.DUPLICATE_REF) {
                respond(ex, 400, error(GrowwHttp.DUPLICATE_REFERENCE, "Duplicate order reference id"));
                return;
            }
            String refusal = authorised(ex);
            if (refusal != null) {
                respond(ex, 401, error("", refusal));
                return;
            }
            String accept = ex.getRequestHeaders().getFirst("Accept");
            if (accept == null || !accept.contains("application/json")) {
                respond(ex, 400, error(GrowwHttp.BAD_REQUEST, "Accept: application/json is required"));
                return;
            }
            if (!"1.0".equals(ex.getRequestHeaders().getFirst("X-API-VERSION"))) {
                respond(ex, 400, error(GrowwHttp.BAD_REQUEST, "X-API-VERSION: 1.0 is required"));
                return;
            }
            JsonNode jsonBody = null;
            if (method.equals("POST")) {
                String type = ex.getRequestHeaders().getFirst("Content-Type");
                if (type == null || !type.startsWith("application/json")) {
                    respond(ex, 400, error(GrowwHttp.BAD_REQUEST, "Content-Type: application/json is required"));
                    return;
                }
                try {
                    jsonBody = json.readTree(body);
                } catch (RuntimeException e) {
                    respond(ex, 400, error(GrowwHttp.BAD_REQUEST, "body is not JSON"));
                    return;
                }
            }
            ObjectNode answer = route(method, path, query, jsonBody, fault);
            if (fault == Fault.HTTP_500_PROCESSED) {
                respond(ex, 500, error(GrowwHttp.INTERNAL, "Internal error, please try again"));
                return;
            }
            if (fault == Fault.TIMEOUT_PROCESSED) {
                Thread.sleep(timeoutMillis);
            }
            respond(ex, answer.has("_status") ? answer.remove("_status").asInt() : 200, answer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            respond(ex, 400, error(GrowwHttp.BAD_REQUEST, String.valueOf(e.getMessage())));
        }
    }

    private static String operation(String path) {
        if (path.startsWith(GrowwHttp.STATUS_BY_REFERENCE)) {
            return "status";
        }
        if (path.startsWith(GrowwHttp.DETAIL)) {
            return "detail";
        }
        if (path.startsWith(GrowwHttp.TRADES)) {
            return "trades";
        }
        return switch (path) {
            case GrowwHttp.PLACE -> "place";
            case GrowwHttp.MODIFY -> "modify";
            case GrowwHttp.CANCEL -> "cancel";
            case GrowwHttp.LIST -> "orders";
            case GrowwHttp.POSITIONS -> "positions";
            case GrowwHttp.MARGINS -> "funds";
            case GrowwHttp.INSTRUMENTS -> "instruments";
            default -> path;
        };
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null) {
            return q;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return q;
    }

    /** Null when the call carries a bearer token (the login's, when strict), else why not. */
    private synchronized String authorised(HttpExchange ex) {
        if (expired) {
            return "Access token expired";
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ") || auth.substring(7).isBlank()) {
            return "Authorization: Bearer <token> is required";
        }
        return !strict || auth.substring(7).equals(session.accessToken()) ? null : "Invalid access token";
    }

    private synchronized ObjectNode route(String method, String path, Map<String, String> query, JsonNode body,
                                          Fault fault) {
        String op = operation(path);
        String expected = switch (op) {
            case "place", "modify", "cancel" -> "POST";
            case "orders", "status", "detail", "trades", "positions", "funds" -> "GET";
            default -> null;
        };
        if (expected == null) {
            return status(404, error(GrowwHttp.NOT_FOUND, "Resource not found"));
        }
        if (!expected.equals(method)) {
            return status(405, error(GrowwHttp.BAD_REQUEST, "Method not allowed"));
        }
        if (!op.equals("funds") && !op.equals("place") && !op.equals("modify") && !op.equals("cancel")
                && !GrowwHttp.SEGMENT.equals(query.get("segment"))) {
            return status(400, error(GrowwHttp.BAD_REQUEST, "segment=FNO is required"));
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return switch (op) {
            case "place" -> place(body, fault);
            case "modify" -> modify(body);
            case "cancel" -> cancel(body);
            case "orders" -> orderList(query);
            case "status" -> statusByReference(URLDecoder.decode(last, StandardCharsets.UTF_8));
            case "detail" -> detail(URLDecoder.decode(last, StandardCharsets.UTF_8));
            case "trades" -> tradeList(URLDecoder.decode(last, StandardCharsets.UTF_8), query);
            case "positions" -> positions();
            default -> funds();
        };
    }

    // ------------------------------------------------------------------ orders

    private ObjectNode place(JsonNode b, Fault fault) {
        for (String field : List.of("trading_symbol", "quantity", "price", "validity", "exchange", "segment", "product",
                "order_type", "transaction_type", "order_reference_id")) {
            if (!b.has(field) || b.get(field).isNull()) {
                return status(400, error(GrowwHttp.BAD_REQUEST, "Missing field " + field));
            }
        }
        if (!b.get("quantity").isIntegralNumber() || !b.get("price").isNumber() || !b.get("trading_symbol").isString()
                || !b.get("order_reference_id").isString()
                || (b.has("trigger_price") && !b.get("trigger_price").isNull() && !b.get("trigger_price").isNumber())) {
            return status(400, error(GrowwHttp.BAD_REQUEST, "Invalid field type"));
        }
        String type = b.get("order_type").asString();
        if (type.equals("MARKET") || type.equals("SL_M") || type.equals("SL-M")) {
            return status(400, error(GrowwHttp.BAD_REQUEST, "The simulator refuses " + type + " orders (we never send "
                    + "them)"));
        }
        if (!(type.equals("LIMIT") || type.equals("SL"))) {
            return status(400, error(GrowwHttp.BAD_REQUEST, "Invalid order_type " + type));
        }
        String reference = b.get("order_reference_id").asString();
        if (!GrowwGateway.validReference(reference)) {
            return status(400, error(GrowwHttp.BAD_REQUEST, "order_reference_id must be 8-20 alphanumeric characters "
                    + "with at most two hyphens"));
        }
        if (references.contains(reference)) {
            return status(400, error(GrowwHttp.DUPLICATE_REFERENCE, "Duplicate order reference id"));
        }
        if (!b.get("segment").asString().equals("FNO") || !b.get("validity").asString().equals("DAY")) {
            return status(400, error(GrowwHttp.BAD_REQUEST, "Only FNO DAY orders are simulated"));
        }
        String exchange = b.get("exchange").asString();
        Listing l = bySymbol(exchange, b.get("trading_symbol").asString());
        if (l == null) {
            return status(400, error(GrowwHttp.BAD_REQUEST, "Invalid trading symbol for " + exchange));
        }
        long quantity = b.get("quantity").asLong();
        if (quantity <= 0 || quantity % l.lotSize() != 0) {
            return status(400, error(GrowwHttp.BAD_REQUEST, "Quantity should be a multiple of the lot size "
                    + l.lotSize()));
        }
        double price = b.get("price").asDouble();
        double trigger = b.has("trigger_price") && !b.get("trigger_price").isNull() ? b.get("trigger_price").asDouble() : 0;
        if (!onTick(price, l.tickSize()) || !onTick(trigger, l.tickSize())) {
            return status(400, error(GrowwHttp.BAD_REQUEST, "Price should be a multiple of the tick size "
                    + l.tickSize()));
        }
        if (type.equals("SL") && !(trigger > 0)) {
            return status(400, error(GrowwHttp.BAD_REQUEST, "Trigger price is required for SL"));
        }
        Order o = new Order();
        o.id = "GMK" + ids.incrementAndGet();
        o.reference = reference;
        o.key = l.instrumentKey();
        o.symbol = l.tradingSymbol();
        o.exchange = exchange;
        o.side = b.get("transaction_type").asString();
        o.type = type;
        o.product = b.get("product").asString();
        o.quantity = quantity;
        o.price = price;
        o.trigger = trigger;
        o.lot = l.lotSize();
        o.placedAt = o.updatedAt = clock.get();
        o.entry = o.side.equals("BUY");
        o.ackHiddenReads = ackLagReads;
        if (type.equals("SL")) {
            o.status = "TRIGGER_PENDING";
        }
        orders.put(o.id, o);
        references.add(reference);
        double available = funds - usedMargin() + (o.side.equals("BUY") ? o.price * o.quantity : 0);  // before this one
        if (fault == Fault.REJECT_FUNDS || (o.side.equals("BUY") && o.price * o.quantity > available)) {
            o.status = "REJECTED";
            o.remark = "Insufficient balance. Required: " + Math.round(o.price * o.quantity) + ", available: "
                    + Math.round(available);
        } else {
            match(o);
        }
        ObjectNode answer = success();
        answer.putObject("payload").put("groww_order_id", o.id).put("order_status", o.ackHiddenReads > 0 ? "NEW"
                : o.status).put("order_reference_id", o.reference).put("remark", o.remark);
        return answer;
    }

    private static boolean onTick(double price, double tick) {
        double ticks = price / tick;
        return Math.abs(ticks - Math.rint(ticks)) < 1e-6;
    }

    private ObjectNode modify(JsonNode b) {
        Order o = orders.get(b.path("groww_order_id").asString(""));
        if (o == null) {
            return status(400, error(GrowwHttp.NOT_FOUND, "Order not found"));
        }
        for (String field : List.of("order_type", "segment", "quantity")) {     // Groww's modify needs the quantity
            if (!b.has(field)) {
                return status(400, error(GrowwHttp.BAD_REQUEST, "Missing field " + field));
            }
        }
        if (terminal(o)) {
            return status(400, error(GrowwHttp.CANNOT_PROCESS, "Order cannot be modified as it is " + o.status));
        }
        if (o.modifications >= GrowwGateway.MAX_MODIFICATIONS) {
            return status(400, error(GrowwHttp.CANNOT_PROCESS, "Maximum modifications reached for this order"));
        }
        Listing l = listings.get(o.key);
        if (b.has("quantity")) {
            long q = b.get("quantity").asLong();
            if (q <= 0 || q % l.lotSize() != 0 || q < o.filled) {
                return status(400, error(GrowwHttp.BAD_REQUEST, "Invalid quantity " + q));
            }
            o.quantity = q;
        }
        double price = b.has("price") ? b.get("price").asDouble() : o.price;
        double trigger = b.has("trigger_price") && !b.get("trigger_price").isNull() ? b.get("trigger_price").asDouble()
                : o.trigger;
        if (!onTick(price, l.tickSize()) || !onTick(trigger, l.tickSize())) {
            return status(400, error(GrowwHttp.BAD_REQUEST, "Price should be a multiple of the tick size "
                    + l.tickSize()));
        }
        o.price = price;
        if (o.type.equals("SL")) {
            o.trigger = trigger;
        }
        o.modifications++;
        o.updatedAt = clock.get();
        match(o);
        ObjectNode answer = success();
        answer.putObject("payload").put("groww_order_id", o.id).put("order_status", o.status);
        return answer;
    }

    private ObjectNode cancel(JsonNode b) {
        Order o = orders.get(b.path("groww_order_id").asString(""));
        if (o == null || !"FNO".equals(b.path("segment").asString(""))) {
            return status(400, error(GrowwHttp.NOT_FOUND, "Order not found"));
        }
        if (fillStopOnCancel && o.type.equals("SL") && !terminal(o)) {
            Book bk = books.get(o.key);
            fill(o, o.quantity - o.filled, bk == null || !(bk.bid() > 0) ? o.price
                    : Math.max(o.price, Math.min(bk.bid(), o.trigger)));
        }
        if (terminal(o)) {
            return status(400, error(GrowwHttp.CANNOT_PROCESS, "Order cannot be cancelled as it is " + o.status));
        }
        o.shownBeforeCancel = o.status;
        o.cancelHiddenReads = cancelLagReads;
        o.status = "CANCELLED";
        o.updatedAt = clock.get();
        ObjectNode answer = success();
        answer.putObject("payload").put("groww_order_id", o.id).put("order_status", "CANCELLATION_REQUESTED");
        return answer;
    }

    private static boolean terminal(Order o) {
        return GrowwGateway.terminal(o.status);
    }

    private void match(Order o) {
        if (terminal(o)) {
            return;
        }
        Book b = books.get(o.key);
        if (b == null) {
            return;
        }
        boolean buy = o.side.equals("BUY");
        if (o.status.equals("TRIGGER_PENDING")) {
            boolean triggered = buy ? b.last > 0 && b.last >= o.trigger : b.last > 0 && b.last <= o.trigger;
            if (!triggered) {
                return;
            }
            o.status = "OPEN";
        }
        double[] px = buy ? b.askPx : b.bidPx;
        long[] qty = buy ? b.askQty : b.bidQty;
        long open = o.quantity - o.filled;
        long want = o.entry && partialFraction < 1 && o.filled == 0
                ? Math.max(o.lot, (long) Math.floor(o.quantity * partialFraction / o.lot) * o.lot) : open;
        want = Math.min(open, want);
        for (int i = 0; i < px.length && want > 0; i++) {
            if (!(px[i] > 0) || (buy ? o.price < px[i] : o.price > px[i])) {
                break;
            }
            long quantity = Math.min(want, qty[i] / o.lot * o.lot);
            if (quantity > 0) {
                fill(o, quantity, px[i]);
                qty[i] -= quantity;
                want -= quantity;
            }
        }
    }

    private void fill(Order o, long quantity, double price) {
        o.average = (o.value() + price * quantity) / (o.filled + quantity);
        o.filled += quantity;
        o.updatedAt = clock.get();
        if (o.filled >= o.quantity) {
            o.status = "EXECUTED";
        } else if (o.status.equals("TRIGGER_PENDING")) {
            o.status = "OPEN";
        }
        trades.add(new Trade(Long.toString(ids.incrementAndGet()), o.id, o.key, o.symbol, o.exchange, o.side, quantity,
                price, o.updatedAt, o.product));
    }

    // ------------------------------------------------------------------ books

    /** One order as Groww's order list and detail show it (numbers as numbers, times "yyyy-MM-ddTHH:mm:ss" IST). */
    private ObjectNode row(Order o, String status) {
        boolean done = GrowwGateway.terminal(status);
        return json.createObjectNode().put("groww_order_id", o.id).put("trading_symbol", o.symbol)
                .put("order_status", status).put("remark", o.remark).put("quantity", o.quantity).put("price", money(o.price))
                .put("trigger_price", money(o.trigger)).put("filled_quantity", o.filled)
                .put("remaining_quantity", done ? 0 : o.quantity - o.filled).put("average_fill_price", money(o.average))
                .put("deliverable_quantity", 0).put("amo_status", "NA").put("validity", "DAY").put("exchange", o.exchange)
                .put("order_type", o.type).put("transaction_type", o.side).put("segment", "FNO").put("product", o.product)
                .put("created_at", TIME.format(local(o.placedAt))).put("exchange_time", TIME.format(local(o.updatedAt)))
                .put("trade_date", local(o.placedAt).toLocalDate().toString()).put("order_reference_id", o.reference);
    }

    private ObjectNode shown(Order o) {
        if (o.ackHiddenReads > 0) {                             // not acknowledged yet: nothing filled shown
            o.ackHiddenReads--;
            return row(o, "NEW").put("filled_quantity", 0).put("average_fill_price", 0.0)
                    .put("remaining_quantity", o.quantity);
        }
        String status = o.status;
        if (o.cancelHiddenReads > 0) {
            o.cancelHiddenReads--;
            status = o.shownBeforeCancel;
        }
        return row(o, status);
    }

    private ObjectNode orderList(Map<String, String> query) {
        int page = Integer.parseInt(query.getOrDefault("page", "0"));
        int size = Math.min(pageCap, Integer.parseInt(query.getOrDefault("page_size", "100")));
        List<ObjectNode> all;
        if (page == 0) {                                        // a read of the list starts at page 0
            List<ObjectNode> now = new ArrayList<>();
            for (Order o : orders.values()) {
                now.add(shown(o));
            }
            all = staleOnce ? previousBook : now;
            staleOnce = false;
            previousBook = now;
        } else {
            all = previousBook;                                 // later pages of the same read
        }
        ObjectNode answer = success();
        ArrayNode list = answer.putObject("payload").putArray("order_list");
        int from = Math.max(0, page * size - (pageOverlap && page > 0 ? 1 : 0));
        for (int i = from; i < Math.min(all.size(), from + size); i++) {
            list.add(all.get(i));
        }
        return answer;
    }

    /** Status by reference id; none is GA004 (HTTP 404). */
    private ObjectNode statusByReference(String reference) {
        for (Order o : orders.values()) {
            if (o.reference.equals(reference)) {
                ObjectNode r = shown(o);
                ObjectNode answer = success();
                answer.putObject("payload").put("groww_order_id", o.id).put("order_status", r.get("order_status")
                        .asString()).put("remark", o.remark).put("filled_quantity", r.get("filled_quantity").asLong())
                        .put("order_reference_id", o.reference);
                return answer;
            }
        }
        return status(404, error(GrowwHttp.NOT_FOUND, "Order not found"));
    }

    private ObjectNode detail(String id) {
        Order o = orders.get(id);
        if (o == null) {
            return status(404, error(GrowwHttp.NOT_FOUND, "Order not found"));
        }
        ObjectNode answer = success();
        answer.set("payload", shown(o));
        return answer;
    }

    /** One order's trades (paged); fills of holdings given with {@link #holding} belong to no order. */
    private ObjectNode tradeList(String orderId, Map<String, String> query) {
        Order o = orders.get(orderId);
        if (o == null) {
            return status(404, error(GrowwHttp.NOT_FOUND, "Order not found"));
        }
        int page = Integer.parseInt(query.getOrDefault("page", "0"));
        int size = Math.min(50, Integer.parseInt(query.getOrDefault("page_size", "50")));
        List<Trade> mine = trades.stream().filter(t -> t.orderId().equals(orderId)).toList();
        if (hideLastTradeReads > 0 && !mine.isEmpty()) {
            hideLastTradeReads--;
            mine = mine.subList(0, mine.size() - 1);
        }
        ObjectNode answer = success();
        ArrayNode list = answer.putObject("payload").putArray("trade_list");
        for (int i = page * size; i < Math.min(mine.size(), (page + 1) * size); i++) {
            Trade t = mine.get(i);
            list.add(json.createObjectNode().put("price", money(t.price())).put("isin", (String) null)
                    .put("quantity", t.quantity()).put("groww_order_id", t.orderId()).put("groww_trade_id", "GTR" + t.id())
                    .put("exchange_trade_id", "EX" + t.id()).put("exchange_order_id", "EXO" + t.orderId())
                    .put("trade_status", "EXECUTED").put("trading_symbol", t.symbol()).put("remark", (String) null)
                    .put("exchange", t.exchange()).put("segment", "FNO").put("product", t.product())
                    .put("transaction_type", t.side()).put("created_at", TIME.format(local(t.time())))
                    .put("trade_date_time", TIME.format(local(t.time()))).put("settlement_number", "1"));
        }
        return answer;
    }

    private ObjectNode positions() {
        Map<String, long[]> qty = new LinkedHashMap<>();
        Map<String, double[]> value = new LinkedHashMap<>();
        for (Trade t : trades) {
            String key = t.product() + "#" + t.key();
            long[] q = qty.computeIfAbsent(key, k -> new long[2]);
            double[] v = value.computeIfAbsent(key, k -> new double[2]);
            int i = t.side().equals("BUY") ? 0 : 1;
            q[i] += t.quantity();
            v[i] += t.quantity() * t.price();
        }
        ObjectNode answer = success();
        ArrayNode list = answer.putObject("payload").putArray("positions");
        for (Map.Entry<String, long[]> e : qty.entrySet()) {
            String product = e.getKey().substring(0, e.getKey().indexOf('#'));
            Listing l = listings.get(e.getKey().substring(e.getKey().indexOf('#') + 1));
            long[] q = e.getValue();
            double[] v = value.get(e.getKey());
            double buyPrice = q[0] > 0 ? v[0] / q[0] : 0, sellPrice = q[1] > 0 ? v[1] / q[1] : 0;
            list.add(json.createObjectNode().put("trading_symbol", l.tradingSymbol()).put("segment", "FNO")
                    .put("credit_quantity", q[0]).put("credit_price", money(buyPrice)).put("debit_quantity", q[1])
                    .put("debit_price", money(sellPrice)).put("carry_forward_credit_quantity", 0)
                    .put("carry_forward_credit_price", 0).put("carry_forward_debit_quantity", 0)
                    .put("carry_forward_debit_price", 0).put("exchange", l.exchange()).put("symbol_isin", (String) null)
                    .put("quantity", q[0] - q[1] + positionsSkew).put("product", product).put("net_carry_forward_quantity", 0)
                    .put("net_price", money(q[0] - q[1] != 0 ? (v[0] - v[1]) / (q[0] - q[1]) : 0))
                    .put("net_carry_forward_price", 0).put("realised_pnl", money(Math.min(q[0], q[1])
                            * (sellPrice - buyPrice))));
        }
        return answer;
    }

    /** Margins: payload.fno_margin_details.option_buy_balance_available (the field the Groww login reads). */
    private ObjectNode funds() {
        double available = funds - usedMargin();
        ObjectNode answer = success();
        ObjectNode payload = answer.putObject("payload");
        payload.put("clear_cash", money(funds)).put("net_margin_used", money(usedMargin()));
        payload.putObject("fno_margin_details").put("net_fno_margin_used", money(usedMargin()))
                .put("option_buy_balance_available", money(available)).put("option_sell_balance_available", 0.0)
                .put("future_balance_available", 0.0);
        payload.putObject("equity_margin_details").put("cnc_balance_available", money(available));
        return answer;
    }

    /**
     * The instrument CSV as Groww publishes it (its columns; expiry YYYY-MM-DD, strike and tick in rupees), with one
     * equity row ahead of the options (not ours: skipped by the parser).
     */
    public synchronized byte[] instrumentFile() {
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        csv.append("NSE,2885,RELIANCE,NSE-RELIANCE,\"Reliance Industries, Ltd\",EQ,CASH,EQ,INE002A01018,,,1,,,0.05,0,"
                + "false,true,true\n");
        for (Listing l : listings.values()) {
            csv.append(l.exchange()).append(',').append(l.exchangeToken()).append(',').append(l.tradingSymbol())
                    .append(',').append(l.exchange()).append('-').append(l.tradingSymbol()).append(',')
                    .append(l.underlying()).append(',').append(l.type()).append(",FNO,,,").append(l.underlying())
                    .append(",26000,").append(l.lotSize()).append(',')
                    .append(l.expiry() == null ? "" : l.expiry().toString()).append(',').append(l.strike()).append(',')
                    .append(l.tickSize()).append(',').append(1800).append(",false,true,true\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ plumbing

    private ObjectNode success() {
        return json.createObjectNode().put("status", "SUCCESS");
    }

    private ObjectNode error(String code, String message) {
        ObjectNode o = json.createObjectNode().put("status", "FAILURE");
        o.putObject("error").put("code", code.isEmpty() ? null : code).put("message", message).putNull("metadata");
        return o;
    }

    private static ObjectNode status(int code, ObjectNode body) {
        return body.put("_status", code);
    }

    private static double money(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static LocalDateTime local(Instant t) {
        return LocalDateTime.ofInstant(t == null ? Instant.EPOCH : t, IST);
    }

    private void respond(HttpExchange ex, int code, ObjectNode body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
