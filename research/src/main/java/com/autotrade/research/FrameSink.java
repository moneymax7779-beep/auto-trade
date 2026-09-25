package com.autotrade.research;

import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.strategy.Decision;

/** Receives every strategy decision with the snapshot it was made on (one "frame" per lane and minute). */
public interface FrameSink {

    void frame(String lane, FeatureSnapshot snapshot, Decision decision);

    FrameSink NONE = (lane, snapshot, decision) -> {
    };

    static FrameSink both(FrameSink first, FrameSink second) {
        return (lane, snapshot, decision) -> {
            first.frame(lane, snapshot, decision);
            second.frame(lane, snapshot, decision);
        };
    }
}
