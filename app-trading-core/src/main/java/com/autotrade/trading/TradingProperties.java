package com.autotrade.trading;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autotrade")
public record TradingProperties(Trading trading, Source source, Upstox upstox, SignalSource signalSource) {

    /**
     * Live execution L1 (docs/LIVE-TRADING-PLAN.md): {@code autotrade.signal-source.enabled=true} runs the session as the
     * copy group's signal source: account {@code account} (SIGNAL-1) with {@code riskFile} (signal-source.v2, never
     * refuses), its approved entries and every close recorded as exec.signal_decision. Absent or false = off (default).
     */
    public record SignalSource(Boolean enabled, String account, String riskFile, String copyGroup, String tenant,
                               String ownerSubject, Boolean tee) {

        public boolean on() {
            return Boolean.TRUE.equals(enabled);
        }

        /**
         * Live: the signal source always runs alongside the session on a {@link TeeFeed} of its feed. Replay:
         * {@code tee=true} does the same (the live wiring, testable offline); absent, the replayed session itself is the
         * signal source.
         */
        public boolean teeOn() {
            return Boolean.TRUE.equals(tee);
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

    /** {@code farStrikesEachSide}: the next weekly and current monthly expiry's band, recorded only (null / 0 = off). */
    public record Upstox(String tokenFile, String tokenSource, String instrumentDir, int strikesEachSide, int recenterStrikes,
                         Integer farStrikesEachSide) {

        public int farStrikes() {
            return farStrikesEachSide == null ? 0 : farStrikesEachSide;
        }
    }

    /** {@code strategyFiles} (several strategies side by side) takes precedence over {@code strategyFile}. */
    public record Trading(String account, List<String> underlyings, String fillModel, String strategyFile,
                          List<String> strategyFiles, String featuresFile, String exchangeFile, String costsFile,
                          String riskFile, long pollIntervalMs, long recheckWindowMs, String startAt, String stopAfter,
                          String feed, double replaySpeed, List<String> tradeUnderlyings, String replaySource,
                          List<String> shadowStrategyFiles, Double replayEquity, Boolean replaySnapshots,
                          Boolean allowMissingFeatures) {

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

        /**
         * --mode=replay only: run a strategy file whose required features file setting is off (logged), to reproduce a run
         * from before the check existed (A-287 review); live and serve always refuse it. Absent = refuse.
         */
        public boolean missingFeaturesAllowed() {
            return Boolean.TRUE.equals(allowMissingFeatures);
        }

        /** --mode=replay only: "own" replays auto-trade's own live capture (md.*), anything else zt-tiger-v2. */
        public boolean replayFromOwnCapture() {
            return "own".equalsIgnoreCase(replaySource);
        }

        /** --mode=replay only: "bars" replays the stored Upstox one-minute bars (hist.candle); no zt-tiger-v2 needed. */
        public boolean replayFromBars() {
            return "bars".equalsIgnoreCase(replaySource) || barOptionsWithoutExtremes();
        }

        /**
         * --mode=replay only: "bars-close" is "bars" with every option quoted only at its bar's open and close, not its
         * high and low: the bars do not say when within the minute a call and a put made their extremes, and "bars"
         * plays both at the same offsets (A-285: a straddle's combined target hit on aligned extremes). A lower bound.
         */
        public boolean barOptionsWithoutExtremes() {
            return "bars-close".equalsIgnoreCase(replaySource);
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
