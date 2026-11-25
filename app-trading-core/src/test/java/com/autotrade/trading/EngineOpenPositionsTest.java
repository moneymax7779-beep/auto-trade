package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Admin exit desk (user 20 Nov 2026, phase A): the engine's own open positions, read-only, from every live session running
 * now (PAPER-1 and the signal source SIGNAL-1 alongside it); a replay's are left out (nobody holds them).
 */
class EngineOpenPositionsTest {

    private static final AuthProperties NONE = new AuthProperties("none", null, null, null, null, null, null, null);

    private static TradingSession session(String account, TradingSession.Mode mode, List<Map<String, Object>> open) {
        return session(account, mode, open, account.startsWith("SIGNAL"));
    }

    private static TradingSession session(String account, TradingSession.Mode mode, List<Map<String, Object>> open,
                                          boolean signalSource) {
        TradingSession s = mock(TradingSession.class);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("session", 7L);
        status.put("status", "RUNNING");
        status.put("mode", mode);
        status.put("date", "2026-11-20");
        status.put("account", account);
        status.put("lastEvent", "11:02:03");
        status.put("signalSource", signalSource);
        status.put("capital", 500_000);                         // not passed on
        status.put("openPositions", open);
        when(s.status()).thenReturn(status);
        return s;
    }

    @Test
    void everyLiveSessionsOpenPositionsAndNoReplay() {
        Map<String, Object> position = Map.of("strategy", "oi-burst-cross", "symbol", "NIFTY 25450 CE 25 NOV 26",
                "quantity", 130, "averageCost", 101.5);
        List<TradingSession> sessions = List.of(session("PAPER-1", TradingSession.Mode.PAPER_LIVE, List.of(position)),
                session("SIGNAL-1", TradingSession.Mode.PAPER_LIVE, List.of()),
                session("REPLAY-1", TradingSession.Mode.PAPER_REPLAY, List.of(position)));
        SessionRunner runner = mock(SessionRunner.class);
        when(runner.sessions()).thenReturn(sessions);

        List<Map<String, Object>> out = new TradingController(runner, NONE).openPositions();
        assertThat(out).extracting(s -> s.get("account")).containsExactly("PAPER-1", "SIGNAL-1");
        assertThat(out.getFirst()).containsOnlyKeys("session", "account", "mode", "date", "status", "lastEvent", "signalSource",
                        "killSwitches", "positions")
                .containsEntry("positions", List.of(position)).containsEntry("signalSource", false);
        // the exit desk's book choice (25 Nov 2026): SIGNAL-1's exits reach every follower, LIVE and paper
        assertThat(out.get(1)).containsEntry("signalSource", true);
        assertThat(new TradingController(mock(SessionRunner.class), NONE).openPositions()).isEmpty();
    }
}
