package com.autotrade.oms;

import static org.assertj.core.api.Assertions.assertThat;

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

/** A straddle: two legs, no resting stops, a combined target and stop, sized to the free capital. */
class OrderManagerStraddleTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 9, 29);
    private static final RiskLimits V4 =
            RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v4.yaml")));
    private static final CostModel COSTS =
            CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs", "india-index-options-costs.v1.yaml")));
    private static final Contract CALL = new Contract(7, "NSE_FO|7", "NIFTY 23300 CE", "NSE", 65, 0.05, 27);
    private static final Contract PUT = new Contract(8, "NSE_FO|8", "NIFTY 23300 PE", "NSE", 65, 0.05, 27);

    private final AtomicReference<Instant> clock = new AtomicReference<>(at("13:54:00"));
    private final List<OrderRequest> sent = new ArrayList<>();
    private final List<String> refusals = new ArrayList<>();
    private PaperBroker broker;
    private OrderManager oms;

    @BeforeEach
    void setUp() {
        broker = new PaperBroker("paper", Duration.ofMillis(250), 0, clock::get);
        oms = new OrderManager("P1", "P1-S1", "ecr", SESSION, broker, new RiskEngine(V4, new KillSwitch((s, e) -> {
        })), COSTS, 30, clock::get, new OmsListener() {
            @Override
            public void orderSent(ManagedPosition position, String role, OrderRequest request) {
                sent.add(request);
            }

            @Override
            public void rejected(String strategyId, String underlying, String intent, String reason) {
                refusals.add(strategyId + ": " + reason);
            }
        });
    }

    @Test
    void theFullBudgetBuysBothLegsWithoutRestingStopsAndTheTargetClosesBoth() {
        quotes("13:54:00", 42.00, 42.20, 40.30, 40.50);                // straddle 82.70 at the asks
        assertThat(oms.enterStraddle("ebs", "NIFTY", CALL, PUT, 500_000, "CONFIRMED", market("13:54"), 30, 20)
                .approved()).isTrue();
        int lots = (int) Math.floor(500_000 / (82.70 * 65));            // 93
        assertThat(sent).filteredOn(r -> r.side() == OrderSide.BUY).extracting(OrderRequest::quantity)
                .containsExactly(27L * 65, 27L * 65, 27L * 65, 12L * 65, 27L * 65, 27L * 65, 27L * 65, 12L * 65);
        quotes("13:54:01", 42.00, 42.20, 40.30, 40.50);                 // fills after the 250 ms latency
        assertThat(oms.livePositions()).hasSize(2).allSatisfy(leg -> assertThat(leg.lots()).isEqualTo(lots));
        assertThat(oms.livePositions()).extracting(ManagedPosition::leg).containsExactly("CE", "PE");
        assertThat(sent).as("no resting stop on a straddle leg").noneMatch(r -> r.type() == OrderType.STOP_LIMIT);
        assertThat(oms.view("ebs", "NIFTY").open()).isTrue();

        // the call collapses to 25 while the put rises to 68, in steps (legs are judged tick by tick on
        // their last bids): +17 %, −3 %, +12.5 %; the pair stays inside the band, no exit
        quote("14:05:00", 8, "NIFTY 23300 PE", 55.00, 55.20);
        quote("14:05:00", 7, "NIFTY 23300 CE", 25.00, 25.20);
        quote("14:05:01", 8, "NIFTY 23300 PE", 68.00, 68.20);
        assertThat(sent).noneMatch(r -> r.side() == OrderSide.SELL);
        // 21.70 + 89.35 = 111.05 ≥ 1.30 × 82.70 = 107.51: both legs sell
        quotes("14:14:53", 21.70, 21.90, 89.35, 89.55);
        assertThat(sent).filteredOn(r -> r.side() == OrderSide.SELL).extracting(OrderRequest::symbol)
                .contains("NIFTY 23300 CE", "NIFTY 23300 PE");
        quotes("14:14:54", 21.70, 21.90, 89.35, 89.55);
        assertThat(oms.livePositions()).isEmpty();
        assertThat(oms.closedPositions()).hasSize(2).allSatisfy(leg -> assertThat(leg.exitReason()).isEqualTo("TARGET_30"));
        assertThat(oms.reconcile()).isTrue();
    }

    @Test
    void theCombinedStopClosesBothLegs() {
        quotes("13:54:00", 42.00, 42.20, 40.30, 40.50);
        oms.enterStraddle("ebs", "NIFTY", CALL, PUT, 100_000, "CONFIRMED", market("13:54"), 30, 20);
        quotes("13:54:01", 42.00, 42.20, 40.30, 40.50);
        quotes("14:30:00", 30.00, 30.20, 36.00, 36.20);                 // 66.00 ≤ 0.80 × 82.70 = 66.16
        quotes("14:30:01", 30.00, 30.20, 36.00, 36.20);
        assertThat(oms.closedPositions()).hasSize(2).allSatisfy(leg -> assertThat(leg.exitReason()).isEqualTo("STOP_20"));
    }

    @Test
    void theStraddleTakesOnlyTheFreeCapitalAndThenBlocksOtherEntries() {
        Contract other = new Contract(9, "NSE_FO|9", "NIFTY 23400 CE", "NSE", 65, 0.05, 27);
        quote("12:40:00", 9, "NIFTY 23400 CE", 99.50, 100.00);
        quotes("12:40:00", 42.00, 42.20, 40.30, 40.50);
        assertThat(oms.enter("ecr", "NIFTY", OptionSide.CE, other, 4, "CONFIRMED", market("12:40"), Double.NaN)
                .approved()).isTrue();                                  // 260 × 100 = ₹26,000 in use
        quote("12:40:01", 9, "NIFTY 23400 CE", 99.50, 100.00);

        assertThat(oms.enterStraddle("ebs", "NIFTY", CALL, PUT, 500_000, "CONFIRMED", market("12:57"), 30, 20)
                .approved()).isTrue();
        int lots = (int) Math.floor((500_000 - 26_000) / (82.70 * 65)); // 88, not 93
        quotes("12:57:01", 42.00, 42.20, 40.30, 40.50);
        assertThat(oms.livePositions()).filteredOn(p -> p.strategy().equals("ebs"))
                .allSatisfy(leg -> assertThat(leg.lots()).isEqualTo(lots));

        // "straddle first": with the capital in use, another strategy's new entry is refused
        assertThat(oms.enter("egb", "NIFTY", OptionSide.CE, other, 1, "EARLY", market("13:00"), 30).reason())
                .startsWith("capital");
        oms.exit("ebs", "NIFTY", "TEST");
        quotes("13:01:00", 42.00, 42.20, 40.30, 40.50);
        quotes("13:01:01", 42.00, 42.20, 40.30, 40.50);
        assertThat(oms.enter("egb", "NIFTY", OptionSide.CE, other, 1, "EARLY", market("13:02"), 30).approved())
                .as("capital free again after the straddle closed").isTrue();
    }

    @Test
    void aLegWhoseAskRunsAwayIsChasedUntilItFills() {
        quotes("13:54:00", 42.00, 42.20, 40.10, 40.30);                 // PE limit 40.50 (ask + 4 ticks)
        oms.enterStraddle("ebs", "NIFTY", CALL, PUT, 100_000, "CONFIRMED", market("13:54"), 30, 20);
        quotes("13:54:00.300", 42.00, 42.20, 40.60, 40.80);            // the put's ask jumped above the limit
        ManagedPosition put = leg("PE");
        assertThat(put.quantity()).isZero();
        assertThat(leg("CE").quantity()).isPositive();
        quotes("13:54:02.400", 42.00, 42.20, 40.60, 40.80);            // 2 s: re-priced to 40.80 + 4 ticks
        quotes("13:54:02.700", 42.00, 42.20, 40.60, 40.80);            // the modification is live: fills
        assertThat(put.quantity()).isEqualTo(leg("CE").quantity());
        assertThat(put.averageCost()).isEqualTo(40.80);
        assertThat(put.entryChases).isEqualTo(1);
    }

    @Test
    void aLegThatNeverFillsClosesItsPartnerSoNoNakedLegIsKept() {
        quotes("13:54:00", 42.00, 42.20, 40.10, 40.30);
        oms.enterStraddle("ebs", "NIFTY", CALL, PUT, 100_000, "CONFIRMED", market("13:54"), 30, 20);
        for (int i = 0; i <= 20; i++) {                                 // the put's ask rises 2 a second
            double ask = 40.80 + 2 * i;
            quotes(String.format("13:54:%02d.300", i), 42.00, 42.20, ask - 0.20, ask);
        }
        assertThat(oms.closedPositions()).extracting(ManagedPosition::leg, ManagedPosition::exitReason)
                .contains(org.assertj.core.groups.Tuple.tuple("PE", "ENTRY_NOT_FILLED"));
        quotes("13:54:21.000", 42.00, 42.20, 80.60, 80.80);
        quotes("13:54:21.500", 42.00, 42.20, 80.60, 80.80);
        assertThat(oms.livePositions()).as("the call is not kept on its own").isEmpty();
        assertThat(oms.closedPositions()).extracting(ManagedPosition::leg, ManagedPosition::exitReason)
                .contains(org.assertj.core.groups.Tuple.tuple("CE", "LEG_NOT_FILLED"));
        assertThat(oms.reconcile()).isTrue();
    }

    private ManagedPosition leg(String leg) {
        return oms.livePositions().stream().filter(p -> p.leg().equals(leg)).findFirst().orElseThrow();
    }

    @Test
    void aStraddleIsRefusedWholeWhenItCannotHaveBothLegs() {
        assertThat(oms.enterStraddle("ebs", "NIFTY", CALL, PUT, 500_000, "CONFIRMED", market("13:54"), 30, 20).reason())
                .contains("no ask");
        quotes("13:54:00", 42.00, 42.20, 40.30, 40.50);
        assertThat(oms.enterStraddle("ebs", "NIFTY", CALL, PUT, 2_000, "CONFIRMED", market("13:54"), 30, 20).reason())
                .contains("capital");                                    // one lot of each costs 5,375.50
        assertThat(oms.livePositions()).isEmpty();
        assertThat(sent).isEmpty();
    }

    private void quotes(String time, double callBid, double callAsk, double putBid, double putAsk) {
        quote(time, 7, "NIFTY 23300 CE", callBid, callAsk);
        quote(time, 8, "NIFTY 23300 PE", putBid, putAsk);
    }

    private void quote(String time, long token, String symbol, double bid, double ask) {
        Instant at = at(time);
        clock.set(at);
        OptionTick.OptionType type = symbol.endsWith("CE") ? OptionTick.OptionType.CE : OptionTick.OptionType.PE;
        OptionTick tick = new OptionTick(at, at, "NIFTY", 1, token, symbol, SESSION, 23300, type, 65, (bid + ask) / 2,
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
