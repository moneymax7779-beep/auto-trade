package com.autotrade.upstox;

import java.net.URI;

/** The Upstox developer app's key, secret and registered redirect URL (from .env; never logged). */
public record UpstoxCredentials(String apiKey, String apiSecret, String redirectUri) {

    public UpstoxCredentials {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalStateException("UPSTOX_API_KEY and UPSTOX_API_SECRET must be set in .env");
        }
        URI uri = URI.create(redirectUri);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())) {
            throw new IllegalStateException("UPSTOX_REDIRECT_URI must be http://127.0.0.1:<port>/... (local callback)");
        }
    }

    @Override
    public String toString() {
        return "UpstoxCredentials[apiKey=" + apiKey.substring(0, Math.min(4, apiKey.length())) + "…, redirect="
                + redirectUri + "]";
    }
}
