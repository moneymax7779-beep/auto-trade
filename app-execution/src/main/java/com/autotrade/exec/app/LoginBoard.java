package com.autotrade.exec.app;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily login board (multi-broker plan section 3.6): every enabled broker account in DRY_RUN or LIVE, whether its
 * broker session for today is ACTIVE, until when, and how many strategies it follows. An account without a session by
 * the open is out for the day (its entries are refused BROKER_SESSION); the board says so instead of leaving it to be
 * noticed. At 09:10 IST on trading days the board goes to Telegram (after the 08:30 login links).
 */
@Component
class LoginBoard {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm");

    /** One account's line. {@code sessionUntil} null = no ACTIVE session. */
    record Row(long accountId, long tenantId, String tenant, String account, String broker, String mode,
               String connection, Instant sessionUntil, int strategies) {

        boolean ready() {
            return sessionUntil != null && sessionUntil.isAfter(Instant.now()) && "ACTIVE".equals(connection);
        }
    }

    private final JdbcTemplate jdbc;
    private final TelegramNotifier telegram;
    private final TradingDays tradingDays;

    LoginBoard(DataSource dataSource, TelegramNotifier telegram, TradingDays tradingDays) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.telegram = telegram;
        this.tradingDays = tradingDays;
    }

    List<Row> rows() {
        return jdbc.query("select a.id, a.tenant_id, t.name, a.display_name, a.broker_type, a.trade_mode, "
                + "a.connection_status, (select max(s.expires_at) from acct.broker_session s where s.broker_account_id = a.id "
                + "and s.status = 'ACTIVE' and s.expires_at > now()) until, (select count(*) from acct.strategy_subscription ss "
                + "where ss.broker_account_id = a.id and ss.enabled) strategies from acct.broker_account a "
                + "join acct.tenant t on t.id = a.tenant_id where a.enabled and a.broker_type <> 'PAPER' "
                + "and a.trade_mode in ('DRY_RUN', 'LIVE') order by t.name, a.display_name", (rs, i) -> {
                    Timestamp until = rs.getTimestamp("until");
                    return new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getString(7), until == null ? null : until.toInstant(),
                            rs.getInt("strategies"));
                });
    }

    /** The board as a message: who is ready, who is out for today. Null when there is no broker account. */
    static String message(List<Row> rows, ZonedDateTime now) {
        if (rows.isEmpty()) {
            return null;
        }
        List<String> ready = new ArrayList<>(), out = new ArrayList<>();
        for (Row r : rows) {
            String name = r.account() + " (" + r.tenant() + ", " + r.broker() + " " + r.mode() + ", " + r.strategies()
                    + (r.strategies() == 1 ? " strategy" : " strategies");
            if (r.ready()) {
                ready.add(name + ", session until " + WHEN.format(r.sessionUntil().atZone(IST)) + ")");
            } else {
                out.add(name + ", " + (r.sessionUntil() == null ? "no login today" : "connection " + r.connection()) + ")");
            }
        }
        StringBuilder text = new StringBuilder("Login board " + WHEN.format(now) + " IST: " + ready.size() + " of "
                + rows.size() + " broker accounts ready.");
        if (!out.isEmpty()) {
            text.append("\nOUT FOR TODAY unless logged in now (entries refused):");
            out.forEach(o -> text.append("\n- ").append(o));
        }
        if (!ready.isEmpty()) {
            text.append("\nReady:");
            ready.forEach(r -> text.append("\n- ").append(r));
        }
        return text.toString();
    }

    /** 09:10 IST on trading days (the 08:30 links went out; the open is at 09:15). */
    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    void beforeTheOpen() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        if (!tradingDays.isTradingDay(now.toLocalDate())) {
            return;
        }
        String text = message(rows(), now);
        if (text != null) {
            telegram.send(text);
        }
    }

    boolean tradingDay(LocalDate day) {
        return tradingDays.isTradingDay(day);
    }
}
