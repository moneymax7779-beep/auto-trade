package com.autotrade.execution;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import com.autotrade.zerodha.KiteSimulator;

/**
 * The broker registry (live plan section 2a): which venue each follower account trades through.
 * <ul>
 *   <li>PAPER, and DRY_RUN on a risk file without order rules (v1, v2): simulated fills ({@link SimulatedVenue}).</li>
 *   <li>DRY_RUN with order rules (v3+): the account's broker adapter ({@link BrokerAdapter}; this build has
 *       {@link ZerodhaDryRunAdapter}: the Kite adapter against a local simulator of its own; and, when the worker is
 *       given it, {@link KotakDryRunAdapter}: the Kotak Neo adapter against its own simulator, DRY_RUN only) under a
 *       {@link BrokerVenue}. A broker without an adapter is refused.</li>
 *   <li>LIVE (the Kite pilot, user 2 Nov 2026): only an account the user switched LIVE ({@code live_trading_enabled}),
 *       on a risk file with {@code live} limits (v7+) and order rules, through an adapter that offers LIVE (Zerodha):
 *       its real broker connection behind a {@link LiveGuard}, under the same {@link BrokerVenue}. Anything else LIVE is
 *       refused, so nothing is sent. The day's login and the registered static IP are the user's.</li>
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

    public Route route(BrokerAccount account, FollowerRisk risk) {
        return routes.computeIfAbsent(account.id(), id -> open(account, risk));
    }

    private Route open(BrokerAccount account, FollowerRisk risk) {
        if (account.tradeMode() == TradeMode.LIVE) {
            return live(account, risk);
        }
        if (account.tradeMode() == TradeMode.PAPER || risk.orders() == null) {
            return new Route(new SimulatedVenue(dataSource, simulatedBrokerFunds), null, "simulated fills");
        }
        BrokerAdapter adapter = adapters.get(account.brokerType());
        if (adapter == null) {
            return new Route(null, null, "no order adapter for " + account.brokerType() + " yet");
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
        BrokerAdapter.Live live = adapter == null ? null : adapter.live(account, risk.live());
        if (live == null) {
            return new Route(null, null, "LIVE refused: no LIVE adapter for " + account.brokerType());
        }
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

    /** The Kite simulator of a ZERODHA DRY_RUN account (tests, fault drills); null when it has none. */
    public KiteSimulator simulator(long accountId) {
        BrokerAdapter.DryRun d = opened.get(accountId);
        return d != null && d.resource() instanceof KiteSimulator sim ? sim : null;
    }

    /** Forgets an account's route (its lease was lost, its configuration changed): a new turn opens a fresh one. */
    public void drop(long accountId) {
        routes.remove(accountId);
        close(opened.remove(accountId));
        closeProbe(accountId);
    }

    @Override
    public void close() {
        opened.values().forEach(BrokerConnections::close);
        opened.clear();
        probes.values().forEach(BrokerConnections::close);
        probes.clear();
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
