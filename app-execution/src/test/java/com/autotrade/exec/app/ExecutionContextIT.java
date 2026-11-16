package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.BrokerType;
import com.autotrade.execution.AccountEgress;
import com.autotrade.execution.BrokerRules;
import com.autotrade.execution.LiveBrokers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The execution worker's Spring context, started as production starts it (review 16 Nov 2026: the broker rules, the
 * configured LIVE brokers, the egress gate and seven controllers with several constructors were wired only in
 * production; a wiring mistake would have left the worker down at 08:30, with no Flatten, kill switch or square-off).
 */
@Testcontainers(disabledWithoutDocker = true)
class ExecutionContextIT {

    private static final DockerImageName TIMESCALE = DockerImageName.parse("timescale/timescaledb:2.30.1-pg17")
            .asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer postgres;
    private static Path dir;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        try (HikariDataSource ds = new HikariDataSource(config)) {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        }
        dir = Files.createTempDirectory("exec-context");
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static ConfigurableApplicationContext run(String... extra) {
        List<String> props = new ArrayList<>(List.of(
                "spring.datasource.url=" + postgres.getJdbcUrl(),
                "spring.datasource.username=" + postgres.getUsername(),
                "spring.datasource.password=" + postgres.getPassword(),
                "server.port=0",
                "autotrade.execution.poll-ms=3600000",
                "autotrade.execution.wake-on-signal=false",
                "autotrade.execution.broker-rules-file=../config/brokers/broker-rules.v1.yaml",
                "autotrade.execution.exchange-file=../config/exchange/nse-bse-sessions.v3.yaml",
                "autotrade.execution.read-live-probe.dir=",
                "autotrade.execution.kite.secrets-dir=" + dir.resolve("secrets"),
                "autotrade.execution.kite.sessions-dir=" + dir.resolve("sessions"),
                "autotrade.execution.kite.telegram-file=" + dir.resolve("telegram.env"),
                "autotrade.execution.brokers.store-dir=" + dir.resolve("brokers")));
        props.addAll(List.of(extra));
        // as command-line arguments: they win over application.yml (builder properties are only defaults)
        return new SpringApplicationBuilder(ExecutionApplication.class).run(props.stream().map(p -> "--" + p)
                .toArray(String[]::new));
    }

    @Test
    void theWorkerStartsWithItsBrokerRulesLiveBrokersAndEgressGate() {
        try (ConfigurableApplicationContext ctx = run()) {
            assertThat(ctx.getBean(LiveBrokers.class).live()).containsExactly(BrokerType.ZERODHA);
            assertThat(ctx.getBean(ExecutionService.class).liveBrokers()).as("one instance for routes and pages")
                    .isSameAs(ctx.getBean(LiveBrokers.class));
            BrokerRules rules = ctx.getBean(BrokerRules.class);
            assertThat(rules.rule(BrokerType.ZERODHA).product()).isEqualTo("MIS");
            assertThat(ctx.getBean(AccountEgress.class).requiredBrokers())
                    .containsExactlyInAnyOrder(BrokerType.KOTAK_NEO, BrokerType.UPSTOX, BrokerType.GROWW);
            for (Class<?> controller : List.of(LiveSwitchController.class, WorkspaceController.class,
                    AccountAdminController.class, BrokerAppController.class, CanaryController.class)) {
                assertThat(ctx.getBean(controller)).as(controller.getSimpleName()).isNotNull();
            }
        }
    }

    @Test
    void aBrokerNameTypoStopsTheWorkerFromStarting() {
        assertThatThrownBy(() -> run("autotrade.execution.live-brokers=ZERODHA,KOTAK").close())
                .hasStackTraceContaining("unknown broker KOTAK");
    }
}
