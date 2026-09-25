package com.autotrade.risk;

public record RiskDecision(boolean approved, String reason) {

    public static final RiskDecision APPROVED = new RiskDecision(true, "ok");

    public static RiskDecision rejected(String reason) {
        return new RiskDecision(false, reason);
    }
}
