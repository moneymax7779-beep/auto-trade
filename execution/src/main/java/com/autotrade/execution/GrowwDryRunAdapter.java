package com.autotrade.execution;

import java.io.IOException;
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
import com.autotrade.groww.GrowwConfig;
import com.autotrade.groww.GrowwGateway;
import com.autotrade.groww.GrowwHttp;
import com.autotrade.groww.GrowwInstruments;
import com.autotrade.groww.GrowwSimulator;

/**
 * Groww in DRY_RUN (live adapters plan step B3): the Groww adapter ({@link GrowwGateway}) against a local
 * {@link GrowwSimulator} of the account's own (127.0.0.1, random port), given the account's open positions at start. Its
 * book comes from one of two places (the risk file's {@code orders.dry_run_market}):
 * <ul>
 *   <li>STORED: before each order, the stored bid and ask of that moment (own capture), deep, as for Kite, Kotak and
 *       Upstox;</li>
 *   <li>BROKER (step C, READ_LIVE): the account's real Groww session (the day's access token of the member's own Groww
 *       API key, from the approval login; a paid Groww API subscription) is read for funds, and Groww's instrument CSV of
 *       the day (public, read without the token, once a day for every account of this worker) is checked against our
 *       contract master by {@link GrowwMappingCheck}, which also proves or blocks the UNCONFIRMED reading of
 *       {@code exchange_token} as the exchange token. Groww's quote API is not called in this build: the book is our own live capture of
 *       that moment ({@link CapturedQuotes}), and every such quote becomes the simulator's book before the order. Orders
 *       go only to the simulator: the real connection is read-only ({@link ReadLiveConnection}), and the gateway itself
 *       refuses any order call to Groww's host.</li>
 * </ul>
 * There is no LIVE Groww connection: {@link #live} returns null, so an account switched LIVE gets no route (and Groww is
 * not in {@link LiveBrokers#LIVE}). LIVE needs the READ_LIVE sessions, the static IP and the canary of the plan first.
 */
public final class GrowwDryRunAdapter implements BrokerAdapter {

    private static final Logger log = LoggerFactory.getLogger(GrowwDryRunAdapter.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double UNLIMITED_FUNDS = 1e12;
    private static final Duration RELOAD_RETRY = Duration.ofMinutes(1);

    private final DataSource dataSource;
    private final double simulatedBrokerFunds;
    private final BrokerSessions sessions;
    private final GrowwConfig groww;
    private final Function<String, Quote> liveQuotes;
    private final DailyFile files;

    public GrowwDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds) {
        this(dataSource, simulatedBrokerFunds, BrokerSessions.NONE);
    }

    /** @param sessions the accounts' real Groww sessions (BROKER market only), read from Groww's own hosts */
    public GrowwDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds, BrokerSessions sessions) {
        this(dataSource, simulatedBrokerFunds, sessions, GrowwConfig.real());
    }

    /** @param groww where the real reads go (Groww's hosts; a stand-in in tests) */
    public GrowwDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds, BrokerSessions sessions,
                               GrowwConfig groww) {
        this(dataSource, simulatedBrokerFunds, sessions, groww, new CapturedQuotes(dataSource));
    }

    /** @param liveQuotes the book of now by our key, for BROKER-market routes (our live capture; a stand-in in tests) */
    public GrowwDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds, BrokerSessions sessions,
                               GrowwConfig groww, Function<String, Quote> liveQuotes) {
        this.dataSource = dataSource;
        this.simulatedBrokerFunds = simulatedBrokerFunds;
        this.sessions = sessions;
        this.groww = groww;
        this.liveQuotes = liveQuotes;
        this.files = new DailyFile(new GrowwHttp(groww));         // no session: the CSV is public
    }

    @Override
    public BrokerType broker() {
        return BrokerType.GROWW;
    }

    @Override
    public DryRun dryRun(BrokerAccount account, OrderRules rules) throws IOException {
        return rules.brokerMarket() ? brokerMarket(account) : storedMarket(account);
    }

    /** No LIVE Groww connection in this build (8 Nov 2026: DRY_RUN only, its gates first). */
    @Override
    public Live live(BrokerAccount account, FollowerRisk.LiveLimits limits) {
        return null;
    }

    private DryRun storedMarket(BrokerAccount account) throws IOException {
        GrowwSimulator sim = new GrowwSimulator(0, 3_000);
        sim.funds(simulatedBrokerFunds);
        GrowwInstruments instruments = new GrowwInstruments(List.of());
        GrowwGateway gateway = simulatorGateway(sim, instruments);
        Market market = new Market(sim, instruments, dataSource);
        seedHoldings(account, sim, market);
        return new DryRun(gateway, market, "Groww simulator " + sim.baseUri(), sim);
    }

    private DryRun brokerMarket(BrokerAccount account) throws IOException {
        GrowwSimulator sim = new GrowwSimulator(0, 3_000);
        sim.funds(UNLIMITED_FUNDS);
        GrowwInstruments simInstruments = new GrowwInstruments(List.of());
        GrowwGateway simulator = simulatorGateway(sim, simInstruments);
        Market listings = new Market(sim, simInstruments, dataSource);
        seedHoldings(account, sim, listings);
        GrowwReads reads = new GrowwReads(account);
        ReadLiveConnection connection = new ReadLiveConnection(reads, simulator, q -> book(q, reads, sim, simInstruments),
                sim::usedMargin);
        return new DryRun(connection, o -> { }, "Groww simulator " + sim.baseUri() + ", funds and instrument file read "
                + "from Groww, book from our live capture", sim);
    }

    private static GrowwGateway simulatorGateway(GrowwSimulator sim, GrowwInstruments instruments) {
        GrowwConfig config = GrowwConfig.simulator(sim.baseUri());
        GrowwHttp http = new GrowwHttp(config);
        http.session("dry-run");
        return new GrowwGateway(config, http, instruments, sim::book);
    }

    /** The account's open positions, held by the new simulator (a restart: the broker would still hold them). */
    private void seedHoldings(BrokerAccount account, GrowwSimulator sim, Market market) {
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
     * A live quote becomes the simulator's book (as {@link KotakDryRunAdapter}'s): the contract is listed first, as
     * Groww's checked instrument file has it, when the simulator does not have it (not in the checked list = refused);
     * what our simulated fills took in the last {@link ZerodhaDryRunAdapter#TAKEN_MEMORY} is taken off the levels.
     */
    private static Quote book(Quote q, GrowwReads reads, GrowwSimulator sim, GrowwInstruments simInstruments) {
        String key = q.instrumentKey();
        if (sim.byKey(key) == null) {
            GrowwInstruments.Contract c = reads.contract(key);
            sim.list(new GrowwSimulator.Listing(c.instrumentKey(), c.tradingSymbol(), c.underlying(), c.expiry(),
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
     * Groww's instrument CSV of the day, read once for every account of this worker (it is public and the same for
     * everyone; tens of MB): a failure is retried after a minute, and yesterday's list is never served.
     */
    private static final class DailyFile {

        private final GrowwHttp http;
        private LocalDate day;
        private List<GrowwInstruments.Contract> contracts = List.of();
        private Instant lastAttempt = Instant.EPOCH;

        DailyFile(GrowwHttp http) {
            this.http = http;
        }

        synchronized List<GrowwInstruments.Contract> today(LocalDate today) throws IOException {
            if (today.equals(day)) {
                return contracts;
            }
            if (Instant.now().isBefore(lastAttempt.plus(RELOAD_RETRY))) {
                throw new IOException("Groww instrument CSV not loaded yet today (retried every minute)");
            }
            lastAttempt = Instant.now();
            contracts = List.copyOf(GrowwInstruments.load(http).contracts());
            day = today;
            return contracts;
        }
    }

    /**
     * The account's real Groww session, read only: the bearer token is looked up on every call (a login during the day
     * takes effect at once; none = {@link Kind#SESSION}); the day's instrument file is checked against our contract master
     * for this account once a day. Quotes come from {@code liveQuotes}, never from Groww. Order calls are not offered
     * (and {@link ReadLiveConnection} and the gateway refuse them as well).
     */
    private final class GrowwReads implements BrokerConnection {

        private final BrokerAccount account;
        private final GrowwInstruments instruments = new GrowwInstruments(List.of());
        private final GrowwHttp http = new GrowwHttp(groww);
        private final GrowwGateway gateway = new GrowwGateway(groww, http, instruments, liveQuotes);
        private LocalDate loadedDay;
        private Instant lastAttempt = Instant.EPOCH;

        GrowwReads(BrokerAccount account) {
            this.account = account;
        }

        GrowwInstruments.Contract contract(String key) {
            gateway();
            return instruments.byInstrumentKey(key).orElseThrow(() -> new BrokerException(Kind.TERMINAL, "instrument "
                    + key + " is not in Groww's checked instrument list"));
        }

        private synchronized GrowwGateway gateway() {
            BrokerSessions.Access access = sessions.access(account).orElseThrow(() -> new BrokerException(Kind.SESSION,
                    "no Groww session for " + account.displayName() + " today: log in"));
            if (access.accessToken() == null || access.accessToken().isBlank()) {
                throw new BrokerException(Kind.SESSION, "the Groww session of " + account.displayName()
                        + " has no token: log in again");
            }
            http.session(access.accessToken());
            loadToday();
            return gateway;
        }

        /** Today's checked list, once a day (a failure is retried after a minute; the old day's list is not kept). */
        private void loadToday() {
            LocalDate today = LocalDate.now(IST);
            if (today.equals(loadedDay) || Instant.now().isBefore(lastAttempt.plus(RELOAD_RETRY))) {
                return;
            }
            lastAttempt = Instant.now();
            try {
                GrowwInstruments fresh = new GrowwInstruments(files.today(today));
                GrowwMappingCheck.Result check = GrowwMappingCheck.apply(fresh, new JdbcTemplate(dataSource), today);
                instruments.replaceWith(fresh);
                loadedDay = today;
                if (check.mismatched().isEmpty()) {
                    log.info("account {} ({}): Groww instrument CSV for {}: {} listed; {}", account.id(),
                            account.displayName(), today, fresh.size(), check.summary());
                } else {
                    log.error("account {} ({}): Groww instrument CSV for {}: {}", account.id(), account.displayName(),
                            today, check.summary());
                    new KillSwitches(dataSource).audit(account, "execution", "INSTRUMENT_MAPPING_MISMATCH",
                            Map.of("broker", "GROWW", "checked", check.checked(), "mismatched", check.mismatched()));
                }
            } catch (IOException | RuntimeException e) {
                if (!today.equals(loadedDay)) {
                    instruments.replaceWith(new GrowwInstruments(List.of()));      // never yesterday's contracts
                }
                log.warn("account {} ({}): Groww instrument CSV not loaded ({}); orders are refused until it is",
                        account.id(), account.displayName(), e.getMessage());
            }
        }

        @Override
        public String name() {
            return "GROWW";
        }

        @Override
        public BrokerCapabilities capabilities() {
            return gateway.capabilities();
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
            return new IllegalStateException(call + " not offered: Groww has no LIVE connection in this build (reads only)");
        }
    }

    /**
     * Before each order of a STORED DRY_RUN: the contract is listed (simulator and the gateway's list, by our key, with
     * our symbol as its trading symbol), the simulator's clock is the decision's time, and its book is the stored bid and ask of that moment
     * (own capture md.option_tick, the last minute), else the order's price on both sides; deep.
     */
    static final class Market implements Consumer<FollowerVenue.OrderSpec> {

        private static final long DEPTH_LOTS = 100_000;
        private final GrowwSimulator sim;
        private final GrowwInstruments instruments;
        private final JdbcTemplate jdbc;

        Market(GrowwSimulator sim, GrowwInstruments instruments, DataSource dataSource) {
            this.sim = sim;
            this.instruments = instruments;
            this.jdbc = new JdbcTemplate(dataSource);
        }

        private static boolean option(String key) {
            return key != null && (key.startsWith("NSE_FO|") || key.startsWith("BSE_FO|"));
        }

        void list(String key, String symbol, String underlying, int lotSize) {
            if (!option(key) || sim.byKey(key) != null) {
                return;
            }
            String tradingSymbol = symbol == null || symbol.isBlank() ? key : symbol;     // an order names its symbol
            GrowwSimulator.Listing l = new GrowwSimulator.Listing(key, tradingSymbol, underlying, null, 0, "CE", lotSize,
                    0.05);
            sim.list(l);
            instruments.add(l.contract());
        }

        @Override
        public void accept(FollowerVenue.OrderSpec o) {
            String key = o.instrumentKey();
            if (!option(key)) {
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
