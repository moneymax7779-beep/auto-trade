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
    void emaStartsAfterAFullPeriod() {
        assertThat(LevelsService.ema(bars(1, 2, 3, 4, 5), 5)).hasSize(1);
        assertThat(LevelsService.ema(bars(1, 2, 3, 4), 5)).isEmpty();
    }
}
