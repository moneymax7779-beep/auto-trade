package com.autotrade.research;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.time.SessionPhase;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.SideView;
import com.autotrade.strategy.Stage;

/**
 * Frame-level (per-minute observation) statistics for one lane: time spent in each stage, and how
 * often each condition held when price was near the level. These describe the funnel; they are not
 * opportunities and are reported apart from episodes.
 */
public final class FrameStats implements FrameSink {

    private final String lane;
    final Map<String, Map<Stage, Integer>> stageFrames = new TreeMap<>();
    final Map<String, Stage> maxStage = new TreeMap<>();
    final Map<String, Map<String, int[]>> conditionHits = new TreeMap<>();
    final Map<String, Integer> nearFrames = new TreeMap<>();
    final Map<String, Integer> allEarlyFrames = new TreeMap<>();

    public FrameStats(String lane) {
        this.lane = lane;
    }

    @Override
    public void frame(String frameLane, FeatureSnapshot snapshot, Decision decision) {
        if (!lane.equals(frameLane) || !SessionPhase.valueOf(snapshot.phase()).isContinuous()) {
            return;
        }
        record(snapshot, decision.ce());
        record(snapshot, decision.pe());
    }

    private void record(FeatureSnapshot snapshot, SideView view) {
        String key = snapshot.underlying() + " " + view.side();
        stageFrames.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(view.stage(), 1, Integer::sum);
        String sessionKey = key + " " + snapshot.session();
        Stage previous = maxStage.get(sessionKey);
        if (previous == null || rank(view.stage()) > rank(previous)) {
            maxStage.put(sessionKey, view.stage());
        }
        if (!Boolean.TRUE.equals(view.conditions().get("near_level"))) {
            return;
        }
        nearFrames.merge(key, 1, Integer::sum);
        if (view.earlyScore() >= 100) {
            allEarlyFrames.merge(key, 1, Integer::sum);
        }
        Map<String, int[]> hits = conditionHits.computeIfAbsent(key, k -> new LinkedHashMap<>());
        view.conditions().forEach((name, value) -> hits.computeIfAbsent(name, n -> new int[1])[0] += value ? 1 : 0);
    }

    /** Stage order for "furthest reached" (EXITED ranks with the position stages it came from). */
    private static int rank(Stage stage) {
        return switch (stage) {
            case IDLE -> 0;
            case WATCH, COMPRESSION -> 1;
            case ARMED -> 2;
            case EARLY_ENTRY -> 3;
            case CONFIRMED, RETEST -> 4;
            case RUNNER -> 5;
            case EXITED -> 3;
        };
    }
}
