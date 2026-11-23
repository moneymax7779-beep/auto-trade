package com.autotrade.execution;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A simulated entry filled as a real marketable limit would have been, on our own captured book (md.option_tick, the
 * Upstox feed's best ask), for risk files with {@code simulation.entry_fill: captured_book} (follower-risk v19, 23 Nov
 * 2026). Before, a PAPER entry always filled at the signal's price: on 23 Nov 11:03 a paper twin "bought" NIFTY 25550 PE
 * at 80.35 while the ask had moved to 81.75-82 and every real entry on that signal (PAPER-1's own, ZERODHA-1, Kotak)
 * went unfilled, so paper results flattered LIVE.
 * <ol>
 *   <li>The limit: the ask when the signal was decided (the newest captured tick at or before it, at most {@link #BOOK_AGE}
 *       old) plus the entry buffer, as {@link BrokerVenue} prices a real entry.</li>
 *   <li>The order arrives {@code latency} later: filled at the ask then when it is within the limit (a marketable order
 *       trades at the resting ask).</li>
 *   <li>Else it rests: filled at its limit the first time the captured ask comes down to it within the entry timeout.</li>
 *   <li>Else NOT_FILLED, as a broker entry whose timeout ran out.</li>
 * </ol>
 * A contract with no captured ask around the signal has no book here: the caller fills at the signal's price as before.
 * The whole quantity fills (no depth: the followers trade a few lots). When the timeout has not yet passed, or its last
 * ticks may not be written yet ({@link #WRITE_SETTLE}: the capture writes every second), it waits, polling, as a broker
 * entry works its order for up to the timeout; a replay's window is long past and is decided at once.
 */
final class CapturedEntryFill {

    /** A captured ask older than this at the signal is no book of then. */
    static final Duration BOOK_AGE = Duration.ofSeconds(10);
    /** How long after a window ends its ticks are taken as written (the capture flushes every second, plus the COPY). */
    static final Duration WRITE_SETTLE = Duration.ofSeconds(3);

    /** What the captured book says: filled at {@code price}, not filled (with why), or no book (signal price). */
    record Outcome(Kind kind, double price, double limit, String reason) {
        enum Kind { FILLED, NOT_FILLED, NO_BOOK }

        boolean filled() {
            return kind == Kind.FILLED;
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Sleeper sleeper;

    /** How the wait pauses between looks (tests do not sleep). */
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    CapturedEntryFill(DataSource dataSource, Clock clock) {
        this(dataSource, clock, Thread::sleep);
    }

    CapturedEntryFill(DataSource dataSource, Clock clock, Sleeper sleeper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
        this.sleeper = sleeper;
    }

    Outcome fill(long token, Instant decidedAt, OrderRules rules, long latencyMs) {
        Instant arrival = decidedAt.plusMillis(latencyMs);
        Instant end = arrival.plusMillis(Math.round(rules.entryTimeoutSec() * 1000));
        long pollMs = Math.max(50, Math.round(rules.pollMs()));
        // review 23 Nov 2026: a hard bound on the waiting, whatever the clock does (a frozen test or replay clock would
        // otherwise wait for ever on a window that never closes): the whole window plus its settling, in polls
        long maxPolls = (Duration.between(decidedAt, end).plus(WRITE_SETTLE).toMillis() / pollMs) + 20;
        long polls = 0;
        // the book at the signal first, once written (review 23 Nov 2026: read at once, the newest tick before the signal
        // may not be written yet, and the limit would come from an older ask: the Kotak bug of the same day)
        while (!written(token, decidedAt) && polls++ < maxPolls) {
            if (!pause(pollMs)) {
                break;
            }
        }
        Double ask0 = askAt(token, decidedAt);
        if (ask0 == null) {
            return new Outcome(Outcome.Kind.NO_BOOK, Double.NaN, Double.NaN, "no captured ask within "
                    + BOOK_AGE.toSeconds() + " s before the signal");
        }
        double limit = round(ask0 + rules.entryBufferTicks() * rules.tick(), rules.tick());
        while (true) {
            Double rested = firstAskWithin(token, arrival, end, limit);
            // the book on arrival is complete once a later tick is written (the capture writes in order) or it settled
            boolean arrivalWritten = rested != null || written(token, arrival);
            if (arrivalWritten) {
                Double atArrival = askAt(token, arrival);
                if (atArrival != null && atArrival <= limit + 1e-9) {
                    return new Outcome(Outcome.Kind.FILLED, atArrival, limit, null);     // marketable: at the ask
                }
            }
            if (rested != null) {
                return new Outcome(Outcome.Kind.FILLED, limit, limit, null);             // resting: at its limit
            }
            boolean over = !clock.instant().isBefore(end.plus(WRITE_SETTLE));
            if (over || polls++ >= maxPolls || !pause(pollMs)) {
                Double atArrival = askAt(token, arrival);
                return new Outcome(Outcome.Kind.NOT_FILLED, Double.NaN, limit, "entry timeout: 0 filled (paper, captured "
                        + "book: limit " + fmt(limit) + ", ask " + (atArrival == null ? "none" : fmt(atArrival))
                        + " on arrival and never at or below the limit within " + fmt(rules.entryTimeoutSec()) + " s"
                        + (over ? "" : "; stopped waiting") + ")");
            }
        }
    }

    /** True once the book at {@code at} is complete: a later tick is written, or the capture has had time to write it. */
    private boolean written(long token, Instant at) {
        if (!clock.instant().isBefore(at.plus(WRITE_SETTLE))) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from md.option_tick where "
                + "instrument_token = ? and recv_ts > ? and recv_ts <= ?)", Boolean.class, token, Timestamp.from(at),
                Timestamp.from(at.plus(WRITE_SETTLE))));
    }

    /** One pause between looks; false when interrupted (the wait ends, with what is known). */
    private boolean pause(long ms) {
        try {
            sleeper.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The best ask of the newest captured tick at or before {@code at}, at most {@link #BOOK_AGE} old; null when none. */
    private Double askAt(long token, Instant at) {
        List<Double> asks = jdbc.queryForList("select ask_px[1] from md.option_tick where instrument_token = ? "
                + "and recv_ts <= ? and recv_ts > ? and ask_px[1] > 0 order by recv_ts desc limit 1", Double.class, token,
                Timestamp.from(at), Timestamp.from(at.minus(BOOK_AGE)));
        return asks.isEmpty() ? null : asks.getFirst();
    }

    /** The first captured ask at or below {@code limit} after {@code from} up to {@code to}; null when none. */
    private Double firstAskWithin(long token, Instant from, Instant to, double limit) {
        List<Double> asks = jdbc.queryForList("select ask_px[1] from md.option_tick where instrument_token = ? "
                + "and recv_ts > ? and recv_ts <= ? and ask_px[1] > 0 and ask_px[1] <= ? order by recv_ts limit 1",
                Double.class, token, Timestamp.from(from), Timestamp.from(to), limit + 1e-9);
        return asks.isEmpty() ? null : asks.getFirst();
    }

    private static double round(double price, double tick) {
        return Math.round(price / tick) * tick;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
