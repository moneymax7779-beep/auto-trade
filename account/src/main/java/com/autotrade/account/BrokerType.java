package com.autotrade.account;

/** The broker behind an account; PAPER is auto-trade's own simulated broker (never live). */
public enum BrokerType {
    PAPER, ZERODHA, UPSTOX, KOTAK_NEO
}
