package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * The trader's manual first target (user 24 Nov 2026; answers: a lock, the whole position, beside the strategy's exits) on
 * real PAPER positions of a running worker, driven by captured bids, with the review fixes of the same day: the lock arms
 * on a touch and sells on a fresh bid back at or below the price; the price is fixed when armed (an add does not move it);
 * once triggered, what is left is sold on whatever the bid; a lost session or a route that cannot open is not a try; a
 * lane that did not answer leaves the sale FIRING until it is repaired; a target set again during a sale is not overwritten;
 * the same % again keeps an armed lock; another worker's account, a closed position, a crossed quote and a pre-open quote do
 * nothing; and a slow sale does not hold up the watcher.
 */
@Testcontainers(disabledWithoutDocker = true)
class PositionTargetsIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);
    /** The market-hours gate's clock: 10:00 IST today (bids and retries go by the database's own clock). */
    private static final Clock OPEN = Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST);
    private static final int N = 13;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static ExecutionLoop loop;
    private static final long[] accounts = new long[N], ids = new long[N], tokens = new long[N];
    private static final double[] entry = new double[N];
    private static final long[] quantity = new long[N];
    private static AccountStore store;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);                 // each account's lease holds a connection of its own
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new AccountStore(dataSource);
        CostModel costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs",
                "india-index-options-costs.v2.yaml")));
        long tenant = store.ensureTenant("t-tara");
        long user = store.ensureUser("kc-tara", "tara");
        store.ensureMembership(tenant, user, "OWNER");
        StringBuilder groups = new StringBuilder();
        for (int i = 0; i < N; i++) {
            tokens[i] = 3_000_047_180L + i;
            long signal = store.ensureSignalSourceAccount(tenant, user, "SIG-tara-" + i, 500_000, TODAY,
                    "config/risk/signal-source.v2.yaml");
            long group = store.ensureCopyGroup(tenant, "SET-tara-" + i, signal);
            groups.append(i == 0 ? "" : ",").append("SET-tara-").append(i);
            accounts[i] = store.ensureFollowerAccount(tenant, user, "TARA-PAPER-" + i, BrokerType.PAPER, TradeMode.PAPER,
                    500_000, EquityMode.FIXED, TODAY, "../config/risk/follower-risk.v3.yaml", 100);
            new FanOut(dataSource).subscribe(group, accounts[i], List.of("opening-drive"), 1.0);
            jdbc.update("update acct.strategy_subscription set enabled_at = now() - interval '1 hour' where copy_group_id = ?",
                    group);
            new SignalDecisionStore(dataSource).insert(new SignalDecision(group, 9600 + i, TODAY,
                    Instant.now().minusSeconds(120), "opening-drive", 2, "NIFTY", SignalDecision.Action.ENTER, "PE", null,
                    "CONFIRMED", "IT", tokens[i], "NSE_FO|" + (47180 + i), name(i), 65, 100, 130_000, 500_000, 20,
                    "TARA:" + i, Map.of("strategy_lots", 1.0, "intended_lots", 1.0, "stop_pct", 25.0)));
        }
        // position 0's contract is in ref.instrument (quotes through the contract index); the others fall back to the token
        jdbc.update("insert into ref.instrument_snapshot (id, snapshot_date, source, file_sha256, instruments) values "
                + "(960, current_date, 'it', 'it', 1) on conflict do nothing");
        jdbc.update("insert into ref.instrument (snapshot_id, instrument_key, exchange_token, segment, exchange, type, "
                + "underlying, trading_symbol, expiry, strike, lot_size, tick_size, freeze_quantity, weekly) values (960, "
                + "'NSE_FO|47180', '47180', 'NSE_FO', 'NSE', 'PE', 'NIFTY', 'NIFTY 23180 PE', current_date + 4, 23180, 65, "
                + "0.05, 1755, true) on conflict do nothing");
        loop = new ExecutionLoop(dataSource, "t-tara", groups.toString(), "worker-T", costs, (d, u, t, at) -> Double.NaN, 1e9);
        loop.tick(TODAY, 20_000);
        for (int i = 0; i < N; i++) {
            Map<String, Object> p = jdbc.queryForMap("select id, average_entry, quantity_open from exec.position where "
                    + "broker_account_id = ? and status = 'OPEN'", accounts[i]);
            ids[i] = ((Number) p.get("id")).longValue();
            entry[i] = ((Number) p.get("average_entry")).doubleValue();
            quantity[i] = ((Number) p.get("quantity_open")).longValue();
        }
    }

    @AfterAll
    static void stop() {
        loop.close();
        dataSource.close();
        postgres.stop();
    }

    private static String name(int i) {
        return "NIFTY 2318" + (i % 10) + " PE" + (i >= 10 ? " " + i : "");
    }

    /** A captured quote of position {@code i}'s contract with {@code bid} (ask {@code ask}), {@code ago} old. */
    private static void tick(int i, double bid, double ask, String ago) {
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, "
                + "symbol, expiry, strike, option_type, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, ask_orders, "
                + "analytics_complete, depth_complete) values (1, current_date, 'NIFTY', now() - ?::interval, 1, ?, ?, "
                + "current_date + 4, ?, 'PE', ?, array[?]::float8[], '{650}', '{3}', array[?]::float8[], '{650}', '{3}', "
                + "false, true)", ago, tokens[i], name(i), 23180 + i, bid, bid, ask);
    }

    /** A tick written late: received a millisecond after the target was set, inserted only now. */
    private static void lateTick(int i, double bid) {
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, "
                + "symbol, expiry, strike, option_type, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, ask_orders, "
                + "analytics_complete, depth_complete) select 1, current_date, 'NIFTY', set_at + interval '1 millisecond', 1, "
                + "?, ?, current_date + 4, ?, 'PE', ?, array[?]::float8[], '{650}', '{3}', array[?]::float8[], '{650}', "
                + "'{3}', false, true from exec.position_target where position_id = ?", tokens[i], name(i), 23180 + i, bid,
                bid, bid + 0.5, ids[i]);
    }

    private static void tick(int i, double bid) {
        tick(i, bid, bid + 0.5, "0 seconds");
    }

    private static BrokerAccount account(int i) {
        return store.byId(accounts[i]).orElseThrow();
    }

    private static Map<String, Object> target(int i) {
        return jdbc.queryForMap("select status, tries, generation, armed_price, last_result, next_try_at > now() later "
                + "from exec.position_target where position_id = ?", ids[i]);
    }

    private static String positionStatus(int i) {
        return jdbc.queryForObject("select status || ':' || coalesce(exit_reason, '-') from exec.position where id = ?",
                String.class, ids[i]);
    }

    private static void due(int i) {
        jdbc.update("update exec.position_target set next_try_at = now() - interval '1 second' where position_id = ?", ids[i]);
    }

    private static PositionTargets targets() {
        return new PositionTargets(dataSource).withClock(OPEN).withSales(Runnable::run);
    }

    private static final PositionTargets.Exiter ENGINE = (account, position, audit) -> loop.exitAtTarget(account, position,
            TODAY, audit);

    /** Arms position {@code i}'s target at {@code pct} % (a touch 3 above its price); returns the price. */
    private static double armed(PositionTargets t, int i, double pct, PositionTargets.Exiter exiter) {
        double price = t.set(account(i), ids[i], pct, "kc-tara").price();
        tick(i, price + 3);
        t.watch(exiter);
        assertThat(target(i)).containsEntry("status", "ARMED");
        return price;
    }

    @Test
    void theLockArmsOnATouchRidesAboveAndSellsTheWholePositionOnTheWayBack() {
        PositionTargets t = targets();
        BrokerAccount a0 = account(0);
        assertThatThrownBy(() -> t.set(a0, ids[0], 0, "kc-tara")).hasMessageContaining("more than 0 %");
        assertThatThrownBy(() -> t.set(a0, ids[0], 0.004, "kc-tara")).as("0 after rounding").hasMessageContaining("more than 0 %");
        assertThatThrownBy(() -> t.set(a0, ids[0], 1001, "kc-tara")).hasMessageContaining("at most 1000 %");
        assertThatThrownBy(() -> t.set(a0, ids[1], 20, "kc-tara")).hasMessageContaining("is not an open PAPER position of TARA-PAPER-0");

        double price = t.set(a0, ids[0], 20, "kc-tara").price();
        assertThat(price).isEqualTo(PositionTargets.priceOf(entry[0], 20));
        tick(0, price - 1);
        assertThat(t.watch(ENGINE)).as("below the target: nothing").isEmpty();
        tick(0, price + 9, price + 8, "0 seconds");             // a crossed quote (bid above ask) is not a price
        assertThat(t.watch(ENGINE)).isEmpty();
        assertThat(target(0)).containsEntry("status", "WAITING");
        lateTick(0, price + 3);                                // a touch written late, behind ticks already looked at
        tick(0, price - 2);
        assertThat(t.watch(ENGINE)).singleElement().asString().contains("reached");
        assertThat(target(0)).containsEntry("status", "ARMED");
        assertThat(positionStatus(0)).as("never armed and sold in the same look").startsWith("OPEN");
        assertThat(t.set(a0, ids[0], 20, "kc-tara").status()).as("the same % again keeps the lock").isEqualTo("ARMED");
        tick(0, price + 6);
        assertThat(t.watch(ENGINE)).as("riding above the target").isEmpty();
        tick(0, price - 0.5);
        assertThat(t.watch(ENGINE)).singleElement().asString().contains("back at or below the target").contains("selling");
        Map<String, Object> closed = jdbc.queryForMap("select status, exit_reason, quantity_open, realised_gross from "
                + "exec.position where id = ?", ids[0]);
        assertThat(closed).containsEntry("status", "CLOSED").containsEntry("exit_reason", "MANUAL_TARGET")
                .containsEntry("quantity_open", 0L);
        assertThat(((Number) closed.get("realised_gross")).doubleValue()).as("sold at the fresh bid, above the entry")
                .isCloseTo((price - 0.5 - entry[0]) * quantity[0], org.assertj.core.data.Offset.offset(quantity[0] * 1.0));
        assertThat(target(0)).containsEntry("status", "DONE");
        assertThat(jdbc.queryForList("select action from exec.audit_log where broker_account_id = ? order by id",
                String.class, accounts[0])).contains("TARGET_SET", "TARGET_ARMED", "TARGET_SELL", "POSITION_EXIT");
        assertThat(jdbc.queryForObject("select detail ->> 'actor' from exec.audit_log where broker_account_id = ? and "
                + "action = 'POSITION_EXIT'", String.class, accounts[0])).isEqualTo(PositionTargets.ACTOR);
        assertThat(t.watch(ENGINE)).as("a closed position is not looked at again").isEmpty();
        assertThat(t.forAccount(accounts[0])).as("not shown once the position is closed").isEmpty();
        assertThatThrownBy(() -> t.set(a0, ids[0], 10, "kc-tara")).hasMessageContaining("is not an open");
    }

    @Test
    void anOldHighDoesNotArmANewTargetAndRemovedOrChangedTargetsSellNothing() {
        PositionTargets t = targets();
        BrokerAccount a1 = account(1);
        double price = PositionTargets.priceOf(entry[1], 10);
        tick(1, price + 20, price + 20.5, "20 seconds");         // before the target was set
        t.set(a1, ids[1], 10, "kc-tara");
        assertThat(t.watch(ENGINE)).isEmpty();
        tick(1, price + 1);
        assertThat(t.watch(ENGINE)).hasSize(1);
        PositionTargets.View changed = t.set(a1, ids[1], 30, "kc-tara");
        assertThat(changed.status()).isEqualTo("WAITING");
        assertThat(changed.armedAt()).isNull();
        assertThat(target(1)).containsEntry("generation", 2);
        assertThat(t.watch(ENGINE)).as("the high that armed 10 % is older than the 30 % target").isEmpty();
        // the trader sets 50 % between a look's read of the 30 % target and its arming: the look does not arm the new one
        double price30 = PositionTargets.priceOf(entry[1], 30);
        tick(1, price30 + 1);
        PositionTargets racing = targets().beforeArm(() -> t.set(a1, ids[1], 50, "kc-tara"));
        assertThat(racing.watch(ENGINE)).isEmpty();
        assertThat(target(1)).containsEntry("status", "WAITING").containsEntry("generation", 3);
        assertThat(t.view(ids[1]).orElseThrow().pct()).isEqualTo(50.0);
        assertThat(t.clear(a1, ids[1], "kc-tara")).isTrue();
        assertThat(t.clear(a1, ids[1], "kc-tara")).as("nothing left to remove").isFalse();
        assertThat(t.forAccount(accounts[1])).isEmpty();
        tick(1, entry[1] * 1.5);
        tick(1, entry[1]);
        assertThat(t.watch(ENGINE)).isEmpty();
        assertThat(positionStatus(1)).startsWith("OPEN");
    }

    @Test
    void theArmedPriceStaysWhenAnAddMovesTheAverageEntry() {
        PositionTargets t = targets();
        double price = armed(t, 2, 20, ENGINE);
        // the strategy adds higher: the average entry moves up, 20 % of it would be well above the bid now
        jdbc.update("update exec.position set average_entry = average_entry * 1.25 where id = ?", ids[2]);
        tick(2, price + 1);
        assertThat(t.watch(ENGINE)).as("the bid is above the armed price: rides on").isEmpty();
        assertThat(t.forAccount(accounts[2]).get(ids[2]).price()).isEqualTo(price);
        assertThat(((Number) target(2).get("armed_price")).doubleValue()).isEqualTo(price);
    }

    @Test
    void onceTriggeredTheRestIsSoldWhateverTheBidAndATargetSetDuringTheSaleIsKept() {
        PositionTargets t = targets();
        // 3: a first sale sells half (a partial fill): SELLING; the bid recovers; the next try sells the rest anyway
        double price = armed(t, 3, 15, ENGINE);
        PositionTargets.Exiter half = (account, position, audit) -> {
            jdbc.update("update exec.position set quantity_open = quantity_open / 2 where id = ?", position);
            return List.of(name(3) + ": 32 of 65 sold (PARTIAL); 33 stay open with a new broker stop");
        };
        tick(3, price - 1);
        assertThat(t.watch(half)).singleElement().asString().contains("selling");
        assertThat(target(3)).containsEntry("status", "SELLING").containsEntry("tries", 1).containsEntry("later", true);
        tick(3, price + 10);
        assertThat(t.watch(ENGINE)).as("not before the next try").isEmpty();
        due(3);
        assertThat(t.watch(ENGINE)).singleElement().asString().contains("the rest of the triggered target");
        assertThat(positionStatus(3)).isEqualTo("CLOSED:MANUAL_TARGET");
        assertThat(target(3)).containsEntry("status", "DONE");

        // 4: the trader sets a new % while a sale runs: the sale's outcome does not overwrite the new target
        double price4 = armed(t, 4, 15, ENGINE);
        PositionTargets.Exiter meanwhile = (account, position, audit) -> {
            jdbc.update("update exec.position_target set status = 'WAITING', generation = generation + 1, target_pct = 40 "
                    + "where position_id = ?", position);                // what set() does, in the middle of the sale
            return List.of(name(4) + ": NOT flattened, order book unreadable");
        };
        tick(4, price4 - 1);
        t.watch(meanwhile);
        assertThat(target(4)).containsEntry("status", "WAITING").containsEntry("tries", 0);
    }

    @Test
    void aLostSessionOrARouteThatCannotOpenIsNotATryAndFailuresBackOffThenGiveUp() {
        PositionTargets t = targets();
        double price = armed(t, 5, 15, ENGINE);
        PositionTargets.Exiter expired = (account, position, audit) -> {
            throw new RuntimeException("lane", new BrokerException(BrokerException.Kind.SESSION, "session expired"));
        };
        tick(5, price - 1);
        t.watch(expired);
        assertThat(target(5)).containsEntry("status", "SELLING").containsEntry("tries", 0).containsEntry("later", true);
        assertThat((String) target(5).get("last_result")).contains("log in to the broker again");
        long audits = jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? and action = "
                + "'TARGET_SELL'", Long.class, accounts[5]);
        assertThat(t.watch(expired)).as("the next try waits SESSION_RETRY").isEmpty();
        due(5);
        t.watch(expired);
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? and action = "
                + "'TARGET_SELL'", Long.class, accounts[5])).as("one audit row per change, not per retry").isEqualTo(audits);
        due(5);
        t.watch((account, position, audit) -> List.of("not flattened: LIVE refused: no Upstox client pinned (log in once first)"));
        assertThat(target(5)).containsEntry("status", "SELLING").containsEntry("tries", 0);

        // real failures count and back off; at MAX_TRIES the target gives up (the trader's Exit is still there)
        PositionTargets.Exiter refusing = (account, position, audit) -> List.of(name(5) + ": NOT flattened, the broker said no");
        due(5);
        t.watch(refusing);
        assertThat(target(5)).containsEntry("status", "SELLING").containsEntry("tries", 1).containsEntry("later", true);
        jdbc.update("update exec.position_target set tries = ? where position_id = ?", PositionTargets.MAX_TRIES - 1, ids[5]);
        due(5);
        t.watch(refusing);
        assertThat(target(5)).containsEntry("status", "FAILED");
        due(5);
        assertThat(t.watch(refusing)).as("a FAILED target is not tried again").isEmpty();
        assertThat(PositionTargets.backOff(1)).hasSeconds(5);
        assertThat(PositionTargets.backOff(3)).hasSeconds(20);
        assertThat(PositionTargets.backOff(9)).hasSeconds(60);
    }

    @Test
    void aLaneThatDidNotAnswerLeavesTheSaleFiringUntilItIsRepaired() {
        PositionTargets t = targets();
        double price = armed(t, 6, 15, ENGINE);
        tick(6, price - 1);
        t.watch((account, position, audit) -> {
            throw new java.util.concurrent.TimeoutException("the account's lane did not answer in 120 s");
        });
        assertThat(target(6)).containsEntry("status", "FIRING").containsEntry("tries", 0);
        assertThat((String) target(6).get("last_result")).startsWith("still selling on the account's lane");
        assertThat(t.watch(ENGINE)).as("FIRING: nothing more is sent").isEmpty();
        jdbc.update("update exec.position_target set last_try_at = now() - interval '4 minutes' where position_id = ?", ids[6]);
        t.watch(ENGINE);                                       // repaired to SELLING (due now), then sold
        assertThat(positionStatus(6)).isEqualTo("CLOSED:MANUAL_TARGET");
        assertThat(target(6)).containsEntry("status", "DONE");
    }

    @Test
    void anotherWorkersAccountAClosedPositionAndAPreOpenQuoteDoNothing() {
        PositionTargets t = targets();
        // 7: another worker holds the account: neither armed nor sold here
        PositionTargets.Exiter notMine = new PositionTargets.Exiter() {
            public List<String> exit(long account, long position, Map<String, ?> audit) {
                throw new AssertionError("never sold here");
            }

            public boolean mine(long account) {
                return account != accounts[7];
            }
        };
        double price = t.set(account(7), ids[7], 15, "kc-tara").price();
        tick(7, price + 3);
        assertThat(t.watch(notMine)).isEmpty();
        assertThat(target(7)).containsEntry("status", "WAITING");

        // 8: closed by the strategy first: ENDED, nothing sold by the target
        double price8 = armed(t, 8, 15, ENGINE);
        jdbc.update("update exec.position set status = 'CLOSED', quantity_open = 0, exit_reason = 'STRATEGY', closed_at = now() "
                + "where id = ?", ids[8]);
        tick(8, price8 - 1);
        assertThat(t.watch(ENGINE)).isEmpty();
        assertThat(target(8)).containsEntry("status", "ENDED");
        assertThat((String) target(8).get("last_result")).contains("closed by STRATEGY");
        jdbc.update("update exec.position set status = 'OPEN', quantity_open = 65, exit_reason = null, closed_at = null "
                + "where id = ?", ids[8]);

        // 9: before 09:15 an armed target never sells (a pre-open quote is no price); it arms (that sells nothing)
        PositionTargets early = new PositionTargets(dataSource).withSales(Runnable::run)
                .withClock(Clock.fixed(TODAY.atTime(9, 5).atZone(IST).toInstant(), IST));
        double price9 = armed(early, 9, 15, ENGINE);
        tick(9, price9 - 1);
        assertThat(early.watch(ENGINE)).isEmpty();
        assertThat(target(9)).containsEntry("status", "ARMED");
    }

    @Test
    void aSlowSaleDoesNotHoldUpTheWatcher() throws Exception {
        PositionTargets t = new PositionTargets(dataSource).withClock(OPEN);          // sales on their own threads
        double price10 = armed(t, 10, 15, ENGINE);
        double price11 = armed(t, 11, 15, ENGINE);
        CountDownLatch release = new CountDownLatch(1);
        List<Long> sold = new java.util.concurrent.CopyOnWriteArrayList<>();
        PositionTargets.Exiter slowFor10 = (account, position, audit) -> {
            if (position == ids[10]) {
                release.await(30, TimeUnit.SECONDS);
            }
            sold.add(position);
            return loop.exitAtTarget(account, position, TODAY, audit);
        };
        tick(10, price10 - 1);
        tick(11, price11 - 1);
        long began = System.nanoTime();
        List<String> lines = new ArrayList<>(t.watch(slowFor10));
        assertThat((System.nanoTime() - began) / 1e9).as("the watch does not wait for the sales").isLessThan(5);
        assertThat(lines).hasSize(2);
        for (int i = 0; i < 100 && !sold.contains(ids[11]); i++) {
            Thread.sleep(100);
        }
        assertThat(sold).as("11 sold while 10's broker is slow").containsExactly(ids[11]);
        assertThat(target(10)).containsEntry("status", "FIRING");
        release.countDown();
        for (int i = 0; i < 100 && !"DONE".equals(target(10).get("status")); i++) {
            Thread.sleep(100);
        }
        assertThat(target(10)).containsEntry("status", "DONE");
        assertThat(target(11)).containsEntry("status", "DONE");
    }

    /**
     * V55 (user 25 Nov 2026): the admin's target is the same lock, marked by_admin with its reason (shown on the member's
     * book, in TARGET_SET's audit); its sale reaches the exit with by_admin and the reason (the member's notice); the owner
     * setting it again makes it theirs.
     */
    @Test
    void theAdminsTargetIsMarkedAndItsSaleSaysSo() {
        PositionTargets t = targets();
        Map<String, Object> admin = Map.of("by_admin", true, "admin_reason", "lock gains", "actor_role", "PLATFORM_ADMIN",
                "consent", "not given");
        PositionTargets.View v = t.set(account(12), ids[12], 10, "kc-admin", admin);
        assertThat(v.byAdmin()).isTrue();
        assertThat(v.adminReason()).isEqualTo("lock gains");
        assertThat(v.toMap()).containsEntry("byAdmin", true).containsEntry("adminReason", "lock gains");
        assertThat(jdbc.queryForMap("select detail ->> 'by_admin' a, detail ->> 'admin_reason' r, detail ->> 'pct' pct from "
                + "exec.audit_log where broker_account_id = ? and action = 'TARGET_SET' order by id desc limit 1", accounts[12]))
                .containsEntry("a", "true").containsEntry("r", "lock gains").containsEntry("pct", "10.0");
        tick(12, v.price() + 3);
        t.watch(ENGINE);
        assertThat(target(12)).containsEntry("status", "ARMED");
        // review 25 Nov 2026: the same % by the owner makes it theirs and keeps the armed lock; the admin's again, theirs
        PositionTargets.View mine = t.set(account(12), ids[12], 10, "kc-tara");
        assertThat(mine.byAdmin()).isFalse();
        assertThat(mine.adminReason()).isNull();
        assertThat(mine.status()).isEqualTo("ARMED");
        assertThat(PositionTargets.changed(java.util.Optional.of(v), java.util.Optional.of(mine))).isTrue();
        assertThat(PositionTargets.changed(java.util.Optional.of(mine), t.view(ids[12]))).as("read again").isFalse();
        PositionTargets.View back = t.set(account(12), ids[12], 10, "kc-admin", admin);
        assertThat(back.byAdmin()).isTrue();
        assertThat(back.status()).isEqualTo("ARMED");
        assertThat(jdbc.queryForObject("select detail ->> 'taken_over_from' from exec.audit_log where broker_account_id = ? "
                + "and action = 'TARGET_SET' order by id desc limit 1", String.class, accounts[12])).isEqualTo("owner");
        List<Map<String, ?>> seen = new java.util.ArrayList<>();
        PositionTargets.Exiter capture = (account, position, audit) -> {
            seen.add(audit);
            return ENGINE.exit(account, position, audit);
        };
        tick(12, v.price() - 0.5);
        t.watch(capture);
        assertThat(seen).singleElement().satisfies(d -> assertThat((Map<String, Object>) d).containsEntry("by_admin", true)
                .containsEntry("admin_reason", "lock gains").containsEntry("admin_target", true));
        assertThat(jdbc.queryForMap("select detail ->> 'by_admin' a, detail ->> 'admin_reason' r from exec.audit_log where "
                + "broker_account_id = ? and action = 'POSITION_EXIT' order by id desc limit 1", accounts[12]))
                .containsEntry("a", "true").containsEntry("r", "lock gains");
        assertThat(positionStatus(12)).isEqualTo("CLOSED:MANUAL_TARGET");
    }
}
