package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.autotrade.account.AccountRole;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;

/** Review 8 Nov 2026: a DRY_RUN open that fails (seeding the holdings) closes the simulator it started. */
class ZerodhaDryRunAdapterTest {

    /** The database is down: seeding the simulator's holdings fails. */
    private static final DataSource DOWN = new DataSource() {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("database down");
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            throw new SQLException("database down");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    };

    @Test
    void aFailedOpenLeavesNoSimulatorServerOrThreadsBehind() throws Exception {
        BrokerAccount account = new BrokerAccount(1, 1, 1, BrokerType.ZERODHA, "DRY-LEAK", null, AccountRole.FOLLOWER_TRADE,
                TradeMode.DRY_RUN, false, 500_000, EquityMode.FIXED, LocalDate.of(2026, 11, 9), "risk.yaml", 100, true);
        ZerodhaDryRunAdapter adapter = new ZerodhaDryRunAdapter(DOWN, 1e9);
        Set<Thread> before = Set.copyOf(Thread.getAllStackTraces().keySet());
        for (boolean brokerMarket : new boolean[] {false, true}) {
            OrderRules rules = new OrderRules(1, 1, 1, 1, 1, 3, 1, 25, 5, 0.05, 5, brokerMarket);
            for (int tick = 0; tick < 3; tick++) {                      // tried again on every tick
                assertThatThrownBy(() -> adapter.dryRun(account, rules)).hasRootCauseMessage("database down");
            }
        }
        List<Thread> left = Thread.getAllStackTraces().keySet().stream().filter(t -> !before.contains(t))
                .filter(t -> t.getName().startsWith("kite-sim-") || t.getName().startsWith("HTTP-Dispatcher")).toList();
        for (Thread t : left) {
            t.join(5_000);
        }
        assertThat(left).as("no simulator server or thread of a failed open still runs").noneMatch(Thread::isAlive);
    }
}
