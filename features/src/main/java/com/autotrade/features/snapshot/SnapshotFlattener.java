package com.autotrade.features.snapshot;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.autotrade.core.time.MarketTime;

/** Flattens a snapshot (nested records) into ordered {@code section.field → value} pairs for CSV and reports. */
public final class SnapshotFlattener {

    private SnapshotFlattener() {
    }

    public static Map<String, Object> flatten(FeatureSnapshot snapshot) {
        Map<String, Object> values = new LinkedHashMap<>();
        flatten("", snapshot, values);
        return values;
    }

    /** JSON-safe copy of a flattened snapshot: NaN/infinite become null, times and dates ISO strings. */
    public static Map<String, Object> jsonSafe(Map<String, Object> row) {
        Map<String, Object> values = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (value instanceof Double d && !Double.isFinite(d)) {
                values.put(key, null);
            } else if (value instanceof Instant || value instanceof java.time.LocalDate) {
                values.put(key, value.toString());
            } else {
                values.put(key, value);
            }
        });
        return values;
    }

    /** CSV cell text: empty for NaN/null, IST wall-clock time for instants. */
    public static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                return "";
            }
            double rounded = Math.round(d * 10_000.0) / 10_000.0;
            return rounded == Math.rint(rounded) && Math.abs(rounded) < 1e15
                    ? Long.toString((long) rounded) : Double.toString(rounded);
        }
        if (value instanceof Instant instant) {
            return instant.atZone(MarketTime.IST).toLocalTime().toString();
        }
        String text = value.toString();
        return text.contains(",") || text.contains("\"") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    private static void flatten(String prefix, Record record, Map<String, Object> values) {
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object value;
            try {
                value = component.getAccessor().invoke(record);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            String name = prefix + component.getName();
            if (value instanceof Record nested) {
                flatten(name + ".", nested, values);
            } else {
                values.put(name, value);
            }
        }
    }
}
