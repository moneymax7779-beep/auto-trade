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
 *       {@link ZerodhaDryRunAdapter}: the Kite adapter against a local simulator of its own) under a
 *       {@link BrokerVenue}. A broker without an adapter is refused.</li>
 *   <li>LIVE: refused. A LIVE route needs a real session (the daily login and the secret store), the registered static
 *       IP and the user's explicit go-ahead; no code path here sends an order to a real broker.</li>
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
            return new Route(null, null, "LIVE refused: no live broker session in this build (needs the user's go-ahead)");
        }
        if (account.tradeMode() == TradeMode.PAPER || risk.orders() == null) {
            return new Route(new SimulatedVenue(dataSource, simulatedBrokerFunds), null, "simulated fills");
        }
        BrokerAdapter adapter = adapters.get(account.brokerType());
        if (adapter == null) {
            return new Route(null, null, "no order adapter for " + account.brokerType() + " yet");
        }
        try {
            BrokerAdapter.DryRun dryRun = adapter.dryRun(account);
            opened.put(account.id(), dryRun);
            BrokerVenue venue = new BrokerVenue(dataSource, dryRun.connection(), risk.orders(), freeze::of,
                    dryRun.beforeOrder());
            log.info("account {} ({}): DRY_RUN orders go to {}", account.id(), account.displayName(), dryRun.description());
            return new Route(venue, dryRun.connection(), dryRun.description());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
    }

    @Override
    public void close() {
        opened.values().forEach(BrokerConnections::close);
        opened.clear();
        routes.clear();
    }

    private static void close(BrokerAdapter.DryRun d) {
        if (d == null || d.resource() == null) {
            return;
        }
        try {
            d.resource().close();
        } catch (Exception e) {
            log.warn("closing {}: {}", d.description(), e.getMessage());
        }
    }
}
