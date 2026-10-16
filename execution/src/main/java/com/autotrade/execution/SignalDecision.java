package com.autotrade.execution;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * One broker-neutral decision of a signal source (exec.signal_decision): what the strategy did on the virtual account
 * that is never refused. ENTER and ADD when its risk approved them, EXIT when its position closed for any reason.
 * {@code premiumBudget} is the strategy's budget at {@code referenceCapital}; followers scale it to their own equity.
 * {@code context} carries the sizing inputs (V22): strategy_lots, intended_lots, liquidity_lots, rvol_tod, stop_pct;
 * for a straddle leg call_ask and put_ask.
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
        String idempotencyKey,
        Map<String, Double> context) {

    public enum Action { ENTER, ADD, EXIT }

    public SignalDecision {
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    /** The context as a flat JSON object of numbers (NaN and infinities as null), for the jsonb column. */
    public String contextJson() {
        StringBuilder json = new StringBuilder("{");
        new TreeMap<>(context).forEach((key, value) -> {
            if (json.length() > 1) {
                json.append(", ");
            }
            json.append('"').append(key.replace("\"", "")).append("\": ")
                    .append(value == null || !Double.isFinite(value) ? "null" : Double.toString(value));
        });
        return json.append('}').toString();
    }
}
