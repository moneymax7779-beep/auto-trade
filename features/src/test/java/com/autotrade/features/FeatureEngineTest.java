package com.autotrade.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.FeatureSnapshot;

class FeatureEngineTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 9, 25);

    @Test
    void aSnapshotNeverSeesAnEventReceivedAtOrAfterItsTime() {
        List<FeatureSnapshot> snapshots = new ArrayList<>();
        FeatureEngine engine = new FeatureEngine("NIFTY", SESSION, TestConfig.load(), SessionHistory.NONE,
                snapshots::add);

        engine.accept(index("09:15:10", 100));
        engine.accept(index("09:16:00", 200)); // exactly at the 09:16 snapshot: must not be in it
        engine.accept(index("09:16:30", 300));
        engine.accept(index("09:17:05", 400));

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).time()).isEqualTo(at("09:16:00"));
        assertThat(snapshots.get(0).spot()).isEqualTo(100);
        assertThat(snapshots.get(1).time()).isEqualTo(at("09:17:00"));
        assertThat(snapshots.get(1).spot()).isEqualTo(300);
    }

    @Test
    void ignoresOtherUnderlyingsAndEmitsTheFullGridOnFinish() {
        List<FeatureSnapshot> snapshots = new ArrayList<>();
        FeatureEngine engine = new FeatureEngine("NIFTY", SESSION, TestConfig.load(), SessionHistory.NONE,
                snapshots::add);
        engine.accept(new IndexTick(at("09:15:01"), null, "SENSEX", 1, 74000, null, null));
        engine.accept(index("09:15:02", 23035));

        engine.finish();

        assertThat(snapshots).hasSize(385); // 09:16 .. 15:40 every minute
        assertThat(snapshots.getFirst().spot()).isEqualTo(23035);
        assertThat(snapshots.getLast().time()).isEqualTo(at("15:40:00"));
        assertThat(snapshots.getLast().phase()).isEqualTo("CLOSED"); // derivatives close is exclusive
    }

    @Test
    void breadthWeightsConstituentMoves() {
        List<FeatureSnapshot> snapshots = new ArrayList<>();
        FeatureEngine engine = new FeatureEngine("NIFTY", SESSION, TestConfig.load(), SessionHistory.NONE,
                snapshots::add);
        engine.accept(stock("09:20:00", "HDFCBANK", 13.0, 1000, 990));
        engine.accept(stock("09:20:00", "ITC", 3.0, 400, 410));
        engine.accept(stock("09:25:00", "HDFCBANK", 13.0, 1010, 990)); // +1.0% in 5 min
        engine.accept(stock("09:25:00", "ITC", 3.0, 399, 410)); // -0.25%
        engine.accept(index("09:26:10", 23100));

        FeatureSnapshot at0926 = snapshots.getLast();
        assertThat(at0926.time()).isEqualTo(at("09:26:00"));
        assertThat(at0926.breadth().momentumBreadth()).isBetween(60.0, 90.0);
        assertThat(at0926.breadth().dayBreadth()).isEqualTo(100.0 * (13 - 3) / 16);
        assertThat(at0926.breadth().weightCoveragePct()).isEqualTo(100.0);
    }

    private static IndexTick index(String time, double price) {
        return new IndexTick(at(time), null, "NIFTY", 1, price, 23063.1, null);
    }

    private static ConstituentTick stock(String time, String symbol, double weight, double price, double close) {
        return new ConstituentTick(at(time), null, "NIFTY", 1, symbol.hashCode(), symbol, price, close, 0L, price,
                weight);
    }

    private static Instant at(String time) {
        return SESSION.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant();
    }
}
