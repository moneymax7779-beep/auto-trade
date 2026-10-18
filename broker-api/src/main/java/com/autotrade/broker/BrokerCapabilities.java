package com.autotrade.broker;

/**
 * What a broker adapter supports; the OMS and the execution workers adapt to it (slicing, tag length, order rate, how
 * order updates arrive, whether funds can be read, how the daily login works, static-IP slots) instead of special-casing
 * brokers.
 */
public record BrokerCapabilities(boolean stopLimit, boolean modify, int maxOrdersPerSecond, int tagMaxLength,
                                 boolean paper, UpdateChannel updates, boolean fundsApi, LoginFlow login,
                                 int staticIpSlots) {

    /** How the adapter learns about order changes. The order book is pulled on a timer in every case. */
    public enum UpdateChannel { IN_PROCESS, WEBSOCKET, POSTBACK, POLLING }

    /** How a session is obtained. */
    public enum LoginFlow { NONE, DAILY_REQUEST_TOKEN, DAILY_OAUTH }

    /** The L0 shape: an in-process adapter with no funds API, login or IP registration. */
    public BrokerCapabilities(boolean stopLimit, boolean modify, int maxOrdersPerSecond, int tagMaxLength, boolean paper) {
        this(stopLimit, modify, maxOrdersPerSecond, tagMaxLength, paper, UpdateChannel.IN_PROCESS, false, LoginFlow.NONE, 0);
    }
}
