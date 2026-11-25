package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class IstMidnightTest {

    @Test
    void waitsOnlyAroundIstMidnight() {
        assertThat(IstMidnight.waitAt(LocalDateTime.of(2026, 11, 25, 0, 36))).isZero();      // the failing build's hour
        assertThat(IstMidnight.waitAt(LocalDateTime.of(2026, 11, 25, 7, 59))).isZero();
        assertThat(IstMidnight.waitAt(LocalDateTime.of(2026, 11, 25, 23, 56, 59))).isZero();
        assertThat(IstMidnight.waitAt(LocalDateTime.of(2026, 11, 25, 23, 57))).isEqualTo(Duration.ofMinutes(15));
        assertThat(IstMidnight.waitAt(LocalDateTime.of(2026, 11, 26, 0, 0, 30))).isEqualTo(Duration.ofSeconds(690));
        assertThat(IstMidnight.waitAt(LocalDateTime.of(2026, 11, 26, 0, 12))).isZero();
    }
}
