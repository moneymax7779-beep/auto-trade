package com.autotrade.zerodha;

import tools.jackson.databind.JsonNode;

/**
 * Read-only checks on the day's Kite session, right after the login exchange: the equity margin the account has. Read
 * calls are allowed from any IP (only order calls must come from the registered static IP). Nothing here places,
 * modifies or cancels an order, and the access token is never logged.
 */
public final class KiteSessionCheck {

    private final KiteHttp http;

    public KiteSessionCheck(KiteConfig config, String accessToken) {
        this.http = new KiteHttp(config);
        this.http.accessToken(accessToken);
    }

    /** The equity segment's available live balance (Kite's {@code /user/margins/equity}), else its net. */
    public double equityAvailable() {
        JsonNode equity = http.get("/user/margins/equity", null);
        JsonNode live = equity.path("available").path("live_balance");
        return live.isMissingNode() || live.isNull() ? equity.path("net").asDouble(Double.NaN) : live.asDouble();
    }
}
