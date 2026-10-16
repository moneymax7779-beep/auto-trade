package com.autotrade.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** A day's ledger: net and closing equity from our fills; a broker difference of a rupee or more needs a person. */
class LedgerDayTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);

    @Test
    void closesFromOurFillsAndComparesWithTheBroker() {
        LedgerDay day = LedgerDay.close(7, DAY, 200_000, 12_500, 640.50, 3);
        assertThat(day.net()).isEqualTo(11_859.50);
        assertThat(day.closingEquity()).isEqualTo(211_859.50);
        assertThat(day.needsAcknowledgement()).isTrue();                          // traded, not reconciled yet
        assertThat(day.reconciled(11_859.20, 640.80).needsAcknowledgement()).isFalse(); // 30 paise apart
        LedgerDay off = day.reconciled(11_800, 700);
        assertThat(off.difference()).isEqualTo(59.5);
        assertThat(off.needsAcknowledgement()).isTrue();
        assertThat(LedgerDay.close(7, DAY, 200_000, 0, 0, 0).needsAcknowledgement()).isFalse();   // no trades
    }
}
