package com.autotrade.execution;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One broker-neutral decision of a signal source (exec.signal_decision): what the strategy did on the virtual account
 * that is never refused. ENTER and ADD when its risk approved them, EXIT when its position closed for any reason.
 * {@code premiumBudget} is the strategy's budget at {@code referenceCapital}; followers scale it to their own equity.
 */
public record SignalDecision(
        long copyGroupId,
        long signalSessionId,
        LocalDate sessionDate,
        Instant decidedAt,
        String strategyId,
        int priority,
        String underlying,
        Action action,
        String optionSide,
        String legGroup,
        String stage,
        String reason,
        long instrumentToken,
        String instrumentKey,
        String symbol,
        int lotSize,
        double referencePrice,
        double premiumBudget,
        double referenceCapital,
        int lots,
        String idempotencyKey) {

    public enum Action { ENTER, ADD, EXIT }
}
