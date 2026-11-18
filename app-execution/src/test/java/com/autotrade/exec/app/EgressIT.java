package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.broker.Egress;
import com.autotrade.execution.AccountEgress;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Automatic static IPs (18 Nov 2026): the owner asks (a REQUESTED row), the host provisioner writes PROVISIONED, the
 * worker binds the account to its container address, checks through it that calls leave from the reserved IP, and LIVE
 * waits for a passing check and the owner's typed word that the IP is registered at the broker.
 */
@Testcontainers(disabledWithoutDocker = true)
class EgressIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static EgressController api;
    private static long tenant;
    private static long owner;
    private static int seq;

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
        AccountStore accounts = new AccountStore(dataSource);
        tenant = accounts.ensureTenant("owner");
        owner = accounts.ensureUser("local-owner", "me");
        accounts.ensureMembership(tenant, owner, "OWNER");
        accounts.ensureMembership(tenant, accounts.ensureUser("kc-trader", "trader"), "TRADER");
        long other = accounts.ensureTenant("someone else");
        accounts.ensureMembership(other, accounts.ensureUser("kc-stranger", "stranger"), "OWNER");
        api = new EgressController(new Access(dataSource), dataSource);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private static long account(BrokerType broker) {
        return new AccountStore(dataSource).ensureFollowerAccount(tenant, owner, broker + "-" + (++seq), broker,
                broker == BrokerType.PAPER ? TradeMode.PAPER : TradeMode.DRY_RUN, 50_000, EquityMode.FIXED,
                LocalDate.now(), "risk.yaml", 0);
    }

    private static String refusal(Runnable call) {
        try {
            call.run();
            return "allowed";
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value() + " " + e.getReason();
        }
    }

    /** What bin/egress writes when it has finished an account. */
    private static void provisioned(long id, int slot, String publicIp, String container) {
        jdbc.update("update acct.broker_egress set state = 'PROVISIONED', slot = ?, public_ip = ?::inet, "
                + "container_ip = ?::inet, alias_ip = ?::inet, provisioned_at = now() where broker_account_id = ?",
                slot, publicIp, container, "10.21.0." + (8 * slot + 2), id);
    }

    @Test
    void onlyAManagerAsksOnceForARealBrokerAccount() {
        long kotak = account(BrokerType.KOTAK_NEO);
        long paper = account(BrokerType.PAPER);
        assertThat(api.get("local-owner", kotak).get("state")).isEqualTo("NONE");
        assertThat(refusal(() -> api.request("kc-trader", kotak))).startsWith("403");
        assertThat(refusal(() -> api.request("kc-stranger", kotak))).startsWith("404");
        assertThat(refusal(() -> api.request("local-owner", paper))).contains("paper account");
        assertThat(api.request("local-owner", kotak).get("state")).isEqualTo("REQUESTED");
        assertThat(refusal(() -> api.request("local-owner", kotak))).contains("already has a static IP");
        jdbc.update("update acct.broker_egress set state = 'FAILED', attempts = 3, last_error = 'quota' "
                + "where broker_account_id = ?", kotak);
        assertThat(api.get("local-owner", kotak)).containsEntry("state", "FAILED").containsEntry("error", "quota");
        Map<String, Object> again = api.request("local-owner", kotak);
        assertThat(again).containsEntry("state", "REQUESTED").containsEntry("attempts", 0);
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where action = 'EGRESS_REQUESTED' "
                + "and broker_account_id = ?", Integer.class, kotak)).isEqualTo(2);
    }

    @Test
    void liveWaitsForAPassingCheckAndTheOwnersTypedRegistration() {
        long kotak = account(BrokerType.KOTAK_NEO);
        api.request("local-owner", kotak);
        assertThat(refusal(() -> api.registered("local-owner", kotak, new EgressController.Registered(true,
                "34.1.2.3")))).contains("no provisioned static IP");
        provisioned(kotak, 1, "34.1.2.3", "172.30.0.10");

        AccountEgress egress = new AccountEgress(Map.of(), AccountEgress.DEFAULT_REQUIRED, a -> true);
        Map<String, String> answers = new HashMap<>(Map.of("a.example", "34.1.2.3", "b.example", "34.1.2.3"));
        List<InetAddress> boundTo = new java.util.ArrayList<>();
        boolean[] sharedUp = {true};
        EgressRegistry registry = new EgressRegistry(dataSource, egress,
                List.of(URI.create("https://a.example"), URI.create("https://b.example")), (from, uri) -> {
                    if (from == null) {                               // the shared route
                        if (!sharedUp[0]) {
                            throw new IllegalStateException("no internet");
                        }
                        return "34.14.137.170";
                    }
                    boundTo.add(from);
                    String v = answers.get(uri.getHost());
                    if (v == null) {
                        throw new IllegalStateException("timeout");
                    }
                    return v + "\n";
                }, a -> true);
        com.autotrade.account.BrokerAccount a = new AccountStore(dataSource).byId(kotak).orElseThrow();

        registry.refresh();
        assertThat(egress.address(a)).isEqualTo(Egress.parse("172.30.0.10"));
        assertThat(egress.liveRefusal(a)).contains("has not passed yet");

        registry.checkAll();
        assertThat(boundTo).as("every echo is asked from the account's own address").containsOnly(
                Egress.parse("172.30.0.10"));
        assertThat(api.get("local-owner", kotak)).containsEntry("checkOk", true).containsEntry("checkIp", "34.1.2.3");
        assertThat(egress.liveRefusal(a)).contains("register 34.1.2.3 at the broker");

        assertThat(refusal(() -> api.registered("kc-trader", kotak, new EgressController.Registered(true,
                "34.1.2.3")))).startsWith("403");
        assertThat(refusal(() -> api.registered("local-owner", kotak, new EgressController.Registered(true,
                "34.1.2.4")))).contains("type the IP you registered at the broker (34.1.2.3)");
        api.registered("local-owner", kotak, new EgressController.Registered(true, " 34.1.2.3 "));
        registry.refresh();
        assertThat(egress.liveRefusal(a)).as("checked and registered: the static-IP gate opens").isNull();

        answers.put("b.example", "34.14.137.170");                    // one service sees the shared NAT
        registry.checkAll();
        assertThat(egress.liveRefusal(a)).contains("not 34.1.2.3");
        assertThat((String) api.get("local-owner", kotak).get("error")).contains("34.14.137.170");

        answers.put("b.example", "34.1.2.3");
        registry.checkAll();
        assertThat(egress.liveRefusal(a)).isNull();
        answers.remove("b.example");                                  // one service does not answer: inconclusive
        registry.checkAll();
        assertThat(egress.liveRefusal(a)).as("an echo outage does not lock a LIVE account out").isNull();
        assertThat((String) api.get("local-owner", kotak).get("error")).contains("inconclusive")
                .contains("b.example: timeout");
        answers.clear();                                              // nothing answers through the account's
        registry.checkAll();                                          // address, the shared route does: broken route
        assertThat(egress.liveRefusal(a)).as("E0: a broken account route is a fail, not an outage")
                .contains("not leaving from 34.1.2.3");
        assertThat((String) api.get("local-owner", kotak).get("error")).contains("its route is broken");
        answers.put("a.example", "34.1.2.3");
        answers.put("b.example", "34.1.2.3");
        registry.checkAll();
        assertThat(egress.liveRefusal(a)).isNull();
        answers.clear();
        sharedUp[0] = false;                                          // nothing answers anywhere: the internet,
        registry.checkAll();                                          // not the account: inconclusive
        assertThat(egress.liveRefusal(a)).isNull();
        sharedUp[0] = true;
        answers.put("a.example", "34.1.2.3");
        answers.put("b.example", "not an ip at all");                 // a garbage answer is a conclusive fail
        registry.checkAll();
        assertThat(egress.liveRefusal(a)).contains("not leaving from 34.1.2.3");

        answers.put("b.example", "34.1.2.3");
        registry.checkAll();
        assertThat(egress.liveRefusal(a)).isNull();
        jdbc.update("update acct.broker_egress set last_check_at = now() - interval '13 hours' "
                + "where broker_account_id = ?", kotak);
        registry.apply(registry.provisioned(), Instant.now());
        assertThat(egress.liveRefusal(a)).as("a stale check is no check").contains("no conclusive check");
        registry.checkAll();

        api.registered("local-owner", kotak, new EgressController.Registered(false, null));
        registry.checkAll();
        assertThat(egress.liveRefusal(a)).contains("register 34.1.2.3");
    }

    @Test
    void anAddressNotOnThisHostFailsTheCheckWithoutAsking() {
        long kotak = account(BrokerType.KOTAK_NEO);
        api.request("local-owner", kotak);
        provisioned(kotak, 2, "34.1.2.5", "172.30.0.18");
        AccountEgress egress = new AccountEgress(Map.of(), AccountEgress.DEFAULT_REQUIRED, a -> false);
        EgressRegistry registry = new EgressRegistry(dataSource, egress, List.of(URI.create("https://a.example")),
                (from, uri) -> {
                    throw new AssertionError("never asked");
                }, a -> false);
        registry.checkAll();
        assertThat((String) api.get("local-owner", kotak).get("error")).contains("not on this host");
        com.autotrade.account.BrokerAccount a = new AccountStore(dataSource).byId(kotak).orElseThrow();
        assertThat(egress.liveRefusal(a)).contains("not on this host");
    }

    /** Review 18 Nov 2026: a new address never moves under a LIVE account (positions: the same query, see busy). */
    @Test
    void anAddressNeverMovesUnderALiveAccount() {
        long kotak = account(BrokerType.KOTAK_NEO);
        long live = account(BrokerType.ZERODHA);
        jdbc.update("update acct.broker_account set trade_mode = 'LIVE' where id = ?", live);
        assertThat(refusal(() -> api.request("local-owner", live))).contains("is LIVE");
        api.request("local-owner", kotak);
        jdbc.update("update acct.broker_egress set state = 'RETIRED' where state = 'PROVISIONED'");

        AccountEgress egress = new AccountEgress(Map.of(), AccountEgress.DEFAULT_REQUIRED, x -> true);
        EgressRegistry registry = new EgressRegistry(dataSource, egress, List.of(URI.create("https://a.example")),
                (from, uri) -> "34.1.2.9", x -> true);
        com.autotrade.account.BrokerAccount a = new AccountStore(dataSource).byId(kotak).orElseThrow();
        registry.refresh();                                            // started, nothing provisioned yet
        jdbc.update("update acct.broker_account set trade_mode = 'LIVE' where id = ?", kotak);
        provisioned(kotak, 3, "34.1.2.9", "172.30.0.26");
        registry.refresh();
        assertThat(egress.address(a)).as("provisioned while LIVE: held back").isNull();
        jdbc.update("update acct.broker_account set trade_mode = 'DRY_RUN' where id = ?", kotak);
        registry.refresh();
        assertThat(egress.address(a)).as("off LIVE: applied").isEqualTo(Egress.parse("172.30.0.26"));

        jdbc.update("update acct.broker_account set trade_mode = 'LIVE' where id = ?", kotak);
        AccountEgress fresh = new AccountEgress(Map.of(), AccountEgress.DEFAULT_REQUIRED, x -> true);
        new EgressRegistry(dataSource, fresh, List.of(), (f, u) -> "", x -> true).refresh();
        assertThat(fresh.address(a)).as("at startup, before any route opens, the provisioned address applies")
                .isEqualTo(Egress.parse("172.30.0.26"));
        jdbc.update("update acct.broker_account set trade_mode = 'DRY_RUN' where id = ?", kotak);
    }
}
