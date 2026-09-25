package com.autotrade.trading;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autotrade")
public record TradingProperties(Trading trading, Source source, Upstox upstox) {

    public record Upstox(String tokenFile, String instrumentDir, int strikesEachSide, int recenterStrikes) {
    }

    public record Trading(String account, List<String> underlyings, String fillModel, String strategyFile,
                          String featuresFile, String exchangeFile, String costsFile, String riskFile,
                          long pollIntervalMs, long recheckWindowMs, String stopAfter, String feed) {
    }

    public record Source(String url, String username, String password, String passwordFile, String passwordKey) {
    }
}
