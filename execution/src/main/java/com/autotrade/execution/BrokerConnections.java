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
 *       Before every order its book is the stored bid and ask of that moment (own capture), else the signal's price;
 *       a new simulator is given the account's open positions (a restart), so reconciliation and stops see them.</li>
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
            SimulatorMarket market = new SimulatorMarket(sim, instruments, dataSource);
            // a new simulator holds what the account still holds (a restart), so the reconciliation and the stops
            // see the broker as it would be
            for (Map<String, Object> pos : new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForList(
                    "select p.instrument_key, p.instrument_token, p.symbol, p.underlying, p.quantity_open, p.average_entry, "
                            + "d.lot_size from exec.position p join exec.trade_intent i on i.id = p.entry_trade_intent_id "
                            + "join exec.trade_batch b on b.id = i.trade_batch_id join exec.signal_decision d "
                            + "on d.id = b.signal_decision_id where p.broker_account_id = ? and p.status = 'OPEN' "
                            + "and p.quantity_open > 0", account.id())) {
                String key = InstrumentKeys.normalize((String) pos.get("instrument_key"),
                        ((Number) pos.get("instrument_token")).longValue());
                market.list(key, (String) pos.get("symbol"), (String) pos.get("underlying"),
                        ((Number) pos.get("lot_size")).intValue());
                sim.holding(key, ((Number) pos.get("quantity_open")).longValue(),
                        ((Number) pos.get("average_entry")).doubleValue());
            }
            BrokerVenue venue = new BrokerVenue(dataSource, gateway, risk.orders(), freeze::of, market);
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
