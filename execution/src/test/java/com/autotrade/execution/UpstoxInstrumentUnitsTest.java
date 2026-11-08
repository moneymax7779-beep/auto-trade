package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.autotrade.instruments.Instrument;
import com.autotrade.instruments.UpstoxInstrumentFile;
import com.autotrade.upstox.orders.UpstoxInstruments;
import com.autotrade.upstox.orders.UpstoxSimulator;

/**
 * The order adapter's reading of Upstox's instrument file and ref.instrument's loader ({@link UpstoxInstrumentFile}) must
 * agree on every unit the mapping check compares (tick in paise, expiry in epoch milliseconds IST, strike, lot, type):
 * otherwise the check would block every contract, or, worse, two wrong readings would agree. Both read the simulator's
 * file here; the real file's units are confirmed only by reading it (READ_LIVE).
 */
class UpstoxInstrumentUnitsTest {

    @TempDir
    Path dir;

    @Test
    void theAdaptersReadingAndTheContractMastersAgree() throws Exception {
        try (UpstoxSimulator sim = new UpstoxSimulator(0, 1_000)) {
            sim.list(new UpstoxSimulator.Listing("NSE_FO|47168", "NIFTY 23100 PE 11 NOV 26", "NIFTY",
                    LocalDate.of(2026, 11, 11), 23100, "PE", 65, 0.05));
            sim.list(new UpstoxSimulator.Listing("BSE_FO|1125704", "SENSEX 74600 CE 13 NOV 26", "SENSEX",
                    LocalDate.of(2026, 11, 13), 74600, "CE", 20, 0.05));
            for (String exchange : List.of("NSE", "BSE")) {
                byte[] file = sim.instrumentFile(exchange);
                Path path = dir.resolve(exchange + ".json.gz");
                Files.write(path, file);
                List<Instrument> master = UpstoxInstrumentFile.read(path, Set.of("NIFTY", "SENSEX"));
                List<UpstoxInstruments.Contract> adapter = UpstoxInstruments.parse(new ByteArrayInputStream(file));
                assertThat(master).hasSize(1);
                assertThat(adapter).hasSize(1);
                Instrument m = master.getFirst();
                UpstoxInstruments.Contract a = adapter.getFirst();
                assertThat(a.instrumentKey()).isEqualTo(m.instrumentKey());
                assertThat(a.tickSize()).isEqualTo(m.tickSize()).isEqualTo(0.05);
                assertThat(a.expiry()).isEqualTo(m.expiry());
                assertThat(a.strike()).isEqualTo(m.strike());
                assertThat(a.lotSize()).isEqualTo(m.lotSize());
                assertThat(a.type()).isEqualTo(m.type());
                assertThat(a.tradingSymbol()).isEqualTo(m.tradingSymbol());
            }
        }
    }
}
