package com.autotrade.broker;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A broker user's order budget beyond the per-second limiter (validation 16 Nov 2026): rolling windows (Kite 400 a
 * minute; Upstox 500 a minute and 2,000 in 30 minutes) and a day's count (Kite 5,000). Every place, modify and cancel is
 * counted; only what adds risk (a buy, or a change to a buy) is ever refused, as {@link Exceeded} with nothing sent:
 * <ul>
 *   <li>a full window: {@link BrokerException.Kind#RETRYABLE};</li>
 *   <li>a used-up day: {@link BrokerException.Kind#TERMINAL}.</li>
 * </ul>
 * A sell (exit, stop, its re-price) and a cancel are never refused here: an exit must not wait on our own cap, and the
 * LIVE guard already bounds sells to what is held. A runaway buy loop stops at our cap, below the broker's.
 * One budget per broker user (the broker counts per user / API key), shared by every gateway built for it: see
 * {@link #shared}.
 */
public final class OrderBudget {

    /** At most {@code max} charged calls in any rolling {@code length}. */
    public record Window(Duration length, int max) {
    }

    private static final Map<String, OrderBudget> SHARED = new ConcurrentHashMap<>();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Window[] windows;
    private final ArrayDeque<Long>[] stamps;
    private final int perDay;
    private final Clock clock;
    private LocalDate day;
    private int today;

    @SuppressWarnings("unchecked")
    public OrderBudget(int perDay, Clock clock, Window... windows) {
        this.windows = windows.clone();
        this.stamps = new ArrayDeque[windows.length];
        for (int i = 0; i < windows.length; i++) {
            stamps[i] = new ArrayDeque<>();
        }
        this.perDay = perDay;
        this.clock = clock;
    }

    /**
     * The budget of one broker user ({@code key}: broker and API key or user id), created on first use; the limits of
     * the first call stand for the key.
     */
    public static OrderBudget shared(String key, int perDay, Window... windows) {
        return SHARED.computeIfAbsent(key, k -> new OrderBudget(perDay, Clock.systemUTC(), windows));
    }

    /**
     * Charges one place or modify, or refuses it before anything is sent.
     *
     * @param addsRisk a buy, or a change to a buy: refused once the day's count is used up
     * @param what     the call, for the message
     */
    public synchronized void charge(boolean addsRisk, String what) {
        long now = clock.millis();
        rollDay();
        prune(now);
        if (addsRisk) {
            if (today >= perDay) {
                throw new Exceeded(BrokerException.Kind.TERMINAL, what + " refused: the day's order budget of " + perDay
                        + " is used up (exits and cancels still go)");
            }
            for (int i = 0; i < windows.length; i++) {
                if (stamps[i].size() >= windows[i].max()) {
                    throw new Exceeded(BrokerException.Kind.RETRYABLE, what + " not sent: " + windows[i].max()
                            + " orders in the last " + windows[i].length().toSeconds() + " s (our cap below the broker's)");
                }
            }
        }
        record(now);
    }

    /** Counts a cancel or a sell that must go (never refused). */
    public synchronized void count() {
        long now = clock.millis();
        rollDay();
        prune(now);
        record(now);
    }

    private void prune(long now) {
        for (int i = 0; i < windows.length; i++) {
            long from = now - windows[i].length().toMillis();
            while (!stamps[i].isEmpty() && stamps[i].peekFirst() <= from) {
                stamps[i].pollFirst();
            }
        }
    }

    private void record(long now) {
        for (ArrayDeque<Long> s : stamps) {
            s.addLast(now);
        }
        today++;
    }

    /** Our own budget refused the call; nothing was sent to the broker. */
    public static final class Exceeded extends BrokerException {
        Exceeded(Kind kind, String message) {
            super(kind, message);
        }
    }

    /**
     * Raises today's count to at least {@code alreadyToday} (review 16 Nov 2026, D9): the budget lives in memory, so a
     * restarted worker started the day's count from 0 below what the broker had already seen; a gateway raises it from
     * today's order book before its first order of the day.
     */
    public synchronized void atLeast(int alreadyToday) {
        rollDay();
        today = Math.max(today, alreadyToday);
    }

    /** Calls charged today (IST). */
    public synchronized int today() {
        rollDay();
        return today;
    }

    private void rollDay() {
        LocalDate d = LocalDate.ofInstant(clock.instant(), IST);
        if (!d.equals(day)) {
            day = d;
            today = 0;
        }
    }
}
