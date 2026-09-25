package com.autotrade.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.paper.PaperBroker;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.time.MarketTime;
import com.autotrade.risk.KillSwitch;
import com.autotrade.risk.RiskEngine;
import com.autotrade.risk.RiskLimits;
import com.autotrade.sim.CostModel;
import com.autotrade.strategy.OptionSide;

class OrderManagerTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 9, 28);
    private static final RiskLimits LIMITS =
            RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v2.yaml")));
    private static final CostModel COSTS =
            CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs", "india-index-options-costs.v1.yaml")));
    private static final Contract CE = new Contract(7, "NSE_FO|7", "NIFTY 23100 CE", "NSE", 65, 0.05, 27);

    private final AtomicReference<Instant> clock = new AtomicReference<>(at("10:00:00"));
    private final List<OrderRequest> sent = new ArrayList<>();
    private final List<String> alerts = new ArrayList<>();
    private KillSwitch killSwitch;
    private PaperBroker broker;
    private OrderManager oms;

    @BeforeEach
    void setUp() {
        killSwitch = new KillSwitch((scope, engagement) -> {
        });
        broker = new PaperBroker("paper", Duration.ofMillis(250), 0, clock::get);
        oms = new OrderManager("P1", "P1-S1", "ecr", SESSION, broker, new RiskEngine(LIMITS, killSwitch), COSTS, 25,
                clock::get, new OmsListener() {
                    @Override
                    public void orderSent(ManagedPosition position, String role, OrderRequest request) {
                        sent.add(request);
                    }

                    @Override
                    public void alert(String message) {
                        alerts.add(message);
                    }
                });
    }

    @Test
    void entryFillsThenARestingStopProtectsItAndFiresOnLtp() {
        quote("10:00:00", 99.5, 100, 100);
        assertThat(oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00")).approved()).isTrue();
        OrderRequest entry = sent.getFirst();
        assertThat(entry.type()).isEqualTo(OrderType.LIMIT);
        assertThat(entry.limitPrice()).isEqualTo(100.20); // ask + 4 ticks

        quote("10:00:01", 99.5, 100, 100);
        ManagedPosition position = oms.livePositions().getFirst();
        assertThat(position.quantity()).isEqualTo(65);
        assertThat(position.averageCost()).isEqualTo(100);
        OrderRequest stop = sent.get(1);
        assertThat(stop.type()).isEqualTo(OrderType.STOP_LIMIT);
        assertThat(stop.triggerPrice()).isEqualTo(75.0);
        assertThat(stop.limitPrice()).isEqualTo(71.25);

        quote("10:04:00", 80.0, 80.5, 80.2); // above the trigger: nothing happens
        assertThat(oms.livePositions()).hasSize(1);
        quote("10:05:00", 74.9, 75.2, 74.9); // LTP through the trigger; the resting stop fills on this quote
        assertThat(oms.livePositions()).isEmpty();
        ManagedPosition done = oms.closedPositions().getFirst();
        assertThat(done.exitReason()).isEqualTo("PREMIUM_STOP");
        assertThat(done.realised()).isCloseTo((74.9 - 100) * 65.0, within(1e-9));
        assertThat(oms.reconcile()).isTrue();
    }

    @Test
    void anAddResizesTheSingleStopInPlace() {
        quote("10:00:00", 99.5, 100, 100);
        oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"));
        quote("10:00:01", 99.5, 100, 100);
        quote("10:03:00", 109.5, 110, 110);
        oms.add("NIFTY", 2, "CONFIRMED", market("10:03"));
        quote("10:03:01", 109.5, 110, 110);

        ManagedPosition position = oms.livePositions().getFirst();
        assertThat(position.quantity()).isEqualTo(195);
        assertThat(position.averageCost()).isCloseTo((100 * 65 + 110 * 130) / 195.0, within(1e-9));
        long stops = sent.stream().filter(r -> r.type() == OrderType.STOP_LIMIT).count();
        assertThat(stops).isEqualTo(1); // modified, not replaced
        assertThat(broker.positions().getFirst().netQuantity()).isEqualTo(195);
    }

    @Test
    void exitCancelsTheStopBeforeSellingAndNeverOversells() {
        quote("10:00:00", 99.5, 100, 100);
        oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"));
        quote("10:00:01", 99.5, 100, 100);

        oms.exit("NIFTY", "INVALIDATED");
        assertThat(sent.stream().filter(r -> r.clientOrderId().endsWith("X"))).isEmpty(); // waits for the cancel
        assertThat(sent.getFirst().clientOrderId()).isEqualTo("P1-S1-260928-NIFTY-CE-1B");
        quote("10:00:02", 101.5, 102, 102); // cancel lands; exit sell placed
        quote("10:00:03", 101.5, 102, 102); // exit fills at bid
        assertThat(oms.livePositions()).isEmpty();
        assertThat(oms.closedPositions().getFirst().exitReason()).isEqualTo("INVALIDATED");
        assertThat(broker.positions().getFirst().netQuantity()).isZero();
        assertThat(oms.reconcile()).isTrue();
    }

    @Test
    void anUnfilledEntryIsCancelledAfterTheTimeout() {
        quote("10:00:00", 99.5, 100, 100);
        oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"));
        quote("10:00:01", 101.5, 103, 102); // ran away above the limit
        quote("10:00:11", 101.5, 103, 102); // timeout: cancel sent
        quote("10:00:12", 101.5, 103, 102); // cancel lands

        assertThat(oms.livePositions()).isEmpty();
        assertThat(oms.closedPositions().getFirst().exitReason()).isEqualTo("ENTRY_NOT_FILLED");
    }

    @Test
    void slicesAboveTheFreezeQuantity() {
        quote("10:00:00", 99.5, 100, 100);
        oms.enter("NIFTY", OptionSide.CE, new Contract(7, "NSE_FO|7", "NIFTY 23100 CE", "NSE", 65, 0.05, 2), 3, "EARLY",
                market("10:00"));

        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).quantity()).isEqualTo(130);
        assertThat(sent.get(1).quantity()).isEqualTo(65);
    }

    @Test
    void riskBlocksLateEntriesAndKillSwitchButNeverExits() {
        quote("10:00:00", 99.5, 100, 100);
        assertThat(oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("14:50")).reason()).contains("after 14:45");
        assertThat(oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY",
                new MarketContext(LocalTime.of(10, 0), 30, 0)).reason()).contains("stale feed");

        oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"));
        quote("10:00:01", 99.5, 100, 100);
        killSwitch.engage("GLOBAL", "manual", clock.get(), "test");
        assertThat(oms.add("NIFTY", 1, "CONFIRMED", market("10:01")).reason()).contains("kill switch GLOBAL");
        oms.exit("NIFTY", "KILL");
        quote("10:00:02", 101.5, 102, 102);
        quote("10:00:03", 101.5, 102, 102);
        assertThat(oms.livePositions()).isEmpty();
    }

    @Test
    void reconciliationMismatchEngagesTheGlobalKillSwitch() {
        quote("10:00:00", 99.5, 100, 100);
        oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"));
        quote("10:00:01", 99.5, 100, 100);
        // someone sells at the broker outside the OMS
        broker.place(new OrderRequest("manual-1", 7, "NSE_FO|7", "NIFTY 23100 CE", "NSE",
                OrderSide.SELL, OrderType.LIMIT, 65, 90, 0, "manual"));
        quote("10:00:02", 99.5, 100, 100);

        assertThat(oms.reconcile()).isFalse();
        assertThat(killSwitch.engaged()).containsKey("GLOBAL");
        assertThat(alerts).singleElement().asString().contains("reconciliation mismatch");
    }

    private void quote(String time, double bid, double ask, double ltp) {
        Instant at = at(time);
        clock.set(at);
        OptionTick tick = new OptionTick(at, at, "NIFTY", 1, 7, "NIFTY 23100 CE", LocalDate.of(2026, 9, 29), 23100,
                OptionTick.OptionType.CE, 65, ltp, 0L, 0.0, 0.0, 0.0,
                DepthLevels.of(new double[] {bid}, new long[] {10_000}, new int[] {1}),
                DepthLevels.of(new double[] {ask}, new long[] {10_000}, new int[] {1}),
                0.1, 0.5, 0.001, -10.0, 12.0, 1.0, at, true, true);
        oms.observe(tick);
        broker.accept(tick);
    }

    private static MarketContext market(String time) {
        return new MarketContext(LocalTime.parse(time), 0.2, 0.2);
    }

    private static Instant at(String time) {
        return SESSION.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant();
    }
}
