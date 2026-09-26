package com.autotrade.trading;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.core.history.DailyBar;
import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.history.ReferenceData;
import com.autotrade.md.store.ReferenceStore;
import com.autotrade.upstox.UpstoxCandles;
import com.autotrade.upstox.UpstoxUniverse;

/**
 * Reference series for a live session: daily India VIX from auto-trade's database, and today's
 * one-minute VIX bars polled from Upstox's public intraday endpoint. Completed bars are appended in
 * time order to the list the feature engines read (they use only bars that have ended), and saved to
 * {@code ref.index_candle} so replays of the day have them too.
 */
final class LiveReference implements ReferenceData {

    private static final Logger log = LoggerFactory.getLogger(LiveReference.class);

    private final ReferenceStore store;
    private final LocalDate session;
    private final UpstoxCandles candles;
    private final List<MinuteBar> today = new CopyOnWriteArrayList<>();
    private long failures;

    LiveReference(ReferenceStore store, LocalDate session, UpstoxCandles candles) {
        this.store = store;
        this.session = session;
        this.candles = candles;
        today.addAll(store.intradayBars(INDIA_VIX, session));
    }

    @Override
    public List<DailyBar> dailyBars(String symbol, LocalDate before, int sessions) {
        return store.dailyBars(symbol, before, sessions);
    }

    @Override
    public List<MinuteBar> intradayBars(String symbol, LocalDate day) {
        return INDIA_VIX.equals(symbol) && day.equals(session) ? today : store.intradayBars(symbol, day);
    }

    @Override
    public List<java.util.Map<java.time.LocalTime, Double>> auctionTurnover(String underlying, LocalDate before,
                                                                           int sessions) {
        return store.auctionTurnover(underlying, before, sessions);
    }

    /** Fetches today's VIX minutes and appends the newly completed ones. Never throws. */
    void poll() {
        try {
            Instant now = Instant.now();
            Instant last = today.isEmpty() ? Instant.MIN : today.getLast().start();
            List<MinuteBar> fresh = new ArrayList<>();
            List<ReferenceStore.Candle> rows = new ArrayList<>();
            for (UpstoxCandles.Candle c : candles.intradayMinutes(UpstoxUniverse.VIX_KEY)) {
                MinuteBar bar = c.toMinuteBar();
                if (c.session().equals(session) && bar.start().isAfter(last) && !bar.end().isAfter(now)) {
                    fresh.add(bar);
                    rows.add(new ReferenceStore.Candle(bar.start(), session, c.open(), c.high(), c.low(), c.close()));
                }
            }
            if (!fresh.isEmpty()) {
                today.addAll(fresh);
                store.upsert(INDIA_VIX, ReferenceStore.MINUTE, "UPSTOX_V3_INTRADAY", rows);
            }
        } catch (Exception e) {
            if (failures++ % 20 == 0) {
                log.warn("India VIX poll failed ({} so far): {}", failures, e.getMessage());
            }
        }
    }
}
