package com.autotrade.features.breadth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.autotrade.core.event.ConstituentTick;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.indicators.TimedSeries;
import com.autotrade.features.snapshot.BreadthFeatures;

/**
 * Index-weighted participation of constituents. Momentum breadth weights each stock's squashed
 * short-window return, tanh(r / scale), so one heavyweight cannot saturate the score; day breadth is
 * the weighted share above versus below the previous close.
 */
public final class BreadthState {

    private static final class Stock {
        final TimedSeries price = new TimedSeries(Duration.ofMinutes(20));
        double weight = Double.NaN;
        double previousClose = Double.NaN;
    }

    private final FeatureConfig config;
    private final Map<String, Stock> stocks = new HashMap<>();

    public BreadthState(FeatureConfig config) {
        this.config = config;
    }

    public void onConstituent(ConstituentTick tick) {
        Stock stock = stocks.computeIfAbsent(tick.symbol(), symbol -> new Stock());
        stock.price.add(tick.receivedAt(), tick.price());
        if (tick.weightPercent() != null) {
            stock.weight = tick.weightPercent();
        }
        if (tick.previousClose() != null && tick.previousClose() > 0) {
            stock.previousClose = tick.previousClose();
        }
    }

    /** Latest known index weight (percent) per constituent symbol. */
    public Map<String, Double> weights() {
        Map<String, Double> weights = new HashMap<>();
        stocks.forEach((symbol, stock) -> {
            if (!Double.isNaN(stock.weight)) {
                weights.put(symbol, stock.weight);
            }
        });
        return weights;
    }

    public BreadthFeatures snapshot(Instant time) {
        Duration window = Duration.ofMinutes(config.breadthReturnWindowMin());
        double scale = config.breadthReturnScalePct() / 100.0;
        double momentum = 0;
        double day = 0;
        double covered = 0;
        double total = 0;
        List<Double> contributions = new ArrayList<>();
        for (Stock stock : stocks.values()) {
            if (Double.isNaN(stock.weight)) {
                continue;
            }
            total += stock.weight;
            double now = stock.price.valueAt(time);
            double before = stock.price.valueAt(time.minus(window));
            if (Double.isNaN(now) || Double.isNaN(before) || before <= 0) {
                continue;
            }
            double change = now / before - 1;
            covered += stock.weight;
            momentum += stock.weight * Math.tanh(change / scale);
            contributions.add(stock.weight * change);
            if (!Double.isNaN(stock.previousClose)) {
                day += stock.weight * Math.signum(now - stock.previousClose);
            }
        }
        double coverage = total > 0 ? 100 * covered / total : 0;
        return new BreadthFeatures(
                covered > 0 ? 100 * momentum / covered : Double.NaN,
                covered > 0 ? 100 * day / covered : Double.NaN,
                coverage,
                concentration(contributions),
                stocks.size());
    }

    /** Share of the dominant side's summed contribution that comes from its top N stocks. */
    private double concentration(List<Double> contributions) {
        double net = contributions.stream().mapToDouble(Double::doubleValue).sum();
        if (net == 0 || contributions.isEmpty()) {
            return Double.NaN;
        }
        double sign = Math.signum(net);
        List<Double> side = new ArrayList<>();
        for (double contribution : contributions) {
            if (Math.signum(contribution) == sign) {
                side.add(Math.abs(contribution));
            }
        }
        side.sort((a, b) -> Double.compare(b, a));
        double sideTotal = side.stream().mapToDouble(Double::doubleValue).sum();
        double top = side.stream().limit(config.concentrationTopN()).mapToDouble(Double::doubleValue).sum();
        return sideTotal > 0 ? top / sideTotal : Double.NaN;
    }
}
