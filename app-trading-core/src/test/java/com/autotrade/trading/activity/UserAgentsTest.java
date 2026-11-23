package com.autotrade.trading.activity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserAgentsTest {

    private static UserAgents.Device ua(String s) {
        return UserAgents.parse(s, null, null, null);
    }

    @Test
    void desktopBrowsers() {
        assertThat(ua("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"))
                .isEqualTo(new UserAgents.Device("Chrome", "131", "macOS", null, "DESKTOP"));
        assertThat(ua("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.2903.70"))
                .isEqualTo(new UserAgents.Device("Edge", "131", "Windows", "10/11", "DESKTOP"));
        assertThat(ua("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:133.0) Gecko/20100101 Firefox/133.0"))
                .isEqualTo(new UserAgents.Device("Firefox", "133", "macOS", null, "DESKTOP"));
        assertThat(ua("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15"))
                .isEqualTo(new UserAgents.Device("Safari", "18", "macOS", null, "DESKTOP"));
        assertThat(ua("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 OPR/115.0.0.0"))
                .isEqualTo(new UserAgents.Device("Opera", "115", "Linux", null, "DESKTOP"));
    }

    @Test
    void phonesAndTablets() {
        assertThat(ua("Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"))
                .isEqualTo(new UserAgents.Device("Safari", "18", "iOS", "18.1", "MOBILE"));
        assertThat(ua("Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/131.0.6778.73 Mobile/15E148 Safari/604.1"))
                .isEqualTo(new UserAgents.Device("Chrome", "131", "iOS", "18.1", "MOBILE"));
        assertThat(ua("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"))
                .isEqualTo(new UserAgents.Device("Chrome", "131", "Android", "10", "MOBILE"));
        assertThat(ua("Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36"))
                .isEqualTo(new UserAgents.Device("Samsung Internet", "26", "Android", "14", "MOBILE"));
        assertThat(ua("Mozilla/5.0 (iPad; CPU OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"))
                .isEqualTo(new UserAgents.Device("Safari", "17", "iPadOS", "17.6", "TABLET"));
        assertThat(ua("Mozilla/5.0 (Linux; Android 13; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"))
                .isEqualTo(new UserAgents.Device("Chrome", "131", "Android", "13", "TABLET"));
    }

    @Test
    void clientHintsNameTheBrowserAReducedAgentHides() {
        String reduced = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
        assertThat(UserAgents.parse(reduced, "\"Brave\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"", "\"macOS\"", "?0"))
                .isEqualTo(new UserAgents.Device("Brave", "131", "macOS", null, "DESKTOP"));
        assertThat(UserAgents.parse(reduced, "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"", null, null)
                .browser()).isEqualTo("Chrome");
        assertThat(UserAgents.parse("", null, "\"Android\"", "?1"))
                .isEqualTo(new UserAgents.Device("Other", null, "Android", null, "MOBILE"));
    }

    @Test
    void nothingKnown() {
        assertThat(UserAgents.parse(null, null, null, null)).isEqualTo(new UserAgents.Device("Other", null, "Other", null, "OTHER"));
        assertThat(ua("curl/8.7.1")).isEqualTo(new UserAgents.Device("Other", null, "Other", null, "OTHER"));
    }
}
