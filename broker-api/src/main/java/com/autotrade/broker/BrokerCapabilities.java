package com.autotrade.broker;

/** What a broker adapter supports; the OMS adapts (e.g. slicing, tag length) to it. */
public record BrokerCapabilities(boolean stopLimit, boolean modify, int maxOrdersPerSecond, int tagMaxLength,
                                 boolean paper) {
}
