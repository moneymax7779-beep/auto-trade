package com.autotrade.kotak;

import java.io.IOException;
import java.io.OutputStream;
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
import java.util.Locale;
import java.util.Map;
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
 * A local Kotak Neo Trade API server (live adapters plan step B1; 127.0.0.1 only): the endpoints {@link KotakGateway}
 * and {@link KotakInstruments} call, as the sources describe them (headers {@code Sid}, {@code Auth},
 * {@code neo-fin-key}; {@code jData} form bodies whose values must all be strings; the {@code stat} / {@code stCode}
 * envelope; lower-case order statuses; a trade book without tokens; positions as strings; the scrip master's file paths
 * and its nse_fo / bse_fo CSVs with Kotak's stray header characters and encodings). DRY_RUN accounts trade against it, so
 * no order can reach Kotak, and the fault suite drives every failure through it.
 * <p>
 * <b>Fills</b> (as the Kite simulator): a limit buy fills against the asks up to its price, level by level, a sell against
 * the bids; the rest rests open. An SL order waits ({@code trigger pending}) until the last price reaches its trigger,
 * then works as a limit. Quantities fill in whole lots. Market and SL-M orders are refused.
 * <p>
 * <b>Faults</b> ({@link #failNext}): Kite's menu (429, a 500 not processed, a 500 or a timeout after the order <i>was</i>
 * processed, a timeout before processing, an expired token (HTTP 401), an input error, a funds rejection), plus Kotak's
 * session failures: {@code WRONG_IP} (stCode 1037), {@code IP_NOT_WHITELISTED} (100008), {@code SESSION_INVALID}
 * (1003), and {@code ERROR_1005} (Kotak's internal error, not processed); {@link #partialFills}, {@link #fillStopOnCancel},
 * {@link #cancelLag}, {@link #staleOrderBookOnce}, {@link #expireSession}, {@link #refuseDuplicateTags}.
 * <p>
 * A repeated {@code ig} is <b>accepted</b> by default (a second order with the same tag): whether Kotak refuses one, and
 * with which words, is UNCONFIRMED, so the gateway's idempotency is proven by its book lookup, not by this refusal.
 */
public final class KotakSimulator implements AutoCloseable {

    public enum Fault {
        NONE, HTTP_429, HTTP_500, HTTP_500_PROCESSED, TIMEOUT, TIMEOUT_PROCESSED, TOKEN_EXPIRED, INPUT_ERROR,
        REJECT_FUNDS, WRONG_IP, IP_NOT_WHITELISTED, SESSION_INVALID, ERROR_1005,
        /** HTTP 200, Not_Ok with a message other than "No Data" (a read that failed: never an empty book). */
        NOT_OK_OTHER
    }

    /** One contract the simulator lists (as its scrip master row): segment {@code nse_fo} or {@code bse_fo}. */
    public record Listing(long token, String segment, String tradingSymbol, String name, LocalDate expiry, double strike,
                          String type, int lotSize, double tickSize) {

        public String instrumentKey() {
            return KotakInstruments.instrumentKey(segment, token);
        }

        public KotakInstruments.Contract contract() {
            return new KotakInstruments.Contract(segment, token, tradingSymbol, name, expiry, strike, type, lotSize,
                    tickSize);
        }
    }

    /** A day's session as the login would give it. */
    public record Session(String token, String sid) {
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
        String id, tag, segment, symbol, side, type, product, status = "open", message;
        long quantity, filled;
        double price, trigger, average;
        Instant placedAt, updatedAt;
        int lot;
        boolean entry;
        String shownBeforeCancel;
        int cancelHiddenReads;

        double value() {
            return average * filled;
        }
    }

    private record Trade(String id, String orderId, String tag, String segment, String symbol, String side, long quantity,
                         double price, Instant time, String product) {
    }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH);
    private static final DateTimeFormatter UPDATE_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final DateTimeFormatter FILL_DAY = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter FILL_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final HttpServer server;
    private final ExecutorService executor;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String consumerKey;
    private final Map<String, Listing> listings = new LinkedHashMap<>();     // segment:symbol
    private final Map<String, Book> books = new HashMap<>();
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final List<Trade> trades = new ArrayList<>();
    private final Map<String, Deque<Fault>> faults = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000);   // unique across instances
    private final long timeoutMillis;
    private volatile Supplier<Instant> clock = Instant::now;
    private Session session = new Session("sim-token-" + UUID.randomUUID(), "sim-sid-" + UUID.randomUUID());
    private boolean strict;
    private boolean expired;
    private double funds = 1e9;
    private double partialFraction = 1.0;
    private boolean fillStopOnCancel;
    private int cancelLagReads;
    private boolean staleOnce;
    private boolean refuseDuplicates;
    private List<Order> previousBook = List.of();
    private int requests;
    private final Map<String, Integer> requestsByOperation = new HashMap<>();

    /** Listens on 127.0.0.1 ({@code port} 0 = any free port); {@code timeoutMillis}: how long a TIMEOUT fault stalls. */
    public KotakSimulator(int port, String consumerKey, long timeoutMillis) throws IOException {
        this.consumerKey = consumerKey;
        this.timeoutMillis = timeoutMillis;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.executor = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("kotak-sim-", 0).factory());
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Stops the server and its threads. */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** Whether {@link #close} has run (its threads are stopping). */
    public boolean closed() {
        return executor.isShutdown();
    }

    // ------------------------------------------------------------------ set-up and faults

    public synchronized KotakSimulator list(Listing listing) {
        listings.put(listing.segment() + ":" + listing.tradingSymbol(), listing);
        return this;
    }

    public synchronized Listing byKey(String instrumentKey) {
        return listings.values().stream().filter(l -> l.instrumentKey().equals(instrumentKey)).findFirst().orElse(null);
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
        Listing l = listing(instrumentKey);
        String key = l.segment() + ":" + l.tradingSymbol();
        books.put(key, new Book(bids, asks, last));
        orders.values().stream().filter(o -> (o.segment + ":" + o.symbol).equals(key)).forEach(this::match);
    }

    private static List<Quote.Level> level(double price, long quantity) {
        return price > 0 && quantity > 0 ? List.of(new Quote.Level(price, quantity)) : List.of();
    }

    /**
     * The book as a quote (in process, not over HTTP: Kotak's quote API is out of scope), with what our fills left at
     * each level; null when the contract has no book.
     */
    public synchronized Quote book(String instrumentKey) {
        Listing l = byKey(instrumentKey);
        Book b = l == null ? null : books.get(l.segment() + ":" + l.tradingSymbol());
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
        holding(instrumentKey, quantity, price, "MIS");
    }

    /** A holding of another product (NRML, CNC: not ours, left out of the adapter's positions). */
    public synchronized void holding(String instrumentKey, long quantity, double price, String product) {
        Listing l = listing(instrumentKey);
        trades.add(new Trade(Long.toString(ids.incrementAndGet()), "0", null, l.segment(), l.tradingSymbol(), "B",
                quantity, price, clock.get(), product));
    }

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

    /** A cancelled order still shows its old status in the next {@code reads} order books. */
    public synchronized void cancelLag(int reads) {
        this.cancelLagReads = reads;
    }

    public synchronized void staleOrderBookOnce() {
        this.staleOnce = true;
    }

    /** A repeated {@code ig} is refused (Not_Ok, the wording OpenAlgo users report; UNCONFIRMED) instead of accepted. */
    public synchronized void refuseDuplicateTags(boolean on) {
        this.refuseDuplicates = on;
    }

    /** The session ends (Kotak's answer is HTTP 401) until the next {@link #login}. */
    public synchronized void expireSession() {
        this.expired = true;
    }

    /** Only the session issued by {@link #login} is accepted (default: any non-blank token and sid, for DRY_RUN). */
    public synchronized void strictSessions() {
        this.strict = true;
    }

    /** The day's login (TOTP + MPIN at Kotak): a new token and sid; the session is valid again. */
    public synchronized Session login() {
        session = new Session("sim-token-" + UUID.randomUUID(), "sim-sid-" + UUID.randomUUID());
        expired = false;
        return session;
    }

    public void clock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    public synchronized int orderCount() {
        return orders.size();
    }

    /** Net quantity held per trading symbol (buys minus sells, filled). */
    public synchronized Map<String, Long> netQuantities() {
        Map<String, Long> net = new LinkedHashMap<>();
        for (Trade t : trades) {
            net.merge(t.symbol(), t.side().equals("B") ? t.quantity() : -t.quantity(), Long::sum);
        }
        return net;
    }

    public synchronized int requests() {
        return requests;
    }

    /** Requests of one operation: place, modify, cancel, orders, trades, positions, limits, scrip, files. */
    public synchronized int requests(String operation) {
        return requestsByOperation.getOrDefault(operation, 0);
    }

    /** What our orders took from a contract's book since {@code since}, by price ({@code side} BUY or SELL). */
    public synchronized Map<Double, Long> taken(String instrumentKey, String side, Instant since) {
        Listing l = byKey(instrumentKey);
        Map<Double, Long> taken = new HashMap<>();
        if (l == null) {
            return taken;
        }
        String s = side.startsWith("S") ? "S" : "B";
        for (Trade t : trades) {
            if (!t.orderId().equals("0") && t.side().equals(s) && t.segment().equals(l.segment())
                    && t.symbol().equals(l.tradingSymbol()) && t.time() != null && !t.time().isBefore(since)) {
                taken.merge(t.price(), t.quantity(), Long::sum);
            }
        }
        return taken;
    }

    /** Margin in use: the premium paid net of sales, plus what open buy orders block. */
    public synchronized double usedMargin() {
        double used = 0;
        for (Order o : orders.values()) {
            if (o.side.equals("B") && !terminal(o)) {
                used += o.price * (o.quantity - o.filled);
            }
        }
        for (Trade t : trades) {
            used += (t.side().equals("B") ? 1 : -1) * t.price() * t.quantity();
        }
        return used;
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
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
            if (path.startsWith("/files/")) {                           // scrip master CSVs: no credentials
                String csv = path.endsWith("nse_fo.csv") ? csv("nse_fo") : path.endsWith("bse_fo.csv") ? csv("bse_fo")
                        : null;
                if (csv == null) {
                    respond(ex, 404, notOk(404, "file not found"));
                    return;
                }
                byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/csv");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bytes);
                }
                return;
            }
            if (fault == Fault.TIMEOUT) {
                Thread.sleep(timeoutMillis);
                respond(ex, 504, notOk(0, "Gateway timed out"));
                return;
            }
            if (fault == Fault.HTTP_429) {
                respond(ex, 429, notOk(0, "Too many requests"));
                return;
            }
            if (fault == Fault.HTTP_500) {
                respond(ex, 500, notOk(0, "Internal server error"));
                return;
            }
            if (fault == Fault.ERROR_1005) {
                respond(ex, 200, notOk(KotakHttp.SERVER_ERROR, "Something went wrong, please try again"));
                return;
            }
            if (fault == Fault.TOKEN_EXPIRED) {
                respond(ex, 401, notOk(0, "Invalid JWT token"));
                return;
            }
            if (fault == Fault.SESSION_INVALID) {
                respond(ex, 200, notOk(KotakHttp.SESSION_INVALID, "Invalid session"));
                return;
            }
            if (fault == Fault.WRONG_IP) {
                respond(ex, 403, notOk(KotakHttp.WRONG_IP, "Request IP does not match the session IP"));
                return;
            }
            if (fault == Fault.IP_NOT_WHITELISTED) {
                respond(ex, 200, notOk(KotakHttp.IP_NOT_WHITELISTED, "IP not whitelisted"));
                return;
            }
            if (fault == Fault.NOT_OK_OTHER) {
                respond(ex, 200, notOk(5000, "Service temporarily unavailable"));
                return;
            }
            if (fault == Fault.INPUT_ERROR) {
                respond(ex, 200, notOk(1001, "Price is outside the circuit limits"));
                return;
            }
            if (path.equals(KotakHttp.SCRIP_PATHS)) {
                if (!consumerKey.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    respond(ex, 401, notOk(0, "Invalid consumer key"));
                    return;
                }
                respond(ex, 200, scripPaths());
                return;
            }
            String refusal = authorised(ex);
            if (refusal != null) {
                respond(ex, 401, notOk(0, refusal));
                return;
            }
            Map<String, String> jData;
            try {
                jData = method.equals("POST") ? jData(body) : Map.of();
            } catch (IllegalArgumentException e) {
                respond(ex, 400, notOk(0, e.getMessage()));
                return;
            }
            ObjectNode answer = route(method, path, jData, fault);
            if (fault == Fault.HTTP_500_PROCESSED) {
                respond(ex, 500, notOk(0, "Internal server error"));
                return;
            }
            if (fault == Fault.TIMEOUT_PROCESSED) {
                Thread.sleep(timeoutMillis);
            }
            respond(ex, answer.has("_status") ? answer.remove("_status").asInt() : 200, answer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            respond(ex, 400, notOk(0, String.valueOf(e.getMessage())));
        }
    }

    private static String operation(String path) {
        return switch (path) {
            case KotakHttp.PLACE -> "place";
            case KotakHttp.MODIFY -> "modify";
            case KotakHttp.CANCEL -> "cancel";
            case KotakHttp.ORDERS -> "orders";
            case KotakHttp.TRADES -> "trades";
            case KotakHttp.POSITIONS -> "positions";
            case KotakHttp.LIMITS -> "limits";
            case KotakHttp.SCRIP_PATHS -> "scrip";
            default -> path.startsWith("/files/") ? "files" : path;
        };
    }

    /** Null when the call carries the session (and neo-fin-key), else why not. */
    private synchronized String authorised(HttpExchange ex) {
        if (!KotakConfig.NEO_FIN_KEY.equals(ex.getRequestHeaders().getFirst("neo-fin-key"))) {
            return "neo-fin-key missing";
        }
        if (expired) {
            return "Invalid JWT token";
        }
        String auth = ex.getRequestHeaders().getFirst("Auth"), sid = ex.getRequestHeaders().getFirst("Sid");
        if (auth == null || auth.isBlank() || sid == null || sid.isBlank()) {
            return "Auth and Sid are required";
        }
        return !strict || (auth.equals(session.token()) && sid.equals(session.sid())) ? null : "Invalid session token";
    }

    /** The form's {@code jData}: a JSON object whose every value is a string (Kotak refuses numbers). */
    private Map<String, String> jData(String body) {
        String raw = null;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals("jData")) {
                raw = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        if (raw == null) {
            throw new IllegalArgumentException("jData is required");
        }
        JsonNode node = json.readTree(raw);
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (!e.getValue().isString()) {
                throw new IllegalArgumentException("jData." + e.getKey() + " must be a string");
            }
            m.put(e.getKey(), e.getValue().asString());
        }
        return m;
    }

    private synchronized ObjectNode route(String method, String path, Map<String, String> j, Fault fault) {
        return switch (path) {
            case KotakHttp.PLACE -> place(j, fault);
            case KotakHttp.MODIFY -> modify(j);
            case KotakHttp.CANCEL -> cancel(j);
            case KotakHttp.ORDERS -> answer(orderBook());
            case KotakHttp.TRADES -> answer(tradeBook());
            case KotakHttp.POSITIONS -> answer(positions());
            case KotakHttp.LIMITS -> ok().put("Net", plain(funds - usedMargin())).put("Collateral", "0");
            default -> status(404, notOk(0, "Route not found"));
        };
    }

    // ------------------------------------------------------------------ orders

    private ObjectNode place(Map<String, String> j, Fault fault) {
        for (String field : List.of("es", "pt", "qt", "pr", "ts", "tt", "pc", "tp")) {
            if (!j.containsKey(field)) {
                return notOk(1000, "Missing field " + field);
            }
        }
        Listing l = listings.get(j.get("es") + ":" + j.get("ts"));
        if (l == null) {
            return notOk(KotakHttp.INVALID_SYMBOL, "Invalid trading symbol");
        }
        String pt = j.get("pt");
        if (!(pt.equals("L") || pt.equals("SL"))) {
            return notOk(1000, "Order type " + pt + " is not allowed");      // MKT converted, SL-M refused for algos
        }
        long quantity = Long.parseLong(j.get("qt"));
        if (quantity <= 0 || quantity % l.lotSize() != 0) {
            return notOk(KotakHttp.INVALID_QUANTITY, "Quantity should be a multiple of lot size");
        }
        String tag = j.get("ig");
        if (refuseDuplicates && tag != null && orders.values().stream().anyMatch(o -> tag.equals(o.tag))) {
            return notOk(400, "Client OrderID already exists");
        }
        Order o = new Order();
        o.id = Long.toString(ids.incrementAndGet());
        o.tag = tag;
        o.segment = l.segment();
        o.symbol = l.tradingSymbol();
        o.side = j.get("tt");
        o.type = pt;
        o.product = j.get("pc");
        o.quantity = quantity;
        o.price = Double.parseDouble(j.get("pr"));
        o.trigger = Double.parseDouble(j.get("tp"));
        if (pt.equals("SL") && !(o.trigger > 0)) {
            return notOk(1000, "Trigger price is required for SL");
        }
        o.lot = l.lotSize();
        o.placedAt = o.updatedAt = clock.get();
        o.entry = o.side.equals("B");
        if (pt.equals("SL")) {
            o.status = "trigger pending";
        }
        orders.put(o.id, o);
        double available = funds - usedMargin() + (o.side.equals("B") ? o.price * o.quantity : 0);  // before this one
        if (fault == Fault.REJECT_FUNDS || (o.side.equals("B") && o.price * o.quantity > available)) {
            o.status = "rejected";
            o.message = "RMS:Margin Exceeds, Required:" + Math.round(o.price * o.quantity) + ", Available:"
                    + Math.round(available);
        } else {
            match(o);
        }
        return ok().put("nOrdNo", o.id);
    }

    private ObjectNode modify(Map<String, String> j) {
        Order o = orders.get(j.get("no"));
        if (o == null) {
            return notOk(1000, "Invalid order number");
        }
        if (terminal(o)) {
            return notOk(KotakHttp.ORDER_NOT_MODIFIABLE, "Order cannot be modified as it is " + o.status);
        }
        if (j.containsKey("qt")) {
            o.quantity = Long.parseLong(j.get("qt"));
        }
        if (j.containsKey("pr")) {
            o.price = Double.parseDouble(j.get("pr"));
        }
        if (j.containsKey("tp") && o.type.equals("SL")) {
            o.trigger = Double.parseDouble(j.get("tp"));
        }
        o.updatedAt = clock.get();
        if (o.status.equals("open")) {
            o.status = "modified";
        }
        match(o);
        return ok().put("nOrdNo", o.id);
    }

    private ObjectNode cancel(Map<String, String> j) {
        Order o = orders.get(j.get("on"));
        if (o == null) {
            return notOk(1000, "Invalid order number");
        }
        if (fillStopOnCancel && o.type.equals("SL") && !terminal(o)) {
            Book b = books.get(o.segment + ":" + o.symbol);
            fill(o, o.quantity - o.filled, b == null || !(b.bid() > 0) ? o.price
                    : Math.max(o.price, Math.min(b.bid(), o.trigger)));
        }
        if (terminal(o)) {
            return notOk(KotakHttp.ORDER_FINISHED, "Order cannot be cancelled as it is " + o.status);
        }
        o.shownBeforeCancel = o.status;
        o.cancelHiddenReads = cancelLagReads;
        o.status = "cancelled";
        o.updatedAt = clock.get();
        return ok().put("result", o.id);
    }

    private static boolean terminal(Order o) {
        return o.status.equals("complete") || o.status.equals("cancelled") || o.status.equals("rejected");
    }

    private void match(Order o) {
        if (terminal(o)) {
            return;
        }
        Book b = books.get(o.segment + ":" + o.symbol);
        if (b == null) {
            return;
        }
        boolean buy = o.side.equals("B");
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
        trades.add(new Trade(Long.toString(ids.incrementAndGet()), o.id, o.tag, o.segment, o.symbol, o.side, quantity,
                price, o.updatedAt, o.product));
    }

    // ------------------------------------------------------------------ books

    private ArrayNode orderBook() {
        List<Order> now = orders.values().stream().map(o -> {
            Order c = copy(o);
            if (o.cancelHiddenReads > 0) {
                o.cancelHiddenReads--;
                c.status = o.shownBeforeCancel;
            }
            return c;
        }).toList();
        List<Order> shown = staleOnce ? previousBook : now;
        staleOnce = false;
        previousBook = now;
        ArrayNode list = json.createArrayNode();
        for (Order o : shown) {
            Listing l = listings.get(o.segment + ":" + o.symbol);
            ObjectNode row = json.createObjectNode().put("nOrdNo", o.id).put("ordSt", o.status)
                    .put("trdSym", o.symbol).put("tok", l == null ? "" : Long.toString(l.token())).put("exSeg", o.segment)
                    .put("trnsTp", o.side).put("prcTp", o.type).put("prc", plain(o.price)).put("avgPrc", plain(o.average))
                    .put("trgPrc", plain(o.trigger)).put("qty", Long.toString(o.quantity))
                    .put("fldQty", o.filled)                                    // a number here: Kotak mixes the two
                    .put("unFldSz", Long.toString(terminal(o) ? 0 : o.quantity - o.filled))
                    .put("cnlQty", Long.toString(o.status.equals("cancelled") ? o.quantity - o.filled : 0))
                    .put("prod", o.product).put("rejRsn", o.message == null ? "" : o.message)
                    .put("GuiOrdId", o.tag == null ? "" : o.tag).put("ordDtTm", ORDER_TIME.format(local(o.placedAt)))
                    .put("hsUpTm", UPDATE_TIME.format(local(o.updatedAt))).put("vldt", "DAY");
            list.add(row);
        }
        return list;
    }

    private static Order copy(Order o) {
        Order c = new Order();
        c.id = o.id;
        c.tag = o.tag;
        c.segment = o.segment;
        c.symbol = o.symbol;
        c.side = o.side;
        c.type = o.type;
        c.product = o.product;
        c.status = o.status;
        c.message = o.message;
        c.quantity = o.quantity;
        c.filled = o.filled;
        c.price = o.price;
        c.trigger = o.trigger;
        c.average = o.average;
        c.placedAt = o.placedAt;
        c.updatedAt = o.updatedAt;
        c.lot = o.lot;
        c.entry = o.entry;
        return c;
    }

    /** The trade book: no token ({@code tok} empty, as Kotak's); fills of holdings given with {@link #holding} are left out. */
    private ArrayNode tradeBook() {
        ArrayNode list = json.createArrayNode();
        for (Trade t : trades) {
            if (t.orderId().equals("0")) {
                continue;
            }
            list.add(json.createObjectNode().put("flId", t.id()).put("nOrdNo", t.orderId()).put("fldQty",
                    Long.toString(t.quantity())).put("avgPrc", plain(t.price())).put("flDt", FILL_DAY.format(local(t.time())))
                    .put("flTm", FILL_TIME.format(local(t.time()))).put("trdSym", t.symbol()).put("exSeg", t.segment())
                    .put("trnsTp", t.side()).put("prod", t.product()).put("GuiOrdId", t.tag() == null ? "" : t.tag())
                    .put("tok", ""));
        }
        return list;
    }

    private ArrayNode positions() {
        Map<String, long[]> qty = new LinkedHashMap<>();
        Map<String, double[]> value = new LinkedHashMap<>();
        for (Trade t : trades) {
            String key = t.product() + "|" + t.segment() + ":" + t.symbol();
            long[] q = qty.computeIfAbsent(key, k -> new long[2]);
            double[] v = value.computeIfAbsent(key, k -> new double[2]);
            int i = t.side().equals("B") ? 0 : 1;
            q[i] += t.quantity();
            v[i] += t.quantity() * t.price();
        }
        ArrayNode list = json.createArrayNode();
        for (Map.Entry<String, long[]> e : qty.entrySet()) {
            String product = e.getKey().substring(0, e.getKey().indexOf('|'));
            Listing l = listings.get(e.getKey().substring(e.getKey().indexOf('|') + 1));
            long[] q = e.getValue();
            double[] v = value.get(e.getKey());
            list.add(json.createObjectNode().put("trdSym", l.tradingSymbol()).put("tok", Long.toString(l.token()))
                    .put("exSeg", l.segment()).put("prod", product).put("flBuyQty", Long.toString(q[0]))
                    .put("flSellQty", Long.toString(q[1])).put("buyAmt", plain(v[0])).put("sellAmt", plain(v[1]))
                    .put("cfBuyQty", "0").put("cfSellQty", "0").put("cfBuyAmt", "0.00").put("cfSellAmt", "0.00")
                    .put("lotSz", Long.toString(l.lotSize())).put("type", l.type()));
        }
        return list;
    }

    /** A book: {@code data} with its rows; an empty book is Not_Ok "No Data", as Kotak answers it (UNCONFIRMED). */
    private ObjectNode answer(ArrayNode rows) {
        if (rows.isEmpty()) {
            return notOk(5203, "No Data");
        }
        ObjectNode body = ok();
        body.set("data", rows);
        return body;
    }

    private ObjectNode scripPaths() {
        String base = baseUri() + "/files/" + LocalDate.now(IST) + "/transformed/";
        ObjectNode data = json.createObjectNode();
        ArrayNode paths = data.putArray("filesPaths");
        paths.add(base + "cde_fo.csv").add(base + "nse_fo.csv").add(base + "bse_fo.csv").add(base + "nse_cm-v1.csv");
        ObjectNode body = json.createObjectNode();
        body.set("data", data);
        return body;
    }

    /**
     * One segment's scrip master CSV for what is listed, in Kotak's encodings: header names with stray ';' and spaces,
     * nse_fo expiry as seconds since 1980, bse_fo as epoch seconds, strike and tick in paise (×100).
     */
    public synchronized String csv(String segment) {
        StringBuilder csv = new StringBuilder("pSymbol,pGroup,pExchSeg,pInstType,pSymbolName,pTrdSymbol,pOptionType,"
                + "pScripRefKey,pISIN,pAssetCode,lLotSize;,lExpiryDate ,dStrikePrice;,dTickSize ,lFreezeQty\n");
        for (Listing l : listings.values()) {
            if (!l.segment().equals(segment)) {
                continue;
            }
            long expiry = l.expiry() == null ? 0 : KotakInstruments.rawExpiry(segment, l.expiry());
            csv.append(l.token()).append(",,").append(segment).append(",OPTIDX,").append(l.name()).append(',')
                    .append(l.tradingSymbol()).append(',').append(l.type()).append(' ').append(',')
                    .append(l.tradingSymbol()).append(",,").append(l.token()).append(',').append(l.lotSize()).append(',')
                    .append(expiry).append(',').append(Math.round(l.strike() * KotakInstruments.STRIKE_DIVISOR))
                    .append(".000000,").append(Math.round(l.tickSize() * KotakInstruments.TICK_DIVISOR)).append(",1800\n");
        }
        return csv.toString();
    }

    // ------------------------------------------------------------------ plumbing

    private ObjectNode ok() {
        return json.createObjectNode().put("stat", "Ok").put("stCode", 200);
    }

    private ObjectNode notOk(int stCode, String message) {
        ObjectNode o = json.createObjectNode().put("stat", "Not_Ok").put("emsg", message);
        if (stCode != 0) {
            o.put("stCode", stCode);
        }
        return o;
    }

    private static ObjectNode status(int code, ObjectNode body) {
        return body.put("_status", code);
    }

    private static String plain(double v) {
        return new java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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
