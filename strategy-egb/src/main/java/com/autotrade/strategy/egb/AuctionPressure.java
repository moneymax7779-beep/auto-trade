package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.CasFeatures;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.strategy.OptionSide;

/**
 * F. Closing-auction pressure (A-040): at the first auction minute with enough constituent coverage, the weighted
 * constituent auction return decides the side; held to {@code flat_by} (15:29). The index is frozen during the auction,
 * so there is no index stop or target: only the resting premium stop.
 */
final class AuctionPressure extends SetupStrategy {

    private final double minCoveragePct, minPressurePct;
    private boolean decided;

    AuctionPressure(ThresholdConfig c) {
        super(c);
        minCoveragePct = c.getDouble("setup.min_coverage_pct");
        minPressurePct = c.getDouble("setup.min_pressure_pct");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        CasFeatures cas = s.cas();
        double coverage = cas == null ? Double.NaN : cas.constituentCoveragePct();
        double pressure = cas == null ? Double.NaN : cas.weightedIepReturnPct();
        boolean ready = Double.isFinite(coverage) && coverage >= minCoveragePct && Double.isFinite(pressure);
        c.put("auction_data", ready);
        if (!canEnter || decided || !ready) {
            return null;
        }
        decided = true;                                               // the first auction minute with data decides
        if (pressure >= minPressurePct) {
            return new Entry(OptionSide.CE, Double.NaN, Double.NaN, "AUCTION_PRESSURE_UP");
        }
        if (pressure <= -minPressurePct) {
            return new Entry(OptionSide.PE, Double.NaN, Double.NaN, "AUCTION_PRESSURE_DOWN");
        }
        return null;
    }
}
