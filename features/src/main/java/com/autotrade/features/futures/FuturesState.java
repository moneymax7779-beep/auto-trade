package com.autotrade.features.futures;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.autotrade.core.event.FutureTick;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.bars.BarSeries;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.indicators.TimedSeries;
import com.autotrade.features.indicators.WilderAtr;
import com.autotrade.features.snapshot.FuturesFeatures;

/** The nearest-expiry future: momentum, acceleration, basis, OI state and time-of-day RVOL. */
public final class FuturesState {

    private static final Duration RETENTION = Duration.ofMinutes(15);

    private final FeatureConfig config;
    private final LocalDate session;
    private final Instant sessionOpen;
    private final VolumeProfile profile;
    private final List<double[]> rvolHistory = new ArrayList<>();

    private long token = -1;
    private LocalDate expiry;
    private String symbol;
    private TimedSeries price;
    private TimedSeries basis;
    private TimedSeries oi;
    private BarSeries oneMinute;
    private WilderAtr atr;
    private Map<LocalTime, Long> minuteVolumes;
    private Long lastCumulative;
    private double vwap = Double.NaN;
    private double firstOi = Double.NaN;
    private Instant lastTime;

    public FuturesState(FeatureConfig config, LocalDate session, Instant sessionOpen, VolumeProfile profile) {
        this.config = config;
        this.session = session;
        this.sessionOpen = sessionOpen;
        this.profile = profile;
    }

    public void onFuture(FutureTick tick, double latestSpot) {
        if (tick.expiry() != null && tick.expiry().isBefore(session)) {
            return;
        }
        if (token != tick.instrumentToken()) {
            boolean nearer = expiry == null || (tick.expiry() != null && tick.expiry().isBefore(expiry));
            if (!nearer) {
                return;
            }
            select(tick);
        }
        Instant time = tick.receivedAt();
        price.add(time, tick.price());
        if (!Double.isNaN(latestSpot)) {
            basis.add(time, tick.price() - latestSpot);
        }
        if (tick.openInterest() != null) {
            oi.add(time, tick.openInterest());
            if (Double.isNaN(firstOi)) {
                firstOi = tick.openInterest();
            }
        }
        long delta = 0;
        if (tick.cumulativeVolume() != null) {
            long cumulative = tick.cumulativeVolume();
            if (lastCumulative != null && cumulative >= lastCumulative) {
                delta = cumulative - lastCumulative;
            }
            lastCumulative = cumulative;
        }
        minuteVolumes.merge(minuteOf(time), delta, Long::sum);
        oneMinute.update(time, tick.price(), delta);
        if (tick.sessionVwap() != null && tick.sessionVwap() > 0) {
            vwap = tick.sessionVwap();
        }
        lastTime = time;
    }

    private void select(FutureTick tick) {
        token = tick.instrumentToken();
        expiry = tick.expiry();
        symbol = tick.symbol();
        price = new TimedSeries(RETENTION);
        basis = new TimedSeries(RETENTION);
        oi = new TimedSeries(RETENTION);
        oneMinute = new BarSeries(Duration.ofMinutes(1), sessionOpen, 400);
        atr = new WilderAtr(config.atrPeriod());
        oneMinute.onClose(atr::update);
        minuteVolumes = new TreeMap<>();
        lastCumulative = null;
        vwap = Double.NaN;
        firstOi = Double.NaN;
    }

    public Instant lastTime() {
        return lastTime;
    }

    public double latestPrice() {
        return price == null ? Double.NaN : price.latest();
    }

    public double latestBasis() {
        return basis == null ? Double.NaN : basis.latest();
    }

    public double vwap() {
        return vwap;
    }

    public FuturesFeatures snapshot(Instant time, double spotAtr3m) {
        if (price == null) {
            return new FuturesFeatures(null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, OiState.NEUTRAL.name(), 0,
                    Double.NaN, Double.NaN, profile.sessionCount(), Double.NaN);
        }
        oneMinute.advanceTo(time);
        List<Integer> windows = config.momentumWindowsSec();
        double m30 = price.change(time, Duration.ofSeconds(windows.get(0)));
        double m60 = price.change(time, Duration.ofSeconds(windows.get(1)));
        double m180 = price.change(time, Duration.ofSeconds(windows.get(2)));
        Duration accelerationWindow = Duration.ofSeconds(config.accelerationWindowSec());
        double previousMomentum = price.change(time.minus(accelerationWindow), accelerationWindow);
        double acceleration = price.change(time, accelerationWindow) - previousMomentum;
        double futuresAtr = atr.value();

        Duration oiWindow = Duration.ofMinutes(config.oiStateWindowMin());
        double oiChange = oi.change(time, oiWindow);
        double priceChange = price.change(time, oiWindow);
        OiState state = OiState.classify(priceChange, oiChange,
                Double.isNaN(spotAtr3m) ? 0 : config.oiStateMinPriceAtr() * spotAtr3m,
                oi.isEmpty() ? 0 : config.oiStateMinOiFraction() * oi.latest());

        LocalTime minute = minuteOf(time);
        long lastMinuteVolume = minuteVolumes.getOrDefault(minute.minusMinutes(1), 0L);
        double[] rvol = rvol(time);
        return new FuturesFeatures(
                symbol, price.latest(), vwap, m30, m60, m180,
                futuresAtr > 0 ? m60 / futuresAtr : Double.NaN,
                acceleration,
                basis.latest(),
                basis.change(time, Duration.ofMinutes(config.basisChangeWindowsMin().get(0))),
                basis.change(time, Duration.ofMinutes(config.basisChangeWindowsMin().get(1))),
                oi.latest(), oiChange, oi.latest() - firstOi, state.name(), lastMinuteVolume, rvol[0], rvol[1], (int) rvol[2], futuresAtr);
    }

    /** [rvol, slope per minute, sessions used] for the slot of completed minutes ending at {@code time}. */
    private double[] rvol(Instant time) {
        LocalTime end = minuteOf(time);
        List<LocalTime> minutes = new ArrayList<>();
        long current = 0;
        for (int i = config.rvolSlotMinutes(); i >= 1; i--) {
            LocalTime minute = end.minusMinutes(i);
            minutes.add(minute);
            current += minuteVolumes.getOrDefault(minute, 0L);
        }
        VolumeProfile.SlotMedian median = profile.slotMedian(minutes);
        double rvol = median.sessions() >= config.rvolMinHistorySessions() && median.median() > 0
                ? current / median.median() : Double.NaN;
        if (!rvolHistory.isEmpty() && rvolHistory.getLast()[0] == time.getEpochSecond()) {
            rvolHistory.removeLast();
        }
        rvolHistory.add(new double[] {time.getEpochSecond(), rvol});
        return new double[] {rvol, slope(), median.sessions()};
    }

    /** Least-squares slope per minute of the last few RVOL values; NaN if any is missing. */
    private double slope() {
        int n = config.rvolSlopePoints();
        if (rvolHistory.size() < n) {
            return Double.NaN;
        }
        List<double[]> points = rvolHistory.subList(rvolHistory.size() - n, rvolHistory.size());
        double meanX = 0;
        double meanY = 0;
        for (double[] point : points) {
            if (Double.isNaN(point[1])) {
                return Double.NaN;
            }
            meanX += point[0] / 60.0;
            meanY += point[1];
        }
        meanX /= n;
        meanY /= n;
        double numerator = 0;
        double denominator = 0;
        for (double[] point : points) {
            double dx = point[0] / 60.0 - meanX;
            numerator += dx * (point[1] - meanY);
            denominator += dx * dx;
        }
        return denominator == 0 ? Double.NaN : numerator / denominator;
    }

    private static LocalTime minuteOf(Instant time) {
        return time.atZone(MarketTime.IST).toLocalTime().withSecond(0).withNano(0);
    }
}
