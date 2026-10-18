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
import com.autotrade.zerodha.KiteConfig;
import com.autotrade.zerodha.KiteGateway;
import com.autotrade.zerodha.KiteHttp;
import com.autotrade.zerodha.KiteInstruments;
import com.autotrade.zerodha.KiteSimulator;

/**
 * The broker registry (live plan section 2a): which venue each follower account trades through.
 * <ul>
 *   <li>PAPER: simulated fills ({@link SimulatedVenue}).</li>
 *   <li>DRY_RUN whose risk file has no order rules (v1, v2): simulated fills, as in L1.</li>
 *   <li>DRY_RUN on ZERODHA with order rules (v3): the whole live path, HTTP included, against a <b>local Kite
 *       simulator</b> of its own (one per account, 127.0.0.1, random port): {@link KiteGateway} + {@link BrokerVenue}.
 *       Its book is set to the signal's price before every order (DRY_RUN fills equal the PAPER follower's), so the
 *       difference is only what the broker path adds (slicing, stops, timeouts, errors).</li>
 *   <li>DRY_RUN on another broker: refused (no adapter yet).</li>
 *   <li>LIVE: refused. A LIVE route needs a real Kite session from Secret Manager, the registered static IP and the
 *       user's explicit go-ahead (L3/L4); no code path here sends an order to a real broker.</li>
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
    private final Map<Long, Route> routes = new ConcurrentHashMap<>();
    private final Map<Long, KiteSimulator> simulators = new ConcurrentHashMap<>();

    public BrokerConnections(DataSource dataSource, double simulatedBrokerFunds) {
        this.dataSource = dataSource;
        this.simulatedBrokerFunds = simulatedBrokerFunds;
        this.freeze = new FreezeQuantities(dataSource);
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
        if (account.brokerType() != BrokerType.ZERODHA) {
            return new Route(null, null, "no order adapter for " + account.brokerType() + " yet");
        }
        try {
            KiteSimulator sim = new KiteSimulator(0, "sim-api-key", "sim-secret", 3_000);
            sim.funds(simulatedBrokerFunds);
            simulators.put(account.id(), sim);
            KiteConfig config = KiteConfig.simulator(sim.baseUri());
            KiteHttp http = new KiteHttp(config);
            http.accessToken("dry-run");
            KiteInstruments instruments = new KiteInstruments(List.of());
            KiteGateway gateway = new KiteGateway(config, http, instruments);
            BrokerVenue venue = new BrokerVenue(dataSource, gateway, risk.orders(), freeze::of,
                    new SimulatorMarket(sim, instruments));
            log.info("account {} ({}): DRY_RUN orders go to a local Kite simulator at {}", account.id(),
                    account.displayName(), sim.baseUri());
            return new Route(venue, gateway, "Kite simulator " + sim.baseUri());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The simulator of a DRY_RUN account (tests, fault drills); null when it has none. */
    public KiteSimulator simulator(long accountId) {
        return simulators.get(accountId);
    }

    /** Forgets an account's route (its lease was lost): a new turn opens a fresh one. */
    public void drop(long accountId) {
        routes.remove(accountId);
        KiteSimulator sim = simulators.remove(accountId);
        if (sim != null) {
            sim.close();
        }
    }

    @Override
    public void close() {
        simulators.values().forEach(KiteSimulator::close);
        simulators.clear();
        routes.clear();
    }

    /**
     * Before each order of a DRY_RUN replay or worker: the contract is listed (simulator and the gateway's instrument
     * map, by the exchange token in our key), the simulator's clock is the decision's time and its book is the order's
     * price (bid = ask = last, deep), so a marketable limit fills at the signal's price as the PAPER follower does.
     */
    static final class SimulatorMarket implements java.util.function.Consumer<FollowerVenue.OrderSpec> {

        private static final long DEPTH_LOTS = 100_000;
        private final KiteSimulator sim;
        private final KiteInstruments instruments;

        SimulatorMarket(KiteSimulator sim, KiteInstruments instruments) {
            this.sim = sim;
            this.instruments = instruments;
        }

        @Override
        public void accept(FollowerVenue.OrderSpec o) {
            String key = o.instrumentKey();
            if (key == null || key.indexOf('|') < 0) {
                return;
            }
            String exchange = key.startsWith("BSE_FO") ? "BFO" : "NFO";
            long token = Long.parseLong(key.substring(key.indexOf('|') + 1));
            if (sim.byKey(key) == null) {
                String symbol = o.symbol().replaceAll("[^A-Za-z0-9]", "");
                sim.list(new KiteSimulator.Listing(token, exchange, symbol, o.underlying(), "", 0, "CE", o.lotSize(), 0.05));
                instruments.add(new KiteInstruments.Contract(token * 256, exchange, token, symbol, o.underlying(), null, 0,
                        "CE", o.lotSize(), 0.05));
            }
            sim.clock(o::at);
            if (o.price() > 0) {
                sim.quote(key, o.price(), DEPTH_LOTS * o.lotSize(), o.price(), DEPTH_LOTS * o.lotSize(), o.price());
            }
        }
    }
}
