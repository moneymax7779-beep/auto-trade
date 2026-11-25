package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

/** User 25 Nov 2026: before the open the desk said "the market feed is not running"; it now says the market is closed. */
class ManualOrdersNoSpotReasonTest {

    private static String at(String isoLocal) {
        return ManualOrders.noSpotReason(ZonedDateTime.parse(isoLocal + "+05:30[Asia/Kolkata]").toInstant());
    }

    @Test
    void outsideTradingHoursOrOnAWeekendTheMarketIsClosed() {
        assertThat(at("2026-11-25T06:55:00")).startsWith("the market is closed");     // Wednesday, before the open
        assertThat(at("2026-11-25T09:14:59")).startsWith("the market is closed");
        assertThat(at("2026-11-25T15:30:00")).startsWith("the market is closed");     // the close
        assertThat(at("2026-11-28T11:00:00")).startsWith("the market is closed");     // Saturday
        assertThat(at("2026-11-29T11:00:00")).startsWith("the market is closed");     // Sunday
    }

    @Test
    void duringTradingHoursTheFeedIsNotRunning() {
        assertThat(at("2026-11-25T09:15:00")).isEqualTo("the market feed is not running");
        assertThat(at("2026-11-25T15:29:59")).isEqualTo("the market feed is not running");
    }
}
