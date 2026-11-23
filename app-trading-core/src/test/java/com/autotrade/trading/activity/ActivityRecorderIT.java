package com.autotrade.trading.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** User activity (V52) against the real schema: sign-ins, beats, clamping, the union of tabs, ends, totals, retention. */
@Testcontainers(disabledWithoutDocker = true)
class ActivityRecorderIT {

    private static PostgreSQLContainer postgres;
    private static HikariDataSource db;
    private static JdbcTemplate jdbc;
    private static final ActivityProperties PROPS = new ActivityProperties(true, null, null, null, null, null);
    private static final UserAgents.Device MAC_CHROME = new UserAgents.Device("Chrome", "131", "macOS", null, "DESKTOP");
    private static final UserAgents.Device IPHONE = new UserAgents.Device("Safari", "18", "iOS", "18.1", "MOBILE");

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void plus(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("timescale/timescaledb:2.30.1-pg17")
                .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        db = new HikariDataSource(config);
        Flyway.configure().dataSource(db).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(db);
    }

    @AfterAll
    static void stop() {
        db.close();
        postgres.stop();
    }

    /** 10:00:00 IST on a weekday, on a minute boundary. */
    private static Instant at(String istTime) {
        return LocalDate.of(2026, 11, 24).atTime(java.time.LocalTime.parse(istTime)).atZone(ActivityRecorder.IST).toInstant();
    }

    private static Map<String, Object> login(String key) {
        return jdbc.queryForMap("select subject, ended_at, end_reason, host(ip) ip, forwarded_chain, browser, os, device_type, "
                + "installed_app, last_seen_at from acct.user_login where login_key = ?", key);
    }

    @Test
    void aSignInIsRecordedWithItsBrowserAndAddressAndKeptAcrossRestarts() {
        TestClock clock = new TestClock(at("09:00:00"));
        ActivityRecorder r = ActivityRecorder.manual(db, PROPS, clock);
        String key = ActivityRecorder.loginKey("sid-1", "anna", null);
        r.signIn(key, "anna", at("08:59:30"), "49.36.10.20", "49.36.10.20, 136.82.45.194, 35.191.9.8", "UA", MAC_CHROME);
        r.flush();
        assertThat(login(key)).containsEntry("subject", "anna").containsEntry("ip", "49.36.10.20")
                .containsEntry("browser", "Chrome").containsEntry("os", "macOS").containsEntry("device_type", "DESKTOP")
                .containsEntry("end_reason", null);
        // trading-core restarts: a new recorder, the same Keycloak session signs in again: still one sign-in
        ActivityRecorder after = ActivityRecorder.manual(db, PROPS, clock);
        clock.plus(Duration.ofMinutes(5));
        after.signIn(ActivityRecorder.loginKey("sid-1", "anna", null), "anna", at("08:59:30"), "49.36.10.20", null, "UA",
                MAC_CHROME);
        after.flush();
        assertThat(jdbc.queryForObject("select count(*) from acct.user_login where subject = 'anna'", Integer.class)).isEqualTo(1);
        assertThat(login(key).get("forwarded_chain")).as("kept when the new sight has none")
                .isEqualTo("49.36.10.20, 136.82.45.194, 35.191.9.8");
    }

    @Test
    void beatsAreClampedToTheWallTimeSinceTheTabsLastBeat() {
        TestClock clock = new TestClock(at("10:01:00"));
        ActivityRecorder r = ActivityRecorder.manual(db, PROPS, clock);
        String key = ActivityRecorder.loginKey("sid-2", "ben", null);
        r.signIn(key, "ben", null, null, null, null, MAC_CHROME);
        UUID tab = UUID.randomUUID();
        assertThat(r.beat(key, "ben", tab, 3600, 3600, "/index/NIFTY?x=1", false, false)).as("first beat: 90 s at most")
                .containsExactly(90, 90);
        clock.plus(Duration.ofSeconds(10));
        assertThat(r.beat(key, "ben", tab, 60, 70, "/index/NIFTY", false, false)).as("10 s after the last: 10 s")
                .containsExactly(10, 10);
        clock.plus(Duration.ofSeconds(60));
        assertThat(r.beat(key, "ben", tab, 60, 20, "/today", false, false)).containsExactly(60, 20);
        assertThat(r.beat(key, "ben", tab, -5, 9, null, false, false)).containsExactly(0, 0);
        r.flush();
        Map<String, Object> t = jdbc.queryForMap("select visible_seconds, active_seconds, last_path, closed_at from acct.user_tab "
                + "where tab_id = ?", tab);
        assertThat(t).containsEntry("visible_seconds", 160).containsEntry("active_seconds", 120)
                .containsEntry("last_path", "/today").containsEntry("closed_at", null);
        assertThat(jdbc.queryForObject("select sum(visible_seconds) from acct.user_minute where subject = 'ben'", Long.class))
                .isEqualTo(160L);
        assertThat(jdbc.queryForObject("select max(visible_seconds) from acct.user_minute where subject = 'ben'", Integer.class))
                .isLessThanOrEqualTo(60);
    }

    @Test
    void twoTabsSideBySideCountOnceAndAHiddenTabAddsNothing() {
        TestClock clock = new TestClock(at("11:00:00"));
        ActivityRecorder r = ActivityRecorder.manual(db, PROPS, clock);
        String laptop = ActivityRecorder.loginKey("sid-3a", "cara", null), phone = ActivityRecorder.loginKey("sid-3b", "cara", null);
        r.signIn(laptop, "cara", null, null, null, null, MAC_CHROME);
        r.signIn(phone, "cara", null, null, null, null, IPHONE);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), hidden = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {                       // ten minutes, both on screen the whole time
            clock.plus(Duration.ofSeconds(60));
            r.beat(laptop, "cara", a, 60, 60, "/index/NIFTY", false, false);
            r.beat(phone, "cara", b, 60, 0, "/today", true, false);
            r.beat(laptop, "cara", hidden, 0, 0, "/sessions", false, false);
        }
        r.flush();
        Map<String, Object> today = new ActivityReport(db, clock).dayTotals(LocalDate.of(2026, 11, 24)).stream()
                .filter(x -> "cara".equals(x.get("subject"))).findFirst().orElseThrow();
        assertThat(((Number) today.get("visible_seconds")).longValue()).as("10 min, not 20").isEqualTo(600);
        assertThat(((Number) today.get("active_seconds")).longValue()).isEqualTo(600);
        assertThat(((Number) today.get("logins")).intValue()).isEqualTo(2);
        assertThat(((Number) today.get("devices")).intValue()).isEqualTo(2);
        assertThat(((Number) today.get("tabs")).intValue()).isEqualTo(3);
        assertThat(login(phone)).containsEntry("installed_app", true);
        assertThat(login(laptop)).containsEntry("installed_app", false);

        Map<String, Object> overview = new ActivityReport(db, clock).overview(7);
        @SuppressWarnings("unchecked")
        Map<String, Object> cara = ((List<Map<String, Object>>) overview.get("users")).stream()
                .filter(u -> "cara".equals(u.get("subject"))).findFirst().orElseThrow();
        assertThat(cara).containsEntry("online", true).containsEntry("openSignIns", 2).containsEntry("openTabs", 3L)
                .containsEntry("todayVisibleSeconds", 600L);
        assertThat(new ActivityReport(db, clock).devices("cara", phone)).extracting(d -> d.get("current"))
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void endsLogoutMaxAgeIdleAndAReturnFromIdle() {
        TestClock clock = new TestClock(at("12:00:00"));
        ActivityRecorder r = ActivityRecorder.manual(db, PROPS, clock);
        String out = ActivityRecorder.loginKey("sid-4a", "dev", null), aged = ActivityRecorder.loginKey("sid-4b", "dev", null),
                idle = ActivityRecorder.loginKey("sid-4c", "dev", null);
        for (String k : List.of(out, aged, idle)) {
            r.signIn(k, "dev", null, null, null, null, MAC_CHROME);
        }
        UUID tab = UUID.randomUUID();
        r.beat(out, "dev", tab, 30, 30, "/", false, false);
        r.flush();
        r.end(out, ActivityRecorder.EndReason.LOGOUT);
        r.end(aged, ActivityRecorder.EndReason.MAX_AGE);
        clock.plus(Duration.ofMinutes(1));
        r.seen(out);
        r.flush();
        assertThat(login(out)).containsEntry("end_reason", "LOGOUT");
        assertThat(jdbc.queryForObject("select closed_at is not null from acct.user_tab where tab_id = ?", Boolean.class, tab))
                .as("its tabs closed with it").isTrue();
        assertThat(login(aged)).containsEntry("end_reason", "MAX_AGE");
        clock.plus(Duration.ofMinutes(16));
        r.flush();
        assertThat(login(idle)).containsEntry("end_reason", "IDLE");
        assertThat(login(out)).as("a logout stays a logout").containsEntry("end_reason", "LOGOUT");
        r.seen(idle);                                          // the browser was asleep, now back
        r.flush();
        assertThat(login(idle)).containsEntry("end_reason", null).containsEntry("ended_at", null);
    }

    @Test
    void dailyTotalsAreStoredAndOldAddressesAreShortened() {
        TestClock clock = new TestClock(at("13:00:00"));
        ActivityRecorder r = ActivityRecorder.manual(db, PROPS, clock);
        String key = ActivityRecorder.loginKey("sid-5", "eve", null);
        r.signIn(key, "eve", null, "49.36.10.20", "49.36.10.20, x", null, IPHONE);
        clock.plus(Duration.ofSeconds(45));
        r.beat(key, "eve", UUID.randomUUID(), 45, 10, "/", false, false);
        r.flush();                                             // the first round rolls today up
        Map<String, Object> day = jdbc.queryForMap("select logins, visible_seconds, active_seconds, by_browser::text b "
                + "from acct.user_activity_day where subject = 'eve' and day = '2026-11-24'");
        assertThat(day).containsEntry("logins", 1).containsEntry("visible_seconds", 45).containsEntry("active_seconds", 10);
        assertThat((String) day.get("b")).contains("Safari / iOS");

        // 31 days later, a new recorder's first round: the address is cut to its /24 and the chain dropped
        clock.plus(Duration.ofDays(31));
        jdbc.update("update acct.user_login set last_seen_at = ? where login_key = ?", java.sql.Timestamp.from(clock.now), key);
        ActivityRecorder later = ActivityRecorder.manual(db, PROPS, clock);
        later.flush();
        assertThat(login(key)).containsEntry("forwarded_chain", null);
        assertThat(jdbc.queryForObject("select ip::text from acct.user_login where login_key = ?", String.class, key))
                .isEqualTo("49.36.10.0/24");
        assertThat(jdbc.queryForObject("select count(*) from acct.user_minute where subject = 'eve'", Integer.class))
                .as("raw minutes gone after 14 days").isZero();
        assertThat(jdbc.queryForObject("select visible_seconds from acct.user_activity_day where subject = 'eve' "
                + "and day = '2026-11-24'", Integer.class)).as("the day's total stays").isEqualTo(45);
    }

    @Test
    void offRecordsNothingAndAFullQueueDropsInsteadOfBlocking() {
        ActivityRecorder off = ActivityRecorder.disabled();
        off.signIn("k", "x", null, null, null, null, null);
        assertThat(off.beat("k", "x", UUID.randomUUID(), 60, 60, "/", false, false)).containsExactly(0, 0);
        assertThat(off.counters()).containsEntry("queued", 0);

        ActivityRecorder r = ActivityRecorder.manual(db, PROPS, new TestClock(at("14:00:00")));
        for (int i = 0; i < ActivityRecorder.QUEUE + 5; i++) {
            r.end("nobody-" + i, ActivityRecorder.EndReason.LOGOUT);
        }
        assertThat(r.counters()).containsEntry("dropped", 5L);
    }

    /** Code review 23 Nov 2026: one bad event loses itself only, not the rest of its round. */
    @Test
    void aFailingEventLosesOnlyItself() {
        TestClock clock = new TestClock(at("14:30:00"));
        ActivityRecorder r = ActivityRecorder.manual(db, PROPS, clock);
        String bad = ActivityRecorder.loginKey("sid-7a", "fay", null), good = ActivityRecorder.loginKey("sid-7b", "fay", null);
        r.signIn(bad, "fay", null, "not-an-address", null, null, MAC_CHROME);   // the inet cast fails
        r.signIn(good, "fay", null, "49.36.10.21", null, null, MAC_CHROME);
        r.beat(good, "fay", UUID.randomUUID(), 30, 30, "/", false, false);
        r.flush();
        assertThat(jdbc.queryForObject("select count(*) from acct.user_login where login_key = ?", Integer.class, bad)).isZero();
        assertThat(login(good)).containsEntry("ip", "49.36.10.21");
        assertThat(jdbc.queryForObject("select sum(visible_seconds) from acct.user_minute where subject = 'fay'", Long.class))
                .isEqualTo(30L);
        assertThat(((Number) r.counters().get("failedWrites")).longValue()).isEqualTo(1L);
    }

    /** Code review: made-up tab ids cannot each add a first beat's time without end. */
    @Test
    void aSignInMayStartALimitedNumberOfTabsADay() {
        ActivityRecorder r = ActivityRecorder.manual(db, PROPS, new TestClock(at("15:00:00")));
        String key = ActivityRecorder.loginKey("sid-8", "gus", null);
        for (int i = 0; i < ActivityRecorder.MAX_TABS_A_DAY; i++) {
            assertThat(r.beat(key, "gus", UUID.randomUUID(), 60, 60, "/", false, false)[0]).isEqualTo(60);
        }
        assertThat(r.beat(key, "gus", UUID.randomUUID(), 60, 60, "/", false, false)).containsExactly(0, 0);
    }

    /** Code review: a day whose last minutes came after its last roll-up (the VMs stopped that evening) is put right. */
    @Test
    void atStartTheLastDaysTotalsAreWorkedOutAgain() {
        TestClock clock = new TestClock(at("09:30:00"));
        Instant threeDaysAgo = at("18:00:00").minus(Duration.ofDays(3));
        jdbc.update("insert into acct.user_minute (subject, minute, visible_seconds, active_seconds) values ('hal', ?, 60, 30), "
                + "('hal', ?, 45, 0)", java.sql.Timestamp.from(threeDaysAgo), java.sql.Timestamp.from(threeDaysAgo.plusSeconds(60)));
        ActivityRecorder.manual(db, PROPS, clock).flush();
        assertThat(jdbc.queryForObject("select visible_seconds from acct.user_activity_day where subject = 'hal' and day = ?",
                Integer.class, LocalDate.of(2026, 11, 21))).isEqualTo(105);
    }

    @Test
    void spreadSplitsABeatOverTheMinutesItCovers() {
        Map<Instant, int[]> m = ActivityRecorder.spread(Instant.parse("2026-11-24T04:31:20Z"), 60, 30);
        assertThat(m).hasSize(2);
        assertThat(m.get(Instant.parse("2026-11-24T04:30:00Z"))).containsExactly(40, 10);
        assertThat(m.get(Instant.parse("2026-11-24T04:31:00Z"))).containsExactly(20, 20);
        assertThat(ActivityRecorder.spread(Instant.now(), 0, 0)).isEmpty();
        assertThat(ActivityRecorder.path("/index/NIFTY?date=2026-11-20#x")).isEqualTo("/index/NIFTY");
    }
}
