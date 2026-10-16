package com.autotrade.account;

/**
 * PAPER simulates fills; DRY_RUN goes through the whole live path up to the broker call and simulates there; LIVE
 * sends orders. DRY_RUN and LIVE both need the broker's funds (a read call) before an entry.
 */
public enum TradeMode {
    PAPER, DRY_RUN, LIVE;

    /** True when the broker's available funds must be known before an entry. */
    public boolean needsBrokerFunds() {
        return this != PAPER;
    }
}
