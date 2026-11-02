package com.autotrade.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.autotrade.instruments.Instrument;
import com.autotrade.instruments.InstrumentMaster;

/** The far expiries recorded beside the nearest (user, 3 Nov 2026): the next expiry and the monthly, never fed to the engine. */
class UpstoxUniverseFarTest {

    private static final List<LocalDate> EXPIRIES = List.of(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 3),
            LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 29));

    private static UpstoxUniverse universe(LocalDate session) {
        List<Instrument> instruments = new ArrayList<>();
        int token = 1;
        for (LocalDate expiry : EXPIRIES) {
            for (double strike = 22_550; strike <= 22_850; strike += 50) {
                for (String type : new String[] {"CE", "PE"}) {
                    instruments.add(new Instrument("NSE_FO|" + token, String.valueOf(token), "NSE_FO", "NSE", type, "NIFTY",
                            "NSE_INDEX|Nifty 50", "NIFTY " + expiry + " " + (int) strike + " " + type, expiry, strike, 65,
                            0.05, 1755, true));
                    token++;
                }
            }
        }
        return new UpstoxUniverse(new InstrumentMaster(instruments), session, List.of("NIFTY"), Map.of("NIFTY", List.of()));
    }

    @Test
    void onAMonthlyExpiryDayTheFarExpiriesAreNextWeekAndNextMonthsMonthly() {
        assertThat(universe(LocalDate.of(2026, 10, 27)).farExpiries("NIFTY"))
                .containsExactly(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 24));
    }

    @Test
    void onAnOrdinaryWeekTheFarExpiriesAreNextWeekAndThisMonthsMonthly() {
        assertThat(universe(LocalDate.of(2026, 11, 2)).farExpiries("NIFTY"))
                .containsExactly(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 24));
        assertThat(universe(LocalDate.of(2026, 11, 11)).farExpiries("NIFTY"))
                .as("the week before the monthly: the next expiry is the monthly itself")
                .containsExactly(LocalDate.of(2026, 11, 24));
    }

    @Test
    void farContractsAreMarkedAndTheNearestOnesAreNot() {
        UpstoxUniverse u = universe(LocalDate.of(2026, 11, 2));
        Set<String> far = u.farOptionKeys("NIFTY", 22_700, 1);
        assertThat(far).hasSize(2 * 3 * 2);                                    // 2 expiries x 3 strikes x CE / PE
        assertThat(far).allSatisfy(k -> assertThat(u.far(u.meta(k).token())).isTrue());
        Set<String> nearest = u.optionKeys("NIFTY", 22_700, 1);
        assertThat(nearest).hasSize(6).doesNotContainAnyElementsOf(far)
                .allSatisfy(k -> assertThat(u.far(u.meta(k).token())).isFalse());
        assertThat(u.farOptionKeys("NIFTY", 22_700, 0)).as("off").isEmpty();
    }

    @Test
    void theFeedSendsFarTicksToTheCaptureOnlyAndEverythingElseToTheEngine() {
        UpstoxUniverse u = universe(LocalDate.of(2026, 11, 2));
        long far = u.meta(u.farOptionKeys("NIFTY", 22_700, 1).iterator().next()).token();
        long near = u.meta(u.optionKeys("NIFTY", 22_700, 1).iterator().next()).token();
        List<com.autotrade.core.event.MarketEvent> engine = new ArrayList<>();
        List<com.autotrade.core.event.OptionTick> capture = new ArrayList<>();
        java.time.Instant t = java.time.Instant.parse("2026-11-02T04:00:00Z");
        for (long token : new long[] {far, near}) {
            UpstoxFeed.route(new com.autotrade.core.event.OptionTick(t, t, "NIFTY", 1, token, "x", LocalDate.of(2026, 11, 3),
                    22_700, com.autotrade.core.event.OptionTick.OptionType.CE, 65, 100, 1L, 1.0, 1.0, 1.0,
                    com.autotrade.core.event.DepthLevels.of(new double[] {99}, new long[] {65}, new int[] {1}),
                    com.autotrade.core.event.DepthLevels.of(new double[] {101}, new long[] {65}, new int[] {1}),
                    0.1, 0.5, 0.001, -1.0, 1.0, 0.1, t, true, true), u, engine::add, capture::add);
        }
        UpstoxFeed.route(new com.autotrade.core.event.IndexTick(t, t, "NIFTY", 2, 22_700, 22_650.0, null), u, engine::add,
                capture::add);
        assertThat(capture).singleElement().satisfies(c -> assertThat(c.instrumentToken()).isEqualTo(far));
        assertThat(engine).hasSize(2).noneSatisfy(e -> assertThat(e instanceof com.autotrade.core.event.OptionTick o
                && o.instrumentToken() == far).isTrue());
    }
}
