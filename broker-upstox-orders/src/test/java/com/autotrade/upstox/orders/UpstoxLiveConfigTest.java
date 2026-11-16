package com.autotrade.upstox.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.autotrade.broker.BrokerException;
import com.autotrade.broker.OrderBudget;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderType;

/**
 * Who may send Upstox orders, the per-user order budget and the egress address (multi-broker production gaps B1, C1, C2,
 * 16 Nov 2026), against the local Upstox simulator.
 */
class UpstoxLiveConfigTest {

    private static final String KEY = "NSE_FO|47168";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);

    private UpstoxSimulator sim;
    private UpstoxInstruments instruments;

    @BeforeEach
    void start() throws Exception {
        sim = new UpstoxSimulator(0, 2_500);
        sim.list(new UpstoxSimulator.Listing(KEY, "NIFTY 23100 PE 11 NOV 26", "NIFTY", LocalDate.of(2026, 11, 11), 23100,
                "PE", 65, 0.05));
        sim.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
        UpstoxHttp loader = new UpstoxHttp(UpstoxConfig.simulator(sim.baseUri()));
        instruments = UpstoxInstruments.load(loader, DAY);
        loader.close();
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    private static OrderRequest buy(String tag) {
        return new OrderRequest(tag, 3000047168L, KEY, "N", "NSE_FO", OrderSide.BUY, OrderType.LIMIT, 65, 100.2, 0, tag);
    }

    private UpstoxGateway gateway(UpstoxConfig config) {
        UpstoxHttp http = new UpstoxHttp(config);
        http.session("any-token");
        return new UpstoxGateway(config, http, instruments, sim::book);
    }

    @Test
    void onlyTheLiveFactoriesAndASimulatorSendAndAnEgressNeverChangesAConfigsKind() {
        UpstoxConfig live = UpstoxConfig.live();
        assertThat(live.ordersAllowed()).isTrue();
        assertThat(live.isLive()).isTrue();
        assertThat(live.ordersReachUpstox()).isTrue();
        assertThat(live.orderBase()).isEqualTo(UpstoxConfig.REAL_ORDER_API);
        UpstoxConfig liveSim = UpstoxConfig.liveSimulator(sim.baseUri());
        assertThat(liveSim.isLive()).isTrue();
        assertThat(liveSim.ordersReachUpstox()).isFalse();
        gateway(liveSim).place(buy("F1E1"));
        assertThat(sim.orderCount()).isEqualTo(1);
        assertThatThrownBy(() -> UpstoxConfig.liveSimulator(URI.create("https://api-hft.upstox.com")))
                .hasMessageContaining("loopback");

        InetAddress loopback = InetAddress.getLoopbackAddress();
        UpstoxConfig reads = UpstoxConfig.at(sim.baseUri()).withEgress(loopback);
        assertThat(reads.egress()).isEqualTo(loopback);
        assertThat(reads.ordersAllowed()).as("an egress keeps a read config read-only").isFalse();
        assertThatThrownBy(() -> gateway(reads).place(buy("F2E1"))).hasMessageContaining("no LIVE Upstox connection");
        assertThat(UpstoxConfig.real().withEgress(loopback).ordersAllowed()).isFalse();
        assertThat(live.withEgress(loopback).isLive()).isTrue();
        assertThat(live.withEgress(loopback).egress()).isEqualTo(loopback);
        assertThat(sim.orderCount()).isEqualTo(1);
    }

    /** Production gaps C2: one budget per Upstox user for LIVE orders that reach Upstox; any other gateway its own. */
    @Test
    void theOrderBudgetIsSharedPerUpstoxUserOnlyForLiveOrdersThatReachUpstox() {
        UpstoxConfig live = UpstoxConfig.live();
        OrderBudget a = UpstoxGateway.budgetFor(live, "AB1234");
        assertThat(UpstoxGateway.budgetFor(live.withEgress(InetAddress.getLoopbackAddress()), " ab1234 "))
                .as("the same user, another gateway: the same budget").isSameAs(a);
        assertThat(UpstoxGateway.budgetFor(live, "CD5678")).isNotSameAs(a);
        assertThatThrownBy(() -> UpstoxGateway.budgetFor(live, null)).hasMessageContaining("Upstox user");
        UpstoxConfig liveSim = UpstoxConfig.liveSimulator(sim.baseUri());
        assertThat(UpstoxGateway.budgetFor(liveSim, "AB1234")).isNotSameAs(a)
                .isNotSameAs(UpstoxGateway.budgetFor(liveSim, "AB1234"));
        assertThat(UpstoxGateway.budgetFor(UpstoxConfig.real(), "AB1234")).isNotSameAs(a);
        assertThat(UpstoxGateway.budgetFor(UpstoxConfig.simulator(sim.baseUri()), null))
                .isNotSameAs(UpstoxGateway.budgetFor(UpstoxConfig.simulator(sim.baseUri()), null));
    }

    /** Production gaps B1: the client binds to the config's egress; one this host lacks never falls back. */
    @Test
    void theClientLeavesFromTheEgressAddressAndNeverFallsBackToTheDefaultRoute() throws Exception {
        UpstoxConfig bound = UpstoxConfig.at(sim.baseUri()).withEgress(InetAddress.getByName("127.0.0.1"));
        assertThat(gateway(bound).orders()).isEmpty();
        UpstoxConfig missing = UpstoxConfig.at(sim.baseUri()).withEgress(InetAddress.getByName("192.0.2.77"));
        int requests = sim.requests();
        assertThatThrownBy(() -> gateway(missing).orders()).isInstanceOf(BrokerException.class);
        assertThat(sim.requests()).isEqualTo(requests);
    }
}
