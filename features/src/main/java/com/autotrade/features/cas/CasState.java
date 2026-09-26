package com.autotrade.features.cas;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.config.FeatureExtensions;
import com.autotrade.features.futures.FuturesState;
import com.autotrade.features.indicators.TimedSeries;
import com.autotrade.features.snapshot.CasFeatures;

/**
 * Closing-auction features (features v3). The index reference is the mean continuous spot over the
 * reference window (15:00–15:15); the indicative index comes from the feed during CAS; per-stock
 * auction data (IEP, equilibrium quantity, imbalance) comes from {@link AuctionTick}s received during
 * CAS, weighted by the constituents' index weights.
 */
public final class CasState {

    private static final Duration RETENTION = Duration.ofMinutes(15);

    private final FeatureExtensions ext;
    private final Instant referenceFrom;
    private final Instant referenceTo;
    private final TimedSeries indicative = new TimedSeries(RETENTION);
    private final TimedSeries weightedImbalance = new TimedSeries(RETENTION);
    private final Map<String, AuctionTick> auctions = new HashMap<>();
    private double referenceSum;
    private int referenceCount;

    public CasState(FeatureExtensions ext, LocalDate session) {
        this.ext = ext;
        this.referenceFrom = session.atTime(ext.casReferenceFrom()).atZone(MarketTime.IST).toInstant();
        this.referenceTo = session.atTime(ext.casReferenceTo()).atZone(MarketTime.IST).toInstant();
    }

    public void onContinuousSpot(Instant time, double price) {
        if (!time.isBefore(referenceFrom) && time.isBefore(referenceTo)) {
            referenceSum += price;
            referenceCount++;
        }
    }

    public void onIndicative(Instant time, double price) {
        if (price > 0) {
            indicative.add(time, price);
        }
    }

    /** A constituent's auction state; call only for ticks received during the closing auction. */
    public void onAuction(AuctionTick tick) {
        if (tick.indicativePrice() > 0) {
            auctions.put(tick.symbol(), tick);
        }
    }

    /**
     * @param inCas            whether {@code time} is inside the closing auction
     * @param showIndicative   whether the indicative value is shown (CAS and the derivatives-only tail)
     * @param weights          constituent index weights (percent) by symbol
     */
    public CasFeatures snapshot(Instant time, String feedPhase, boolean inCas, boolean showIndicative,
                                double lastContinuousSpot, FuturesState futures, double orh, double orl,
                                Map<String, Double> weights) {
        double n = Double.NaN;
        double reference = referenceCount > 0 ? referenceSum / referenceCount : n;
        double value = showIndicative ? indicative.valueAt(time) : n;
        List<Integer> windows = ext.casIndicativeWindowsSec();
        Duration shortWindow = Duration.ofSeconds(windows.get(0));
        Duration midWindow = Duration.ofSeconds(windows.get(1));
        Duration longWindow = Duration.ofSeconds(windows.get(2));
        double changeMid = showIndicative ? indicative.change(time, midWindow) : n;
        double previousMid = showIndicative ? indicative.change(time.minus(midWindow), midWindow) : n;
        double futuresChange = futures.priceChange(time, Duration.ofMinutes(1));
        double indicativeChange1m = showIndicative ? indicative.change(time, Duration.ofMinutes(1)) : n;

        Constituents c = constituents(weights);
        if (inCas && !Double.isNaN(c.imbalance)) {
            weightedImbalance.add(time, c.imbalance);
        }
        double imbalanceChange = inCas ? weightedImbalance.change(time, Duration.ofSeconds(ext.casImbalanceVelocitySec()))
                : n;
        return new CasFeatures(
                feedPhase, value, reference,
                reference > 0 && !Double.isNaN(value) ? 100 * (value / reference - 1) : n,
                inCas ? value - lastContinuousSpot : n,
                showIndicative ? indicative.change(time, shortWindow) : n, changeMid,
                showIndicative ? indicative.change(time, longWindow) : n,
                changeMid - previousMid,
                inCas ? futures.latestPrice() - value : n,
                inCas ? futuresChange - indicativeChange1m : n,
                inCas ? futuresChange : n,
                inCas && indicativeChange1m != 0 && !Double.isNaN(indicativeChange1m) ? futuresChange / indicativeChange1m : n,
                inCas && value > orh, inCas && value < orl,
                inCas ? c.iepReturnPct : n, inCas ? c.imbalance : n, imbalanceChange,
                inCas ? c.breadthPct : n, inCas ? c.concentration : n, inCas ? c.turnoverCr : n,
                inCas ? c.coveragePct : n, inCas ? c.stocks : 0);
    }

    private record Constituents(double iepReturnPct, double imbalance, double breadthPct, double concentration,
                                double turnoverCr, double coveragePct, int stocks) {
    }

    private Constituents constituents(Map<String, Double> weights) {
        double totalWeight = 0;
        for (double weight : weights.values()) {
            totalWeight += weight;
        }
        double covered = 0;
        double returns = 0;
        double imbalance = 0;
        double imbalanceWeight = 0;
        double positive = 0;
        double turnover = 0;
        List<Double> contributions = new ArrayList<>();
        int stocks = 0;
        for (Map.Entry<String, AuctionTick> entry : auctions.entrySet()) {
            Double weight = weights.get(entry.getKey());
            AuctionTick tick = entry.getValue();
            if (weight == null || !(tick.referencePrice() > 0)) {
                continue;
            }
            stocks++;
            covered += weight;
            double ret = tick.indicativePrice() / tick.referencePrice() - 1;
            returns += weight * ret;
            contributions.add(weight * ret);
            if (ret > 0) {
                positive += weight;
            }
            double denominator = 2.0 * tick.equilibriumQuantity() + Math.abs(tick.imbalanceTotal());
            if (denominator > 0) {
                imbalance += weight * tick.imbalanceTotal() / denominator;
                imbalanceWeight += weight;
            }
            turnover += tick.indicativePrice() * tick.equilibriumQuantity();
        }
        if (covered == 0) {
            return new Constituents(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    totalWeight > 0 ? 0 : Double.NaN, 0);
        }
        return new Constituents(100 * returns / covered,
                imbalanceWeight > 0 ? imbalance / imbalanceWeight : Double.NaN,
                100 * positive / covered, concentration(contributions), turnover / 1e7,
                totalWeight > 0 ? 100 * covered / totalWeight : Double.NaN, stocks);
    }

    /** Share of the dominant side's contribution that comes from its top N stocks. */
    private double concentration(List<Double> contributions) {
        double net = contributions.stream().mapToDouble(Double::doubleValue).sum();
        if (net == 0) {
            return Double.NaN;
        }
        List<Double> side = new ArrayList<>();
        for (double contribution : contributions) {
            if (Math.signum(contribution) == Math.signum(net)) {
                side.add(Math.abs(contribution));
            }
        }
        side.sort((a, b) -> Double.compare(b, a));
        double total = side.stream().mapToDouble(Double::doubleValue).sum();
        double top = side.stream().limit(ext.casConcentrationTopN()).mapToDouble(Double::doubleValue).sum();
        return total > 0 ? top / total : Double.NaN;
    }
}
