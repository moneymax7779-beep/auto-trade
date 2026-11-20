package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.account.BrokerType;
import org.junit.jupiter.api.Test;

class AccountAdminClientIdTest {

    @Test
    void zerodhaAcceptsTwoAndThreeLetterIds() {
        assertThat(AccountAdminController.clientId(BrokerType.ZERODHA, "AB1234")).isEqualTo("AB1234");
        assertThat(AccountAdminController.clientId(BrokerType.ZERODHA, " ewn787 ")).isEqualTo("EWN787");
        assertThat(AccountAdminController.clientId(BrokerType.ZERODHA, "XY123456")).isEqualTo("XY123456");
    }

    @Test
    void zerodhaRejectsOtherShapes() {
        for (String bad : new String[] {"A12345", "ABCD123", "AB12", "AB1234567", "AB12CD", "123456"}) {
            assertThatThrownBy(() -> AccountAdminController.clientId(BrokerType.ZERODHA, bad)).as(bad)
                    .hasMessageContaining("client id");
        }
    }
}
