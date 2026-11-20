package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Admin exit desk review (20 Nov 2026): an exit whose order may still work or may have filled is UNKNOWN, never REFUSED,
 * and says not to sell by hand; a sale is judged against the position as read just before the exit.
 */
class ExitOutcomeTest {

    @Test
    void soldPartlyAndRefusedAreJudgedFromThePositionBeforeAndAfter() {
        assertThat(ExitOutcome.classify(130, 0L, true, List.of("X: 130 sold at 101.00"), List.of("FILLED"), null).outcome())
                .isEqualTo("SOLD");
        ExitOutcome part = ExitOutcome.classify(130, 65L, false, List.of("X: 65 of 130 sold at 101.00; 65 stay open"),
                List.of("FILLED"), null);
        assertThat(part.outcome()).isEqualTo("PARTLY");
        assertThat(part.detail()).isEqualTo("65 of 130 sold, 65 open");
        assertThat(ExitOutcome.classify(130, 130L, false, List.of("X: NOT exited, no bid and no signal price"), List.of(),
                null).outcome()).isEqualTo("REFUSED");
        assertThat(ExitOutcome.classify(130, 130L, false, List.of("X: 0 of 130 sold (BROKER_REJECTED margin); 130 stay "
                + "open"), List.of("REJECTED"), null).outcome()).as("rejected at the broker: nothing sold, known")
                .isEqualTo("REFUSED");
        assertThat(ExitOutcome.classify(130, 130L, false, List.of("X: 0 of 130 sold (EXIT_NOT_FILLED exit chase ran out: 0 "
                + "of 130 filled); 130 stay open"), List.of("CANCELLED"), null).outcome())
                .as("chased and cancelled, the cancel confirmed").isEqualTo("REFUSED");
        ExitOutcome thrown = ExitOutcome.classify(130, 130L, false, List.of("the exit failed: boom"), List.of(),
                new IllegalStateException("boom"));
        assertThat(thrown.outcome()).isEqualTo("REFUSED");
        assertThat(thrown.detail()).contains("before any order was sent");
    }

    @Test
    void anOrderThatMayStillWorkIsUnknownNeverRefused() {
        for (ExitOutcome o : List.of(
                ExitOutcome.classify(130, 130L, false, List.of("X: 0 of 130 sold (ORDER_UNKNOWN order T1 not found at the "
                        + "broker after placing); 130 stay open"), List.of("UNKNOWN"), null),
                ExitOutcome.classify(130, 130L, false, List.of("X: 0 of 130 sold (ORDER_UNKNOWN still OPEN after its "
                        + "cancel); 130 stay open"), List.of(), null),
                ExitOutcome.classify(130, 130L, false, List.of("X: NOT flattened, sell T9 has an unknown outcome (looked at "
                        + "each turn)"), List.of(), null),
                ExitOutcome.classify(130, 130L, false, List.of("X: 130 sold at 101.00"), List.of("CANCEL_PENDING"), null),
                ExitOutcome.classify(130, 130L, false, List.of("the exit failed: timeout"), List.of("SUBMITTED"),
                        new IllegalStateException("timeout")),
                ExitOutcome.classify(130, 65L, false, List.of("X: 65 of 130 sold (ORDER_UNKNOWN cancel not confirmed)"),
                        List.of("RECONCILIATION_REQUIRED"), null),
                ExitOutcome.classify(130, null, false, List.of("X: 130 sold at 101.00"), List.of("FILLED"), null))) {
            assertThat(o.outcome()).isEqualTo("UNKNOWN");
            assertThat(o.detail()).contains("do not sell by hand in the broker app; check the broker's order book first");
        }
        assertThat(ExitOutcome.classify(130, 65L, false, List.of("X: 65 of 130 sold (ORDER_UNKNOWN cancel not confirmed)"),
                List.of("RECONCILIATION_REQUIRED"), null).detail()).startsWith("65 of 130 sold so far");
    }

    /**
     * Second review 20 Nov 2026: at a broker the exit first takes the position's stop off (a cancel sent to the broker),
     * so a failure on the way, or a stop whose cancel is not confirmed, leaves the stop's state at the broker not known:
     * UNKNOWN even with no exit order written. In the simulation nothing is sent anywhere: REFUSED as before. A clean
     * broker refusal stays REFUSED.
     */
    @Test
    void atABrokerAFailureOrAnUnconfirmedStopCancelIsUnknownEvenWithNoExitOrder() {
        IllegalStateException session = new IllegalStateException("session expired");
        ExitOutcome broker = ExitOutcome.classify(130, 130L, false, List.of("the exit failed: session expired"), List.of(),
                session, true);
        assertThat(broker.outcome()).isEqualTo("UNKNOWN");
        assertThat(broker.detail()).contains("its stop may have been taken off")
                .contains("do not sell by hand in the broker app; check the broker's order book first");
        assertThat(ExitOutcome.classify(130, 130L, false, List.of("the exit failed: boom"), List.of(), session, false)
                .outcome()).as("simulated: nothing was sent anywhere").isEqualTo("REFUSED");
        for (String line : List.of("X: NOT flattened, stop FS12 cancel not confirmed: timeout",
                "X: NOT flattened, stop FS12 not yet cancelled at the broker",
                "X: NOT flattened, order book unreadable: 503")) {
            assertThat(ExitOutcome.classify(130, 130L, false, List.of(line), List.of(), null, true).outcome()).as(line)
                    .isEqualTo("UNKNOWN");
        }
        assertThat(ExitOutcome.classify(130, 130L, false, List.of("X: 0 of 130 sold (BROKER_REJECTED margin); 130 stay "
                + "open"), List.of("REJECTED"), null, true).outcome()).isEqualTo("REFUSED");
        assertThat(ExitOutcome.classify(130, 130L, false, List.of(), List.of(), null, false).detail())
                .as("never a blank reason").isEqualTo("nothing was sold");
    }
}
