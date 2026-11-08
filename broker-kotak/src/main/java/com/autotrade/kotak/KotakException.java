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

    KotakException(Kind kind, String message, int httpStatus, int stCode, boolean mayHaveReached, Throwable cause) {
        super(kind, message, cause);
        this.httpStatus = httpStatus;
        this.stCode = stCode;
        this.mayHaveReached = mayHaveReached;
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
}
