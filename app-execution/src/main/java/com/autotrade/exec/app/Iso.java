package com.autotrade.exec.app;

/** SQL for a timestamp the UI reads: ISO-8601 in UTC ({@code 2026-11-07T04:39:02Z}), which every browser parses. */
final class Iso {

    private Iso() {
    }

    static String of(String column) {
        return "to_char(" + column + " at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')";
    }
}
