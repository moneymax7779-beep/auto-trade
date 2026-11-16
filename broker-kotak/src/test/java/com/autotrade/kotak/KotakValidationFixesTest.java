package com.autotrade.kotak;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Kotak fixes from the 16 Nov 2026 adapter validation. */
class KotakValidationFixesTest {

    @Test
    void aSessionMessageWithoutAnStCodeReadsAsSession() {
        for (String m : new String[] {"Invalid session", "Session Expired", "invalid sid", "Token has expired",
                "Please login again", "Session timed out"}) {
            assertThat(KotakHttp.SESSION_WORDS.matcher(m).find()).as(m).isTrue();
        }
        for (String m : new String[] {"Insufficient funds", "Invalid price", "No Data", "RMS: margin exceeds",
                "You are not authorized to trade in this segment", "Unauthorized product type"}) {
            assertThat(KotakHttp.SESSION_WORDS.matcher(m).find()).as(m).isFalse();
        }
    }
}
