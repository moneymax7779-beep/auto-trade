package com.autotrade.zerodha;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A local Kite Connect v3 server (live plan section 8, "Fault-injection broker"): the endpoints {@link KiteGateway} and
 * {@link KiteAuth} call, an order book that fills against the quotes it is given, and faults on demand. DRY_RUN accounts
 * trade against it (so the whole live path runs, HTTP included, and no order can reach Zerodha), and the fault suite
 * drives every section-4 scenario through it.
 * <p>
 * <b>Fills</b>: a LIMIT buy fills against the ask when its price reaches it (at the ask, at most the ask quantity), a
 * sell against the bid; the rest rests OPEN and fills when a later quote reaches it. An SL sell waits (TRIGGER PENDING)
 * until the last price is at or below its trigger, then works as a limit at its price. Quantities fill in whole
 * {@code lot}s of the contract.
 * <p>
 * <b>Faults</b> ({@link #failNext}): 429, a 500 that was not processed, a 500 or a timeout after the order <i>was</i>
 * processed, a timeout before processing, an expired token, an InputException (price band), a rejected order (funds);
 * {@link #partialFills} (an entry fills only part of its quantity), {@link #fillStopOnCancel} (a stop fills just as its
 * cancel arrives), {@link #staleOrderBookOnce} (the next order book is the previous one: a late, reordered report),
 * {@link #expireToken}.
 */
public final class KiteSimulator implements AutoCloseable {

    /** NONE: the call goes through (queued before a fault to aim it at a later call). */
    public enum Fault {
        NONE, HTTP_429, HTTP_500, HTTP_500_PROCESSED, TIMEOUT, TIMEOUT_PROCESSED, TOKEN_EXPIRED, INPUT_ERROR, REJECT_FUNDS
    }

    /** One contract the simulator lists. */
    public record Listing(long exchangeToken, String exchange, String tradingSymbol, String name, String expiry,
                          double strike, String type, int lotSize, double tickSize) {
        long instrumentToken() {
            return exchangeToken * 256 + (exchange.equals("BFO") ? 4 : 2);
        }
    }

    private record Book(double bid, long bidQuantity, double ask, long askQuantity, double last) {
    }

    private static final class Order {
        String id, tag, exchange, symbol, side, type, status = "OPEN", message;
        long quantity, filled;
        double price, trigger, average;
        Instant placedAt, updatedAt;
        int lot;
        boolean entry;

        double value() {
            return average * filled;
        }
    }

    private record Trade(String id, String orderId, String exchange, String symbol, String side, long quantity,
                         double price, Instant time) {
    }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String apiKey;
    private final String apiSecret;
    private final Map<String, Listing> listings = new LinkedHashMap<>();
    private final Map<String, Book> books = new HashMap<>();
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final List<Trade> trades = new ArrayList<>();
    private final Map<String, Deque<Fault>> faults = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000);   // unique across instances, as Kite's
    private final long timeoutMillis;
    private volatile Supplier<Instant> clock = Instant::now;
    private String token;
    private boolean acceptAnyToken = true;
    private boolean tokenExpired;
    private double funds = 1e9;
    private double partialFraction = 1.0;
    private boolean fillStopOnCancel;
    private boolean staleOnce;
    private List<Order> previousBook = List.of();
    private int requests;

    /** Listens on 127.0.0.1 ({@code port} 0 = any free port); {@code timeoutMillis}: how long a TIMEOUT fault stalls. */
    public KiteSimulator(int port, String apiKey, String apiSecret, long timeoutMillis) throws IOException {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.timeoutMillis = timeoutMillis;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("kite-sim-", 0).factory()));
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ set-up and faults

    public synchronized KiteSimulator list(Listing listing) {
        listings.put(listing.exchange() + ":" + listing.tradingSymbol(), listing);
        return this;
    }

    /** The executable book of a contract; resting orders that it reaches fill now. */
    public synchronized void quote(String exchange, String symbol, double bid, long bidQuantity, double ask,
                                   long askQuantity, double last) {
        books.put(exchange + ":" + symbol, new Book(bid, bidQuantity, ask, askQuantity, last));
        orders.values().stream().filter(o -> (o.exchange + ":" + o.symbol).equals(exchange + ":" + symbol)).forEach(this::match);
    }

    /** The same quote by our instrument key ({@code NSE_FO|47168}). */
    public synchronized void quote(String instrumentKey, double bid, long bidQuantity, double ask, long askQuantity,
                                   double last) {
        Listing l = byKey(instrumentKey);
        if (l == null) {
            throw new IllegalArgumentException("not listed: " + instrumentKey);
        }
        quote(l.exchange(), l.tradingSymbol(), bid, bidQuantity, ask, askQuantity, last);
    }

    public synchronized Listing byKey(String instrumentKey) {
        int bar = instrumentKey.indexOf('|');
        String exchange = instrumentKey.startsWith("BSE_FO") ? "BFO" : "NFO";
        long token = Long.parseLong(instrumentKey.substring(bar + 1));
        return listings.values().stream().filter(l -> l.exchange().equals(exchange) && l.exchangeToken() == token)
                .findFirst().orElse(null);
    }

    /**
     * A holding the broker already has (a worker restarted: the simulator is new, the account's open positions are
     * not): recorded as a filled buy, without an order.
     */
    public synchronized void holding(String instrumentKey, long quantity, double price) {
        Listing l = byKey(instrumentKey);
        if (l == null) {
            throw new IllegalArgumentException("not listed: " + instrumentKey);
        }
        trades.add(new Trade(Long.toString(ids.incrementAndGet()), "0", l.exchange(), l.tradingSymbol(), "BUY", quantity,
                price, clock.get()));
    }

    public synchronized void failNext(String operation, Fault fault) {
        faults.computeIfAbsent(operation, k -> new ArrayDeque<>()).add(fault);
    }

    public synchronized void funds(double available) {
        this.funds = available;
    }

    /** Entries fill this fraction of their quantity (whole lots, at least one), the rest rests until cancelled. */
    public synchronized void partialFills(double fraction) {
        this.partialFraction = fraction;
    }

    public synchronized void fillStopOnCancel(boolean on) {
        this.fillStopOnCancel = on;
    }

    public synchronized void staleOrderBookOnce() {
        this.staleOnce = true;
    }

    public synchronized void expireToken() {
        this.tokenExpired = true;
    }

    /** Strict: only the token issued by /session/token is accepted (default: any token, for DRY_RUN). */
    public synchronized void strictTokens() {
        this.acceptAnyToken = false;
    }

    public void clock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    public synchronized int orderCount() {
        return orders.size();
    }

    /** Net quantity held per tradingsymbol (buys minus sells, filled). */
    public synchronized Map<String, Long> netQuantities() {
        Map<String, Long> net = new LinkedHashMap<>();
        for (Trade t : trades) {
            net.merge(t.symbol(), t.side().equals("BUY") ? t.quantity() : -t.quantity(), Long::sum);
        }
        return net;
    }

    public synchronized int requests() {
        return requests;
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        Map<String, String> form = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Map<String, List<String>> query = query(ex.getRequestURI().getRawQuery());
        String op = operation(method, path);
        Fault fault;
        synchronized (this) {
            requests++;
            Deque<Fault> queue = faults.get(op);
            fault = queue == null ? null : queue.poll();
            fault = fault == Fault.NONE ? null : fault;
        }
        try {
            if (path.startsWith("/sim/")) {                          // drills: faults from inside the host only
                String result = drill(path, query);
                byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bytes);
                }
                return;
            }
            if (path.equals("/instruments")) {
                byte[] csv = instrumentsCsv().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/csv");
                ex.sendResponseHeaders(200, csv.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(csv);
                }
                return;
            }
            if (fault == Fault.TIMEOUT) {
                Thread.sleep(timeoutMillis);
                respond(ex, 504, error("NetworkException", "Gateway timed out"));
                return;
            }
            if (fault == Fault.HTTP_429) {
                respond(ex, 429, error("NetworkException", "Too many requests"));
                return;
            }
            if (fault == Fault.HTTP_500) {
                respond(ex, 500, error("GeneralException", "Internal server error"));
                return;
            }
            if (fault == Fault.TOKEN_EXPIRED) {
                respond(ex, 403, error("TokenException", "Incorrect `api_key` or `access_token`."));
                return;
            }
            if (fault == Fault.INPUT_ERROR) {
                respond(ex, 400, error("InputException", "Price is outside the circuit limits."));
                return;
            }
            if (!path.equals("/session/token") && !path.equals("/instruments") && !authorised(ex)) {
                respond(ex, 403, error("TokenException", "Incorrect `api_key` or `access_token`."));
                return;
            }
            ObjectNode body = route(method, path, form, query, fault);
            if (fault == Fault.HTTP_500_PROCESSED) {
                respond(ex, 500, error("GeneralException", "Internal server error"));
                return;
            }
            if (fault == Fault.TIMEOUT_PROCESSED) {
                Thread.sleep(timeoutMillis);
            }
            respond(ex, body.has("_status") ? body.remove("_status").asInt() : 200, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            respond(ex, 400, error("InputException", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Drill endpoints (the simulator listens on 127.0.0.1 only): {@code /sim/fault?op=place&fault=TIMEOUT_PROCESSED},
     * {@code /sim/expire-token}, {@code /sim/login}, {@code /sim/partial?fraction=0.5}, {@code /sim/stop-fills-on-cancel?on=true},
     * {@code /sim/state} (orders and net quantities).
     */
    private synchronized String drill(String path, Map<String, List<String>> query) {
        return switch (path) {
            case "/sim/fault" -> {
                failNext(query.getOrDefault("op", List.of("place")).getFirst(),
                        Fault.valueOf(query.getOrDefault("fault", List.of("NONE")).getFirst()));
                yield "queued\n";
            }
            case "/sim/expire-token" -> {
                expireToken();
                yield "token expired\n";
            }
            case "/sim/login" -> {                                  // the day's new login: the session is valid again
                tokenExpired = false;
                yield "token valid\n";
            }
            case "/sim/partial" -> {
                partialFills(Double.parseDouble(query.getOrDefault("fraction", List.of("1")).getFirst()));
                yield "partial fills " + partialFraction + "\n";
            }
            case "/sim/stop-fills-on-cancel" -> {
                fillStopOnCancel(Boolean.parseBoolean(query.getOrDefault("on", List.of("true")).getFirst()));
                yield "stop fills on cancel " + fillStopOnCancel + "\n";
            }
            case "/sim/state" -> "orders " + orders.size() + ", net " + netQuantities() + ", token "
                    + (tokenExpired ? "expired" : "valid") + "\n";
            default -> "unknown drill " + path + "\n";
        };
    }

    private static String operation(String method, String path) {
        if (path.startsWith("/orders/regular")) {
            return switch (method) {
                case "POST" -> "place";
                case "PUT" -> "modify";
                default -> "cancel";
            };
        }
        return switch (path) {
            case "/orders" -> "orders";
            case "/trades" -> "trades";
            case "/quote" -> "quote";
            case "/user/margins/equity" -> "margins";
            case "/portfolio/positions" -> "positions";
            case "/session/token" -> "session";
            default -> path;
        };
    }

    private synchronized boolean authorised(HttpExchange ex) {
        if (tokenExpired) {
            return false;
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("token " + apiKey + ":")) {
            return false;
        }
        String given = auth.substring(("token " + apiKey + ":").length());
        return acceptAnyToken ? !given.isBlank() : given.equals(token);
    }

    private synchronized ObjectNode route(String method, String path, Map<String, String> form,
                                          Map<String, List<String>> query, Fault fault) {
        if (path.equals("/session/token")) {
            if (!apiKey.equals(form.get("api_key"))
                    || !KiteAuth.checksum(apiKey, form.getOrDefault("request_token", ""), apiSecret).equals(form.get("checksum"))) {
                return status(403, error("TokenException", "Invalid `checksum`."));
            }
            token = "sim-" + UUID.randomUUID();
            tokenExpired = false;
            ObjectNode data = json.createObjectNode().put("user_id", "SIM001").put("access_token", token)
                    .put("login_time", TIME.format(LocalDateTime.ofInstant(clock.get(), IST)));
            return success(data);
        }
        if (path.startsWith("/orders/regular")) {
            return switch (method) {
                case "POST" -> place(form, fault);
                case "PUT" -> modify(path.substring("/orders/regular/".length()), form);
                default -> cancel(path.substring("/orders/regular/".length()));
            };
        }
        return switch (path) {
            case "/orders" -> success(orderBook());
            case "/trades" -> success(tradeBook());
            case "/quote" -> success(quotes(query.getOrDefault("i", List.of())));
            case "/user/margins/equity" -> success(json.createObjectNode().put("net", funds)
                    .set("available", json.createObjectNode().put("live_balance", funds - usedMargin()).put("cash", funds)));
            case "/portfolio/positions" -> success(positions());
            default -> status(404, error("GeneralException", "Route not found"));
        };
    }

    // ------------------------------------------------------------------ orders

    private ObjectNode place(Map<String, String> f, Fault fault) {
        String key = f.get("exchange") + ":" + f.get("tradingsymbol");
        Listing l = listings.get(key);
        if (l == null) {
            return status(400, error("InputException", "Invalid `tradingsymbol`."));
        }
        String tag = f.get("tag");
        if (tag != null && (tag.length() > 20 || !tag.chars().allMatch(Character::isLetterOrDigit))) {
            return status(400, error("InputException", "Invalid `tag`."));
        }
        long quantity = Long.parseLong(f.get("quantity"));
        if (quantity % l.lotSize() != 0) {
            return status(400, error("InputException", "Quantity should be a multiple of lot size."));
        }
        Order o = new Order();
        o.id = Long.toString(ids.incrementAndGet());
        o.tag = tag;
        o.exchange = f.get("exchange");
        o.symbol = f.get("tradingsymbol");
        o.side = f.get("transaction_type");
        o.type = f.get("order_type");
        o.quantity = quantity;
        o.price = Double.parseDouble(f.getOrDefault("price", "0"));
        o.trigger = Double.parseDouble(f.getOrDefault("trigger_price", "0"));
        o.lot = l.lotSize();
        o.placedAt = o.updatedAt = clock.get();
        o.entry = o.side.equals("BUY");
        if (o.type.equals("SL")) {
            o.status = "TRIGGER PENDING";
        }
        orders.put(o.id, o);
        if (fault == Fault.REJECT_FUNDS || (o.side.equals("BUY") && o.price * o.quantity > funds - usedMargin())) {
            o.status = "REJECTED";
            o.message = "Insufficient funds. Required margin is " + Math.round(o.price * o.quantity) + ".";
        } else {
            match(o);
        }
        return success(json.createObjectNode().put("order_id", o.id));
    }

    private ObjectNode modify(String id, Map<String, String> f) {
        Order o = orders.get(id);
        if (o == null) {
            return status(400, error("InputException", "Invalid `order_id`."));
        }
        if (terminal(o)) {
            return status(400, error("InputException", "Order cannot be modified as it is " + o.status.toLowerCase() + "."));
        }
        if (f.containsKey("quantity")) {
            o.quantity = Long.parseLong(f.get("quantity"));
        }
        if (f.containsKey("price")) {
            o.price = Double.parseDouble(f.get("price"));
        }
        if (f.containsKey("trigger_price")) {
            o.trigger = Double.parseDouble(f.get("trigger_price"));
        }
        o.updatedAt = clock.get();
        match(o);
        return success(json.createObjectNode().put("order_id", o.id));
    }

    private ObjectNode cancel(String id) {
        Order o = orders.get(id);
        if (o == null) {
            return status(400, error("InputException", "Invalid `order_id`."));
        }
        if (fillStopOnCancel && o.type.equals("SL") && !terminal(o)) {
            Book b = books.get(o.exchange + ":" + o.symbol);
            fill(o, o.quantity - o.filled, b == null ? o.price : Math.max(o.price, Math.min(b.bid(), o.trigger)));
        }
        if (terminal(o)) {
            return status(400, error("InputException", "Order cannot be cancelled as it is " + o.status.toLowerCase()
                    + "."));
        }
        o.status = "CANCELLED";
        o.updatedAt = clock.get();
        return success(json.createObjectNode().put("order_id", o.id));
    }

    private static boolean terminal(Order o) {
        return o.status.equals("COMPLETE") || o.status.equals("CANCELLED") || o.status.equals("REJECTED");
    }

    /** Fills what the book reaches: whole lots, at most the book's quantity (and the partial-fill fraction). */
    private void match(Order o) {
        if (terminal(o)) {
            return;
        }
        Book b = books.get(o.exchange + ":" + o.symbol);
        if (b == null) {
            return;
        }
        if (o.status.equals("TRIGGER PENDING")) {
            boolean triggered = o.side.equals("SELL") ? b.last() > 0 && b.last() <= o.trigger
                    : b.last() > 0 && b.last() >= o.trigger;
            if (!triggered) {
                return;
            }
            o.status = "OPEN";
        }
        boolean buy = o.side.equals("BUY");
        double touch = buy ? b.ask() : b.bid();
        long depth = buy ? b.askQuantity() : b.bidQuantity();
        if (!(touch > 0) || depth <= 0 || (buy ? o.price < touch : o.price > touch)) {
            return;
        }
        long open = o.quantity - o.filled;
        long want = o.entry && partialFraction < 1 && o.filled == 0
                ? Math.max(o.lot, (long) Math.floor(o.quantity * partialFraction / o.lot) * o.lot) : open;
        long quantity = Math.min(Math.min(open, want), depth / o.lot * o.lot);
        if (quantity > 0) {
            fill(o, quantity, touch);
        }
    }

    private void fill(Order o, long quantity, double price) {
        o.average = (o.value() + price * quantity) / (o.filled + quantity);
        o.filled += quantity;
        o.updatedAt = clock.get();
        if (o.filled >= o.quantity) {
            o.status = "COMPLETE";
        } else if (o.status.equals("TRIGGER PENDING")) {
            o.status = "OPEN";
        }
        trades.add(new Trade(Long.toString(ids.incrementAndGet()), o.id, o.exchange, o.symbol, o.side, quantity, price,
                o.updatedAt));
    }

    /** Margin in use: the premium paid net of sales, plus what open buy orders block. */
    private double usedMargin() {
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

    // ------------------------------------------------------------------ books

    private ArrayNode orderBook() {
        List<Order> now = orders.values().stream().map(KiteSimulator::copy).toList();
        List<Order> shown = staleOnce ? previousBook : now;
        staleOnce = false;
        previousBook = now;
        ArrayNode list = json.createArrayNode();
        for (Order o : shown) {
            Listing l = listings.get(o.exchange + ":" + o.symbol);
            list.add(json.createObjectNode().put("order_id", o.id).put("status", o.status)
                    .put("status_message", o.message).put("tradingsymbol", o.symbol).put("exchange", o.exchange)
                    .put("exchange_token", l == null ? 0 : l.exchangeToken())
                    .put("instrument_token", l == null ? 0 : l.instrumentToken()).put("transaction_type", o.side)
                    .put("order_type", o.type).put("product", "MIS").put("variety", "regular").put("validity", "DAY")
                    .put("quantity", o.quantity).put("filled_quantity", o.filled)
                    .put("pending_quantity", terminal(o) ? 0 : o.quantity - o.filled).put("price", o.price)
                    .put("trigger_price", o.trigger).put("average_price", o.average).put("tag", o.tag)
                    .put("order_timestamp", ts(o.placedAt)).put("exchange_update_timestamp", ts(o.updatedAt)));
        }
        return list;
    }

    private static Order copy(Order o) {
        Order c = new Order();
        c.id = o.id;
        c.tag = o.tag;
        c.exchange = o.exchange;
        c.symbol = o.symbol;
        c.side = o.side;
        c.type = o.type;
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

    private ArrayNode tradeBook() {
        ArrayNode list = json.createArrayNode();
        for (Trade t : trades) {
            Listing l = listings.get(t.exchange() + ":" + t.symbol());
            list.add(json.createObjectNode().put("trade_id", t.id()).put("order_id", t.orderId()).put("exchange", t.exchange())
                    .put("tradingsymbol", t.symbol()).put("exchange_token", l == null ? 0 : l.exchangeToken())
                    .put("transaction_type", t.side()).put("quantity", t.quantity()).put("average_price", t.price())
                    .put("product", "MIS").put("fill_timestamp", ts(t.time())));
        }
        return list;
    }

    private ObjectNode quotes(List<String> keys) {
        ObjectNode data = json.createObjectNode();
        for (String key : keys) {
            Book b = books.get(key);
            Listing l = listings.get(key);
            if (b == null || l == null) {
                continue;
            }
            ObjectNode depth = json.createObjectNode();
            depth.set("buy", json.createArrayNode().add(json.createObjectNode().put("price", b.bid())
                    .put("quantity", b.bidQuantity()).put("orders", 1)));
            depth.set("sell", json.createArrayNode().add(json.createObjectNode().put("price", b.ask())
                    .put("quantity", b.askQuantity()).put("orders", 1)));
            ObjectNode q = json.createObjectNode().put("instrument_token", l.instrumentToken()).put("last_price", b.last())
                    .put("timestamp", ts(clock.get()));
            q.set("depth", depth);
            data.set(key, q);
        }
        return data;
    }

    private ObjectNode positions() {
        Map<String, long[]> qty = new LinkedHashMap<>();
        Map<String, double[]> value = new LinkedHashMap<>();
        for (Trade t : trades) {
            String key = t.exchange() + ":" + t.symbol();
            long[] q = qty.computeIfAbsent(key, k -> new long[2]);
            double[] v = value.computeIfAbsent(key, k -> new double[2]);
            int i = t.side().equals("BUY") ? 0 : 1;
            q[i] += t.quantity();
            v[i] += t.quantity() * t.price();
        }
        ArrayNode net = json.createArrayNode();
        for (Map.Entry<String, long[]> e : qty.entrySet()) {
            Listing l = listings.get(e.getKey());
            long[] q = e.getValue();
            double[] v = value.get(e.getKey());
            long quantity = q[0] - q[1];
            net.add(json.createObjectNode().put("tradingsymbol", l.tradingSymbol()).put("exchange", l.exchange())
                    .put("instrument_token", l.instrumentToken()).put("exchange_token", l.exchangeToken())
                    .put("product", "MIS").put("quantity", quantity)
                    .put("average_price", q[0] == 0 ? 0 : v[0] / q[0]).put("buy_quantity", q[0]).put("sell_quantity", q[1])
                    .put("buy_value", v[0]).put("sell_value", v[1]).put("day_buy_quantity", q[0])
                    .put("day_sell_quantity", q[1]).put("day_buy_value", v[0]).put("day_sell_value", v[1])
                    .put("realised", quantity == 0 ? v[1] - v[0] : 0));
        }
        ObjectNode data = json.createObjectNode();
        data.set("net", net);
        data.set("day", net.deepCopy());
        return data;
    }

    /** Kite's instrument CSV for what is listed (GET /instruments). */
    public synchronized String instrumentsCsv() {
        StringBuilder csv = new StringBuilder("instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,"
                + "tick_size,lot_size,instrument_type,segment,exchange\n");
        for (Listing l : listings.values()) {
            csv.append(l.instrumentToken()).append(',').append(l.exchangeToken()).append(',').append(l.tradingSymbol())
                    .append(",\"").append(l.name()).append("\",0,").append(l.expiry()).append(',').append(l.strike())
                    .append(',').append(l.tickSize()).append(',').append(l.lotSize()).append(',').append(l.type())
                    .append(',').append(l.exchange()).append("-OPT,").append(l.exchange()).append('\n');
        }
        return csv.toString();
    }

    // ------------------------------------------------------------------ plumbing

    private ObjectNode success(tools.jackson.databind.JsonNode data) {
        ObjectNode body = json.createObjectNode().put("status", "success");
        body.set("data", data);
        return body;
    }

    private ObjectNode error(String type, String message) {
        return json.createObjectNode().put("status", "error").put("message", message).put("error_type", type);
    }

    private static ObjectNode status(int code, ObjectNode body) {
        return body.put("_status", code);
    }

    private void respond(HttpExchange ex, int code, ObjectNode body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String ts(Instant t) {
        return t == null ? null : TIME.format(LocalDateTime.ofInstant(t, IST));
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return m;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static Map<String, List<String>> query(String raw) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        if (raw == null) {
            return m;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                m.computeIfAbsent(pair.substring(0, eq), k -> new ArrayList<>())
                        .add(URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }
}
