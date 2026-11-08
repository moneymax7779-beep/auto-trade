package com.autotrade.upstox.orders;

import com.autotrade.broker.BrokerException;

/**
 * An Upstox call that failed, with what decides a retry: the HTTP status (0 = no response), Upstox's error code
 * ({@code UDAPI...}; empty = none) and whether the request can have reached the broker (false for a refused connection or
 * a 429).
 */
public final class UpstoxException extends BrokerException {

    private final int httpStatus;
    private final String errorCode;
    private final boolean mayHaveReached;

    UpstoxException(Kind kind, String message, int httpStatus, String errorCode, boolean mayHaveReached, Throwable cause) {
        super(kind, message, cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode == null ? "" : errorCode;
        this.mayHaveReached = mayHaveReached;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean mayHaveReached() {
        return mayHaveReached;
    }
}
