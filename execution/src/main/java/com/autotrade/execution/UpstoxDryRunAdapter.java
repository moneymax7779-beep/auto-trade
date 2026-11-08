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
import com.autotrade.upstox.orders.UpstoxConfig;
import com.autotrade.upstox.orders.UpstoxGateway;
import com.autotrade.upstox.orders.UpstoxHttp;
import com.autotrade.upstox.orders.UpstoxInstruments;
import com.autotrade.upstox.orders.UpstoxSimulator;

/**
 * Upstox in DRY_RUN (live adapters plan step B2): the Upstox adapter ({@link UpstoxGateway}) against a local
 * {@link UpstoxSimulator} of the account's own (127.0.0.1, random port), given the account's open positions at start. Its
 * book comes from one of two places (the risk file's {@code orders.dry_run_market}):
 * <ul>
 *   <li>STORED: before each order, the stored bid and ask of that moment (own capture), deep, as for Kite and Kotak;</li>
 *   <li>BROKER (step C, READ_LIVE): the account's real Upstox session (the bearer token of the member's own Upstox app,
 *       from the day's login; never zt-tiger-v2's) is read for funds, and Upstox's instrument file of the day (public,
 *       read without the token, once a day for every account of this worker) is checked against our contract master by
 *       {@link UpstoxMappingCheck}. Upstox's quote API is not called in this build: the book is our own live capture of
 *       that moment ({@link CapturedQuotes}), and every such quote becomes the simulator's book before the order. Orders
 *       go only to the simulator: the real connection is read-only ({@link ReadLiveConnection}), and the gateway itself
 *       refuses any order call to Upstox's host.</li>
 * </ul>
 * There is no LIVE Upstox connection: {@link #live} returns null, so an account switched LIVE gets no route (and Upstox is
 * not in {@link LiveBrokers#LIVE}). LIVE needs the READ_LIVE sessions, the static IP and the canary of the plan first.
 */
public final class UpstoxDryRunAdapter implements BrokerAdapter {

    private static final Logger log = LoggerFactory.getLogger(UpstoxDryRunAdapter.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double UNLIMITED_FUNDS = 1e12;
    private static final Duration RELOAD_RETRY = Duration.ofMinutes(1);

    private final DataSource dataSource;
    private final double simulatedBrokerFunds;
    private final BrokerSessions sessions;
    private final UpstoxConfig upstox;
    private final Function<String, Quote> liveQuotes;
    private final DailyFile files;

    public UpstoxDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds) {
        this(dataSource, simulatedBrokerFunds, BrokerSessions.NONE);
    }

    /** @param sessions the accounts' real Upstox sessions (BROKER market only), read from Upstox's own hosts */
    public UpstoxDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds, BrokerSessions sessions) {
        this(dataSource, simulatedBrokerFunds, sessions, UpstoxConfig.real());
    }

    /** @param upstox where the real reads go (Upstox's hosts; a stand-in in tests) */
    public UpstoxDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds, BrokerSessions sessions,
                               UpstoxConfig upstox) {
        this(dataSource, simulatedBrokerFunds, sessions, upstox, new CapturedQuotes(dataSource));
    }

    /** @param liveQuotes the book of now by our key, for BROKER-market routes (our live capture; a stand-in in tests) */
    public UpstoxDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds, BrokerSessions sessions,
                               UpstoxConfig upstox, Function<String, Quote> liveQuotes) {
        this.dataSource = dataSource;
        this.simulatedBrokerFunds = simulatedBrokerFunds;
        this.sessions = sessions;
        this.upstox = upstox;
        this.liveQuotes = liveQuotes;
        this.files = new DailyFile(new UpstoxHttp(upstox));         // no session: the files are public
    }

    @Override
    public BrokerType broker() {
        return BrokerType.UPSTOX;
    }

    @Override
    public DryRun dryRun(BrokerAccount account, OrderRules rules) throws IOException {
        return rules.brokerMarket() ? brokerMarket(account) : storedMarket(account);
    }

    /** No LIVE Upstox connection in this build (8 Nov 2026: DRY_RUN only, its gates first). */
    @Override
    public Live live(BrokerAccount account, FollowerRisk.LiveLimits limits) {
        return null;
    }

    private DryRun storedMarket(BrokerAccount account) throws IOException {
        UpstoxSimulator sim = new UpstoxSimulator(0, 3_000);
        sim.funds(simulatedBrokerFunds);
        UpstoxInstruments instruments = new UpstoxInstruments(List.of());
        UpstoxGateway gateway = simulatorGateway(sim, instruments);
        Market market = new Market(sim, instruments, dataSource);
        seedHoldings(account, sim, market);
        return new DryRun(gateway, market, "Upstox simulator " + sim.baseUri(), sim);
    }

    private DryRun brokerMarket(BrokerAccount account) throws IOException {
        UpstoxSimulator sim = new UpstoxSimulator(0, 3_000);
        sim.funds(UNLIMITED_FUNDS);
        UpstoxInstruments simInstruments = new UpstoxInstruments(List.of());
        UpstoxGateway simulator = simulatorGateway(sim, simInstruments);
        Market listings = new Market(sim, simInstruments, dataSource);
        seedHoldings(account, sim, listings);
        UpstoxReads reads = new UpstoxReads(account);
        ReadLiveConnection connection = new ReadLiveConnection(reads, simulator, q -> book(q, reads, sim, simInstruments),
                sim::usedMargin);
        return new DryRun(connection, o -> { }, "Upstox simulator " + sim.baseUri() + ", funds and instrument file read "
                + "from Upstox, book from our live capture", sim);
    }

    private static UpstoxGateway simulatorGateway(UpstoxSimulator sim, UpstoxInstruments instruments) {
        UpstoxConfig config = UpstoxConfig.simulator(sim.baseUri());
        UpstoxHttp http = new UpstoxHttp(config);
        http.session("dry-run");
        return new UpstoxGateway(config, http, instruments, sim::book);
    }

    /** The account's open positions, held by the new simulator (a restart: the broker would still hold them). */
    private void seedHoldings(BrokerAccount account, UpstoxSimulator sim, Market market) {
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
     * Upstox's checked instrument file has it, when the simulator does not have it (not in the checked list = refused);
     * what our simulated fills took in the last {@link ZerodhaDryRunAdapter#TAKEN_MEMORY} is taken off the levels.
     */
    private static Quote book(Quote q, UpstoxReads reads, UpstoxSimulator sim, UpstoxInstruments simInstruments) {
        String key = q.instrumentKey();
        if (sim.byKey(key) == null) {
            UpstoxInstruments.Contract c = reads.contract(key);
            sim.list(new UpstoxSimulator.Listing(c.instrumentKey(), c.tradingSymbol(), c.underlying(), c.expiry(),
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
     * Upstox's instrument files of the day, read once for every account of this worker (they are public and the same for
     * everyone; tens of MB): a failure is retried after a minute, and yesterday's list is never served.
     */
    private static final class DailyFile {

        private final UpstoxHttp http;
        private LocalDate day;
        private List<UpstoxInstruments.Contract> contracts = List.of();
        private Instant lastAttempt = Instant.EPOCH;

        DailyFile(UpstoxHttp http) {
            this.http = http;
        }

        synchronized List<UpstoxInstruments.Contract> today(LocalDate today) throws IOException {
            if (today.equals(day)) {
                return contracts;
            }
            if (Instant.now().isBefore(lastAttempt.plus(RELOAD_RETRY))) {
                throw new IOException("Upstox instrument file not loaded yet today (retried every minute)");
            }
            lastAttempt = Instant.now();
            contracts = List.copyOf(UpstoxInstruments.load(http).contracts());
            day = today;
            return contracts;
        }
    }

    /**
     * The account's real Upstox session, read only: the bearer token is looked up on every call (a login during the day
     * takes effect at once; none = {@link Kind#SESSION}); the day's instrument file is checked against our contract master
     * for this account once a day. Quotes come from {@code liveQuotes}, never from Upstox. Order calls are not offered
     * (and {@link ReadLiveConnection} and the gateway refuse them as well).
     */
    private final class UpstoxReads implements BrokerConnection {

        private final BrokerAccount account;
        private final UpstoxInstruments instruments = new UpstoxInstruments(List.of());
        private final UpstoxHttp http = new UpstoxHttp(upstox);
        private final UpstoxGateway gateway = new UpstoxGateway(upstox, http, instruments, liveQuotes);
        private LocalDate loadedDay;
        private Instant lastAttempt = Instant.EPOCH;

        UpstoxReads(BrokerAccount account) {
            this.account = account;
        }

        UpstoxInstruments.Contract contract(String key) {
            gateway();
            return instruments.byInstrumentKey(key).orElseThrow(() -> new BrokerException(Kind.TERMINAL, "instrument "
                    + key + " is not in Upstox's checked instrument list"));
        }

        private synchronized UpstoxGateway gateway() {
            BrokerSessions.Access access = sessions.access(account).orElseThrow(() -> new BrokerException(Kind.SESSION,
                    "no Upstox session for " + account.displayName() + " today: log in"));
            if (access.accessToken() == null || access.accessToken().isBlank()) {
                throw new BrokerException(Kind.SESSION, "the Upstox session of " + account.displayName()
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
                UpstoxInstruments fresh = new UpstoxInstruments(files.today(today));
                UpstoxMappingCheck.Result check = UpstoxMappingCheck.apply(fresh, new JdbcTemplate(dataSource), today);
                instruments.replaceWith(fresh);
                loadedDay = today;
                if (check.mismatched().isEmpty()) {
                    log.info("account {} ({}): Upstox instrument file for {}: {} listed; {}", account.id(),
                            account.displayName(), today, fresh.size(), check.summary());
                } else {
                    log.error("account {} ({}): Upstox instrument file for {}: {}", account.id(), account.displayName(),
                            today, check.summary());
                    new KillSwitches(dataSource).audit(account, "execution", "INSTRUMENT_MAPPING_MISMATCH",
                            Map.of("broker", "UPSTOX", "checked", check.checked(), "mismatched", check.mismatched()));
                }
            } catch (IOException | RuntimeException e) {
                if (!today.equals(loadedDay)) {
                    instruments.replaceWith(new UpstoxInstruments(List.of()));      // never yesterday's contracts
                }
                log.warn("account {} ({}): Upstox instrument file not loaded ({}); orders are refused until it is",
                        account.id(), account.displayName(), e.getMessage());
            }
        }

        @Override
        public String name() {
            return "UPSTOX";
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
            return new IllegalStateException(call + " not offered: Upstox has no LIVE connection in this build (reads only)");
        }
    }

    /**
     * Before each order of a STORED DRY_RUN: the contract is listed (simulator and the gateway's list, by our key, which
     * is Upstox's), the simulator's clock is the decision's time, and its book is the stored bid and ask of that moment
     * (own capture md.option_tick, the last minute), else the order's price on both sides; deep.
     */
    static final class Market implements Consumer<FollowerVenue.OrderSpec> {

        private static final long DEPTH_LOTS = 100_000;
        private final UpstoxSimulator sim;
        private final UpstoxInstruments instruments;
        private final JdbcTemplate jdbc;

        Market(UpstoxSimulator sim, UpstoxInstruments instruments, DataSource dataSource) {
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
            UpstoxSimulator.Listing l = new UpstoxSimulator.Listing(key, symbol, underlying, null, 0, "CE", lotSize, 0.05);
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
