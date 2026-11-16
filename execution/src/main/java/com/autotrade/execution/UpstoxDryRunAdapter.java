package com.autotrade.execution;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * LIVE ({@link #live}, production gaps C1, 16 Nov 2026; built and tested against the simulator, not reachable while
 * Upstox is not in {@link LiveBrokers}): the member's own session read on every call, orders to Upstox through the
 * LIVE config ({@link UpstoxConfig#live}, the only one besides a simulator's whose gateway sends), behind a
 * {@link LiveGuard} with the risk file's {@code live} limits and the lot size of today's checked instrument file; one
 * gateway for the route's life (a new token is set on it, never a new gateway), so its listeners, placed tags and last
 * reports survive a login during the day. Only from the account's own egress address ({@link AccountEgress}; Upstox
 * takes orders only from the user's registered IP), and never for zt-tiger-v2's Upstox user ({@link #ZT_UPSTOX_USER}:
 * registering a static IP for it would log zt out) or another denied user. The order budget is the Upstox user's,
 * shared by every gateway of that user. LIVE needs the READ_LIVE sessions, the static IP and the canary of the plan
 * first.
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
    private final DailyFile files = new DailyFile();
    private final ReadLiveProbe.Settings probe;
    private final UpstoxConfig liveConfig;
    private final AccountEgress egress;
    private final Set<String> deniedUsers;

    /**
     * zt-tiger-v2's Upstox user: never a LIVE account here, whatever the configuration (as UpstoxDenyList in the app;
     * enforced again at {@link #live}, 16 Nov 2026).
     */
    public static final String ZT_UPSTOX_USER = "BM6462";

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
        this(dataSource, simulatedBrokerFunds, sessions, upstox, liveQuotes, ReadLiveProbe.Settings.OFF);
    }

    /** @param probe how often a READ_LIVE route's {@link ReadLiveProbe} reads the real books, and where it saves them */
    public UpstoxDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds, BrokerSessions sessions,
                               UpstoxConfig upstox, Function<String, Quote> liveQuotes, ReadLiveProbe.Settings probe) {
        this(dataSource, simulatedBrokerFunds, sessions, upstox, liveQuotes, probe, UpstoxConfig.live(),
                AccountEgress.NONE, Set.of());
    }

    /**
     * @param liveConfig  the LIVE connection's config ({@link UpstoxConfig#live}; {@link UpstoxConfig#liveSimulator} in
     *                    the LIVE-against-simulator tests); must be of the LIVE kind
     * @param egress      the accounts' source addresses and the static-IP LIVE gate (production gaps B1/B2)
     * @param deniedUsers Upstox users never switched LIVE here (UpstoxDenyList's; {@link #ZT_UPSTOX_USER} always)
     */
    public UpstoxDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds, BrokerSessions sessions,
                               UpstoxConfig upstox, Function<String, Quote> liveQuotes, ReadLiveProbe.Settings probe,
                               UpstoxConfig liveConfig, AccountEgress egress, java.util.Collection<String> deniedUsers) {
        if (liveConfig == null || !liveConfig.isLive()) {
            throw new IllegalArgumentException("the Upstox LIVE connection takes a LIVE config (UpstoxConfig.live)");
        }
        if (upstox.ordersAllowed()) {
            throw new IllegalArgumentException("the READ_LIVE reads take a read-only config (UpstoxConfig.real or at)");
        }
        this.probe = probe == null ? ReadLiveProbe.Settings.OFF : probe;
        this.dataSource = dataSource;
        this.simulatedBrokerFunds = simulatedBrokerFunds;
        this.sessions = sessions;
        this.upstox = upstox;
        this.liveQuotes = liveQuotes;
        this.liveConfig = liveConfig;
        this.egress = egress == null ? AccountEgress.NONE : egress;
        Set<String> denied = new java.util.TreeSet<>();
        denied.add(ZT_UPSTOX_USER);
        if (deniedUsers != null) {
            deniedUsers.stream().filter(java.util.Objects::nonNull).map(u -> u.trim().toUpperCase(java.util.Locale.ROOT))
                    .filter(u -> !u.isEmpty()).forEach(denied::add);
        }
        this.deniedUsers = Set.copyOf(denied);
    }

    @Override
    public String egressRefusal(BrokerAccount account) {
        return egress.refusal(account);
    }

    @Override
    public BrokerType broker() {
        return BrokerType.UPSTOX;
    }

    /**
     * The product of our Upstox orders: its config's ({@link UpstoxConfig#INTRADAY}, I, for the reads and the simulator);
     * checked against the broker rules at the worker's start (16 Nov 2026, A1).
     */
    @Override
    public String product() {
        return upstox.product();
    }

    @Override
    public DryRun dryRun(BrokerAccount account, OrderRules rules) throws IOException {
        return rules.brokerMarket() ? brokerMarket(account) : storedMarket(account);
    }

    /**
     * The LIVE Upstox connection (production gaps C1, 16 Nov 2026), behind its {@link LiveGuard}. Refused: without the
     * account's verified egress address (B2), without an Upstox client pinned on the account, and for a denied Upstox
     * user (zt-tiger-v2's, always).
     */
    @Override
    public Live live(BrokerAccount account, FollowerRisk.LiveLimits limits) {
        String refusal = egress.liveRefusal(account);
        if (refusal != null) {
            return Live.refused(refusal);
        }
        String user = pinnedUser(account);
        if (user == null) {
            return Live.refused(account.displayName() + " has no Upstox client pinned (log in once first)");
        }
        if (deniedUsers.contains(user.trim().toUpperCase(java.util.Locale.ROOT))) {
            log.error("account {} ({}): LIVE refused: a denied Upstox user (zt-tiger-v2's or configured)", account.id(),
                    account.displayName());
            return Live.refused("Upstox client " + user + " is never traded LIVE here (zt-tiger-v2's or denied)");
        }
        UpstoxSession upstoxLive = new UpstoxSession(account, liveConfig, user);
        Thread.ofVirtual().name("upstox-live-warmup-" + account.id()).start(upstoxLive::warmUp);   // off the orders
        return new Live(new LiveGuard(upstoxLive, limits, upstoxLive::lotSize), "LIVE: Upstox "
                + liveConfig.orderBase().getHost() + " from " + egress.configured(account.id())
                .map(java.net.InetAddress::getHostAddress).orElse("the default route") + ", product "
                + liveConfig.product() + ", at most " + limits.maxLotsPerOrder() + " lot(s) a buy and "
                + limits.maxOpenPositions() + " contracts, book from our live capture");
    }

    /** The Upstox client (UCC) pinned on the account (the login and the session source check the session against it). */
    private String pinnedUser(BrokerAccount account) {
        List<String> user = new JdbcTemplate(dataSource).queryForList("select external_broker_user_id from "
                + "acct.broker_account where id = ?", String.class, account.id());
        return user.isEmpty() || user.getFirst() == null || user.getFirst().isBlank() ? null : user.getFirst().trim();
    }

    private DryRun storedMarket(BrokerAccount account) throws IOException {
        UpstoxSimulator sim = new UpstoxSimulator(0, 3_000);
        try {
            sim.funds(simulatedBrokerFunds);
            UpstoxInstruments instruments = new UpstoxInstruments(List.of());
            UpstoxGateway gateway = simulatorGateway(sim, instruments);
            Market market = new Market(sim, instruments, dataSource);
            seedHoldings(account, sim, market);
            return new DryRun(gateway, market, "Upstox simulator " + sim.baseUri(), sim);
        } catch (RuntimeException e) {
            sim.close();                                                // the route did not open: nothing keeps it
            throw e;
        }
    }

    private DryRun brokerMarket(BrokerAccount account) throws IOException {
        UpstoxSimulator sim = new UpstoxSimulator(0, 3_000);
        UpstoxSession reads = null;
        ReadLiveProbe readProbe = null;
        try {
            sim.funds(UNLIMITED_FUNDS);
            UpstoxInstruments simInstruments = new UpstoxInstruments(List.of());
            UpstoxGateway simulator = simulatorGateway(sim, simInstruments);
            Market listings = new Market(sim, simInstruments, dataSource);
            seedHoldings(account, sim, listings);
            reads = new UpstoxSession(account, upstox, null);
            UpstoxSession r = reads;
            readProbe = new ReadLiveProbe(dataSource, account, "UPSTOX", reads, UpstoxGateway::knownStatus, probe,
                    () -> sessions.access(account).map(a -> java.util.Arrays.asList(a.apiKey(), a.accessToken()))
                            .orElse(List.of()));
            reads.tap(readProbe.tap());
            ReadLiveConnection connection = new ReadLiveConnection(reads, simulator, q -> book(q, r, sim, simInstruments),
                    sim::usedMargin);
            readProbe.start(reads::warmUp);
            ReadLiveProbe p = readProbe;
            return new DryRun(connection, o -> { }, "Upstox simulator " + sim.baseUri() + ", funds and instrument file read "
                + "from Upstox, book from our live capture", sim, () -> {
                p.close();
                r.close();
            });
        } catch (RuntimeException e) {
            if (readProbe != null) {
                readProbe.close();
            }
            if (reads != null) {
                reads.close();
            }
            sim.close();                                                // the route did not open: nothing keeps it
            throw e;
        }
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
    private static Quote book(Quote q, UpstoxSession reads, UpstoxSimulator sim, UpstoxInstruments simInstruments) {
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
     * everyone; tens of MB) by whichever account first needs them, through that account's own client (its egress
     * address, production gaps B1; the files are fetched without the token): a failure is retried after a minute, and
     * yesterday's list is never served.
     */
    private static final class DailyFile {

        private LocalDate day;
        private List<UpstoxInstruments.Contract> contracts = List.of();
        private Instant lastAttempt = Instant.EPOCH;

        synchronized List<UpstoxInstruments.Contract> today(LocalDate today, UpstoxHttp http) throws IOException {
            if (today.equals(day)) {
                return contracts;
            }
            if (Instant.now().isBefore(lastAttempt.plus(RELOAD_RETRY))) {
                throw new IOException("Upstox instrument file not loaded yet today (retried every minute)");
            }
            lastAttempt = Instant.now();
            contracts = List.copyOf(UpstoxInstruments.load(http, today).contracts());   // a stale file is refused
            day = today;
            return contracts;
        }
    }

    /**
     * The account's real Upstox session: the bearer token is looked up on every call (a login during the day takes effect
     * at once; none = {@link Kind#SESSION}) and set on the one gateway this session keeps for its life, so its listeners,
     * placed tags and last reports survive a new login; every call leaves from the account's egress address (checked on
     * every call: not on this host = SESSION). The day's instrument file is checked against our contract master for this
     * account once a day. Quotes come from {@code liveQuotes}, never from Upstox.
     * <ul>
     *   <li>Reads (a read config, READ_LIVE): order calls are not offered (and {@link ReadLiveConnection} and the gateway
     *       refuse them as well).</li>
     *   <li>LIVE (the LIVE config, production gaps C1): order calls go to Upstox, always behind the {@link LiveGuard}
     *       that {@link #live} puts in front, on the Upstox user's shared order budget.</li>
     * </ul>
     */
    private final class UpstoxSession implements BrokerConnection {

        private final BrokerAccount account;
        private final boolean orders;
        private final UpstoxInstruments instruments = new UpstoxInstruments(List.of());
        private final UpstoxHttp http;
        private final UpstoxGateway gateway;
        private final Object dayLock = new Object();
        private LocalDate loadedDay;
        private Instant lastAttempt = Instant.EPOCH;

        /** @param user the pinned Upstox client (LIVE: its order budget); null for reads */
        UpstoxSession(BrokerAccount account, UpstoxConfig config, String user) {
            this.account = account;
            this.orders = config.isLive();
            // the configured address, on this host or not: the registry refused the route if not, and every call checks
            UpstoxConfig own = config.withEgress(egress.configured(account.id()).orElse(null));
            this.http = new UpstoxHttp(own);
            this.gateway = new UpstoxGateway(own, http, instruments, liveQuotes, orders ? user : null);
        }

        void tap(java.util.function.BiConsumer<String, String> tap) {
            http.tap(tap);
        }

        /** The contract as the checked list has it (loaded here on first use when the warm-up has not). */
        UpstoxInstruments.Contract contract(String key) {
            gateway();
            loadToday();
            return instruments.byInstrumentKey(key).orElseThrow(() -> new BrokerException(Kind.TERMINAL, "instrument "
                    + key + " is not in Upstox's checked instrument list"));
        }

        /** The contract's lot size in today's checked list, 0 when it is not there (the LIVE guard refuses). */
        int lotSize(String instrumentKey) {
            gateway();
            loadToday();
            return instruments.byInstrumentKey(instrumentKey).map(UpstoxInstruments.Contract::lotSize).orElse(0);
        }

        private synchronized UpstoxGateway gateway() {
            BrokerSessions.Access access = sessions.access(account).orElseThrow(() -> new BrokerException(Kind.SESSION,
                    "no Upstox session for " + account.displayName() + " today: log in"));
            if (access.accessToken() == null || access.accessToken().isBlank()) {
                throw new BrokerException(Kind.SESSION, "the Upstox session of " + account.displayName()
                        + " has no token: log in again");
            }
            egress.address(account);                                    // on this host, or SESSION: nothing is sent
            http.session(access.accessToken());
            return gateway;                                             // no download under this lock
        }

        /** The gateway for an order call: LIVE only, with today's checked list (an exit of a contract needs it too). */
        private UpstoxGateway orderGateway(String call) {
            if (!orders) {
                throw refused(call);
            }
            UpstoxGateway g = gateway();
            loadToday();
            return g;
        }

        /** The route's first work, on its own thread: today's list, so no order waits for the download. */
        void warmUp() {
            try {
                gateway();
                loadToday();
            } catch (BrokerException e) {
                log.info("account {} ({}): Upstox instrument file not warmed up: {}", account.id(), account.displayName(),
                        e.getMessage());
            }
        }

        @Override
        public void close() {
            http.close();
        }

        /**
         * Today's checked list, once a day, outside the session's lock (the shared download has its own): a failure is
         * retried after a minute; the old day's list is not kept.
         */
        private void loadToday() {
            synchronized (dayLock) {
                loadTodayLocked();
            }
        }

        private void loadTodayLocked() {
            LocalDate today = LocalDate.now(IST);
            if (today.equals(loadedDay) || Instant.now().isBefore(lastAttempt.plus(RELOAD_RETRY))) {
                return;
            }
            lastAttempt = Instant.now();
            try {
                UpstoxInstruments fresh = new UpstoxInstruments(files.today(today, http));
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

        /** Every order of ours is intraday ("I"); a hand order of another product is not counted as held. */
        @Override
        public String product() {
            return gateway.product();
        }

        @Override
        public BrokerCapabilities capabilities() {
            return gateway.capabilities();
        }

        @Override
        public double availableFunds() {
            return gateway().availableFunds();
        }

        /** Our live capture: no Upstox call and no session needed (code review 8 Nov 2026). */
        @Override
        public Quote quote(String instrumentKey) {
            Quote q = liveQuotes.apply(instrumentKey);
            if (q == null) {
                throw new BrokerException(Kind.RETRYABLE, "no quote for " + instrumentKey);
            }
            return q;
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
        public Map<String, Long> carriedIn() {
            return gateway().carriedIn();
        }

        @Override
        public int poll() {
            return orderGateway("poll").poll();
        }

        @Override
        public void onUpdate(Consumer<OrderUpdate> listener) {
            if (!orders) {
                throw refused("onUpdate");
            }
            gateway.onUpdate(listener);                                 // one gateway for the session's life
        }

        @Override
        public void place(OrderRequest request) {
            orderGateway("place").place(request);
        }

        @Override
        public void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
            orderGateway("modify").modify(clientOrderId, quantity, limitPrice, triggerPrice);
        }

        @Override
        public void cancel(String clientOrderId) {
            orderGateway("cancel").cancel(clientOrderId);
        }

        private IllegalStateException refused(String call) {
            return new IllegalStateException(call + " not offered: a READ_LIVE Upstox connection (reads only)");
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
