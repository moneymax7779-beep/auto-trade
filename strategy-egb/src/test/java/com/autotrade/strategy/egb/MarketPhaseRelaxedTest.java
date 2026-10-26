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

/** A-240: the relaxed market-phase rows (box breakout, VWAP pullback, FII as sizing, VIX bounds). */
class MarketPhaseRelaxedTest {

    private static Strategy load(String file) {
        return Strategies.load(Path.of("..", "config", "strategy", file)).create("NIFTY", Snapshots.SESSION);
    }

    private static Map<String, Object> base() {
        Map<String, Object> m = new HashMap<>();
        m.put("structure.orComplete", true);
        m.put("structure.orHigh", 22760.0);
        m.put("structure.orLow", 22500.0);
        m.put("structure.pdh", 22800.0);
        m.put("structure.pdl", 22450.0);
        m.put("structure.atr3m", 10.0);
        m.put("structure.vwapSpotProxy", 22650.0);
        m.put("regime.dteTradingDays", 2);
        return m;
    }

    /** Flat at {@code from} 09:40-10:14 (a tight box), futures flat; at 10:15 (a 3-minute close) {@code to} with the columns. */
    private static Decision run(Strategy s, double from, double to, double futMove, double oiMove, Map<String, Object> last) {
        for (int i = 40; i < 75; i++) {
            Snapshots snap = new Snapshots().set("spot", from).set("futures.price", from + 20).set("futures.oi", 1.0e7);
            base().forEach(snap::set);
            s.decide(snap.at(String.format("%02d:%02d", 9 + i / 60, i % 60)), PositionView.FLAT);
        }
        Snapshots snap = new Snapshots().set("spot", to).set("futures.price", from + 20 + futMove)
                .set("futures.oi", 1.0e7 + oiMove);
        base().forEach(snap::set);
        last.forEach(snap::set);
        return s.decide(snap.at("10:15"), PositionView.FLAT);
    }

    private static Map<String, Object> squeeze(double fiiRatio, double fiiChange) {
        Map<String, Object> m = new HashMap<>();
        m.put("volatility.vix", 15.5);                              // above v1's 14, below v2's 16
        m.put("futures.rvolTod", 2.0);
        m.put("options.atmBandNet3m", 2.0e6);
        m.put("options.atmBandNet5m", 3.0e6);
        m.put("cues.fiiIndexFutLongPct", fiiRatio);
        m.put("cues.fiiIndexFutLongPctChange", fiiChange);
        return m;
    }

    @Test
    void boxBreakoutBuysFullSizeWhenFiiAgrees() {
        Decision d = run(load("market-phase-squeeze.v2.yaml"), 22690, 22710, 30, -50_000, squeeze(10.0, 0.5));
        assertThat(d.orders()).hasSize(1);
        assertThat(d.orders().getFirst().side()).isEqualTo(OptionSide.CE);
        assertThat(d.orders().getFirst().reason()).isEqualTo("BOX_BREAK");
        assertThat(d.orders().getFirst().lots()).isEqualTo(2);
    }

    @Test
    void fiiAgainstHalvesTheSizeAndOnlyAnExtremeBlocks() {
        Decision half = run(load("market-phase-squeeze.v2.yaml"), 22690, 22710, 30, -50_000, squeeze(10.0, -0.4));
        assertThat(half.orders()).hasSize(1);
        assertThat(half.orders().getFirst().lots()).isEqualTo(1);
        assertThat(run(load("market-phase-squeeze.v2.yaml"), 22690, 22710, 30, -50_000, squeeze(7.0, 0.5)).orders())
                .as("FII long share below its 5th percentile").isEmpty();
    }

    @Test
    void theOtherColumnsStillGate() {
        Map<String, Object> highVix = squeeze(10.0, 0.5);
        highVix.put("volatility.vix", 16.5);
        assertThat(run(load("market-phase-squeeze.v2.yaml"), 22690, 22710, 30, -50_000, highVix).orders()).isEmpty();
        Map<String, Object> coiAgainst = squeeze(10.0, 0.5);
        coiAgainst.put("options.atmBandNet5m", -1.0e6);
        assertThat(run(load("market-phase-squeeze.v2.yaml"), 22690, 22710, 30, -50_000, coiAgainst).orders()).isEmpty();
        assertThat(run(load("market-phase-squeeze.v2.yaml"), 22690, 22710, 30, 50_000, squeeze(10.0, 0.5)).orders())
                .as("futures OI up: long build-up, not short covering").isEmpty();
    }

    @Test
    void v3SkipsSensexOnItsOwnExpiryDayOnly() {
        Map<String, Object> expiry = squeeze(10.0, 0.5);
        expiry.put("regime.dteTradingDays", 0);
        Strategy sensex = Strategies.load(Path.of("..", "config", "strategy", "market-phase-squeeze.v3.yaml"))
                .create("SENSEX", Snapshots.SESSION);
        assertThat(run(sensex, 22690, 22710, 30, -50_000, expiry).orders()).isEmpty();
        assertThat(run(load("market-phase-squeeze.v3.yaml"), 22690, 22710, 30, -50_000, expiry).orders())
                .as("NIFTY on its expiry day still trades").hasSize(1);
    }

    @Test
    void vwapPullbackNeedsTheCloseAboveTheBandAndRisingOi() {
        Strategy s = load("market-phase-expansion.v2.yaml");
        for (int i = 40; i < 75; i++) {                             // resting on VWAP 22650, OI differential rising
            Snapshots snap = new Snapshots().set("spot", 22650.0).set("futures.price", 22670.0).set("futures.oi", 1.0e7)
                    .set("options.atmBandNet3m", 1.0e5 * (i - 39));
            base().forEach(snap::set);
            s.decide(snap.at(String.format("%02d:%02d", 9 + i / 60, i % 60)), PositionView.FLAT);
        }
        Snapshots snap = new Snapshots().set("spot", 22652.0).set("futures.price", 22675.0).set("futures.oi", 1.0e7 + 40_000)
                .set("volatility.vix", 14.0).set("futures.rvolTod", 1.2).set("options.atmBandNet3m", 5.0e6)
                .set("options.atmBandNet5m", 6.0e6).set("cues.fiiIndexFutLongPct", 10.0)
                .set("cues.fiiIndexFutLongPctChange", 0.3);
        base().forEach(snap::set);
        Decision d = s.decide(snap.at("10:15"), PositionView.FLAT);   // 22652 > 22650 + 0.15 x 10
        assertThat(d.orders()).hasSize(1);
        assertThat(d.orders().getFirst().reason()).isEqualTo("VWAP_PULLBACK");
        assertThat(d.orders().getFirst().strikeOffset()).isEqualTo(1);
    }
}
