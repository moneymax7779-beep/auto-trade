package com.autotrade.trading;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.autotrade.core.time.MarketTime;
import com.autotrade.md.zt.ZtReadOnlyDataSource;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Watches the always-on service once a minute (--mode=auto only; replays never alert): the Upstox token
 * before the open, zt-tiger-v2's database, the Mac's free disk, the live session (running, feed fresh, kill
 * switch), and reports trades and the end-of-day result. Read-only everywhere; it never acts on what it sees.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class AlertMonitor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AlertMonitor.class);
    private static final double GB = 1024.0 * 1024 * 1024;

    private final SessionRunner runner;
    private final AlertService alerts;
    private final AlertProperties properties;
    private final TradingProperties trading;
    private final JdbcTemplate jdbc;

    private HikariDataSource zt;
    private int ztFailures;
    private long lastTokenCheckMinute = -1;
    private Long sessionId;
    private String lastStatus;
    private final Set<Long> seenOpened = new HashSet<>();
    private final Set<Long> seenClosed = new HashSet<>();

    AlertMonitor(SessionRunner runner, AlertService alerts, AlertProperties properties, TradingProperties trading,
                 DataSource target) {
        this.runner = runner;
        this.alerts = alerts;
        this.properties = properties;
        this.trading = trading;
        this.jdbc = new JdbcTemplate(target);
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> mode = args.getOptionValues("mode");
        if (mode == null || !mode.contains("auto")) {
            return;
        }
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
                runnable -> Thread.ofPlatform().name("alert-monitor").daemon(true).unstarted(runnable));
        timer.scheduleWithFixedDelay(this::tick, 30, 60, TimeUnit.SECONDS);
        log.info("alerts on: telegram {}", alerts.telegramConfigured() ? "configured" : "not configured (log and UI only)");
    }

    private synchronized void tick() {
        ZonedDateTime now = ZonedDateTime.now(MarketTime.IST);
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        boolean tradingDay = runner.tradingDay(today);
        for (Runnable check : List.<Runnable>of(this::disk, () -> zt(tradingDay, time), () -> token(tradingDay, time),
                () -> session(tradingDay, today, time))) {
            try {
                check.run();
            } catch (RuntimeException e) {
                log.warn("alert check failed: {}", e.toString());
            }
        }
    }

    // ---------------------------------------------------------------- disk (the Mac's, via a bind mount)

    private void disk() {
        try {
            double free = Files.getFileStore(Path.of(properties.diskPath())).getUsableSpace() / GB;
            if (free < properties.diskCriticalGb()) {
                alerts.raise("disk", AlertService.Level.CRITICAL, String.format(
                        "Mac disk almost full: %.1f GB free. Live capture writes about 3 GB a day; free space now.", free));
            } else if (free < properties.diskWarnGb()) {
                alerts.raise("disk", AlertService.Level.WARN, String.format("Mac disk low: %.1f GB free.", free));
            } else if (free >= properties.diskWarnGb() + 2) {
                alerts.clear("disk", String.format("Mac disk OK again: %.1f GB free.", free));
            }
        } catch (java.io.IOException e) {
            log.warn("disk check failed: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- zt-tiger-v2 database

    private void zt(boolean tradingDay, LocalTime time) {
        if (!tradingDay || time.isBefore(LocalTime.of(7, 30)) || time.isAfter(LocalTime.of(16, 0))) {
            return;
        }
        if (ztReachable()) {
            ztFailures = 0;
            alerts.clear("zt", "zt-tiger-v2 database reachable again.");
        } else if (++ztFailures >= 3) {
            alerts.raise("zt", AlertService.Level.CRITICAL,
                    "zt-tiger-v2 database unreachable for " + ztFailures + " minutes. auto-trade needs it for the Upstox "
                            + "token (and as its fallback feed): start zt-tiger-v2.");
        }
    }

    private boolean ztReachable() {
        try {
            if (zt == null) {
                TradingProperties.Source s = trading.source();
                zt = ZtReadOnlyDataSource.open(new ZtReadOnlyDataSource.Settings(s.url(), s.username(), s.password(),
                        s.passwordFile(), s.passwordKey(), "autotrade-alerts", 1));
            }
            Integer one = new JdbcTemplate(zt).queryForObject("select 1", Integer.class);
            return one != null && one == 1;
        } catch (RuntimeException | java.sql.SQLException e) {
            if (zt != null) {
                zt.close();
                zt = null;
            }
            return false;
        }
    }

    // ---------------------------------------------------------------- Upstox token before the open

    private void token(boolean tradingDay, LocalTime time) {
        if (!tradingDay || time.isBefore(LocalTime.of(8, 0)) || !time.isBefore(LocalTime.of(9, 0))) {
            return;
        }
        long minute = time.toSecondOfDay() / 60;
        if (lastTokenCheckMinute >= 0 && minute - lastTokenCheckMinute < 10) {
            return;
        }
        lastTokenCheckMinute = minute;
        if (zt == null && !ztReachable()) {
            return;                                   // the zt alert covers it
        }
        try {
            if (runner.upstoxToken(zt).isPresent()) {
                alerts.clear("token", "Upstox token valid: today's live session can start at 09:00.");
            } else {
                alerts.raise("token", AlertService.Level.CRITICAL,
                        "No valid Upstox token. Sign in to Upstox in zt-tiger-v2 before 09:00, or the live feed won't start.");
            }
        } catch (java.io.IOException e) {
            log.warn("token check failed: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- the live session

    private void session(boolean tradingDay, LocalDate today, LocalTime time) {
        Map<String, Object> row = jdbc.query("select id, status, summary::text summary from trade.session "
                        + "where mode = 'PAPER_LIVE' and session_date = ? order by id desc limit 1",
                rs -> rs.next() ? Map.<String, Object>of("id", rs.getLong("id"), "status", rs.getString("status"),
                        "summary", rs.getString("summary")) : null, today);
        boolean inWindow = tradingDay && !time.isBefore(LocalTime.of(9, 2)) && time.isBefore(LocalTime.of(15, 40));
        String status = row == null ? null : (String) row.get("status");
        if (inWindow && !"RUNNING".equals(status) && !"DONE".equals(status)) {
            alerts.raise("session", AlertService.Level.CRITICAL, row == null
                    ? "Today's live session has not started (it should start at 09:00)."
                    : "Today's live session is " + status + " (session " + row.get("id") + "). Check the service log.");
        } else if ("RUNNING".equals(status)) {
            alerts.clear("session", "Live session " + row.get("id") + " is running.");
        }
        if (row == null) {
            return;
        }
        long id = (Long) row.get("id");
        boolean first = sessionId == null || sessionId != id;
        if (first) {
            sessionId = id;
            seenOpened.clear();
            seenClosed.clear();
        }
        if ("RUNNING".equals(status)) {
            feed(time);
        }
        trades(id, first);
        if ("DONE".equals(status) && lastStatus != null && !"DONE".equals(lastStatus)) {
            alerts.info("Session " + id + " done: " + row.get("summary"));
        }
        lastStatus = status;
    }

    private void feed(LocalTime time) {
        TradingSession live = runner.current();
        Map<String, Object> s = live == null ? Map.of() : live.status();
        Object lag = s.get("feedLagSeconds");
        Object feedName = s.get("feed");
        boolean continuous = !time.isBefore(LocalTime.of(9, 16)) && time.isBefore(LocalTime.of(15, 30));
        if (continuous && lag instanceof Number seconds && seconds.doubleValue() > 60) {
            alerts.raise("feed", AlertService.Level.CRITICAL, String.format(
                    "Live feed stale: last event %.0f s ago (%s). New entries are blocked while the feed is stale.",
                    seconds.doubleValue(), feedName));
        } else if (lag instanceof Number) {
            alerts.clear("feed", "Live feed fresh again.");
        }
        if (feedName != null && feedName.toString().contains("zt-tail")) {
            alerts.raise("fallback", AlertService.Level.WARN,
                    "Live session is running on the zt-tiger-v2 tail fallback, not the Upstox feed.");
        }
        Object kill = s.get("killSwitches");
        if (kill instanceof java.util.Collection<?> engaged && !engaged.isEmpty()) {
            alerts.raise("kill", AlertService.Level.CRITICAL, "Kill switch engaged: " + engaged
                    + ". No new entries until it is released.");
        } else if (kill != null) {
            alerts.clear("kill", "Kill switch released.");
        }
    }

    private void trades(long session, boolean first) {
        if (!properties.trades()) {
            return;
        }
        jdbc.query("select id, strategy_id, symbol, to_char(opened_at at time zone 'Asia/Kolkata', 'HH24:MI') opened, "
                        + "to_char(closed_at at time zone 'Asia/Kolkata', 'HH24:MI') closed, closed_at is not null done, "
                        + "exit_reason, quantity, lot_size, average_cost, average_exit, net "
                        + "from trade.position where session_id = ? order by id", rs -> {
            long id = rs.getLong("id");
            String what = rs.getString("strategy_id") + " · " + rs.getString("symbol");
            long qty = rs.getLong("quantity");
            int lot = rs.getInt("lot_size");
            String lots = lot > 0 && qty > 0 ? (qty / lot) + " lots (" + qty + ")" : "";
            if (qty == 0) {                                   // an entry that never filled: nothing to report
                seenOpened.add(id);
                seenClosed.add(id);
                return;
            }
            if (seenOpened.add(id) && !first) {
                alerts.info("Trade opened " + rs.getString("opened") + ": " + what + " " + lots
                        + String.format(" @ %.2f", rs.getDouble("average_cost")));
            }
            if (rs.getBoolean("done") && seenClosed.add(id) && !first) {
                alerts.info("Trade closed " + rs.getString("closed") + ": " + what + " " + lots
                        + String.format(" %.2f → %.2f, %s, net ₹%,.0f", rs.getDouble("average_cost"),
                        rs.getDouble("average_exit"), rs.getString("exit_reason"), rs.getDouble("net")));
            }
        }, session);
    }
}
