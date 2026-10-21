package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** When the Upstox token check runs (21 Oct: a sign-in after 08:50 left the 08:00 alert up all day). */
class AlertMonitorTokenTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21), YESTERDAY = TODAY.minusDays(1);
    private static final long M0800 = 8 * 60, M0850 = 8 * 60 + 50;

    private static boolean due(String time, boolean active, LocalDate lastDay, long lastMinute) {
        return AlertMonitor.tokenCheckDue(LocalTime.parse(time), active, TODAY, lastDay, lastMinute);
    }

    @Test
    void before0900EveryTenMinutesNotBefore0800() {
        assertThat(due("07:59", false, null, -1)).isFalse();
        assertThat(due("08:00", false, null, -1)).isTrue();
        assertThat(due("08:09", true, TODAY, M0800)).isFalse();
        assertThat(due("08:10", true, TODAY, M0800)).isTrue();
    }

    @Test
    void after0900OnlyWhileTheAlertIsActiveEveryMinuteUntil1540() {
        assertThat(due("09:00", true, TODAY, M0850)).as("the alert from before the open is re-checked").isTrue();
        assertThat(due("09:00", false, TODAY, M0850)).as("no alert: nothing to do").isFalse();
        assertThat(due("12:16", true, TODAY, 12 * 60 + 15)).isTrue();
        assertThat(due("12:16", true, TODAY, 12 * 60 + 16)).isFalse();
        assertThat(due("15:40", true, TODAY, 15 * 60 + 30)).isFalse();
    }

    @Test
    void aNewDayChecksAtOnceWhateverYesterdaysMinute() {
        assertThat(due("08:00", false, YESTERDAY, M0850)).as("08:00 today after 08:50 yesterday").isTrue();
        assertThat(due("08:05", false, YESTERDAY, 15 * 60 + 39)).isTrue();
    }
}
