package com.autotrade.upstox.orders;

import java.io.ByteArrayOutputStream;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import com.autotrade.broker.Quote;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A local Upstox API server (live adapters plan step B2; 127.0.0.1 only) standing for all three of Upstox's hosts: the
 * v3 order calls (place / modify / cancel, JSON bodies with Upstox's field names and types), the v2 reads (order book,
 * order history by tag or order id, trades, short-term positions), v3 funds and the day's instrument files
 * ({@code /market-quote/instruments/exchange/NSE.json.gz}, gzipped JSON in Upstox's units: tick in paise, expiry in epoch
 * milliseconds). Headers {@code Authorization: Bearer}, {@code Accept} and, on a body, {@code Content-Type} are required;
 * answers are Upstox's envelope ({@code status} success / error with {@code errors[].errorCode}); statuses are Upstox's
 * lower-case ones. DRY_RUN accounts trade against it, so no order can reach Upstox, and the fault suite drives every
 * failure through it.
 * <p>
 * <b>Fills</b> (as the Kite and Kotak simulators): a limit buy fills against the asks up to its price, level by level, a
 * sell against the bids; the rest rests open. An SL order waits ({@code trigger pending}) until the last price reaches
 * its trigger, then works as a limit. Quantities fill in whole lots. MARKET and SL-M are refused (market protection).
 * <p>
 * <b>Faults</b> ({@link #failNext}): Kite's menu (429, a 500 not processed, a 500 or a timeout after the order <i>was</i>
 * processed, a timeout before processing, an expired token (HTTP 401 without a code), an input error, a funds rejection),
 * plus Upstox's {@code STATIC_IP} (UDAPI1154, HTTP 403), {@code TOKEN_INVALID} (UDAPI100050, HTTP 401) and
 * {@code FUNDS_DOWN} (UDAPI100072, HTTP 423); {@link #partialFills}, {@link #fillStopOnCancel}, {@link #cancelLag},
 * {@link #staleOrderBookOnce}, {@link #ackLag}, {@link #expireSession}. Error codes Upstox's docs do not name (a lot or
 * tick refusal, a price-band refusal) are the simulator's own and marked so.
 */
public final class UpstoxSimulator implements AutoCloseable {

    public enum Fault {
        NONE, HTTP_429, HTTP_500, HTTP_500_PROCESSED, TIMEOUT, TIMEOUT_PROCESSED, TOKEN_EXPIRED, INPUT_ERROR,
        REJECT_FUNDS, STATIC_IP, TOKEN_INVALID, FUNDS_DOWN
    }

    /** One contract the simulator lists (as its instrument file row). */
    public record Listing(String instrumentKey, String tradingSymbol, String underlying, LocalDate expiry, double strike,
                          String type, int lotSize, double tickSize) {

        public String segment() {
            return instrumentKey.substring(0, instrumentKey.indexOf('|'));
        }

        public UpstoxInstruments.Contract contract() {
            return new UpstoxInstruments.Contract(instrumentKey, tradingSymbol, underlying, expiry, strike, type, lotSize,
                    tickSize, 0);
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
        String id, tag, key, symbol, side, type, product, status = "open", message;
        long quantity, filled;
        double price, trigger, average;
        Instant placedAt, updatedAt;
        int lot;
        boolean entry;
        String shownBeforeCancel;
        int cancelHiddenReads, ackHiddenReads;
        final List<ObjectNode> history = new ArrayList<>();

        double value() {
            return average * filled;
        }
    }

    private record Trade(String id, String orderId, String key, String symbol, String side, long quantity, double price,
                         Instant time, String product) {
    }

    // Upstox's own literals (docs, SDK 1.29), written out here rather than taken from the gateway (review 8 Nov 2026): a
    // wrong constant in the gateway must fail against the simulator, not be mirrored by it
    private static final String PLACE = "/v3/order/place";
    private static final String MODIFY = "/v3/order/modify";
    private static final String CANCEL = "/v3/order/cancel";
    private static final String ORDERS = "/v2/order/retrieve-all";
    private static final String HISTORY = "/v2/order/history";
    private static final String TRADES = "/v2/order/trades/get-trades-for-day";
    private static final String POSITIONS = "/v2/portfolio/short-term-positions";
    private static final String FUNDS_V3 = "/v3/user/get-funds-and-margin";
    private static final String FUNDS_V2 = "/v2/user/get-funds-and-margin";
    private static final String INSTRUMENTS = "/market-quote/instruments/exchange/";
    private static final String NOT_FOUND = "UDAPI100010";
    private static final String RATE_LIMIT = "UDAPI10005";
    private static final String TOKEN_INVALID = "UDAPI100050";
    private static final String STATIC_IP_CODE = "UDAPI1154";
    private static final String FUNDS_DOWN_CODE = "UDAPI100072";
    private static final String INVALID_INSTRUMENT = "UDAPI100011";
    private static final String FINISHED_CANCEL = "UDAPI100040";
    private static final String FINISHED_MODIFY = "UDAPI100041";
    /** Upstox's limit on {@code tag}. */
    private static final int TAG_MAX = 40;
    /** The instrument file's tick is in paise. */
    private static final double PAISE = 100;
    private static final java.util.Set<String> FINISHED = java.util.Set.of("complete", "rejected", "cancelled",
            "cancelled after market order");

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SIM_QUANTITY = "UDAPI1023";     // the simulator's own code (Upstox's not documented)
    private static final String SIM_TICK = "UDAPI1024";         // the simulator's own code
    private static final String SIM_FIELD = "UDAPI1000";        // the simulator's own code for a field error
    private static final String SIM_CIRCUIT = "UDAPI1052";      // the simulator's own code for a price-band refusal

    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Listing> listings = new LinkedHashMap<>();
    private final Map<String, Book> books = new HashMap<>();
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final List<Trade> trades = new ArrayList<>();
    private final Map<String, Deque<Fault>> faults = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000);   // unique across instances
    private final long timeoutMillis;
    private volatile Supplier<Instant> clock = Instant::now;
    private Session session = new Session("sim-token-" + UUID.randomUUID());
    private boolean strict;
    private boolean expired;
    private double funds = 1e9;
    private FundsV3 fundsV3 = FundsV3.OK;
    private Instant filesModified;
    private double partialFraction = 1.0;
    private boolean fillStopOnCancel;
    private int cancelLagReads;
    private int ackLagReads;
    private boolean staleOnce;
    private List<ObjectNode> previousBook = List.of();
    private int requests;
    private int tokensSentToAssets;
    private final Map<String, Integer> requestsByOperation = new HashMap<>();

    /** Listens on 127.0.0.1 ({@code port} 0 = any free port); {@code timeoutMillis}: how long a TIMEOUT fault stalls. */
    public UpstoxSimulator(int port, long timeoutMillis) throws IOException {
        this.timeoutMillis = timeoutMillis;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        executor = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("upstox-sim-", 0).factory());
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
        executor.shutdownNow();                 // its threads end with it (review 8 Nov 2026: they were left running)
    }

    /** True once {@link #close} has shut its handler threads down (tests). */
    public boolean closed() {
        return executor.isShutdown();
    }

    // ------------------------------------------------------------------ set-up and faults

    public synchronized UpstoxSimulator list(Listing listing) {
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
     * The book as a quote (in process, not over HTTP: Upstox's quote API is out of scope), with what our fills left at
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

    /** A holding the broker already has (a restart): a filled buy without an order, product I. */
    public synchronized void holding(String instrumentKey, long quantity, double price) {
        holding(instrumentKey, quantity, price, UpstoxConfig.INTRADAY);
    }

    /** A holding of another product (D: not ours, left out of the adapter's positions). */
    public synchronized void holding(String instrumentKey, long quantity, double price, String product) {
        Listing l = listing(instrumentKey);
        trades.add(new Trade(Long.toString(ids.incrementAndGet()), "0", l.instrumentKey(), l.tradingSymbol(), "BUY",
                quantity, price, clock.get(), product));
    }

    /** Operations: place, modify, cancel, orders, history, trades, positions, funds, funds-v2, instruments. */
    public synchronized void failNext(String operation, Fault fault) {
        faults.computeIfAbsent(operation, k -> new ArrayDeque<>()).add(fault);
    }

    /** How v3 funds answers: as the SDK's model (default), 404 (not offered), or a shape the gateway does not know. */
    public enum FundsV3 { OK, MISSING, OTHER_SHAPE }

    public synchronized void fundsV3(FundsV3 mode) {
        this.fundsV3 = mode;
    }

    /** The instrument files' {@code Last-Modified} (default: none sent). */
    public synchronized void instrumentFilesModified(Instant at) {
        this.filesModified = at;
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

    /** A cancelled order still shows its old status in the next {@code reads} order books. */
    public synchronized void cancelLag(int reads) {
        this.cancelLagReads = reads;
    }

    /** A new order shows "put order req received" in the next {@code reads} order books before its real status. */
    public synchronized void ackLag(int reads) {
        this.ackLagReads = reads;
    }

    public synchronized void staleOrderBookOnce() {
        this.staleOnce = true;
    }

    /** The token ends (Upstox's answer: HTTP 401, UDAPI100050) until the next {@link #login}. */
    public synchronized void expireSession() {
        this.expired = true;
    }

    /** Only the token issued by {@link #login} is accepted (default: any non-blank bearer token, for DRY_RUN). */
    public synchronized void strictSessions() {
        this.strict = true;
    }

    /** The day's login (Upstox's OAuth): a new token; the session is valid again. */
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

    /** Requests of one operation: place, modify, cancel, orders, history, trades, positions, funds, instruments. */
    public synchronized int requests(String operation) {
        return requestsByOperation.getOrDefault(operation, 0);
    }

    /** Instrument file requests that carried an Authorization header (the files are public: must be zero). */
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
            if (op.equals("instruments")) {                             // public files: no token
                if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                    synchronized (this) {
                        tokensSentToAssets++;
                    }
                }
                if (fault == Fault.HTTP_500) {
                    respond(ex, 500, error("UDAPI100500", "Something went wrong"));
                    return;
                }
                String exchange = path.substring(path.lastIndexOf('/') + 1).replace(".json.gz", "");
                byte[] bytes = instrumentFile(exchange);
                if (bytes == null) {
                    respond(ex, 404, error(NOT_FOUND, "file not found"));
                    return;
                }
                ex.getResponseHeaders().set("Content-Type", "application/gzip");
                Instant modified;
                synchronized (this) {
                    modified = filesModified;
                }
                if (modified != null) {
                    ex.getResponseHeaders().set("Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                            .format(modified.atZone(java.time.ZoneOffset.UTC)));
                }
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bytes);
                }
                return;
            }
            if (fault == Fault.TIMEOUT) {
                Thread.sleep(timeoutMillis);
                respond(ex, 504, error("UDAPI100500", "Gateway timed out"));
                return;
            }
            if (fault == Fault.HTTP_429) {
                respond(ex, 429, error(RATE_LIMIT, "Too Many Requests"));
                return;
            }
            if (fault == Fault.HTTP_500) {
                respond(ex, 500, error("UDAPI100500", "Something went wrong, please try again"));
                return;
            }
            if (fault == Fault.TOKEN_EXPIRED) {
                respond(ex, 401, error("", "Unauthorized"));
                return;
            }
            if (fault == Fault.TOKEN_INVALID) {
                respond(ex, 401, error(TOKEN_INVALID, "Invalid token used to access API"));
                return;
            }
            if (fault == Fault.STATIC_IP) {
                respond(ex, 403, error(STATIC_IP_CODE, "Requests from this IP address are not allowed: register "
                        + "your static IP"));
                return;
            }
            if (fault == Fault.FUNDS_DOWN) {
                respond(ex, 423, error(FUNDS_DOWN_CODE, "Funds service is not available between 00:00 and 05:30"));
                return;
            }
            if (fault == Fault.INPUT_ERROR) {
                respond(ex, 400, error(SIM_CIRCUIT, "Price is outside the circuit limits"));
                return;
            }
            String refusal = authorised(ex);
            if (refusal != null) {
                respond(ex, 401, error(TOKEN_INVALID, refusal));
                return;
            }
            String accept = ex.getRequestHeaders().getFirst("Accept");
            if (accept == null || !accept.contains("application/json")) {
                respond(ex, 400, error(SIM_FIELD, "Accept: application/json is required"));
                return;
            }
            JsonNode jsonBody = null;
            if (method.equals("POST") || method.equals("PUT")) {
                String type = ex.getRequestHeaders().getFirst("Content-Type");
                if (type == null || !type.startsWith("application/json")) {
                    respond(ex, 400, error(SIM_FIELD, "Content-Type: application/json is required"));
                    return;
                }
                try {
                    jsonBody = json.readTree(body);
                } catch (RuntimeException e) {
                    respond(ex, 400, error(SIM_FIELD, "body is not JSON"));
                    return;
                }
            }
            ObjectNode answer = route(method, path, query, jsonBody, fault);
            if (fault == Fault.HTTP_500_PROCESSED) {
                respond(ex, 500, error("UDAPI100500", "Something went wrong, please try again"));
                return;
            }
            if (fault == Fault.TIMEOUT_PROCESSED) {
                Thread.sleep(timeoutMillis);
            }
            respond(ex, answer.has("_status") ? answer.remove("_status").asInt() : 200, answer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            respond(ex, 400, error(SIM_FIELD, String.valueOf(e.getMessage())));
        }
    }

    private static String operation(String path) {
        return switch (path) {
            case PLACE -> "place";
            case MODIFY -> "modify";
            case CANCEL -> "cancel";
            case ORDERS -> "orders";
            case HISTORY -> "history";
            case TRADES -> "trades";
            case POSITIONS -> "positions";
            case FUNDS_V3 -> "funds";
            case FUNDS_V2 -> "funds-v2";
            default -> path.startsWith(INSTRUMENTS) ? "instruments" : path;
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
            return "Invalid token used to access API";
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ") || auth.substring(7).isBlank()) {
            return "Authorization: Bearer <token> is required";
        }
        return !strict || auth.substring(7).equals(session.accessToken()) ? null : "Invalid token used to access API";
    }

    private synchronized ObjectNode route(String method, String path, Map<String, String> query, JsonNode body,
                                          Fault fault) {
        String op = operation(path);
        String expected = switch (op) {
            case "place" -> "POST";
            case "modify" -> "PUT";
            case "cancel" -> "DELETE";
            case "orders", "history", "trades", "positions", "funds", "funds-v2" -> "GET";
            default -> null;
        };
        if (expected == null) {
            return status(404, error("UDAPI100060", "Resource not Found"));
        }
        if (!expected.equals(method)) {
            return status(405, error(SIM_FIELD, "Method not allowed"));
        }
        return switch (op) {
            case "place" -> place(body, fault);
            case "modify" -> modify(body);
            case "cancel" -> cancel(query.get("order_id"));
            case "orders" -> success(orderBook());
            case "history" -> history(query.get("tag"), query.get("order_id"));
            case "trades" -> success(tradeBook());
            case "positions" -> success(positions());
            case "funds-v2" -> fundsV2();
            default -> funds();
        };
    }

    // ------------------------------------------------------------------ orders

    private ObjectNode place(JsonNode b, Fault fault) {
        for (String field : List.of("quantity", "product", "validity", "price", "tag", "instrument_token", "order_type",
                "transaction_type", "disclosed_quantity", "trigger_price", "is_amo", "slice")) {
            if (!b.has(field)) {
                return status(400, error(SIM_FIELD, "Missing field " + field, field));
            }
        }
        if (!b.get("quantity").isIntegralNumber() || !b.get("price").isNumber() || !b.get("trigger_price").isNumber()
                || !b.get("disclosed_quantity").isIntegralNumber() || !b.get("is_amo").isBoolean()
                || !b.get("slice").isBoolean() || !b.get("tag").isString()) {
            return status(400, error(SIM_FIELD, "Invalid field type"));
        }
        String type = b.get("order_type").asString();
        if (type.equals("MARKET") || type.equals("SL-M")) {
            return status(400, error("UDAPI1158", "Market protection is required for " + type + " orders"));
        }
        if (!(type.equals("LIMIT") || type.equals("SL"))) {
            return status(400, error(SIM_FIELD, "Invalid order_type " + type, "order_type"));
        }
        String tag = b.get("tag").asString();
        if (tag.length() > TAG_MAX) {
            return status(400, error(SIM_FIELD, "tag can be at most 40 characters", "tag"));
        }
        Listing l = listings.get(b.get("instrument_token").asString());
        if (l == null) {
            return status(400, error(INVALID_INSTRUMENT, "Invalid instrument key"));
        }
        if (b.get("is_amo").asBoolean() || b.get("slice").asBoolean() || !b.get("validity").asString().equals("DAY")) {
            return status(400, error(SIM_FIELD, "Only DAY orders without AMO or slicing are simulated"));
        }
        long quantity = b.get("quantity").asLong();
        if (quantity <= 0 || quantity % l.lotSize() != 0) {
            return status(400, error(SIM_QUANTITY, "Quantity should be a multiple of the lot size " + l.lotSize()));
        }
        double price = b.get("price").asDouble(), trigger = b.get("trigger_price").asDouble();
        if (!onTick(price, l.tickSize()) || !onTick(trigger, l.tickSize())) {
            return status(400, error(SIM_TICK, "Price should be a multiple of the tick size " + l.tickSize()));
        }
        if (type.equals("SL") && !(trigger > 0)) {
            return status(400, error(SIM_FIELD, "Trigger price is required for SL", "trigger_price"));
        }
        Order o = new Order();
        o.id = Long.toString(ids.incrementAndGet());
        o.tag = tag;
        o.key = l.instrumentKey();
        o.symbol = l.tradingSymbol();
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
            o.status = "trigger pending";
        }
        orders.put(o.id, o);
        double available = funds - usedMargin() + (o.side.equals("BUY") ? o.price * o.quantity : 0);  // before this one
        if (fault == Fault.REJECT_FUNDS || (o.side.equals("BUY") && o.price * o.quantity > available)) {
            o.status = "rejected";
            o.message = "Insufficient funds. Required margin: " + Math.round(o.price * o.quantity) + ", available: "
                    + Math.round(available);
            o.history.add(row(o, o.status));
        } else {
            o.history.add(row(o, o.status));
            match(o);
        }
        ObjectNode answer = success(null);
        ObjectNode data = answer.putObject("data");
        data.putArray("order_ids").add(o.id);
        answer.putObject("metadata").put("latency", 30);
        return answer;
    }

    private static boolean onTick(double price, double tick) {
        double ticks = price / tick;
        return Math.abs(ticks - Math.rint(ticks)) < 1e-6;
    }

    private ObjectNode modify(JsonNode b) {
        Order o = orders.get(b.path("order_id").asString(""));
        if (o == null) {
            return status(400, error(NOT_FOUND, "Order not found"));
        }
        for (String field : List.of("validity", "price", "order_type", "trigger_price")) {
            if (!b.has(field)) {
                return status(400, error(SIM_FIELD, "Missing field " + field, field));
            }
        }
        if (terminal(o)) {
            return status(400, error(FINISHED_MODIFY, "Order cannot be modified as it is " + o.status));
        }
        Listing l = listings.get(o.key);
        if (b.has("quantity")) {
            long q = b.get("quantity").asLong();
            if (q <= 0 || q % l.lotSize() != 0 || q < o.filled) {
                return status(400, error(SIM_QUANTITY, "Invalid quantity " + q));
            }
            o.quantity = q;
        }
        double price = b.get("price").asDouble(), trigger = b.get("trigger_price").asDouble();
        if (!onTick(price, l.tickSize()) || !onTick(trigger, l.tickSize())) {
            return status(400, error(SIM_TICK, "Price should be a multiple of the tick size " + l.tickSize()));
        }
        o.price = price;
        if (o.type.equals("SL")) {
            o.trigger = trigger;
        }
        o.updatedAt = clock.get();
        if (o.status.equals("open")) {
            o.status = "modified";
        }
        o.history.add(row(o, o.status));
        match(o);
        ObjectNode answer = success(null);
        answer.putObject("data").put("order_id", o.id);
        answer.putObject("metadata").put("latency", 25);
        return answer;
    }

    private ObjectNode cancel(String id) {
        Order o = id == null ? null : orders.get(id);
        if (o == null) {
            return status(400, error(NOT_FOUND, "Order not found"));
        }
        if (fillStopOnCancel && o.type.equals("SL") && !terminal(o)) {
            Book b = books.get(o.key);
            fill(o, o.quantity - o.filled, b == null || !(b.bid() > 0) ? o.price
                    : Math.max(o.price, Math.min(b.bid(), o.trigger)));
        }
        if (terminal(o)) {
            return status(400, error(FINISHED_CANCEL, "Order cannot be cancelled as it is " + o.status));
        }
        o.shownBeforeCancel = o.status;
        o.cancelHiddenReads = cancelLagReads;
        o.status = "cancelled";
        o.updatedAt = clock.get();
        o.history.add(row(o, o.status));
        ObjectNode answer = success(null);
        answer.putObject("data").put("order_id", o.id);
        answer.putObject("metadata").put("latency", 20);
        return answer;
    }

    private static boolean terminal(Order o) {
        return finished(o.status);
    }

    private static boolean finished(String status) {
        return status != null && FINISHED.contains(status);
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
        if (o.status.equals("trigger pending")) {
            boolean triggered = buy ? b.last > 0 && b.last >= o.trigger : b.last > 0 && b.last <= o.trigger;
            if (!triggered) {
                return;
            }
            o.status = "open";
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
            o.status = "complete";
        } else if (o.status.equals("trigger pending")) {
            o.status = "open";
        }
        o.history.add(row(o, o.status));
        trades.add(new Trade(Long.toString(ids.incrementAndGet()), o.id, o.key, o.symbol, o.side, quantity, price,
                o.updatedAt, o.product));
    }

    // ------------------------------------------------------------------ books

    /** One order as Upstox's order book and history show it (numbers as numbers, timestamps "yyyy-MM-dd HH:mm:ss"). */
    private ObjectNode row(Order o, String status) {
        boolean done = finished(status);
        return json.createObjectNode().put("exchange", o.key.startsWith("BSE") ? "BFO" : "NFO").put("product", o.product)
                .put("price", money(o.price)).put("quantity", o.quantity).put("status", status)
                .put("tag", o.tag == null || o.tag.isEmpty() ? null : o.tag).put("instrument_token", o.key)
                .put("placed_by", "SIM").put("trading_symbol", o.symbol).put("tradingsymbol", o.symbol)
                .put("order_type", o.type).put("validity", "DAY").put("trigger_price", money(o.trigger))
                .put("disclosed_quantity", 0).put("transaction_type", o.side).put("average_price", money(o.average))
                .put("filled_quantity", o.filled).put("pending_quantity", done ? 0 : o.quantity - o.filled)
                .put("status_message", o.message).put("exchange_order_id", "EX" + o.id).put("parent_order_id", (String) null)
                .put("order_id", o.id).put("variety", "SIMPLE").put("order_timestamp", TIME.format(local(o.placedAt)))
                .put("exchange_timestamp", TIME.format(local(o.updatedAt))).put("is_amo", false)
                .put("order_request_id", "1");
    }

    private List<ObjectNode> orderBook() {
        List<ObjectNode> now = new ArrayList<>();
        for (Order o : orders.values()) {
            String shown = o.status;
            if (o.cancelHiddenReads > 0) {
                o.cancelHiddenReads--;
                shown = o.shownBeforeCancel;
            }
            if (o.ackHiddenReads > 0) {                             // not acknowledged yet: nothing filled shown
                o.ackHiddenReads--;
                now.add(row(o, "put order req received").put("filled_quantity", 0).put("average_price", 0.0)
                        .put("pending_quantity", o.quantity));
                continue;
            }
            now.add(row(o, shown));
        }
        List<ObjectNode> shown = staleOnce ? previousBook : now;
        staleOnce = false;
        previousBook = now;
        return shown;
    }

    /** Order history: every recorded state of the orders with this tag (or order id); none is UDAPI100010. */
    private ObjectNode history(String tag, String orderId) {
        List<ObjectNode> rows = new ArrayList<>();
        for (Order o : orders.values()) {
            if ((tag != null && tag.equals(o.tag)) || (orderId != null && orderId.equals(o.id))) {
                rows.addAll(o.history);
            }
        }
        if (rows.isEmpty()) {
            return status(400, error(NOT_FOUND, "No order found"));
        }
        return success(rows);
    }

    /** The trade book; fills of holdings given with {@link #holding} are left out. */
    private List<ObjectNode> tradeBook() {
        List<ObjectNode> list = new ArrayList<>();
        for (Trade t : trades) {
            if (t.orderId().equals("0")) {
                continue;
            }
            list.add(json.createObjectNode().put("exchange", t.key().startsWith("BSE") ? "BFO" : "NFO")
                    .put("product", t.product()).put("trading_symbol", t.symbol()).put("tradingsymbol", t.symbol())
                    .put("instrument_token", t.key()).put("order_type", "LIMIT").put("transaction_type", t.side())
                    .put("quantity", t.quantity()).put("exchange_order_id", "EX" + t.orderId()).put("order_id", t.orderId())
                    .put("exchange_timestamp", TIME.format(local(t.time()))).put("average_price", money(t.price()))
                    .put("trade_id", t.id()).put("order_ref_id", (String) null)
                    .put("order_timestamp", TIME.format(local(t.time()))));
        }
        return list;
    }

    private List<ObjectNode> positions() {
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
        List<ObjectNode> list = new ArrayList<>();
        for (Map.Entry<String, long[]> e : qty.entrySet()) {
            String product = e.getKey().substring(0, e.getKey().indexOf('#'));
            Listing l = listings.get(e.getKey().substring(e.getKey().indexOf('#') + 1));
            long[] q = e.getValue();
            double[] v = value.get(e.getKey());
            list.add(json.createObjectNode().put("exchange", l.segment().startsWith("BSE") ? "BFO" : "NFO")
                    .put("multiplier", 1.0).put("value", money(v[1] - v[0])).put("pnl", 0.0).put("product", product)
                    .put("instrument_token", l.instrumentKey()).put("average_price", money(q[0] > 0 ? v[0] / q[0] : 0))
                    .put("buy_value", money(v[0])).put("overnight_quantity", 0).put("day_buy_value", money(v[0]))
                    .put("day_buy_price", money(q[0] > 0 ? v[0] / q[0] : 0)).put("overnight_buy_amount", 0.0)
                    .put("overnight_buy_quantity", 0).put("day_buy_quantity", q[0]).put("day_sell_value", money(v[1]))
                    .put("day_sell_price", money(q[1] > 0 ? v[1] / q[1] : 0)).put("overnight_sell_amount", 0.0)
                    .put("overnight_sell_quantity", 0).put("day_sell_quantity", q[1]).put("quantity", q[0] - q[1])
                    .put("last_price", 0.0).put("unrealised", 0.0).put("realised", 0.0).put("sell_value", money(v[1]))
                    .put("trading_symbol", l.tradingSymbol()).put("tradingsymbol", l.tradingSymbol())
                    .put("close_price", 0.0).put("buy_price", money(q[0] > 0 ? v[0] / q[0] : 0))
                    .put("sell_price", money(q[1] > 0 ? v[1] / q[1] : 0)));
        }
        return list;
    }

    /** v3 funds (the SDK's GetUserFundMarginV3Response): available_to_trade.total, unavailable_to_trade.total. */
    private ObjectNode funds() {
        if (fundsV3 == FundsV3.MISSING) {
            return status(404, error("UDAPI100060", "Resource not Found"));
        }
        if (fundsV3 == FundsV3.OTHER_SHAPE) {
            ObjectNode answer = success(null);
            answer.putObject("data").putObject("margins").put("cash", money(funds - usedMargin()));
            return answer;
        }
        double available = funds - usedMargin();
        ObjectNode answer = success(null);
        ObjectNode data = answer.putObject("data");
        ObjectNode avail = data.putObject("available_to_trade").put("total", money(available));
        avail.putObject("cash_available_to_trade").put("total", money(available));
        data.putObject("unavailable_to_trade").put("total", money(usedMargin()));
        return answer;
    }

    /** v2 funds (the login's call): data.equity.available_margin, data.commodity.available_margin. */
    private ObjectNode fundsV2() {
        double available = funds - usedMargin();
        ObjectNode answer = success(null);
        ObjectNode data = answer.putObject("data");
        data.putObject("equity").put("used_margin", money(usedMargin())).put("payin_amount", 0.0)
                .put("span_margin", 0.0).put("adhoc_margin", 0.0).put("notional_cash", 0.0)
                .put("available_margin", money(available)).put("exposure_margin", 0.0);
        data.putObject("commodity").put("used_margin", 0.0).put("available_margin", 0.0);
        return answer;
    }

    /**
     * One exchange's instrument file (NSE: NSE_FO listings, BSE: BSE_FO) as Upstox publishes it: a gzipped JSON array, tick
     * in paise, expiry the last millisecond of the day IST, strike and lot plain; null for another exchange.
     */
    public synchronized byte[] instrumentFile(String exchange) {
        String segment = switch (exchange) {
            case "NSE" -> "NSE_FO";
            case "BSE" -> "BSE_FO";
            default -> null;
        };
        if (segment == null) {
            return null;
        }
        ArrayNode rows = json.createArrayNode();
        for (Listing l : listings.values()) {
            if (!l.segment().equals(segment)) {
                continue;
            }
            long expiry = l.expiry() == null ? 0
                    : l.expiry().plusDays(1).atStartOfDay(IST).toInstant().toEpochMilli() - 1;
            rows.add(json.createObjectNode().put("weekly", true).put("segment", segment).put("name", l.underlying())
                    .put("exchange", exchange.equals("NSE") ? "NSE" : "BSE").put("expiry", expiry)
                    .put("instrument_type", l.type()).put("asset_symbol", l.underlying())
                    .put("underlying_symbol", l.underlying()).put("instrument_key", l.instrumentKey())
                    .put("lot_size", l.lotSize()).put("freeze_quantity", 1800.0).put("exchange_token",
                            l.instrumentKey().substring(l.instrumentKey().indexOf('|') + 1))
                    .put("minimum_lot", l.lotSize()).put("asset_key", "NSE_INDEX|Nifty 50").put("underlying_key",
                            "NSE_INDEX|Nifty 50").put("tick_size", Math.round(l.tickSize() * PAISE
                            * 100) / 100.0).put("asset_type", "INDEX").put("underlying_type", "INDEX")
                    .put("trading_symbol", l.tradingSymbol()).put("strike_price", l.strike()).put("qty_multiplier", 1.0));
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
                gz.write(json.writeValueAsBytes(rows));
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private ObjectNode success(List<ObjectNode> rows) {
        ObjectNode o = json.createObjectNode().put("status", "success");
        if (rows != null) {
            ArrayNode data = o.putArray("data");
            rows.forEach(data::add);
        }
        return o;
    }

    private ObjectNode error(String code, String message) {
        return error(code, message, null);
    }

    private ObjectNode error(String code, String message, String property) {
        ObjectNode o = json.createObjectNode().put("status", "error");
        ObjectNode e = o.putArray("errors").addObject();
        e.put("errorCode", code.isEmpty() ? null : code).put("message", message).put("propertyPath", property)
                .put("invalidValue", (String) null).put("error_code", code.isEmpty() ? null : code)
                .put("property_path", property).put("invalid_value", (String) null);
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
