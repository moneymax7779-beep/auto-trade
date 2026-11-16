package com.autotrade.groww;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.autotrade.broker.BrokerException;
import com.autotrade.broker.BrokerException.Kind;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderType;

/**
 * Who may send Groww orders, the per-client order limiter and the egress address (multi-broker production gaps B1, C1,
 * C2, 16 Nov 2026), against the local Groww simulator.
 */
class GrowwLiveConfigTest {

    private static final String KEY = "NSE_FO|47168";

    private GrowwSimulator sim;
    private GrowwInstruments instruments;

    @BeforeEach
    void start() throws Exception {
        sim = new GrowwSimulator(0, 2_500);
        sim.list(new GrowwSimulator.Listing(KEY, "NIFTY2611123100PE", "NIFTY", LocalDate.of(2026, 11, 11), 23100, "PE", 65,
                0.05));
        sim.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
        GrowwHttp loader = new GrowwHttp(GrowwConfig.simulator(sim.baseUri()));
        instruments = GrowwInstruments.load(loader);
        loader.close();
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    private static OrderRequest buy(String tag) {
        return new OrderRequest(tag, 3000047168L, KEY, "N", "NSE_FO", OrderSide.BUY, OrderType.LIMIT, 65, 100.2, 0, tag);
    }

    private GrowwGateway gateway(GrowwConfig config) {
        GrowwHttp http = new GrowwHttp(config);
        http.session("any-token");
        return new GrowwGateway(config, http, instruments, sim::book);
    }

    /**
     * Until 16 Nov 2026 the refusal was decided by the host: a read config ({@link GrowwConfig#at}, the public
     * constructor) pointed at a loopback address could place. Now a read config never sends, whatever its host.
     */
    @Test
    void aReadConfigNeverSendsAnOrderWhateverItsHost() {
        for (GrowwConfig reads : new GrowwConfig[] {GrowwConfig.at(sim.baseUri()),
                new GrowwConfig(sim.baseUri(), sim.baseUri(), "MIS", null, 8, 200, null, 100),
                GrowwConfig.at(sim.baseUri()).withEgress(InetAddress.getLoopbackAddress()).withPageSize(50)}) {
            assertThat(reads.ordersAllowed()).isFalse();
            GrowwGateway g = gateway(reads);
            assertThatThrownBy(() -> g.place(buy("F1E1AAAA"))).hasMessageContaining("no LIVE Groww connection")
                    .satisfies(e -> assertThat(((BrokerException) e).kind()).isEqualTo(Kind.TERMINAL));
            assertThatThrownBy(() -> g.modify("F1E1AAAA", 65, 101, 0)).hasMessageContaining("no LIVE Groww connection");
            assertThatThrownBy(() -> g.cancel("F1E1AAAA")).hasMessageContaining("no LIVE Groww connection");
            assertThat(g.orders()).as("reads still work").isEmpty();
        }
        assertThat(sim.orderCount()).isZero();
    }

    @Test
    void onlyTheLiveFactoriesAndASimulatorSend() {
        GrowwConfig live = GrowwConfig.live();
        assertThat(live.ordersAllowed()).isTrue();
        assertThat(live.isLive()).isTrue();
        assertThat(live.ordersReachGroww()).isTrue();
        GrowwConfig liveSim = GrowwConfig.liveSimulator(sim.baseUri());
        assertThat(liveSim.isLive()).isTrue();
        assertThat(liveSim.ordersReachGroww()).isFalse();
        gateway(liveSim).place(buy("F1E1AAAA"));
        assertThat(sim.orderCount()).isEqualTo(1);
        assertThatThrownBy(() -> GrowwConfig.liveSimulator(URI.create("https://api.groww.in")))
                .hasMessageContaining("loopback");
        assertThatThrownBy(() -> GrowwConfig.simulator(URI.create("https://api.groww.in")))
                .hasMessageContaining("cannot name Groww");
        assertThat(live.withEgress(InetAddress.getLoopbackAddress()).isLive()).isTrue();
        assertThat(live.withPageSize(10).isLive()).as("a page size keeps the kind").isTrue();
    }

    /** Production gaps C2: one order limiter per Groww client for LIVE orders that reach Groww; any other its own. */
    @Test
    void theOrderLimiterIsSharedPerGrowwClientOnlyForLiveOrdersThatReachGroww() {
        GrowwConfig live = GrowwConfig.live();
        GrowwHttp.Limiter a = GrowwGateway.orderLimiter(live, "GW1234");
        assertThat(GrowwGateway.orderLimiter(live.withEgress(InetAddress.getLoopbackAddress()), " gw1234 ")).isSameAs(a);
        assertThat(GrowwGateway.orderLimiter(live, "GW5678")).isNotSameAs(a);
        assertThatThrownBy(() -> GrowwGateway.orderLimiter(live, " ")).hasMessageContaining("Groww client");
        GrowwConfig liveSim = GrowwConfig.liveSimulator(sim.baseUri());
        assertThat(GrowwGateway.orderLimiter(liveSim, "GW1234")).isNotSameAs(a)
                .isNotSameAs(GrowwGateway.orderLimiter(liveSim, "GW1234"));
        assertThat(GrowwGateway.orderLimiter(GrowwConfig.real(), "GW1234")).isNotSameAs(a);
        GrowwHttp http = new GrowwHttp(live);
        assertThat(new GrowwGateway(live, http, instruments, null, "GW1234").limiter()).isSameAs(a);
        assertThat(new GrowwGateway(liveSim, new GrowwHttp(liveSim), instruments, null, "GW1234").limiter())
                .isNotSameAs(a);
    }

    /** Production gaps B1: the client binds to the config's egress; one this host lacks never falls back. */
    @Test
    void theClientLeavesFromTheEgressAddressAndNeverFallsBackToTheDefaultRoute() throws Exception {
        GrowwConfig bound = GrowwConfig.at(sim.baseUri()).withEgress(InetAddress.getByName("127.0.0.1"));
        assertThat(gateway(bound).orders()).isEmpty();
        GrowwConfig missing = GrowwConfig.at(sim.baseUri()).withEgress(InetAddress.getByName("192.0.2.77"));
        int requests = sim.requests();
        assertThatThrownBy(() -> gateway(missing).orders()).isInstanceOf(BrokerException.class);
        assertThat(sim.requests()).isEqualTo(requests);
    }
}
