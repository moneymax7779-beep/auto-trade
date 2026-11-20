package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Second review of the admin exit desk (20 Nov 2026): the members are told what an admin's exit sold or may have sold,
 * in the engine's own wording, and that the account was stopped when it was; never "the admin exited" for an exit that
 * sold nothing.
 */
class AdminExitsTest {

    @Test
    void onlySalesAndSellsOfUnknownOutcomeAreNews() {
        assertThat(AdminExits.news(List.of(
                "NIFTY 24000 CE: 130 sold at 101.00",
                "NIFTY 24000 CE: 65 of 130 sold at 101.00; 65 stay open",
                "NIFTY 24000 CE: 130 sold at 77.00 (last bid 12 min old)",
                "NIFTY 24000 CE: 0 of 130 sold (ORDER_UNKNOWN cancel not confirmed); 130 stay open",
                "NIFTY 24000 CE: NOT flattened, sell FX12 has an unknown outcome (looked at each turn)"), false)).hasSize(5);
        assertThat(AdminExits.news(List.of(
                "position 7 is not open",
                "NIFTY 24000 CE: NOT exited, no bid and no signal price for this contract today",
                "not flattened: LIVE refused: no broker session",
                "NIFTY 24000 CE: 0 of 130 sold (BROKER_REJECTED margin); 130 stay open",
                "NIFTY 24000 CE: the broker stop had sold it",
                "the worker has not started"), false)).isEmpty();
        assertThat(AdminExits.news(List.of("position 7 is not open"), true)).containsExactly(AdminExits.STOPPED);
    }
}
