package com.autotrade.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.autotrade.core.time.MarketTime;

class MarketHoursGuardTest {

    @Test
    void blocksWeekdayMarketHoursInIst() {
        assertThat(MarketHoursGuard.isBlocked(ist("2026-09-25T09:00:00"))).isTrue();
        assertThat(MarketHoursGuard.isBlocked(ist("2026-09-25T15:49:59"))).isTrue();
        assertThat(MarketHoursGuard.isBlocked(ist("2026-09-25T15:50:00"))).isFalse();
        assertThat(MarketHoursGuard.isBlocked(ist("2026-09-25T08:59:59"))).isFalse();
    }

    @Test
    void allowsWeekends() {
        assertThat(MarketHoursGuard.isBlocked(ist("2026-09-26T11:00:00"))).isFalse();
    }

    @Test
    void usesIstRegardlessOfCallerZone() {
        // 04:00 UTC is 09:30 IST
        assertThat(MarketHoursGuard.isBlocked(ZonedDateTime.parse("2026-09-25T04:00:00Z"))).isTrue();
    }

    private static ZonedDateTime ist(String local) {
        return LocalDateTime.parse(local).atZone(MarketTime.IST);
    }
}
