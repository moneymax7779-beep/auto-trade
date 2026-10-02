package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Tested zones appear only from the minute their second touch is confirmed, and end at a close beyond them. */
class LevelsServiceTest {

    private static List<LevelsService.Bar> bars(double... closes) {
        List<LevelsService.Bar> out = new ArrayList<>();
        LocalTime t = LocalTime.of(10, 0);
        for (double c : closes) {
            out.add(new LevelsService.Bar(t, c, c + 1, c - 1, c));
            t = t.plusMinutes(1);
        }
        return out;
    }

    @Test
    void aResistanceZoneFormsAtTheSecondConfirmedTouchAndEndsWhenBroken() {
        // a high at 10:03 (100) and another at 10:11 (100.02), each with 3 lower bars either side; then a close above
        List<LevelsService.Bar> b = bars(95, 96, 97, 100, 97, 96, 95, 94, 95, 96, 97, 100.02, 97, 96, 95, 96, 98, 103);
        List<LevelsService.Zone> zones = LevelsService.zones(b);
        assertThat(zones).singleElement().satisfies(z -> {
            assertThat(z.kind()).isEqualTo("resistance");
            assertThat(z.touches()).isEqualTo(2);
            assertThat(z.from()).isEqualTo("10:14");        // the 10:11 pivot confirmed 3 minutes later
            assertThat(z.until()).isEqualTo("10:17");       // the first close above the zone
            assertThat(z.lo()).isEqualTo(101.0);
            assertThat(z.hi()).isEqualTo(101.02);
        });
    }

    @Test
    void vwapSubtractsTheTrailingMeanBasisNotTheLatestTick() {
        // futures VWAP 1,000 throughout; the tick basis jumps 50 → 90 → 50
        List<Object> v = LevelsService.smoothVwap(List.of("09:16", "09:17", "09:18"),
                List.of(950.0, 910.0, 950.0), List.of(50.0, 90.0, 50.0), 15);
        assertThat(v).containsExactly(List.of("09:16", 950.0), List.of("09:17", 930.0), List.of("09:18", 936.67));
        // only the last `window` basis values count; a missing basis falls back to the proxy
        assertThat(LevelsService.smoothVwap(List.of("a", "b", "c"), List.of(950.0, 910.0, 900.0),
                java.util.Arrays.asList(50.0, 90.0, null), 1)).containsExactly(List.of("a", 950.0), List.of("b", 910.0), List.of("c", 900.0));
    }

    @Test
    void emaStartsAfterAFullPeriod() {
        assertThat(LevelsService.ema(bars(1, 2, 3, 4, 5), 5)).hasSize(1);
        assertThat(LevelsService.ema(bars(1, 2, 3, 4), 5)).isEmpty();
    }
}
