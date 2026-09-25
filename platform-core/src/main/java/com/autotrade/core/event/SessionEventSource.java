package com.autotrade.core.event;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

/**
 * A recorded trading session that can be streamed in replay order. Implementations read from the
 * platform's own store or directly from another capture, and must deliver the same event model.
 */
public interface SessionEventSource {

    /** Short name for logs and run records, e.g. "zt-tiger-v2" or "own-store". */
    String name();

    ReplayResult replay(LocalDate session, List<String> underlyings, Consumer<MarketEvent> sink) throws Exception;

    /**
     * @param delivered  events passed to the sink
     * @param lateEvents events that arrived later than the reordering window allowed and were
     *                   delivered out of order (0 means the stream was fully ordered)
     */
    record ReplayResult(long delivered, long lateEvents) {
    }
}
