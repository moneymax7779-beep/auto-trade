package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientErrorControllerTest {

    @Test
    void fieldsAreCutToOneShortLine() {
        assertThat(ClientErrorController.cut(null, 10)).isEqualTo("-");
        assertThat(ClientErrorController.cut("a\nb\r\nc", 50)).isEqualTo("a ⏎ b ⏎ c");
        assertThat(ClientErrorController.cut("x".repeat(20), 5)).isEqualTo("xxxxx…");
    }

    @Test
    void aFloodIsCountedNotLoggedAndTheCountStartsAgainNextMinute() {
        long[] minute = {1_000};
        ClientErrorController c = new ClientErrorController(() -> minute[0]);
        ClientErrorController.Report r = new ClientErrorController.Report("error", "boom", "at x", "/home", "ua");
        for (int i = 0; i < ClientErrorController.PER_MINUTE + 12; i++) {
            c.report(r, null);
        }
        assertThat(c.counts()).containsEntry("dropped", 12);
        minute[0]++;
        c.report(r, null);
        assertThat(c.counts()).containsEntry("dropped", 0).containsEntry("inMinute", 1);
    }
}
