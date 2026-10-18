package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.autotrade.config.ThresholdConfig;
import com.autotrade.sim.CostModel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * L1 divergence report on one constructed day: a signal session, a PAPER follower run by the simulator, and a PAPER-1
 * session whose trades match, are explained (the follower's refusal, PAPER-1's refusal) or are unexplained.
 */
@Testcontainers(disabledWithoutDocker = true)
class DivergenceReportIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    private static final long SIGNAL_SESSION = 9101;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

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
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private static Instant ist(String time) {
        return LocalDateTime.parse(DAY + "T" + time).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
    }

    private static SignalDecision decision(long group, int seq, String time, String strategy, SignalDecision.Action action,
                                           String side, double price, double budget, int lots, Map<String, Double> context) {
        return new SignalDecision(group, SIGNAL_SESSION, DAY, ist(time), strategy, 2, "SENSEX", action, side, null, "CONFIRMED",
                "IT", "CE".equals(side) ? 1234 : 1235, "BSE_FO|" + side, "SENSEX 73200 " + side, 20, price, budget, 500_000,
                lots, group + ":" + SIGNAL_SESSION + ":" + seq, context);
    }

    private static long session(Long id, String account, String code) {
        String sql = "insert into trade.session (" + (id == null ? "" : "id, ") + "account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values (" + (id == null ? "" : "?, ")
                + "?, 'PAPER_REPLAY', ?, 'replay-as-live:zt-tiger-v2', 'live-set', 'sha256:it', '{}'::jsonb, ?, 'DONE') returning id";
        return id == null ? jdbc.queryForObject(sql, Long.class, account, DAY, code)
                : jdbc.queryForObject(sql, Long.class, id, account, DAY, code);
    }

    private static void paper1Position(long session, String strategy, String side, String time, long quantity, double net) {
        jdbc.update("insert into trade.position (session_id, underlying, option_side, symbol, opened_at, stages, realised, costs, "
                + "net, strategy_id, quantity, lot_size) values (?, 'SENSEX', ?, ?, ?, '{CONFIRMED}', ?, 0, ?, ?, ?, 20)", session,
                side, "SENSEX 73200 " + side, Timestamp.from(ist(time)), net, net, strategy, quantity);
    }

    @Test
    void eachFollowerTradeIsComparedWithTheSignalAndThePaperFollowerWithPaper1() {
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIGNAL-1", 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "LIVE-SET", signal);
        String risk = "../config/risk/follower-risk.v1.yaml";
        long follower = accounts.ensureFollowerAccount(tenant, owner, "PAPER-F", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, DAY, risk, 100);
        FanOut fanOut = new FanOut(dataSource);
        fanOut.subscribe(group, follower, List.of("opening-drive", "expiry-trend-rider", "ma-cross"), 1.0);

        session(SIGNAL_SESSION, "SIGNAL-1", "it1");
        SignalDecisionStore store = new SignalDecisionStore(dataSource);
        store.insert(decision(group, 1, "09:25:00", "opening-drive", SignalDecision.Action.ENTER, "CE", 183.81, 500_000, 124,
                Map.of("liquidity_lots", 124.0, "strategy_lots", 1.0, "intended_lots", 1.0)));
        store.insert(decision(group, 2, "10:00:00", "opening-drive", SignalDecision.Action.EXIT, "CE", 488.19, Double.NaN, 124,
                Map.of()));
        // etr: Rs 6L budget at Rs 5L equity is over the follower's per-trade cap -> refused
        store.insert(decision(group, 3, "11:40:00", "expiry-trend-rider", SignalDecision.Action.ENTER, "PE", 300, 600_000, 100,
                Map.of("liquidity_lots", 100.0, "strategy_lots", 1.0, "intended_lots", 1.0)));
        store.insert(decision(group, 4, "14:06:00", "expiry-trend-rider", SignalDecision.Action.EXIT, "PE", 281, Double.NaN, 100,
                Map.of()));
        store.insert(decision(group, 5, "12:30:00", "ma-cross", SignalDecision.Action.ENTER, "CE", 150, 200_000, 50,
                Map.of("liquidity_lots", 50.0, "strategy_lots", 1.0, "intended_lots", 1.0)));
        store.insert(decision(group, 6, "13:00:00", "ma-cross", SignalDecision.Action.EXIT, "CE", 160, Double.NaN, 50,
                Map.of()));
        fanOut.fanOut(group, DAY);
        CostModel costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs",
                "india-index-options-costs.v2.yaml")));
        BrokerAccount account = accounts.followers(group).stream().filter(a -> a.id() == follower).findFirst().orElseThrow();
        new FollowerSimulator(dataSource, costs, FollowerRisk.load(Path.of(risk)), 1e9).run(account, group, DAY);

        // PAPER-1: odb at 09:25:10 (matches), etr at 11:40:05 (the follower refused it: explained), a second odb at 13:30 with
        // no signal decision (unexplained), odm (not subscribed: left out); for the follower's ma-cross PAPER-1's risk
        // engine refused at 12:30:20 (explained)
        long paper1 = session(null, "PAPER-1", "it1");
        session(null, "PAPER-1", "other");                       // a later session of another code version: not chosen
        paper1Position(paper1, "opening-drive", "CE", "09:25:10", 2480, 7_50_000);
        paper1Position(paper1, "expiry-trend-rider", "PE", "11:40:05", 2000, -38_000);
        paper1Position(paper1, "opening-drive", "CE", "13:30:00", 1000, 12_000);
        paper1Position(paper1, "opening-drive-momentum", "PE", "09:21:00", 400, -77_000);
        jdbc.update("insert into trade.rejection (session_id, underlying, intent, reason, at, strategy_id) values (?, 'SENSEX', "
                + "'ENTER CE', 'capital: premium over equity', ?, 'ma-cross')", paper1, Timestamp.from(ist("12:30:20")));

        DivergenceReport.Report report = new DivergenceReport(dataSource).build(group, DAY, null);
        assertThat(report.signalSessionId()).isEqualTo(SIGNAL_SESSION);
        assertThat(report.paper1SessionId()).as("same mode and code version as the signal session").isEqualTo(paper1);

        DivergenceReport.FollowerSummary f = report.followers().getFirst();
        assertThat(f.entries()).isEqualTo(3);
        assertThat(f.executed()).isEqualTo(2);
        assertThat(f.notFollowed()).containsEntry("REJECTED_PER_TRADE", 1);
        DivergenceReport.FollowerTrade odb = f.trades().stream().filter(t -> t.strategy().equals("opening-drive")).findFirst()
                .orElseThrow();
        assertThat(odb.lots()).isEqualTo(124);
        assertThat(odb.entrySlipPct()).as("filled at the signal's price moved against the account").isGreaterThanOrEqualTo(0);
        assertThat(odb.exitFill()).isNotNull();
        assertThat(odb.exitSlipPct()).isLessThanOrEqualTo(0);
        assertThat(odb.exitAt()).isEqualTo(ist("10:00:00"));

        DivergenceReport.Paper1Comparison c = report.paper1().getFirst();
        assertThat(c.matched()).isEqualTo(1);
        assertThat(c.onlyPaper1()).isEqualTo(2);
        assertThat(c.onlyFollower()).isEqualTo(1);
        assertThat(c.unexplained()).isEqualTo(1);
        assertThat(c.withinGate()).isFalse();
        assertThat(c.paper1Net()).as("odm is not subscribed: left out").isEqualTo(7_50_000 - 38_000 + 12_000);
        assertThat(c.rows()).filteredOn(m -> m.kind().equals("ONLY_PAPER1") && m.strategy().equals("expiry-trend-rider"))
                .singleElement().satisfies(m -> assertThat(m.explanation()).isEqualTo("follower REJECTED PER_TRADE"));
        assertThat(c.rows()).filteredOn(m -> m.kind().equals("ONLY_FOLLOWER")).singleElement()
                .satisfies(m -> assertThat(m.explanation()).startsWith("PAPER-1 refused: capital"));
        assertThat(c.rows()).filteredOn(m -> m.explanation() == null).singleElement()
                .satisfies(m -> assertThat(m.at()).isEqualTo(ist("13:30:00")));

        String markdown = DivergenceReport.markdown(report, "LIVE-SET");
        assertThat(markdown).contains("# Divergence report 2026-10-15", "| PAPER-F |", "**OUTSIDE**", "**unexplained**",
                "REJECTED_PER_TRADE");

        // the unexplained trade removed: the day is within the gate
        jdbc.update("delete from trade.position where session_id = ? and opened_at = ?", paper1, Timestamp.from(ist("13:30:00")));
        DivergenceReport.Paper1Comparison again = new DivergenceReport(dataSource).build(group, DAY, null).paper1().getFirst();
        assertThat(again.unexplained()).isZero();
        assertThat(again.withinGate()).isTrue();

        // a given PAPER-1 session is used as is
        long empty = session(null, "PAPER-1", "it1");
        DivergenceReport.Report given = new DivergenceReport(dataSource).build(group, DAY, empty);
        assertThat(given.paper1SessionId()).isEqualTo(empty);
        assertThat(given.paper1().getFirst().onlyFollower()).isEqualTo(2);
    }

    @Test
    void aFollowerThatDidNotRunTheDayIsReportedAsNotRun() {
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIGNAL-2", 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "NOT-RUN", signal);
        long follower = accounts.ensureFollowerAccount(tenant, owner, "PAPER-IDLE", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, DAY, "../config/risk/follower-risk.v1.yaml", 100);
        FanOut fanOut = new FanOut(dataSource);
        fanOut.subscribe(group, follower, List.of("opening-drive"), 1.0);
        long signalSession = session(null, "SIGNAL-2", "it2");
        new SignalDecisionStore(dataSource).insert(new SignalDecision(group, signalSession, DAY, ist("09:25:00"), "opening-drive",
                2, "SENSEX", SignalDecision.Action.ENTER, "CE", null, "CONFIRMED", "IT", 1234, "BSE_FO|CE", "SENSEX 73200 CE", 20,
                183.81, 500_000, 500_000, 124, group + ":" + signalSession + ":1", Map.of()));
        fanOut.fanOut(group, DAY);                                    // intents created, the follower never run
        long paper1 = session(null, "PAPER-1", "it2");
        paper1Position(paper1, "opening-drive", "CE", "09:25:10", 2480, 7_50_000);

        DivergenceReport.Report report = new DivergenceReport(dataSource).build(group, DAY, paper1);
        DivergenceReport.Paper1Comparison c = report.paper1().getFirst();
        assertThat(c.followerRan()).isFalse();
        assertThat(c.withinGate()).isFalse();
        assertThat(c.rows()).isEmpty();
        assertThat(DivergenceReport.markdown(report, "NOT-RUN")).contains("not run this day").doesNotContain("**OUTSIDE**");
    }

    @Test
    void addsCountInTheQuantityAndARefusedAddExplainsASizeDivergence() {
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIGNAL-3", 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "ADDS", signal);
        String risk = "../config/risk/follower-risk.v1.yaml";
        long follower = accounts.ensureFollowerAccount(tenant, owner, "PAPER-ADDS", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, DAY, risk, 100);
        FanOut fanOut = new FanOut(dataSource);
        fanOut.subscribe(group, follower, List.of("opening-drive"), 1.0);
        long signalSession = session(null, "SIGNAL-3", "it3");
        Map<String, Double> sizing = Map.of("liquidity_lots", 1000.0, "strategy_lots", 1.0, "intended_lots", 1.0);
        SignalDecisionStore store = new SignalDecisionStore(dataSource);
        String[][] rows = {{"09:25:00", "ENTER", "100"}, {"09:40:00", "ADD", "110"}, {"09:50:00", "ADD", "50000"},
                {"10:30:00", "EXIT", "120"}};
        for (int i = 0; i < rows.length; i++) {
            SignalDecision.Action action = SignalDecision.Action.valueOf(rows[i][1]);
            store.insert(new SignalDecision(group, signalSession, DAY, ist(rows[i][0]), "opening-drive", 2, "SENSEX", action,
                    "CE", null, "CONFIRMED", "IT", 1234, "BSE_FO|CE", "SENSEX 73200 CE", 20, Double.parseDouble(rows[i][2]),
                    action == SignalDecision.Action.ENTER ? 100_000 : Double.NaN, 500_000, 0,
                    group + ":" + signalSession + ":" + (i + 1), action == SignalDecision.Action.EXIT ? Map.of() : sizing));
        }
        fanOut.fanOut(group, DAY);
        CostModel costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs",
                "india-index-options-costs.v2.yaml")));
        BrokerAccount account = accounts.followers(group).stream().filter(a -> a.id() == follower).findFirst().orElseThrow();
        new FollowerSimulator(dataSource, costs, FollowerRisk.load(Path.of(risk)), 1e9).run(account, group, DAY);
        // the entry's own order (the first ENTRY fill) and everything filled as ENTRY (entry + the executed add)
        Map<String, Object> entryOrder = jdbc.queryForMap("select e.quantity_filled, e.average_fill_price from exec.order_execution e "
                + "where e.broker_account_id = ? and e.role = 'ENTRY' order by e.id limit 1", follower);
        long entryQuantity = ((Number) entryOrder.get("quantity_filled")).longValue();
        double entryPrice = ((Number) entryOrder.get("average_fill_price")).doubleValue();
        long filled = jdbc.queryForObject("select sum(quantity_filled) from exec.order_execution where broker_account_id = ? "
                + "and role = 'ENTRY'", Long.class, follower);
        assertThat(filled).as("the 09:40 add executed").isGreaterThan(entryQuantity);

        long paper1 = session(null, "PAPER-1", "it3");
        paper1Position(paper1, "opening-drive", "CE", "09:25:05", filled + entryQuantity, 30_000);   // took both adds

        DivergenceReport.Report report = new DivergenceReport(dataSource).build(group, DAY, paper1);
        DivergenceReport.FollowerSummary f = report.followers().getFirst();
        assertThat(f.ran()).isTrue();
        assertThat(f.entries()).isEqualTo(1);
        assertThat(f.executed()).isEqualTo(1);
        assertThat(f.addsExecuted()).isEqualTo(1);
        assertThat(f.notFollowed()).hasSize(1).allSatisfy((outcome, n) -> assertThat(outcome).startsWith("ADD_REJECTED_"));
        DivergenceReport.FollowerTrade entry = f.trades().stream().filter(t -> t.action().equals("ENTER")).findFirst()
                .orElseThrow();
        assertThat(entry.lots()).as("the entry's lots, not entry + add").isEqualTo((int) (entryQuantity / 20));
        assertThat(entry.entryFill()).as("the entry's own fill, not the average after the add").isEqualTo(entryPrice);
        assertThat(entry.entrySlipPct()).isLessThan(2.0);

        DivergenceReport.Paper1Comparison c = report.paper1().getFirst();
        assertThat(c.sizeDivergences()).isEqualTo(1);
        assertThat(c.rows()).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo("SIZE");
            assertThat(m.followerQuantity()).as("entry + the executed add").isEqualTo(filled);
            assertThat(m.explanation()).startsWith("follower ADD REJECTED").endsWith("09:50");
        });
        assertThat(c.withinGate()).isTrue();
        assertThat(DivergenceReport.markdown(report, "ADDS")).contains("| SIZE |", "follower ADD REJECTED", "| ADD |");

        // without the refused add the size divergence is unexplained, unless the follower holds exactly the signal
        // account's fill (the replay depth ceiling)
        jdbc.update("delete from exec.trade_intent where broker_account_id = ? and state = 'REJECTED'", follower);
        assertThat(new DivergenceReport(dataSource).build(group, DAY, paper1).paper1().getFirst().unexplained()).isEqualTo(1);
        paper1Position(signalSession, "opening-drive", "CE", "09:25:00", filled, 20_000);
        assertThat(new DivergenceReport(dataSource).build(group, DAY, paper1).paper1().getFirst().rows()).singleElement()
                .satisfies(m -> assertThat(m.explanation()).startsWith("replay depth ceiling: the signal account filled"));

        // PAPER-1 with the follower's quantity: matched
        jdbc.update("update trade.position set quantity = ? where session_id = ?", filled, paper1);
        DivergenceReport.Paper1Comparison same = new DivergenceReport(dataSource).build(group, DAY, paper1).paper1().getFirst();
        assertThat(same.matched()).isEqualTo(1);
        assertThat(same.sizeDivergences()).isZero();
    }

    @Test
    void aDayWithoutSignalDecisionsSaysSo() {
        DivergenceReport.Report report = new DivergenceReport(dataSource).build(999_999, DAY.plusDays(1), null);
        assertThat(report.signalSessionId()).isNull();
        assertThat(DivergenceReport.markdown(report, "NONE")).contains("No signal decisions for this day.");
    }
}
