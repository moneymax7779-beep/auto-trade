package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.features.snapshot.StructureFeatures;

/** odm-v15's room to the next level (A-216): the nearest level beyond the index in the drive's direction, in atr3m. */
class OpeningDriveMomentumRoomTest {

    /** A StructureFeatures with every number NaN (ints 0) except the given ones. */
    static StructureFeatures st(Map<String, Double> values) throws Exception {
        RecordComponent[] parts = StructureFeatures.class.getRecordComponents();
        Object[] args = new Object[parts.length];
        Class<?>[] types = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Class<?> t = parts[i].getType();
            types[i] = t;
            Double v = values.get(parts[i].getName());
            args[i] = t == double.class ? (v == null ? Double.NaN : v) : t == int.class ? 0 : t == boolean.class ? false : null;
        }
        return StructureFeatures.class.getDeclaredConstructor(types).newInstance(args);
    }

    @Test
    void the22OctPutHadHalfAnAtrToTheMonthlyLow() throws Exception {
        // 22 Oct 2026 09:24 NIFTY: index 22,584.15, atr3m 31.07; PDL 22,650 (above: not ahead of a put), PML 22,569.65 below
        StructureFeatures s = st(Map.of("atr3m", 31.0668, "pdl", 22649.95, "pdh", 23120.9, "pml", 22569.65, "pwl", 22288.7,
                "prevOrLow", 22981.35, "prevOrHigh", 23086.55));
        assertThat(OpeningDriveMomentum.roomAtr(s, 22584.15, true)).isCloseTo(0.467, org.assertj.core.data.Offset.offset(0.01));
        assertThat(OpeningDriveMomentum.roomAtr(s, 22584.15, false)).as("a call looks above: PDL")
                .isCloseTo((22649.95 - 22584.15) / 31.0668, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void unknownAtrOrNoLevelAheadIsNaNSoTheEntryIsAllowed() throws Exception {
        assertThat(OpeningDriveMomentum.roomAtr(st(Map.of("pdl", 100.0)), 110, true)).isNaN();
        assertThat(OpeningDriveMomentum.roomAtr(st(Map.of("atr3m", 5.0, "pdh", 120.0)), 110, true)).as("nothing below").isNaN();
        assertThat(Double.NaN < 1.0).as("NaN never blocks").isFalse();
    }
}
