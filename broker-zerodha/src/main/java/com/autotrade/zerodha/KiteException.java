package com.autotrade.zerodha;

import com.autotrade.broker.BrokerException;

/**
 * A Kite call that failed, with what decides a retry: the HTTP status (0 = no response) and whether the request can
 * have reached the broker (false for a refused connection or a 429, which Kite drops before processing).
 */
public final class KiteException extends BrokerException {

    private final int httpStatus;
    private final String errorType;
    private final boolean mayHaveReached;

    KiteException(Kind kind, String message, int httpStatus, String errorType, boolean mayHaveReached, Throwable cause) {
        super(kind, message, cause);
        this.httpStatus = httpStatus;
        this.errorType = errorType;
        this.mayHaveReached = mayHaveReached;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorType() {
        return errorType;
    }

    public boolean mayHaveReached() {
        return mayHaveReached;
    }
}
