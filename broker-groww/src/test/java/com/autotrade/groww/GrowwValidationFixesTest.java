package com.autotrade.groww;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.autotrade.broker.OrderStatus;

/** Groww fixes from the 16 Nov 2026 adapter validation. */
class GrowwValidationFixesTest {

    @Test
    void aDoneWordWithLessThanTheQuantityFilledIsNeverFilled() {
        assertThat(GrowwGateway.status("EXECUTED", 75, 150)).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(GrowwGateway.status("COMPLETED", 0, 150)).isEqualTo(OrderStatus.PENDING);
        assertThat(GrowwGateway.status("EXECUTED", 150, 150)).isEqualTo(OrderStatus.FILLED);
        assertThat(GrowwGateway.status("CANCELLED", 75, 150)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void onlyAnExplicitNoRefusesASide() {
        assertThat(GrowwInstruments.allowed("")).isTrue();
        assertThat(GrowwInstruments.allowed("1")).isTrue();
        assertThat(GrowwInstruments.allowed("TRUE")).isTrue();
        assertThat(GrowwInstruments.allowed("0")).isFalse();
        assertThat(GrowwInstruments.allowed("false")).isFalse();
        assertThat(GrowwInstruments.allowed(" N ")).isFalse();
    }
}
