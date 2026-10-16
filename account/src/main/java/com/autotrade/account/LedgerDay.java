package com.autotrade.account;

import java.time.LocalDate;

/**
 * One account's day (acct.ledger_day): our own figures, the broker's when reconciled, and the difference. A difference
 * of a rupee or more must be acknowledged by a person before the account's next live start.
 */
public record LedgerDay(
        long brokerAccountId,
        LocalDate sessionDate,
        double openingEquity,
        double realisedGross,
        double charges,
        double net,
        double closingEquity,
        int trades,
        Double brokerNet,
        Double brokerCharges,
        Double difference) {

    /** Closes a day from our own fills; the broker's figures are added by {@link #reconciled}. */
    public static LedgerDay close(long accountId, LocalDate day, double openingEquity, double realisedGross,
                                  double charges, int trades) {
        double net = realisedGross - charges;
        return new LedgerDay(accountId, day, openingEquity, realisedGross, charges, net, openingEquity + net, trades,
                null, null, null);
    }

    /** The same day with the broker's net and charges (from its trade book and contract note). */
    public LedgerDay reconciled(double brokerNetValue, double brokerChargesValue) {
        return new LedgerDay(brokerAccountId, sessionDate, openingEquity, realisedGross, charges, net, closingEquity,
                trades, brokerNetValue, brokerChargesValue, Math.round((net - brokerNetValue) * 100) / 100.0);
    }

    /** True when the broker disagrees with us by a rupee or more (or has not been reconciled for a traded day). */
    public boolean needsAcknowledgement() {
        if (difference == null) {
            return trades > 0;
        }
        return Math.abs(difference) >= 1;
    }
}
