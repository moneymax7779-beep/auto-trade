package com.autotrade.exec.app;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code autotrade.brokers.upstox.*}: the Upstox app keys and Upstox users this service must never use (review 8 Nov
 * 2026): zt-tiger-v2's own Upstox app and its Upstox user. A login of zt's app ends zt's token, and registering a static
 * IP for zt's user (every app of a user shares its IPs) logs zt out, so neither may be a member's broker app or account.
 * Enforced where an Upstox key is saved ({@link BrokerAppController}), at the Upstox login ({@link UpstoxLogin}) and
 * where the worker reads an Upstox session ({@link UpstoxSessionSource}: a denied key or user is no session).
 * Compared trimmed and case-insensitively.
 *
 * @param deniedApiKeys  Upstox app keys (client ids) never used here
 * @param deniedUserIds  Upstox user ids (UCC) never logged in or read here
 */
@ConfigurationProperties(prefix = "autotrade.brokers.upstox")
public record UpstoxDenyList(List<String> deniedApiKeys, List<String> deniedUserIds) {

    /** Nothing denied (tests). */
    static final UpstoxDenyList NONE = new UpstoxDenyList(List.of(), List.of());

    public UpstoxDenyList {
        deniedApiKeys = clean(deniedApiKeys);
        deniedUserIds = clean(deniedUserIds);
    }

    boolean deniedKey(String apiKey) {
        return apiKey != null && deniedApiKeys.contains(norm(apiKey));
    }

    boolean deniedUser(String userId) {
        return userId != null && deniedUserIds.contains(norm(userId));
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(UpstoxDenyList::norm)
                .filter(v -> !v.isEmpty()).distinct().toList();
    }

    private static String norm(String v) {
        return v.trim().toUpperCase(Locale.ROOT);
    }
}
