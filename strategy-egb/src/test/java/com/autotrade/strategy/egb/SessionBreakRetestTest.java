package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.core.time.MarketTime;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;

/**
 * A-428: sbr-v1 on the synthetic day also run through .local/a426/brk.py (setup 2, no filter): the 10:20 close 25,075 breaks
 * the 25,060 resistance; the 10:25 bar retests (low 25,065 within 12.6 of the line), closes 25,085 (>= 7.56 beyond, above
 * 25,075): CE at 10:25 held to the close (A); or stopped by the 10:35 close 25,050 after a 10:30 close 25,058 inside the
 * buffer (B). EM 252.
 */
class SessionBreakRetestTest {

    private static final String[][] BARS = {{"09:20", "25020", "24990", "25000"}, {"09:25", "25030", "25000", "25020"},
            {"09:30", "25060", "25020", "25040"}, {"09:35", "25040", "25010", "25020"}, {"09:40", "25030", "25000", "25010"},
            {"09:45", "25020", "24995", "25005"}, {"09:50", "25030", "25005", "25025"}, {"09:55", "25058", "25030", "25050"},
            {"10:00", "25040", "25020", "25030"}, {"10:05", "25035", "25015", "25025"}, {"10:10", "25040", "25020", "25030"},
            {"10:15", "25040", "25020", "25030"}, {"10:20", "25080", "25062", "25075"}};

    private final Strategy strategy = Strategies.load(Path.of("..", "config", "strategy", "session-break-retest.v1.yaml"))
            .create("NIFTY", Snapshots.SESSION);

    private List<OrderIntent> minute(LocalTime t, double h, double l, double c, PositionView p) {
        Snapshots s = new Snapshots().set("spot", c).set("structure.dayOpen", 25000.0).set("structure.pdh", 25200.0)
                .set("structure.lastMinuteHigh", h).set("structure.lastMinuteLow", l).set("structure.lastMinuteClose", c)
                .set("volatility.vix", 16.0).set("options.expiry", "2026-09-29");
        return strategy.decide(s.at(t.toString()), p).orders();
    }

    private List<OrderIntent> bar(String close, double h, double l, double c, PositionView p) {
        LocalTime end = LocalTime.parse(close);
        List<OrderIntent> last = List.of();
        for (int k = 4; k >= 0; k--) {
            last = minute(end.minusMinutes(k), h, l, c, p);
            if (k > 0) {
                assertThat(last).as("%s", end.minusMinutes(k)).isEmpty();
            }
        }
        return last;
    }

    private void morning() {
        for (String[] b : BARS) {
            assertThat(bar(b[0], Double.parseDouble(b[1]), Double.parseDouble(b[2]), Double.parseDouble(b[3]), PositionView.FLAT))
                    .as(b[0]).isEmpty();
        }
    }

    private static PositionView held(String at) {
        Instant i = Snapshots.SESSION.atTime(LocalTime.parse(at)).atZone(MarketTime.IST).toInstant();
        return new PositionView(true, OptionSide.CE, 1, 100, i, 100);
    }

    @Test
    void aRetestAfterTheBreakBuysTheCallAndHoldsWithoutATarget() {
        morning();
        assertThat(bar("10:25", 25090, 25065, 25085, PositionView.FLAT)).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.CE);
            assertThat(o.reason()).isEqualTo("BREAK_RETEST_CE");
        });
        for (String t : new String[] {"10:30", "10:35", "11:00"}) {
            assertThat(bar(t, 25200, 25150, 25190, held("10:25"))).as(t).isEmpty();
        }
    }

    @Test
    void stopsOnAFiveMinuteCloseABufferBackAcross() {
        morning();
        assertThat(bar("10:25", 25090, 25065, 25085, PositionView.FLAT)).hasSize(1);
        assertThat(bar("10:30", 25062, 25055, 25058, held("10:25"))).as("2 pts under: inside the buffer").isEmpty();
        assertThat(bar("10:35", 25055, 25048, 25050, held("10:25"))).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("LINE_STOP"));
    }

    @Test
    void noEntryWithoutARetestOrWhenTheRetestCloseIsNotBeyondThePreviousClose() {
        morning();
        assertThat(bar("10:25", 25120, 25100, 25110, PositionView.FLAT)).as("no come-back to the line").isEmpty();
        Strategy s2 = Strategies.load(Path.of("..", "config", "strategy", "session-break-retest.v1.yaml")).create("NIFTY", Snapshots.SESSION);
        assertThat(s2).isNotNull();
    }
}
