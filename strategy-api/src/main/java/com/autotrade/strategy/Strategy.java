package com.autotrade.strategy;

import com.autotrade.features.snapshot.FeatureSnapshot;

/**
 * A strategy instance for one underlying and session. It is called once per feature snapshot, in
 * time order, and may keep state between calls. It must use only the snapshot and the position it
 * is given, so replay and live behave identically.
 */
public interface Strategy {

    String id();

    /** Content hash of the configuration this instance runs with. */
    String configHash();

    Decision decide(FeatureSnapshot snapshot, PositionView position);
}
