package com.autotrade.trading;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autotrade")
public record TradingProperties(Trading trading, Source source, Upstox upstox) {

    public record Upstox(String tokenFile, String tokenSource, String instrumentDir, int strikesEachSide, int recenterStrikes) {
    }

    /** {@code strategyFiles} (several strategies side by side) takes precedence over {@code strategyFile}. */
    public record Trading(String account, List<String> underlyings, String fillModel, String strategyFile,
                          List<String> strategyFiles, String featuresFile, String exchangeFile, String costsFile,
                          String riskFile, long pollIntervalMs, long recheckWindowMs, String startAt, String stopAfter,
                          String feed, double replaySpeed) {

        public List<String> strategyFileList() {
            return strategyFiles != null && !strategyFiles.isEmpty() ? strategyFiles : List.of(strategyFile);
        }
    }

    public record Source(String url, String username, String password, String passwordFile, String passwordKey) {
    }
}
