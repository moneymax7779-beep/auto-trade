package com.autotrade.tools.clone;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** What a clone copies. Ticks are required; the rest are optional extras. */
public enum Dataset {
    TICKS,
    CANDLES,
    CAS,
    OI;

    public static Set<Dataset> parse(List<String> names) {
        EnumSet<Dataset> datasets = EnumSet.noneOf(Dataset.class);
        for (String name : names) {
            datasets.add(Dataset.valueOf(name.toUpperCase(Locale.ROOT)));
        }
        if (!datasets.contains(TICKS)) {
            throw new IllegalArgumentException("ticks must be part of every clone");
        }
        return datasets;
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
