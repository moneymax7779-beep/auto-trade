package com.autotrade.md.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.SessionPhaseEvent;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** The tail against a minimal copy of zt-tiger-v2's two source tables. */
@Testcontainers(disabledWithoutDocker = true)
class ZtTailFeedIT {

    private static final LocalDate SESSION = LocalDate.of(2026, 9, 28);
    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer("postgres:15-alpine");
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table market_tick_records (id bigint primary key, underlying_key text, session_date date, "
                    + "sequence_number bigint, tick_type text, instrument_token bigint, symbol text, "
                    + "exchange_timestamp_ms bigint, received_at timestamp, payload_json text, payload_hash text, "
                    + "analytics_complete boolean, contract_expiry date, contract_lot_size int, depth_complete boolean, "
                    + "exchange_segment text, instrument_type text, quote_source text)");
            s.execute("create index on market_tick_records (underlying_key, session_date, sequence_number)");
            s.execute("create table market_session_price_events (id bigint primary key, received_at_utc timestamptz, "
                    + "event_time_utc timestamptz, session_phase text, price_semantics text, event_time_phase text, "
                    + "received_time_phase text, price double precision, official_final_proven boolean, "
                    + "underlying_continuously_tradable boolean, source text, idempotency_key text, underlying_key text)");
        }
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    @Test
    void catchesUpThenTailsAndDeliversALateCommitOnce() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("delete from market_tick_records");
            s.execute("delete from market_session_price_events");
        }
        insert(100, 1, "09:15:01", 23000);
        insert(101, 2, "09:15:02", 23001);
        ZtTailFeed feed = new ZtTailFeed(dataSource, SESSION, List.of("NIFTY"), Duration.ofMillis(10),
                Duration.ofSeconds(30));
        List<MarketEvent> events = new ArrayList<>();
        feed.seed();
        feed.poll(events::add);
        assertThat(events).extracting(e -> ((IndexTick) e).price()).containsExactly(23000.0, 23001.0);

        insert(103, 4, "09:15:04", 23003);
        feed.poll(events::add);
        insert(102, 3, "09:15:03", 23002); // committed after 103, with a lower id
        feed.poll(events::add);
        feed.poll(events::add); // nothing new, nothing repeated

        assertThat(events).extracting(e -> ((IndexTick) e).price()).containsExactly(23000.0, 23001.0, 23003.0, 23002.0);
        assertThat(feed.lateRows()).isEqualTo(1);
    }

    @Test
    void holdsBackSessionPhaseRowsUntilTicksReachTheirTime() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("delete from market_tick_records");
            s.execute("insert into market_session_price_events (id, received_at_utc, event_time_utc, session_phase, "
                    + "price_semantics, price, official_final_proven, underlying_key) values (1, "
                    + "'2026-09-28 09:45:30+00', '2026-09-28 09:45:30+00', 'CAS_REFERENCE_TRANSITION', 'X', 23010, "
                    + "false, 'NSE:INDEX:NIFTY')");
        }
        insert(200, 1, "09:15:01", 23000); // 03:45:01 UTC, hours before the auction row
        ZtTailFeed feed = new ZtTailFeed(dataSource, SESSION, List.of("NIFTY"), Duration.ofMillis(10),
                Duration.ofSeconds(30));
        List<MarketEvent> events = new ArrayList<>();
        feed.seed();
        feed.poll(events::add);
        assertThat(events).hasSize(1).first().isInstanceOf(IndexTick.class);

        insert(201, 2, "15:16:00", 23020); // ticks now past the auction row's time
        feed.poll(events::add);
        assertThat(events).hasSize(3);
        assertThat(events.getLast()).isInstanceOf(SessionPhaseEvent.class);
    }

    private static void insert(long id, long sequence, String time, double price) throws Exception {
        String payload = "{\"price\":" + price + ",\"atm\":23000,\"volume\":0,\"close\":22990.0}";
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(
                "insert into market_tick_records (id, underlying_key, session_date, sequence_number, tick_type, symbol, "
                        + "received_at, payload_json, payload_hash) values (?, 'NSE:INDEX:NIFTY', ?, ?, 'CASH', 'NIFTY', "
                        + "?::timestamp, ?, ?)")) {
            p.setLong(1, id);
            p.setObject(2, SESSION);
            p.setLong(3, sequence);
            p.setString(4, SESSION + " " + time);
            p.setString(5, payload);
            p.setString(6, hash);
            p.executeUpdate();
        }
    }
}
