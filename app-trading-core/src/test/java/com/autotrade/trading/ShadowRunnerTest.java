package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** A failing shadow file is reported and the files after it still run (one bad file must not cost the others their day). */
class ShadowRunnerTest {

    @Test
    void aFailingFileIsReportedAndTheRestStillRun() {
        TradingProperties.Trading trading = new TradingProperties.Trading("P", List.of("NIFTY"), "base", null, null, null, null,
                null, null, 250, 3000, "09:00", "15:45", "upstox", 0, null, "own", null, null, null);
        ShadowRunner runner = new ShadowRunner(new TradingProperties(trading, null, null, null), null);

        ShadowRunner.Outcome outcome = runner.run(LocalDate.of(2026, 10, 23),
                List.of("config/strategy/missing-a.v1.yaml", "config/strategy/missing-b.v1.yaml"));

        // both files were tried (the first failure did not stop the second) and both are reported by name
        assertThat(outcome.failed()).hasSize(2);
        assertThat(outcome.failed().get(0)).startsWith("missing-a.v1.yaml: ");
        assertThat(outcome.failed().get(1)).startsWith("missing-b.v1.yaml: ");
        assertThat(outcome.summary()).isNull();
    }

    @Test
    void noFilesIsNothingToDo() {
        TradingProperties.Trading trading = new TradingProperties.Trading("P", List.of("NIFTY"), "base", null, null, null, null,
                null, null, 250, 3000, "09:00", "15:45", "upstox", 0, null, "own", null, null, null);
        ShadowRunner.Outcome outcome = new ShadowRunner(new TradingProperties(trading, null, null, null), null)
                .run(LocalDate.of(2026, 10, 23), List.of());
        assertThat(outcome.summary()).isNull();
        assertThat(outcome.failed()).isEmpty();
    }
}
