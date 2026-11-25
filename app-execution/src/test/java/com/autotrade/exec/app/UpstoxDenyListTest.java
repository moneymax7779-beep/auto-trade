package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** User 25 Nov 2026: zt-tiger-v2's Upstox user (BM6462) only for the listed broker accounts; nothing else changes. */
class UpstoxDenyListTest {

    @Test
    void ztTigersUserIsDeniedUnlessTheAccountIsListed() {
        UpstoxDenyList none = new UpstoxDenyList(List.of(), List.of(), null);
        assertThat(none.deniedUser("bm6462", 40)).isTrue();
        UpstoxDenyList listed = new UpstoxDenyList(List.of(), List.of(), List.of(" 40 ", ""));
        assertThat(listed.deniedUser(" BM6462 ", 40)).isFalse();
        assertThat(listed.deniedUser("BM6462", 15)).as("another account").isTrue();
        assertThat(listed.deniedUser("BM6462")).as("without an account: denied").isTrue();
        assertThat(listed.ztUserAccountIds()).containsExactly(40L);
    }

    @Test
    void listingNeverFreesAnotherDeniedUserOrANormalOneIsDenied() {
        UpstoxDenyList d = new UpstoxDenyList(List.of(), List.of("ZZ9999"), List.of("40"));
        assertThat(d.deniedUser("zz9999", 40)).isTrue();
        assertThat(d.deniedUser("AB1234", 40)).isFalse();
        assertThat(d.deniedUser(null, 40)).isFalse();
    }

    @Test
    void anEntryThatIsNoAccountIdIsLeftOutNotFatal() {
        UpstoxDenyList d = new UpstoxDenyList(List.of(), List.of(), List.of("satya", "-4", "0", "41"));
        assertThat(d.ztUserAccountIds()).containsExactly(41L);
        assertThat(d.deniedUser("BM6462", 41)).isFalse();
    }
}
