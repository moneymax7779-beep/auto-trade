package com.autotrade.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Allocated capital is the hard cap; every limit refuses (never shrinks); live modes need the broker's funds. */
class CapitalPolicyTest {

    private static final CapitalPolicy POLICY = new CapitalPolicy(new CapitalLimits(20, 50, 100, 10));

    private static BrokerAccount account(EquityMode mode) {
        return new BrokerAccount(1, 1, 1, BrokerType.ZERODHA, "LIVE-A", null, AccountRole.FOLLOWER_TRADE, TradeMode.LIVE,
                true, 200_000, mode, LocalDate.of(2026, 10, 16), "config/risk/live-risk.v1.yaml", 0, true);
    }

    @Test
    void fixedEquityIgnoresProfitsCompoundingAddsThemAndMovementsCountForBoth() {
        assertThat(CapitalPolicy.equity(account(EquityMode.FIXED), 50_000, 80_000)).isEqualTo(250_000);
        assertThat(CapitalPolicy.equity(account(EquityMode.COMPOUNDING), 50_000, 80_000)).isEqualTo(330_000);
        assertThat(CapitalPolicy.equity(account(EquityMode.COMPOUNDING), 0, -250_000)).isZero();   // never negative
    }

    @Test
    void theAllocationCapsTheEntryEvenWhenTheBrokerHoldsMore() {
        // equity 2L: per trade 1L (50 %), open premium 2L (100 %); the broker holds 50L
        assertThat(POLICY.checkEntry(TradeMode.LIVE, 200_000, 90_000, 0, 0, 5_000_000).approved()).isTrue();
        assertThat(POLICY.checkEntry(TradeMode.LIVE, 200_000, 110_000, 0, 0, 5_000_000).code()).isEqualTo("PER_TRADE");
        assertThat(POLICY.checkEntry(TradeMode.LIVE, 200_000, 90_000, 150_000, 0, 5_000_000).code()).isEqualTo("CAPITAL");
    }

    @Test
    void theDailyLossLimitStopsEntries() {
        assertThat(POLICY.checkEntry(TradeMode.LIVE, 200_000, 10_000, 0, -39_999, 1e6).approved()).isTrue();
        assertThat(POLICY.checkEntry(TradeMode.LIVE, 200_000, 10_000, 0, -40_000, 1e6).code()).isEqualTo("DAILY_LOSS");
    }

    @Test
    void liveAndDryRunNeedTheBrokersFundsWithABufferPaperDoesNot() {
        assertThat(POLICY.checkEntry(TradeMode.LIVE, 200_000, 90_000, 0, 0, Double.NaN).code()).isEqualTo("FUNDS_UNKNOWN");
        assertThat(POLICY.checkEntry(TradeMode.DRY_RUN, 200_000, 90_000, 0, 0, Double.NaN).code())
                .isEqualTo("FUNDS_UNKNOWN");
        assertThat(POLICY.checkEntry(TradeMode.LIVE, 200_000, 90_000, 0, 0, 99_000).code()).isEqualTo("BROKER_FUNDS");
        assertThat(POLICY.checkEntry(TradeMode.LIVE, 200_000, 90_000, 0, 0, 100_000).approved()).isTrue();   // 90 % usable
        assertThat(POLICY.checkEntry(TradeMode.PAPER, 200_000, 90_000, 0, 0, Double.NaN).approved()).isTrue();
    }

    @Test
    void anAccountCannotBeLiveOnPaperOrOutsideLiveMode() {
        assertThatThrownBy(() -> new BrokerAccount(1, 1, 1, BrokerType.PAPER, "P", null, AccountRole.DISABLED,
                TradeMode.LIVE, true, 1, EquityMode.FIXED, LocalDate.now(), "r", 0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BrokerAccount(1, 1, 1, BrokerType.ZERODHA, "Z", null, AccountRole.FOLLOWER_TRADE,
                TradeMode.DRY_RUN, true, 1, EquityMode.FIXED, LocalDate.now(), "r", 0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapitalLimits(0, 50, 100, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
