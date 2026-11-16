package com.autotrade.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** What was carried into today is read once a day after 09:00 IST; a failed read is never kept (review 16 Nov 2026). */
class DailyCarryTest {

    private static final Map<String, Long> CARRY = Map.of("NSE_FO|47168", 65L);

    /** A fixed clock at {@code ist} (HH:mm) on 17 Nov 2026 in IST. */
    private static Clock at(String ist) {
        return Clock.fixed(java.time.ZonedDateTime.of(java.time.LocalDate.of(2026, 11, 17), java.time.LocalTime.parse(ist),
                java.time.ZoneId.of("Asia/Kolkata")).toInstant(), ZoneOffset.UTC);
    }

    @Test
    void readOnceADayFromNineAndAgainBefore() {
        AtomicInteger reads = new AtomicInteger();
        DailyCarry early = new DailyCarry("TEST", at("08:55"));
        early.get(() -> { reads.incrementAndGet(); return CARRY; });
        early.get(() -> { reads.incrementAndGet(); return CARRY; });
        assertThat(reads).as("before 09:15 IST: not kept").hasValue(2);

        DailyCarry day = new DailyCarry("TEST", at("09:20"));
        assertThat(day.get(() -> { reads.incrementAndGet(); return CARRY; })).isEqualTo(CARRY);
        assertThat(day.get(() -> { reads.incrementAndGet(); return Map.of(); })).as("kept for the day").isEqualTo(CARRY);
        assertThat(reads).hasValue(3);
    }

    @Test
    void aFailedReadIsUnknownNeverNothingAndIsNotReadAgainForAWhile() {
        // second review 16 Nov 2026: "nothing carried" on a failed read made the reconciler cut our stop
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now = new java.util.concurrent.atomic.AtomicReference<>(
                at("10:00").instant());
        Clock moving = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { return now.get(); }
        };
        AtomicInteger reads = new AtomicInteger();
        DailyCarry day = new DailyCarry("TEST", moving);
        assertThatThrownBy(() -> day.get(() -> {
            reads.incrementAndGet();
            throw new BrokerException(BrokerException.Kind.RETRYABLE, "timeout");
        })).isInstanceOf(BrokerException.class).hasMessageContaining("not known")
                .satisfies(e -> assertThat(((BrokerException) e).kind()).isEqualTo(BrokerException.Kind.RETRYABLE));
        assertThatThrownBy(() -> day.get(() -> { reads.incrementAndGet(); return CARRY; }))
                .as("not read again within the pause").hasMessageContaining("not known");
        assertThat(reads).hasValue(1);
        now.set(now.get().plus(DailyCarry.RETRY));
        assertThat(day.get(() -> { reads.incrementAndGet(); return CARRY; })).isEqualTo(CARRY);
        DailyCarry other = new DailyCarry("TEST", at("10:00"));
        assertThatThrownBy(() -> other.get(() -> {
            throw new BrokerException(BrokerException.Kind.SESSION, "logged out");
        })).isInstanceOf(BrokerException.class)
                .satisfies(e -> assertThat(((BrokerException) e).kind()).isEqualTo(BrokerException.Kind.SESSION));
    }
}
