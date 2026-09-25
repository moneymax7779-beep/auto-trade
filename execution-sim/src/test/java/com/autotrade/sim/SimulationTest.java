package com.autotrade.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.time.MarketTime;

class SimulationTest {

    private static final ThresholdConfig CONFIG =
            ThresholdConfig.load(Path.of("..", "config", "costs", "india-index-options-costs.v1.yaml"));
    private static final CostModel COSTS = CostModel.from(CONFIG);
    private static final FillModel BASE = FillModel.from(CONFIG, "base");
    private static final FillModel STRESSED = FillModel.from(CONFIG, "stressed");
    private static final LocalDate SESSION = LocalDate.of(2026, 9, 25);

    @Test
    void chargesEachLegWithTheDatedRates() {
        CostBreakdown buy = COSTS.costs(SESSION, "NSE", Side.BUY, 100, 65);
        CostBreakdown sell = COSTS.costs(SESSION, "NSE", Side.SELL, 100, 65);

        assertThat(buy.turnover()).isEqualTo(6500);
        assertThat(buy.stt()).isZero();
        assertThat(buy.stamp()).isCloseTo(0.195, within(1e-9));
        assertThat(buy.exchange()).isCloseTo(2.27695, within(1e-9));
        assertThat(buy.gst()).isCloseTo(0.18 * (20 + 2.27695 + 0.0065), within(1e-9));
        assertThat(buy.total()).isCloseTo(26.4894, within(1e-3));
        assertThat(sell.stt()).isCloseTo(6.5, within(1e-9));
        assertThat(sell.stamp()).isZero();
        assertThat(sell.total()).isCloseTo(32.7944, within(1e-3));
        assertThat(COSTS.costs(SESSION, "BSE", Side.BUY, 100, 20).exchange()).isCloseTo(0.65, within(1e-9));
    }

    @Test
    void refusesTradesBeforeTheFirstRateSet() {
        assertThatThrownBy(
                () -> COSTS.costs(LocalDate.of(2024, 9, 30), "NSE", Side.BUY, 100, 65))
                .hasMessageContaining("no cost rates");
    }

    @Test
    void walksTheBookAndAppliesAdverseSlippage() {
        OptionTick quote = tick("10:00:00", new double[] {99, 98}, new long[] {65, 130},
                new double[] {100, 101}, new long[] {65, 65});

        FillModel.Fill buy = BASE.fill(Side.BUY, 130, quote);
        assertThat(buy.price()).isEqualTo(100.5);
        assertThat(buy.levelsUsed()).isEqualTo(2);

        FillModel.Fill big = BASE.fill(Side.BUY, 195, quote); // 65 beyond visible depth, 2 ticks past 101
        assertThat(big.depthExhausted()).isTrue();
        assertThat(big.price()).isCloseTo((100 * 65 + 101 * 65 + 101.1 * 65) / 195.0, within(1e-9));

        FillModel.Fill stressedSell = STRESSED.fill(Side.SELL, 65, quote);
        assertThat(stressedSell.price()).isCloseTo(99 * (1 - 0.0025), within(1e-9));
    }

    @Test
    void entersAfterLatencyAndTakesTheTarget() {
        LongOptionSimulator sim = new LongOptionSimulator(plan("10:00:00", 90, 120, "10:30:00"), BASE, COSTS);
        sim.accept(tick("10:00:00.100", 99, 100)); // inside the 250 ms latency: not used
        sim.accept(tick("10:00:00.300", 101, 102)); // entry at ask 102
        sim.accept(tick("10:05:00", 110, 111));
        sim.accept(tick("10:06:00", 121, 122)); // target on bid 121
        sim.accept(tick("10:06:00.100", 125, 126)); // within latency of the exit order
        sim.accept(tick("10:06:00.400", 119, 120)); // exit fills at bid 119

        TradeResult result = sim.result();
        assertThat(result.status()).isEqualTo("FILLED_AND_EXITED");
        assertThat(result.exitReason()).isEqualTo("TARGET");
        assertThat(result.entry().price()).isEqualTo(102);
        assertThat(result.exit().price()).isEqualTo(119);
        assertThat(result.grossPnl()).isEqualTo(17 * 65.0);
        assertThat(result.netPnl()).isCloseTo(17 * 65.0 - result.totalCosts(), within(1e-9));
        assertThat(result.maxFavourablePerUnit()).isEqualTo(125 - 102.0); // includes the in-flight exit
    }

    @Test
    void stopTouchedBeforeTargetIsTakenFirst() {
        LongOptionSimulator sim = new LongOptionSimulator(plan("10:00:00", 90, 120, "10:30:00"), BASE, COSTS);
        sim.accept(tick("10:00:01", 99, 100));
        sim.accept(tick("10:01:00", 89, 90));
        sim.accept(tick("10:01:00.500", 88, 89));

        TradeResult result = sim.result();
        assertThat(result.exitReason()).isEqualTo("STOP");
        assertThat(result.exit().price()).isEqualTo(88);
        assertThat(result.maxAdversePerUnit()).isEqualTo(100 - 88.0);
    }

    @Test
    void exitsOnTimeAndReportsUnfilledAndOpenTrades() {
        LongOptionSimulator timed = new LongOptionSimulator(plan("10:00:00", 50, 200, "10:10:00"), BASE, COSTS);
        timed.accept(tick("10:00:01", 99, 100));
        timed.accept(tick("10:10:00", 101, 102));
        timed.accept(tick("10:10:01", 101, 102));
        assertThat(timed.result().exitReason()).isEqualTo("TIME");

        LongOptionSimulator none = new LongOptionSimulator(plan("10:00:00", 50, 200, "10:10:00"), BASE, COSTS);
        assertThat(none.result().status()).isEqualTo("NOT_FILLED");

        LongOptionSimulator open = new LongOptionSimulator(plan("10:00:00", 50, 200, "10:10:00"), BASE, COSTS);
        open.accept(tick("10:00:01", 99, 100));
        assertThat(open.result().status()).isEqualTo("OPEN_AT_END");
    }

    private static TradePlan plan(String decision, double stop, double target, String exitBy) {
        return new TradePlan(7, "NSE", 65, at(decision), stop, target, at(exitBy));
    }

    private static OptionTick tick(String time, double bid, double ask) {
        return tick(time, new double[] {bid}, new long[] {650}, new double[] {ask}, new long[] {650});
    }

    private static OptionTick tick(String time, double[] bids, long[] bidQty, double[] asks, long[] askQty) {
        Instant at = at(time);
        return new OptionTick(at, at, "NIFTY", 1, 7, "NIFTY 23100 CE", LocalDate.of(2026, 9, 29), 23100,
                OptionTick.OptionType.CE, 65, asks[0], 0L, 0.0, 0.0, 0.0,
                DepthLevels.of(bids, bidQty, new int[bids.length]), DepthLevels.of(asks, askQty, new int[asks.length]),
                0.1, 0.5, 0.001, -10.0, 12.0, 1.0, at, true, true);
    }

    private static Instant at(String time) {
        return SESSION.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant();
    }
}
