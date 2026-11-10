package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * User, 10 Nov 2026: "Other than Owner, all other accounts ... should only see the chart and trades, nothing more". A
 * member (signed in without the operator role) gets the status trimmed to the session and its trades, and the chart's
 * index and volume without the surges.
 */
class MemberViewTest {

    private static final AuthProperties OIDC = new AuthProperties("oidc", null, null, null, null, null, null, null);
    private static final AuthProperties NONE = new AuthProperties("none", null, null, null, null, null, null, null);
    private static final UsernamePasswordAuthenticationToken OPERATOR = new UsernamePasswordAuthenticationToken("satya", "x",
            List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
    private static final UsernamePasswordAuthenticationToken MEMBER = new UsernamePasswordAuthenticationToken("demo", "x",
            List.of(new SimpleGrantedAuthority("OIDC_USER")));

    @Test
    void aMemberIsSignedInWithoutTheOperatorRole() {
        assertThat(OIDC.member(MEMBER)).isTrue();
        assertThat(OIDC.member(null)).isTrue();
        assertThat(OIDC.member(OPERATOR)).isFalse();
        assertThat(NONE.member(MEMBER)).as("no login: the single owner").isFalse();
    }

    @Test
    void aMembersStatusHasTheSessionAndItsTradesOnly() {
        SessionRunner runner = mock(SessionRunner.class);
        TradingSession session = mock(TradingSession.class);
        Map<String, Object> full = new LinkedHashMap<>();
        for (String key : List.of("status", "session", "date", "mode", "lastEvent", "openPositions", "closedPositions",
                "strategy", "capital", "dayPnl", "killSwitches", "recentRejections", "dailyLossLimit", "account")) {
            full.put(key, key);
        }
        when(runner.current()).thenReturn(session);
        when(session.status()).thenReturn(full);
        when(runner.schedule()).thenReturn(Map.of("mode", "auto"));

        assertThat(new TradingController(runner, OIDC).status(MEMBER).getBody()).containsOnlyKeys(
                "status", "session", "date", "mode", "lastEvent", "openPositions", "closedPositions");
        assertThat(new TradingController(runner, OIDC).status(OPERATOR).getBody())
                .containsKeys("strategy", "capital", "killSwitches", "recentRejections", "schedule");
        assertThat(new TradingController(runner, NONE).status(null).getBody()).containsKeys("strategy", "schedule");
    }

    @Test
    void aMembersChartHasTheIndexAndVolumeWithoutSurges() {
        Map<String, Object> full = Map.of("underlying", "NIFTY", "index", List.of(List.of("09:15", 22300.0)),
                "volume", List.of(List.of("09:15", 1000, 800.0)), "surges", List.of(Map.of("t", "09:16")),
                "futuresExpiry", "2026-11-25", "optionsExpiry", "2026-11-11", "historyAvailable", false);
        Map<String, Object> chart = SurgeController.chartOnly(full);
        assertThat(chart).containsOnlyKeys("underlying", "index", "volume", "surges");
        assertThat(chart.get("surges")).isEqualTo(List.of());
        assertThat(chart.get("index")).isEqualTo(full.get("index"));
        assertThat(chart.get("volume")).isEqualTo(full.get("volume"));
    }
}
