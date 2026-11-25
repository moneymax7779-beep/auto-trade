package com.autotrade.exec.app;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code autotrade.brokers.upstox.*}: the Upstox app keys and Upstox users this service must never use (review 8 Nov
 * 2026): zt-tiger-v2's own Upstox app and its Upstox user. A login of zt's app ends zt's token, and registering a static
 * IP for zt's user (every app of a user shares its IPs) logs zt out, so zt's app is never a member's broker app and its
 * user is no member's account, except the accounts listed in {@code ztUserAccounts} (below).
 * Enforced where an Upstox key is saved ({@link BrokerAppController}), at the Upstox login ({@link UpstoxLogin}) and
 * where the worker reads an Upstox session ({@link UpstoxSessionSource}: a denied key or user is no session).
 * Compared trimmed and case-insensitively.
 *
 * <p>User 25 Nov 2026 ("I still want to use BM6462 from a new application account and new workspace"): zt-tiger-v2's
 * user may be used by the broker accounts listed in {@code ztUserAccounts} only (their own Upstox app, never zt's key,
 * which stays denied); every other account and every other denied user stay refused. Empty by default.
 *
 * @param deniedApiKeys   Upstox app keys (client ids) never used here
 * @param deniedUserIds   Upstox user ids (UCC) never logged in or read here
 * @param ztUserAccounts  broker account ids that may use zt-tiger-v2's Upstox user (BM6462); blanks ignored
 */
@ConfigurationProperties(prefix = "autotrade.brokers.upstox")
public record UpstoxDenyList(List<String> deniedApiKeys, List<String> deniedUserIds, List<String> ztUserAccounts) {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UpstoxDenyList.class);

    /** Nothing denied (tests). */
    static final UpstoxDenyList NONE = new UpstoxDenyList(List.of(), List.of(), List.of());

    /**
     * zt-tiger-v2's own Upstox user: denied whatever the configuration says (validation 16 Nov 2026: a relaxed-binding
     * variable such as AUTOTRADE_BROKERS_UPSTOX_DENIED_USER_IDS replaces the configured list, so it cannot be left out).
     */
    static final String ZT_UPSTOX_USER = "BM6462";

    public UpstoxDenyList {
        deniedApiKeys = clean(deniedApiKeys);
        deniedUserIds = clean(java.util.stream.Stream.concat(Stream.of(ZT_UPSTOX_USER),
                deniedUserIds == null ? Stream.<String>empty() : deniedUserIds.stream()).toList());
        // review 25 Nov 2026: an entry that is not an account id is left out and logged, never fatal (a typo must not stop
        // the worker for every account; left out = that account stays denied, the safe side)
        ztUserAccounts = ztUserAccounts == null ? List.of() : ztUserAccounts.stream().filter(Objects::nonNull)
                .map(String::trim).filter(v -> !v.isEmpty()).map(v -> {
                    try {
                        long id = Long.parseLong(v);
                        if (id > 0) {
                            return Long.toString(id);
                        }
                    } catch (NumberFormatException e) {
                        // logged below
                    }
                    LOG.error("autotrade.brokers.upstox.zt-user-accounts: '{}' is not a broker account id: left out", v);
                    return null;
                }).filter(Objects::nonNull).distinct().toList();
    }

    /** The account ids allowed zt-tiger-v2's Upstox user ({@link #ztUserAccounts} as numbers). */
    java.util.Set<Long> ztUserAccountIds() {
        return ztUserAccounts.stream().map(Long::parseLong).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Whether {@code userId} may not be used by broker account {@code accountId}: a denied user, except zt-tiger-v2's
     * for an account listed in {@link #ztUserAccounts} (user 25 Nov 2026).
     */
    boolean deniedUser(String userId, long accountId) {
        if (!deniedUser(userId)) {
            return false;
        }
        return !(norm(userId).equals(ZT_UPSTOX_USER) && ztUserAccounts.contains(Long.toString(accountId)));
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
