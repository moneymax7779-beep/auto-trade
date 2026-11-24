package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The manual target's lock rule and price (user 24 Nov 2026), without a database. */
class PositionTargetsTest {

    @Test
    void thePriceIsTheEntryPlusThePercentToTheTick() {
        assertThat(PositionTargets.priceOf(100, 20)).isEqualTo(120.0);
        assertThat(PositionTargets.priceOf(146.35, 20)).isEqualTo(175.6);   // 175.62
        assertThat(PositionTargets.priceOf(87.15, 12.5)).isEqualTo(98.05);  // 98.04375
    }

    @Test
    void aPercentIsTwoDecimalsAboveZeroAndAtMostTheMax() {
        assertThat(PositionTargets.pct(12.346)).isEqualTo(12.35);
        assertThat(PositionTargets.pct(0.004)).as("0 after rounding").isNull();
        assertThat(PositionTargets.pct(0.0)).isNull();
        assertThat(PositionTargets.pct(-5.0)).isNull();
        assertThat(PositionTargets.pct(1000.0)).isEqualTo(1000.0);
        assertThat(PositionTargets.pct(1000.01)).isNull();
        assertThat(PositionTargets.pct(Double.NaN)).isNull();
        assertThat(PositionTargets.pct(null)).isNull();
    }

    @Test
    void waitingArmsOnlyWhenTheHighReachedThePrice() {
        assertThat(PositionTargets.decide(false, null, null, 120)).isEqualTo(PositionTargets.Step.NONE);
        assertThat(PositionTargets.decide(false, 119.95, 119.95, 120)).isEqualTo(PositionTargets.Step.NONE);
        assertThat(PositionTargets.decide(false, 120.0, 110.0, 120)).as("a touch counts, whatever the bid is now")
                .isEqualTo(PositionTargets.Step.ARM);
        assertThat(PositionTargets.decide(false, 135.0, 135.0, 120)).isEqualTo(PositionTargets.Step.ARM);
    }

    @Test
    void armedSellsOnlyOnAFreshBidAtOrBelowThePrice() {
        assertThat(PositionTargets.decide(true, 140.0, 125.0, 120)).as("riding above the target")
                .isEqualTo(PositionTargets.Step.NONE);
        assertThat(PositionTargets.decide(true, null, null, 120)).as("no fresh bid: nothing sold")
                .isEqualTo(PositionTargets.Step.NONE);
        assertThat(PositionTargets.decide(true, null, 120.0, 120)).isEqualTo(PositionTargets.Step.SELL);
        assertThat(PositionTargets.decide(true, null, 104.0, 120)).as("a gap through the target still sells")
                .isEqualTo(PositionTargets.Step.SELL);
    }
}
