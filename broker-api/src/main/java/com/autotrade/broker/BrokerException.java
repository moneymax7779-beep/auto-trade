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
    /** True when the broker refuses this app market data (a Kite app without the Connect plan): see {@link #noMarketData}. */
    private boolean noMarketData;

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

    /**
     * 23 Nov 2026: the broker answers but refuses this app market data (Kite's PermissionException on /quote from an app
     * without the paid Connect plan). Orders can neither be priced nor exited at a price, so it is handled as a lost
     * session ({@link Kind#SESSION}: the day goes back to PAPER, entries refused, exits wait), but it is not one: the
     * login is valid and logging in again changes nothing. Callers say so and leave the session as it is.
     */
    public static BrokerException noMarketData(String message, Throwable cause) {
        BrokerException e = new BrokerException(Kind.SESSION, message, cause);
        e.noMarketData = true;
        return e;
    }

    /** True for {@link #noMarketData}: the app may not read prices; the session itself is valid. */
    public boolean isNoMarketData() {
        return noMarketData;
    }
}
