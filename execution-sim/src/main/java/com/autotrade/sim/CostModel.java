package com.autotrade.sim;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.autotrade.config.ThresholdConfig;

/**
 * Dated transaction costs for index options. Each trade date uses the newest rate set effective on
 * or before it, so replaying an old session charges the costs that applied then.
 */
public final class CostModel {

    record RateSet(LocalDate effectiveFrom, String source, double brokeragePerOrder, double sttSellFraction,
                   Map<String, Double> exchangeFraction, double sebiFraction, double stampBuyFraction,
                   double gstFraction) {
    }

    private final List<RateSet> rateSets;
    private final String configHash;

    CostModel(List<RateSet> rateSets, String configHash) {
        this.rateSets = rateSets.stream().sorted(Comparator.comparing(RateSet::effectiveFrom)).toList();
        this.configHash = configHash;
    }

    @SuppressWarnings("unchecked")
    public static CostModel from(ThresholdConfig config) {
        List<RateSet> sets = new ArrayList<>();
        for (Object item : (List<Object>) config.get("rate_sets")) {
            Map<String, Object> set = (Map<String, Object>) item;
            Map<String, Double> exchange = new TreeMap<>();
            ((Map<String, Object>) set.get("exchange_fraction"))
                    .forEach((name, value) -> exchange.put(name, ((Number) value).doubleValue()));
            sets.add(new RateSet(LocalDate.parse((String) set.get("effective_from")), (String) set.get("source"),
                    number(set, "brokerage_per_order"), number(set, "stt_sell_fraction"), Map.copyOf(exchange),
                    number(set, "sebi_fraction"), number(set, "stamp_buy_fraction"), number(set, "gst_fraction")));
        }
        if (sets.isEmpty()) {
            throw new IllegalArgumentException("cost config has no rate_sets");
        }
        return new CostModel(sets, config.contentHash());
    }

    public String configHash() {
        return configHash;
    }

    /** Charges for one order leg of {@code quantity} units at {@code price} on {@code exchange} ("NSE"/"BSE"). */
    public CostBreakdown costs(LocalDate tradeDate, String exchange, Side side, double price, long quantity) {
        RateSet rates = ratesFor(tradeDate);
        Double exchangeRate = rates.exchangeFraction().get(exchange);
        if (exchangeRate == null) {
            throw new IllegalArgumentException("no exchange charge for " + exchange);
        }
        double turnover = price * quantity;
        double brokerage = rates.brokeragePerOrder();
        double stt = side == Side.SELL ? turnover * rates.sttSellFraction() : 0;
        double exchangeCharge = turnover * exchangeRate;
        double sebi = turnover * rates.sebiFraction();
        double stamp = side == Side.BUY ? turnover * rates.stampBuyFraction() : 0;
        double gst = (brokerage + exchangeCharge + sebi) * rates.gstFraction();
        return new CostBreakdown(turnover, brokerage, stt, exchangeCharge, sebi, stamp, gst,
                rates.effectiveFrom().toString());
    }

    RateSet ratesFor(LocalDate tradeDate) {
        RateSet chosen = null;
        for (RateSet set : rateSets) {
            if (!set.effectiveFrom().isAfter(tradeDate)) {
                chosen = set;
            }
        }
        if (chosen == null) {
            throw new IllegalArgumentException("no cost rates effective on " + tradeDate);
        }
        return chosen;
    }

    private static double number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("cost rate " + key + " missing or not a number");
        }
        return number.doubleValue();
    }
}
