package com.autotrade.md.store;

import javax.sql.DataSource;

import com.autotrade.core.history.BackfilledSessionHistory;
import com.autotrade.core.history.SessionHistory;

/** Builds the session history a features file asks for, so every entry point (live, replay, shadow, tools) agrees. */
public final class SessionHistories {

    private SessionHistories() {
    }

    /**
     * {@code captured} as is, or (features v12, {@code history.bar_backfill}) with the stored one-minute bars filling the
     * older sessions and providing the OI baseline (A-090).
     */
    public static SessionHistory forFeatures(SessionHistory captured, boolean barBackfill, DataSource target) {
        return barBackfill ? new BackfilledSessionHistory(captured, new BarSessionHistory(target)) : captured;
    }
}
