package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.autotrade.execution.ChartLines.Event;
import com.autotrade.execution.ChartLines.Kind;
import com.autotrade.execution.ChartLines.Lines;

/** The chart-order line rules (plan 2.2–2.4) for a call and the mirror-image put. */
class ChartLinesTest {

    /** A NIFTY call: entry 24,250, stop 24,200, target 24,350, buffer 2, no-chase 8. */
    private static final Lines CALL = new Lines("CE", 24_250, 24_200, 24_350, 2, 8);
    /** The same trade as a put: entry 24,250, stop 24,300, target 24,150. */
    private static final Lines PUT = new Lines("PE", 24_250, 24_300, 24_150, 2, 8);

    @Test
    void theEntryKindComesFromWhereTheIndexIsWhenArmed() {
        assertThat(ChartLines.validate(CALL, 24_230)).isEqualTo(Kind.BREAKOUT);
        assertThat(ChartLines.validate(CALL, 24_280)).isEqualTo(Kind.PULLBACK);
        assertThat(ChartLines.validate(PUT, 24_270)).isEqualTo(Kind.BREAKOUT);      // a breakdown, for a put
        assertThat(ChartLines.validate(PUT, 24_220)).isEqualTo(Kind.PULLBACK);      // a rally back to resistance
    }

    @Test
    void linesInTheWrongOrderOrTooCloseAreRefusedWithTheReason() {
        assertThatThrownBy(() -> ChartLines.validate(new Lines("CE", 24_250, 24_300, 24_350, 2, 8), 24_230))
                .hasMessage("a CE's stop must be below its entry and its target above it");
        assertThatThrownBy(() -> ChartLines.validate(new Lines("PE", 24_250, 24_200, 24_150, 2, 8), 24_270))
                .hasMessage("a PE's stop must be above its entry and its target below it");
        assertThatThrownBy(() -> ChartLines.validate(new Lines("CE", 24_250, 24_247, 24_350, 2, 8), 24_230))
                .hasMessageStartingWith("the stop is 3 pts from the entry: at least 4 pts");
        assertThatThrownBy(() -> ChartLines.validate(new Lines("CE", 24_250, 24_200, 24_251, 2, 8), 24_230))
                .hasMessageStartingWith("the target is 1 pts from the entry");
        assertThatThrownBy(() -> ChartLines.validate(CALL, 24_247.5))
                .hasMessageStartingWith("the entry line is 2.5 pts from the index (24247.5): it would fire at once");
        assertThatThrownBy(() -> ChartLines.validate(new Lines("XX", 1, 1, 1, 2, 8), 1)).hasMessage("side: CE or PE");
        assertThatThrownBy(() -> ChartLines.validate(new Lines("CE", 24_250, 24_200, Double.NaN, 2, 8), 24_230))
                .hasMessageStartingWith("entry, stop and target must be index prices");
    }

    @Test
    void aWaitingOrdersStopOrTargetMovesNearTheEntryButAMovedEntryIsCheckedAnew() {
        // code review 15 Nov 2026: the index 2.5 points under a breakout's entry; the trader tightens the stop
        Lines tighter = new Lines("CE", 24_250, 24_220, 24_350, 2, 8);
        assertThat(ChartLines.validateArmedMove(tighter, Kind.BREAKOUT, 24_247.5, false)).isEqualTo(Kind.BREAKOUT);
        assertThatThrownBy(() -> ChartLines.validateArmedMove(tighter, Kind.BREAKOUT, 24_247.5, true))
                .hasMessageContaining("it would fire at once");
        assertThatThrownBy(() -> ChartLines.validateArmedMove(new Lines("CE", 24_250, 24_247, 24_350, 2, 8), Kind.BREAKOUT,
                24_249, false)).hasMessageStartingWith("the stop is 3 pts from the entry");
        assertThatThrownBy(() -> ChartLines.validateOpen(new Lines("CE", 24_250, 24_200, Double.POSITIVE_INFINITY, 2, 8),
                24_260)).hasMessageStartingWith("stop and target must be index prices");
    }

    @Test
    void aBreakoutFiresAtTheBufferPastTheLineAndIsNotChasedFurther() {
        assertThat(ChartLines.onArmed(CALL, Kind.BREAKOUT, 24_240)).isEqualTo(Event.NONE);
        assertThat(ChartLines.onArmed(CALL, Kind.BREAKOUT, 24_251)).as("a one-point poke").isEqualTo(Event.NONE);
        assertThat(ChartLines.onArmed(CALL, Kind.BREAKOUT, 24_252)).isEqualTo(Event.ENTER);
        assertThat(ChartLines.onArmed(CALL, Kind.BREAKOUT, 24_258)).as("8 past: still in the band").isEqualTo(Event.ENTER);
        assertThat(ChartLines.onArmed(CALL, Kind.BREAKOUT, 24_258.5)).as("a gap past the band").isEqualTo(Event.VOID_CHASE);
        assertThat(ChartLines.onArmed(CALL, Kind.BREAKOUT, 24_200)).as("the stop came first").isEqualTo(Event.VOID_STOP_FIRST);

        assertThat(ChartLines.onArmed(PUT, Kind.BREAKOUT, 24_249)).isEqualTo(Event.NONE);
        assertThat(ChartLines.onArmed(PUT, Kind.BREAKOUT, 24_248)).isEqualTo(Event.ENTER);
        assertThat(ChartLines.onArmed(PUT, Kind.BREAKOUT, 24_241)).isEqualTo(Event.VOID_CHASE);
        assertThat(ChartLines.onArmed(PUT, Kind.BREAKOUT, 24_300)).isEqualTo(Event.VOID_STOP_FIRST);
    }

    @Test
    void theKindTheTicketShowedMustBeTheKindTheLinesArmAs() {
        // review 16 Nov 2026 (D7)
        ChartLines.requireKind(Kind.BREAKOUT, "BREAKOUT", 24_230);
        ChartLines.requireKind(Kind.BREAKOUT, null, 24_230);                           // not given: not checked
        ChartLines.requireKind(Kind.FAR_BREAKOUT, "far_breakout", 24_150);
        assertThatThrownBy(() -> ChartLines.requireKind(Kind.PULLBACK, "BREAKOUT", 24_280))
                .hasMessage("the index is at 24280 now: these lines arm as a pullback, not the breakout the ticket showed: "
                        + "nothing armed; check the ticket again");
        assertThatThrownBy(() -> ChartLines.requireKind(Kind.PULLBACK, "SIDEWAYS", 24_280)).hasMessageContaining("unknown");
    }

    @Test
    void aBreakoutWhoseEntryTickIsAlreadyAtTheTargetIsNotBought() {
        // review 16 Nov 2026: target 5 past the entry, inside the no-chase band of 8: the entering tick could pass it
        Lines close = new Lines("CE", 24_250, 24_200, 24_255, 2, 8);
        assertThat(ChartLines.onArmed(close, Kind.BREAKOUT, 24_254)).isEqualTo(Event.ENTER);
        assertThat(ChartLines.onArmed(close, Kind.BREAKOUT, 24_255)).isEqualTo(Event.VOID_CHASE);
        assertThat(ChartLines.onArmed(close, Kind.FAR_BREAKOUT, 24_256)).isEqualTo(Event.VOID_CHASE);
        Lines put = new Lines("PE", 24_250, 24_300, 24_245, 2, 8);
        assertThat(ChartLines.onArmed(put, Kind.BREAKOUT, 24_246)).isEqualTo(Event.ENTER);
        assertThat(ChartLines.onArmed(put, Kind.BREAKOUT, 24_245)).isEqualTo(Event.VOID_CHASE);
    }

    @Test
    void aPullbackFiresWhenTheIndexComesBackToTheLine() {
        assertThat(ChartLines.onArmed(CALL, Kind.PULLBACK, 24_251)).isEqualTo(Event.NONE);
        assertThat(ChartLines.onArmed(CALL, Kind.PULLBACK, 24_250)).isEqualTo(Event.ENTER);
        assertThat(ChartLines.onArmed(CALL, Kind.PULLBACK, 24_241)).as("a fall through the support").isEqualTo(Event.VOID_CHASE);
        assertThat(ChartLines.onArmed(CALL, Kind.PULLBACK, 24_199)).isEqualTo(Event.VOID_STOP_FIRST);

        assertThat(ChartLines.onArmed(PUT, Kind.PULLBACK, 24_249)).isEqualTo(Event.NONE);
        assertThat(ChartLines.onArmed(PUT, Kind.PULLBACK, 24_250)).isEqualTo(Event.ENTER);
    }

    @Test
    void anOpenPositionStopsAtTheBufferPastTheStopAndTakesProfitAtTheTarget() {
        assertThat(ChartLines.onOpen(CALL, 24_300)).isEqualTo(Event.NONE);
        assertThat(ChartLines.onOpen(CALL, 24_199)).as("a poke through the stop").isEqualTo(Event.NONE);
        assertThat(ChartLines.onOpen(CALL, 24_198)).isEqualTo(Event.STOP);
        assertThat(ChartLines.onOpen(CALL, 24_350)).isEqualTo(Event.TARGET);

        assertThat(ChartLines.onOpen(PUT, 24_301)).isEqualTo(Event.NONE);
        assertThat(ChartLines.onOpen(PUT, 24_302)).isEqualTo(Event.STOP);
        assertThat(ChartLines.onOpen(PUT, 24_150)).isEqualTo(Event.TARGET);
    }

    @Test
    void anOpenStopMayTrailPastTheEntryButNotPastTheIndex() {
        ChartLines.validateOpen(new Lines("CE", 24_250, 24_260, 24_350, 2, 8), 24_300);   // locks in profit
        assertThatThrownBy(() -> ChartLines.validateOpen(new Lines("CE", 24_250, 24_299, 24_350, 2, 8), 24_300))
                .hasMessageContaining("it would sell at once; use Exit now");
        assertThatThrownBy(() -> ChartLines.validateOpen(new Lines("PE", 24_250, 24_300, 24_290, 2, 8), 24_280))
                .hasMessageStartingWith("that target is at or beyond the index");
    }

    @Test
    void theBrokerSafetyStopSitsBeyondThePremiumExpectedAtTheIndexStop() {
        // 50 points + 2 × 2 buffer = 54; at delta 0.5 the premium falls 27 from 120 to 93; less 15 %: 79.05, 34.1 % below
        assertThat(ChartLines.safetyStopPct(CALL, 120)).isEqualTo(34.1);
        assertThat(ChartLines.safetyStopPct(PUT, 120)).isEqualTo(34.1);
        // a tight stop on a dear option: 14 points move the premium 7 of 300; the 15 % margin is most of the distance
        assertThat(ChartLines.safetyStopPct(new Lines("CE", 24_250, 24_240, 24_350, 2, 8), 300)).isEqualTo(17.0);
        assertThat(ChartLines.safetyStopPct(CALL, 20)).as("the option would be worthless at the stop").isEqualTo(60.0);
        assertThat(ChartLines.safetyStopPct(CALL, Double.NaN)).isEqualTo(60.0);
    }

    @Test
    void linesAreCheckedFromTheOpenNotOnThePreOpenAuctionsIndex() {
        assertThat(ChartLines.checked(java.time.LocalTime.of(9, 7, 30))).isFalse();
        assertThat(ChartLines.checked(java.time.LocalTime.of(9, 14, 59))).isFalse();
        assertThat(ChartLines.checked(java.time.LocalTime.of(9, 15))).isTrue();
        assertThat(ChartLines.checked(java.time.LocalTime.of(15, 20))).isTrue();
    }

    @Test
    void anEntryWithItsStopBetweenItAndTheIndexIsAPlainStopEntry() {
        // 15 Nov 2026: Buy PE drawn at 25,069 with the index at 25,145.75 and the ticket's default stop 30 above (25,099)
        Lines put = new Lines("PE", 25_069, 25_099, 25_009, 2, 8);
        assertThat(ChartLines.validate(put, 25_145.75)).isEqualTo(Kind.FAR_BREAKOUT);
        assertThat(ChartLines.validate(CALL, 24_201)).as("within the buffer of the stop").isEqualTo(Kind.FAR_BREAKOUT);
        assertThat(ChartLines.validate(CALL, 24_150)).isEqualTo(Kind.FAR_BREAKOUT);
        assertThat(ChartLines.validate(CALL, 24_203)).isEqualTo(Kind.BREAKOUT);

        // the index on its way through the stop line and back does not void it; it fires like a breakout
        assertThat(ChartLines.onArmed(put, Kind.FAR_BREAKOUT, 25_120)).isEqualTo(Event.NONE);
        assertThat(ChartLines.onArmed(put, Kind.FAR_BREAKOUT, 25_080)).isEqualTo(Event.NONE);
        assertThat(ChartLines.onArmed(put, Kind.FAR_BREAKOUT, 25_110)).as("back past the stop line").isEqualTo(Event.NONE);
        assertThat(ChartLines.onArmed(put, Kind.FAR_BREAKOUT, 25_068)).as("a one-point poke").isEqualTo(Event.NONE);
        assertThat(ChartLines.onArmed(put, Kind.FAR_BREAKOUT, 25_067)).isEqualTo(Event.ENTER);
        assertThat(ChartLines.onArmed(put, Kind.FAR_BREAKOUT, 25_060)).as("a gap past the band").isEqualTo(Event.VOID_CHASE);
        // once bought, its stop is an ordinary stop
        assertThat(ChartLines.onOpen(put, 25_101)).isEqualTo(Event.STOP);
    }

    @Test
    void aBreakoutsStopMovedPastTheIndexMakesItAFarOneAndBack() {
        Lines stopAbove = new Lines("CE", 24_250, 24_235, 24_350, 2, 8);
        assertThat(ChartLines.validateArmedMove(stopAbove, Kind.BREAKOUT, 24_230, false)).isEqualTo(Kind.FAR_BREAKOUT);
        assertThat(ChartLines.validateArmedMove(CALL, Kind.FAR_BREAKOUT, 24_230, false)).isEqualTo(Kind.BREAKOUT);
        assertThatThrownBy(() -> ChartLines.validateArmedMove(new Lines("CE", 24_250, 24_275, 24_350, 2, 8), Kind.PULLBACK,
                24_270, false)).hasMessageContaining("stop must be below its entry");
    }
}
