package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.time.SessionClock;

/** The display endpoints' "market busy" test follows the exchange holiday file, not just the weekday. */
class MarketHoursTest {

    private static ZonedDateTime ist(String t) {
        return LocalDateTime.parse(t).atZone(MarketTime.IST);
    }

    @Test
    void aWeekdayHolidayIsNotBusyAndATradingDayIsBusyFrom0900To1550() throws Exception {
        MarketHours market = new MarketHours(new SessionClock(FeatureConfig.from(
                ThresholdConfig.load(Path.of("..", "config", "features", "features.v12.yaml")),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v3.yaml")))));
        assertThat(market.busy(ist("2026-10-20T10:00"))).as("Tue 20 Oct, Dussehra").isFalse();
        assertThat(market.busy(ist("2026-10-21T08:59"))).isFalse();
        assertThat(market.busy(ist("2026-10-21T09:00"))).isTrue();
        assertThat(market.busy(ist("2026-10-21T15:49"))).isTrue();
        assertThat(market.busy(ist("2026-10-21T15:50"))).isFalse();
        assertThat(market.busy(ist("2026-10-24T11:00"))).as("Saturday").isFalse();
    }

    @Test
    void withoutACalendarEveryWeekdayCounts() {
        MarketHours market = new MarketHours((SessionClock) null);
        assertThat(market.busy(ist("2026-10-20T10:00"))).isTrue();
        assertThat(market.busy(ist("2026-10-24T10:00"))).isFalse();
    }
}
