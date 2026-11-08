package com.autotrade.exec.app;

import java.util.Map;
import java.util.Optional;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.execution.BrokerSessions;

/**
 * The worker's broker sessions, by the account's broker (8 Nov 2026): ZERODHA from {@link KiteSessionSource} (as
 * before), KOTAK_NEO from {@link KotakSessionSource}, UPSTOX from {@link UpstoxSessionSource} (the member's own Upstox
 * login, never zt-tiger-v2's token); any other broker has none.
 */
final class BrokerSessionRouter implements BrokerSessions {

    private final Map<BrokerType, BrokerSessions> byBroker;

    BrokerSessionRouter(Map<BrokerType, BrokerSessions> byBroker) {
        this.byBroker = Map.copyOf(byBroker);
    }

    @Override
    public Optional<Access> access(BrokerAccount account) {
        BrokerSessions s = byBroker.get(account.brokerType());
        return s == null ? Optional.empty() : s.access(account);
    }
}
