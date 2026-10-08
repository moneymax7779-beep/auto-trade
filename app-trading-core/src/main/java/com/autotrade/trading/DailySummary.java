package com.autotrade.trading;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.time.MarketTime;
import com.autotrade.risk.RiskLimits;

/**
 * The day's PAPER profit and loss as one Telegram message, sent after the live session (user, 8 Oct 2026: "send the
 * summary of the Daily profit/loss to the Telegram admin id and group id ... through the application after the
 * session"): the day's net and costs, the account equity before and after, the pace the ₹10 crore goal needs, each
 * strategy's result and every trade.
 */
final class DailySummary {

    /** The goal's required pace: ₹5L to ₹10 Cr by 31 Dec 2026 is about 10 % per session, compounded (user's goal). */
    static final double GOAL_PER_SESSION = 0.10;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.ENGLISH);

    record Trade(String time, String strategy, String symbol, long quantity, double entry, double exit, double net, String reason) {
    }

    record StrategyLine(String strategy, int trades, int wins, double net) {
    }

    private final JdbcTemplate jdbc;
    private final String riskFile;

    DailySummary(DataSource target, String riskFile) {
        this.jdbc = new JdbcTemplate(target);
        this.riskFile = riskFile;
    }

    /** The message for {@code day}'s live sessions on {@code account}; null when there was no finished live session. */
    String build(String account, LocalDate day) {
        List<Map<String, Object>> sessions = jdbc.queryForList("select id, (summary->>'costs')::numeric costs from trade.session "
                + "where account = ? and mode = 'PAPER_LIVE' and session_date = ? and status in ('DONE', 'STOPPED') order by id",
                account, day);
        if (sessions.isEmpty()) {
            return null;
        }
        List<Long> ids = sessions.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
        double costs = sessions.stream().mapToDouble(r -> r.get("costs") == null ? 0 : ((Number) r.get("costs")).doubleValue()).sum();
        String in = String.join(",", ids.stream().map(String::valueOf).toList());
        List<Trade> trades = jdbc.query("select opened_at, strategy_id, symbol, quantity, average_cost, average_exit, net, exit_reason "
                + "from trade.position where session_id in (" + in + ") and average_cost is not null order by opened_at, id",
                (rs, i) -> new Trade(rs.getTimestamp(1).toInstant().atZone(MarketTime.IST).toLocalTime().toString().substring(0, 5),
                        rs.getString(2), rs.getString(3), rs.getLong(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7),
                        rs.getString(8)));
        Integer unfilled = jdbc.queryForObject("select count(*) from trade.position where session_id in (" + in + ") "
                + "and exit_reason = 'ENTRY_NOT_FILLED'", Integer.class);
        Integer refused = jdbc.queryForObject("select count(*) from trade.rejection where session_id in (" + in + ")", Integer.class);

        RiskLimits limits = RiskLimits.from(ThresholdConfig.load(Path.of(riskFile)));
        LocalDate from = limits.equityFrom() == null ? LocalDate.of(1970, 1, 1) : limits.equityFrom();
        Double before = jdbc.queryForObject("select coalesce(sum((summary->>'net')::numeric), 0) from trade.session where account = ? "
                + "and mode = 'PAPER_LIVE' and status in ('DONE', 'STOPPED') and summary ?? 'net' and session_date >= ? and session_date < ?",
                Double.class, account, from, day);
        Integer sessionsSince = jdbc.queryForObject("select count(distinct session_date) from trade.session where account = ? "
                + "and mode = 'PAPER_LIVE' and status in ('DONE', 'STOPPED') and session_date >= ? and session_date <= ?",
                Integer.class, account, from, day);
        double start = limits.startingCapital();
        double equityBefore = start + (before == null ? 0 : before);
        double dayNet = trades.stream().mapToDouble(Trade::net).sum();
        double equityAfter = equityBefore + dayNet;
        double pace = start * Math.pow(1 + GOAL_PER_SESSION, sessionsSince == null ? 0 : sessionsSince);
        return text(day, dayNet, costs, equityBefore, equityAfter, pace, sessionsSince == null ? 0 : sessionsSince,
                limits.equityMode(), trades, unfilled == null ? 0 : unfilled, refused == null ? 0 : refused);
    }

    /** The message text (pure: tested without a database). */
    static String text(LocalDate day, double dayNet, double costs, double equityBefore, double equityAfter, double pace, int sessions,
                       boolean equityMode, List<Trade> trades, int unfilled, int refused) {
        StringBuilder m = new StringBuilder();
        m.append(dayNet >= 0 ? "🟢" : "🔴").append(" Daily P&L · auto-trade PAPER · ").append(day.format(DAY)).append('\n');
        long wins = trades.stream().filter(t -> t.net() > 0).count();
        m.append("Day: ").append(signedRupees(dayNet));
        if (equityBefore > 0) {
            m.append(" (").append(signed(100 * dayNet / equityBefore, 1)).append("%)");
        }
        m.append(" · ").append(trades.size()).append(trades.size() == 1 ? " trade" : " trades").append(", ").append(wins)
                .append(wins == 1 ? " win" : " wins").append(" · costs ₹").append(SurgeAlertRules.fmt(costs, 0)).append('\n');
        if (equityMode) {
            m.append("Equity: ₹").append(SurgeAlertRules.fmt(equityBefore, 0)).append(" → ₹").append(SurgeAlertRules.fmt(equityAfter, 0))
                    .append('\n');
            double gap = equityAfter - pace;
            m.append("Goal pace (10%/session, session ").append(sessions).append("): ₹").append(SurgeAlertRules.fmt(pace, 0))
                    .append(" · ").append(gap >= 0 ? "ahead " : "behind ").append(signedRupees(gap)).append('\n');
        }
        if (trades.isEmpty()) {
            m.append("No trades today.");
        } else {
            m.append("\nBy strategy:\n");
            for (StrategyLine s : byStrategy(trades)) {
                m.append("• ").append(s.strategy()).append(": ").append(s.trades()).append(s.trades() == 1 ? " trade, " : " trades, ")
                        .append(s.wins()).append(s.wins() == 1 ? " win, " : " wins, ").append(signedRupees(s.net())).append('\n');
            }
            m.append("\nTrades:\n");
            for (Trade t : trades) {
                m.append(t.time()).append(' ').append(t.strategy()).append(" · ").append(t.symbol()).append(" · ").append(t.quantity())
                        .append(" @ ₹").append(SurgeAlertRules.fmt(t.entry(), 2)).append(" → ₹").append(SurgeAlertRules.fmt(t.exit(), 2))
                        .append(" · ").append(signedRupees(t.net())).append(t.reason() == null ? "" : " (" + t.reason() + ")").append('\n');
            }
        }
        if (unfilled > 0 || refused > 0) {
            m.append("\nNot filled: ").append(unfilled).append(" · refused by risk/capital: ").append(refused);
        }
        String out = m.toString().strip();
        return out.length() > 4000 ? out.substring(0, 3990) + "\n…" : out;     // Telegram's limit is 4096 characters
    }

    static List<StrategyLine> byStrategy(List<Trade> trades) {
        Map<String, double[]> acc = new java.util.LinkedHashMap<>();
        for (Trade t : trades) {
            double[] a = acc.computeIfAbsent(t.strategy(), k -> new double[3]);
            a[0]++;
            a[1] += t.net() > 0 ? 1 : 0;
            a[2] += t.net();
        }
        List<StrategyLine> out = new ArrayList<>();
        acc.forEach((k, a) -> out.add(new StrategyLine(k, (int) a[0], (int) a[1], a[2])));
        out.sort((x, y) -> Double.compare(y.net(), x.net()));
        return out;
    }

    private static String signedRupees(double v) {
        return (v > 0 ? "+" : v < 0 ? "−" : "") + "₹" + SurgeAlertRules.fmt(Math.abs(v), 0);
    }

    private static String signed(double v, int digits) {
        return (v > 0 ? "+" : v < 0 ? "−" : "") + String.format(Locale.ROOT, "%." + digits + "f", Math.abs(v));
    }
}
