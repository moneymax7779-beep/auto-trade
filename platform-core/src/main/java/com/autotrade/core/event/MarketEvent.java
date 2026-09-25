package com.autotrade.core.event;

import java.time.Instant;

/**
 * One observation from the market, as the platform received it. Replay and live trading consume the
 * same event types, ordered by {@link #receivedAt()} and then {@link #sourceSequence()}.
 */
public sealed interface MarketEvent
        permits IndexTick, FutureTick, OptionTick, ConstituentTick, SessionPhaseEvent {

    /** When the platform received the observation; the only time features may act on. */
    Instant receivedAt();

    /** Canonical underlying code, e.g. "NIFTY". */
    String underlying();

    /** Capture order within the underlying's session; 0 when the source has none. */
    long sourceSequence();

    EventKind kind();
}
