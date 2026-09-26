package com.autotrade.strategy;

/** Splitting a position's intended lots across its tranches. */
public final class Tranches {

    private Tranches() {
    }

    /**
     * Lots per tranche by largest remainder: each tranche gets the floor of its fraction of
     * {@code intendedLots}, and the lots left over go to the largest remainders (ties to the earlier
     * tranche), so the tranches always sum to {@code intendedLots}.
     */
    public static int[] split(int intendedLots, double... fractions) {
        int n = fractions.length;
        int[] lots = new int[n];
        double[] remainders = new double[n];
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            double exact = fractions[i] * intendedLots;
            lots[i] = (int) Math.floor(exact);
            remainders[i] = exact - lots[i];
            assigned += lots[i];
        }
        while (assigned < intendedLots) {
            int best = 0;
            for (int i = 1; i < n; i++) {
                if (remainders[i] > remainders[best]) {
                    best = i;
                }
            }
            lots[best]++;
            remainders[best] = -1;
            assigned++;
        }
        return lots;
    }
}
