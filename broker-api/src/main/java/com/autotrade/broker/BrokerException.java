package com.autotrade.broker;

/**
 * A broker call that failed, with the class of failure that decides what the caller does next (live plan section 3,
 * "Error taxonomy"): retry it, give the order up, or stop the account's entries until the next login.
 */
public class BrokerException extends RuntimeException {

    public enum Kind {
        /** Timeout, 5xx, rate limit: the same call may be tried again (a timed-out place is looked up by tag first). */
        RETRYABLE,
        /** Funds, price band, freeze quantity, instrument, input: the order is refused; retrying changes nothing. */
        TERMINAL,
        /** Token expired or invalid, IP not whitelisted: no call works until the account logs in again. */
        SESSION,
        /** The outcome is not known (a place call that timed out): look the order up by tag before anything else. */
        UNKNOWN
    }

    private final Kind kind;

    public BrokerException(String message) {
        this(Kind.TERMINAL, message, null);
    }

    public BrokerException(String message, Throwable cause) {
        this(Kind.TERMINAL, message, cause);
    }

    public BrokerException(Kind kind, String message) {
        this(kind, message, null);
    }

    public BrokerException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
