package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.autotrade.broker.BrokerException;
import com.autotrade.broker.Quote;

/** User 25 Nov 2026: an Upstox LIVE order is priced from our capture only when it is at most 3 s old. */
class UpstoxLiveQuoteAgeTest {

    private static final Instant NOW = Instant.parse("2026-11-26T05:00:00Z");

    private static Function<String, Quote> at(Instant time) {
        return k -> new Quote(k, 99.5, 65, 100.0, 65, 99.8, time, List.of(), List.of());
    }

    @Test
    void aQuoteWithinTheAgePassesAndAnOlderOneIsNoQuote() {
        Duration max = UpstoxDryRunAdapter.LIVE_QUOTE_MAX_AGE;
        assertThat(max).isEqualTo(Duration.ofSeconds(3));
        assertThat(UpstoxDryRunAdapter.fresh(at(NOW.minusMillis(2_900)), max, () -> NOW).apply("NSE_FO|1").ask())
                .isEqualTo(100.0);
        assertThatThrownBy(() -> UpstoxDryRunAdapter.fresh(at(NOW.minusMillis(3_200)), max, () -> NOW).apply("NSE_FO|1"))
                .isInstanceOf(BrokerException.class).hasMessageContaining("3.2 s old")
                .satisfies(e -> assertThat(((BrokerException) e).kind()).isEqualTo(BrokerException.Kind.RETRYABLE));
    }

    @Test
    void noTimeOrNoSourcePassThrough() {
        assertThat(UpstoxDryRunAdapter.fresh(at(null), Duration.ofSeconds(3), () -> NOW).apply("NSE_FO|1")).isNotNull();
        assertThat(UpstoxDryRunAdapter.fresh(null, Duration.ofSeconds(3), () -> NOW)).isNull();
    }
}
