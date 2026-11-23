package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Fix 4 (23 Nov 2026): a paper entry on follower-risk v19 fills as a real marketable limit would have on the captured
 * book. The two cases are the real ticks of 23 Nov (our own capture): 11:03 NIFTY 25550 PE (every real entry unfilled,
 * the v18 twin "filled" at 80.35) and 12:06 NIFTY 25500 PE (a fresh quote fills at once).
 */
@Testcontainers
class CapturedEntryFillIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 23);
    private static final long PE_25550 = 64476, PE_25500 = 64470, RESTING = 1001, NO_BOOK = 1002, LATE = 1003;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static OrderRules rules;
    private static long latency;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        FollowerRisk v19 = FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk.v19.yaml"));
        rules = v19.orders();
        latency = v19.paperFill().latencyMs();
        // 11:03, NIFTY 25550 PE: the ask jumped from 80.35 to 82 within a second of the signal and never came back
        ask(PE_25550, "11:02:59.085", 80.35);
        ask(PE_25550, "11:02:59.891", 80.35);
        ask(PE_25550, "11:03:00.710", 82.00);
        ask(PE_25550, "11:03:02.291", 81.70);
        ask(PE_25550, "11:03:03.892", 82.40);
        ask(PE_25550, "11:03:05.684", 82.65);
        ask(PE_25550, "11:03:07.289", 83.00);
        ask(PE_25550, "11:03:08.889", 82.30);
        ask(PE_25550, "11:03:10.685", 81.35);
        // 12:06, NIFTY 25500 PE
        ask(PE_25500, "12:05:57.786", 68.20);
        ask(PE_25500, "12:05:59.393", 69.85);
        ask(PE_25500, "12:06:00.193", 69.85);
        ask(PE_25500, "12:06:00.713", 69.85);
        ask(PE_25500, "12:06:01.190", 69.15);
        ask(PE_25500, "12:06:02.788", 70.05);
        // a resting limit: above it on arrival, at it 3 s later
        ask(RESTING, "13:00:00.000", 100.00);
        ask(RESTING, "13:00:00.400", 101.00);
        ask(RESTING, "13:00:03.500", 100.10);
        // written late (the live case): only the signal's tick at first
        ask(LATE, "14:00:00.000", 50.00);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    @Test
    void theElevenOhThreeEntryIsNotFilledAsNoRealOneWas() {
        CapturedEntryFill.Outcome o = fill(PE_25550, "11:03:00.215", Clock.fixed(at("15:30:00"), ZoneOffset.UTC));
        assertThat(o.kind()).isEqualTo(CapturedEntryFill.Outcome.Kind.NOT_FILLED);
        assertThat(o.limit()).isEqualTo(80.55, org.assertj.core.data.Offset.offset(1e-9));    // 80.35 + 4 ticks
        assertThat(o.reason()).startsWith("entry timeout: 0 filled").contains("limit 80.55").contains("ask 82.00");
    }

    @Test
    void theTwelveOhSixEntryFillsAtTheAskOnArrival() {
        CapturedEntryFill.Outcome o = fill(PE_25500, "12:06:00.251", Clock.fixed(at("15:30:00"), ZoneOffset.UTC));
        assertThat(o.filled()).isTrue();
        assertThat(o.price()).isEqualTo(69.85);
        assertThat(o.limit()).isEqualTo(70.05, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aRestingLimitFillsAtItsLimitWhenTheAskComesDown() {
        CapturedEntryFill.Outcome o = fill(RESTING, "13:00:00.100", Clock.fixed(at("15:30:00"), ZoneOffset.UTC));
        assertThat(o.filled()).isTrue();
        assertThat(o.price()).isEqualTo(100.20, org.assertj.core.data.Offset.offset(1e-9));   // its limit, not 100.10
    }

    @Test
    void noCapturedBookMeansTheSignalsPriceAsBefore() {
        CapturedEntryFill.Outcome o = fill(NO_BOOK, "13:00:00.000", Clock.fixed(at("15:30:00"), ZoneOffset.UTC));
        assertThat(o.kind()).isEqualTo(CapturedEntryFill.Outcome.Kind.NO_BOOK);
    }

    @Test
    void liveItWaitsForTheBookAndFillsOnceTheTickIsWritten() {
        // the decision at 14:00:00.000; the clock starts a second later, the ticks after it are written while it waits
        AtomicReference<Instant> now = new AtomicReference<>(at("14:00:00.900"));
        AtomicInteger sleeps = new AtomicInteger();
        Clock clock = new Clock() {
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return now.get();
            }
        };
        java.util.concurrent.atomic.AtomicBoolean written = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean arrival = new java.util.concurrent.atomic.AtomicBoolean();
        CapturedEntryFill fills = new CapturedEntryFill(dataSource, clock, ms -> {
            now.set(now.get().plusMillis(ms));
            sleeps.incrementAndGet();
            // each tick lands at the capture's next write, about a second after it was received
            if (!now.get().isBefore(at("14:00:01.300")) && arrival.compareAndSet(false, true)) {
                ask(LATE, "14:00:00.300", 52.00);                // on arrival: above the limit (50.20)
            }
            if (!now.get().isBefore(at("14:00:05")) && written.compareAndSet(false, true)) {
                ask(LATE, "14:00:04.000", 50.10);                // later: at or below it
            }
        });
        CapturedEntryFill.Outcome o = fills.fill(LATE, at("14:00:00"), rules, latency);
        assertThat(written).as("it waited for the book to be written: " + o + " sleeps " + sleeps + " now " + now).isTrue();
        assertThat(o.filled()).isTrue();
        assertThat(o.price()).isEqualTo(50.20, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void liveItGivesUpOnceTheTimeoutHasPassedAndSettled() {
        AtomicReference<Instant> now = new AtomicReference<>(at("12:06:01"));
        AtomicInteger sleeps = new AtomicInteger();
        CapturedEntryFill fills = new CapturedEntryFill(dataSource, new Clock() {
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return now.get();
            }
        }, ms -> {
            sleeps.incrementAndGet();
            now.set(now.get().plusMillis(ms));
        });
        CapturedEntryFill.Outcome o = fills.fill(PE_25550, at("11:03:00.215").plusSeconds(0), rules, latency);
        assertThat(o.kind()).as("an old window is decided at once").isEqualTo(CapturedEntryFill.Outcome.Kind.NOT_FILLED);
        assertThat(sleeps.get()).isZero();
        // a fresh window with nothing below the limit: it waits to the end of the timeout plus the settle, then gives up
        now.set(at("11:03:00.715"));
        CapturedEntryFill.Outcome waited = fills.fill(PE_25550, at("11:03:00.215"), rules, latency);
        assertThat(waited.kind()).isEqualTo(CapturedEntryFill.Outcome.Kind.NOT_FILLED);
        assertThat(sleeps.get()).isPositive();
        assertThat(now.get()).isAfterOrEqualTo(at("11:03:00.215").plusMillis(latency)
                .plusMillis(Math.round(rules.entryTimeoutSec() * 1000)).plus(CapturedEntryFill.WRITE_SETTLE));
    }

    @Test
    void aFrozenClockNeverWaitsForEver() {
        // review 23 Nov 2026: a test or replay clock that never moves, inside the window: bounded, decided with what is known
        AtomicInteger sleeps = new AtomicInteger();
        CapturedEntryFill.Outcome o = new CapturedEntryFill(dataSource,
                Clock.fixed(at("11:03:01"), ZoneOffset.UTC), ms -> sleeps.incrementAndGet())
                .fill(PE_25550, at("11:03:00.215"), rules, latency);
        assertThat(o.kind()).isEqualTo(CapturedEntryFill.Outcome.Kind.NOT_FILLED);
        assertThat(o.reason()).contains("stopped waiting");
        assertThat(sleeps.get()).isPositive().isLessThan(200);
    }

    private static CapturedEntryFill.Outcome fill(long token, String decided, Clock clock) {
        return new CapturedEntryFill(dataSource, clock, ms -> {
            throw new AssertionError("a settled window never waits");
        }).fill(token, at(decided), rules, latency);
    }

    private static Instant at(String istTime) {
        return DAY.atTime(LocalTime.parse(istTime)).atZone(IST).toInstant();
    }

    private static void ask(long token, String istTime, double ask) {
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, "
                + "symbol, expiry, strike, option_type, lot_size, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, "
                + "ask_orders, analytics_complete, depth_complete) values (1, ?, 'NIFTY', ?, 1, ?, 'NIFTY TEST PE', ?, 25500, "
                + "'PE', 65, ?, array[?]::float8[], '{650}', '{3}', array[?]::float8[], '{650}', '{3}', false, true)", DAY,
                Timestamp.from(at(istTime)), token, DAY, ask, ask - 0.15, ask);
    }
}
