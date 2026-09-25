package com.autotrade.md.store;

import com.autotrade.core.event.SessionPhaseEvent;

/** A session-phase event plus the source's phase bookkeeping, stored as-is for later analysis. */
public record SessionPhaseRow(
        SessionPhaseEvent event,
        String eventTimePhase,
        String receivedTimePhase,
        Boolean continuouslyTradable,
        String source,
        String idempotencyKey) {
}
