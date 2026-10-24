package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The cross-site guard: the UI's own calls and host scripts pass; another site's form or fetch is refused. */
class SameOriginFilterTest {

    private static final String HOST = "autotrade.136-82-45-194.sslip.io";

    @Test
    void sameOriginAndHeaderlessRequestsPass() {
        assertThat(SameOriginFilter.refusal("same-origin", "https://" + HOST, HOST)).isNull();
        assertThat(SameOriginFilter.refusal(null, null, "127.0.0.1:8095")).as("curl on the host").isNull();
        assertThat(SameOriginFilter.refusal("none", null, HOST)).as("typed in the address bar").isNull();
        assertThat(SameOriginFilter.refusal(null, "http://localhost:8097", "localhost:8097")).isNull();
    }

    @Test
    void anotherSiteIsRefused() {
        assertThat(SameOriginFilter.refusal("cross-site", "https://evil.example", HOST)).contains("cross-site");
        assertThat(SameOriginFilter.refusal("same-site", "https://v2.136-82-45-194.sslip.io", HOST)).isNotNull();
        assertThat(SameOriginFilter.refusal(null, "https://evil.example", HOST)).as("older browser, Origin only")
                .contains("evil.example");
        assertThat(SameOriginFilter.refusal(null, "null", HOST)).as("sandboxed or file origin").isNotNull();
    }
}
