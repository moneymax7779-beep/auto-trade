package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.autotrade.account.BrokerType;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.sim.CostModel;

/** The per-broker trading-day rules (16 Nov 2026, multi-broker gaps A1 and A3) and their checks at load. */
class BrokerRulesTest {

    private static final Path V1 = Path.of("..", "config", "brokers", "broker-rules.v1.yaml");
    private static final Path COSTS_V2 = Path.of("..", "config", "costs", "india-index-options-costs.v2.yaml")
            .toAbsolutePath().normalize();
    private static final DataSource NO_DB = new DriverManagerDataSource();

    @TempDir
    Path dir;

    @Test
    void zerodhaKeepsExactlyTheValuesTheLiveAccountTradesUnder() {
        BrokerRules rules = BrokerRules.load(V1);
        BrokerRules.Rule z = rules.rule(BrokerType.ZERODHA);
        assertThat(z.product()).isEqualTo("MIS");
        assertThat(z.lastLiveEntry()).isEqualTo(LocalTime.of(15, 10));
        assertThat(z.squareOff()).isEqualTo(LocalTime.of(15, 20));
        assertThat(z.costsFile()).isEqualTo("config/costs/india-index-options-costs.v2.yaml");
        assertThat(z.costs().configHash()).as("the costs v2 the worker charged with before")
                .isEqualTo(CostModel.from(ThresholdConfig.load(COSTS_V2)).configHash());
    }

    @Test
    void everyBrokerHasItsOwnProductAndTimesWithRoomBeforeTheBrokersCutOff() {
        BrokerRules rules = BrokerRules.load(V1);
        assertThat(rules.rules()).containsOnlyKeys(BrokerType.values());
        assertThat(rules.rule(BrokerType.UPSTOX).product()).isEqualTo("I");
        assertThat(rules.rule(BrokerType.KOTAK_NEO).product()).isEqualTo("MIS");
        assertThat(rules.rule(BrokerType.GROWW).product()).isEqualTo("MIS");
        assertThat(rules.rule(BrokerType.KOTAK_NEO).brokerTimeConfirmed()).as("Kotak's own pages disagree").isFalse();
        for (BrokerType b : List.of(BrokerType.ZERODHA, BrokerType.UPSTOX, BrokerType.KOTAK_NEO, BrokerType.GROWW)) {
            BrokerRules.Rule r = rules.rule(b);
            assertThat(r.squareOff().plus(BrokerRules.MIN_MARGIN)).as(b + "").isBeforeOrEqualTo(r.brokerAutoSquareOff());
            assertThat(r.lastLiveEntry()).as(b + "").isBefore(r.squareOff());
        }
        assertThat(rules.rule(BrokerType.PAPER).product()).isNull();
        assertThat(rules.lastLiveEntry(BrokerType.PAPER)).as("PAPER is never LIVE").isNull();
        assertThat(rules.squareOffDue(LocalTime.of(15, 14, 59))).isEmpty();
        assertThat(rules.squareOffDue(LocalTime.of(15, 15))).containsExactlyInAnyOrder(BrokerType.KOTAK_NEO,
                BrokerType.GROWW);
        assertThat(rules.squareOffDue(LocalTime.of(15, 20))).containsExactlyInAnyOrder(BrokerType.values());
    }

    @Test
    void theAdaptersSendTheProductsTheRulesName() {
        BrokerRules rules = BrokerRules.load(V1);
        List<BrokerAdapter> adapters = List.of(new ZerodhaDryRunAdapter(NO_DB, 1e9, BrokerSessions.NONE,
                com.autotrade.zerodha.KiteConfig.REAL_API, rules.rule(BrokerType.ZERODHA).product()),
                new KotakDryRunAdapter(NO_DB, 1e9), new UpstoxDryRunAdapter(NO_DB, 1e9),
                new GrowwDryRunAdapter(NO_DB, 1e9));
        rules.requireProducts(adapters);
        assertThat(new ZerodhaDryRunAdapter(NO_DB, 1e9).product()).as("without rules: Kite's own default").isEqualTo("MIS");
        assertThatThrownBy(() -> rules.requireProducts(List.of(new ZerodhaDryRunAdapter(NO_DB, 1e9, BrokerSessions.NONE,
                com.autotrade.zerodha.KiteConfig.REAL_API, "NRML")))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ZERODHA sends NRML");
    }

    @Test
    void aSquareOffLessThanFiveMinutesBeforeTheBrokersIsRefusedAtLoad() throws IOException {
        Path file = write(rule("ZERODHA", "MIS", "15:10", "15:22", "15:26"));
        assertThatThrownBy(() -> BrokerRules.load(file)).hasMessageContaining("ZERODHA")
                .hasMessageContaining("at least 5 minutes before the broker's auto square-off 15:26");
        BrokerRules.load(write(rule("ZERODHA", "MIS", "15:10", "15:21", "15:26")));       // 5 minutes: allowed
    }

    @Test
    void otherMistakesAreRefusedAtLoad() throws IOException {
        Path late = write(rule("UPSTOX", "I", "15:20", "15:20", "15:25"));
        assertThatThrownBy(() -> BrokerRules.load(late)).hasMessageContaining("last_live_entry");
        Path outside = write(rule("GROWW", "MIS", "14:30", "14:50", "15:20"));
        assertThatThrownBy(() -> BrokerRules.load(outside)).hasMessageContaining("outside 15:00-15:30");
        Path noProduct = write(rule("KOTAK_NEO", null, "15:05", "15:15", "15:20"));
        assertThatThrownBy(() -> BrokerRules.load(noProduct)).hasMessageContaining("product");
        // review 16 Nov 2026: the Kite adapter takes its product from here; a carry product was accepted
        Path nrml = write(rule("ZERODHA", "NRML", "15:10", "15:20", "15:26"));
        assertThatThrownBy(() -> BrokerRules.load(nrml)).hasMessageContaining("product NRML is not ZERODHA's intraday "
                + "product MIS");
        Path upstoxD = write(rule("UPSTOX", "D", "15:10", "15:20", "15:25"));
        assertThatThrownBy(() -> BrokerRules.load(upstoxD)).hasMessageContaining("intraday product I");
        Path missing = write("""
                  ZERODHA: {product: MIS, last_live_entry: "15:10", square_off: "15:20", broker_auto_square_off: "15:26",
                            broker_auto_square_off_status: CONFIRMED, costs_file: "%s"}
                """.formatted(COSTS_V2));
        assertThatThrownBy(() -> BrokerRules.load(missing)).hasMessageContaining("no rules for");
        Path status = write(rule("GROWW", "MIS", "15:05", "15:15", "15:20").replace("CONFIRMED", "MAYBE"));
        assertThatThrownBy(() -> BrokerRules.load(status)).hasMessageContaining("CONFIRMED or UNCONFIRMED");
    }

    /** A rules file with {@code broker}'s line as given and the v1 values for the others. */
    private String rule(String broker, String product, String lastEntry, String squareOff, String brokers) {
        StringBuilder b = new StringBuilder();
        for (String[] r : List.of(new String[] {"ZERODHA", "MIS", "15:10", "15:20", "15:26"},
                new String[] {"UPSTOX", "I", "15:10", "15:20", "15:25"},
                new String[] {"KOTAK_NEO", "MIS", "15:05", "15:15", "15:20"},
                new String[] {"GROWW", "MIS", "15:05", "15:15", "15:20"})) {
            String[] v = r[0].equals(broker) ? new String[] {broker, product, lastEntry, squareOff, brokers} : r;
            b.append("  ").append(v[0]).append(": {").append(v[1] == null ? "" : "product: " + v[1] + ", ")
                    .append("last_live_entry: \"").append(v[2]).append("\", square_off: \"").append(v[3])
                    .append("\", broker_auto_square_off: \"").append(v[4])
                    .append("\", broker_auto_square_off_status: CONFIRMED, costs_file: \"").append(COSTS_V2)
                    .append("\"}\n");
        }
        b.append("  PAPER: {square_off: \"15:20\", costs_file: \"").append(COSTS_V2).append("\"}\n");
        return b.toString();
    }

    private Path write(String brokers) throws IOException {
        Path file = Files.createDirectories(dir.resolve("config").resolve("brokers")).resolve("broker-rules.v1.yaml");
        Files.writeString(file, "version: broker-rules-v1\nname: broker-rules\nstatus: UNCALIBRATED\nbrokers:\n" + brokers);
        return file;
    }
}
