package com.autotrade.execution;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.zerodha.KiteConfig;
import com.autotrade.zerodha.KiteGateway;
import com.autotrade.zerodha.KiteHttp;
import com.autotrade.zerodha.KiteInstruments;
import com.autotrade.zerodha.KiteSimulator;

/**
 * Zerodha in DRY_RUN: the Kite adapter ({@link KiteGateway}) against a local {@link KiteSimulator} of the account's own
 * (127.0.0.1, random port). The simulator is given the account's open positions (a restart: the broker would still hold
 * them), and before each order its book is set by {@link SimulatorMarket}.
 */
public final class ZerodhaDryRunAdapter implements BrokerAdapter {

    private final DataSource dataSource;
    private final double simulatedBrokerFunds;

    public ZerodhaDryRunAdapter(DataSource dataSource, double simulatedBrokerFunds) {
        this.dataSource = dataSource;
        this.simulatedBrokerFunds = simulatedBrokerFunds;
    }

    @Override
    public BrokerType broker() {
        return BrokerType.ZERODHA;
    }

    @Override
    public DryRun dryRun(BrokerAccount account) throws IOException {
        KiteSimulator sim = new KiteSimulator(0, "sim-api-key", "sim-secret", 3_000);
        sim.funds(simulatedBrokerFunds);
        KiteConfig config = KiteConfig.simulator(sim.baseUri());
        KiteHttp http = new KiteHttp(config);
        http.accessToken("dry-run");
        KiteInstruments instruments = new KiteInstruments(List.of());
        KiteGateway gateway = new KiteGateway(config, http, instruments);
        SimulatorMarket market = new SimulatorMarket(sim, instruments, dataSource);
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
        return new DryRun(gateway, market, "Kite simulator " + sim.baseUri(), sim);
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
