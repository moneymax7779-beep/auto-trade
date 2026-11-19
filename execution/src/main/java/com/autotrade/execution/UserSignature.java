package com.autotrade.execution;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signed user between the UI's proxy (app-trading-core) and the execution workers (app-execution) (account-management
 * review, 19 Nov 2026: the workers trusted a bare {@code X-Autotrade-User}, so anything on the host's Docker network could
 * act as any user, the platform admin included). With a shared secret ({@code AUTOTRADE_PROXY_SECRET}, the same in
 * both, never in git) the proxy adds {@link #TS} (epoch seconds) and {@link #SIG}: HMAC-SHA256 over the user, the time,
 * the method, the path with its query and the SHA-256 of the body; a worker accepts a request naming a user only with a
 * signature that matches and a time within {@link #MAX_SKEW_SECONDS}, so a captured request can be neither altered nor
 * replayed later. Requests naming no user (status, health, the login board) are unchanged.
 */
public final class UserSignature {

    public static final String USER = "X-Autotrade-User";
    public static final String TS = "X-Autotrade-Ts";
    public static final String SIG = "X-Autotrade-Sig";
    public static final long MAX_SKEW_SECONDS = 60;

    private UserSignature() {
    }

    /** The signature of one request; {@code pathAndQuery} is the raw path plus "?" and the raw query when there is one. */
    public static String sign(String secret, String user, long epochSeconds, String method, String pathAndQuery, byte[] body) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0]
                    : body));
            String message = user + "\n" + epochSeconds + "\n" + method.toUpperCase(java.util.Locale.ROOT) + "\n"
                    + pathAndQuery + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** Null when the request's signature is good; else why it is refused. */
    public static String refusal(String secret, String user, String ts, String sig, String method, String pathAndQuery,
                                 byte[] body, long nowEpochSeconds) {
        if (ts == null || sig == null) {
            return "the user is not signed";
        }
        long at;
        try {
            at = Long.parseLong(ts.trim());
        } catch (NumberFormatException e) {
            return "the signature's time is not a number";
        }
        if (Math.abs(nowEpochSeconds - at) > MAX_SKEW_SECONDS) {
            return "the signature is too old or from the future";
        }
        byte[] expected = sign(secret, user, at, method, pathAndQuery, body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, sig.trim().getBytes(StandardCharsets.US_ASCII)) ? null
                : "the signature does not match";
    }
}
