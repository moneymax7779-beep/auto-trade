package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.broker.BrokerException;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.sim.CostModel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The spot exit (user 27 Nov 2026: "I should exit from LIVE open position when it reaches the spot not just on the percentage
 * target") on real PAPER positions of a running worker, driven by captured index ticks: a PE is sold when the index is at
 * or below the level, a CE at or above it; ticks from before the level was set and pre-open ticks sell nothing, a touch
 * while the watcher was not looking still sells, a bad tick never does; a level the index is already past, one far from
 * it, one with no live index price, and a new level over a pending sale are refused; a removed or replaced level sells
 * nothing; a lost session is not a try; a sale triggered yesterday is not resent at the open; beside the % target,
 * whichever comes first (the other ends).
 */
@Testcontainers(disabledWithoutDocker = true)
class SpotTargetsIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);
    private static final Clock OPEN = Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST);
    private static final Clock PRE_OPEN = Clock.fixed(TODAY.atTime(9, 10).atZone(IST).toInstant(), IST);
    private static final int N = 9;
    /** Position 5 is a CE; the others are PEs. */
    private static final int CE = 5;
    /** Each test's positions on an index of their own: one test's ticks never touch (or skew) another's levels. */
    private static final String[] U = {"NIFTY", "BANKNIFTY", "SENSEX", "FINNIFTY", "FINNIFTY", "MIDCPNIFTY", "BANKEX",
            "NIFTYNXT50", "SENSEX50"};

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static ExecutionLoop loop;
    private static final long[] accounts = new long[N], ids = new long[N], tokens = new long[N];
    private static final double[] entry = new double[N];
    private static AccountStore store;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(30);
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new AccountStore(dataSource);
        CostModel costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs",
                "india-index-options-costs.v2.yaml")));
        long tenant = store.ensureTenant("t-sita");
        long user = store.ensureUser("kc-sita", "sita");
        store.ensureMembership(tenant, user, "OWNER");
        StringBuilder groups = new StringBuilder();
        for (int i = 0; i < N; i++) {
            tokens[i] = 3_000_057_180L + i;
            long signal = store.ensureSignalSourceAccount(tenant, user, "SIG-sita-" + i, 500_000, TODAY,
                    "config/risk/signal-source.v2.yaml");
            long group = store.ensureCopyGroup(tenant, "SET-sita-" + i, signal);
            groups.append(i == 0 ? "" : ",").append("SET-sita-").append(i);
            accounts[i] = store.ensureFollowerAccount(tenant, user, "SITA-PAPER-" + i, BrokerType.PAPER, TradeMode.PAPER,
                    500_000, EquityMode.FIXED, TODAY, "../config/risk/follower-risk.v3.yaml", 100);
            new FanOut(dataSource).subscribe(group, accounts[i], List.of("opening-drive"), 1.0);
            jdbc.update("update acct.strategy_subscription set enabled_at = now() - interval '1 hour' where copy_group_id = ?",
                    group);
            new SignalDecisionStore(dataSource).insert(new SignalDecision(group, 9700 + i, TODAY,
                    Instant.now().minusSeconds(120), "opening-drive", 2, U[i], SignalDecision.Action.ENTER, type(i), null,
                    "CONFIRMED", "IT", tokens[i], "NSE_FO|" + (57180 + i), name(i), 65, 100, 130_000, 500_000, 20,
                    "SITA:" + i, Map.of("strategy_lots", 1.0, "intended_lots", 1.0, "stop_pct", 25.0)));
        }
        loop = new ExecutionLoop(dataSource, "t-sita", groups.toString(), "worker-S", costs, (d, u, t, at) -> Double.NaN, 1e9);
        loop.tick(TODAY, 20_000);
        for (int i = 0; i < N; i++) {
            Map<String, Object> p = jdbc.queryForMap("select id, average_entry from exec.position where broker_account_id = ? "
                    + "and status = 'OPEN'", accounts[i]);
            ids[i] = ((Number) p.get("id")).longValue();
            entry[i] = ((Number) p.get("average_entry")).doubleValue();
            bid(i, entry[i] + 10);                             // a fresh bid: a paper sale needs one
        }
        for (String u : new java.util.TreeSet<>(List.of(U))) {
            index(u, 26_000, "30 seconds");                    // the index when the levels are set
        }
    }

    @AfterAll
    static void stop() {
        loop.close();
        dataSource.close();
        postgres.stop();
    }

    private static String type(int i) {
        return i == CE ? "CE" : "PE";
    }

    private static String name(int i) {
        return U[i] + " 2600" + i + " " + type(i);
    }

    private static void bid(int i, double bid) {
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, "
                + "symbol, expiry, strike, option_type, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, ask_orders, "
                + "analytics_complete, depth_complete) values (1, current_date, ?, now(), 1, ?, ?, current_date + 4, ?, "
                + "?, ?, array[?]::float8[], '{650}', '{3}', array[?]::float8[], '{650}', '{3}', false, true)", U[i], tokens[i],
                name(i), 26000 + i, type(i), bid, bid, bid + 0.5);
    }

    /** A captured index tick of {@code underlying} at {@code price}, {@code ago} old. */
    private static void index(String underlying, double price, String ago) {
        jdbc.update("insert into md.index_tick (manifest_id, session_date, underlying, recv_ts, src_seq, price) values (1, "
                + "current_date, ?, now() - ?::interval, 1, ?)", underlying, ago, price);
    }

    private static void index(int i, double price, String ago) {
        index(U[i], price, ago);
    }

    private static void index(int i, double price) {
        index(U[i], price, "0 seconds");
    }

    private static BrokerAccount account(int i) {
        return store.byId(accounts[i]).orElseThrow();
    }

    private static Map<String, Object> spot(int i) {
        return jdbc.queryForMap("select status, tries, generation, fired_spot, last_result from exec.position_spot_target "
                + "where position_id = ?", ids[i]);
    }

    private static String positionStatus(int i) {
        return jdbc.queryForObject("select status || ':' || coalesce(exit_reason, '-') from exec.position where id = ?",
                String.class, ids[i]);
    }

    /** Moves position {@code i}'s level's set time back, so ticks can be older than now and still after it. */
    private static void setEarlier(int i) {
        jdbc.update("update exec.position_spot_target set set_at = now() - interval '2 minutes' where position_id = ?", ids[i]);
    }

    private static SpotTargets spots(Clock clock) {
        // the session opened an hour ago by the database's clock (the tests run at any time of day)
        return new SpotTargets(dataSource).withClock(clock).withSales(Runnable::run)
                .withSessionOpen(() -> Instant.now().minusSeconds(3600));
    }

    private static final PositionTargets.Exiter ENGINE = (account, position, audit) -> loop.exitAtSpot(account, position,
            TODAY, audit);

    @Test
    void aPeIsSoldWhenTheIndexFallsToTheLevelAndNotBefore() {
        SpotTargets s = spots(OPEN);
        BrokerAccount a0 = account(0);
        index(0, 26_000);
        assertThatThrownBy(() -> s.set(a0, ids[0], 26_010, "kc-sita")).as("a PE level above the index is reached already")
                .hasMessageContaining("already at or below");
        assertThatThrownBy(() -> s.set(a0, ids[0], 2_590, "kc-sita")).as("a typo, 10 x off")
                .hasMessageContaining("more than 10 % away");
        assertThatThrownBy(() -> s.set(a0, ids[0], 0, "kc-sita")).hasMessageContaining("above 0");
        assertThatThrownBy(() -> s.set(a0, ids[1], 25_950, "kc-sita")).hasMessageContaining("is not an open PAPER position");

        SpotTargets.View v = s.set(a0, ids[0], 25_950, "kc-sita");
        assertThat(v.direction()).isEqualTo(SpotTargets.Direction.DOWN);
        assertThat(v.spotAtSet()).isEqualTo(26_000.0);
        assertThat(s.set(a0, ids[0], 25_950, "kc-sita").setAt()).as("the same level again changes nothing")
                .isEqualTo(v.setAt());
        index(0, 25_960);
        assertThat(s.watch(ENGINE)).as("above the level: nothing").isEmpty();
        assertThat(positionStatus(0)).startsWith("OPEN");
        index(0, 25_949.5);
        index(0, 25_970);                                         // the touch counts though the index bounced in the same second
        assertThat(s.watch(ENGINE)).singleElement().asString().contains("reached the spot exit 25950.00").contains("selling");
        assertThat(positionStatus(0)).isEqualTo("CLOSED:SPOT_TARGET");
        assertThat(spot(0)).containsEntry("status", "DONE");
        assertThat(((Number) spot(0).get("fired_spot")).doubleValue()).isEqualTo(25_949.5);
        assertThat(jdbc.queryForList("select action from exec.audit_log where broker_account_id = ? order by id",
                String.class, accounts[0])).contains("SPOT_TARGET_SET", "SPOT_TARGET_SELL", "POSITION_EXIT");
        assertThat(jdbc.queryForObject("select detail ->> 'actor' from exec.audit_log where broker_account_id = ? and "
                + "action = 'POSITION_EXIT'", String.class, accounts[0])).isEqualTo(SpotTargets.ACTOR);
        assertThat(s.forAccount(accounts[0])).as("not shown once the position is closed").isEmpty();
        assertThatThrownBy(() -> s.set(a0, ids[0], 25_900, "kc-sita")).hasMessageContaining("is not an open");
    }

    @Test
    void aCeIsSoldWhenTheIndexRisesToTheLevel() {
        SpotTargets s = spots(OPEN);
        BrokerAccount a = account(CE);
        index(CE, 26_000);
        assertThatThrownBy(() -> s.set(a, ids[CE], 25_990, "kc-sita")).hasMessageContaining("already at or above");
        assertThat(s.set(a, ids[CE], 26_080, "kc-sita").direction()).isEqualTo(SpotTargets.Direction.UP);
        index(CE, 26_079.95);
        assertThat(s.watch(ENGINE)).isEmpty();
        index(CE, 26_080);
        assertThat(s.watch(ENGINE)).hasSize(1);
        assertThat(positionStatus(CE)).isEqualTo("CLOSED:SPOT_TARGET");
    }

    @Test
    void preSetPreOpenRemovedAndReplacedLevelsSellNothing() {
        SpotTargets s = spots(OPEN);
        BrokerAccount a1 = account(1);
        index(1, 26_000);
        index(1, 25_850, "3 seconds");                            // fresh, but from before the level was set
        s.set(a1, ids[1], 25_900, "kc-sita");
        assertThat(s.watch(ENGINE)).as("a touch before the level was set").isEmpty();
        index(1, 25_880);
        assertThat(spots(PRE_OPEN).watch(ENGINE)).as("not before 09:15").isEmpty();
        // at 09:15:00 the pre-open's ticks (here: the 25 880 written just before the session opened) do not count
        assertThat(spots(OPEN).withSessionOpen(Instant::now).watch(ENGINE)).as("a pre-open touch").isEmpty();
        assertThat(positionStatus(1)).startsWith("OPEN");
        // replaced by a lower level: the old one's touch (25 880, before the new one was set) sells nothing
        index(1, 26_000);
        SpotTargets.View again = s.set(a1, ids[1], 25_800, "kc-sita");
        assertThat(spot(1)).containsEntry("generation", 2).containsEntry("status", "WAITING");
        assertThat(again.level()).isEqualTo(25_800.0);
        index(1, 25_850);
        assertThat(s.watch(ENGINE)).isEmpty();
        assertThat(s.clear(a1, ids[1], "kc-sita")).isTrue();
        assertThat(s.clear(a1, ids[1], "kc-sita")).isFalse();
        index(1, 25_700);
        assertThat(s.watch(ENGINE)).as("removed").isEmpty();
        assertThat(positionStatus(1)).startsWith("OPEN");
    }

    @Test
    void aTouchWhileTheWatcherWasNotLookingStillSells() {
        // review 27 Nov 2026: a worker restart or a stall longer than a few seconds must not lose a touch
        SpotTargets s = spots(OPEN);
        BrokerAccount a6 = account(6);
        index(6, 26_000);
        s.set(a6, ids[6], 25_900, "kc-sita");
        setEarlier(6);
        index(6, 25_890, "40 seconds");                           // after the level was set, during a 40 s gap
        index(6, 25_990);                                          // and back above it now
        assertThat(spots(OPEN).watch(ENGINE)).as("a fresh worker's first look reaches back").hasSize(1);
        assertThat(positionStatus(6)).isEqualTo("CLOSED:SPOT_TARGET");
        assertThat(((Number) spot(6).get("fired_spot")).doubleValue()).isEqualTo(25_890.0);
    }

    @Test
    void aBadTickNeverSells() {
        // review 27 Nov 2026: one glitch tick (a wrong-scale value, a feed reconnect) is not a touch
        SpotTargets s = spots(OPEN);
        BrokerAccount a7 = account(7);
        index(7, 26_000);
        s.set(a7, ids[7], 25_900, "kc-sita");
        index(7, 26_010);
        index(7, 2_601);                                           // a tenth of the index
        index(7, 25_100);                                          // 3.5 % below the median in one tick
        index(7, 26_005);
        assertThat(s.watch(ENGINE)).isEmpty();
        assertThat(positionStatus(7)).startsWith("OPEN");
        index(7, 25_899);
        assertThat(s.watch(ENGINE)).as("a real touch still sells").hasSize(1);
        assertThat(positionStatus(7)).isEqualTo("CLOSED:SPOT_TARGET");
    }

    @Test
    void noPriceAStalePriceAPendingSaleAndYesterdaysSaleAreRefused() {
        SpotTargets s = spots(OPEN);
        BrokerAccount a8 = account(8);
        assertThat(SpotTargets.refusal("SENSEX50", SpotTargets.Direction.UP, 100, null)).as("no index price known")
                .contains("no SENSEX50 price");
        jdbc.update("delete from md.index_tick where underlying = ?", U[8]);
        index(8, 26_000, "5 minutes");
        assertThatThrownBy(() -> s.set(a8, ids[8], 25_900, "kc-sita")).as("in market hours the feed is not recording")
                .hasMessageContaining("is 5 min old");
        assertThat(spots(PRE_OPEN).set(a8, ids[8], 25_900, "kc-sita").status()).as("before the open last night's close does")
                .isEqualTo("WAITING");
        index(8, 26_000);
        // triggered, the rest pending (a lost broker session): no new level over it, only Exit or removing it
        jdbc.update("update exec.position_spot_target set status = 'SELLING', fired_at = now(), next_try_at = now() + "
                + "interval '30 seconds' where position_id = ?", ids[8]);
        assertThatThrownBy(() -> s.set(a8, ids[8], 25_800, "kc-sita")).hasMessageContaining("the rest is being sold");
        // triggered yesterday and not all sold by the close: not sold again at today's open whatever the index
        jdbc.update("update exec.position_spot_target set fired_at = now() - interval '1 day', next_try_at = now() - "
                + "interval '1 second' where position_id = ?", ids[8]);
        assertThat(s.watch(ENGINE)).isEmpty();
        assertThat(spot(8)).containsEntry("status", "FAILED");
        assertThat((String) spot(8).get("last_result")).contains("not sold again at the open; use Exit");
        assertThat(positionStatus(8)).startsWith("OPEN");
        assertThat(s.clear(a8, ids[8], "kc-sita")).as("a FAILED one can be removed").isTrue();
    }

    @Test
    void aLostSessionIsNotATryAndAFailureBacksOff() {
        SpotTargets s = spots(OPEN);
        BrokerAccount a2 = account(2);
        index(2, 26_000);
        s.set(a2, ids[2], 25_950, "kc-sita");
        index(2, 25_940);
        PositionTargets.Exiter lost = (account, position, audit) -> {
            throw new RuntimeException("lane", new BrokerException(BrokerException.Kind.SESSION, "session expired"));
        };
        assertThat(s.watch(lost)).hasSize(1);
        assertThat(spot(2)).containsEntry("status", "SELLING").containsEntry("tries", 0);
        assertThat((String) spot(2).get("last_result")).contains("log in to the broker again");
        index(2, 26_100);                                         // the index went back: the rest is still sold, like a stop
        jdbc.update("update exec.position_spot_target set next_try_at = now() - interval '1 second' where position_id = ?",
                ids[2]);
        PositionTargets.Exiter refused = (account, position, audit) -> {
            throw new IllegalStateException("price band");
        };
        assertThat(s.watch(refused)).singleElement().asString().contains("the rest of the triggered spot exit");
        assertThat(spot(2)).containsEntry("status", "SELLING").containsEntry("tries", 1);
        jdbc.update("update exec.position_spot_target set next_try_at = now() - interval '1 second' where position_id = ?",
                ids[2]);
        assertThat(s.watch(ENGINE)).hasSize(1);
        assertThat(positionStatus(2)).isEqualTo("CLOSED:SPOT_TARGET");
        assertThat(spot(2)).containsEntry("status", "DONE");
    }

    @Test
    void besideThePercentTargetWhicheverComesFirst() {
        SpotTargets s = spots(OPEN);
        PositionTargets pct = new PositionTargets(dataSource).withClock(OPEN).withSales(Runnable::run);
        BrokerAccount a3 = account(3);
        index(3, 26_000);
        pct.set(a3, ids[3], 50, "kc-sita");
        s.set(a3, ids[3], 25_950, "kc-sita");
        index(3, 25_950);
        assertThat(s.watch(ENGINE)).hasSize(1);
        assertThat(positionStatus(3)).isEqualTo("CLOSED:SPOT_TARGET");
        pct.watch((account, position, audit) -> loop.exitAtTarget(account, position, TODAY, audit));
        assertThat(jdbc.queryForObject("select status from exec.position_target where position_id = ?", String.class,
                ids[3])).isEqualTo("ENDED");
        // the other way round: the % target sells first, the spot exit ends
        BrokerAccount a4 = account(4);
        index(4, 26_000);
        s.set(a4, ids[4], 25_900, "kc-sita");
        jdbc.update("update exec.position set exit_reason = 'MANUAL_TARGET', status = 'CLOSED', quantity_open = 0, "
                + "closed_at = now() where id = ?", ids[4]);
        index(4, 25_850);
        assertThat(s.watch(ENGINE)).isEmpty();
        assertThat(spot(4)).containsEntry("status", "ENDED");
        assertThat((String) spot(4).get("last_result")).contains("closed by MANUAL_TARGET");
    }
}
