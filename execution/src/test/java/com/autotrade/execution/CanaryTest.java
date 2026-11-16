package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.autotrade.broker.BrokerCapabilities;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderStatus;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.broker.Quote;
import com.autotrade.groww.GrowwConfig;
import com.autotrade.groww.GrowwGateway;
import com.autotrade.groww.GrowwHttp;
import com.autotrade.groww.GrowwInstruments;
import com.autotrade.groww.GrowwSimulator;
import com.autotrade.kotak.KotakConfig;
import com.autotrade.kotak.KotakGateway;
import com.autotrade.kotak.KotakHttp;
import com.autotrade.kotak.KotakInstruments;
import com.autotrade.kotak.KotakSimulator;
import com.autotrade.upstox.orders.UpstoxConfig;
import com.autotrade.upstox.orders.UpstoxGateway;
import com.autotrade.upstox.orders.UpstoxHttp;
import com.autotrade.upstox.orders.UpstoxInstruments;
import com.autotrade.upstox.orders.UpstoxSimulator;
import com.autotrade.zerodha.KiteConfig;
import com.autotrade.zerodha.KiteGateway;
import com.autotrade.zerodha.KiteHttp;
import com.autotrade.zerodha.KiteInstruments;
import com.autotrade.zerodha.KiteSimulator;

/**
 * The morning canary (16 Nov 2026, D1) against each broker's simulator standing for the real broker, the connection built
 * directly (the broker's own gateway over HTTP) and wrapped in a {@link LiveGuard} as the adapter's LIVE connection is:
 * the pass path (placed far below the market, read back by its tag, modified, cancelled, nothing filled), a fill during
 * the canary (sold back at once, FAIL), the broker's refusal of the buy, a session lost in the middle (the order is
 * reported as left working), and the refusals before anything is sent.
 */
class CanaryTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String KEY = "NSE_FO|47168";
    private static final int LOT = 65;
    private static final LocalDate TODAY = LocalDate.now(IST);
    private static final LocalDate EXPIRY = TODAY.plusDays(3);
    private static final Canary.Settings FAST = new Canary.Settings(Duration.ofMillis(20), 15, Duration.ofMinutes(2));
    private static final FollowerRisk.LiveLimits LIMITS = new FollowerRisk.LiveLimits(1, 2, Set.of("canary"));

    enum Broker { ZERODHA, KOTAK_NEO, UPSTOX, GROWW }

    /** Quotes the simulator's book of a contract. */
    interface Book {
        void quote(String key, double bid, long bidQuantity, double ask, long askQuantity, double last);
    }

    /** One broker's simulator (the "real broker") and its own gateway to it. */
    record Venue(String name, BrokerConnection gateway, Book book, Consumer<String> failPlace, Runnable expireSession,
                 ToIntFunction<String> requests, java.util.function.Supplier<Map<String, Long>> net,
                 java.util.function.BiConsumer<String, Long> holding, AutoCloseable sim) {
    }

    private Venue venue;

    @AfterEach
    void close() throws Exception {
        if (venue != null) {
            venue.sim().close();
        }
    }

    private static Venue open(Broker b) throws Exception {
        return switch (b) {
            case ZERODHA -> {
                KiteSimulator sim = new KiteSimulator(0, "sim-api-key", "sim-secret", 1_500);
                sim.list(new KiteSimulator.Listing(47168, "NFO", "NIFTY26N2023100PE", "NIFTY", EXPIRY.toString(), 23100,
                        "PE", LOT, 0.05));
                KiteConfig config = KiteConfig.simulator(sim.baseUri());
                KiteHttp http = new KiteHttp(config);
                KiteInstruments instruments = KiteInstruments.load(http);
                http.accessToken("day-token");
                yield new Venue("ZERODHA", new KiteGateway(config, http, instruments), sim::quote,
                        op -> sim.failNext(op, KiteSimulator.Fault.INPUT_ERROR), sim::expireToken, sim::requests,
                        sim::netQuantities, (k, q) -> sim.holding(k, q, 50), sim);
            }
            case KOTAK_NEO -> {
                KotakSimulator sim = new KotakSimulator(0, "sim-consumer-key", 1_500);
                sim.list(new KotakSimulator.Listing(47168, "nse_fo", "NIFTY26N2023100PE", "NIFTY", EXPIRY, 23100, "PE",
                        LOT, 0.05));
                KotakConfig config = KotakConfig.simulator(sim.baseUri());
                KotakHttp http = new KotakHttp(config);
                http.session("day-token", "day-sid");
                KotakInstruments instruments = KotakInstruments.load(http);
                yield new Venue("KOTAK_NEO", new KotakGateway(config, http, instruments, sim::book), sim::quote,
                        op -> sim.failNext(op, KotakSimulator.Fault.INPUT_ERROR), sim::expireSession, sim::requests,
                        sim::netQuantities, (k, q) -> sim.holding(k, q, 50), () -> {
                            http.close();
                            sim.close();
                        });
            }
            case UPSTOX -> {
                UpstoxSimulator sim = new UpstoxSimulator(0, 1_500);
                sim.list(new UpstoxSimulator.Listing(KEY, "NIFTY 23100 PE 20 NOV 26", "NIFTY", EXPIRY, 23100, "PE", LOT,
                        0.05));
                UpstoxConfig config = UpstoxConfig.simulator(sim.baseUri());
                UpstoxHttp http = new UpstoxHttp(config);
                UpstoxInstruments instruments = UpstoxInstruments.load(http, TODAY);
                http.session("day-token");
                yield new Venue("UPSTOX", new UpstoxGateway(config, http, instruments, sim::book), sim::quote,
                        op -> sim.failNext(op, UpstoxSimulator.Fault.INPUT_ERROR), sim::expireSession, sim::requests,
                        sim::netQuantities, (k, q) -> sim.holding(k, q, 50), () -> {
                            http.close();
                            sim.close();
                        });
            }
            case GROWW -> {
                GrowwSimulator sim = new GrowwSimulator(0, 1_500);
                sim.list(new GrowwSimulator.Listing(KEY, "NIFTY2611723100PE", "NIFTY", EXPIRY, 23100, "PE", LOT, 0.05));
                GrowwConfig config = GrowwConfig.simulator(sim.baseUri());
                GrowwHttp http = new GrowwHttp(config);
                GrowwInstruments instruments = GrowwInstruments.load(http);
                http.session("day-token");
                yield new Venue("GROWW", new GrowwGateway(config, http, instruments, sim::book), sim::quote,
                        op -> sim.failNext(op, GrowwSimulator.Fault.INPUT_ERROR), sim::expireSession, sim::requests,
                        sim::netQuantities, (k, q) -> sim.holding(k, q, 50), () -> {
                            http.close();
                            sim.close();
                        });
            }
        };
    }

    private Venue start(Broker b) throws Exception {
        venue = open(b);
        venue.book().quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
        return venue;
    }

    private static Canary.Contract contract() {
        return new Canary.Contract(KEY, "NIFTY 23100 PE", LOT, 0.05);
    }

    /** The LIVE connection as the adapter gives it: the broker's gateway behind its guard (one lot, two contracts). */
    private static LiveGuard live(BrokerConnection gateway) {
        return new LiveGuard(gateway, LIMITS, k -> KEY.equals(k) ? LOT : 0);
    }

    private static final class Recorded implements Canary.Journal {
        final List<Canary.Step> steps = new ArrayList<>();
        final List<Canary.Report> results = new ArrayList<>();

        @Override
        public void step(Canary.Step step) {
            steps.add(step);
        }

        @Override
        public void result(Canary.Report report) {
            results.add(report);
        }
    }

    private static List<String> names(Canary.Report r) {
        return r.steps().stream().map(Canary.Step::name).toList();
    }

    private static Canary.Step step(Canary.Report r, String name) {
        return r.steps().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow(() ->
                new AssertionError("no step " + name + " in " + names(r)));
    }

    private static long net(Venue v) {
        return v.net().get().values().stream().mapToLong(Long::longValue).sum();
    }

    @ParameterizedTest
    @EnumSource(Broker.class)
    void passesPlacingFarBelowTheMarketReadingBackByTagModifyingAndCancelling(Broker b) throws Exception {
        Venue v = start(b);
        Recorded journal = new Recorded();
        Canary.Report r = new Canary(live(v.gateway()), contract(), FAST, journal, Clock.systemUTC()).run();

        assertThat(r.result()).as(r.message()).isEqualTo(Canary.Result.PASS);
        assertThat(names(r)).containsExactly("FUNDS", "BOOK_CHECK", "QUOTE", "PLACE", "READ_BY_TAG", "MODIFY",
                "CONFIRM_MODIFY", "CANCEL", "CONFIRM_CANCELLED", "NO_FILL");
        assertThat(r.steps()).allMatch(Canary.Step::ok);
        assertThat(r.buyPrice()).as("max(tick, bid x 0.5) on the tick").isEqualTo(49.75);
        assertThat(r.modifiedPrice()).as("still far below the market").isEqualTo(44.75);
        assertThat(r.tag()).matches("F0C\\d{15}").matches(Canary.OUR_TAG.pattern());
        assertThat(step(r, "READ_BY_TAG").rawStatus()).as("the broker's own word").isNotBlank();
        assertThat(step(r, "CONFIRM_CANCELLED").status()).isEqualTo("CANCELLED");
        assertThat(step(r, "CONFIRM_CANCELLED").rawStatus()).isNotBlank();
        assertThat(r.filledQuantity()).isZero();
        assertThat(r.leftWorking()).isFalse();
        assertThat(v.requests().applyAsInt("place")).isEqualTo(1);
        assertThat(v.requests().applyAsInt("modify")).isEqualTo(1);
        assertThat(v.requests().applyAsInt("cancel")).isEqualTo(1);
        assertThat(net(v)).as("nothing filled at the broker").isZero();
        BrokerOrder o = v.gateway().orderByTag(r.tag()).orElseThrow();
        assertThat(o.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(o.limitPrice()).isEqualTo(44.75);
        assertThat(journal.steps).hasSize(r.steps().size());
        assertThat(journal.results).singleElement().isEqualTo(r);
    }

    @ParameterizedTest
    @EnumSource(Broker.class)
    void aFillDuringTheCanaryIsSoldBackAtOnceAndFails(Broker b) throws Exception {
        Venue v = start(b);
        // the market falls onto the canary's price just after the place: the buy fills (a canary must never fill)
        Hooked hooked = new Hooked(v.gateway());
        hooked.afterPlace = r -> {
            if (r.side() == OrderSide.BUY) {
                v.book().quote(KEY, r.limitPrice() - 0.05, 6500, r.limitPrice(), 6500, r.limitPrice());
            }
        };
        Canary.Report r = new Canary(live(hooked), contract(), FAST, null, Clock.systemUTC()).run();

        assertThat(r.result()).isEqualTo(Canary.Result.FAIL);
        assertThat(r.filledQuantity()).isEqualTo(LOT);
        assertThat(r.soldBack()).as(r.message()).isTrue();
        assertThat(r.message()).startsWith("FILLED 65 of " + r.tag()).contains("sold back");
        assertThat(names(r)).contains("SELL_BACK", "SELL_BACK_FILLED").doesNotContain("MODIFY");
        BrokerOrder sell = v.gateway().orderByTag(r.tag() + "X").orElseThrow();
        assertThat(sell.side()).isEqualTo(OrderSide.SELL);
        assertThat(sell.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(net(v)).as("flat again at the broker").isZero();
    }

    @ParameterizedTest
    @EnumSource(Broker.class)
    void theBrokersRefusalOfTheBuyFailsAndLeavesNothing(Broker b) throws Exception {
        Venue v = start(b);
        v.failPlace().accept("place");
        Canary.Report r = new Canary(live(v.gateway()), contract(), FAST, null, Clock.systemUTC()).run();

        assertThat(r.result()).isEqualTo(Canary.Result.FAIL);
        assertThat(r.message()).contains("TERMINAL");
        assertThat(step(r, "PLACE").ok()).isFalse();
        assertThat(names(r)).contains("SECURE_LOOKUP").doesNotContain("MODIFY", "SECURE_CANCEL");
        assertThat(r.leftWorking()).isFalse();
        assertThat(r.filledQuantity()).isZero();
        assertThat(v.gateway().orders()).noneMatch(o -> !o.status().isTerminal());
    }

    @ParameterizedTest
    @EnumSource(Broker.class)
    void aSessionLostMidRunIsReportedWithTheOrderLeftWorking(Broker b) throws Exception {
        Venue v = start(b);
        Hooked hooked = new Hooked(v.gateway());
        hooked.afterModify = v.expireSession();
        Canary.Report r = new Canary(live(hooked), contract(), FAST, null, Clock.systemUTC()).run();

        assertThat(r.result()).isEqualTo(Canary.Result.FAIL);
        assertThat(r.message()).contains("SESSION").contains("may still be working at the broker: cancel it by hand");
        assertThat(r.leftWorking()).isTrue();
        assertThat(step(r, "CONFIRM_MODIFY").ok()).isFalse();
        assertThat(v.requests().applyAsInt("cancel")).as("no cancel could be sent").isZero();
    }

    @ParameterizedTest
    @EnumSource(Broker.class)
    void refusesBeforeSendingAnything(Broker b) throws Exception {
        Venue v = start(b);
        // no two-sided quote
        v.book().quote(KEY, 0, 0, 100.0, 6500, 99.8);
        assertThat(refused(v)).contains("no two-sided quote");
        // a book so low that the canary's prices could not stay below the bid and the ask
        v.book().quote(KEY, 0.05, 6500, 0.10, 6500, 0.05);
        assertThat(refused(v)).contains("would not stay below");
        v.book().quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
        // a working order of ours in the contract
        live(v.gateway()).place(new OrderRequest("F9E1", 3000047168L, KEY, "NIFTY 23100 PE", "NSE_FO", OrderSide.BUY,
                OrderType.LIMIT, LOT, 10.0, 0, "F9E1"));
        assertThat(refused(v)).contains("orders of ours still working").contains("F9E1");
        v.gateway().cancel("F9E1");
        // on a scheduler thread, never
        AtomicReference<Canary.Report> scheduled = new AtomicReference<>();
        Thread t = new Thread(() -> scheduled.set(new Canary(live(v.gateway()), contract(), FAST, null,
                Clock.systemUTC()).run()), "scheduling-1");
        t.start();
        t.join();
        assertThat(scheduled.get().result()).isEqualTo(Canary.Result.REFUSED);
        assertThat(scheduled.get().steps()).isEmpty();
        // a position held in the contract
        v.holding().accept(KEY, (long) LOT);
        assertThat(refused(v)).contains("holds 65");
        assertThat(v.requests().applyAsInt("place")).as("only the test's own F9E1").isEqualTo(1);
        assertThat(v.requests().applyAsInt("modify")).isZero();
    }

    private String refused(Venue v) {
        Canary.Report r = new Canary(live(v.gateway()), contract(), FAST, null, Clock.systemUTC()).run();
        assertThat(r.result()).as(r.message()).isEqualTo(Canary.Result.REFUSED);
        assertThat(r.tag()).as("nothing placed").isNull();
        return r.message();
    }

    /** The broker's gateway with a hook after a place or a modify (the market moving, the session ending). */
    private static final class Hooked implements BrokerConnection {
        final BrokerConnection g;
        Consumer<OrderRequest> afterPlace = r -> { };
        Runnable afterModify = () -> { };

        Hooked(BrokerConnection g) {
            this.g = g;
        }

        @Override public String name() { return g.name(); }
        @Override public BrokerCapabilities capabilities() { return g.capabilities(); }
        @Override public void onUpdate(Consumer<OrderUpdate> l) { g.onUpdate(l); }
        @Override public String product() { return g.product(); }
        @Override public double availableFunds() { return g.availableFunds(); }
        @Override public Quote quote(String k) { return g.quote(k); }
        @Override public List<BrokerOrder> orders() { return g.orders(); }
        @Override public java.util.Optional<BrokerOrder> orderByTag(String t) { return g.orderByTag(t); }
        @Override public List<BrokerTrade> trades() { return g.trades(); }
        @Override public List<BrokerPosition> positions() { return g.positions(); }
        @Override public int poll() { return g.poll(); }
        @Override public void cancel(String t) { g.cancel(t); }

        @Override
        public void place(OrderRequest r) {
            g.place(r);
            afterPlace.accept(r);
        }

        @Override
        public void modify(String t, long q, double l, double tr) {
            g.modify(t, q, l, tr);
            afterModify.run();
        }
    }
}
