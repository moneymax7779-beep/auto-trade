package com.autotrade.execution;

import java.nio.file.Path;

import com.autotrade.account.CapitalLimits;
import com.autotrade.config.ThresholdConfig;

/** A follower's risk file (config/risk/follower-risk.vN.yaml): capital limits and the simulated fill model. */
public record FollowerRisk(String version, CapitalLimits limits, double adverseBps) {

    public static FollowerRisk load(Path file) {
        ThresholdConfig c = ThresholdConfig.load(file);
        return new FollowerRisk(c.version(), new CapitalLimits(c.getDouble("capital.daily_loss_pct"),
                c.getDouble("capital.max_premium_per_trade_pct"), c.getDouble("capital.max_open_premium_pct"),
                c.getDouble("capital.broker_funds_buffer_pct")),
                c.has("simulation.adverse_bps") ? c.getDouble("simulation.adverse_bps") : 0);
    }
}
