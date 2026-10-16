package com.autotrade.trading;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autotrade")
public record TradingProperties(Trading trading, Source source, Upstox upstox, SignalSource signalSource) {

    /**
     * Live execution L1 (docs/LIVE-TRADING-PLAN.md): {@code autotrade.signal-source.enabled=true} runs the session as the
     * copy group's signal source: account {@code account} (SIGNAL-1) with {@code riskFile} (signal-source.v2, never
     * refuses), its approved entries and every close recorded as exec.signal_decision. Absent or false = off (default).
     * Replay only until the live feed tee exists.
     */
    public record SignalSource(Boolean enabled, String account, String riskFile, String copyGroup, String tenant,
                               String ownerSubject) {

        public boolean on() {
            return Boolean.TRUE.equals(enabled);
        }

        public String accountLabel() {
            return account == null || account.isBlank() ? "SIGNAL-1" : account;
        }

        public String riskFileOr() {
            return riskFile == null || riskFile.isBlank() ? "config/risk/signal-source.v2.yaml" : riskFile;
        }

        public String copyGroupName() {
            return copyGroup == null || copyGroup.isBlank() ? "LIVE-SET" : copyGroup;
        }

        public String tenantName() {
            return tenant == null || tenant.isBlank() ? "owner" : tenant;
        }

        public String ownerSubjectOr() {
            return ownerSubject == null || ownerSubject.isBlank() ? "local-owner" : ownerSubject;
        }
    }

    public record Upstox(String tokenFile, String tokenSource, String instrumentDir, int strikesEachSide, int recenterStrikes) {
    }

    /** {@code strategyFiles} (several strategies side by side) takes precedence over {@code strategyFile}. */
    public record Trading(String account, List<String> underlyings, String fillModel, String strategyFile,
                          List<String> strategyFiles, String featuresFile, String exchangeFile, String costsFile,
                          String riskFile, long pollIntervalMs, long recheckWindowMs, String startAt, String stopAfter,
                          String feed, double replaySpeed, List<String> tradeUnderlyings, String replaySource,
                          List<String> shadowStrategyFiles, Double replayEquity, Boolean replaySnapshots) {

        /**
         * --mode=replay only: the equity the replayed day starts from (risk v8/v9 size from it), so consecutive days can
         * be chained (bin/replay-chain.sh); absent = the starting capital, which keeps single replays comparable.
         */
        public double replayRealisedNet(double startingCapital) {
            return replayEquity == null ? 0 : replayEquity - startingCapital;
        }

        /**
         * --mode=replay only: also store every feature snapshot (feat.snapshot under a REPLAY_FEATURES run), the
         * per-minute dataset for research (A-094); absent = off. Live sessions always store theirs (LIVE_FEATURES).
         */
        public boolean storeReplaySnapshots() {
            return Boolean.TRUE.equals(replaySnapshots);
        }

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
