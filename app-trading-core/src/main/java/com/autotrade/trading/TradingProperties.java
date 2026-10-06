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
                          String feed, double replaySpeed, List<String> tradeUnderlyings, String replaySource,
                          List<String> shadowStrategyFiles) {

        /** --mode=replay only: "own" replays auto-trade's own live capture (md.*), anything else zt-tiger-v2. */
        public boolean replayFromOwnCapture() {
            return "own".equalsIgnoreCase(replaySource);
        }

        /** --mode=replay only: "bars" replays the stored Upstox one-minute bars (hist.candle); no zt-tiger-v2 needed. */
        public boolean replayFromBars() {
            return "bars".equalsIgnoreCase(replaySource);
        }

        /** The underlyings strategies trade: {@code trade-underlyings}, else every recorded underlying. */
        public List<String> tradeUnderlyingList() {
            return tradeUnderlyings != null && !tradeUnderlyings.isEmpty() ? tradeUnderlyings : underlyings;
        }


        public List<String> strategyFileList() {
            return strategyFiles != null && !strategyFiles.isEmpty() ? strategyFiles : List.of(strategyFile);
        }
    }

    public record Source(String url, String username, String password, String passwordFile, String passwordKey) {
    }
}
