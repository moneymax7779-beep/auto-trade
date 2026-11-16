package com.autotrade.exec.app;

import java.net.URI;
import java.util.List;

import javax.sql.DataSource;

import com.autotrade.execution.BrokerAdapter;
import com.autotrade.execution.LiveBrokers;
import com.autotrade.execution.ReadLiveProbe;

/** The worker's real adapters (no session, reads to a closed local port) and the default LIVE brokers, for the tests. */
final class TestBrokers {

    private static final URI LOCAL = URI.create("http://127.0.0.1:1");

    private TestBrokers() {
    }

    static List<BrokerAdapter> adapters(DataSource dataSource) {
        return ExecutionService.adapters(dataSource, 1e9, null, null, null, null, LOCAL,
                com.autotrade.upstox.orders.UpstoxConfig.at(LOCAL), com.autotrade.groww.GrowwConfig.at(LOCAL),
                ReadLiveProbe.Settings.OFF);
    }

    /** {@code autotrade.execution.live-brokers} at its default (ZERODHA) over the worker's adapters. */
    static LiveBrokers live(DataSource dataSource) {
        return LiveBrokers.of(LiveBrokers.DEFAULT, adapters(dataSource));
    }
}
