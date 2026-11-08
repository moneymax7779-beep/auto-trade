package com.autotrade.execution;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

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
import com.autotrade.kotak.KotakConfig;
import com.autotrade.kotak.KotakGateway;
import com.autotrade.kotak.KotakHttp;
import com.autotrade.kotak.KotakInstruments;
import com.autotrade.kotak.KotakSimulator;

/**
 * Kotak Neo in DRY_RUN (live adapters plan step B1): the Kotak adapter ({@link KotakGateway}) against a local
 * {@link KotakSimulator} of the account's own (127.0.0.1, random port), given the account's open positions at start. Its
 * book comes from one of two places (the risk file's {@code orders.dry_run_market}):
 * <ul>
 *   <li>STORED: before each order, the stored bid and ask of that moment (own capture), deep, as for Kite;</li>
 *   <li>BROKER (step C, READ_LIVE): the account's real Kotak session (token, sid and base URL from the day's login) is
 *       read for funds (limits) and the scrip master, checked each day against our contract master by
 *       {@link KotakMappingCheck}. Kotak's quote API is not called in this build: the book is our own live capture of
 *       that moment ({@link CapturedQuotes}), and every such quote becomes the simulator's book before the order. Orders
 *       go only to the simulator: the real connection is read-only ({@link ReadLiveConnection}).</li>
 * </ul>
 * There is no LIVE Kotak connection: {@link #live} returns null, so an account switched LIVE gets no route (and Kotak is
 * not in {@link LiveBrokers#LIVE}). LIVE needs the READ_LIVE sessions, the static IP and the canary of the plan first.
 */
public final class KotakDryRunAdapter implements BrokerAdapter {

    private static final Logger log = LoggerFactory.getLogger(KotakDryRunAdapter.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double UNLIMITED_FUNDS = 1e12;
    private static final Duration RELOAD_RETRY = Duration.ofMinutes(1);
    /** The keys of {@link BrokerSessions.Access#extra()} a Kotak session carries. */
    public static final String SID = "sid", BASE_URL = "baseUrl";

    private final DataSource dataSource;
    private final double simulatedBrokerFunds;
    private final BrokerSessions sessions;
    private final Function<String, Quote> liveQuotes;

    public KotakDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds) {
        this(dataSource, simulatedBrokerFunds, BrokerSessions.NONE);
    }

    /** @param sessions the accounts' real Kotak sessions (BROKER market only) */
    public KotakDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds, BrokerSessions sessions) {
        this(dataSource, simulatedBrokerFunds, sessions, new CapturedQuotes(dataSource));
    }

    /** @param liveQuotes the book of now by our key, for BROKER-market routes (our live capture; a stand-in in tests) */
    public KotakDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds, BrokerSessions sessions,
                              Function<String, Quote> liveQuotes) {
        this.dataSource = dataSource;
        this.simulatedBrokerFunds = simulatedBrokerFunds;
        this.sessions = sessions;
        this.liveQuotes = liveQuotes;
    }

    @Override
    public BrokerType broker() {
        return BrokerType.KOTAK_NEO;
    }

    @Override
    public DryRun dryRun(BrokerAccount account, OrderRules rules) throws IOException {
        return rules.brokerMarket() ? brokerMarket(account) : storedMarket(account);
    }

    /** No LIVE Kotak connection in this build (8 Nov 2026: DRY_RUN only, its gates first). */
    @Override
    public Live live(BrokerAccount account, FollowerRisk.LiveLimits limits) {
        return null;
    }

    private DryRun storedMarket(BrokerAccount account) throws IOException {
        KotakSimulator sim = new KotakSimulator(0, "sim-consumer-key", 3_000);
        sim.funds(simulatedBrokerFunds);
        KotakInstruments instruments = new KotakInstruments(List.of());
        KotakGateway gateway = simulatorGateway(sim, instruments);
        Market market = new Market(sim, instruments, dataSource);
        seedHoldings(account, sim, market);
        return new DryRun(gateway, market, "Kotak simulator " + sim.baseUri(), sim);
    }

    private DryRun brokerMarket(BrokerAccount account) throws IOException {
        KotakSimulator sim = new KotakSimulator(0, "sim-consumer-key", 3_000);
        sim.funds(UNLIMITED_FUNDS);
        KotakInstruments simInstruments = new KotakInstruments(List.of());
        KotakGateway simulator = simulatorGateway(sim, simInstruments);
        Market listings = new Market(sim, simInstruments, dataSource);
        seedHoldings(account, sim, listings);
        KotakReads reads = new KotakReads(account);
        ReadLiveConnection connection = new ReadLiveConnection(reads, simulator, q -> book(q, reads, sim, simInstruments),
                sim::usedMargin);
        return new DryRun(connection, o -> { }, "Kotak simulator " + sim.baseUri() + ", funds and scrip master read from "
                + "Kotak, book from our live capture", sim);
    }

    private static KotakGateway simulatorGateway(KotakSimulator sim, KotakInstruments instruments) {
        KotakConfig config = KotakConfig.simulator(sim.baseUri());
        KotakHttp http = new KotakHttp(config);
        http.session("dry-run", "dry-run");
        return new KotakGateway(config, http, instruments, sim::book);
    }

    /** The account's open positions, held by the new simulator (a restart: the broker would still hold them). */
    private void seedHoldings(BrokerAccount account, KotakSimulator sim, Market market) {
        for (Map<String, Object> pos : new JdbcTemplate(dataSource).queryForList("select p.instrument_key, "
                + "p.instrument_token, p.symbol, p.underlying, p.quantity_open, p.average_entry, d.lot_size from exec.position p "
                + "join exec.trade_intent i on i.id = p.entry_trade_intent_id join exec.trade_batch b on b.id = i.trade_batch_id "
                + "join exec.signal_decision d on d.id = b.signal_decision_id where p.broker_account_id = ? "
                + "and p.status = 'OPEN' and p.quantity_open > 0", account.id())) {
            String key = InstrumentKeys.normalize((String) pos.get("instrument_key"),
                    ((Number) pos.get("instrument_token")).longValue());
            market.list(key, (String) pos.get("symbol"), (String) pos.get("underlying"),
                    ((Number) pos.get("lot_size")).intValue());
            if (sim.byKey(key) != null) {
                sim.holding(key, ((Number) pos.get("quantity_open")).longValue(),
                        ((Number) pos.get("average_entry")).doubleValue());
            }
        }
    }

    /**
     * A live quote becomes the simulator's book (as {@link ZerodhaDryRunAdapter}'s): the contract is listed first, as
     * Kotak's checked scrip master names it, when the simulator does not have it (not in the checked list = refused);
     * what our simulated fills took in the last {@link ZerodhaDryRunAdapter#TAKEN_MEMORY} is taken off the levels.
     */
    private static Quote book(Quote q, KotakReads reads, KotakSimulator sim, KotakInstruments simInstruments) {
        String key = q.instrumentKey();
        if (sim.byKey(key) == null) {
            KotakInstruments.Contract c = reads.contract(key);
            sim.list(new KotakSimulator.Listing(c.exchangeToken(), c.segment(), c.tradingSymbol(), c.name(), c.expiry(),
                    c.strike(), c.type(), c.lotSize(), c.tickSize()));
            simInstruments.add(c);
        }
        List<Quote.Level> bids = !q.bids().isEmpty() ? q.bids()
                : q.hasBid() ? List.of(new Quote.Level(q.bid(), q.bidQuantity())) : List.of();
        List<Quote.Level> asks = !q.asks().isEmpty() ? q.asks()
                : q.hasAsk() ? List.of(new Quote.Level(q.ask(), q.askQuantity())) : List.of();
        Instant since = Instant.now().minus(ZerodhaDryRunAdapter.TAKEN_MEMORY);
        List<Quote.Level> bidsLeft = ZerodhaDryRunAdapter.less(bids, sim.taken(key, "SELL", since));
        List<Quote.Level> asksLeft = ZerodhaDryRunAdapter.less(asks, sim.taken(key, "BUY", since));
        sim.depth(key, bidsLeft, asksLeft, q.last());
        Quote.Level bid = bidsLeft.isEmpty() ? new Quote.Level(0, 0) : bidsLeft.getFirst();
        Quote.Level ask = asksLeft.isEmpty() ? new Quote.Level(0, 0) : asksLeft.getFirst();
        return new Quote(key, bid.price(), bid.quantity(), ask.price(), ask.quantity(), q.last(), q.time(), bidsLeft,
                asksLeft);
    }

    /**
     * The account's real Kotak session, read only: the consumer key, trade token, sid and base URL are looked up on every
     * call (a login during the day takes effect at once; none = {@link Kind#SESSION}); the scrip master is loaded once a
     * day and checked against our contract master. Quotes come from {@code liveQuotes}, never from Kotak. Order calls
     * are not offered (and {@link ReadLiveConnection} refuses them as well).
     */
    private final class KotakReads implements BrokerConnection {

        private final BrokerAccount account;
        private final KotakInstruments instruments = new KotakInstruments(List.of());
        private String consumerKey;
        private URI baseUrl;
        private KotakHttp http;
        private KotakGateway gateway;
        private LocalDate loadedDay;
        private Instant lastAttempt = Instant.EPOCH;

        KotakReads(BrokerAccount account) {
            this.account = account;
        }

        KotakInstruments.Contract contract(String key) {
            gateway();
            return instruments.byInstrumentKey(key).orElseThrow(() -> new BrokerException(Kind.TERMINAL, "instrument "
                    + key + " is not in Kotak's checked scrip master"));
        }

        private synchronized KotakGateway gateway() {
            BrokerSessions.Access access = sessions.access(account).orElseThrow(() -> new BrokerException(Kind.SESSION,
                    "no Kotak session for " + account.displayName() + " today: log in"));
            String sid = access.extra().get(SID), base = access.extra().get(BASE_URL);
            if (sid == null || sid.isBlank() || base == null || base.isBlank()) {
                throw new BrokerException(Kind.SESSION, "the Kotak session of " + account.displayName()
                        + " has no sid or base URL: log in again");
            }
            URI uri;
            try {
                uri = URI.create(base.replaceAll("/+$", ""));
                if (!KotakConfig.trustedBase(uri)) {
                    throw new IllegalArgumentException("untrusted");
                }
            } catch (IllegalArgumentException e) {
                throw new BrokerException(Kind.SESSION, "the Kotak session of " + account.displayName()
                        + " names a base URL that is not Kotak's: log in again");
            }
            if (gateway == null || !uri.equals(baseUrl) || !access.apiKey().equals(consumerKey)) {
                KotakConfig config = KotakConfig.session(access.apiKey(), uri);
                http = new KotakHttp(config);
                gateway = new KotakGateway(config, http, instruments, liveQuotes);
                baseUrl = uri;
                consumerKey = access.apiKey();
                loadedDay = null;
            }
            http.session(access.accessToken(), sid);
            loadToday();
            return gateway;
        }

        /** Today's scrip master, once a day (a failure is retried after a minute; the old day's list is not kept). */
        private void loadToday() {
            LocalDate today = LocalDate.now(IST);
            if (today.equals(loadedDay) || Instant.now().isBefore(lastAttempt.plus(RELOAD_RETRY))) {
                return;
            }
            lastAttempt = Instant.now();
            try {
                KotakInstruments fresh = KotakInstruments.load(http);
                KotakMappingCheck.Result check = KotakMappingCheck.apply(fresh, new JdbcTemplate(dataSource), today);
                instruments.replaceWith(fresh);
                loadedDay = today;
                if (check.mismatched().isEmpty()) {
                    log.info("account {} ({}): Kotak scrip master for {}: {} listed; {}", account.id(),
                            account.displayName(), today, fresh.size(), check.summary());
                } else {
                    log.error("account {} ({}): Kotak scrip master for {}: {}", account.id(), account.displayName(), today,
                            check.summary());
                    new KillSwitches(dataSource).audit(account, "execution", "INSTRUMENT_MAPPING_MISMATCH",
                            Map.of("broker", "KOTAK_NEO", "checked", check.checked(), "mismatched", check.mismatched()));
                }
            } catch (IOException | RuntimeException e) {
                if (e instanceof BrokerException b && b.kind() == Kind.SESSION) {
                    throw b;
                }
                if (!today.equals(loadedDay)) {
                    instruments.replaceWith(new KotakInstruments(List.of()));      // never yesterday's tokens
                }
                log.warn("account {} ({}): Kotak scrip master not loaded ({}); orders are refused until it is",
                        account.id(), account.displayName(), e.getMessage());
            }
        }

        @Override
        public String name() {
            return "KOTAK_NEO";
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
            throw refused("poll");
        }

        @Override
        public void onUpdate(Consumer<OrderUpdate> listener) {
            throw refused("onUpdate");
        }

        @Override
        public void place(OrderRequest request) {
            throw refused("place");
        }

        @Override
        public void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
            throw refused("modify");
        }

        @Override
        public void cancel(String clientOrderId) {
            throw refused("cancel");
        }

        private IllegalStateException refused(String call) {
            return new IllegalStateException(call + " not offered: Kotak has no LIVE connection in this build (reads only)");
        }
    }

    /**
     * Before each order of a STORED DRY_RUN: the contract is listed (simulator and the gateway's map, by the exchange
     * token in our key), the simulator's clock is the decision's time, and its book is the stored bid and ask of that
     * moment (own capture md.option_tick, the last minute), else the order's price on both sides; deep.
     */
    static final class Market implements Consumer<FollowerVenue.OrderSpec> {

        private static final long DEPTH_LOTS = 100_000;
        private final KotakSimulator sim;
        private final KotakInstruments instruments;
        private final JdbcTemplate jdbc;

        Market(KotakSimulator sim, KotakInstruments instruments, DataSource dataSource) {
            this.sim = sim;
            this.instruments = instruments;
            this.jdbc = new JdbcTemplate(dataSource);
        }

        void list(String key, String symbol, String underlying, int lotSize) {
            String segment = KotakInstruments.segment(key);
            if (segment == null || sim.byKey(key) != null) {
                return;
            }
            long token = Long.parseLong(key.substring(key.indexOf('|') + 1));
            String tradingSymbol = symbol.replaceAll("[^A-Za-z0-9]", "");
            KotakSimulator.Listing l = new KotakSimulator.Listing(token, segment, tradingSymbol, underlying, null, 0, "CE",
                    lotSize, 0.05);
            sim.list(l);
            instruments.add(l.contract());
        }

        @Override
        public void accept(FollowerVenue.OrderSpec o) {
            String key = o.instrumentKey();
            if (KotakInstruments.segment(key) == null) {
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
