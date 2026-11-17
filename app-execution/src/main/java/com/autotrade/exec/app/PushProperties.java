package com.autotrade.exec.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code autotrade.execution.push.*}: web push to the installed app (mobile phase 4, 17 Nov 2026).
 *
 * @param enabled off (the default): no key is made, nothing is sent, subscribing is refused with a clear reason
 * @param subject who runs this server, for the push services (RFC 8292 "sub"): a mailto: or https: address; default the
 *                site's public address
 */
@ConfigurationProperties(prefix = "autotrade.execution.push")
public record PushProperties(boolean enabled, String subject) {

    static PushProperties off() {
        return new PushProperties(false, null);
    }
}
