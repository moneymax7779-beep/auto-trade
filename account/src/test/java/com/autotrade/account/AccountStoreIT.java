package com.autotrade.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** V21 on the real schema: PAPER-1 registration, tenant scoping, the live guards in the table, the ledger. */
@Testcontainers(disabledWithoutDocker = true)
class AccountStoreIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final LocalDate FROM = LocalDate.of(2026, 10, 7);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static AccountStore store;
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
        store = new AccountStore(dataSource);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    @Test
    void paper1IsRegisteredOnceAsADisabledPaperAccount() {
        long tenant = store.ensureTenant("owner");
        long user = store.ensureUser("kc-owner", "Owner");
        store.ensureMembership(tenant, user, "OWNER");
        long id = store.registerLegacyPaperAccount(tenant, user, "PAPER-1", "PAPER-1", 500_000, FROM,
                "config/risk/paper-risk.v17.yaml");
        assertThat(store.registerLegacyPaperAccount(tenant, user, "PAPER-1", "PAPER-1", 500_000, FROM,
                "config/risk/paper-risk.v17.yaml")).isEqualTo(id);                  // idempotent
        BrokerAccount paper = store.byLegacyLabel("PAPER-1").orElseThrow();
        assertThat(paper.brokerType()).isEqualTo(BrokerType.PAPER);
        assertThat(paper.role()).isEqualTo(AccountRole.DISABLED);                   // receives no intents
        assertThat(paper.tradeMode()).isEqualTo(TradeMode.PAPER);
        assertThat(paper.liveTradingEnabled()).isFalse();
        assertThat(paper.allocatedCapital()).isEqualTo(500_000);
    }

    @Test
    void aUserSeesOnlyTheAccountsOfTheirOwnTenants() {
        long a = store.ensureTenant("tenant-a"), b = store.ensureTenant("tenant-b");
        long ua = store.ensureUser("kc-a", "A"), ub = store.ensureUser("kc-b", "B");
        store.ensureMembership(a, ua, "OWNER");
        store.ensureMembership(b, ub, "OWNER");
        store.registerLegacyPaperAccount(a, ua, "A-PAPER", "A-PAPER", 100_000, FROM, "r");
        store.registerLegacyPaperAccount(b, ub, "B-PAPER", "B-PAPER", 100_000, FROM, "r");
        assertThat(store.visibleTo("kc-a")).extracting(BrokerAccount::displayName).containsExactly("A-PAPER");
        assertThat(store.visibleTo("kc-b")).extracting(BrokerAccount::displayName).containsExactly("B-PAPER");
        assertThat(store.visibleTo("kc-nobody")).isEmpty();
    }

    @Test
    void theTableRefusesLiveOnAPaperAccountOrWithoutCredentials() {
        long tenant = store.ensureTenant("guards");
        long user = store.ensureUser("kc-guards", "G");
        long id = store.registerLegacyPaperAccount(tenant, user, "G-PAPER", "G-PAPER", 100_000, FROM, "r");
        assertThatThrownBy(() -> jdbc.update("update acct.broker_account set trade_mode = 'LIVE', "
                + "live_trading_enabled = true where id = ?", id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into acct.broker_account (tenant_id, app_user_id, broker_type, "
                + "display_name, trade_mode, live_trading_enabled, allocated_capital, equity_from, risk_file) values "
                + "(?, ?, 'ZERODHA', 'Z-NOCRED', 'LIVE', true, 1000, ?, 'r')", tenant, user, java.sql.Date.valueOf(FROM)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void capitalMovementsCountUntilTheirDateAndTheLedgerBlocksOnADifference() {
        long tenant = store.ensureTenant("ledger");
        long user = store.ensureUser("kc-ledger", "L");
        long id = store.registerLegacyPaperAccount(tenant, user, "L-PAPER", "L-PAPER", 100_000, FROM, "r");
        store.addCapitalMovement(id, LocalDate.of(2026, 10, 10), 50_000, "top-up", user);
        store.addCapitalMovement(id, LocalDate.of(2026, 10, 20), -20_000, "withdrawal", user);
        assertThat(store.capitalMovements(id, LocalDate.of(2026, 10, 16))).isEqualTo(50_000);
        assertThat(store.capitalMovements(id, LocalDate.of(2026, 10, 20))).isEqualTo(30_000);

        LedgerDay day = LedgerDay.close(id, LocalDate.of(2026, 10, 16), 150_000, 10_000, 500, 2);
        store.writeLedgerDay(day.reconciled(9_500, 500));
        assertThat(store.hasUnacknowledgedDifference(id)).isFalse();
        store.writeLedgerDay(day.reconciled(9_000, 1_000));                         // broker says ₹500 less
        assertThat(store.hasUnacknowledgedDifference(id)).isTrue();
    }
}
