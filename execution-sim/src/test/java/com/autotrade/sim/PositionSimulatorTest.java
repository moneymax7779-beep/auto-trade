package com.autotrade.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.time.MarketTime;

class PositionSimulatorTest {

    private static final ThresholdConfig CONFIG =
            ThresholdConfig.load(Path.of("..", "config", "costs", "india-index-options-costs.v1.yaml"));
    private static final LocalDate SESSION = LocalDate.of(2026, 9, 25);

    private final OptionPositionSimulator sim = new OptionPositionSimulator(7, "NIFTY 23100 CE", "NSE", SESSION,
            FillModel.from(CONFIG, "base"), CostModel.from(CONFIG), 25);

    @Test
    void averagesTranchesAndClosesEverything() {
        sim.buy(at("10:00:00"), 65, "EARLY");
        sim.accept(tick("10:00:01", 99, 100));
        sim.buy(at("10:03:00"), 130, "CONFIRMED");
        sim.accept(tick("10:03:01", 109, 110));
        assertThat(sim.quantity()).isEqualTo(195);
        assertThat(sim.averageCost()).isCloseTo((100 * 65 + 110 * 130) / 195.0, within(1e-9));

        sim.exitAll(at("10:10:00"), "FLAT_BY");
        sim.accept(tick("10:10:01", 120, 121));

        assertThat(sim.closed()).isTrue();
        assertThat(sim.exitReason()).isEqualTo("FLAT_BY");
        assertThat(sim.realised()).isCloseTo((120 - 100) * 65 + (120 - 110) * 130.0, within(1e-9));
        assertThat(sim.legs()).hasSize(3);
    }

    @Test
    void restingStopFiresBetweenDecisionsOnTheBid() {
        sim.buy(at("10:00:00"), 65, "EARLY");
        sim.accept(tick("10:00:01", 99, 100));
        sim.accept(tick("10:01:00", 76, 77)); // 24% below cost: not yet
        assertThat(sim.closed()).isFalse();
        sim.accept(tick("10:01:10", 75, 76)); // stop at 75
        sim.accept(tick("10:01:10.400", 74, 75)); // fills after latency

        assertThat(sim.exitReason()).isEqualTo("PREMIUM_STOP");
        assertThat(sim.realised()).isCloseTo((74 - 100) * 65.0, within(1e-9));
    }

    @Test
    void ignoresOrdersAfterAnExitIsWorking() {
        sim.buy(at("10:00:00"), 65, "EARLY");
        sim.accept(tick("10:00:01", 99, 100));
        sim.exitAll(at("10:05:00"), "INVALIDATED");
        sim.buy(at("10:05:00"), 130, "CONFIRMED");
        sim.accept(tick("10:05:01", 101, 102));

        assertThat(sim.closed()).isTrue();
        assertThat(sim.legs()).hasSize(2);
    }

    private static OptionTick tick(String time, double bid, double ask) {
        Instant at = at(time);
        return new OptionTick(at, at, "NIFTY", 1, 7, "NIFTY 23100 CE", LocalDate.of(2026, 9, 29), 23100,
                OptionTick.OptionType.CE, 65, ask, 0L, 0.0, 0.0, 0.0,
                DepthLevels.of(new double[] {bid}, new long[] {1000}, new int[] {1}),
                DepthLevels.of(new double[] {ask}, new long[] {1000}, new int[] {1}),
                0.1, 0.5, 0.001, -10.0, 12.0, 1.0, at, true, true);
    }

    private static Instant at(String time) {
        return SESSION.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant();
    }
}
