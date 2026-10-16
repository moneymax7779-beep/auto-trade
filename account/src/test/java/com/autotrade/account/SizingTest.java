package com.autotrade.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Follower sizing: the strategy budget scaled to the follower's equity, then the live ladder's step. */
class SizingTest {

    @Test
    void theBudgetScalesWithTheFollowersEquityAndMultiplier() {
        // a ₹5L budget at ₹5L reference capital; a ₹2L follower at 1.0 gets ₹2L, at 0.5 gets ₹1L
        assertThat(Sizing.followerBudget(500_000, 500_000, 200_000, 1.0)).isEqualTo(200_000);
        assertThat(Sizing.followerBudget(500_000, 500_000, 200_000, 0.5)).isEqualTo(100_000);
        assertThat(Sizing.followerBudget(500_000, 0, 200_000, 1.0)).isZero();
    }

    @Test
    void thePilotStepIsOneLotAndOtherStepsTakeTheirShareInWholeLots() {
        // NIFTY lot 65 at ₹120: one lot = ₹7,800
        assertThat(Sizing.lots(200_000, 120, 65, 0)).isEqualTo(1);
        assertThat(Sizing.lots(5_000, 120, 65, 0)).isZero();            // even one lot does not fit
        assertThat(Sizing.lots(200_000, 120, 65, 10)).isEqualTo(2);     // ₹20,000 -> 2 lots (₹15,600)
        assertThat(Sizing.lots(200_000, 120, 65, 100)).isEqualTo(25);   // ₹2L -> 25 lots (₹1,95,000)
        assertThat(Sizing.lots(200_000, Double.NaN, 65, 100)).isZero(); // no ask, no order
    }
}
