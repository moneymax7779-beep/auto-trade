package com.autotrade.risk;

import java.time.Instant;
import java.time.LocalTime;

/**
 * Pre-trade checks for entries and adds. Exits are not checked: risk may always reduce exposure.
 * A breach of the daily loss limit also engages the account's kill switch.
 */
public final class RiskEngine {

    private final RiskLimits limits;
    private final KillSwitch killSwitch;

    public RiskEngine(RiskLimits limits, KillSwitch killSwitch) {
        this.limits = limits;
        this.killSwitch = killSwitch;
    }

    public RiskLimits limits() {
        return limits;
    }

    public KillSwitch killSwitch() {
        return killSwitch;
    }

    public RiskDecision checkEntry(RiskCheck c, Instant now) {
        var blocked = killSwitch.blocking(c.account(), c.strategy());
        if (blocked.isPresent()) {
            return RiskDecision.rejected("kill switch " + blocked.get().scope() + ": " + blocked.get().reason());
        }
        if (c.dayPnl() <= -limits.dailyLossLimit()) {
            killSwitch.engage("ACCOUNT:" + c.account(), "daily loss limit " + limits.dailyLossLimit() + " reached",
                    now, "risk");
            return RiskDecision.rejected("daily loss limit");
        }
        if (!c.time().isBefore(limits.noNewEntriesAfter()) && !limits.inCasEntryWindow(c.time())) {
            return RiskDecision.rejected("after " + limits.noNewEntriesAfter());
        }
        if (!c.add() && c.openPositions() >= limits.maxOpenPositions()) {
            return RiskDecision.rejected("max open positions " + limits.maxOpenPositions());
        }
        if (c.lotsAlreadyHeldInUnderlying() + c.lots() > limits.maxLotsPerUnderlying()) {
            return RiskDecision.rejected("max lots per underlying " + limits.maxLotsPerUnderlying());
        }
        if (c.premium() > 0 && c.premiumInUse() + c.premium() > limits.capital()) {
            return RiskDecision.rejected(String.format("capital: premium %.0f + in use %.0f over %.0f", c.premium(),
                    c.premiumInUse(), limits.capital()));
        }
        if (c.ordersLastMinute() >= limits.maxOrdersPerMinute()) {
            return RiskDecision.rejected("order rate");
        }
        if (!(c.secondsSinceSpot() <= limits.maxFeedAgeSec()) || !(c.secondsSinceOption() <= limits.maxFeedAgeSec())) {
            return RiskDecision.rejected("stale feed (spot " + c.secondsSinceSpot() + " s, option "
                    + c.secondsSinceOption() + " s)");
        }
        if (Double.isNaN(c.spreadPct()) || c.spreadPct() > limits.maxSpreadPct()) {
            return RiskDecision.rejected("spread " + c.spreadPct() + "% over " + limits.maxSpreadPct() + "%");
        }
        return RiskDecision.APPROVED;
    }

    /** True from the square-off time on: everything must be closed. */
    public boolean squareOffDue(LocalTime time) {
        return !time.isBefore(limits.squareOffAt());
    }
}
