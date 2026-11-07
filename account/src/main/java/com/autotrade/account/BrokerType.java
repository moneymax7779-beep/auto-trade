package com.autotrade.account;

/** The broker behind an account; PAPER is auto-trade's own simulated broker (never live). GROWW: 7 Nov 2026. */
public enum BrokerType {
    PAPER, ZERODHA, UPSTOX, KOTAK_NEO, GROWW
}
