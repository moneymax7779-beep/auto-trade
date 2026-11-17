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
 * A-430: sbr-v2's exit at a rejection of the next level, on the synthetic day also run through .local/a429/exits.py (X2):
 * CE at 10:25 (close 25,085); levels at entry 24,990 / 25,000 / 25,200 (PDH), target level 25,200 (>= 37.8 beyond).
 * A: the 10:26 minute reaches 25,195 (within 12.6: armed), the 10:35 close 25,165 is >= 25.2 back from 25,195: LEVEL_REJECT.
 * C: the 10:35 close 25,215 crosses 25,200 (> 7.56 beyond); no level beyond: held, no exit on the later pullback.
 */
class SessionBreakRetestV2Test {

    private static final String[][] BARS = {{"09:20", "25020", "24990", "25000"}, {"09:25", "25030", "25000", "25020"},
            {"09:30", "25060", "25020", "25040"}, {"09:35", "25040", "25010", "25020"}, {"09:40", "25030", "25000", "25010"},
            {"09:45", "25020", "24995", "25005"}, {"09:50", "25030", "25005", "25025"}, {"09:55", "25058", "25030", "25050"},
            {"10:00", "25040", "25020", "25030"}, {"10:05", "25035", "25015", "25025"}, {"10:10", "25040", "25020", "25030"},
            {"10:15", "25040", "25020", "25030"}, {"10:20", "25080", "25062", "25075"}};

    private final Strategy strategy = Strategies.load(Path.of("..", "config", "strategy", "session-break-retest.v2.yaml"))
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

    private PositionView enter() {
        for (String[] b : BARS) {
            assertThat(bar(b[0], Double.parseDouble(b[1]), Double.parseDouble(b[2]), Double.parseDouble(b[3]), PositionView.FLAT))
                    .as(b[0]).isEmpty();
        }
        assertThat(bar("10:25", 25090, 25065, 25085, PositionView.FLAT)).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("BREAK_RETEST_CE"));
        Instant i = Snapshots.SESSION.atTime(LocalTime.of(10, 25)).atZone(MarketTime.IST).toInstant();
        return new PositionView(true, OptionSide.CE, 1, 100, i, 100);
    }

    @Test
    void exitsOnARejectionOfTheNextLevel() {
        PositionView p = enter();
        assertThat(bar("10:30", 25195, 25185, 25190, p)).as("armed, 5 pts back").isEmpty();
        assertThat(bar("10:35", 25170, 25160, 25165, p)).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("LEVEL_REJECT"));
    }

    @Test
    void aCrossedLevelWithNothingBeyondKeepsTheRunner() {
        PositionView p = enter();
        assertThat(bar("10:30", 25195, 25185, 25190, p)).isEmpty();
        assertThat(bar("10:35", 25220, 25205, 25215, p)).as("crossed").isEmpty();
        assertThat(bar("10:40", 25190, 25180, 25185, p)).as("no level left: held").isEmpty();
    }
}
