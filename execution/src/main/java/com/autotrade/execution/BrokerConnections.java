package com.autotrade.execution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.TradeMode;
import com.autotrade.broker.BrokerConnection;

/**
 * The broker registry (live plan section 2a): which venue each follower account trades through.
 * <ul>
 *   <li>PAPER, and DRY_RUN on a risk file without order rules (v1, v2): simulated fills ({@link SimulatedVenue}).</li>
 *   <li>DRY_RUN with order rules (v3+): the account's broker adapter ({@link BrokerAdapter}; this build has
 *       {@link ZerodhaDryRunAdapter}: the Kite adapter against a local simulator of its own; and, when the worker is
 *       given it, {@link KotakDryRunAdapter}: the Kotak Neo adapter against its own simulator, DRY_RUN only) under a
 *       {@link BrokerVenue}. A broker without an adapter is refused.</li>
 *   <li>LIVE (the Kite pilot, user 2 Nov 2026): only an account the user switched LIVE ({@code live_trading_enabled}),
 *       on a risk file with {@code live} limits (v7+) and order rules, of a broker in {@link LiveBrokers}, through an
 *       adapter that offers LIVE: its real broker connection behind a {@link LiveGuard}, under the same
 *       {@link BrokerVenue}. Anything else LIVE is refused, so nothing is sent. The day's login and the registered
 *       static IP are the user's.</li>
 *   <li>Egress (production gaps B1/B2, 16 Nov 2026): an account whose configured source address is not on this host gets
 *       no route at all (its adapter's {@link BrokerAdapter#egressRefusal}); a LIVE connection of a broker that needs a
 *       registered static IP is refused by the adapter without one ({@link AccountEgress#liveRefusal}).</li>
 * </ul>
 */
public final class BrokerConnections implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrokerConnections.class);

    /** An account's venue, or why it has none ({@code venue} null: the account is not run). */
    public record Route(FollowerVenue venue, BrokerConnection connection, String description) {
    }

    private final DataSource dataSource;
    private final double simulatedBrokerFunds;
    private final FreezeQuantities freeze;
    private final Map<BrokerType, BrokerAdapter> adapters = new java.util.EnumMap<>(BrokerType.class);
    private final Map<Long, Route> routes = new ConcurrentHashMap<>();
    private final Map<Long, BrokerAdapter.DryRun> opened = new ConcurrentHashMap<>();
    /** Read-only connections of idle accounts ({@link #probeOnly}): never a venue, so nothing can be sent through them. */
    private final Map<Long, BrokerAdapter.DryRun> probes = new ConcurrentHashMap<>();
    /**
     * LIVE connections opened here, closed when their route is dropped (16 Nov 2026: Kotak's, Upstox's and Groww's hold
     * HTTP clients of their own; Kite's has nothing to close).
     */
    private final Map<Long, BrokerConnection> liveOpened = new ConcurrentHashMap<>();
    /**
     * The brokers configured LIVE ({@link LiveBrokers}; review 16 Nov 2026: a LIVE route opens only for a broker that is
     * both configured LIVE and whose adapter offers LIVE, so taking a broker out of the setting and restarting stops its
     * LIVE accounts, not only the switch pages). Every broker until the worker sets it (tests).
     */
    private volatile java.util.function.Predicate<BrokerType> liveBrokers = b -> true;

    /** The registry with this build's adapters (Zerodha). */
    public BrokerConnections(DataSource dataSource, double simulatedBrokerFunds) {
        this(dataSource, simulatedBrokerFunds, List.of(new ZerodhaDryRunAdapter(dataSource, simulatedBrokerFunds)));
    }

    public BrokerConnections(DataSource dataSource, double simulatedBrokerFunds, List<BrokerAdapter> adapters) {
        this.dataSource = dataSource;
        this.simulatedBrokerFunds = simulatedBrokerFunds;
        this.freeze = new FreezeQuantities(dataSource);
        adapters.forEach(a -> this.adapters.put(a.broker(), a));
    }

    /**
     * Review 16 Nov 2026: a LIVE account refused its route by the egress check (its address was added while it held
     * positions, or the address left this host) has no venue, so neither our exits nor Flatten can reach the broker: its
     * kill switch is engaged (no new entry when the address comes back before anyone looks) and EGRESS_REFUSED is
     * audited, which the worker sends on Telegram. Its resting stops at the broker still protect it. Once per account
     * and reason while the worker runs.
     */
    private void stranded(BrokerAccount account, String why) {
        stranded(account, why, "EGRESS: ", "EGRESS_REFUSED",
                "no LIVE route from an address this host lacks: exits wait, resting broker stops still protect");
    }

    /**
     * Review 16 Nov 2026: the same for every other LIVE refusal (the broker taken out of the LIVE setting, its adapter
     * not offering LIVE, the switch off, the risk file without live limits): it was only logged, so a LIVE account with
     * positions sat with no exits, no kill switch and no alert until a restart.
     */
    private void strandedLive(BrokerAccount account, String why) {
        stranded(account, why, "LIVE ROUTE: ", "LIVE_ROUTE_REFUSED",
                "a LIVE account with open positions has no route: exits and Flatten wait; resting broker stops still protect");
    }

    private void stranded(BrokerAccount account, String why, String prefix, String action, String rule) {
        Integer open = new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject("select count(*) from "
                + "exec.position where broker_account_id = ? and status <> 'CLOSED' and quantity_open > 0", Integer.class,
                account.id());
        // second review: once a day (the worker runs for days: a refusal after the user released yesterday's kill)
        if (open == null || open == 0 || !strandedSaid.add(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                + ":" + account.id() + ":" + why)) {
            return;
        }
        KillSwitches kills = new KillSwitches(dataSource);
        kills.engageAccount(account, prefix + why, "broker-registry");
        kills.audit(account, "broker-registry", action, Map.of("open_positions", open, "reason", why, "rule", rule));
    }

    private final java.util.Set<String> strandedSaid = ConcurrentHashMap.newKeySet();

    /** The brokers configured LIVE (the worker's {@link LiveBrokers}); routes already open are not re-checked. */
    public void liveBrokers(java.util.function.Predicate<BrokerType> configuredLive) {
        this.liveBrokers = java.util.Objects.requireNonNull(configuredLive);
    }

    /**
     * The account's route, opened on first use and kept. Review 16 Nov 2026 (D5): a refused route (no venue) was kept
     * until a restart, so a LIVE account whose egress address came up seconds after the worker started had no exits and
     * no square-off all day. A refused route is opened again {@link #REFUSED_RETRY} after it was refused (the refusal's
     * kill switch and alert are said once per account and reason, not at every retry).
     */
    public Route route(BrokerAccount account, FollowerRisk risk) {
        Route r = routes.computeIfAbsent(account.id(), id -> openNoted(account, risk));
        Instant refused = refusedAt.get(account.id());
        if (r.venue() == null && (refused == null || !Instant.now().isBefore(refused.plus(refusedRetry)))) {
            routes.remove(account.id(), r);
            r = routes.computeIfAbsent(account.id(), id -> openNoted(account, risk));
        }
        return r;
    }

    /** {@link #open}, noting when a route was refused. */
    private Route openNoted(BrokerAccount account, FollowerRisk risk) {
        Route opened = open(account, risk);
        if (opened.venue() == null) {
            refusedAt.put(account.id(), Instant.now());
        } else {
            refusedAt.remove(account.id());
        }
        return opened;
    }

    /** How long a refused route is kept before it is opened again. */
    static final Duration REFUSED_RETRY = Duration.ofSeconds(30);
    private final Map<Long, Instant> refusedAt = new ConcurrentHashMap<>();
    private volatile Duration refusedRetry = REFUSED_RETRY;

    /** Tests: how long a refused route is kept. */
    void retryRefusedAfter(Duration retry) {
        this.refusedRetry = retry;
    }

    private Route open(BrokerAccount account, FollowerRisk risk) {
        if (account.tradeMode() == TradeMode.LIVE) {
            Route live = live(account, risk);
            if (live.venue() == null) {               // an egress refusal was said already (the same account and reason)
                strandedLive(account, live.description().replaceFirst("^LIVE refused: ", ""));
            }
            return live;
        }
        if (account.tradeMode() == TradeMode.PAPER || risk.orders() == null) {
            return new Route(new SimulatedVenue(dataSource, simulatedBrokerFunds), null, "simulated fills");
        }
        BrokerAdapter adapter = adapters.get(account.brokerType());
        if (adapter == null) {
            return new Route(null, null, "no order adapter for " + account.brokerType() + " yet");
        }
        String egress = adapter.egressRefusal(account);
        if (egress != null) {
            log.error("account {} ({}): route refused: {}", account.id(), account.displayName(), egress);
            return new Route(null, null, "refused: " + egress);
        }
        try {
            BrokerAdapter.DryRun dryRun = adapter.dryRun(account, risk.orders());
            opened.put(account.id(), dryRun);
            BrokerVenue venue = new BrokerVenue(dataSource, dryRun.connection(), risk.orders(), freeze::of,
                    dryRun.beforeOrder());
            log.info("account {} ({}): DRY_RUN orders go to {}", account.id(), account.displayName(), dryRun.description());
            return new Route(venue, dryRun.connection(), dryRun.description());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Route live(BrokerAccount account, FollowerRisk risk) {
        if (!account.liveTradingEnabled()) {
            return new Route(null, null, "LIVE refused: the account's LIVE switch is off");
        }
        if (risk.live() == null || risk.orders() == null) {
            return new Route(null, null, "LIVE refused: risk file " + risk.version() + " has no live limits");
        }
        BrokerAdapter adapter = adapters.get(account.brokerType());
        if (adapter == null || !adapter.offersLive()) {
            // 16 Nov 2026: Kotak, Upstox and Groww have LIVE connections now (production gaps C1), tested against their
            // simulators only (and usable by the canary); none trades until its adapter offers LIVE after its gates and the
            // user's go-ahead, and its broker is configured LIVE (LiveBrokers, checked by the LIVE switch)
            return new Route(null, null, "LIVE refused: " + account.brokerType() + " is not a LIVE broker yet");
        }
        if (!liveBrokers.test(account.brokerType())) {
            log.error("account {} ({}) is LIVE but {} is not configured LIVE (autotrade.execution.live-brokers): no route",
                    account.id(), account.displayName(), account.brokerType());
            return new Route(null, null, "LIVE refused: " + account.brokerType() + " is not configured LIVE on this worker");
        }
        String egress = adapter == null ? null : adapter.egressRefusal(account);
        if (egress != null) {
            log.error("account {} ({}): LIVE route refused: {}", account.id(), account.displayName(), egress);
            stranded(account, egress);
            return new Route(null, null, "LIVE refused: " + egress);
        }
        BrokerAdapter.Live live = adapter == null ? null : adapter.live(account, risk.live());
        if (live == null) {
            return new Route(null, null, "LIVE refused: no LIVE adapter for " + account.brokerType());
        }
        if (live.isRefused()) {
            log.error("account {} ({}): LIVE route refused: {}", account.id(), account.displayName(), live.description());
            return new Route(null, null, "LIVE refused: " + live.description());
        }
        liveOpened.put(account.id(), live.connection());
        BrokerVenue venue = new BrokerVenue(dataSource, live.connection(), risk.orders(), freeze::of, o -> { });
        log.warn("account {} ({}): LIVE orders go to {}", account.id(), account.displayName(), live.description());
        return new Route(venue, live.connection(), live.description());
    }

    /**
     * A READ_LIVE probe on its own (user 8 Nov 2026: "put Ravali's Kotak account in READ_LIVE for Monday"): a DAILY_SWITCH
     * member's real account is idle on a PAPER day (its entries go to the paper twin), so it never gets a route and its
     * adapter's real readers never run. This opens the account's READ_LIVE connection (its adapter's {@code dryRun} with
     * a risk file that reads the broker: the {@link ReadLiveProbe} reads the real order book, trades and positions every
     * few minutes) and keeps it here, apart from the routes: no {@link BrokerVenue} is built over it and it is never
     * handed to the follower, so no order (not even a simulated one) can go through it. Whatever the account's mode.
     * Returns what the status page shows.
     */
    public String probeOnly(BrokerAccount account, FollowerRisk risk) {
        BrokerAdapter.DryRun open = probes.get(account.id());
        if (open != null) {
            return "READ_LIVE probe only (no order path): " + open.description();
        }
        if (account.brokerType() == BrokerType.PAPER) {
            return "no READ_LIVE probe: a paper account has no broker";
        }
        if (risk.orders() == null || !risk.orders().brokerMarket()) {
            return "no READ_LIVE probe: risk file " + risk.version() + " does not read the broker (orders.dry_run_market)";
        }
        BrokerAdapter adapter = adapters.get(account.brokerType());
        if (adapter == null) {
            return "no READ_LIVE probe: no adapter for " + account.brokerType();
        }
        String egress = adapter.egressRefusal(account);
        if (egress != null) {
            return "no READ_LIVE probe: " + egress;
        }
        try {
            BrokerAdapter.DryRun d = adapter.dryRun(account, risk.orders());
            probes.put(account.id(), d);
            log.info("account {} ({}): READ_LIVE probe only, no order path: {}", account.id(), account.displayName(),
                    d.description());
            return "READ_LIVE probe only (no order path): " + d.description();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether the account has a probe-only connection open. */
    public boolean probing(long accountId) {
        return probes.containsKey(accountId);
    }

    /** Closes the account's probe-only connection, if any (its session ended, it trades now, the list changed). */
    public void closeProbe(long accountId) {
        BrokerAdapter.DryRun d = probes.remove(accountId);
        if (d != null) {
            log.info("account {}: READ_LIVE probe closed", accountId);
            close(d);
        }
    }

    /**
     * The local simulator of a DRY_RUN account (tests, fault drills), whatever its broker (16 Nov 2026, A4: it was cast
     * to the Kite simulator): {@code KiteSimulator}, {@code KotakSimulator}, {@code UpstoxSimulator} or
     * {@code GrowwSimulator}; null when the account has none or its simulator is of another type.
     */
    public <T extends AutoCloseable> T simulator(long accountId, Class<T> type) {
        AutoCloseable sim = simulator(accountId);
        return type.isInstance(sim) ? type.cast(sim) : null;
    }

    /** The local simulator of a DRY_RUN account, of its broker's own type; null when it has none. */
    public AutoCloseable simulator(long accountId) {
        BrokerAdapter.DryRun d = opened.get(accountId);
        return d == null ? null : d.resource();
    }

    /** Forgets an account's route (its lease was lost, its configuration changed): a new turn opens a fresh one. */
    public void drop(long accountId) {
        routes.remove(accountId);
        refusedAt.remove(accountId);
        close(opened.remove(accountId));
        closeLive(liveOpened.remove(accountId));
        closeProbe(accountId);
    }

    private static void closeLive(BrokerConnection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (RuntimeException e) {
            log.warn("closing a LIVE connection: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        opened.values().forEach(BrokerConnections::close);
        opened.clear();
        probes.values().forEach(BrokerConnections::close);
        probes.clear();
        liveOpened.values().forEach(BrokerConnections::closeLive);
        liveOpened.clear();
        routes.clear();
    }

    private static void close(BrokerAdapter.DryRun d) {
        if (d == null) {
            return;
        }
        try {
            d.closeAll();
        } catch (Exception e) {
            log.warn("closing {}: {}", d.description(), e.getMessage());
        }
    }
}
