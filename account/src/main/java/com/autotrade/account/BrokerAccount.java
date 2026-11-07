package com.autotrade.account;

import java.time.LocalDate;

/**
 * One row of acct.broker_account: the execution boundary. Every order, fill and position belongs to exactly one broker
 * account; {@code legacyLabel} ties it to an existing trade.session.account label (PAPER-1) without changing that code.
 * Allocated capital is the hard cap the account sizes from, whatever the broker account holds. {@code paperOf}: the real
 * broker account this one is the paper twin of (trader workspaces, V29), else null.
 */
public record BrokerAccount(
        long id,
        long tenantId,
        long appUserId,
        BrokerType brokerType,
        String displayName,
        String legacyLabel,
        AccountRole role,
        TradeMode tradeMode,
        boolean liveTradingEnabled,
        double allocatedCapital,
        EquityMode equityMode,
        LocalDate equityFrom,
        String riskFile,
        double sizeStepPct,
        boolean enabled,
        Long paperOf) {

    /** An account that is nobody's paper twin. */
    public BrokerAccount(long id, long tenantId, long appUserId, BrokerType brokerType, String displayName, String legacyLabel,
                         AccountRole role, TradeMode tradeMode, boolean liveTradingEnabled, double allocatedCapital,
                         EquityMode equityMode, LocalDate equityFrom, String riskFile, double sizeStepPct, boolean enabled) {
        this(id, tenantId, appUserId, brokerType, displayName, legacyLabel, role, tradeMode, liveTradingEnabled,
                allocatedCapital, equityMode, equityFrom, riskFile, sizeStepPct, enabled, null);
    }

    public BrokerAccount {
        if (!(allocatedCapital > 0)) {
            throw new IllegalArgumentException("allocated capital must be positive");
        }
        if (!(sizeStepPct >= 0 && sizeStepPct <= 100)) {
            throw new IllegalArgumentException("size step must be 0-100 %");
        }
        if (liveTradingEnabled && (tradeMode != TradeMode.LIVE || brokerType == BrokerType.PAPER)) {
            throw new IllegalArgumentException("live trading needs LIVE mode on a real broker");
        }
    }
}
