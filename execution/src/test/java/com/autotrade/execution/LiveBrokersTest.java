package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;

/**
 * The LIVE brokers from configuration (16 Nov 2026, multi-broker gap A2): configured and an adapter that offers LIVE;
 * the default is the Kite pilot alone.
 */
class LiveBrokersTest {

    private static final DataSource NO_DB = new DriverManagerDataSource();
    private static final List<BrokerAdapter> WORKER = List.of(new ZerodhaDryRunAdapter(NO_DB, 1e9),
            new KotakDryRunAdapter(NO_DB, 1e9), new UpstoxDryRunAdapter(NO_DB, 1e9), new GrowwDryRunAdapter(NO_DB, 1e9));

    /** An adapter of {@code broker} that offers a LIVE connection (as a future LIVE Upstox adapter would). */
    private static BrokerAdapter offeringLive(BrokerType broker) {
        return new BrokerAdapter() {
            @Override
            public BrokerType broker() {
                return broker;
            }

            @Override
            public String product() {
                return "I";
            }

            @Override
            public boolean offersLive() {
                return true;
            }

            @Override
            public DryRun dryRun(BrokerAccount account, OrderRules rules) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void theDefaultIsZerodhaAloneAndEveryBrokerWithAnAdapterMayTradeDryRun() {
        LiveBrokers live = LiveBrokers.of(LiveBrokers.DEFAULT, WORKER);
        assertThat(live.live()).containsExactly(BrokerType.ZERODHA);
        assertThat(live.live(BrokerType.ZERODHA)).isTrue();
        assertThat(live.live(BrokerType.UPSTOX)).isFalse();
        for (BrokerType b : List.of(BrokerType.ZERODHA, BrokerType.KOTAK_NEO, BrokerType.UPSTOX, BrokerType.GROWW)) {
            assertThat(live.hasAdapter(b)).as(b + "").isTrue();
        }
        assertThat(live.hasAdapter(BrokerType.PAPER)).isFalse();
    }

    @Test
    void aConfiguredBrokerIsLiveOnlyWhenItsAdapterOffersLive() {
        LiveBrokers configured = LiveBrokers.of(" zerodha , UPSTOX,KOTAK_NEO ", WORKER);
        assertThat(configured.live()).containsExactly(BrokerType.ZERODHA);
        assertThat(configured.configuredWithoutLiveAdapter()).containsExactlyInAnyOrder(BrokerType.UPSTOX,
                BrokerType.KOTAK_NEO);
        assertThat(LiveBrokers.of("ZERODHA,UPSTOX", List.of(WORKER.getFirst(), offeringLive(BrokerType.UPSTOX))).live())
                .containsExactlyInAnyOrder(BrokerType.ZERODHA, BrokerType.UPSTOX);
        assertThat(LiveBrokers.of("ZERODHA", List.of(WORKER.getFirst(), offeringLive(BrokerType.UPSTOX)))
                .live(BrokerType.UPSTOX)).as("offers LIVE but is not configured").isFalse();
        assertThat(LiveBrokers.of("", WORKER).live()).as("none configured").isEmpty();
        assertThat(LiveBrokers.of("ZERODHA", List.of()).live(BrokerType.ZERODHA)).as("no adapter").isFalse();
    }

    @Test
    void paperOrAnUnknownBrokerIsRefused() {
        assertThatThrownBy(() -> LiveBrokers.of("ZERODHA,PAPER", WORKER)).hasMessageContaining("PAPER is never LIVE");
        assertThatThrownBy(() -> LiveBrokers.of("ZERODHA,ANGEL", WORKER)).hasMessageContaining("unknown broker ANGEL");
    }
}
