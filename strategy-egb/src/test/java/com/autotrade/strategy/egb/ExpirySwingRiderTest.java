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

/** esr-v1: the zigzag on one-minute closes, the reversal exit and the entry of the other side. */
class ExpirySwingRiderTest {

    private static final StrategyFactory FACTORY =
            Strategies.load(Path.of("..", "config", "strategy", "expiry-swing-rider.v1.yaml"));

    private static ExpirySwingRiderConfig config() {
        return ExpirySwingRiderConfig.from(com.autotrade.config.ThresholdConfig.load(
                Path.of("..", "config", "strategy", "expiry-swing-rider.v1.yaml")));
    }

    @Test
    void loads() {
        assertThat(FACTORY).isInstanceOf(ExpirySwingRiderFactory.class);
        assertThat(FACTORY.version()).isEqualTo("esr-v1");
        assertThat(FACTORY.premiumBudget()).isEqualTo(500_000);
        assertThat(FACTORY.intendedLots()).isEqualTo(1);
    }

    @Test
    void zigzagReversesOnAQuarterPercentFromTheExtreme() {
        ExpirySwingRider s = new ExpirySwingRider(config());
        assertThat(s.onClose(22000)).isFalse();
        assertThat(s.onClose(22040)).isFalse();                 // +0.18 %
        assertThat(s.onClose(22056)).isTrue();                  // +0.25 % from the day's low: first swing up
        assertThat(s.trend()).isEqualTo(1);
        assertThat(s.onClose(22100)).isFalse();                 // new extreme
        assertThat(s.onClose(22050)).isFalse();                 // -0.23 %
        assertThat(s.onClose(22044)).isTrue();                  // -0.25 % from 22100: down
        assertThat(s.trend()).isEqualTo(-1);
        assertThat(s.onClose(21990)).isFalse();
        assertThat(s.onClose(22045)).isTrue();                  // +0.25 % from 21990: up again
    }

    @Test
    void entersOnTheReversalExitsOnTheNextAndOnlyOnExpiryDay() {
        Strategy s = FACTORY.create("SENSEX", Snapshots.SESSION);
        Snapshots b = Snapshots.bullishCoil();
        assertThat(s.decide(b.set("structure.lastBarClose", 72000.0).at("10:00"), PositionView.FLAT).orders()).isEmpty();
        assertThat(s.decide(b.set("structure.lastBarClose", 72200.0).at("10:01"), PositionView.FLAT).orders())
                .singleElement().satisfies(o -> {
                    assertThat(o.action()).isEqualTo(OrderIntent.Action.ENTER);
                    assertThat(o.side()).isEqualTo(OptionSide.CE);
                });
        PositionView calls = new PositionView(true, OptionSide.CE, 1, 300, null, 310);
        assertThat(s.decide(b.set("structure.lastBarClose", 72250.0).at("10:02"), calls).orders()).isEmpty();
        assertThat(s.decide(b.set("structure.lastBarClose", 72060.0).at("10:03"), calls).orders())
                .singleElement().satisfies(o -> assertThat(o.action()).isEqualTo(OrderIntent.Action.EXIT));
        assertThat(s.decide(b.set("structure.lastBarClose", 72050.0).at("10:03:30"), PositionView.FLAT).orders())
                .singleElement().satisfies(o -> {
                    assertThat(o.action()).isEqualTo(OrderIntent.Action.ENTER);
                    assertThat(o.side()).isEqualTo(OptionSide.PE);
                });

        Strategy notExpiry = FACTORY.create("SENSEX", Snapshots.SESSION);
        Snapshots n = Snapshots.bullishCoil().set("regime.dteTradingDays", 2);
        notExpiry.decide(n.set("structure.lastBarClose", 72000.0).at("10:00"), PositionView.FLAT);
        assertThat(notExpiry.decide(n.set("structure.lastBarClose", 72200.0).at("10:01"), PositionView.FLAT).orders()).isEmpty();
    }
}
