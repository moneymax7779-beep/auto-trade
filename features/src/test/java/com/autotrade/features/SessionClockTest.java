package com.autotrade.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.autotrade.core.time.MarketTime;
import com.autotrade.features.time.SessionClock;
import com.autotrade.features.time.SessionPhase;

class SessionClockTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 9, 25);
    private final SessionClock clock = new SessionClock(TestConfig.load());

    @Test
    void phasesFollowFeatureWindowsAndExchangeCasTimings() {
        assertThat(phase("09:10")).isEqualTo(SessionPhase.PRE_OPEN);
        assertThat(phase("09:20")).isEqualTo(SessionPhase.OPENING_DISCOVERY);
        assertThat(phase("10:00")).isEqualTo(SessionPhase.TREND_WINDOW);
        assertThat(phase("12:00")).isEqualTo(SessionPhase.MIDDAY);
        assertThat(phase("14:00")).isEqualTo(SessionPhase.AFTERNOON);
        assertThat(phase("14:52")).isEqualTo(SessionPhase.LATE);
        assertThat(phase("15:17")).isEqualTo(SessionPhase.CAS_REFERENCE);
        assertThat(phase("15:22")).isEqualTo(SessionPhase.CAS_MARKET_AND_LIMIT);
        assertThat(phase("15:27")).isEqualTo(SessionPhase.CAS_LIMIT_ONLY);
        assertThat(phase("15:32")).isEqualTo(SessionPhase.CAS_MATCHING);
        assertThat(phase("15:37")).isEqualTo(SessionPhase.DERIVATIVES_ONLY);
        assertThat(phase("15:45")).isEqualTo(SessionPhase.CLOSED);
        assertThat(SessionPhase.LATE.isContinuous()).isTrue();
        assertThat(SessionPhase.CAS_REFERENCE.isContinuous()).isFalse();
    }

    @Test
    void tradingDaysToExpirySkipWeekends() {
        assertThat(clock.tradingDaysToExpiry(SESSION, LocalDate.of(2026, 9, 29))).isEqualTo(2); // Fri -> Tue
        assertThat(clock.tradingDaysToExpiry(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 29))).isZero();
    }

    @Test
    void continuousMinutesLeftStopsAtTheCasStart() {
        assertThat(clock.continuousMinutesLeft(at("14:52"))).isEqualTo(23);
        assertThat(clock.continuousMinutesLeft(at("15:20"))).isZero();
    }

    private SessionPhase phase(String time) {
        return clock.phase(at(time));
    }

    private static Instant at(String time) {
        return SESSION.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant();
    }
}
