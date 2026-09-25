package com.autotrade.strategy;

/** Long calls for a bullish view, long puts for a bearish one. Option buying only. */
public enum OptionSide {
    CE,
    PE;

    public int sign() {
        return this == CE ? 1 : -1;
    }
}
