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
import com.autotrade.strategy.PositionView;

/**
 * A-358: a short straddle (PAPER only): two legs sold at the bid, no resting stops, the margin as capital in use, a
 * combined stop on the asks, buy-back exits, and cash settlement at intrinsic value.
 */
class OrderManagerShortStraddleTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 10, 27);
    private static final RiskLimits V19 =
            RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v19.yaml")));
    private static final RiskLimits V17 =
            RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v17.yaml")));
    private static final CostModel COSTS =
            CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs", "india-index-options-costs.v2.yaml")));
    private static final Contract CALL = new Contract(7, "NSE_FO|7", "NIFTY 22800 CE", "NSE", 65, 0.05, 27, 22800);
    private static final Contract PUT = new Contract(8, "NSE_FO|8", "NIFTY 22800 PE", "NSE", 65, 0.05, 27, 22800);

    private final AtomicReference<Instant> clock = new AtomicReference<>(at("12:00:00"));
    private final List<OrderRequest> sent = new ArrayList<>();
    private final List<String> refusals = new ArrayList<>();
    private PaperBroker broker;
    private OrderManager oms;

    @BeforeEach
    void setUp() {
        oms = oms(V19);
    }

    private OrderManager oms(RiskLimits limits) {
        broker = new PaperBroker("paper", Duration.ofMillis(250), 0, clock::get);
        return new OrderManager("P1", "P1-S1", "ess", SESSION, broker, new RiskEngine(limits, new KillSwitch((s, e) -> {
        })), COSTS, 50, clock::get, new OmsListener() {
            @Override
            public void orderSent(ManagedPosition position, String role, OrderRequest request) {
                sent.add(request);
            }

            @Override
            public void rejected(String strategyId, String underlying, String intent, String reason) {
                refusals.add(intent + ": " + reason);
            }
        });
    }

    @Test
    void sellsBothLegsAtTheBidWithNoRestingStopAndTheMarginIsCapitalInUse() {
        quotes("12:00:00", 44.80, 45.00, 41.80, 42.00);
        assertThat(oms.enterShortStraddle("ess", "NIFTY", CALL, PUT, 1, "CONFIRMED", market("12:00"), 50).approved())
                .isTrue();
        assertThat(sent).hasSize(2).allSatisfy(r -> {
            assertThat(r.side()).isEqualTo(OrderSide.SELL);
            assertThat(r.type()).isEqualTo(OrderType.LIMIT);
            assertThat(r.quantity()).isEqualTo(65);
        });
        assertThat(sent).extracting(OrderRequest::limitPrice).containsExactly(44.60, 41.60);   // bid − 4 ticks
        quotes("12:00:01", 44.80, 45.00, 41.80, 42.00);                 // fills at the bids after the 250 ms latency
        assertThat(oms.livePositions()).hasSize(2).allSatisfy(leg -> {
            assertThat(leg.direction()).isEqualTo(ManagedPosition.Direction.SHORT);
            assertThat(leg.quantity()).isEqualTo(65);
            assertThat(leg.state()).isEqualTo(ManagedPosition.State.OPEN);
        });
        assertThat(oms.livePositions()).extracting(ManagedPosition::averageCost).containsExactly(44.80, 41.80);
        assertThat(sent).as("no resting stop on a sold leg").noneMatch(r -> r.type() == OrderType.STOP_LIMIT);
        assertThat(oms.premiumInUse(null)).as("the risk file's margin per lot, not the credit").isEqualTo(120_000);
        // marked to the asks: (44.80 − 45.00 + 41.80 − 42.00) × 65
        assertThat(oms.dayPnl()).isCloseTo(-26 - costsOf(), within(0.01));
        PositionView view = oms.view("ess", "NIFTY");
        assertThat(view.open()).isTrue();
        assertThat(view.legs()).hasSize(2).allSatisfy(leg -> assertThat(leg.strike()).isEqualTo(22800));
        assertThat(view.legs()).extracting(PositionView.Leg::ask).containsExactly(45.00, 42.00);
        assertThat(view.heldValueRatio()).isCloseTo(87.0 / 86.6, within(1e-9));
        assertThat(oms.reconcile()).as("the broker holds −65 of each").isTrue();

        // the margin blocks another strategy's entry that needs more than the free ₹3,80,000
        Contract other = new Contract(9, "NSE_FO|9", "NIFTY 22900 CE", "NSE", 65, 0.05, 27, 22900);
        quote("12:01:00", 9, "NIFTY 22900 CE", 99.50, 100.00);
        assertThat(oms.enter("ecr", "NIFTY", OptionSide.CE, other, 60, "CONFIRMED", market("12:01"), Double.NaN).reason())
                .startsWith("capital");                                   // 60 × 65 × 100 = ₹3,90,000
    }

    @Test
    void theStopBuysBothBackWhenTheCombinedAskReaches150PercentOfTheCredit() {
        sold();
        // legs are judged tick by tick on their last asks (as a long straddle is on its bids): the put falls first
        quote("13:00:00", 8, "NIFTY 22800 PE", 34.80, 35.00);          // 45.00 + 35.00 = 80.00
        quote("13:00:00", 7, "NIFTY 22800 CE", 94.00, 94.20);          // 94.20 + 35.00 = 129.20 < 1.5 × 86.60 = 129.90: held
        assertThat(sent).noneMatch(r -> r.side() == OrderSide.BUY);
        quote("13:01:00", 7, "NIFTY 22800 CE", 95.00, 95.20);          // 95.20 + 35.00 = 130.20 ≥ 129.90: both bought back
        assertThat(sent).filteredOn(r -> r.side() == OrderSide.BUY).extracting(OrderRequest::limitPrice)
                .containsExactly(96.20, 35.35);                          // ask + max(4 ticks, 1 %)
        quotes("13:01:01", 95.00, 95.20, 34.80, 35.00);
        assertThat(oms.livePositions()).isEmpty();
        assertThat(oms.closedPositions()).hasSize(2).allSatisfy(leg -> assertThat(leg.exitReason()).isEqualTo("STOP_50"));
        double realised = oms.closedPositions().stream().mapToDouble(ManagedPosition::realised).sum();
        assertThat(realised).isCloseTo((44.80 - 95.20) * 65 + (41.80 - 35.00) * 65, within(1e-6));
        assertThat(oms.premiumInUse(null)).as("the margin is free again").isZero();
        assertThat(oms.view("ess", "NIFTY").closedToday()).isEqualTo(2);
        assertThat(oms.reconcile()).isTrue();
    }

    @Test
    void anExitBuysBackAtTheAskAndChasesARisingAsk() {
        sold();
        quotes("12:30:00", 50.00, 50.20, 40.00, 40.20);
        oms.exit("ess", "NIFTY", "BREAK_EVEN_UP");
        assertThat(sent).filteredOn(r -> r.side() == OrderSide.BUY).extracting(OrderRequest::limitPrice)
                .containsExactly(50.75, 40.65);                          // 50.20 + 0.502 → 50.75; 40.20 + 0.402 → 40.65
        quotes("12:30:01", 50.00, 50.20, 40.00, 40.20);
        assertThat(oms.livePositions()).isEmpty();
        assertThat(oms.closedPositions()).allSatisfy(leg -> assertThat(leg.exitReason()).isEqualTo("BREAK_EVEN_UP"));
    }

    @Test
    void aShortHeldToTheCloseSettlesAtIntrinsicValue() {
        sold();
        clock.set(at("15:30:05"));
        oms.settleAtExpiry(7, 0.0);                                      // spot 22749.75: the call expires worthless
        oms.settleAtExpiry(8, 50.25);                                    // the put is worth 22800 − 22749.75
        assertThat(oms.livePositions()).isEmpty();
        assertThat(oms.closedPositions()).hasSize(2)
                .allSatisfy(leg -> assertThat(leg.exitReason()).isEqualTo("EXPIRY_SETTLEMENT"));
        assertThat(leg("CE").realised()).isCloseTo(44.80 * 65, within(1e-6));
        assertThat(leg("PE").realised()).isCloseTo((41.80 - 50.25) * 65, within(1e-6));
        assertThat(oms.reconcile()).as("the paper broker's short holdings settled too").isTrue();
    }

    @Test
    void aSoldLegWhoseBidRunsAwayIsRePricedAtTheNewBidUntilItFills() {
        quotes("12:00:00", 44.80, 45.00, 41.80, 42.00);                 // PE limit 41.60 (bid − 4 ticks)
        oms.enterShortStraddle("ess", "NIFTY", CALL, PUT, 1, "CONFIRMED", market("12:00"), 50);
        quotes("12:00:00.300", 44.80, 45.00, 41.20, 41.40);            // the put's bid fell below the limit
        ManagedPosition put = oms.livePositions().stream().filter(p -> p.leg().equals("PE")).findFirst().orElseThrow();
        assertThat(put.quantity()).isZero();
        assertThat(oms.livePositions().stream().filter(p -> p.leg().equals("CE")).findFirst().orElseThrow().quantity())
                .isEqualTo(65);
        quotes("12:00:02.400", 44.80, 45.00, 41.20, 41.40);            // 2 s: re-priced to 41.20 − 4 ticks = 41.00
        quotes("12:00:02.700", 44.80, 45.00, 41.20, 41.40);            // the modification is live: sells at the bid
        assertThat(put.quantity()).isEqualTo(65);
        assertThat(put.averageCost()).isEqualTo(41.20);
        assertThat(put.entryChases).isEqualTo(1);
        assertThat(oms.reconcile()).isTrue();
    }

    @Test
    void aSoldLegThatNeverFillsClosesItsPartnerAndFreesTheMargin() {
        quotes("12:00:00", 44.80, 45.00, 41.80, 42.00);
        oms.enterShortStraddle("ess", "NIFTY", CALL, PUT, 1, "CONFIRMED", market("12:00"), 50);
        for (int i = 0; i <= 20; i++) {                                 // the put's bid falls 2 a second: never sells
            double bid = 41.00 - 2 * i;
            quotes(String.format("12:00:%02d.300", i), 44.80, 45.00, Math.max(0.05, bid), Math.max(0.10, bid + 0.20));
        }
        assertThat(oms.closedPositions()).extracting(ManagedPosition::leg, ManagedPosition::exitReason)
                .contains(org.assertj.core.groups.Tuple.tuple("PE", "ENTRY_NOT_FILLED"));
        quotes("12:00:21.000", 44.80, 45.00, 0.05, 0.10);
        quotes("12:00:21.500", 44.80, 45.00, 0.05, 0.10);
        assertThat(oms.livePositions()).as("the sold call is not kept on its own").isEmpty();
        assertThat(oms.closedPositions()).extracting(ManagedPosition::leg, ManagedPosition::exitReason)
                .contains(org.assertj.core.groups.Tuple.tuple("CE", "LEG_NOT_FILLED"));
        assertThat(oms.premiumInUse(null)).isZero();
        assertThat(oms.view("ess", "NIFTY").closedToday()).as("one leg traded").isEqualTo(1);
        assertThat(oms.reconcile()).isTrue();
    }

    @Test
    void settlementFillsABuyBackThatIsAlreadyWorking() {
        sold();
        quotes("15:29:00", 0.00, 0.05, 50.00, 50.50);
        oms.exit("ess", "NIFTY", "FLAT_BY");                             // buy-backs placed: the call has an ask, both work
        clock.set(at("15:30:05"));
        oms.settleAtExpiry(7, 0.0);
        oms.settleAtExpiry(8, 50.25);
        assertThat(oms.livePositions()).isEmpty();
        assertThat(oms.closedPositions()).allSatisfy(leg -> assertThat(leg.exitReason()).isEqualTo("EXPIRY_SETTLEMENT"));
        assertThat(leg("PE").realised()).isCloseTo((41.80 - 50.25) * 65, within(1e-6));
        assertThat(oms.reconcile()).isTrue();
    }

    @Test
    void bothLegsOpenAtTheSameInstantSoTheyCountAsOneTrade() {
        sold();
        assertThat(oms.livePositions()).extracting(ManagedPosition::openedAt).containsOnly(oms.livePositions().getFirst().openedAt());
    }

    @Test
    void refusedWithoutTheRiskFilesShortsSectionOrAboveItsLotLimit() {
        OrderManager v17 = oms(V17);
        quotes("12:00:00", 44.80, 45.00, 41.80, 42.00);
        assertThat(v17.enterShortStraddle("ess", "NIFTY", CALL, PUT, 1, "CONFIRMED", market("12:00"), 50).reason())
                .contains("short selling is not enabled");
        assertThat(v17.livePositions()).isEmpty();
        assertThat(oms.enterShortStraddle("ess", "NIFTY", CALL, PUT, 2, "CONFIRMED", market("12:00"), 50).reason())
                .contains("shorts.max_lots");
        assertThat(oms.enterShortStraddle("ess", "NIFTY", CALL, PUT, 1, "CONFIRMED", market("12:00"), Double.NaN).reason())
                .contains("stop");
        assertThat(sent).isEmpty();
    }

    @Test
    void refusedWholeWithoutABidForBothLegs() {
        assertThat(oms.enterShortStraddle("ess", "NIFTY", CALL, PUT, 1, "CONFIRMED", market("12:00"), 50).reason())
                .contains("no bid");
        assertThat(oms.livePositions()).isEmpty();
        assertThat(sent).isEmpty();
    }

    private void sold() {
        quotes("12:00:00", 44.80, 45.00, 41.80, 42.00);
        assertThat(oms.enterShortStraddle("ess", "NIFTY", CALL, PUT, 1, "CONFIRMED", market("12:00"), 50).approved())
                .isTrue();
        quotes("12:00:01", 44.80, 45.00, 41.80, 42.00);
        assertThat(oms.livePositions()).hasSize(2);
    }

    private double costsOf() {
        return oms.livePositions().stream().mapToDouble(ManagedPosition::costs).sum();
    }

    private ManagedPosition leg(String leg) {
        return oms.closedPositions().stream().filter(p -> p.leg().equals(leg)).findFirst().orElseThrow();
    }

    private void quotes(String time, double callBid, double callAsk, double putBid, double putAsk) {
        quote(time, 7, "NIFTY 22800 CE", callBid, callAsk);
        quote(time, 8, "NIFTY 22800 PE", putBid, putAsk);
    }

    private void quote(String time, long token, String symbol, double bid, double ask) {
        Instant at = at(time);
        clock.set(at);
        OptionTick.OptionType type = symbol.endsWith("CE") ? OptionTick.OptionType.CE : OptionTick.OptionType.PE;
        OptionTick tick = new OptionTick(at, at, "NIFTY", 1, token, symbol, SESSION, 22800, type, 65, (bid + ask) / 2,
                0L, 0.0, 0.0, 0.0,
                DepthLevels.of(new double[] {bid}, new long[] {100_000}, new int[] {1}),
                DepthLevels.of(new double[] {ask}, new long[] {100_000}, new int[] {1}),
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
