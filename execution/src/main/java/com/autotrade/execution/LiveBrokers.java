package com.autotrade.execution;

import java.util.Set;

import com.autotrade.account.BrokerType;

/**
 * The brokers an account may be switched LIVE on (8 Nov 2026: one set, read by the LIVE switch, the workspace's day
 * switch, the admin's LIVE eligibility and the UI). A broker joins only after its adapter's gates in
 * docs/plans/2026-11-08-live-adapters-plan.md (simulator fault suite, READ_LIVE sessions, canary) and the user's go-ahead;
 * its adapter's {@code live()} must also return a connection.
 */
public final class LiveBrokers {

    public static final Set<BrokerType> LIVE = Set.of(BrokerType.ZERODHA);

    private LiveBrokers() {
    }

    public static boolean live(BrokerType broker) {
        return LIVE.contains(broker);
    }
}
