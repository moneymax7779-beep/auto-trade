package com.autotrade.broker;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What an account carried into today, as {@link BrokerConnection#carriedIn} returns it, kept for the IST day (review
 * 16 Nov 2026): the carry is fixed for the day, so one positions read is enough. A read before 09:15 IST is not kept
 * (the broker may not have rolled the day yet). A failed read is NOT "nothing carried" (second review: the reconciler
 * then cut our stop as if another product were short): it throws RETRYABLE, and the next {@link #RETRY} throws the
 * same at once without reading (a guarded exit does not wait on a positions read it can do without); a lost session is
 * thrown, as every other read throws it.
 */
public final class DailyCarry {

    private static final System.Logger log = System.getLogger(DailyCarry.class.getName());
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime KEPT_FROM = LocalTime.of(9, 15);
    /** After a failed read, how long the carry is unknown without reading again. */
    public static final java.time.Duration RETRY = java.time.Duration.ofSeconds(30);

    private final String broker;
    private final Clock clock;
    private volatile Kept kept;
    private volatile java.time.Instant failedAt;
    private volatile String failure;

    private record Kept(LocalDate day, Map<String, Long> byKey) {
    }

    public DailyCarry(String broker) {
        this(broker, Clock.systemUTC());
    }

    public DailyCarry(String broker, Clock clock) {
        this.broker = broker;
        this.clock = clock;
    }

    /** Today's carry: the kept one, else {@code read} (instrument key → net quantity carried in; zeros left out). */
    public Map<String, Long> get(Supplier<Map<String, Long>> read) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(IST));
        Kept k = kept;
        if (k != null && k.day().equals(now.toLocalDate())) {
            return k.byKey();
        }
        java.time.Instant failed = failedAt;
        if (failed != null && clock.instant().isBefore(failed.plus(RETRY))) {
            throw unknown(failure);
        }
        Map<String, Long> byKey;
        try {
            byKey = Map.copyOf(read.get());
        } catch (BrokerException e) {
            if (e.kind() == BrokerException.Kind.SESSION) {
                throw e;
            }
            failure = e.getMessage();
            failedAt = clock.instant();
            log.log(System.Logger.Level.WARNING, broker + " positions not read for what was carried into today ("
                    + e.getMessage() + "): unknown for " + RETRY.toSeconds() + " s");
            throw unknown(failure);
        }
        failedAt = null;
        if (!now.toLocalTime().isBefore(KEPT_FROM)) {
            kept = new Kept(now.toLocalDate(), byKey);
        }
        return byKey;
    }

    private BrokerException unknown(String why) {
        return new BrokerException(BrokerException.Kind.RETRYABLE, broker + ": what was carried into today is not known ("
                + why + ")");
    }
}
