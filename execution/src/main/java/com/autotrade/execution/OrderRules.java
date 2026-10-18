package com.autotrade.execution;

import com.autotrade.config.ThresholdConfig;

/**
 * How a broker-routed follower works its orders (follower-risk v3 {@code orders} / {@code broker_stop}; the values are
 * paper-risk v17's, so a follower's broker orders are priced and chased as PAPER-1's OMS prices and chases its own):
 * <ul>
 *   <li>entry: a marketable limit at the broker's ask + {@code entryBufferTicks}; what is unfilled after
 *       {@code entryTimeoutSec} is cancelled and the position is what filled;</li>
 *   <li>exit: a limit at the bid − max({@code exitBufferTicks}, {@code exitBufferPct} % of the bid), re-priced at the new
 *       touch every {@code exitChaseSec} up to {@code exitChaseMax} times, then {@code exitCrossPct} % through the
 *       touch;</li>
 *   <li>protective stop: a resting stop-limit sell at the premium stop (the signal's {@code stop_pct}, else
 *       {@code defaultStopPct}) with its limit {@code stopLimitOffsetPct} % below the trigger;</li>
 *   <li>{@code tick}: the price step of index options (Rs 0.05).</li>
 * </ul>
 */
public record OrderRules(int entryBufferTicks, double entryTimeoutSec, int exitBufferTicks, double exitBufferPct,
                         double exitChaseSec, int exitChaseMax, double exitCrossPct, double defaultStopPct,
                         double stopLimitOffsetPct, double tick, double pollMs) {

    /** The rules of a risk file, or null when it has none (v1, v2: simulated fills only). */
    static OrderRules from(ThresholdConfig c) {
        if (!c.has("orders.entry_buffer_ticks")) {
            return null;
        }
        return new OrderRules(c.getInt("orders.entry_buffer_ticks"), c.getDouble("orders.entry_timeout_sec"),
                c.getInt("orders.exit_buffer_ticks"), c.getDouble("orders.exit_buffer_pct"),
                c.getDouble("orders.exit_chase_sec"), c.getInt("orders.exit_chase_max"),
                c.getDouble("orders.exit_cross_pct"), c.getDouble("broker_stop.default_stop_pct"),
                c.getDouble("broker_stop.limit_offset_pct"), c.has("orders.tick") ? c.getDouble("orders.tick") : 0.05,
                c.has("orders.poll_ms") ? c.getDouble("orders.poll_ms") : 250);
    }

    /** The same rules with the waits shortened (tests against the in-process simulator). */
    public OrderRules fast() {
        return new OrderRules(entryBufferTicks, 0.2, exitBufferTicks, exitBufferPct, 0.05, exitChaseMax, exitCrossPct,
                defaultStopPct, stopLimitOffsetPct, tick, 5);
    }
}
