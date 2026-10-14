package com.autotrade.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;

/** risk v16 (A-101): one release of the daily-loss switch a day, raising the limit by 10 % of equity. */
class OperatorReleaseTest {

    private static RiskEngine engine(String file, double equity) {
        RiskLimits limits = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", file))).withEquity(equity);
        return new RiskEngine(limits, new KillSwitch((scope, e) -> { }));
    }

    @Test
    void v16AllowsOneReleaseThatRaisesTheLimitByTenPercentOfEquity() {
        RiskEngine v16 = engine("paper-risk.v16.yaml", 1_069_295);        // 14 Oct equity
        assertThat(v16.dailyLossLimit()).isCloseTo(235_244.9, within(0.01)); // 22 %
        assertThat(v16.dailyLossReleasesLeft()).isEqualTo(1);
        assertThat(v16.dailyLossLimitAfterRelease()).isCloseTo(342_174.4, within(0.01));
        assertThat(v16.limits().dailyLossAutoRelease()).isTrue();
        assertThat(v16.releaseDailyLoss()).isTrue();
        assertThat(v16.dailyLossLimit()).isCloseTo(342_174.4, within(0.01)); // 32 %
        assertThat(v16.dailyLossReleasesLeft()).isZero();
        assertThat(v16.releaseDailyLoss()).isFalse();                        // the second breach stays locked
        assertThat(v16.dailyLossLimit()).isCloseTo(342_174.4, within(0.01));
    }

    @Test
    void v12ReleasesWithoutRaisingTheLimit() {
        RiskEngine v12 = engine("paper-risk.v12.yaml", 1_069_295);
        assertThat(v12.dailyLossReleasesLeft()).isZero();
        assertThat(v12.limits().dailyLossAutoRelease()).isFalse();
        assertThat(v12.releaseDailyLoss()).isTrue();
        assertThat(v12.dailyLossLimit()).isCloseTo(235_244.9, within(0.01));
    }
}
