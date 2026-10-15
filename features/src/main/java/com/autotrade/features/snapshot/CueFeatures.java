package com.autotrade.features.snapshot;

/**
 * Pre-open and positioning context (features v13, {@code cues.enabled}): GIFT Nifty, which trades through the night and
 * so carries the global session, and NSE's participant-wise open interest of the last published session (the evening
 * before). Point-in-time: a GIFT quote counts from the moment it was fetched; positioning is the previous session's.
 * <ul>
 * <li>{@code giftNiftyLast} / {@code giftNiftyChangePct}: near-month GIFT Nifty futures, its % change against the
 *     previous settlement as NSE IX reports it; {@code giftNiftyQuoteAgeMin}: minutes since that quote's exchange time
 *     (NaN without a quote in the last 12 hours);</li>
 * <li>{@code giftNiftyMovePct}: GIFT's move from its latest quote within Indian hours (09:15-15:30 IST) on an earlier
 *     day (the previous Indian close, as fetched) to the latest quote: the overnight move the index has not seen yet;</li>
 * <li>{@code fiiIndexFutLongPct}: FII index futures long as % of long + short; {@code fiiIndexFutLongPctChange}: its
 *     change from the session before; {@code fiiIndexCallNet} / {@code fiiIndexPutNet}: FII index options long minus
 *     short contracts; {@code clientIndexFutLongPct} / {@code proIndexFutLongPct}: the same for clients and props;
 *     {@code positioningAgeDays}: calendar days from that session to this one (NaN without data).</li>
 * </ul>
 */
public record CueFeatures(
        double giftNiftyLast,
        double giftNiftyChangePct,
        double giftNiftyQuoteAgeMin,
        double giftNiftyMovePct,
        double fiiIndexFutLongPct,
        double fiiIndexFutLongPctChange,
        double fiiIndexCallNet,
        double fiiIndexPutNet,
        double clientIndexFutLongPct,
        double proIndexFutLongPct,
        double positioningAgeDays) {

    public static final CueFeatures EMPTY = new CueFeatures(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
}
