package com.autotrade.features.options;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.history.OiBaseline;
import com.autotrade.features.TestConfig;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.indicators.TimedSeries;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.time.SessionClock;

class OptionChainStateTest {

    private static final List<Integer> WINDOWS = List.of(1, 3, 5, 10);
    private static final LocalDate SESSION = LocalDate.of(2026, 9, 25);
    private static final Instant T0 = Instant.parse("2026-09-25T08:00:00Z"); // 13:30 IST

    @Test
    void classifiesTheDesignExamples() {
        // unwinding whose rate grows toward the present
        assertThat(OptionChainState.flow(new double[] {-3, -6, -8, -10}, WINDOWS, 0.5)).isEqualTo("ACCELERATING_UNWIND");
        // "10m -10L, 5m -6L, 3m -2L, 1m 0": still negative, but it has basically stopped
        assertThat(OptionChainState.flow(new double[] {0, -2, -6, -10}, WINDOWS, 0.5)).isEqualTo("FADING_UNWIND");
        assertThat(OptionChainState.flow(new double[] {1, 3, 5, 10}, WINDOWS, 0.5)).isEqualTo("BUILD");
        assertThat(OptionChainState.flow(new double[] {0.1, 0.2, 0.3, 0.4}, WINDOWS, 0.5)).isEqualTo("FLAT");
    }

    @Test
    void measuresNearAtmOiChangeAndPicksTheCallBarrier() {
        FeatureConfig config = TestConfig.load();
        OptionChainState chain = new OptionChainState(config, new SessionClock(config), SESSION);
        // 23200 CE: large OI that is falling (a weakening wall); 23100 CE: small, steady.
        double[] wall = {1_500_000, 1_430_000, 1_350_000, 1_290_000, 1_250_000};
        int[] minutesAgo = {11, 6, 4, 2, 0};
        for (int i = 0; i < wall.length; i++) {
            Instant time = T0.minus(Duration.ofMinutes(minutesAgo[i]));
            chain.onOption(tick(1, 23200, OptionTick.OptionType.CE, wall[i], time));
            chain.onOption(tick(2, 23100, OptionTick.OptionType.CE, 400_000, time));
            chain.onOption(tick(3, 23100, OptionTick.OptionType.PE, 600_000, time));
            chain.onOption(tick(4, 23050, OptionTick.OptionType.PE, 900_000, time));
        }
        TimedSeries spot = new TimedSeries(Duration.ofMinutes(15));
        spot.add(T0.minus(Duration.ofMinutes(4)), 23180);
        spot.add(T0.minusSeconds(1), 23198);

        OptionsFeatures features = chain.snapshot(T0.plusMillis(1), 23198, 10, spot);

        assertThat(features.strikeStep()).isEqualTo(50);
        assertThat(features.atmStrike()).isEqualTo(23200);
        assertThat(features.ceOiChange10m()).isEqualTo(1_250_000 - 1_500_000.0);
        assertThat(features.ceOiFlow()).contains("UNWIND");
        assertThat(features.callBarrierStrike()).isEqualTo(23200);
        assertThat(features.callWallWeakening()).isTrue();
        // 23050 PE has more OI, but 23100 is nearer spot and proximity dominates
        assertThat(features.putSupportStrike()).isEqualTo(23100);
    }

    /**
     * A-090: the 5-minute weighted near-ATM call OI change as a share of near-ATM OI, scored in the baseline cell for
     * 13:30 (bucket 17) and 4 days to expiry; a baseline built with other strikes is refused; no score from 15:30.
     */
    @Test
    void scoresTheFiveMinuteOiChangeInItsBaselineCell() {
        FeatureConfig config = TestConfig.loadV12();
        OiBaseline.Params params = new OiBaseline.Params(config.nearAtmStrikes(), config.heavyWeightStrikes(),
                config.outerStrikeWeight(), 5);
        OiBaseline baseline = new OiBaseline(params, java.util.Map.of(
                new OiBaseline.Cell.Key("CE", 4, 17), new OiBaseline.Cell(0.0, 0.01, 50)));
        OptionChainState chain = new OptionChainState(config, new SessionClock(config), SESSION);
        assertThat(chain.oiBaseline(baseline)).isEqualTo(OiBaseline.Status.ACCEPTED);
        double[] wall = {1_500_000, 1_430_000, 1_350_000, 1_290_000, 1_250_000};
        int[] minutesAgo = {11, 6, 4, 2, 0};
        for (int i = 0; i < wall.length; i++) {
            Instant time = T0.minus(Duration.ofMinutes(minutesAgo[i]));
            chain.onOption(tick(1, 23200, OptionTick.OptionType.CE, wall[i], time));
            chain.onOption(tick(2, 23100, OptionTick.OptionType.CE, 400_000, time));
            chain.onOption(tick(3, 23100, OptionTick.OptionType.PE, 600_000, time));
            chain.onOption(tick(4, 23050, OptionTick.OptionType.PE, 900_000, time));
        }
        TimedSeries spot = new TimedSeries(Duration.ofMinutes(15));
        spot.add(T0.minusSeconds(1), 23198);

        OptionsFeatures features = chain.snapshot(T0.plusMillis(1), 23198, 10, spot);
        // 23200 CE: 1,250,000 now vs 1,430,000 five minutes ago (weight 1); near-ATM OI 3,150,000
        double share = (1_250_000 - 1_430_000.0) / 3_150_000;
        assertThat(features.ceOiChange5mZ()).isCloseTo(share / (1.4826 * 0.01), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(features.peOiChange5mZ()).isNaN();                  // no PE cell in this baseline

        OptionChainState other = new OptionChainState(config, new SessionClock(config), SESSION);
        assertThat(other.oiBaseline(new OiBaseline(new OiBaseline.Params(4, 2, 0.5, 5), baseline.cells())))
                .isEqualTo(OiBaseline.Status.MISMATCH);
        assertThat(other.oiBaseline(OiBaseline.NONE)).isEqualTo(OiBaseline.Status.MISSING);
        assertThat(other.oiBaseline(new OiBaseline(null, baseline.cells()))).isEqualTo(OiBaseline.Status.MISMATCH);
        assertThat(OiBaseline.minuteBucket(java.time.LocalTime.of(9, 15), java.time.LocalTime.of(15, 29))).isEqualTo(24);
        assertThat(OiBaseline.minuteBucket(java.time.LocalTime.of(9, 15), java.time.LocalTime.of(15, 30))).isEqualTo(-1);
        assertThat(OiBaseline.minuteBucket(java.time.LocalTime.of(9, 15), java.time.LocalTime.of(9, 14))).isEqualTo(-1);
        assertThat(OiBaseline.dteBucket(SESSION, LocalDate.of(2026, 10, 6))).isEqualTo(4);
    }

    private static OptionTick tick(long token, double strike, OptionTick.OptionType type, double oi, Instant time) {
        return new OptionTick(time, time, "NIFTY", 1, token, "NIFTY " + (int) strike + " " + type,
                LocalDate.of(2026, 9, 29), strike, type, 65, 100, 1000L, oi, 0.0, 0.0,
                DepthLevels.of(new double[] {99.5}, new long[] {65}, new int[] {1}),
                DepthLevels.of(new double[] {100.5}, new long[] {65}, new int[] {1}),
                0.11, type == OptionTick.OptionType.CE ? 0.5 : -0.5, 0.001, -10.0, 12.0, 1.0, time, true, true);
    }
}
