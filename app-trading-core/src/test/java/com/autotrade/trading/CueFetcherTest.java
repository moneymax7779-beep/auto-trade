package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.core.history.GiftQuote;
import com.autotrade.core.time.MarketTime;
import com.autotrade.md.store.ReferenceStore;

/** Parsers against the real files (14-15 Oct 2026) and the collector's timing. */
class CueFetcherTest {

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src", "test", "resources", "cues", name));
    }

    @Test
    void parsesNseParticipantOiByColumnName() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 14);
        List<ReferenceStore.ParticipantRow> rows = CueFetcher.parseParticipantOi(fixture("fao_participant_oi_14102026.csv"), day);
        assertThat(rows).extracting(r -> r.oi().clientType()).containsExactly("Client", "DII", "FII", "Pro", "TOTAL");
        var fii = rows.get(2).oi();
        assertThat(fii.session()).isEqualTo(day);
        assertThat(fii.futureIndexLong()).isEqualTo(34282);
        assertThat(fii.futureIndexShort()).isEqualTo(330979);
        assertThat(fii.futureStockShort()).isEqualTo(2850560);       // "Future Stock Short       " (padded header)
        assertThat(fii.optionIndexCallLong()).isEqualTo(675757);
        assertThat(fii.optionIndexPutShort()).isEqualTo(551135);
        assertThat(fii.totalShort()).isEqualTo(5405143);
        assertThat(rows.get(2).optionStockPutShort()).isEqualTo(239005);
        assertThat(fii.futureIndexLongPct()).isBetween(9.38, 9.39);
        assertThatThrownBy(() -> CueFetcher.parseParticipantOi("<html>not found</html>", day))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesGiftNiftyFuturesFromNseIxMarketRates() throws Exception {
        Instant fetched = Instant.parse("2026-10-14T18:46:00Z");
        List<GiftQuote> quotes = CueFetcher.parseGift(fixture("nseix_market_rate_derivative.json"), fetched);
        assertThat(quotes).hasSize(2);
        GiftQuote near = quotes.stream().filter(q -> q.expiry().equals(LocalDate.of(2026, 10, 27))).findFirst().orElseThrow();
        assertThat(near.last()).isEqualTo(22570.5);
        assertThat(near.change()).isEqualTo(-52.0);
        assertThat(near.changePct()).isEqualTo(-0.23);                // ".23" style numbers
        assertThat(near.contractsTraded()).isEqualTo(78373);
        assertThat(near.exchangeTime()).isEqualTo(LocalDateTime.of(2026, 10, 15, 0, 15, 40).atZone(MarketTime.IST).toInstant());
        assertThat(near.fetchedAt()).isEqualTo(fetched);
    }

    private static ZonedDateTime ist(String t) {
        return LocalDateTime.parse(t).atZone(MarketTime.IST);
    }

    @Test
    void pollsGiftOutsideTheNightPauseAndParticipantOiOnWeekdayEvenings() {
        Instant polled = ist("2026-10-15T08:40:01").toInstant();
        assertThat(CueScheduler.dueForGift(ist("2026-10-15T08:45:00"), null, 5)).isTrue();
        assertThat(CueScheduler.dueForGift(ist("2026-10-15T08:44:00"), polled, 5)).isFalse();
        assertThat(CueScheduler.dueForGift(ist("2026-10-15T08:44:58"), polled, 5)).isTrue();   // timer drift slack
        assertThat(CueScheduler.dueForGift(ist("2026-10-15T04:00:00"), null, 5)).isFalse();    // NSE IX pause
        assertThat(CueScheduler.dueForParticipant(ist("2026-10-15T19:05:00"), null)).isTrue();
        assertThat(CueScheduler.dueForParticipant(ist("2026-10-15T18:59:00"), null)).isFalse();
        assertThat(CueScheduler.dueForParticipant(ist("2026-10-17T20:00:00"), null)).isFalse();  // Saturday
        Instant tried = ist("2026-10-15T19:05:00").toInstant();
        assertThat(CueScheduler.dueForParticipant(ist("2026-10-15T19:20:00"), tried)).isFalse();
        assertThat(CueScheduler.dueForParticipant(ist("2026-10-15T19:36:00"), tried)).isTrue();
    }
}
