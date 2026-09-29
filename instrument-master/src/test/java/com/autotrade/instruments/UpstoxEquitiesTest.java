package com.autotrade.instruments;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SENSEX weights list BSE scrip codes; the BSE contract file carries them as exchange_token. */
class UpstoxEquitiesTest {

    @Test
    void bseEquitiesMatchByScripCodeAndNseBySymbol(@TempDir Path dir) throws Exception {
        Path bse = dir.resolve("BSE-2026-09-29.json");
        Files.writeString(bse, """
                [{"segment":"BSE_EQ","instrument_key":"BSE_EQ|INE040A01034","exchange_token":"500180","trading_symbol":"HDFCBANK","instrument_type":"A","tick_size":5},
                 {"segment":"BSE_EQ","instrument_key":"BSE_EQ|INE090A01021","exchange_token":"532174","trading_symbol":"ICICIBANK","instrument_type":"A","tick_size":5},
                 {"segment":"BSE_EQ","instrument_key":"BSE_EQ|INE002A01018","exchange_token":"500325","trading_symbol":"RELIANCE","instrument_type":"A","tick_size":5}]
                """);
        List<UpstoxInstrumentFile.Listing> bseHits = UpstoxInstrumentFile.readEquities(bse, "BSE_EQ", Set.of("500180", "532174"));
        assertThat(bseHits).extracting(UpstoxInstrumentFile.Listing::tradingSymbol).containsExactlyInAnyOrder("HDFCBANK", "ICICIBANK");
        assertThat(bseHits).extracting(UpstoxInstrumentFile.Listing::exchangeToken).containsExactlyInAnyOrder("500180", "532174");

        Path nse = dir.resolve("NSE-2026-09-29.json");
        Files.writeString(nse, """
                [{"segment":"NSE_EQ","instrument_key":"NSE_EQ|INE040A01034","exchange_token":"1333","trading_symbol":"HDFCBANK","instrument_type":"EQ","tick_size":10}]
                """);
        assertThat(UpstoxInstrumentFile.readEquities(nse, "NSE_EQ", Set.of("HDFCBANK"))).singleElement()
                .satisfies(l -> assertThat(l.instrumentKey()).isEqualTo("NSE_EQ|INE040A01034"));
    }
}
