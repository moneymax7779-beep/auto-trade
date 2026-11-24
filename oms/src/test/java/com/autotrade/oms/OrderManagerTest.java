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
    /** The quantity held each time the OMS said an entry was complete. */
    private final List<Long> entriesComplete = new ArrayList<>();
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

    /** The admin exit desk's engine exit (23 Nov 2026): held while entering or open, not once an exit started or closed. */
    @Test
    void holdsIsTrueOnlyUntilAnExitStarts() {
        quote("10:00:00", 99.5, 100, 100);
        assertThat(oms.holds("ecr", "NIFTY")).isFalse();
        assertThat(oms.enter("ecr", "NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"), Double.NaN).approved()).isTrue();
        assertThat(oms.holds("ecr", "NIFTY")).as("entering").isTrue();
        quote("10:00:01", 99.5, 100, 100);
        assertThat(oms.holds("ecr", "NIFTY")).as("open").isTrue();
        assertThat(oms.holds("egb", "NIFTY")).as("another strategy holds nothing here").isFalse();
        oms.exit("ecr", "NIFTY", "ADMIN_EXIT");
        assertThat(oms.holds("ecr", "NIFTY")).as("its exit started: not exited twice").isFalse();
        quote("10:02:00", 99.5, 100, 100);
        quote("10:02:01", 99.5, 100, 100);
        assertThat(oms.holds("ecr", "NIFTY")).isFalse();
        assertThat(oms.closedPositions()).singleElement().extracting(ManagedPosition::exitReason).isEqualTo("ADMIN_EXIT");
    }

    @Test
    void twoStrategiesHoldTheSameUnderlyingIndependently() {
        quote("10:00:00", 99.5, 100, 100);
        assertThat(oms.enter("ecr", "NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"), Double.NaN).approved())
                .isTrue();
        assertThat(oms.enter("egb", "NIFTY", OptionSide.CE, CE, 2, "EARLY", market("10:00"), 30).approved()).isTrue();
        assertThat(oms.enter("egb", "NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"), 30).approved())
                .as("one position per strategy and underlying").isFalse();
        quote("10:00:01", 99.5, 100, 100);

        assertThat(oms.view("ecr", "NIFTY").lots()).isEqualTo(1);
        assertThat(oms.view("egb", "NIFTY").lots()).isEqualTo(2);
        assertThat(oms.view("other", "NIFTY").open()).isFalse();
        assertThat(sent).extracting(OrderRequest::tag).contains("ecr", "egb");
        assertThat(sent).extracting(OrderRequest::clientOrderId).doesNotHaveDuplicates();

        // the per-underlying lot limit (4) counts both strategies: 1 + 2 held, 2 more would be 5
        assertThat(oms.add("egb", "NIFTY", 2, "CONFIRMED", market("10:01")).reason())
                .contains("max lots per underlying");
        assertThat(oms.add("egb", "NIFTY", 1, "CONFIRMED", market("10:01")).approved()).isTrue();

        oms.exit("ecr", "NIFTY", "TEST");
        quote("10:02:00", 99.5, 100, 100);
        quote("10:02:01", 99.5, 100, 100);
        assertThat(oms.view("ecr", "NIFTY").open()).isFalse();
        assertThat(oms.view("egb", "NIFTY").open()).isTrue();
        assertThat(oms.closedPositions()).singleElement().extracting(ManagedPosition::strategy).isEqualTo("ecr");
        assertThat(oms.reconcile()).isTrue();
    }

    @Test
    void anEntryCanCarryItsOwnPremiumStop() {
        quote("10:00:00", 99.5, 100, 100);
        assertThat(oms.enter("NIFTY", OptionSide.CE, CE, 1, "CONFIRMED", market("10:00"), 35).approved()).isTrue();
        quote("10:00:01", 99.5, 100, 100);
        OrderRequest stop = sent.get(1);
        assertThat(stop.type()).isEqualTo(OrderType.STOP_LIMIT);
        assertThat(stop.triggerPrice()).isEqualTo(65.0); // 35% below 100, not the default 25%
    }

    @Test
    void riskV3AllowsEntriesInTheClosingAuctionWindowOnly() {
        RiskLimits v3 = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v3.yaml")));
        RiskEngine risk = new RiskEngine(v3, killSwitch);
        assertThat(v3.squareOffAt()).isEqualTo(LocalTime.of(15, 36));
        assertThat(risk.checkEntry(check("15:00"), clock.get()).reason()).contains("after 14:45");
        assertThat(risk.checkEntry(check("15:22"), clock.get()).approved()).isTrue();
        assertThat(risk.checkEntry(check("15:30"), clock.get()).approved()).isFalse();
        assertThat(LIMITS.inCasEntryWindow(LocalTime.of(15, 22))).as("v2 has no CAS window").isFalse();
    }

    private static com.autotrade.risk.RiskCheck check(String time) {
        return new com.autotrade.risk.RiskCheck("P1", "ecr", "NIFTY", false, 1, 0, 0, 0, 0.2, 0.2, 0.3,
                LocalTime.parse(time), 0);
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
    void pyramidProtectionKeepsTheLossAtTheStopToWhatTheEntryRisked() {
        // 1 lot at 100 with the default 25 % stop risks 65 x 100 x 0.25 = 1,625; then 2 lots added at 110
        quote("10:00:00", 99.5, 100, 100);
        oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"));
        quote("10:00:01", 99.5, 100, 100);
        quote("10:03:00", 109.5, 110, 110);
        oms.capRiskAtFirstEntry("ecr", "NIFTY");
        oms.add("NIFTY", 2, "CONFIRMED", market("10:03"));
        quote("10:03:01", 109.5, 110, 110);
        oms.capRiskAtFirstEntry("ecr", "NIFTY");            // a second add keeps the first cap

        // average 106.67 on 195: stop at 106.67 - 1,625 / 195 = 98.33 (the plain 25 % stop would be 80.00)
        quote("10:06:00", 98.5, 99, 98.5);                  // above the capped trigger
        assertThat(oms.livePositions()).hasSize(1);
        quote("10:07:00", 98.0, 98.4, 98.0);                // through it
        assertThat(oms.livePositions()).isEmpty();
        ManagedPosition done = oms.closedPositions().getFirst();
        assertThat(done.exitReason()).isEqualTo("PREMIUM_STOP");
        assertThat(done.realised()).isCloseTo((98.0 - (100 * 65 + 110 * 130) / 195.0) * 195, within(1e-6));
        assertThat(-done.realised()).isLessThanOrEqualTo(1625 + 0.4 * 195);  // the cap, plus at most the gap to the fill
        assertThat(oms.reconcile()).isTrue();
    }

    @Test
    void withoutPyramidProtectionAnAddKeepsThePlainStop() {
        quote("10:00:00", 99.5, 100, 100);
        oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"));
        quote("10:00:01", 99.5, 100, 100);
        quote("10:03:00", 109.5, 110, 110);
        oms.add("NIFTY", 2, "CONFIRMED", market("10:03"));
        quote("10:03:01", 109.5, 110, 110);
        quote("10:07:00", 98.0, 98.4, 98.0);
        assertThat(oms.livePositions()).hasSize(1);         // the stop is 25 % below 106.67 = 80.00
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
        assertThat(oms.view("NIFTY").closedToday()).as("an entry that never filled is not a trade").isZero();
    }

    /** The signal account under {@code file}, on a paper broker filling as that file says. */
    private OrderManager signalSource(String file) {
        RiskLimits limits = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", file)));
        broker = new PaperBroker("signal", Duration.ofMillis(250), 0, clock::get, limits.entryFillsAtDecision());
        return new OrderManager("SIG", "SIG-S1", "ecr", SESSION, broker, new RiskEngine(limits, killSwitch), COSTS, 25,
                clock::get, new OmsListener() {
                    @Override
                    public void orderSent(ManagedPosition position, String role, OrderRequest request) {
                        sent.add(request);
                    }
                });
    }

    /** The option runs above the entry's limit at once and stays there past the 10 s entry timeout. */
    private void optionRunsAway() {
        quote("10:00:00", 99.5, 100, 100);
        assertThat(oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00")).approved()).isTrue();
        for (String t : List.of("10:00:01", "10:00:06", "10:00:11", "10:00:12", "10:00:13")) {
            quote(t, 101.5, 103, 102);
        }
    }

    /**
     * 24 Nov 2026: under signal-source v2 the signal account's own entry could fill nothing (ENTRY_NOT_FILLED) on a fast
     * option, and that close reached every follower as an EXIT, selling LIVE fills seconds after they were bought.
     */
    @Test
    void signalSourceV2LosesAnEntryWhoseOptionRunsAway() {
        oms = signalSource("signal-source.v2.yaml");
        optionRunsAway();
        assertThat(oms.livePositions()).isEmpty();
        assertThat(oms.closedPositions()).singleElement()
                .satisfies(p -> assertThat(p.exitReason()).isEqualTo("ENTRY_NOT_FILLED"));
    }

    /** signal-source v3: the same entry fills at its limit (the decision's ask + 4 ticks) and is protected by its stop. */
    @Test
    void signalSourceV3FillsTheSameEntryAtItsDecisionLimit() {
        oms = signalSource("signal-source.v3.yaml");
        optionRunsAway();
        assertThat(oms.closedPositions()).isEmpty();
        assertThat(oms.livePositions()).singleElement().satisfies(p -> {
            assertThat(p.quantity()).isEqualTo(65);
            assertThat(p.averageCost()).isEqualTo(100.20);
        });
        assertThat(sent).filteredOn(r -> r.type() == OrderType.STOP_LIMIT).hasSize(1);
        assertThat(oms.reconcile()).isTrue();
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

    @Test
    void riskV11PricesEntriesWithAPercentBufferAndRepricesOnceWhenTheAskRunsAway() {
        RiskLimits v11 = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v11.yaml")));
        assertThat(v11.entryBuffer(300, 0.05)).isCloseTo(0.9, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(v11.entryBuffer(20, 0.05)).as("4 ticks when larger").isCloseTo(0.2, org.assertj.core.api.Assertions.within(1e-9));
        oms = new OrderManager("P1", "P1-S1", "ecr", SESSION, broker, new RiskEngine(v11, killSwitch), COSTS, 25, clock::get,
                new OmsListener() {
                    @Override
                    public void orderSent(ManagedPosition position, String role, OrderRequest request) {
                        sent.add(request);
                    }

                    @Override
                    public void entryComplete(ManagedPosition position) {
                        entriesComplete.add(position.quantity());
                    }

                    @Override
                    public void alert(String message) {
                        alerts.add(message);
                    }
                });
        quote("10:00:00", 99.5, 100, 100);
        oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"));
        assertThat(sent.getFirst().limitPrice()).isEqualTo(100.30);            // 100 + 0.3 %
        quote("10:00:01", 101.0, 101.5, 101.2);                                // ran 1.5 % away
        quote("10:00:02", 101.0, 101.5, 101.2);
        oms.onTimer(at("10:00:02"));                                          // 2 s: re-price at 101.5 + 0.3 %
        quote("10:00:03", 101.0, 101.5, 101.2);                                // the modify lands and fills
        assertThat(oms.livePositions()).singleElement()
                .satisfies(p -> assertThat(p.averageCost).isCloseTo(101.5, org.assertj.core.api.Assertions.within(0.01)));
    }

    /** A-612 (risk v49): an entry is one limit at the mid, fills only when the ask comes down to it, cancelled after 60 s. */
    @Test
    void riskV49EntersAtTheMidAndCancelsWhatIsUnfilledAfterTheWait() {
        RiskLimits v49 = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v49.yaml")));
        assertThat(v49.entryAtMid()).isTrue();
        assertThat(v49.entryMidWaitSec()).isEqualTo(60);
        assertThat(RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v48.yaml"))).entryAtMid())
                .as("off before v49").isFalse();
        java.util.function.Supplier<OrderManager> make = () -> new OrderManager("P1", "P1-S1", "ecr", SESSION, broker,
                new RiskEngine(v49, killSwitch), COSTS, 25, clock::get, new OmsListener() {
                    @Override
                    public void orderSent(ManagedPosition position, String role, OrderRequest request) {
                        sent.add(request);
                    }

                    @Override
                    public void entryComplete(ManagedPosition position) {
                        entriesComplete.add(position.quantity());
                    }

                    @Override
                    public void alert(String message) {
                        alerts.add(message);
                    }
                });
        // fills: the ask comes down to the mid within the wait
        oms = make.get();
        quote("10:00:00", 99.6, 100.4, 100.0);            // spread 0.8 % (v44 refuses > 1.5 %)
        var r1 = oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"));
        assertThat(r1.approved()).as(String.valueOf(r1.reason())).isTrue();
        assertThat(sent).singleElement().satisfies(o -> assertThat(o.limitPrice()).isEqualTo(100.0));
        quote("10:00:01", 99.8, 100.4, 100.1);
        oms.onTimer(at("10:00:20"));
        assertThat(oms.livePositions()).singleElement().satisfies(p -> assertThat(p.boughtQuantity).isZero());
        quote("10:00:30", 99.6, 100.0, 100.0);
        quote("10:00:31", 99.6, 100.0, 100.0);
        assertThat(oms.livePositions()).singleElement().satisfies(p -> {
            assertThat(p.boughtQuantity).isEqualTo(65);
            assertThat(p.averageCost).isLessThanOrEqualTo(100.0);
        });
        oms.exit("NIFTY", "TEST");
        quote("10:00:32", 99.6, 100.0, 100.0);
        quote("10:00:33", 99.6, 100.0, 100.0);

        // never filled: cancelled after 60 s, the position closes as not filled
        sent.clear();
        quote("11:00:00", 99.6, 100.4, 100.0);
        oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("11:00"));
        assertThat(sent).singleElement().satisfies(o -> assertThat(o.limitPrice()).isEqualTo(100.0));
        quote("11:00:30", 100.6, 101.2, 100.9);
        oms.onTimer(at("11:00:30"));
        assertThat(oms.livePositions()).as("still waiting at 30 s").hasSize(1);
        oms.onTimer(at("11:01:01"));
        quote("11:01:02", 100.6, 101.2, 100.9);
        quote("11:01:03", 100.6, 101.2, 100.9);
        assertThat(oms.livePositions()).isEmpty();
        assertThat(sent).hasSize(1).as("no re-price");
    }

    /** A-615 (risk v52): a slice priced just before the option jumps is re-priced to the new ask and fills. */
    @Test
    void riskV52RepricesAWorkingSliceWhenTheAskRuns() {
        RiskLimits v52 = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v52.yaml")));
        assertThat(v52.entrySliceReprice()).isTrue();
        assertThat(v52.entrySliceMaxDriftPct()).isEqualTo(10.0);
        assertThat(v52.entryTimeoutSec()).isEqualTo(30);
        oms = new OrderManager("P1", "P1-S1", "ecr", SESSION, broker, new RiskEngine(v52, killSwitch), COSTS, 25, clock::get,
                new OmsListener() {
                    @Override
                    public void orderSent(ManagedPosition position, String role, OrderRequest request) {
                        sent.add(request);
                    }

                    @Override
                    public void entryComplete(ManagedPosition position) {
                        entriesComplete.add(position.quantity());
                    }

                    @Override
                    public void alert(String message) {
                        alerts.add(message);
                    }
                });
        quote("10:00:00", 99.8, 100.0, 100.0);
        var r = oms.enter("NIFTY", OptionSide.CE, CE, 1, "EARLY", market("10:00"));
        assertThat(r.approved()).as(String.valueOf(r.reason())).isTrue();
        double first = sent.getFirst().limitPrice();
        assertThat(first).isLessThan(101.0);
        quote("10:00:01", 103.8, 104.0, 104.0);              // the option jumps 4 % before the slice fills
        quote("10:00:02", 103.8, 104.0, 104.0);
        quote("10:00:03", 103.8, 104.0, 104.0);
        assertThat(oms.livePositions()).singleElement().satisfies(p -> {
            assertThat(p.boughtQuantity).isEqualTo(65);
            assertThat(p.averageCost).isBetween(104.0, 104.6);
        });
    }

    /** A-638 (risk v58): the MT2 pair stops entering once its own day P&L reaches -5 % of capital; others still enter. */
    @Test
    void riskV58StopsTheStrategyGroupAtItsOwnDailyLoss() {
        RiskLimits v58 = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v58.yaml")));
        assertThat(v58.strategyGroupLoss().strategies())
                .containsExactlyInAnyOrder("oi-burst-cross@obc-v31hm2", "oi-burst-cross@obc-v31lvoirh");
        assertThat(v58.strategyGroupLoss().pct()).isEqualTo(5.0);
        assertThat(RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v44.yaml"))).strategyGroupLoss())
                .as("off before v58").isNull();
        oms = new OrderManager("P1", "P1-S1", "ecr", SESSION, broker, new RiskEngine(v58, killSwitch), COSTS, 25, clock::get,
                new OmsListener() { });
        quote("10:00:00", 99.8, 100.0, 100.0);
        assertThat(oms.enter("oi-burst-cross@obc-v31hm2", "NIFTY", OptionSide.CE, CE, 10, "OBC", market("10:00"), Double.NaN)
                .approved()).isTrue();
        quote("10:00:01", 99.8, 100.0, 100.0);
        quote("10:05:00", 60.0, 60.2, 60.0);                 // 650 bought near 100, bid 60: about -26,000 open
        var group = oms.enter("oi-burst-cross@obc-v31lvoirh", "SENSEX", OptionSide.CE, CE, 1, "OBC", market("10:05"), Double.NaN);
        assertThat(group.approved()).isFalse();
        assertThat(group.reason()).startsWith("strategy group daily loss");
        assertThat(oms.enter("ma-cross", "SENSEX", OptionSide.CE, CE, 1, "MAC", market("10:05"), Double.NaN).approved())
                .as("outside the group").isTrue();
    }

    private OrderManager v12() {
        RiskLimits v12 = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v12.yaml")));
        return new OrderManager("P1", "P1-S1", "ecr", SESSION, broker, new RiskEngine(v12, killSwitch), COSTS, 25, clock::get,
                new OmsListener() {
                    @Override
                    public void orderSent(ManagedPosition position, String role, OrderRequest request) {
                        sent.add(request);
                    }

                    @Override
                    public void entryComplete(ManagedPosition position) {
                        entriesComplete.add(position.quantity());
                    }

                    @Override
                    public void alert(String message) {
                        alerts.add(message);
                    }
                });
    }

    private void book(String time, double[] askPrices, long[] askQty, long volume) {
        Instant at = at(time);
        clock.set(at);
        int[] orders = new int[askPrices.length];
        java.util.Arrays.fill(orders, 1);
        OptionTick tick = new OptionTick(at, at, "NIFTY", 1, 7, "NIFTY 23100 CE", LocalDate.of(2026, 9, 29), 23100,
                OptionTick.OptionType.CE, 65, askPrices[0], volume, 0.0, 0.0, 0.0,
                DepthLevels.of(new double[] {askPrices[0] - 0.5}, new long[] {10_000}, new int[] {1}),
                DepthLevels.of(askPrices, askQty, orders),
                0.1, 0.5, 0.001, -10.0, 12.0, 1.0, at, true, true);
        oms.observe(tick);
        broker.accept(tick);
    }

    @Test
    void riskV12SlicesAnEntryToTheVisibleBookAndBuysTheRestOnTheNextQuotes() {
        oms = v12();
        double[] prices = {100.00, 100.30, 101.00};
        long[] qty = {650, 650, 6500};                                       // within 0.5 % of 100: 1,300
        book("10:00:00", prices, qty, 0);
        oms.enter("NIFTY", OptionSide.CE, CE, 40, "EARLY", market("10:00"));    // 2,600 wanted
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().quantity()).isEqualTo(1300);
        assertThat(sent.getFirst().limitPrice()).isEqualTo(100.50);
        book("10:00:01", prices, qty, 0);                                     // the first slice fills
        book("10:00:02", prices, qty, 0);                                     // the next slice goes out
        assertThat(sent.stream().filter(r -> r.side() == com.autotrade.broker.OrderSide.BUY)).hasSize(2);
        book("10:00:03", prices, qty, 0);
        assertThat(oms.livePositions()).singleElement().satisfies(p -> assertThat(p.quantity).isEqualTo(2600));
        assertThat(entriesComplete).containsExactly(2600L);                  // once, after the last slice, not the first
    }

    @Test
    void riskV12StopsSlicingWhenThePriceRunsAndKeepsWhatItBought() {
        oms = v12();
        book("10:00:00", new double[] {100.00, 100.30}, new long[] {650, 650}, 0);
        oms.enter("NIFTY", OptionSide.CE, CE, 40, "EARLY", market("10:00"));
        book("10:00:01", new double[] {100.00, 100.30}, new long[] {650, 650}, 0);     // 1,300 filled
        book("10:00:02", new double[] {102.50, 102.60}, new long[] {650, 650}, 0);     // ran 2.5 %: stop
        book("10:00:03", new double[] {102.50, 102.60}, new long[] {650, 650}, 0);
        assertThat(sent.stream().filter(r -> r.side() == com.autotrade.broker.OrderSide.BUY)).hasSize(1);
        assertThat(oms.livePositions()).singleElement().satisfies(p -> assertThat(p.quantity).isEqualTo(1300));
        assertThat(entriesComplete).containsExactly(1300L);                  // complete when slicing gives up
    }

    @Test
    void riskV12CapsAnEntryAtTwoPercentOfTheLastMinutesTradedPremium() {
        oms = v12();
        double[] deep = {100.00};
        long[] lots = {100_000};
        book("09:59:00", deep, lots, 0);
        book("10:00:00", deep, lots, 100_000);                                // ₹1 Cr traded in the minute: 2 % = ₹2L
        oms.enter("NIFTY", OptionSide.CE, CE, 60, "EARLY", market("10:00"));    // 3,900 wanted; cap 1,950 (30 lots)
        long bought = sent.stream().filter(r -> r.side() == com.autotrade.broker.OrderSide.BUY).mapToLong(OrderRequest::quantity).sum();
        assertThat(bought).isEqualTo(1755);                                   // first slice: 27 lots (the freeze limit)
        book("10:00:01", deep, lots, 100_000);
        book("10:00:02", deep, lots, 100_000);
        book("10:00:03", deep, lots, 100_000);
        assertThat(oms.livePositions()).singleElement().satisfies(p -> assertThat(p.quantity).isEqualTo(1950));
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
