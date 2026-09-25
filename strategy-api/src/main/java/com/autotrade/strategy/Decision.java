package com.autotrade.strategy;

import java.time.Instant;
import java.util.List;

/** A strategy's full output for one snapshot. */
public record Decision(Instant time, String underlying, MarketState state, SideView ce, SideView pe,
                       List<OrderIntent> orders) {
}
