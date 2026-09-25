package com.autotrade.features.options;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.autotrade.core.event.OptionTick;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.greeks.BlackScholes;
import com.autotrade.features.indicators.TimedSeries;
import com.autotrade.features.time.SessionClock;
import com.autotrade.features.snapshot.OptionsFeatures;

/**
 * The nearest-expiry option chain as captured (an ATM band). Keeps each contract's recent OI, price
 * and volume so near-ATM OI velocity, call barriers / put floors, straddle and IV behaviour can be
 * measured at any snapshot time.
 */
public final class OptionChainState {

    private static final Duration RETENTION = Duration.ofMinutes(15);

    /** Recent state of one option contract. */
    static final class Contract {
        final double strike;
        final boolean call;
        final TimedSeries oi = new TimedSeries(RETENTION);
        final TimedSeries mid = new TimedSeries(RETENTION);
        final TimedSeries volume = new TimedSeries(RETENTION);
        OptionTick last;

        Contract(double strike, boolean call) {
            this.strike = strike;
            this.call = call;
        }

        void update(OptionTick tick) {
            last = tick;
            Instant time = tick.receivedAt();
            if (tick.openInterest() != null) {
                oi.add(time, tick.openInterest());
            }
            if (tick.cumulativeVolume() != null) {
                volume.add(time, tick.cumulativeVolume());
            }
            mid.add(time, midPrice(tick));
        }
    }

    private final FeatureConfig config;
    private final SessionClock clock;
    private final LocalDate session;
    private final Map<Long, Contract> contracts = new HashMap<>();
    private final TreeSet<Double> strikes = new TreeSet<>();
    private final TimedSeries atmIvSeries = new TimedSeries(RETENTION);
    private final TimedSeries straddleSeries = new TimedSeries(RETENTION);
    private LocalDate expiry;
    private Instant lastTime;

    public OptionChainState(FeatureConfig config, SessionClock clock, LocalDate session) {
        this.config = config;
        this.clock = clock;
        this.session = session;
    }

    public void onOption(OptionTick tick) {
        if (tick.expiry() == null || tick.expiry().isBefore(session)) {
            return;
        }
        if (expiry == null || tick.expiry().isBefore(expiry)) {
            expiry = tick.expiry();
            contracts.clear();
            strikes.clear();
        } else if (tick.expiry().isAfter(expiry)) {
            return;
        }
        contracts.computeIfAbsent(tick.instrumentToken(),
                token -> new Contract(tick.strike(), tick.optionType() == OptionTick.OptionType.CE)).update(tick);
        strikes.add(tick.strike());
        lastTime = tick.receivedAt();
    }

    public LocalDate expiry() {
        return expiry;
    }

    public Instant lastTime() {
        return lastTime;
    }

    /** Smallest gap between captured strikes (50 for NIFTY, 100 for SENSEX). */
    public double strikeStep() {
        double step = Double.NaN;
        Double previous = null;
        for (double strike : strikes) {
            if (previous != null && (Double.isNaN(step) || strike - previous < step)) {
                step = strike - previous;
            }
            previous = strike;
        }
        return step;
    }

    public OptionsFeatures snapshot(Instant time, double spot, double atr1m, TimedSeries spotSeries) {
        double step = strikeStep();
        if (expiry == null || Double.isNaN(step) || Double.isNaN(spot)) {
            return empty();
        }
        double atm = Math.round(spot / step) * step;
        List<Integer> windows = config.oiDeltaWindowsMin();
        double[] ce = new double[windows.size()];
        double[] pe = new double[windows.size()];
        double nearOi = 0;
        for (Contract contract : contracts.values()) {
            int offset = (int) Math.round((contract.strike - atm) / step);
            if (Math.abs(offset) > config.nearAtmStrikes()) {
                continue;
            }
            double weight = Math.abs(offset) <= config.heavyWeightStrikes() ? 1.0 : config.outerStrikeWeight();
            nearOi += contract.oi.isEmpty() ? 0 : contract.oi.latest();
            for (int i = 0; i < windows.size(); i++) {
                double change = contract.oi.change(time, Duration.ofMinutes(windows.get(i)));
                if (!Double.isNaN(change)) {
                    if (contract.call) {
                        ce[i] += weight * change;
                    } else {
                        pe[i] += weight * change;
                    }
                }
            }
        }
        double flat = config.oiFlowFlatFraction() * nearOi;
        Barrier callBarrier = barrier(time, spot, step, true);
        Barrier putSupport = barrier(time, spot, step, false);

        Contract atmCe = find(atm, true);
        Contract atmPe = find(atm, false);
        double years = clock.yearsToExpiry(time, expiry);
        Quote ceQuote = quote(atmCe, spot, years);
        Quote peQuote = quote(atmPe, spot, years);
        double straddle = ceQuote.mid + peQuote.mid;
        if (!Double.isNaN(straddle)) {
            straddleSeries.add(time, straddle);
        }
        double atmIv = average(ceQuote.iv, peQuote.iv);
        if (!Double.isNaN(atmIv)) {
            atmIvSeries.add(time, atmIv);
        }
        List<Integer> ivWindows = config.ivChangeWindowsMin();
        String ivSource = ceQuote.source.equals(peQuote.source) ? ceQuote.source : ceQuote.source + "/" + peQuote.source;
        return new OptionsFeatures(
                expiry.toString(), step, atm, contracts.size(),
                ce[0], ce[1], ce[2], ce[3], pe[0], pe[1], pe[2], pe[3],
                flow(ce, windows, flat), flow(pe, windows, flat),
                callBarrier.strike, callBarrier.score, putSupport.strike, putSupport.score,
                weakening(callBarrier, time, spotSeries, true), weakening(putSupport, time, spotSeries, false),
                straddle, straddleSeries.change(time, Duration.ofMinutes(config.straddleChangeWindowMin())),
                ceQuote.iv, peQuote.iv, atmIv,
                atmIvSeries.change(time, Duration.ofMinutes(ivWindows.get(0))),
                atmIvSeries.change(time, Duration.ofMinutes(ivWindows.get(1))),
                atmIvSeries.change(time, Duration.ofMinutes(ivWindows.get(2))),
                peQuote.iv - ceQuote.iv, ivSource,
                ceQuote.delta, ceQuote.gamma, ceQuote.vega, ceQuote.theta, peQuote.delta,
                premiumResponse(atmCe, ceQuote, time, spotSeries, atr1m),
                premiumResponse(atmPe, peQuote, time, spotSeries, atr1m),
                ceQuote.spreadPct, peQuote.spreadPct);
    }

    private record Barrier(double strike, double score, Contract contract) {
    }

    /**
     * Strongest call barrier above spot (or put support below): weighted OI share, fresh OI added
     * over 5 minutes and volume share, scaled 0–100 and decayed by distance from spot.
     */
    private Barrier barrier(Instant time, double spot, double step, boolean call) {
        Duration window = Duration.ofMinutes(5);
        double maxOi = 0;
        double maxFresh = 0;
        double maxVolume = 0;
        List<Contract> candidates = new ArrayList<>();
        for (Contract contract : contracts.values()) {
            if (contract.call != call || contract.oi.isEmpty()) {
                continue;
            }
            boolean onSide = call ? contract.strike >= spot : contract.strike <= spot;
            int offset = (int) Math.round(Math.abs(contract.strike - spot) / step);
            if (!onSide || offset > config.nearAtmStrikes() + 1) {
                continue;
            }
            candidates.add(contract);
            maxOi = Math.max(maxOi, contract.oi.latest());
            maxFresh = Math.max(maxFresh, positive(contract.oi.change(time, window)));
            maxVolume = Math.max(maxVolume, positive(contract.volume.change(time, window)));
        }
        Barrier best = new Barrier(Double.NaN, Double.NaN, null);
        for (Contract contract : candidates) {
            double oiShare = maxOi > 0 ? contract.oi.latest() / maxOi : 0;
            double fresh = maxFresh > 0 ? positive(contract.oi.change(time, window)) / maxFresh : 0;
            double volume = maxVolume > 0 ? positive(contract.volume.change(time, window)) / maxVolume : 0;
            double proximity = Math.exp(-Math.abs(contract.strike - spot) / (config.proximityDecayStrikes() * step));
            double score = 100 * proximity * (config.barrierOiWeight() * oiShare
                    + config.barrierFreshOiWeight() * fresh + config.barrierVolumeWeight() * volume);
            if (best.contract == null || score > best.score) {
                best = new Barrier(contract.strike, score, contract);
            }
        }
        return best;
    }

    /** OI at the barrier strike falling at every lookback point while spot moves toward it. */
    private boolean weakening(Barrier barrier, Instant time, TimedSeries spotSeries, boolean call) {
        if (barrier.contract == null) {
            return false;
        }
        double previous = Double.NaN;
        for (int minutes : config.wallWeakeningLookbackMin()) {
            double value = barrier.contract.oi.valueAt(time.minus(Duration.ofMinutes(minutes)));
            if (Double.isNaN(value) || (!Double.isNaN(previous) && value >= previous)) {
                return false;
            }
            previous = value;
        }
        double now = barrier.contract.oi.valueAt(time);
        double spotMove = spotSeries.change(time, Duration.ofMinutes(3));
        boolean approaching = call ? spotMove > 0 : spotMove < 0;
        return now < previous && approaching;
    }

    /**
     * Classifies OI flow from weighted changes over the configured windows (1, 3, 5, 10 minutes):
     * direction from the longest window, acceleration when the per-minute rate grows toward the
     * present, fading when the latest minute runs at under half the 10-minute rate.
     */
    static String flow(double[] changes, List<Integer> windows, double flat) {
        double r1 = changes[0] / windows.get(0);
        double r3 = changes[1] / windows.get(1);
        double r10 = changes[3] / windows.get(3);
        if (Math.abs(changes[3]) < flat && Math.abs(changes[1]) < flat) {
            return "FLAT";
        }
        double direction = Math.signum(Math.abs(changes[3]) >= flat ? changes[3] : changes[1]);
        String side = direction > 0 ? "BUILD" : "UNWIND";
        if (Math.signum(r1) == direction && Math.abs(r1) > Math.abs(r3) && Math.abs(r3) > Math.abs(r10)) {
            return "ACCELERATING_" + side;
        }
        if (Math.abs(r1) < 0.5 * Math.abs(r10)) {
            return "FADING_" + side;
        }
        return side;
    }

    private record Quote(double mid, double iv, double delta, double gamma, double vega, double theta,
                         double spreadPct, String source) {
        static final Quote NONE = new Quote(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, "NONE");
    }

    /** Feed IV and Greeks when the feed had them, otherwise implied from the mid price. */
    private Quote quote(Contract contract, double spot, double years) {
        if (contract == null || contract.last == null) {
            return Quote.NONE;
        }
        OptionTick tick = contract.last;
        double mid = midPrice(tick);
        double spreadPct = tick.spread() / mid * 100;
        boolean feed = tick.analyticsComplete() && tick.impliedVolatility() != null && tick.impliedVolatility() > 0
                && tick.delta() != null && tick.delta() != 0;
        if (feed) {
            return new Quote(mid, tick.impliedVolatility(), tick.delta(), value(tick.gamma()), value(tick.vega()),
                    value(tick.theta()), spreadPct, "FEED");
        }
        double iv = BlackScholes.impliedVol(contract.call, mid, spot, contract.strike, years, config.riskFreeRate());
        if (Double.isNaN(iv)) {
            return new Quote(mid, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, spreadPct, "NONE");
        }
        BlackScholes.Greeks greeks = BlackScholes.greeks(contract.call, spot, contract.strike, years,
                config.riskFreeRate(), iv);
        return new Quote(mid, iv, greeks.delta(), greeks.gamma(), greeks.vega(), greeks.theta(), spreadPct, "MODEL");
    }

    /**
     * Actual one-minute premium change divided by the change the Greeks predict from the spot move
     * (delta·ΔS + ½·gamma·ΔS²). Below 1 means the option is lagging its underlying (e.g. IV falling).
     */
    private double premiumResponse(Contract contract, Quote quote, Instant time, TimedSeries spotSeries,
                                   double atr1m) {
        if (contract == null || Double.isNaN(quote.delta) || !(atr1m > 0)) {
            return Double.NaN;
        }
        double spotMove = spotSeries.change(time, Duration.ofMinutes(1));
        if (Double.isNaN(spotMove) || Math.abs(spotMove) < config.premiumResponseMinMoveAtr() * atr1m) {
            return Double.NaN;
        }
        double expected = quote.delta * spotMove + 0.5 * value(quote.gamma) * spotMove * spotMove;
        double actual = contract.mid.change(time, Duration.ofMinutes(1));
        if (Double.isNaN(actual) || Math.abs(expected) < 0.05) {
            return Double.NaN;
        }
        return actual / expected;
    }

    private Contract find(double strike, boolean call) {
        for (Contract contract : contracts.values()) {
            if (contract.call == call && contract.strike == strike) {
                return contract;
            }
        }
        return null;
    }

    private OptionsFeatures empty() {
        double n = Double.NaN;
        return new OptionsFeatures(expiry == null ? null : expiry.toString(), n, n, contracts.size(), n, n, n, n, n, n, n,
                n, "UNKNOWN", "UNKNOWN", n, n, n, n, false, false, n, n, n, n, n, n, n, n, n, "NONE", n, n, n, n, n, n,
                n, n, n);
    }

    static double midPrice(OptionTick tick) {
        if (!tick.bids().isEmpty() && !tick.asks().isEmpty() && tick.bids().price(0) > 0 && tick.asks().price(0) > 0) {
            return (tick.bids().price(0) + tick.asks().price(0)) / 2;
        }
        return tick.lastPrice();
    }

    private static double positive(double value) {
        return Double.isNaN(value) ? 0 : Math.max(0, value);
    }

    private static double value(Double value) {
        return value == null ? Double.NaN : value;
    }

    private static double average(double a, double b) {
        if (Double.isNaN(a)) {
            return b;
        }
        return Double.isNaN(b) ? a : (a + b) / 2;
    }
}
