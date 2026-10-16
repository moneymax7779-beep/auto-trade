package com.autotrade.account;

import java.util.Locale;

/**
 * Allocated capital as a hard cap (docs/LIVE-TRADING-PLAN.md section 2). An account sizes from its own equity, never
 * from the broker balance or another account's: FIXED = allocation + capital movements; COMPOUNDING adds the account's
 * realised net since its equity-from date. An entry must fit all of: the daily loss limit, the per-trade cap, the
 * account's free capital (equity share for open premium minus what is open) and, in DRY_RUN and LIVE, the broker's
 * available funds minus a buffer. Unknown funds refuse in those modes. Refusals are never turned into smaller orders.
 */
public final class CapitalPolicy {

    private final CapitalLimits limits;

    public CapitalPolicy(CapitalLimits limits) {
        this.limits = limits;
    }

    public CapitalLimits limits() {
        return limits;
    }

    /** The equity an account sizes from today. */
    public static double equity(BrokerAccount account, double capitalMovements, double realisedNetSinceEquityFrom) {
        double base = account.allocatedCapital() + capitalMovements;
        double equity = account.equityMode() == EquityMode.COMPOUNDING ? base + realisedNetSinceEquityFrom : base;
        return Math.max(0, equity);
    }

    /**
     * One entry: {@code premium} the order's full premium, {@code openPremium} that of the account's open positions,
     * {@code todayNet} the day's net so far (realised plus open), {@code brokerAvailableFunds} NaN when unknown.
     */
    public EntryDecision checkEntry(TradeMode mode, double equity, double premium, double openPremium, double todayNet,
                                    double brokerAvailableFunds) {
        if (!(premium > 0)) {
            return EntryDecision.refuse("NO_PREMIUM", "entry premium unknown or zero");
        }
        if (!(equity > 0)) {
            return EntryDecision.refuse("NO_EQUITY", "account equity is zero");
        }
        double lossLimit = equity * limits.dailyLossPct() / 100;
        if (todayNet <= -lossLimit) {
            return EntryDecision.refuse("DAILY_LOSS", "day net " + rupees(todayNet) + " at or beyond the loss limit -"
                    + rupees(lossLimit));
        }
        double perTrade = equity * limits.maxPremiumPerTradePct() / 100;
        if (premium > perTrade) {
            return EntryDecision.refuse("PER_TRADE", "premium " + rupees(premium) + " over the per-trade cap "
                    + rupees(perTrade));
        }
        double free = equity * limits.maxOpenPremiumPct() / 100 - Math.max(0, openPremium);
        if (premium > free) {
            return EntryDecision.refuse("CAPITAL", "premium " + rupees(premium) + " over free capital " + rupees(free));
        }
        if (mode.needsBrokerFunds()) {
            if (!(brokerAvailableFunds >= 0)) {
                return EntryDecision.refuse("FUNDS_UNKNOWN", "broker funds not known");
            }
            double usable = brokerAvailableFunds * (1 - limits.brokerFundsBufferPct() / 100);
            if (premium > usable) {
                return EntryDecision.refuse("BROKER_FUNDS", "premium " + rupees(premium) + " over the broker's usable funds "
                        + rupees(usable));
            }
        }
        return EntryDecision.APPROVED;
    }

    private static String rupees(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
