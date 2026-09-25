package com.autotrade.research;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.autotrade.sim.OptionPositionSimulator;

/**
 * One traded campaign: every tranche and the exit of one side's position, per fill model (lane).
 * {@code risk} is the rupee loss if the first tranche had hit its premium stop; {@code r} = net / risk.
 */
public record Episode(
        String lane,
        LocalDate session,
        String underlying,
        String side,
        String symbol,
        String entryStage,
        List<String> stages,
        String exitReason,
        int tranches,
        long maxQuantity,
        double averageCost,
        double gross,
        double costs,
        double net,
        double risk,
        double r,
        double maxFavourablePerUnit,
        double maxAdversePerUnit,
        Instant openedAt,
        Instant closedAt,
        List<OptionPositionSimulator.Leg> legs) {

    public boolean win() {
        return net > 0;
    }
}
