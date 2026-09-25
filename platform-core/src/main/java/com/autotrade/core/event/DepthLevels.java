package com.autotrade.core.event;

import java.util.Arrays;

/** One side of the order book, best level first. Arrays are copied on the way in and out. */
public final class DepthLevels {

    private static final DepthLevels EMPTY = new DepthLevels(new double[0], new long[0], new int[0]);

    private final double[] prices;
    private final long[] quantities;
    private final int[] orders;

    private DepthLevels(double[] prices, long[] quantities, int[] orders) {
        this.prices = prices;
        this.quantities = quantities;
        this.orders = orders;
    }

    public static DepthLevels of(double[] prices, long[] quantities, int[] orders) {
        if (prices.length != quantities.length || prices.length != orders.length) {
            throw new IllegalArgumentException("depth arrays differ in length");
        }
        if (prices.length == 0) {
            return EMPTY;
        }
        return new DepthLevels(prices.clone(), quantities.clone(), orders.clone());
    }

    public static DepthLevels empty() {
        return EMPTY;
    }

    public int size() {
        return prices.length;
    }

    public boolean isEmpty() {
        return prices.length == 0;
    }

    public double price(int level) {
        return prices[level];
    }

    public long quantity(int level) {
        return quantities[level];
    }

    public int orders(int level) {
        return orders[level];
    }

    public double[] prices() {
        return prices.clone();
    }

    public long[] quantities() {
        return quantities.clone();
    }

    public int[] orderCounts() {
        return orders.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DepthLevels that
                && Arrays.equals(prices, that.prices)
                && Arrays.equals(quantities, that.quantities)
                && Arrays.equals(orders, that.orders);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(prices) + Arrays.hashCode(quantities)) + Arrays.hashCode(orders);
    }

    @Override
    public String toString() {
        return "DepthLevels" + Arrays.toString(prices) + "x" + Arrays.toString(quantities);
    }
}
