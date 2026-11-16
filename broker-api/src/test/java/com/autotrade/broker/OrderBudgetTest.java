package com.autotrade.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** The per-user order budget (validation 16 Nov 2026). */
class OrderBudgetTest {

    private Instant now = Instant.parse("2026-11-16T04:00:00Z");                  // 09:30 IST
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    @Test
    void aFullWindowRefusesBuysAsRetryableNeverSellsAndFreesUpAsItRolls() {
        OrderBudget budget = new OrderBudget(1_000, clock, new OrderBudget.Window(Duration.ofMinutes(1), 3));
        budget.charge(true, "buy A");
        budget.charge(false, "sell B");
        budget.count();                                                           // a cancel fills the window too
        assertThatThrownBy(() -> budget.charge(true, "buy C")).isInstanceOfSatisfying(OrderBudget.Exceeded.class,
                e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.RETRYABLE)).hasMessageContaining("not sent");
        budget.charge(false, "exit D");                                            // an exit never waits on our cap
        budget.count();
        now = now.plusSeconds(61);
        budget.charge(true, "buy C");
        assertThat(budget.today()).isEqualTo(6);
    }

    @Test
    void aUsedUpDayRefusesBuysButNeverExitsOrCancels() {
        OrderBudget budget = new OrderBudget(2, clock);
        budget.charge(true, "buy 1");
        budget.charge(true, "buy 2");
        assertThatThrownBy(() -> budget.charge(true, "buy 3")).isInstanceOfSatisfying(OrderBudget.Exceeded.class,
                e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.TERMINAL))
                .hasMessageContaining("exits and cancels still go");
        budget.charge(false, "exit");
        budget.count();
        now = now.plus(Duration.ofDays(1));                                       // a new IST day
        budget.charge(true, "buy next day");
        assertThat(budget.today()).isEqualTo(1);
    }

    @Test
    void sharedBudgetsAreOnePerKey() {
        assertThat(OrderBudget.shared("T:a", 10)).isSameAs(OrderBudget.shared("T:a", 10))
                .isNotSameAs(OrderBudget.shared("T:b", 10));
    }
}
