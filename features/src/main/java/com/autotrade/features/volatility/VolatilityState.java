package com.autotrade.features.volatility;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.autotrade.core.history.DailyBar;
import com.autotrade.core.history.IvSession;
import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.history.ReferenceData;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.bars.Bar;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.config.FeatureExtensions;
import com.autotrade.features.indicators.TimedSeries;
import com.autotrade.features.indicators.WilderAtr;
import com.autotrade.features.snapshot.VolatilityFeatures;

/**
 * Volatility percentiles against earlier sessions and India VIX (features v3). History is loaded once
 * per session from {@link SessionHistory}; nothing from the session itself is used except VIX
 * one-minute bars, and only those that have completed by the snapshot time.
 */
public final class VolatilityState {

    private static final Duration VIX_RETENTION = Duration.ofMinutes(30);

    private final FeatureExtensions ext;
    private final LocalDate session;
    private final Map<LocalTime, List<Double>> atrByMinute = new HashMap<>();
    private final Map<LocalTime, List<Double>> rvByMinute = new HashMap<>();
    private final int spotSessions;
    private final List<IvSession> ivHistory;
    private final List<Double> vixCloses;
    private final List<MinuteBar> vixBars;
    private final TimedSeries vix = new TimedSeries(VIX_RETENTION);
    private int vixBarsApplied;
    private boolean vixTicks;

    public VolatilityState(FeatureConfig config, String underlying, LocalDate session, SessionHistory history) {
        this.ext = config.extensions();
        this.session = session;
        List<List<MinuteBar>> spot = history.spotMinuteBars(underlying, session, ext.volatilityHistorySessions());
        for (List<MinuteBar> bars : spot) {
            WilderAtr atr = new WilderAtr(config.atrPeriod());
            List<Double> closes = new ArrayList<>();
            for (MinuteBar bar : bars) {
                atr.update(new Bar(bar.start(), bar.end(), bar.open(), bar.high(), bar.low(), bar.close(), 0, 1));
                closes.add(bar.close());
                LocalTime minute = bar.end().atZone(MarketTime.IST).toLocalTime();
                if (atr.ready()) {
                    atrByMinute.computeIfAbsent(minute, m -> new ArrayList<>()).add(atr.value());
                }
                double rv = realized(closes, ext.realizedWindowMin(), ext.annualisationMinutes());
                if (!Double.isNaN(rv)) {
                    rvByMinute.computeIfAbsent(minute, m -> new ArrayList<>()).add(rv);
                }
            }
        }
        this.spotSessions = spot.size();
        this.ivHistory = history.atmIvMinutes(underlying, session, ext.volatilityHistorySessions());
        int vixDays = ext.vixPercentileDays().stream().mapToInt(Integer::intValue).max().orElse(252);
        this.vixCloses = history.reference().dailyBars(ReferenceData.INDIA_VIX, session, vixDays).stream()
                .map(DailyBar::close).toList();
        this.vixBars = history.reference().intradayBars(ReferenceData.INDIA_VIX, session);
    }

    /** A live India VIX tick (ticks take precedence over one-minute bars once seen). */
    public void onVixTick(Instant time, double value) {
        vixTicks = true;
        vix.add(time, value);
    }

    /**
     * @param spotBars  completed 1-minute spot bars of today, oldest first
     * @param atr1m     today's ATR on 1-minute bars
     * @param atmIv     current ATM IV (fraction)
     * @param ivChange5m ATM IV change over 5 minutes
     * @param expiryDay whether today is the nearest contract's expiry day
     */
    public VolatilityFeatures snapshot(Instant time, List<Bar> spotBars, double atr1m, double atmIv,
                                       double ivChange5m, boolean expiryDay) {
        LocalTime minute = time.atZone(MarketTime.IST).toLocalTime();
        List<Double> closes = new ArrayList<>(spotBars.size());
        for (Bar bar : spotBars) {
            closes.add(bar.close());
        }
        double rv = realized(closes, ext.realizedWindowMin(), ext.annualisationMinutes());

        List<Double> ivs = new ArrayList<>();
        LocalTime lastMinute = minute.minusMinutes(1).withSecond(0).withNano(0);
        for (IvSession earlier : ivHistory) {
            Double value = earlier.atmIvByMinute().get(lastMinute);
            if (earlier.expiryDay() == expiryDay && value != null) {
                ivs.add(value);
            }
        }
        String trend = Double.isNaN(ivChange5m) ? "UNKNOWN" : ivChange5m > ext.ivTrendThreshold() ? "RISING"
                : ivChange5m < -ext.ivTrendThreshold() ? "FALLING" : "FLAT";

        applyVixBars(time);
        double vixNow = vix.valueAt(time);
        Instant vixTime = vix.latestTime();
        List<Integer> changeWindows = ext.vixChangeWindowsMin();
        List<Integer> percentileDays = ext.vixPercentileDays();
        return new VolatilityFeatures(
                percentileIfEnough(atmIv, ivs), ivs.size(), trend,
                rv, percentileIfEnough(rv, rvByMinute.getOrDefault(minute, List.of())),
                atmIv > 0 && !Double.isNaN(rv) ? rv / atmIv : Double.NaN,
                percentileIfEnough(atr1m, atrByMinute.getOrDefault(minute, List.of())), spotSessions,
                vixNow,
                vix.change(time, Duration.ofMinutes(changeWindows.get(0))),
                vix.change(time, Duration.ofMinutes(changeWindows.get(1))),
                vixCloses.isEmpty() || Double.isNaN(vixNow) ? Double.NaN : vixNow - vixCloses.getFirst(),
                vixPercentile(vixNow, percentileDays.get(0)), vixPercentile(vixNow, percentileDays.get(1)),
                vixCloses.size(),
                vixTime == null || Double.isNaN(vixNow) ? Double.NaN : Duration.between(vixTime, time).toMillis() / 1000.0,
                Double.isNaN(vixNow) ? "NONE" : vixTicks ? "TICK" : "MINUTE");
    }

    /** Adds one-minute VIX bars that have completed by {@code time} (the live list may have grown). */
    private void applyVixBars(Instant time) {
        if (vixTicks) {
            return;
        }
        while (vixBarsApplied < vixBars.size()) {
            MinuteBar bar = vixBars.get(vixBarsApplied);
            if (bar.end().isAfter(time)) {
                return;
            }
            vix.add(bar.end(), bar.close());
            vixBarsApplied++;
        }
    }

    private double vixPercentile(double value, int days) {
        if (Double.isNaN(value) || vixCloses.size() < Math.min(days, ext.volatilityMinHistorySessions())) {
            return Double.NaN;
        }
        return percentile(value, vixCloses.subList(0, Math.min(days, vixCloses.size())));
    }

    private double percentileIfEnough(double value, List<Double> history) {
        return Double.isNaN(value) || history.size() < ext.volatilityMinHistorySessions() ? Double.NaN
                : percentile(value, history);
    }

    /** Percent of {@code history} below {@code value}, ties counted half (0..100). */
    public static double percentile(double value, List<Double> history) {
        if (history.isEmpty() || Double.isNaN(value)) {
            return Double.NaN;
        }
        double below = 0;
        for (double h : history) {
            if (h < value) {
                below += 1;
            } else if (h == value) {
                below += 0.5;
            }
        }
        return 100 * below / history.size();
    }

    /**
     * Annualised standard deviation of the last {@code window} one-minute log returns of
     * {@code closes}; NaN until {@code window} returns exist.
     */
    public static double realized(List<Double> closes, int window, double annualisationMinutes) {
        int n = closes.size();
        if (n < window + 1) {
            return Double.NaN;
        }
        double sum = 0;
        double sumSquares = 0;
        for (int i = n - window; i < n; i++) {
            double r = Math.log(closes.get(i) / closes.get(i - 1));
            sum += r;
            sumSquares += r * r;
        }
        double mean = sum / window;
        double variance = (sumSquares - window * mean * mean) / (window - 1);
        return Math.sqrt(Math.max(0, variance) * annualisationMinutes);
    }
}
