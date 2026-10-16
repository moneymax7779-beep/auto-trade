package com.autotrade.account;

/**
 * What an account does in a copy group (zt-tiger-v2's roles): a signal source decides, a follower trades the decisions
 * it subscribes to, a view-only follower sees them, a disabled account does nothing.
 */
public enum AccountRole {
    SIGNAL_SOURCE, FOLLOWER_TRADE, FOLLOWER_VIEW_ONLY, DISABLED
}
