package com.autotrade.md.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.core.history.GiftQuote;
import com.autotrade.core.history.ParticipantOi;
import com.autotrade.core.time.MarketTime;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** feature/missing-data (V20): GIFT Nifty quotes and participant OI on the real schema, point-in-time reads. */
@Testcontainers(disabledWithoutDocker = true)
class ReferenceStoreCuesIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static ReferenceStore store;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new ReferenceStore(dataSource);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private static ReferenceStore.ParticipantRow fii(LocalDate day, long longs, long shorts) {
        return new ReferenceStore.ParticipantRow(new ParticipantOi(day, "FII", longs, shorts, 1, 2, 3, 4, 5, 6, 7, 8), 9, 10, 11, 12);
    }

    private static Instant ist(String t) {
        return LocalDateTime.parse(t).atZone(MarketTime.IST).toInstant();
    }

    @Test
    void participantOiIsReadStrictlyBeforeTheSessionNewestFirstAndUpsertReplaces() {
        LocalDate d12 = LocalDate.of(2026, 10, 12), d13 = LocalDate.of(2026, 10, 13), d14 = LocalDate.of(2026, 10, 14);
        assertThat(store.upsertParticipantOi(List.of(fii(d12, 10, 90), fii(d13, 20, 80), fii(d14, 30, 70)))).isEqualTo(3);
        assertThat(store.upsertParticipantOi(List.of(fii(d14, 34282, 330979)))).isEqualTo(1);       // replaced, not added
        List<ParticipantOi> forOct14 = store.participantOi(d14, 2);
        assertThat(forOct14).extracting(ParticipantOi::session).containsExactly(d13, d12);        // never the 14th itself
        List<ParticipantOi> forOct15 = store.participantOi(d14.plusDays(1), 1);
        assertThat(forOct15).singleElement().satisfies(p -> {
            assertThat(p.futureIndexLong()).isEqualTo(34282);
            assertThat(p.optionIndexPutShort()).isEqualTo(6);
        });
    }

    @Test
    void giftQuotesAreKeptOnceAndAMondayReadsFridaysIndianHours() {
        LocalDate expiry = LocalDate.of(2026, 10, 27);
        GiftQuote friday = new GiftQuote(ist("2026-10-16T15:29:00"), ist("2026-10-16T15:29:30"), expiry, 22000, -10, -0.05, 5);
        GiftQuote monday = new GiftQuote(ist("2026-10-19T08:45:00"), ist("2026-10-19T08:46:00"), expiry, 22100, 100, 0.45, 9);
        GiftQuote old = new GiftQuote(ist("2026-10-14T15:00:00"), ist("2026-10-14T15:00:30"), expiry, 21000, 0, 0, 1);
        assertThat(store.insertGiftQuotes(List.of(friday, monday, old))).isEqualTo(3);
        assertThat(store.insertGiftQuotes(List.of(monday))).isZero();                              // same quote again
        List<GiftQuote> quotes = store.giftNiftyQuotes(LocalDate.of(2026, 10, 19));
        assertThat(quotes).extracting(GiftQuote::last).containsExactly(22000.0, 22100.0);         // not Wednesday's (before Thursday 09:00)
        assertThat(quotes.getLast().changePct()).isEqualTo(0.45);
    }
}
