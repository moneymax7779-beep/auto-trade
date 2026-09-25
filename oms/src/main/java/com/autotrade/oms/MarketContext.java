package com.autotrade.oms;

import java.time.LocalTime;

/** Market facts at decision time that risk checks need. */
public record MarketContext(LocalTime time, double secondsSinceSpot, double secondsSinceOption) {
}
