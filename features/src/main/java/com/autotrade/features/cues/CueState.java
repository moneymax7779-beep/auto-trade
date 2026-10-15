package com.autotrade.features.cues;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.autotrade.core.history.GiftQuote;
import com.autotrade.core.history.ParticipantOi;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.CueFeatures;

/**
 * Features v13 cues for one session ({@link CueFeatures}). Positioning is fixed at the open (the previous published
 * sessions); GIFT quotes come from {@code quotes}, re-read at most once a minute so a live session sees new fetches,
 * and are used only once fetched (point-in-time in replay and live alike).
 */
public final class CueState {

    static final Duration MAX_QUOTE_AGE = Duration.ofHours(12);
    static final Duration REFRESH = Duration.ofMinutes(1);
    static final LocalTime INDIAN_OPEN = LocalTime.of(9, 15);
    static final LocalTime INDIAN_CLOSE = LocalTime.of(15, 30);

    private final LocalDate session;
    private final Supplier<List<GiftQuote>> quotes;
    private final double fiiLongPct, fiiLongPctChange, fiiCallNet, fiiPutNet, clientLongPct, proLongPct, ageDays;
    private List<GiftQuote> cached = List.of();
    private Instant readAt;

    /** {@code positioning}: rows of the latest sessions before {@code session}, newest first (two sessions suffice). */
    public CueState(LocalDate session, List<ParticipantOi> positioning, Supplier<List<GiftQuote>> quotes) {
        this.session = session;
        this.quotes = quotes;
        Map<LocalDate, Map<String, ParticipantOi>> byDay = positioning.stream()
                .filter(p -> p.session().isBefore(session))
                .collect(Collectors.groupingBy(ParticipantOi::session, Collectors.toMap(ParticipantOi::clientType, p -> p,
                        (a, b) -> a)));
        List<LocalDate> days = byDay.keySet().stream().sorted(Comparator.reverseOrder()).toList();
        Optional<Map<String, ParticipantOi>> latest = days.isEmpty() ? Optional.empty() : Optional.of(byDay.get(days.get(0)));
        Optional<Map<String, ParticipantOi>> before = days.size() < 2 ? Optional.empty() : Optional.of(byDay.get(days.get(1)));
        this.fiiLongPct = longPct(latest, "FII");
        this.fiiLongPctChange = fiiLongPct - longPct(before, "FII");
        this.fiiCallNet = latest.map(m -> m.get("FII")).map(p -> (double) p.optionIndexCallNet()).orElse(Double.NaN);
        this.fiiPutNet = latest.map(m -> m.get("FII")).map(p -> (double) p.optionIndexPutNet()).orElse(Double.NaN);
        this.clientLongPct = longPct(latest, "Client");
        this.proLongPct = longPct(latest, "Pro");
        this.ageDays = days.isEmpty() ? Double.NaN : ChronoUnit.DAYS.between(days.get(0), session);
    }

    private static double longPct(Optional<Map<String, ParticipantOi>> day, String type) {
        return day.map(m -> m.get(type)).map(ParticipantOi::futureIndexLongPct).orElse(Double.NaN);
    }

    public CueFeatures features(Instant time) {
        if (readAt == null || !time.isBefore(readAt.plus(REFRESH))) {
            cached = quotes.get();
            readAt = time;
        }
        // the near-month contract: the earliest expiry among the quotes known at this time
        List<GiftQuote> known = cached.stream().filter(q -> !q.fetchedAt().isAfter(time)).toList();
        Optional<LocalDate> near = known.stream().map(GiftQuote::expiry).filter(e -> !e.isBefore(session))
                .min(Comparator.naturalOrder());
        double last = Double.NaN, pct = Double.NaN, age = Double.NaN, move = Double.NaN;
        if (near.isPresent()) {
            List<GiftQuote> contract = known.stream().filter(q -> q.expiry().equals(near.get()))
                    .sorted(Comparator.comparing(GiftQuote::exchangeTime)).toList();
            GiftQuote latest = contract.getLast();
            Duration since = Duration.between(latest.exchangeTime(), time);
            if (since.compareTo(MAX_QUOTE_AGE) <= 0) {
                last = latest.last();
                pct = latest.changePct();
                age = Math.max(0, since.toSeconds() / 60.0);
                // the reference: the latest quote between 09:15 and 15:30 IST on a day before the latest quote's Indian
                // session (a quote after 15:30 belongs to the next session), e.g. Friday's 15:30 for Monday morning
                java.time.ZonedDateTime at = latest.exchangeTime().atZone(MarketTime.IST);
                LocalDate sessionOfLatest = at.toLocalTime().isAfter(INDIAN_CLOSE) ? at.toLocalDate().plusDays(1)
                        : at.toLocalDate();
                Optional<GiftQuote> atClose = contract.stream().filter(q -> {
                    java.time.ZonedDateTime z = q.exchangeTime().atZone(MarketTime.IST);
                    return z.toLocalDate().isBefore(sessionOfLatest) && !z.toLocalTime().isBefore(INDIAN_OPEN)
                            && !z.toLocalTime().isAfter(INDIAN_CLOSE);     // in Indian hours: not a night quote
                }).reduce((a, b) -> b);
                if (atClose.isPresent() && atClose.get().last() > 0) {
                    move = 100.0 * (latest.last() / atClose.get().last() - 1);
                }
            }
        }
        return new CueFeatures(last, pct, age, move, fiiLongPct, fiiLongPctChange, fiiCallNet, fiiPutNet, clientLongPct,
                proLongPct, ageDays);
    }
}
