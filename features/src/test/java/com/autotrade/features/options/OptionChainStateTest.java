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

    /**
     * A-157 / A-158: over the ATM ± 2 strikes (ATM 23200: 23100-23300), puts +60,000 and calls +20,000 in the last 3
     * minutes on 10 x 100,000 OI at the window start: share (60,000 − 20,000) / 1,000,000 = 0.04. A strike outside the band
     * does not count; a missing band contract gives NaN.
     */
    @Test
    void atmBandShareIsPutMinusCallOiChangeOverTheBandsStartingOi() {
        FeatureConfig config = TestConfig.load();
        OptionChainState chain = new OptionChainState(config, new SessionClock(config), SESSION);
        long token = 1;
        for (double strike = 23100; strike <= 23300; strike += 50) {
            for (OptionTick.OptionType type : OptionTick.OptionType.values()) {
                boolean put = type == OptionTick.OptionType.PE;
                chain.onOption(tick(token, strike, type, 100_000, T0.minus(Duration.ofMinutes(4))));
                chain.onOption(tick(token, strike, type, 100_000 + (put ? 12_000 : 4_000), T0.minusSeconds(30)));
                token++;
            }
        }
        chain.onOption(tick(token, 23400, OptionTick.OptionType.PE, 5_000_000, T0.minusSeconds(30)));   // outside the band
        TimedSeries spot = new TimedSeries(Duration.ofMinutes(15));
        spot.add(T0.minusSeconds(1), 23205);

        OptionsFeatures features = chain.snapshot(T0, 23205, 10, spot);
        assertThat(features.atmBandShare3m()).isCloseTo(0.04, org.assertj.core.api.Assertions.within(1e-12));
        assertThat(features.atmBandShare5m()).as("no OI 5 minutes back").isNaN();
        assertThat(features.atmBandCallShare3m()).isCloseTo(0.02, org.assertj.core.api.Assertions.within(1e-12));   // A-161
        assertThat(features.atmBandPutShare3m()).isCloseTo(0.06, org.assertj.core.api.Assertions.within(1e-12));

        OptionsFeatures shifted = chain.snapshot(T0, 23305, 10, spot);   // ATM 23300: 23350 / 23400 missing on one side
        assertThat(shifted.atmBandShare3m()).isNaN();

        // A-240: the same change, absolute: (5 x 12,000) - (5 x 4,000) = +40,000 OI units over 3 minutes
        assertThat(features.atmBandNet3m()).isEqualTo(40_000.0);
        assertThat(features.atmBandNet5m()).as("no OI 5 minutes back").isNaN();
        assertThat(features.atmBandNet10m()).isNaN();
        assertThat(shifted.atmBandNet3m()).isNaN();
    }

    /**
     * A-239 / A-240 (features v15, atm_band_strikes 5): the band is ATM 23200 +- 5 strikes (22950-23450). Calls written
     * (+3,000 each) and puts covered (-1,000 each) over 10 minutes give a negative differential: (11 x -1,000) -
     * (11 x 3,000) = -44,000; a strike at +-5 missing makes it NaN, so a +-2 answer is never passed off as +-5.
     */
    @Test
    void v15BandIsAtmPlusMinusFiveAndNetIsAbsolute() {
        FeatureConfig config = TestConfig.load("v15");
        assertThat(config.atmBandStrikes()).isEqualTo(5);
        assertThat(TestConfig.load().atmBandStrikes()).as("files before v15").isEqualTo(2);
        OptionChainState chain = new OptionChainState(config, new SessionClock(config), SESSION);
        long token = 1;
        for (double strike = 22950; strike <= 23450; strike += 50) {
            for (OptionTick.OptionType type : OptionTick.OptionType.values()) {
                boolean put = type == OptionTick.OptionType.PE;
                chain.onOption(tick(token, strike, type, 100_000, T0.minus(Duration.ofMinutes(11))));
                chain.onOption(tick(token, strike, type, 100_000 + (put ? -1_000 : 3_000), T0.minusSeconds(30)));
                token++;
            }
        }
        TimedSeries spot = new TimedSeries(Duration.ofMinutes(15));
        spot.add(T0.minusSeconds(1), 23205);
        OptionsFeatures features = chain.snapshot(T0, 23205, 10, spot);
        assertThat(features.atmBandNet3m()).isEqualTo(-44_000.0);
        assertThat(features.atmBandNet10m()).isEqualTo(-44_000.0);
        assertThat(features.atmBandShare3m()).isCloseTo(-44_000.0 / 2_200_000, org.assertj.core.api.Assertions.within(1e-12));
        assertThat(chain.snapshot(T0, 23255, 10, spot).atmBandNet3m()).as("ATM 23250: 23500 not captured").isNaN();
    }

    private static OptionTick tick(long token, double strike, OptionTick.OptionType type, double oi, Instant time) {
        return new OptionTick(time, time, "NIFTY", 1, token, "NIFTY " + (int) strike + " " + type,
                LocalDate.of(2026, 9, 29), strike, type, 65, 100, 1000L, oi, 0.0, 0.0,
                DepthLevels.of(new double[] {99.5}, new long[] {65}, new int[] {1}),
                DepthLevels.of(new double[] {100.5}, new long[] {65}, new int[] {1}),
                0.11, type == OptionTick.OptionType.CE ? 0.5 : -0.5, 0.001, -10.0, 12.0, 1.0, time, true, true);
    }
}
