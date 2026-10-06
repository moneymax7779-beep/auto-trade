package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

class TrendPullbackScalperTest {

    private static final StrategyFactory FACTORY =
            Strategies.load(Path.of("..", "config", "strategy", "trend-pullback-scalper.v1.yaml"));

    private final Strategy strategy = FACTORY.create("NIFTY", Snapshots.SESSION);
    private double dayHigh = Double.NaN;
    private double dayLow = Double.NaN;

    /** One minute: the index, VWAP 22,600, ATR(3m) 20; the day high / low follow the closes. */
    private Decision minute(String t, double spot, PositionView position, int dte) {
        dayHigh = Double.isNaN(dayHigh) ? spot : Math.max(dayHigh, spot);
        dayLow = Double.isNaN(dayLow) ? spot : Math.min(dayLow, spot);
        return strategy.decide(new Snapshots().set("spot", spot).set("structure.dayHigh", dayHigh)
                .set("structure.dayLow", dayLow).set("structure.vwapSpotProxy", 22600.0).set("structure.atr3m", 20.0)
                .set("regime.dteTradingDays", dte).at(t), position);
    }

    /** 09:30-10:09: a steady climb from 22,600 to 22,678 (EMA20 rising, new highs). */
    private LocalTime climb(int dte) {
        LocalTime t = LocalTime.of(9, 30);
        for (int i = 0; i < 40; i++, t = t.plusMinutes(1)) {
            minute(t.toString(), 22600 + i * 2.0, PositionView.FLAT, dte);
        }
        return t;
    }

    @Test
    void loads() {
        assertThat(FACTORY).isInstanceOf(TrendPullbackScalperFactory.class);
        assertThat(FACTORY.version()).isEqualTo("tps-v1");
        assertThat(FACTORY.premiumBudget()).isEqualTo(500_000);
    }

    @Test
    void buysTheCallOnTheTurnAfterAPullbackThenTakesTheTarget() {
        climb(0);                                                     // high 22,678 at 10:09
        assertThat(minute("10:10", 22670, PositionView.FLAT, 0).orders()).isEmpty();
        assertThat(minute("10:11", 22660, PositionView.FLAT, 0).orders()).isEmpty();   // pullback 18 = 0.9 ATR
        assertThat(minute("10:12", 22662, PositionView.FLAT, 0).orders()).as("not yet 0.3 ATR off the low").isEmpty();
        Decision entry = minute("10:13", 22667, PositionView.FLAT, 0);
        assertThat(entry.orders()).singleElement().satisfies(o -> {
            assertThat(o.action()).isEqualTo(OrderIntent.Action.ENTER);
            assertThat(o.side()).isEqualTo(OptionSide.CE);
            assertThat(o.premiumStopPct()).as("expiring index").isEqualTo(10);
        });
        PositionView holding = new PositionView(true, OptionSide.CE, 1, 100, null, 112);
        assertThat(minute("10:14", 22669, holding, 0).orders()).as("+12 %, target 15 % on expiry").isEmpty();
        assertThat(minute("10:15", 22672, new PositionView(true, OptionSide.CE, 1, 100, null, 115.5), 0).orders())
                .singleElement().extracting(OrderIntent::reason).isEqualTo("TARGET_15");
    }

    @Test
    void stopsBelowThePullbackLowAndTimesOut() {
        climb(2);
        minute("10:10", 22670, PositionView.FLAT, 2);
        minute("10:11", 22660, PositionView.FLAT, 2);
        minute("10:12", 22662, PositionView.FLAT, 2);
        assertThat(minute("10:13", 22667, PositionView.FLAT, 2).orders()).hasSize(1);
        PositionView holding = new PositionView(true, OptionSide.CE, 1, 100, null, 101);
        assertThat(minute("10:14", 22659, holding, 2).orders()).singleElement()
                .extracting(OrderIntent::reason).isEqualTo("BELOW_PULLBACK_LOW");

        TrendPullbackScalperTest t = new TrendPullbackScalperTest();
        t.climb(2);
        t.minute("10:10", 22670, PositionView.FLAT, 2);
        t.minute("10:11", 22660, PositionView.FLAT, 2);
        t.minute("10:12", 22662, PositionView.FLAT, 2);
        assertThat(t.minute("10:13", 22667, PositionView.FLAT, 2).orders()).hasSize(1);
        assertThat(t.minute("10:27", 22668, holding, 2).orders()).as("14 minutes").isEmpty();
        assertThat(t.minute("10:28", 22668, holding, 2).orders()).singleElement()
                .extracting(OrderIntent::reason).isEqualTo("TIME_15");
    }

    @Test
    void noEntryWhenThePullbackIsTooShallowOrBreaksBelowTheVwap() {
        climb(2);
        minute("10:10", 22673, PositionView.FLAT, 2);               // 5 pts = 0.25 ATR: too shallow
        assertThat(minute("10:11", 22677, PositionView.FLAT, 2).orders()).isEmpty();

        TrendPullbackScalperTest deep = new TrendPullbackScalperTest();
        deep.climb(2);
        deep.minute("10:10", 22630, PositionView.FLAT, 2);
        deep.minute("10:11", 22595, PositionView.FLAT, 2);           // below the VWAP 22,600
        assertThat(deep.minute("10:12", 22610, PositionView.FLAT, 2).orders()).isEmpty();
        assertThat(deep.minute("10:13", 22620, PositionView.FLAT, 2).orders()).isEmpty();
    }

    @Test
    void mirrorsForPutsInADownTrend() {
        LocalTime t = LocalTime.of(9, 30);
        for (int i = 0; i < 40; i++, t = t.plusMinutes(1)) {
            minute(t.toString(), 22780 - i * 2.0, PositionView.FLAT, 2);     // falling to 22,702, above VWAP 22,600?
        }
        // below the VWAP is required for puts: this fall stays above 22,600, so nothing
        minute("10:10", 22710, PositionView.FLAT, 2);
        minute("10:11", 22720, PositionView.FLAT, 2);
        assertThat(minute("10:13", 22712, PositionView.FLAT, 2).orders()).isEmpty();

        TrendPullbackScalperTest below = new TrendPullbackScalperTest();
        LocalTime u = LocalTime.of(9, 30);
        for (int i = 0; i < 40; i++, u = u.plusMinutes(1)) {
            below.minute(u.toString(), 22580 - i * 2.0, PositionView.FLAT, 2);   // falling to 22,502, below VWAP
        }
        below.minute("10:10", 22510, PositionView.FLAT, 2);
        below.minute("10:11", 22520, PositionView.FLAT, 2);           // rally 18 = 0.9 ATR, still below VWAP
        assertThat(below.minute("10:12", 22508, PositionView.FLAT, 2).orders()).singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.PE));
    }
}
