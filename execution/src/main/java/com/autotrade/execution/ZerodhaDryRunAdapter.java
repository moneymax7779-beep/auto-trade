package com.autotrade.execution;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.broker.BrokerCapabilities;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerException;
import com.autotrade.broker.BrokerException.Kind;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.broker.Quote;
import com.autotrade.zerodha.KiteConfig;
import com.autotrade.zerodha.KiteGateway;
import com.autotrade.zerodha.KiteHttp;
import com.autotrade.zerodha.KiteInstruments;
import com.autotrade.zerodha.KiteSimulator;

/**
 * Zerodha in DRY_RUN: the Kite adapter ({@link KiteGateway}) against a local {@link KiteSimulator} of the account's own
 * (127.0.0.1, random port). The simulator is given the account's open positions (a restart: the broker would still hold
 * them). Its book comes from one of two places (the risk file's {@code orders.dry_run_market}):
 * <ul>
 *   <li>STORED: before each order, {@link SimulatorMarket} sets it from our stored bid and ask (own capture), deep;</li>
 *   <li>BROKER (multi-broker plan step K1): the account's real Kite session is read (funds, quotes with five levels of
 *       depth, the instrument list, checked each day against our contract master by {@link KiteMappingCheck}) and every
 *       Kite quote becomes the simulator's book before the order is placed, so fills follow the real book of that
 *       moment. Orders still go only to the simulator: the real connection is read-only ({@link ReadLiveConnection}).</li>
 * </ul>
 */
public final class ZerodhaDryRunAdapter implements BrokerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaDryRunAdapter.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** The simulator never refuses for funds when the broker's real funds decide (CapitalPolicy reads them). */
    private static final double UNLIMITED_FUNDS = 1e12;
    /** A failed instrument load is tried again after this long, not on every read. */
    private static final Duration RELOAD_RETRY = Duration.ofMinutes(1);
    /** How long our simulated fills are taken off Kite's later quotes of the same contract (the entry timeout). */
    static final Duration TAKEN_MEMORY = Duration.ofSeconds(10);

    private final DataSource dataSource;
    private final double simulatedBrokerFunds;
    private final BrokerSessions sessions;
    private final URI kiteApiBase;

    public ZerodhaDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds) {
        this(dataSource, simulatedBrokerFunds, BrokerSessions.NONE, KiteConfig.REAL_API);
    }

    /**
     * @param sessions    the accounts' real Kite sessions (BROKER market only)
     * @param kiteApiBase Kite's API for those reads ({@link KiteConfig#REAL_API}; a local server in tests)
     */
    public ZerodhaDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds, BrokerSessions sessions,
                                URI kiteApiBase) {
        this.dataSource = dataSource;
        this.simulatedBrokerFunds = simulatedBrokerFunds;
        this.sessions = sessions;
        this.kiteApiBase = kiteApiBase;
    }

    @Override
    public BrokerType broker() {
        return BrokerType.ZERODHA;
    }

    @Override
    public DryRun dryRun(BrokerAccount account, OrderRules rules) throws IOException {
        return rules.brokerMarket() ? brokerMarket(account) : storedMarket(account);
    }

    private DryRun storedMarket(BrokerAccount account) throws IOException {
        KiteSimulator sim = new KiteSimulator(0, "sim-api-key", "sim-secret", 3_000);
        sim.funds(simulatedBrokerFunds);
        KiteConfig config = KiteConfig.simulator(sim.baseUri());
        KiteHttp http = new KiteHttp(config);
        http.accessToken("dry-run");
        KiteInstruments instruments = new KiteInstruments(List.of());
        KiteGateway gateway = new KiteGateway(config, http, instruments);
        SimulatorMarket market = new SimulatorMarket(sim, instruments, dataSource);
        seedHoldings(account, sim, market);
        return new DryRun(gateway, market, "Kite simulator " + sim.baseUri(), sim);
    }

    private DryRun brokerMarket(BrokerAccount account) throws IOException {
        KiteSimulator sim = new KiteSimulator(0, "sim-api-key", "sim-secret", 3_000);
        sim.funds(UNLIMITED_FUNDS);
        KiteConfig config = KiteConfig.simulator(sim.baseUri());
        KiteHttp http = new KiteHttp(config);
        http.accessToken("dry-run");
        KiteInstruments simInstruments = new KiteInstruments(List.of());
        KiteGateway simulator = new KiteGateway(config, http, simInstruments);
        SimulatorMarket listings = new SimulatorMarket(sim, simInstruments, dataSource);
        seedHoldings(account, sim, listings);
        KiteReads reads = new KiteReads(account);
        ReadLiveConnection connection = new ReadLiveConnection(reads, simulator, q -> book(q, reads, sim, simInstruments),
                sim::usedMargin);
        return new DryRun(connection, o -> { }, "Kite simulator " + sim.baseUri() + ", book and funds read from Kite "
                + kiteApiBase.getHost(), sim);
    }

    /** The account's open positions, held by the new simulator (a restart: the broker would still hold them). */
    private void seedHoldings(BrokerAccount account, KiteSimulator sim, SimulatorMarket market) {
        for (Map<String, Object> pos : new JdbcTemplate(dataSource).queryForList("select p.instrument_key, "
                + "p.instrument_token, p.symbol, p.underlying, p.quantity_open, p.average_entry, d.lot_size from exec.position p "
                + "join exec.trade_intent i on i.id = p.entry_trade_intent_id join exec.trade_batch b on b.id = i.trade_batch_id "
                + "join exec.signal_decision d on d.id = b.signal_decision_id where p.broker_account_id = ? "
                + "and p.status = 'OPEN' and p.quantity_open > 0", account.id())) {
            String key = InstrumentKeys.normalize((String) pos.get("instrument_key"),
                    ((Number) pos.get("instrument_token")).longValue());
            market.list(key, (String) pos.get("symbol"), (String) pos.get("underlying"),
                    ((Number) pos.get("lot_size")).intValue());
            sim.holding(key, ((Number) pos.get("quantity_open")).longValue(),
                    ((Number) pos.get("average_entry")).doubleValue());
        }
    }

    /**
     * A Kite quote becomes the simulator's book: the contract is listed first (as Kite names it) when the simulator does
     * not have it; the five levels when Kite gave them, else the top of book. Our simulated fills never reach Kite, so
     * Kite's next quote still shows the quantity we "took": what our orders filled at a price in the last
     * {@link #TAKEN_MEMORY} is taken off that level again (an approximation: how fast the real book refills is unknown).
     * Returns the book as the simulator now holds it.
     */
    private static Quote book(Quote q, KiteReads reads, KiteSimulator sim, KiteInstruments simInstruments) {
        String key = q.instrumentKey();
        if (sim.byKey(key) == null) {
            KiteInstruments.Contract c = reads.instruments.byInstrumentKey(key).orElseThrow(() -> new BrokerException(
                    Kind.TERMINAL, "instrument " + key + " is not in Kite's instrument list"));
            sim.list(new KiteSimulator.Listing(c.exchangeToken(), c.exchange(), c.tradingSymbol(), c.name(),
                    c.expiry() == null ? "" : c.expiry().toString(), c.strike(), c.type(), c.lotSize(), c.tickSize()));
            simInstruments.add(c);
        }
        List<Quote.Level> bids = !q.bids().isEmpty() ? q.bids()
                : q.hasBid() ? List.of(new Quote.Level(q.bid(), q.bidQuantity())) : List.of();
        List<Quote.Level> asks = !q.asks().isEmpty() ? q.asks()
                : q.hasAsk() ? List.of(new Quote.Level(q.ask(), q.askQuantity())) : List.of();
        Instant since = Instant.now().minus(TAKEN_MEMORY);
        List<Quote.Level> bidsLeft = less(bids, sim.taken(key, "SELL", since));
        List<Quote.Level> asksLeft = less(asks, sim.taken(key, "BUY", since));
        sim.depth(key, bidsLeft, asksLeft, q.last());
        Quote.Level bid = bidsLeft.isEmpty() ? new Quote.Level(0, 0) : bidsLeft.getFirst();
        Quote.Level ask = asksLeft.isEmpty() ? new Quote.Level(0, 0) : asksLeft.getFirst();
        return new Quote(key, bid.price(), bid.quantity(), ask.price(), ask.quantity(), q.last(), q.time(), bidsLeft,
                asksLeft);
    }

    /** The levels with what we took at each price removed (a level we emptied is left out). */
    static List<Quote.Level> less(List<Quote.Level> levels, Map<Double, Long> taken) {
        List<Quote.Level> left = new java.util.ArrayList<>();
        for (Quote.Level l : levels) {
            long quantity = l.quantity() - taken.getOrDefault(l.price(), 0L);
            if (quantity > 0) {
                left.add(new Quote.Level(l.price(), quantity));
            }
        }
        return left;
    }

    /**
     * The account's real Kite session, for reads: the app key and token are looked up on every call (a login during the
     * day takes effect at once; no session = {@link Kind#SESSION}); the instrument list is loaded once a day and checked
     * against our contract master. Order calls are not offered here (and {@link ReadLiveConnection} refuses them).
     */
    private final class KiteReads implements BrokerConnection {

        private final BrokerAccount account;
        private final KiteInstruments instruments = new KiteInstruments(List.of());
        private String apiKey;
        private KiteHttp http;
        private KiteGateway gateway;
        private LocalDate loadedDay;
        private Instant lastAttempt = Instant.EPOCH;

        KiteReads(BrokerAccount account) {
            this.account = account;
        }

        private synchronized KiteGateway gateway() {
            BrokerSessions.Access access = sessions.access(account).orElseThrow(() -> new BrokerException(Kind.SESSION,
                    "no Kite session for " + account.displayName() + " today: log in"));
            if (gateway == null || !access.apiKey().equals(apiKey)) {
                KiteConfig config = new KiteConfig(access.apiKey(), kiteApiBase, KiteConfig.REAL_LOGIN, "MIS",
                        Duration.ofSeconds(5), 8, null);
                http = new KiteHttp(config);
                gateway = new KiteGateway(config, http, instruments);
                apiKey = access.apiKey();
                loadedDay = null;
            }
            http.accessToken(access.accessToken());
            loadToday();
            return gateway;
        }

        /** Today's instrument list, once a day (a failure is retried after a minute; the old day's list is not kept). */
        private void loadToday() {
            LocalDate today = LocalDate.now(IST);
            if (today.equals(loadedDay) || Instant.now().isBefore(lastAttempt.plus(RELOAD_RETRY))) {
                return;
            }
            lastAttempt = Instant.now();
            try {
                KiteInstruments fresh = KiteInstruments.load(http);
                KiteMappingCheck.Result check = KiteMappingCheck.apply(fresh, new JdbcTemplate(dataSource), today);
                instruments.replaceWith(fresh);
                loadedDay = today;
                if (check.mismatched().isEmpty()) {
                    log.info("account {} ({}): Kite instruments for {}: {} listed; {}", account.id(),
                            account.displayName(), today, fresh.size(), check.summary());
                } else {
                    log.error("account {} ({}): Kite instruments for {}: {}", account.id(), account.displayName(), today,
                            check.summary());
                    new KillSwitches(dataSource).audit(account, "execution", "INSTRUMENT_MAPPING_MISMATCH",
                            Map.of("checked", check.checked(), "mismatched", check.mismatched()));
                }
            } catch (IOException | RuntimeException e) {
                if (e instanceof BrokerException b && b.kind() == Kind.SESSION) {
                    throw b;
                }
                if (!today.equals(loadedDay)) {
                    instruments.replaceWith(new KiteInstruments(List.of()));   // never yesterday's tokens
                }
                log.warn("account {} ({}): Kite instrument list not loaded ({}); orders are refused until it is",
                        account.id(), account.displayName(), e.getMessage());
            }
        }

        @Override
        public String name() {
            return "ZERODHA";
        }

        @Override
        public BrokerCapabilities capabilities() {
            return gateway().capabilities();
        }

        @Override
        public double availableFunds() {
            return gateway().availableFunds();
        }

        @Override
        public Quote quote(String instrumentKey) {
            return gateway().quote(instrumentKey);
        }

        @Override
        public List<BrokerOrder> orders() {
            return gateway().orders();
        }

        @Override
        public List<BrokerTrade> trades() {
            return gateway().trades();
        }

        @Override
        public List<BrokerPosition> positions() {
            return gateway().positions();
        }

        @Override
        public int poll() {
            throw new IllegalStateException("not offered: reads only");
        }

        @Override
        public void onUpdate(java.util.function.Consumer<OrderUpdate> listener) {
            throw new IllegalStateException("not offered: reads only");
        }

        @Override
        public void place(OrderRequest request) {
            throw new IllegalStateException("not offered: reads only");
        }

        @Override
        public void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
            throw new IllegalStateException("not offered: reads only");
        }

        @Override
        public void cancel(String clientOrderId) {
            throw new IllegalStateException("not offered: reads only");
        }
    }

    /**
     * Before each order of a DRY_RUN replay or worker: the contract is listed (simulator and the gateway's instrument
     * map, by the exchange token in our key), the simulator's clock is the decision's time, and its book is the stored
     * bid and ask of that moment (own capture md.option_tick, the last minute), else the order's price on both sides.
     * Depth is not modelled (deep book): the replay depth ceiling already caps sizes.
     */
    static final class SimulatorMarket implements java.util.function.Consumer<FollowerVenue.OrderSpec> {

        private static final long DEPTH_LOTS = 100_000;
        private final KiteSimulator sim;
        private final KiteInstruments instruments;
        private final org.springframework.jdbc.core.JdbcTemplate jdbc;

        SimulatorMarket(KiteSimulator sim, KiteInstruments instruments, DataSource dataSource) {
            this.sim = sim;
            this.instruments = instruments;
            this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        }

        void list(String key, String symbol, String underlying, int lotSize) {
            if (key == null || key.indexOf('|') < 0 || sim.byKey(key) != null) {
                return;
            }
            String exchange = key.startsWith("BSE_FO") ? "BFO" : "NFO";
            long token = Long.parseLong(key.substring(key.indexOf('|') + 1));
            String tradingSymbol = symbol.replaceAll("[^A-Za-z0-9]", "");
            sim.list(new KiteSimulator.Listing(token, exchange, tradingSymbol, underlying, "", 0, "CE", lotSize, 0.05));
            instruments.add(new KiteInstruments.Contract(token * 256, exchange, token, tradingSymbol, underlying, null, 0,
                    "CE", lotSize, 0.05));
        }

        @Override
        public void accept(FollowerVenue.OrderSpec o) {
            String key = o.instrumentKey();
            if (key == null || key.indexOf('|') < 0) {
                return;
            }
            list(key, o.symbol(), o.underlying(), o.lotSize());
            sim.clock(o::at);
            long depth = DEPTH_LOTS * o.lotSize();
            List<Map<String, Object>> tick = jdbc.queryForList("select bid_px[1] bid, ask_px[1] ask, ltp from md.option_tick "
                    + "where instrument_token = ? and recv_ts <= ? and recv_ts > ? order by recv_ts desc limit 1", o.token(),
                    java.sql.Timestamp.from(o.at()), java.sql.Timestamp.from(o.at().minusSeconds(60)));
            if (!tick.isEmpty() && tick.getFirst().get("bid") != null && tick.getFirst().get("ask") != null
                    && ((Number) tick.getFirst().get("bid")).doubleValue() > 0
                    && ((Number) tick.getFirst().get("ask")).doubleValue() > 0) {
                Map<String, Object> t = tick.getFirst();
                sim.quote(key, ((Number) t.get("bid")).doubleValue(), depth, ((Number) t.get("ask")).doubleValue(), depth,
                        ((Number) t.get("ltp")).doubleValue());
            } else if (o.price() > 0) {
                sim.quote(key, o.price(), depth, o.price(), depth, o.price());
            }
        }
    }
}
