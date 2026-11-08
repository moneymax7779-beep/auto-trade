package com.autotrade.kotak;

import com.autotrade.broker.BrokerException;

/**
 * A Kotak Neo call that failed, with what decides a retry: the HTTP status (0 = no response), Kotak's {@code stCode}
 * (0 = none) and whether the request can have reached the broker (false for a refused connection or a 429).
 */
public final class KotakException extends BrokerException {

    private final int httpStatus;
    private final int stCode;
    private final boolean mayHaveReached;
    private final String notOkMessage;

    KotakException(Kind kind, String message, int httpStatus, int stCode, boolean mayHaveReached, Throwable cause) {
        this(kind, message, httpStatus, stCode, mayHaveReached, cause, null);
    }

    KotakException(Kind kind, String message, int httpStatus, int stCode, boolean mayHaveReached, Throwable cause,
                   String notOkMessage) {
        super(kind, message, cause);
        this.httpStatus = httpStatus;
        this.stCode = stCode;
        this.mayHaveReached = mayHaveReached;
        this.notOkMessage = notOkMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public int stCode() {
        return stCode;
    }

    public boolean mayHaveReached() {
        return mayHaveReached;
    }

    /**
     * Kotak's own message when it answered {@code stat: Not_Ok} ({@code emsg}/{@code errMsg}, as sent); null for any other
     * failure (no answer, not Kotak's envelope, an HTTP error without one).
     */
    public String notOkMessage() {
        return notOkMessage;
    }
}
