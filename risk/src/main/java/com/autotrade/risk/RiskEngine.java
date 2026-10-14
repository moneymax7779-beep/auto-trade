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
    private int dailyLossReleases;           // v16: releases of the daily-loss switch used today
    private double dailyLossRaise;           // v16: rupees those releases added to the day's loss limit

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
        if (c.dayPnl() <= -dailyLossLimit()) {
            killSwitch.engage("ACCOUNT:" + c.account(), "daily loss limit " + dailyLossLimit() + " reached",
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

    /** The daily loss limit in force: the file's, raised by each release of the daily-loss switch (v16). */
    public synchronized double dailyLossLimit() {
        return limits.dailyLossLimit() + dailyLossRaise;
    }

    /** v16: releases of the daily-loss switch still allowed today (0 when the file allows none). */
    public synchronized int dailyLossReleasesLeft() {
        return Math.max(0, limits.dailyLossReleasesPerDay() - dailyLossReleases);
    }

    /** v16: the limit a release would give: the limit in force plus one raise. */
    public synchronized double dailyLossLimitAfterRelease() {
        return dailyLossLimit() + limits.capital() * limits.dailyLossReleaseRaisePct() / 100.0;
    }

    /**
     * v16 (A-101): a release of the daily-loss switch (by the application for an A-grade entry, or the operator). With
     * releases allowed, each one raises the day's loss limit by {@code daily_loss_release_raise_pct} of equity, up to
     * {@code daily_loss_releases_per_day}; false once they are
     * used (the switch stays). Without them (v1-v15) the release is allowed and the limit unchanged, so the switch
     * engages again at the next entry while the day's loss is still beyond the limit.
     */
    public synchronized boolean releaseDailyLoss() {
        if (limits.dailyLossReleasesPerDay() <= 0) {
            return true;
        }
        if (dailyLossReleases >= limits.dailyLossReleasesPerDay()) {
            return false;
        }
        dailyLossReleases++;
        dailyLossRaise += limits.capital() * limits.dailyLossReleaseRaisePct() / 100.0;
        return true;
    }

    /** True from the square-off time on: everything must be closed. */
    public boolean squareOffDue(LocalTime time) {
        return !time.isBefore(limits.squareOffAt());
    }
}
