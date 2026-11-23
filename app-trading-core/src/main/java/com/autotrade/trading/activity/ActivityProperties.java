package com.autotrade.trading.activity;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code autotrade.activity.*}: user activity recording (plan docs/plans/2026-11-23-user-activity-logging-plan.md).
 *
 * @param enabled        on or off; unset: on exactly when sign-in is on ({@code autotrade.auth.mode=oidc})
 * @param trustedProxies our own proxies' addresses or CIDRs, for the client address ({@link ClientAddress})
 * @param idle           a sign-in with no request for this long has ended (IDLE); a later request reopens it
 * @param rawDays        tabs and per-minute rows are kept this many days (the daily totals are kept)
 * @param loginDays      sign-ins are kept this many days
 * @param ipDays         after this many days a sign-in's address is cut to its /24 (IPv4) or /48 (IPv6)
 */
@ConfigurationProperties(prefix = "autotrade.activity")
public record ActivityProperties(Boolean enabled, List<String> trustedProxies, Duration idle, Integer rawDays,
                                 Integer loginDays, Integer ipDays) {
    public ActivityProperties {
        List<String> given = trustedProxies == null ? List.of()
                : trustedProxies.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).toList();
        trustedProxies = given.isEmpty() ? ClientAddress.DEFAULT_TRUSTED : given;
        idle = idle == null || idle.isNegative() || idle.isZero() ? Duration.ofMinutes(15) : idle;
        rawDays = rawDays == null ? 14 : Math.max(3, rawDays);   // the daily totals are re-worked from the last days
        loginDays = loginDays == null || loginDays < 1 ? 180 : loginDays;
        ipDays = ipDays == null || ipDays < 1 ? 30 : ipDays;
    }

    /** Recording on: as configured, else with sign-in. */
    public boolean on(boolean signInOn) {
        return enabled != null ? enabled : signInOn;
    }
}
