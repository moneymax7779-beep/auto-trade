package com.autotrade.kotak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.autotrade.broker.BrokerException;
import com.autotrade.broker.BrokerException.Kind;
import com.autotrade.broker.OrderBudget;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderStatus;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.OrderUpdate;

/**
 * Who may send Kotak orders, a session swap, the per-consumer-key budget and the egress address (multi-broker production
 * gaps B1, C1, C2, 16 Nov 2026), against the local Kotak simulator.
 */
class KotakLiveConfigTest {

    private static final String KEY = "NSE_FO|47168";
    private static final String NIFTY_SYMBOL = "NIFTY25N1123100PE";

    private KotakSimulator sim;
    private KotakInstruments instruments;

    @BeforeEach
    void start() throws Exception {
        sim = new KotakSimulator(0, "sim-consumer-key", 2_500);
        sim.list(new KotakSimulator.Listing(47168, "nse_fo", NIFTY_SYMBOL, "NIFTY", LocalDate.of(2026, 11, 11), 23100, "PE",
                65, 0.05));
        sim.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
        KotakHttp loader = new KotakHttp(KotakConfig.simulator(sim.baseUri()));
        instruments = KotakInstruments.load(loader);
        loader.close();
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    private static OrderRequest buy(String tag, long quantity, double limit) {
        return new OrderRequest(tag, 3000047168L, KEY, "NIFTY 23100 PE", "NSE_FO", OrderSide.BUY, OrderType.LIMIT,
                quantity, limit, 0, tag);
    }

    private static OrderRequest stop(String tag, long quantity, double limit, double trigger) {
        return new OrderRequest(tag, 3000047168L, KEY, "N", "NSE_FO", OrderSide.SELL, OrderType.STOP_LIMIT, quantity, limit,
                trigger, tag);
    }

    private KotakGateway gateway(KotakConfig config) {
        KotakHttp http = new KotakHttp(config);
        http.session("any-token", "any-sid");
        return new KotakGateway(config, http, instruments, sim::book);
    }

    /** Before 16 Nov 2026 a session (read) config pointed at any trusted base could place: now it never sends. */
    @Test
    void aReadConfigNeverSendsAnOrderWhateverItsBase() {
        for (KotakConfig reads : new KotakConfig[] {KotakConfig.session("k", sim.baseUri()),
                KotakConfig.session("k", sim.baseUri(), InetAddress.getLoopbackAddress()),
                new KotakConfig("k", sim.baseUri(), "MIS", null, 8, null)}) {
            assertThat(reads.ordersAllowed()).isFalse();
            assertThat(reads.isLive()).isFalse();
            KotakGateway g = gateway(reads);
            assertThatThrownBy(() -> g.place(buy("F1E1", 65, 100.2))).hasMessageContaining("read-only")
                    .satisfies(e -> assertThat(((BrokerException) e).kind()).isEqualTo(Kind.TERMINAL));
            assertThatThrownBy(() -> g.modify("F1E1", 65, 101, 0)).hasMessageContaining("read-only");
            assertThatThrownBy(() -> g.cancel("F1E1")).hasMessageContaining("read-only");
            assertThat(g.orders()).as("reads still work").isEmpty();
        }
        assertThat(sim.requests("place") + sim.requests("modify") + sim.requests("cancel")).as("nothing sent").isZero();
        assertThat(sim.orderCount()).isZero();
    }

    @Test
    void onlyTheExplicitLiveFactoryAndASimulatorSendAndASimulatorNeverNamesKotak() {
        KotakConfig live = KotakConfig.live("k", sim.baseUri(), null);
        assertThat(live.ordersAllowed()).isTrue();
        assertThat(live.isLive()).isTrue();
        assertThat(live.ordersReachKotak()).as("a loopback simulator: nothing leaves the machine").isFalse();
        gateway(live).place(buy("F1E1", 65, 100.2));
        assertThat(sim.orderCount()).isEqualTo(1);
        assertThat(KotakConfig.live("k", URI.create("https://e21.kotaksecurities.com"), null).ordersReachKotak()).isTrue();
        assertThat(KotakConfig.simulator(sim.baseUri()).ordersAllowed()).isTrue();
        assertThat(KotakConfig.simulator(sim.baseUri()).isLive()).isFalse();
        assertThatThrownBy(() -> KotakConfig.simulator(URI.create("https://e21.kotaksecurities.com")))
                .hasMessageContaining("cannot name Kotak");
        assertThatThrownBy(() -> KotakConfig.live("k", URI.create("https://evil.example.com"), null))
                .hasMessageContaining("https on kotaksecurities.com");
        assertThat(KotakConfig.live("secret-key", sim.baseUri(), InetAddress.getLoopbackAddress()).toString())
                .doesNotContain("secret-key").contains("egress=127.0.0.1").contains("orders=LIVE");
    }

    /**
     * A login during the day may name another base URL and consumer key: the gateway takes the new client and keeps its
     * listeners, its placed tags and its last reports (16 Nov 2026: the adapter rebuilt the gateway and lost them).
     */
    @Test
    void aSessionSwapKeepsTheListenersThePlacedTagsAndTheLastReports() {
        sim.strictSessions();
        KotakSimulator.Session first = sim.login();
        KotakConfig before = KotakConfig.live("key-1", sim.baseUri(), null);
        KotakHttp firstHttp = new KotakHttp(before);
        firstHttp.session(first.token(), first.sid());
        KotakGateway gateway = new KotakGateway(before, firstHttp, instruments, sim::book);
        List<OrderUpdate> updates = new ArrayList<>();
        gateway.onUpdate(updates::add);
        gateway.place(buy("F1E1", 65, 100.2));
        gateway.place(stop("F1S1", 65, 71.0, 75.0));
        gateway.poll();
        assertThat(updates).extracting(OrderUpdate::clientOrderId).contains("F1E1");
        int before1 = updates.size();

        // the new login: another token and sid, another base URL (the same simulator by another name) and consumer key
        KotakSimulator.Session second = sim.login();
        URI otherName = URI.create("http://localhost:" + sim.baseUri().getPort());
        KotakConfig after = KotakConfig.live("key-2", otherName, null);
        KotakHttp secondHttp = new KotakHttp(after);
        secondHttp.session(second.token(), second.sid());
        gateway.swap(after, secondHttp);
        firstHttp.retire();
        assertThat(gateway.http()).isSameAs(secondHttp);

        assertThat(gateway.poll()).as("the reports from before the login are remembered: nothing new").isZero();
        gateway.place(buy("F1E1", 65, 100.2));
        assertThat(sim.orderCount()).as("a tag placed before the login is not placed again").isEqualTo(2);
        sim.quote(KEY, 74.0, 6500, 74.5, 6500, 74.9);                    // the stop placed before the login fills
        assertThat(gateway.poll()).isEqualTo(1);
        assertThat(updates.subList(before1, updates.size())).singleElement().satisfies(u -> {
            assertThat(u.clientOrderId()).isEqualTo("F1S1");
            assertThat(u.status()).isEqualTo(OrderStatus.FILLED);
        });
        assertThat(gateway.poll()).isZero();
    }

    @Test
    void aSwapNeverChangesTheConfigsKind() {
        KotakConfig live = KotakConfig.live("k", sim.baseUri(), null);
        KotakGateway gateway = gateway(live);
        KotakConfig reads = KotakConfig.session("k", sim.baseUri());
        assertThatThrownBy(() -> gateway.swap(reads, new KotakHttp(reads))).hasMessageContaining("kind");
        KotakConfig elsewhere = KotakConfig.live("k", sim.baseUri(), InetAddress.getLoopbackAddress());
        assertThatThrownBy(() -> gateway.swap(elsewhere, new KotakHttp(elsewhere))).as("another egress")
                .hasMessageContaining("egress");
        KotakConfig ok = KotakConfig.live("k2", sim.baseUri(), null);
        assertThatThrownBy(() -> gateway.swap(ok, new KotakHttp(KotakConfig.live("k2", sim.baseUri(), null))))
                .as("the client must be the config's own").isInstanceOf(IllegalArgumentException.class);
    }

    /** Production gaps C2: one minute budget per consumer key for LIVE configs that reach Kotak; any other its own. */
    @Test
    void theOrderBudgetIsSharedPerConsumerKeyOnlyForLiveOrdersThatReachKotak() {
        URI real = URI.create("https://e21.kotaksecurities.com");
        OrderBudget a = KotakGateway.budgetFor(KotakConfig.live("member-key", real, null));
        OrderBudget b = KotakGateway.budgetFor(KotakConfig.live("member-key", URI.create("https://e22.kotaksecurities.com"),
                null));
        assertThat(a).as("one consumer key, whatever the base URL").isSameAs(b);
        assertThat(KotakGateway.budgetFor(KotakConfig.live("other-key", real, null))).isNotSameAs(a);
        assertThat(KotakGateway.budgetFor(KotakConfig.live("member-key", sim.baseUri(), null)))
                .as("LIVE against a simulator: its own").isNotSameAs(a)
                .isNotSameAs(KotakGateway.budgetFor(KotakConfig.live("member-key", sim.baseUri(), null)));
        assertThat(KotakGateway.budgetFor(KotakConfig.session("member-key", real))).isNotSameAs(a);
        assertThat(KotakGateway.budgetFor(KotakConfig.simulator(sim.baseUri())))
                .isNotSameAs(KotakGateway.budgetFor(KotakConfig.simulator(sim.baseUri())));
    }

    /** The gateway charges its budget: once the minute is full a buy is refused with nothing sent; a sell still goes. */
    @Test
    void aFullMinuteRefusesBuysButNeverExits() {
        KotakGateway gateway = gateway(KotakConfig.simulator(sim.baseUri()));
        gateway.place(buy("F1E1", 65, 100.2));
        for (int i = 1; i < KotakGateway.ORDERS_PER_MINUTE; i++) {
            gateway.budget().count();                                   // the rest of the minute's calls
        }
        int placed = sim.requests("place");
        assertThatThrownBy(() -> gateway.place(buy("F2E1", 65, 100.2))).isInstanceOf(OrderBudget.Exceeded.class)
                .satisfies(e -> assertThat(((BrokerException) e).kind()).isEqualTo(Kind.RETRYABLE));
        assertThat(sim.requests("place")).as("nothing sent").isEqualTo(placed);
        gateway.place(new OrderRequest("F1X1", 3000047168L, KEY, "N", "NSE_FO", OrderSide.SELL, OrderType.LIMIT, 65, 99.5,
                0, "F1X1"));
        assertThat(sim.requests("place")).as("an exit is never held by our cap").isEqualTo(placed + 1);
    }

    /** Production gaps B1: the client binds to the config's egress address; one this host lacks cannot connect at all. */
    @Test
    void theClientLeavesFromTheEgressAddressAndNeverFallsBackToTheDefaultRoute() throws Exception {
        KotakConfig bound = KotakConfig.session("k", sim.baseUri(), InetAddress.getByName("127.0.0.1"));
        KotakHttp ok = new KotakHttp(bound);
        ok.session("any-token", "any-sid");
        assertThat(new KotakGateway(bound, ok, instruments, sim::book).orders()).isEmpty();
        KotakConfig missing = KotakConfig.session("k", sim.baseUri(), InetAddress.getByName("192.0.2.77"));
        KotakHttp nowhere = new KotakHttp(missing);
        nowhere.session("any-token", "any-sid");
        int reads = sim.requests();
        assertThatThrownBy(() -> new KotakGateway(missing, nowhere, instruments, sim::book).orders())
                .isInstanceOf(BrokerException.class);
        assertThat(sim.requests()).as("never sent from the default route instead").isEqualTo(reads);
    }
}
