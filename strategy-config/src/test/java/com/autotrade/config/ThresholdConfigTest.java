package com.autotrade.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThresholdConfigTest {

    private static final Path REPO_FILE = Path.of("..", "config", "strategy", "early-confirm-runner.v1.yaml");

    private static final String MINIMAL = """
            version: demo-v1
            strategy: demo
            status: UNCALIBRATED
            runner:
              score_min: 70
              weights: { structure: 60, futures: 40 }
            sizing:
              standard: { early: 0.30, confirm: 0.40, runner: 0.30 }
            windows: [1, 3, 5]
            """;

    @TempDir
    Path dir;

    @Test
    void loadsTheRepositoryDefaults() {
        ThresholdConfig config = ThresholdConfig.load(REPO_FILE);

        assertThat(config.version()).isEqualTo("ecr-v1");
        assertThat(config.status()).isEqualTo(ThresholdStatus.UNCALIBRATED);
        assertThat(config.getDouble("early_entry.distance_to_orh_atr_max.normal")).isEqualTo(0.15);
        assertThat(config.getDouble("early_entry.distance_to_orh_atr_max.expiry")).isEqualTo(0.10);
        assertThat(config.getInt("runner.score_min")).isEqualTo(70);
        assertThat(config.getInt("confirmation.required_confirm_score_by_window.09:25-11:15")).isEqualTo(62);
        assertThat(config.getDoubleList("options_chain.oi_delta_windows_min")).containsExactly(1.0, 3.0, 5.0, 10.0);
        assertThat(config.contentHash()).startsWith("sha256:").hasSize(71);
    }

    @Test
    void hashIgnoresCommentsKeyOrderAndFormatting() throws IOException {
        String reordered = """
                # a comment that must not change the hash
                strategy: demo
                version: demo-v1
                windows:
                  - 1
                  - 3
                  - 5
                sizing: { standard: { runner: 0.3, confirm: 0.4, early: 0.3 } }
                runner: { weights: { futures: 40, structure: 60 }, score_min: 70 }
                status: UNCALIBRATED
                """;
        ThresholdConfig a = ThresholdConfig.load(write("demo.v1.yaml", MINIMAL));
        ThresholdConfig b = ThresholdConfig.load(write("demo.v1.yml", reordered));

        assertThat(b.contentHash()).isEqualTo(a.contentHash());
    }

    @Test
    void anyValueChangeChangesTheHash() throws IOException {
        ThresholdConfig a = ThresholdConfig.load(write("demo.v1.yaml", MINIMAL));
        ThresholdConfig b = ThresholdConfig.load(write("demo.v1.yml", MINIMAL.replace("score_min: 70", "score_min: 71")));

        assertThat(b.contentHash()).isNotEqualTo(a.contentHash());
    }

    @Test
    void rejectsWeightsThatDoNotSumTo100() throws IOException {
        Path file = write("demo.v1.yaml", MINIMAL.replace("futures: 40", "futures: 35"));

        assertThatThrownBy(() -> ThresholdConfig.load(file))
                .isInstanceOf(ThresholdConfigException.class)
                .hasMessageContaining("runner.weights weights sum to 95.0");
    }

    @Test
    void rejectsSizingThatDoesNotSumToOne() throws IOException {
        Path file = write("demo.v1.yaml", MINIMAL.replace("runner: 0.30", "runner: 0.20"));

        assertThatThrownBy(() -> ThresholdConfig.load(file)).hasMessageContaining("sizing.standard fractions sum");
    }

    @Test
    void rejectsFileNameThatDoesNotMatchVersion() throws IOException {
        Path file = write("demo.v2.yaml", MINIMAL);

        assertThatThrownBy(() -> ThresholdConfig.load(file)).hasMessageContaining("version suffix .v1");
    }

    @Test
    void rejectsUnknownStatus() throws IOException {
        Path file = write("demo.v1.yaml", MINIMAL.replace("UNCALIBRATED", "PRODUCTION"));

        assertThatThrownBy(() -> ThresholdConfig.load(file)).hasMessageContaining("status must be one of");
    }

    @Test
    void rejectsDuplicateKeys() throws IOException {
        Path file = write("demo.v1.yaml", MINIMAL + "windows: [2]\n");

        assertThatThrownBy(() -> ThresholdConfig.load(file)).isInstanceOf(ThresholdConfigException.class);
    }

    @Test
    void reportsMissingKeysByPath() throws IOException {
        ThresholdConfig config = ThresholdConfig.load(write("demo.v1.yaml", MINIMAL));

        assertThatThrownBy(() -> config.getDouble("runner.nope")).hasMessageContaining("missing key: runner.nope");
        assertThatThrownBy(() -> config.getBoolean("runner.score_min")).hasMessageContaining("not a boolean");
    }

    private Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
