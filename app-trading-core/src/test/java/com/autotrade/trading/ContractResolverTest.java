package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.OptionTick;

/** An underlying expires today when the nearest expiry among its option quotes is this session. */
class ContractResolverTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 29);

    private static OptionTick tick(String underlying, LocalDate expiry) {
        return new OptionTick(Instant.EPOCH, Instant.EPOCH, underlying, 1, 1, underlying + " OPT", expiry, 23000,
                OptionTick.OptionType.CE, 65, 50, null, null, null, null, null, null, null, null, null, null, null,
                null, null, false, false);
    }

    @Test
    void nearestExpiryOfTheQuotesDecides() {
        ContractResolver contracts = new ContractResolver(TUESDAY, null);
        assertThat(contracts.expiresToday("NIFTY")).isFalse();                 // nothing seen yet
        contracts.accept(tick("NIFTY", TUESDAY.plusDays(7)));                   // next week's quote first
        assertThat(contracts.expiresToday("NIFTY")).isFalse();
        contracts.accept(tick("NIFTY", TUESDAY));                               // today's weekly
        contracts.accept(tick("SENSEX", TUESDAY.plusDays(2)));                  // SENSEX expires Thursday
        assertThat(contracts.expiresToday("NIFTY")).isTrue();
        assertThat(contracts.expiresToday("SENSEX")).isFalse();
    }
}
