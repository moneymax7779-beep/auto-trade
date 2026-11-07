package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** trr-v1 (A-227): etr-v1's rules on the index that does not expire today (1-5 days to expiry), under its own id. */
class TrendRiderTest {

    private static final StrategyFactory FACTORY =
            Strategies.load(Path.of("..", "config", "strategy", "trend-rider.v1.yaml"));
    private static final StrategyFactory ETR =
            Strategies.load(Path.of("..", "config", "strategy", "expiry-trend-rider.v1.yaml"));

    /** 21 Oct-like: below the opening-range low, breadth -80, short build, IV rising; {@code dte} trading days to expiry. */
    private static Snapshots falling(double spot, double dayLow, int dte) {
        return new Snapshots()
                .set("spot", spot)
                .set("regime.dteTradingDays", dte)
                .set("structure.orComplete", true)
                .set("structure.orHigh", 23086.55)
                .set("structure.orLow", 22981.35)
                .set("structure.dayHigh", 23174.5)
                .set("structure.dayLow", dayLow)
                .set("structure.lastBarClose", spot)
                .set("structure.lastSwingHigh", 22990.0)
                .set("structure.lastSwingLow", dayLow)
                .set("breadth.moverBreadth", -80.0)
                .set("futures.oiState", "FRESH_SHORT")
                .set("options.atmIvChange3m", 0.01);
    }

    private static boolean enters(Strategy s, int dte) {
        s.decide(falling(22900, 22890, dte).at("12:40"), PositionView.FLAT);
        return !s.decide(falling(22850, 22840, dte).at("12:41"), PositionView.FLAT).orders().isEmpty();
    }

    @Test
    void loadsUnderItsOwnIdWithHalfOfEtrsSize() {
        assertThat(FACTORY).isInstanceOf(ExpiryTrendRiderFactory.class);
        assertThat(FACTORY.id()).isEqualTo("trend-rider");
        assertThat(FACTORY.version()).isEqualTo("trr-v1");
        assertThat(FACTORY.intendedLots()).isEqualTo(2);
        assertThat(FACTORY.premiumBudget()).isEqualTo(250_000);
        assertThat(FACTORY.premiumStopPct()).isEqualTo(ETR.premiumStopPct());
        assertThat(ETR.id()).as("etr keeps its id, so both can run in one session").isEqualTo("expiry-trend-rider");
    }

    @Test
    void tradesOneToFiveDaysBeforeExpiryAndNeverOnTheExpiryDay() {
        assertThat(enters(FACTORY.create("NIFTY", Snapshots.SESSION), 0)).as("expiry day belongs to etr").isFalse();
        assertThat(enters(FACTORY.create("NIFTY", Snapshots.SESSION), 1)).isTrue();
        assertThat(enters(FACTORY.create("NIFTY", Snapshots.SESSION), 4)).as("21 Oct: NIFTY 4 days from 27 Oct").isTrue();
        assertThat(enters(FACTORY.create("NIFTY", Snapshots.SESSION), 5)).isTrue();
        assertThat(enters(FACTORY.create("NIFTY", Snapshots.SESSION), 6)).isFalse();
        assertThat(enters(ETR.create("NIFTY", Snapshots.SESSION), 0)).as("etr-v1 unchanged").isTrue();
        assertThat(enters(ETR.create("NIFTY", Snapshots.SESSION), 1)).as("etr-v1 unchanged").isFalse();
    }

    @Test
    void entersThePutOnANewDayLowAddsOnceAndExitsOnTheSwing() {
        Strategy s = FACTORY.create("NIFTY", Snapshots.SESSION);
        s.decide(falling(22900, 22890, 4).at("12:40"), PositionView.FLAT);
        assertThat(s.decide(falling(22850, 22840, 4).at("12:41"), PositionView.FLAT).orders()).singleElement().satisfies(o -> {
            assertThat(o.action()).isEqualTo(OrderIntent.Action.ENTER);
            assertThat(o.side()).isEqualTo(OptionSide.PE);
            assertThat(o.reason()).isEqualTo("NEW_DAY_LOW");
        });
        PositionView puts = new PositionView(true, OptionSide.PE, 1, 100, null, 105);
        assertThat(s.decide(falling(22800, 22790, 4).at("12:45"), puts).orders()).singleElement()
                .extracting(OrderIntent::action).isEqualTo(OrderIntent.Action.ADD);
        assertThat(s.decide(falling(22750, 22740, 4).at("12:50"), puts).orders()).as("max_adds 1").isEmpty();
        assertThat(s.decide(falling(23000, 22740, 4).set("structure.lastBarClose", 23000.0).at("12:55"), puts).orders())
                .singleElement().extracting(OrderIntent::reason).isEqualTo("SWING_HIGH_RECLAIMED");
    }

    @Test
    void v2StandsAsideOnADayAnotherIndexExpiresAndTradesOtherwise() {
        StrategyFactory v2 = Strategies.load(Path.of("..", "config", "strategy", "trend-rider.v2.yaml"));
        assertThat(v2.id()).isEqualTo("trend-rider");
        assertThat(v2.version()).isEqualTo("trr-v2");

        Strategy onSensexExpiry = v2.create("NIFTY", Snapshots.SESSION);
        onSensexExpiry.observeOther(new Snapshots().set("underlying", "SENSEX").set("regime.dteTradingDays", 0)
                .set("regime.expiry", "2026-10-22").at("09:16"));
        assertThat(enters(onSensexExpiry, 3)).as("8 Oct-like: SENSEX expires, NIFTY 3 days out").isFalse();

        Strategy ordinaryDay = v2.create("NIFTY", Snapshots.SESSION);
        ordinaryDay.observeOther(new Snapshots().set("underlying", "SENSEX").set("regime.dteTradingDays", 1)
                .set("regime.expiry", "2026-10-22").at("09:16"));
        assertThat(enters(ordinaryDay, 4)).as("21 Oct-like: neither index expires").isTrue();

        Strategy unknownRegime = v2.create("NIFTY", Snapshots.SESSION);
        unknownRegime.observeOther(new Snapshots().set("underlying", "SENSEX").at("09:16"));   // dte 0 but no expiry: unknown
        assertThat(enters(unknownRegime, 4)).as("an empty regime is not an expiry").isTrue();

        Strategy v1 = FACTORY.create("NIFTY", Snapshots.SESSION);
        v1.observeOther(new Snapshots().set("underlying", "SENSEX").set("regime.dteTradingDays", 0)
                .set("regime.expiry", "2026-10-22").at("09:16"));
        assertThat(enters(v1, 3)).as("trr-v1 ignores the other index").isTrue();
    }
}
