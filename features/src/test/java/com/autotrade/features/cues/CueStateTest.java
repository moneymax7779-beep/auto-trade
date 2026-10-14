package com.autotrade.features.cues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.core.history.GiftQuote;
import com.autotrade.core.history.ParticipantOi;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.CueFeatures;

/** Features v13 cues: positioning of the previous published sessions; GIFT quotes only once fetched. */
class CueStateTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 10, 15);
    private static final LocalDate NEAR = LocalDate.of(2026, 10, 27);

    private static Instant ist(String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(MarketTime.IST).toInstant();
    }

    private static GiftQuote quote(String exchange, String fetched, double last, double pct) {
        return new GiftQuote(ist(exchange), ist(fetched), NEAR, last, Double.NaN, pct, 100);
    }

    /** 14 Oct 2026 as published by NSE (fao_participant_oi_14102026.csv), and an invented 13 Oct for the change. */
    private static List<ParticipantOi> positioning() {
        LocalDate d14 = LocalDate.of(2026, 10, 14), d13 = LocalDate.of(2026, 10, 13);
        return List.of(
                new ParticipantOi(d14, "FII", 34282, 330979, 3594546, 2850560, 675757, 1095900, 1008663, 551135, 6031370, 5405143),
                new ParticipantOi(d14, "Client", 296818, 51460, 3442948, 220873, 3171120, 2489251, 2983699, 3195752, 12631615, 8914047),
                new ParticipantOi(d14, "Pro", 54685, 33368, 843800, 412780, 1056241, 896996, 1009140, 811210, 4959009, 4746402),
                new ParticipantOi(d13, "FII", 40000, 320000, 0, 0, 0, 0, 0, 0, 0, 0),
                new ParticipantOi(SESSION, "FII", 1, 1, 0, 0, 0, 0, 0, 0, 0, 0));   // today's file: never used today
    }

    @Test
    void positioningIsThePreviousPublishedSessionsNeverToday() {
        CueFeatures f = new CueState(SESSION, positioning(), List::of).features(ist("2026-10-15T09:15:00"));
        assertThat(f.fiiIndexFutLongPct()).isCloseTo(100.0 * 34282 / (34282 + 330979), within(1e-9));   // 9.39 %
        assertThat(f.fiiIndexFutLongPctChange()).isCloseTo(100.0 * 34282 / 365261 - 100.0 * 40000 / 360000, within(1e-9));
        assertThat(f.fiiIndexCallNet()).isEqualTo(675757 - 1008663);
        assertThat(f.fiiIndexPutNet()).isEqualTo(1095900 - 551135);
        assertThat(f.clientIndexFutLongPct()).isCloseTo(100.0 * 296818 / (296818 + 51460), within(1e-9));
        assertThat(f.positioningAgeDays()).isEqualTo(1);
        assertThat(f.giftNiftyLast()).isNaN();
    }

    @Test
    void aGiftQuoteCountsOnlyFromItsFetchAndTheMoveIsFromThePreviousIndianClose() {
        List<GiftQuote> quotes = new ArrayList<>(List.of(
                quote("2026-10-14T15:29:00", "2026-10-14T15:29:30", 22700.0, 0.10),    // the previous close reference
                quote("2026-10-14T23:50:00", "2026-10-14T23:50:30", 22600.0, -0.30),
                quote("2026-10-15T08:45:00", "2026-10-15T08:50:00", 22540.0, -0.56)));  // fetched 08:50
        CueState state = new CueState(SESSION, List.of(), () -> quotes);
        CueFeatures at0849 = state.features(ist("2026-10-15T08:49:00"));
        assertThat(at0849.giftNiftyLast()).isEqualTo(22600.0);                         // 08:45 quote not fetched yet
        assertThat(at0849.giftNiftyMovePct()).isCloseTo(100 * (22600.0 / 22700.0 - 1), within(1e-9));
        CueFeatures at0915 = state.features(ist("2026-10-15T09:15:00"));
        assertThat(at0915.giftNiftyLast()).isEqualTo(22540.0);
        assertThat(at0915.giftNiftyChangePct()).isEqualTo(-0.56);
        assertThat(at0915.giftNiftyQuoteAgeMin()).isCloseTo(30.0, within(1e-9));
        assertThat(at0915.giftNiftyMovePct()).isCloseTo(100 * (22540.0 / 22700.0 - 1), within(1e-9));
    }

    @Test
    void mondayMorningMeasuresTheMoveFromFridaysCloseAndStaleQuotesAreIgnored() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        List<GiftQuote> quotes = List.of(
                new GiftQuote(ist("2026-10-16T15:30:00"), ist("2026-10-16T15:30:10"), NEAR, 22000.0, Double.NaN, 0.0, 1),
                new GiftQuote(ist("2026-10-17T02:40:00"), ist("2026-10-17T02:40:10"), NEAR, 22220.0, Double.NaN, 1.0, 1),
                new GiftQuote(ist("2026-10-19T08:30:00"), ist("2026-10-19T08:30:10"), NEAR, 22330.0, Double.NaN, 1.5, 1));
        CueFeatures f = new CueState(monday, List.of(), () -> quotes).features(ist("2026-10-19T09:15:00"));
        assertThat(f.giftNiftyMovePct()).isCloseTo(100 * (22330.0 / 22000.0 - 1), within(1e-9));
        // only Saturday's 02:40 quote known at Monday 09:15 if the morning one was never fetched: 30 hours old, NaN
        CueFeatures stale = new CueState(monday, List.of(), () -> quotes.subList(0, 2)).features(ist("2026-10-19T09:15:00"));
        assertThat(stale.giftNiftyLast()).isNaN();
        assertThat(stale.giftNiftyMovePct()).isNaN();
    }
}
