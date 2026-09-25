package com.autotrade.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autotrade")
public record ToolProperties(Source source, String spoolDir, Upstox upstox) {

    public record Upstox(String apiKey, String apiSecret, String redirectUri, String tokenFile) {
    }

    /**
     * zt-tiger-v2 connection. When {@code passwordFile} exists and holds {@code passwordKey}, that
     * value is used and {@code password} is ignored; zt-tiger-v2 rotates its generated password there.
     */
    public record Source(String name, String url, String username, String password, String passwordFile,
                         String passwordKey) {
    }
}
