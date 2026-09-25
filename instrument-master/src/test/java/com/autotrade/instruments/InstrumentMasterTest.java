package com.autotrade.instruments;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class InstrumentMasterTest {

    private static final Path SAMPLE = Path.of("src", "test", "resources", "instruments-sample.json");

    @Test
    void readsIndexDerivativesOnlyAndConvertsUnits() throws Exception {
        List<Instrument> instruments = UpstoxInstrumentFile.read(SAMPLE, Set.of("NIFTY", "SENSEX"));

        assertThat(instruments).hasSize(4); // stock future and the VIX index are not index derivatives
        Instrument ce = instruments.getFirst();
        assertThat(ce.expiry()).isEqualTo(LocalDate.of(2026, 9, 29)); // 23:59:59 IST, not the UTC date
        assertThat(ce.tickSize()).isEqualTo(0.05); // paise -> rupees
        assertThat(ce.lotSize()).isEqualTo(65);
        assertThat(ce.maxLotsPerOrder()).isEqualTo(27);
    }

    @Test
    void answersExpiryStrikeAndContractLookups() throws Exception {
        InstrumentMaster master = new InstrumentMaster(UpstoxInstrumentFile.read(SAMPLE, Set.of("NIFTY")));
        LocalDate session = LocalDate.of(2026, 9, 25);

        assertThat(master.optionExpiries("NIFTY", session))
                .containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 6));
        assertThat(master.nearestOptionExpiry("NIFTY", LocalDate.of(2026, 9, 30))).contains(LocalDate.of(2026, 10, 6));
        assertThat(master.strikeStep("NIFTY", LocalDate.of(2026, 9, 29))).isEqualTo(50);
        assertThat(master.option("NIFTY", LocalDate.of(2026, 9, 29), 23150, "CE"))
                .get().extracting(Instrument::instrumentKey).isEqualTo("NSE_FO|2");
        assertThat(master.nearestFuture("NIFTY", session)).get().extracting(Instrument::tickSize).isEqualTo(0.10);
    }

    @Test
    void roundsPricesToTheTick() throws Exception {
        Instrument ce = UpstoxInstrumentFile.read(SAMPLE, Set.of("NIFTY")).getFirst();

        assertThat(ce.roundToTick(142.33, 1)).isEqualTo(142.35);
        assertThat(ce.roundToTick(142.33, -1)).isEqualTo(142.30);
        assertThat(ce.roundToTick(142.30, 1)).isEqualTo(142.30);
    }
}
