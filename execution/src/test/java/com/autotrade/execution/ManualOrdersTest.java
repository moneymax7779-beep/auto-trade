package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** "Best ATM" (user, 9 Nov 2026): of the strike nearest spot and one either side, the buyable one with the tightest spread. */
class ManualOrdersTest {

    private static ManualOrders.Candidate c(double strike, double spreadPct, String refusal) {
        return new ManualOrders.Candidate(1, "NIFTY " + (int) strike + " CE", strike, LocalDate.now(), 65, 100, 100.5, 6500,
                spreadPct, Instant.now(), refusal);
    }

    @Test
    void theStrikesAroundSpotAreTheNearestAndOneEitherSide() {
        List<Double> strikes = List.of(23200.0, 23000.0, 23100.0, 23050.0, 23150.0);
        assertThat(ManualOrders.aroundSpot(strikes, 23112)).containsExactly(23050.0, 23100.0, 23150.0);
        assertThat(ManualOrders.aroundSpot(strikes, 23125)).as("a tie takes the lower").containsExactly(23050.0, 23100.0,
                23150.0);
        assertThat(ManualOrders.aroundSpot(strikes, 22900)).as("at the edge of the band").containsExactly(23000.0, 23050.0);
        assertThat(ManualOrders.aroundSpot(strikes, 23400)).containsExactly(23150.0, 23200.0);
        assertThat(ManualOrders.aroundSpot(List.of(), 23100)).isEmpty();
    }

    @Test
    void theTightestBuyableSpreadWinsAndATieGoesToTheNearerStrike() {
        assertThat(ManualOrders.best(List.of(c(23050, 0.4, null), c(23100, 0.9, null), c(23150, 0.2, null)), 23110).strike())
                .isEqualTo(23150);
        assertThat(ManualOrders.best(List.of(c(23050, 0.1, "last quote 09:20:01"), c(23100, 0.9, null),
                c(23150, 0.5, null)), 23110).strike()).as("a refused strike is never chosen").isEqualTo(23150);
        assertThat(ManualOrders.best(List.of(c(23050, 0.5, null), c(23100, 0.5, null), c(23150, 0.5, null)), 23110).strike())
                .isEqualTo(23100);
        assertThat(ManualOrders.best(List.of(c(23100, 0.5, "no two-sided quote")), 23100)).isNull();
    }
}
