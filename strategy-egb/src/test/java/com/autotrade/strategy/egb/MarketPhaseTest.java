package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;

/** A-239: the market-phase rows enter only when every column of the row holds on the same close. */
class MarketPhaseTest {

    private static Strategy load(String file) {
        return Strategies.load(Path.of("..", "config", "strategy", file)).create("NIFTY", Snapshots.SESSION);
    }

    private static Map<String, Object> base() {
        Map<String, Object> m = new HashMap<>();
        m.put("structure.orComplete", true);
        m.put("structure.orHigh", 22700.0);
        m.put("structure.orLow", 22550.0);
        m.put("structure.pdh", 22800.0);
        m.put("structure.pdl", 22450.0);
        m.put("structure.atr3m", 10.0);
        m.put("regime.dteTradingDays", 2);
        return m;
    }

    /** 15 quiet minutes 10:00-10:14 at {@code from}, futures flat; then {@code to} at 10:15 with the row's columns. */
    private static Decision run(Strategy s, double from, double to, double futMove, double oiMove, Map<String, Object> last) {
        for (int i = 0; i < 15; i++) {
            Snapshots snap = new Snapshots().set("spot", from).set("futures.price", from + 20).set("futures.oi", 1.0e7);
            base().forEach(snap::set);
            s.decide(snap.at(String.format("10:%02d", i)), PositionView.FLAT);
        }
        Snapshots snap = new Snapshots().set("spot", to).set("futures.price", from + 20 + futMove)
                .set("futures.oi", 1.0e7 + oiMove);
        base().forEach(snap::set);
        last.forEach(snap::set);
        return s.decide(snap.at("10:15"), PositionView.FLAT);
    }

    private static Map<String, Object> squeezeColumns() {
        Map<String, Object> m = new HashMap<>();
        m.put("volatility.vix", 13.0);
        m.put("volatility.vixChange15m", 0.2);
        m.put("futures.rvolTod", 2.0);
        m.put("options.atmBandCallShare3m", -0.01);
        m.put("options.atmBandPutShare3m", 0.01);
        m.put("cues.fiiIndexFutLongPctChange", 1.5);
        return m;
    }

    @Test
    void squeezeBuysTheAtmCallWhenEveryColumnAgrees() {
        Decision d = run(load("market-phase-squeeze.v1.yaml"), 22690, 22710, 30, -50_000, squeezeColumns());
        assertThat(d.orders()).hasSize(1);
        assertThat(d.orders().getFirst().side()).isEqualTo(OptionSide.CE);
        assertThat(d.orders().getFirst().reason()).isEqualTo("SQUEEZE");
    }

    @Test
    void squeezeNeedsEachColumn() {
        for (String drop : new String[] {"volatility.vix", "futures.rvolTod", "options.atmBandPutShare3m",
                "cues.fiiIndexFutLongPctChange"}) {
            Map<String, Object> cols = squeezeColumns();
            cols.remove(drop);                                       // unknown = the column fails
            assertThat(run(load("market-phase-squeeze.v1.yaml"), 22690, 22710, 30, -50_000, cols).orders())
                    .as("without " + drop).isEmpty();
        }
        // VIX above 14, and futures OI rising (long build-up, not short covering)
        Map<String, Object> highVix = squeezeColumns();
        highVix.put("volatility.vix", 15.0);
        assertThat(run(load("market-phase-squeeze.v1.yaml"), 22690, 22710, 30, -50_000, highVix).orders()).isEmpty();
        assertThat(run(load("market-phase-squeeze.v1.yaml"), 22690, 22710, 30, 50_000, squeezeColumns()).orders()).isEmpty();
    }

    @Test
    void avalancheBuysTheAtmPutOnASpikingVixBreakdown() {
        Map<String, Object> m = new HashMap<>();
        m.put("volatility.vix", 15.6);
        m.put("volatility.vixChange15m", 0.3);
        m.put("volatility.vixChangeDay", 0.6);                     // +4 % on the previous close of 15.0
        m.put("futures.rvolTod", 1.8);
        m.put("options.atmBandPutShare3m", -0.01);
        m.put("options.atmBandCallShare3m", 0.02);
        m.put("cues.fiiIndexFutLongPctChange", -2.0);
        Decision d = run(load("market-phase-avalanche.v1.yaml"), 22560, 22540, -30, 50_000, m);
        assertThat(d.orders()).hasSize(1);
        assertThat(d.orders().getFirst().side()).isEqualTo(OptionSide.PE);
        m.put("volatility.vixChangeDay", 0.2);                     // +1.3 %: not a spike
        assertThat(run(load("market-phase-avalanche.v1.yaml"), 22560, 22540, -30, 50_000, m).orders()).isEmpty();
    }

    @Test
    void expansionBuysTheItmCallOnACleanBounceOffSupport() {
        Map<String, Object> m = new HashMap<>();
        m.put("volatility.vix", 14.0);
        m.put("volatility.vixChange15m", 0.05);
        m.put("futures.rvolTod", 1.1);
        m.put("options.atmBandShare3m", 0.01);
        m.put("options.atmBandShare5m", 0.02);
        m.put("options.atmBandPutShare3m", 0.01);
        m.put("cues.fiiIndexFutLongPctChange", 0.5);
        Decision d = run(load("market-phase-expansion.v1.yaml"), 22552, 22560, 10, 40_000, m);   // ORL 22550 held
        assertThat(d.orders()).hasSize(1);
        assertThat(d.orders().getFirst().side()).isEqualTo(OptionSide.CE);
        assertThat(d.orders().getFirst().strikeOffset()).isEqualTo(1);
        m.put("volatility.vixChange15m", 0.5);                     // VIX moving, not flat
        assertThat(run(load("market-phase-expansion.v1.yaml"), 22552, 22560, 10, 40_000, m).orders()).isEmpty();
    }
}
