package com.autotrade.strategy;

import java.util.Map;

/**
 * One side's evaluation at a snapshot: its stage, its three scores (0..100) and every named
 * condition the strategy checked, so a replay can show exactly why a stage was or was not reached.
 */
public record SideView(OptionSide side, Stage stage, double earlyScore, double confirmScore, double runnerScore,
                       Map<String, Boolean> conditions) {
}
