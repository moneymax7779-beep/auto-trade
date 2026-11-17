package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrokerLoginPageTest {

    @Test
    void aSuccessfulLoginReturnsToHomeAndOffersTheWayBack() {
        String page = BrokerLoginPage.result("Kite", true, "Kite session active", "ZERODHA-1", "logged in until 06:00").getBody();
        assertThat(page).contains("<meta http-equiv=\"refresh\" content=\"3; url=/home\">")
                .contains("<h3>Kite session active: ZERODHA-1</h3>")
                .contains("href=\"/home\"").contains("Back to auto-trade")
                .contains("width=device-width, initial-scale=1");
    }

    @Test
    void aFailedLoginStaysSoItsReasonCanBeReadAndEscapesWhatTheBrokerSent() {
        String page = BrokerLoginPage.result("Upstox", false, "Upstox login failed", null, "<script>x</script> denied").getBody();
        assertThat(page).doesNotContain("http-equiv=\"refresh\"").doesNotContain("<script>")
                .contains("&lt;script&gt;x&lt;/script&gt; denied").contains("Back to auto-trade")
                .doesNotContain("Returning to auto-trade");
    }
}
