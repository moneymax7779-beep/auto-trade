package com.autotrade.features.preopen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.PreOpenFeatures;

/** Features v14: index-weighted pre-open IEP return, breadth and imbalance; closing-auction ticks ignored. */
class PreOpenStateTest {

    private static Instant at(String time) {
        return LocalDateTime.parse("2026-10-14T" + time).atZone(MarketTime.IST).toInstant();
    }

    private static AuctionTick tick(String time, String symbol, double iep, long imbalance) {
        return new AuctionTick(at(time), at(time), "NIFTY", 1, symbol, iep, 0, 100, imbalance, 0, true);
    }

    @Test
    void weightsTheIepAgainstThePreviousCloseAndIgnoresTicksFromTheOpen() {
        PreOpenState state = new PreOpenState();
        state.onAuction(tick("09:01:00", "A", 101.0, 500));      // +1 %, buyers
        state.onAuction(tick("09:07:59", "B", 49.0, -200));      // -2 %, sellers
        state.onAuction(tick("09:03:00", "A", 102.0, 300));      // A's latest: +2 %
        state.onAuction(tick("15:25:00", "B", 60.0, 900));       // closing auction: ignored
        Map<String, Double> weights = Map.of("A", 30.0, "B", 10.0, "C", 10.0);   // C has no auction quote
        Map<String, Double> closes = Map.of("A", 100.0, "B", 50.0, "C", 20.0);
        PreOpenFeatures f = state.features(weights, closes);
        assertThat(f.preOpenReturnPct()).isCloseTo(100 * (30 * 0.02 + 10 * -0.02) / 40, within(1e-9));   // +1.0 %
        assertThat(f.preOpenBreadth()).isCloseTo(100 * (30 - 10) / 40.0, within(1e-9));                // +50
        assertThat(f.preOpenImbalance()).isCloseTo(100 * (30 - 10) / 40.0, within(1e-9));              // +50
        assertThat(f.preOpenCoveragePct()).isCloseTo(80.0, within(1e-9));                             // 40 of 50
    }

    @Test
    void recomputedUntil0920ThenFrozen() {
        PreOpenState state = new PreOpenState();
        state.onAuction(tick("09:07:00", "A", 101.0, 500));
        state.onAuction(tick("09:07:00", "B", 51.0, 100));
        Map<String, Double> weights = new HashMap<>(Map.of("A", 30.0));
        Map<String, Double> closes = new HashMap<>(Map.of("A", 100.0));
        assertThat(state.features(at("09:16:00"), () -> weights, () -> closes).preOpenCoveragePct()).isEqualTo(100.0);
        weights.put("B", 10.0);                                  // B's first constituent tick arrives
        closes.put("B", 50.0);
        PreOpenFeatures at0920 = state.features(at("09:20:00"), () -> weights, () -> closes);
        assertThat(at0920.preOpenCoveragePct()).isEqualTo(100.0);
        assertThat(at0920.preOpenReturnPct()).isCloseTo(100 * (30 * 0.01 + 10 * 0.02) / 40, within(1e-9));
        weights.put("C", 60.0);                                  // after the freeze: not seen any more
        assertThat(state.features(at("10:00:00"), () -> {
            throw new AssertionError("weights read after the freeze");
        }, () -> closes)).isEqualTo(at0920);
    }

    @Test
    void emptyWithoutQuotes() {
        assertThat(new PreOpenState().features(Map.of("A", 50.0), Map.of("A", 100.0))).isEqualTo(PreOpenFeatures.EMPTY);
    }
}
