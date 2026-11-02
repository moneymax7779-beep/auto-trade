package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TradingDaysTest {

    @Test
    void quotedUnquotedAndBadHolidaysAreEachReadOnTheirOwn(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sessions.yaml");
        Files.writeString(file, "holidays:\n  - \"2026-11-10\"\n  - 2026-11-24\n  - not-a-date\n");
        assertThat(TradingDays.load(file)).containsExactlyInAnyOrder(LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 24));
    }
}
