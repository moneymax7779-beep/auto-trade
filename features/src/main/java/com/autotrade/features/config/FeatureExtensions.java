package com.autotrade.features.config;

import java.time.LocalTime;
import java.util.List;

import com.autotrade.config.ThresholdConfig;

/**
 * Feature definitions added in features v3: level acceptance and ladder, order-book imbalance,
 * option premium behaviour, volatility percentiles and VIX, and the closing-auction features. Null
 * for v1/v2 files, whose snapshots then leave these sections empty.
 */
public record FeatureExtensions(
        int ladderWindowMin,
        int breakoutVolumeAverageBars,
        List<Integer> bookPersistenceWindowsSec,
        List<Integer> premiumMomentumWindowsMin,
        int premiumNewHighWindowMin,
        int volumeExpansionBaselineMin,
        int straddleCompressionLookbackMin,
        int straddleStopWindowMin,
        int realizedWindowMin,
        double annualisationMinutes,
        int volatilityHistorySessions,
        int volatilityMinHistorySessions,
        double ivTrendThreshold,
        List<Integer> vixPercentileDays,
        List<Integer> vixChangeWindowsMin,
        LocalTime casReferenceFrom,
        LocalTime casReferenceTo,
        List<Integer> casIndicativeWindowsSec,
        int casImbalanceVelocitySec,
        int casConcentrationTopN,
        boolean premiumResponseFull,
        double moverNeutralPct,
        int liquidityWindowSec,
        int auctionHistorySessions) {

    public static FeatureExtensions from(ThresholdConfig c) {
        if (!c.has("levels")) {
            return null;
        }
        String[] reference = c.getString("cas.reference_window").split("-");
        return new FeatureExtensions(
                c.getInt("levels.ladder_window_min"),
                c.getInt("levels.breakout_volume_average_bars"),
                ints(c, "order_book.persistence_windows_sec"),
                ints(c, "premium.momentum_windows_min"),
                c.getInt("premium.new_high_window_min"),
                c.getInt("premium.volume_expansion_baseline_min"),
                c.getInt("straddle.compression_lookback_min"),
                c.getInt("straddle.stop_window_min"),
                c.getInt("volatility.realized_window_min"),
                c.getDouble("volatility.annualisation_minutes"),
                c.getInt("volatility.history_sessions"),
                c.getInt("volatility.min_history_sessions"),
                c.getDouble("volatility.iv_trend_threshold"),
                ints(c, "volatility.vix_percentile_days"),
                ints(c, "volatility.vix_change_windows_min"),
                LocalTime.parse(reference[0]), LocalTime.parse(reference[1]),
                ints(c, "cas.indicative_windows_sec"),
                c.getInt("cas.imbalance_velocity_window_sec"),
                c.getInt("cas.concentration_top_n"),
                // v5 additions; absent in v3/v4 files, which then keep their v3/v4 definitions
                c.has("options_chain.premium_response_terms")
                        && "FULL".equals(c.getString("options_chain.premium_response_terms")),
                c.has("breadth.mover_neutral_pct") ? c.getDouble("breadth.mover_neutral_pct") : Double.NaN,
                c.has("order_book.liquidity_window_sec") ? c.getInt("order_book.liquidity_window_sec") : 60,
                c.has("cas.auction_history_sessions") ? c.getInt("cas.auction_history_sessions") : 20);
    }

    private static List<Integer> ints(ThresholdConfig config, String path) {
        return config.getDoubleList(path).stream().map(Double::intValue).toList();
    }
}
