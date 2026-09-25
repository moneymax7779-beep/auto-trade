package com.autotrade.strategy.ecr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Stage;
import com.autotrade.strategy.Strategy;

class EarlyConfirmRunnerStrategyTest {

    private static final ThresholdConfig FILE =
            ThresholdConfig.load(Path.of("..", "config", "strategy", "early-confirm-runner.v2.yaml"));
    private static final EcrConfig CONFIG = EcrConfig.from(FILE);

    private final Strategy strategy = new EarlyConfirmRunnerFactory(FILE).create("NIFTY", Snapshots.SESSION);

    @Test
    void tranchesFollowTheSizingProfile() {
        assertThat(CONFIG.tranches(false)).containsExactly(1, 2, 1); // 30/40/30 of 4 lots
        assertThat(CONFIG.tranches(true)).containsExactly(1, 2, 1); // expiry early 25%
        assertThat(CONFIG.requiredConfirmScore(LocalTime.of(10, 0))).isEqualTo(62);
        assertThat(CONFIG.requiredConfirmScore(LocalTime.of(12, 0))).isEqualTo(70);
    }

    @Test
    void entersTheEarlyProbeOnlyWhenEveryEarlyConditionHolds() {
        Decision decision = strategy.decide(Snapshots.bullishEarly().at("10:30"), PositionView.FLAT);

        assertThat(decision.ce().earlyScore()).isEqualTo(100);
        assertThat(decision.ce().stage()).isEqualTo(Stage.EARLY_ENTRY);
        assertThat(decision.orders()).singleElement().satisfies(order -> {
            assertThat(order.action()).isEqualTo(OrderIntent.Action.ENTER);
            assertThat(order.side()).isEqualTo(OptionSide.CE);
            assertThat(order.lots()).isEqualTo(1);
        });
        assertThat(decision.pe().stage()).isEqualTo(Stage.IDLE);
    }

    @Test
    void oneFailedConditionMeansArmedNotEntered() {
        Decision decision = strategy.decide(Snapshots.bullishEarly().set("breadth.momentumBreadth", 40.0).at("10:30"),
                PositionView.FLAT);

        assertThat(decision.ce().earlyScore()).isEqualTo(90);
        assertThat(decision.ce().stage()).isEqualTo(Stage.ARMED);
        assertThat(decision.orders()).isEmpty();
    }

    @Test
    void addsTheConfirmTrancheOnABreakoutThenExitsAtFlatBy() {
        strategy.decide(Snapshots.bullishEarly().at("10:30"), PositionView.FLAT);
        PositionView held = new PositionView(true, OptionSide.CE, 1, 100, null);

        Decision confirm = strategy.decide(Snapshots.bullishEarly().confirmedBreakout().at("10:33"), held);
        assertThat(confirm.ce().conditions()).containsEntry("broke_level", true).containsEntry("rvol_confirms", true)
                .containsEntry("breakout_bar", true);
        assertThat(confirm.ce().stage()).isEqualTo(Stage.CONFIRMED);
        assertThat(confirm.orders()).singleElement().extracting(OrderIntent::lots).isEqualTo(2);

        Decision flat = strategy.decide(Snapshots.bullishEarly().confirmedBreakout().at("15:15"),
                new PositionView(true, OptionSide.CE, 3, 100, null));
        assertThat(flat.orders()).singleElement().satisfies(order -> {
            assertThat(order.action()).isEqualTo(OrderIntent.Action.EXIT);
            assertThat(order.reason()).isEqualTo("FLAT_BY");
        });
    }

    @Test
    void closesAProbeThatIsNotConfirmedInTime() {
        strategy.decide(Snapshots.bullishEarly().at("10:30"), PositionView.FLAT);
        PositionView held = new PositionView(true, OptionSide.CE, 1, 100, null);

        assertThat(strategy.decide(Snapshots.bullishEarly().at("10:35"), held).orders()).isEmpty();
        Decision timeout = strategy.decide(Snapshots.bullishEarly().at("10:39"), held);

        assertThat(timeout.orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("PROBE_TIMEOUT");
        assertThat(timeout.ce().stage()).isEqualTo(Stage.EXITED);
    }

    @Test
    void neverReentersASideTheSameDay() {
        strategy.decide(Snapshots.bullishEarly().at("10:30"), PositionView.FLAT);
        strategy.decide(Snapshots.bullishEarly().at("10:39"), new PositionView(true, OptionSide.CE, 1, 100, null));

        Decision later = strategy.decide(Snapshots.bullishEarly().at("11:30"), PositionView.FLAT);

        assertThat(later.orders()).isEmpty();
        assertThat(later.ce().stage()).isEqualTo(Stage.EXITED);
    }

    @Test
    void noNewEntriesBeforeTheEarliestEntryTime() {
        Decision decision = strategy.decide(Snapshots.bullishEarly().at("09:28"), PositionView.FLAT);

        assertThat(decision.orders()).isEmpty();
    }
}
