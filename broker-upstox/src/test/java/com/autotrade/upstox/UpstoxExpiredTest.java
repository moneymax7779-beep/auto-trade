package com.autotrade.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class UpstoxExpiredTest {

    @Test
    void parsesContractsWithEitherFieldSpelling() {
        String json = """
                [{"expired_instrument_key":"NSE_FO|12345|06-10-2026","trading_symbol":"NIFTY 22700 CE 06 OCT 26",
                  "strike_price":22700,"instrument_type":"CE","lot_size":65,"expiry":"2026-10-06","exchange":"NSE"},
                 {"instrument_key":"NSE_FO|99|27-10-2026","tradingsymbol":"NIFTY FUT 27 OCT 26","instrument_type":"FUT",
                  "lot_size":"65","expiry":"2026-10-27T00:00:00","segment":"NSE_FO"}]
                """;
        List<UpstoxExpired.Contract> c = UpstoxExpired.parseContracts(JsonMapper.builder().build().readTree(json));
        assertThat(c).hasSize(2);
        assertThat(c.get(0).expiredKey()).isEqualTo("NSE_FO|12345|06-10-2026");
        assertThat(c.get(0).strike()).isEqualTo(22700);
        assertThat(c.get(0).optionType()).isEqualTo("CE");
        assertThat(c.get(0).lotSize()).isEqualTo(65);
        assertThat(c.get(0).expiry()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(c.get(1).isFuture()).isTrue();
        assertThat(c.get(1).expiry()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(c.get(1).exchange()).isEqualTo("NSE");
    }

    @Test
    void candlesCarryVolumeAndOpenInterestWhenSent() {
        String body = """
                {"status":"success","data":{"candles":[["2026-10-06T09:15:00+05:30",50.1,52.0,49.0,51.5,1300,98765.0],
                 ["2026-10-06T09:16:00+05:30",51.5,51.6,50.0,50.2,650,98800.0]]}}
                """;
        List<UpstoxCandles.Candle> c = UpstoxCandles.parse(body);
        assertThat(c).hasSize(2);
        assertThat(c.get(0).volume()).isEqualTo(1300);
        assertThat(c.get(0).oi()).isEqualTo(98765.0);
        assertThat(c.get(1).close()).isEqualTo(50.2);
        // the older five-field shape still parses (no volume, no OI)
        List<UpstoxCandles.Candle> old = UpstoxCandles.parse("""
                {"data":{"candles":[["2026-10-06T09:15:00+05:30",1,2,0.5,1.5]]}}
                """);
        assertThat(old.getFirst().volume()).isZero();
        assertThat(old.getFirst().oi()).isNaN();
    }
}
