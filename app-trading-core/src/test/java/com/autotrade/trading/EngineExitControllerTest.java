package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Plan 2026-11-23 A1 (user: "As a owner I should be able exit all the open positions, live/paper"): the engine's own
 * positions are exited from the admin exit desk, one (strategy and index in one running live session) or all; a replay is
 * never touched; a reason is required and "EXIT EVERYTHING" typed for all.
 */
class EngineExitControllerTest {

    private static TradingSession session(long id, String account, TradingSession.Mode mode, int open) {
        TradingSession s = mock(TradingSession.class);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("session", id);
        status.put("mode", mode);
        status.put("account", account);
        status.put("openPositions", java.util.Collections.nCopies(open, Map.of("strategy", "etr")));
        when(s.status()).thenReturn(status);
        return s;
    }

    @Test
    void onePositionOfALiveSessionExitsThroughItsOrderManager() {
        TradingSession signal = session(12, "SIGNAL-1", TradingSession.Mode.PAPER_LIVE, 1);
        TradingSession replay = session(13, "REPLAY-1", TradingSession.Mode.PAPER_REPLAY, 1);
        when(signal.exitPosition("expiry-trend-rider", "NIFTY", EngineExitController.REASON)).thenReturn(true);
        SessionRunner runner = mock(SessionRunner.class);
        when(runner.sessions()).thenReturn(List.of(signal, replay));
        EngineExitController api = new EngineExitController(runner, null);

        Map<String, Object> out = api.exitPosition(12, new EngineExitController.PositionExit("expiry-trend-rider", "NIFTY",
                "owner: risk off"), () -> "satya");
        assertThat(out).containsEntry("account", "SIGNAL-1").containsEntry("outcome", "EXITING");
        verify(signal).exitPosition("expiry-trend-rider", "NIFTY", "ADMIN_EXIT");

        // nothing held (or already exiting): answered, not an error
        assertThat(api.exitPosition(12, new EngineExitController.PositionExit("ma-cross", "NIFTY", "owner: risk off"),
                () -> "satya")).containsEntry("outcome", "NOT_HELD");
        // a replay or an unknown session: 404; no reason: 400
        assertThatThrownBy(() -> api.exitPosition(13, new EngineExitController.PositionExit("etr", "NIFTY", "risk"),
                () -> "satya")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        verify(replay, never()).exitPosition(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> api.exitPosition(12, new EngineExitController.PositionExit("etr", "NIFTY", " "),
                () -> "satya")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void everythingExitsEveryLiveSessionAndNeedsThePhrase() {
        TradingSession paper = session(11, "PAPER-1", TradingSession.Mode.PAPER_LIVE, 2);
        TradingSession signal = session(12, "SIGNAL-1", TradingSession.Mode.PAPER_LIVE, 1);
        TradingSession replay = session(13, "REPLAY-1", TradingSession.Mode.PAPER_REPLAY, 3);
        SessionRunner runner = mock(SessionRunner.class);
        when(runner.sessions()).thenReturn(List.of(paper, signal, replay));
        EngineExitController api = new EngineExitController(runner, null);

        assertThatThrownBy(() -> api.exitEverything(new EngineExitController.Everything("risk off", "EXIT ALL", true, null), () -> "satya"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verify(paper, never()).exitAll(anyString());

        Map<String, Object> out = api.exitEverything(new EngineExitController.Everything("risk off", "exit everything", true, null),
                () -> "satya");
        // review 23 Nov 2026: new entries stopped first on each live session, so nothing re-enters on the next signal
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(paper);
        order.verify(paper).engageKillSwitch(org.mockito.ArgumentMatchers.eq("GLOBAL"), anyString(), org.mockito.ArgumentMatchers.eq("satya"));
        order.verify(paper).exitAll("ADMIN_EXIT");
        verify(replay, never()).engageKillSwitch(anyString(), anyString(), anyString());
        // entries stopped only, positions kept
        TradingSession other = session(14, "PAPER-1", TradingSession.Mode.PAPER_LIVE, 1);
        when(runner.sessions()).thenReturn(List.of(other));
        api.exitEverything(new EngineExitController.Everything("pause", "EXIT EVERYTHING", true, false), () -> "satya");
        verify(other).engageKillSwitch(org.mockito.ArgumentMatchers.eq("GLOBAL"), anyString(), org.mockito.ArgumentMatchers.eq("satya"));
        verify(other, never()).exitAll(anyString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) out.get("sessions");
        assertThat(sessions).extracting(r -> r.get("account")).containsExactly("PAPER-1", "SIGNAL-1");
        assertThat(sessions).extracting(r -> r.get("positions")).containsExactly(2, 1);
        verify(paper).exitAll("ADMIN_EXIT");
        verify(signal).exitAll("ADMIN_EXIT");
        verify(replay, never()).exitAll(anyString());
    }

    @Test
    void aSessionsEntriesAreResumedOnlyWhenStopped() {
        TradingSession signal = session(12, "SIGNAL-1", TradingSession.Mode.PAPER_LIVE, 0);
        when(signal.releaseKillSwitch("GLOBAL", "satya")).thenReturn(new TradingSession.Release(List.of("GLOBAL"), null))
                .thenReturn(new TradingSession.Release(List.of(), null));
        SessionRunner runner = mock(SessionRunner.class);
        when(runner.sessions()).thenReturn(List.of(signal));
        EngineExitController api = new EngineExitController(runner, null);
        assertThat(api.releaseEntries(12, new EngineExitController.Release("flat again"), () -> "satya"))
                .containsEntry("account", "SIGNAL-1").containsEntry("released", List.of("GLOBAL"));
        assertThatThrownBy(() -> api.releaseEntries(12, null, () -> "satya"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }
}
