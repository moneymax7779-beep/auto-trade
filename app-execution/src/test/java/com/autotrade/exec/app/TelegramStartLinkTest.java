package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TelegramStartLinkTest {

    @Test
    void theStartLinkUsesTelegramMeBecauseSomeNetworksBlockTMe() {
        assertThat(MembersController.startLink("AutoDhan_bot", "H65HGTBN"))
                .isEqualTo("https://telegram.me/AutoDhan_bot?start=H65HGTBN");
    }

    @Test
    void noBotNameMeansNoLink() {
        assertThat(MembersController.startLink(null, "H65HGTBN")).isNull();
    }
}
