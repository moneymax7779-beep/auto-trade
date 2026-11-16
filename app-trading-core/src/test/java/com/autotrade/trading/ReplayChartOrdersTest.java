package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.autotrade.broker.paper.PaperBroker;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.time.MarketTime;
import com.autotrade.execution.ChartLines;
import com.autotrade.oms.Contract;
import com.autotrade.oms.ManagedPosition;
import com.autotrade.oms.MarketContext;
import com.autotrade.oms.OmsListener;
import com.autotrade.oms.OrderManager;
import com.autotrade.risk.KillSwitch;
import com.autotrade.risk.RiskDecision;
import com.autotrade.risk.RiskEngine;
import com.autotrade.risk.RiskLimits;
import com.autotrade.sim.CostModel;
import com.autotrade.strategy.OptionSide;
import com.autotrade.trading.ReplayChartOrders.Order;

/**
 * Chart orders in a replay (user, 15 Nov 2026: "in the replay i should be able to draw and run the replay"): the lines
 * on replayed index ticks, traded through a real OMS and paper broker on replayed option quotes.
 */
class ReplayChartOrdersTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 9, 28);
    private static final RiskLimits LIMITS =
            RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v2.yaml")));
    private static final CostModel COSTS =
            CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs", "india-index-options-costs.v1.yaml")));
    private static final Contract CE = new Contract(7, "NSE_FO|7", "NIFTY 23100 CE", "NSE", 65, 0.05, 27);

    private final AtomicReference<Instant> clock = new AtomicReference<>(at("10:00:00"));
    private final Map<String, Double> spot = new HashMap<>();
    private PaperBroker broker;
    private OrderManager oms;
    private ReplayChartOrders book;

    @BeforeEach
    void setUp() {
        broker = new PaperBroker("paper", Duration.ofMillis(250), 0, clock::get);
        oms = new OrderManager("P1", "P1-S1", "ecr", SESSION, broker, new RiskEngine(LIMITS, new KillSwitch((s, e) -> {
        })), COSTS, 25, clock::get, new OmsListener() {
        });
        book = new ReplayChartOrders(new ReplayChartOrders.Desk() {
            @Override
            public Instant now() {
                return clock.get();
            }

            @Override
            public double spot(String underlying) {
                return spot.getOrDefault(underlying, Double.NaN);
            }

            @Override
            public ReplayChartOrders.Bought buy(String underlying, String side, int lots, ChartLines.Lines lines) {
                LocalTime time = clock.get().atZone(MarketTime.IST).toLocalTime();
                RiskDecision r = oms.enter(ReplayChartOrders.STRATEGY, underlying, OptionSide.valueOf(side), CE, lots,
                        "CHART", new MarketContext(time, 0.2, 0.2), ChartLines.safetyStopPct(lines, oms.ask(CE)));
                return r.approved() ? new ReplayChartOrders.Bought(oms.livePositions().getFirst(), null)
                        : new ReplayChartOrders.Bought(null, r.reason());
            }

            @Override
            public void exit(String underlying, String reason) {
                oms.exit(ReplayChartOrders.STRATEGY, underlying, reason);
            }
        }, java.util.Set.of("NIFTY", "SENSEX"));
    }

    @Test
    void aReplayOrderIsNotArmedAsAnotherKindThanTheTicketShowed() {
        // review 16 Nov 2026 (D7): at 300x the ticket's quote can be minutes of replay old
        index("10:00:00", 23_080);
        quote("10:00:00", 99.5, 100, 100);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1,
                "14:45", "PULLBACK")).hasMessageContaining("these lines arm as a breakout, not the pullback");
        assertThat(book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, "14:45", "BREAKOUT").kind)
                .isEqualTo(ChartLines.Kind.BREAKOUT);
    }

    @Test
    void aBreakoutBuysAtTheBufferPastTheEntryAndSellsAtTheTarget() {
        index("10:00:00", 23_080);
        quote("10:00:00", 99.5, 100, 100);
        Order o = book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, "14:45");
        assertThat(o.kind).isEqualTo(ChartLines.Kind.BREAKOUT);

        index("10:01:00", 23_101);
        assertThat(o.status).as("a one-point poke").isEqualTo("ARMED");
        index("10:02:00", 23_102);
        assertThat(o.status).isEqualTo("TRIGGERED");
        assertThat(o.position.contract().symbol()).isEqualTo("NIFTY 23100 CE");

        quote("10:02:01", 99.5, 100, 100);                  // the buy fills on the next quote
        index("10:02:01", 23_110);
        assertThat(o.status).isEqualTo("OPEN");
        assertThat(o.position.quantity()).isEqualTo(65);

        index("10:20:00", 23_160);                          // the target line
        assertThat(o.status).isEqualTo("EXITING");
        quote("10:20:01", 130, 131, 130);
        quote("10:20:02", 130, 131, 130);
        book.refresh(false);
        assertThat(o.status).isEqualTo("CLOSED");
        assertThat(o.reason).isEqualTo("CHART_TARGET");
        assertThat(o.position.net()).isPositive();
        Map<String, Object> view = ReplayChartOrders.view(o);
        assertThat(view).containsEntry("status", "CLOSED").containsEntry("symbol", "NIFTY 23100 CE");
        assertThat(view.get("events").toString()).contains("BUY_SENT", "FILLED", "CHART_TARGET", "CLOSED");
    }

    @Test
    void aStopCrossedWhileTheBuyIsStillWorkingCancelsItWithNothingBought() {
        index("10:00:00", 23_080);
        quote("10:00:00", 99.5, 100, 100);
        Order o = book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, null);
        index("10:02:00", 23_102);
        assertThat(o.status).isEqualTo("TRIGGERED");
        index("10:02:00", 23_067);                          // through the stop before any quote filled the buy
        assertThat(o.status).isEqualTo("EXITING");
        quote("10:02:01", 99.5, 100, 100);                  // the broker confirms the cancel on its next event
        book.refresh(false);
        assertThat(o.status).isEqualTo("CANCELLED");
        assertThat(o.reason).startsWith("the buy did not fill");
        assertThat(oms.livePositions()).isEmpty();
    }

    @Test
    void theStopFirstOrAGapPastTheEntryVoidsAWaitingOrder() {
        index("10:00:00", 23_080);
        Order stopFirst = book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, "14:45");
        index("10:01:00", 23_070);
        assertThat(stopFirst.status).isEqualTo("VOID");

        index("10:02:00", 23_080);
        Order gap = book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, "14:45");
        index("10:03:00", 23_109);                          // 9 past, the no-chase limit is 8
        assertThat(gap.status).isEqualTo("VOID");
        assertThat(gap.reason).contains("not chased");
        assertThat(oms.livePositions()).isEmpty();
    }

    @Test
    void exitNowSellsAnOpenPositionAndCancelsAWaitingOne() {
        index("10:00:00", 23_080);
        quote("10:00:00", 99.5, 100, 100);
        Order o = book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, "14:45");
        index("10:02:00", 23_102);
        quote("10:02:01", 99.5, 100, 100);
        index("10:02:01", 23_105);
        assertThat(o.status).isEqualTo("OPEN");
        book.exitNow(o.id);
        assertThat(o.status).isEqualTo("EXITING");
        quote("10:03:00", 103, 104, 103);
        quote("10:03:01", 103, 104, 103);
        book.refresh(false);
        assertThat(o.status).isEqualTo("CLOSED");
        assertThat(o.reason).isEqualTo("TRADER_EXIT");

        Order waiting = book.arm("NIFTY", "CE", 23_150.0, 23_090.0, 23_200.0, 1, "14:45");
        book.exitNow(waiting.id);
        assertThat(waiting.status).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> book.exitNow(waiting.id)).hasMessageContaining("nothing to exit");
    }

    @Test
    void aPositionClosedByItsPremiumSafetyStopEndsTheOrder() {
        index("10:00:00", 23_080);
        quote("10:00:00", 99.5, 100, 100);
        Order o = book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, "14:45");
        index("10:02:00", 23_102);
        quote("10:02:01", 99.5, 100, 100);
        index("10:02:01", 23_103);
        assertThat(o.status).isEqualTo("OPEN");
        quote("10:05:00", 70, 70.5, 70);                     // the premium falls through the broker safety stop
        book.refresh(false);
        assertThat(o.status).isEqualTo("CLOSED");
        assertThat(o.reason).isEqualTo("PREMIUM_STOP");
        assertThat(oms.closedPositions()).singleElement().extracting(ManagedPosition::exitReason).isEqualTo("PREMIUM_STOP");
    }

    @Test
    void thePreOpenAuctionsIndexNeitherVoidsNorEntersAnOrder() {
        index("09:10:00", 23_080);                          // armed on the pre-open auction's indicative
        quote("09:10:00", 99.5, 100, 100);
        Order o = book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, "14:45");
        index("09:12:00", 23_060);                          // past the stop in the auction: not a tradable price
        index("09:14:59", 23_110);                          // past the entry in the auction
        assertThat(o.status).isEqualTo("ARMED");
        index("09:15:00", 23_102);                          // the first normal tick
        assertThat(o.status).isEqualTo("TRIGGERED");
    }

    @Test
    void armingAndMovingAreCheckedAgainstTheReplaysClockAndPrice() {
        assertThatThrownBy(() -> book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, "14:45"))
                .hasMessageContaining("no NIFTY price in the replay yet");
        index("10:00:00", 23_080);
        assertThatThrownBy(() -> book.arm("BANKNIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, "14:45"))
                .hasMessageContaining("NIFTY and SENSEX");
        assertThatThrownBy(() -> book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, "09:30"))
                .hasMessageContaining("is not after the replay's time 10:00");
        Order o = book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, "10:30");
        assertThatThrownBy(() -> book.arm("NIFTY", "PE", 23_050.0, 23_090.0, 23_000.0, 1, "14:45"))
                .hasMessageContaining("one chart order at a time on NIFTY");

        book.move(o.id, o.version, null, 23_075.0, null);
        assertThat(o.stop).isEqualTo(23_075);
        assertThatThrownBy(() -> book.move(o.id, 1, null, 23_072.0, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed meanwhile");
        assertThatThrownBy(() -> book.move(o.id, o.version, 23_081.0, null, null)).hasMessageContaining("fire at once");

        index("10:31:00", 23_085);
        assertThat(o.status).isEqualTo("EXPIRED");
        index("10:32:00", 23_085);
        Order last = book.arm("NIFTY", "CE", 23_100.0, 23_070.0, 23_160.0, 1, "14:45");
        book.refresh(true);
        assertThat(last.status).isEqualTo("EXPIRED");
        assertThat(last.reason).isEqualTo("the replay ended");
    }

    private void index(String time, double price) {
        clock.set(at(time));
        spot.put("NIFTY", price);
        book.onIndexTick("NIFTY", price);
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

    private static Instant at(String time) {
        return SESSION.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant();
    }
}
