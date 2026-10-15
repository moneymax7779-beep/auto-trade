package com.autotrade.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.history.DailyBar;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.PreOpenFeatures;

/** Features v14 end to end through the engine: the pre-open auction from auction and constituent ticks. */
class FeatureEngineV14Test {

    private static final LocalDate SESSION = LocalDate.of(2026, 10, 14);

    @Test
    void preOpenFromTheAuctionTicksAndTheConstituentsPreviousCloses() {
        List<FeatureSnapshot> snapshots = run("v14");
        FeatureSnapshot at0916 = last(snapshots, "09:16:00");
        // A +1 % (buyers), B -2 % (sellers); C has no auction quote; the 15:20 closing-auction tick is ignored
        assertThat(at0916.preopen().preOpenReturnPct()).isCloseTo(100 * (30 * 0.01 - 10 * 0.02) / 40, within(1e-9));
        assertThat(at0916.preopen().preOpenBreadth()).isCloseTo(50.0, within(1e-9));
        assertThat(at0916.preopen().preOpenImbalance()).isCloseTo(50.0, within(1e-9));
        assertThat(at0916.preopen().preOpenCoveragePct()).isCloseTo(80.0, within(1e-9));
        assertThat(snapshots.getLast().preopen()).isEqualTo(last(snapshots, "09:20:00").preopen());
    }

    @Test
    void emptyWhenTheFeatureIsOff() {
        assertThat(run("v13").getLast().preopen()).isEqualTo(PreOpenFeatures.EMPTY);
    }

    @Test
    void breadthStateGivesThePreviousCloseOfEachConstituentThatHasOne() {
        com.autotrade.features.breadth.BreadthState breadth =
                new com.autotrade.features.breadth.BreadthState(TestConfig.load("v14"));
        breadth.onConstituent(stock("09:15:01", "A", 30.0, 100.0, 101.0));
        breadth.onConstituent(stock("09:15:01", "B", 10.0, null, 49.0));
        assertThat(breadth.previousCloses()).isEqualTo(Map.of("A", 100.0));
    }

    private static List<FeatureSnapshot> run(String version) {
        List<FeatureSnapshot> snapshots = new ArrayList<>();
        FeatureEngine engine = new FeatureEngine("NIFTY", SESSION, TestConfig.load(version), history(), snapshots::add);
        engine.accept(auction("09:05:00", "A", 101.0, 500));
        engine.accept(auction("09:07:30", "B", 49.0, -200));
        engine.accept(index("09:15:01", 23000));
        engine.accept(stock("09:15:01", "A", 30.0, 100.0, 101.0));
        engine.accept(stock("09:15:01", "B", 10.0, 50.0, 49.0));
        engine.accept(stock("09:15:01", "C", 10.0, 20.0, 20.0));
        for (int s = 10; s <= 3600; s += 10) {                    // 09:15:11 .. 10:15:01
            engine.accept(index(LocalTime.of(9, 15, 1).plusSeconds(s).toString(), 23000));
        }
        engine.accept(auction("10:15:02", "B", 60.0, 900));    // not pre-open: ignored
        engine.accept(index("10:16:01", 23000));
        return snapshots;
    }

    private static FeatureSnapshot last(List<FeatureSnapshot> snapshots, String time) {
        Instant until = at(time);
        return snapshots.stream().filter(s -> !s.time().isAfter(until)).reduce((a, b) -> b).orElseThrow();
    }

    private static SessionHistory history() {
        return new SessionHistory() {
            @Override
            public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
                return Optional.of(new DailyBar(session.minusDays(1), 22950, 23100, 22900, 23060));
            }

            @Override
            public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
                return List.of();
            }
        };
    }

    private static IndexTick index(String time, double price) {
        return new IndexTick(at(time), null, "NIFTY", 1, price, 23060.0, null);
    }

    private static ConstituentTick stock(String time, String symbol, double weight, Double previousClose, double price) {
        return new ConstituentTick(at(time), null, "NIFTY", 1, symbol.hashCode(), symbol, price, previousClose, 0L,
                price, weight);
    }

    private static AuctionTick auction(String time, String symbol, double iep, long imbalance) {
        return new AuctionTick(at(time), at(time), "NIFTY", symbol.hashCode(), symbol, iep, 0, 100, imbalance, 0, true);
    }

    private static Instant at(String time) {
        return SESSION.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant();
    }
}
