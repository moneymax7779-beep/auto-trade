package com.autotrade.account;

/** The capital check of one entry: approved, or refused with a code and a reason (an entry is never shrunk to fit). */
public record EntryDecision(boolean approved, String code, String reason) {

    public static final EntryDecision APPROVED = new EntryDecision(true, "OK", "");

    static EntryDecision refuse(String code, String reason) {
        return new EntryDecision(false, code, reason);
    }
}
