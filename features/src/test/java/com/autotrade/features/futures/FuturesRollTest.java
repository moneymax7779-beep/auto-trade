package com.autotrade.features.futures;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.FutureTick;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.TestConfig;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.snapshot.FuturesFeatures;

/**
 * A-248: on a roll day the near month's OI falls while the next month's rises by the same amount. Near-month-only OI
 * (v12) reads the price rise as short covering; the sum over both months (v16) sees no change. Price stays near-month.
 */
class FuturesRollTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 10, 27);
    private static final LocalDate NEAR = LocalDate.of(2026, 10, 27);
    private static final LocalDate NEXT = LocalDate.of(2026, 11, 24);

    private static Instant at(String time) {
        return SESSION.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant();
    }

    private static FutureTick tick(String time, long token, LocalDate expiry, double price, double oi) {
        return new FutureTick(at(time), at(time), "NIFTY", 1, token, "NIFTY FUT " + expiry, expiry, price, 1000L, oi, null);
    }

    /** 09:15 both months; to 09:20 the index future rises 20 points while 300,000 OI moves from October to November. */
    private static FuturesFeatures roll(FeatureConfig config) {
        FuturesState state = new FuturesState(config, "NIFTY", SESSION, at("09:15:00"), new VolumeProfile(List.of()));
        for (int m = 0; m <= 5; m++) {
            String time = String.format("09:%02d:00", 15 + m);
            state.onFuture(tick(time, 2, NEXT, 22900 + 4 * m, 1_000_000 + 60_000 * m), 22800 + 4 * m);   // next month first
            state.onFuture(tick(time, 1, NEAR, 22800 + 4 * m, 5_000_000 - 60_000 * m), 22800 + 4 * m);
        }
        return state.snapshot(at("09:20:00"), 10.0);
    }

    @Test
    void nearMonthOnlyReadsTheRollAsShortCovering() {
        FuturesFeatures v12 = roll(TestConfig.loadV12());
        assertThat(v12.price()).isEqualTo(22820);                            // the near month, though November printed first
        assertThat(v12.oi()).isEqualTo(4_700_000);
        assertThat(v12.oiChange3m()).isEqualTo(-180_000);
        assertThat(v12.oiState()).isEqualTo("SHORT_COVERING");
    }

    @Test
    void theSumOverBothMonthsSeesNoChangeAndKeepsTheNearMonthPrice() {
        FuturesFeatures v16 = roll(TestConfig.load("v16"));
        assertThat(v16.price()).isEqualTo(22820);
        assertThat(v16.oi()).isEqualTo(6_000_000);
        assertThat(v16.oiChange3m()).isEqualTo(0);
        assertThat(v16.oiChangeDay()).isEqualTo(0);
        assertThat(v16.oiState()).isEqualTo("NEUTRAL");
    }

    @Test
    void withOneExpiryV16EqualsV12() {
        FeatureConfig v16 = TestConfig.load("v16");
        FuturesState state = new FuturesState(v16, "NIFTY", SESSION, at("09:15:00"), new VolumeProfile(List.of()));
        for (int m = 0; m <= 5; m++) {
            String time = String.format("09:%02d:00", 15 + m);
            state.onFuture(tick(time, 1, NEAR, 22800 + 4 * m, 5_000_000 - 60_000 * m), 22800 + 4 * m);
        }
        FuturesFeatures one = state.snapshot(at("09:20:00"), 10.0);
        assertThat(one.oi()).isEqualTo(4_700_000);
        assertThat(one.oiChange3m()).isEqualTo(-180_000);
        assertThat(one.oiState()).isEqualTo("SHORT_COVERING");
    }
}
