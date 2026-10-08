package com.autotrade.trading;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.core.time.MarketTime;

/**
 * The day's PAPER profit and loss as one Telegram message, sent after the live session (user, 8 Oct 2026: "send the
 * summary of the Daily profit/loss to the Telegram admin id and group id ... through the application after the
 * session"; then "just say this is profit/loss followed by the trades"): the day's net after charges and every trade.
 */
final class DailySummary {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.ENGLISH);

    record Trade(String time, String closed, String strategy, String symbol, long quantity, double entry, double exit, double net,
                 String reason) {
    }

    private final JdbcTemplate jdbc;

    DailySummary(DataSource target) {
        this.jdbc = new JdbcTemplate(target);
    }

    /** The message for {@code day}'s live sessions on {@code account}; null when there was no finished live session. */
    String build(String account, LocalDate day) {
        List<Map<String, Object>> sessions = jdbc.queryForList("select id from trade.session "
                + "where account = ? and mode = 'PAPER_LIVE' and session_date = ? and status in ('DONE', 'STOPPED') order by id",
                account, day);
        if (sessions.isEmpty()) {
            return null;
        }
        List<Long> ids = sessions.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
        String in = String.join(",", ids.stream().map(String::valueOf).toList());
        List<Trade> trades = jdbc.query("select opened_at, closed_at, strategy_id, symbol, quantity, average_cost, average_exit, net, "
                + "exit_reason from trade.position where session_id in (" + in + ") and average_cost is not null order by opened_at, id",
                (rs, i) -> new Trade(hhmm(rs.getTimestamp(1)), hhmm(rs.getTimestamp(2)), rs.getString(3), rs.getString(4), rs.getLong(5),
                        rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getString(9)));
        double dayNet = trades.stream().mapToDouble(Trade::net).sum();
        return text(day, dayNet, trades);
    }

    /**
     * The message text (pure: tested without a database). User, 8 Oct: "summary should be readable no need to mention
     * about the strategies and goals, just say this is profit/loss followed by the trades".
     */
    static String text(LocalDate day, double dayNet, List<Trade> trades) {
        StringBuilder m = new StringBuilder();
        m.append(dayNet >= 0 ? "🟢" : "🔴").append(" Profit/Loss · ").append(day.format(DAY)).append(" (PAPER)\n");
        long wins = trades.stream().filter(t -> t.net() > 0).count();
        m.append(dayNet >= 0 ? "Profit " : "Loss ").append(signedRupees(dayNet));
        if (!trades.isEmpty()) {
            m.append(" · ").append(trades.size()).append(trades.size() == 1 ? " trade" : " trades").append(", ").append(wins)
                    .append(wins == 1 ? " in profit" : " in profit");
        }
        m.append(" (after charges)\n");
        if (trades.isEmpty()) {
            m.append("\nNo trades today.");
            return m.toString().strip();
        }
        m.append("\nTrades:\n");
        int n = 0;
        for (Trade t : trades) {
            n++;
            m.append(t.net() > 0 ? "✅ " : "❌ ").append(n).append(". ").append(t.time())
                    .append(t.closed() == null ? "" : "–" + t.closed()).append("  ").append(readable(t.symbol())).append('\n')
                    .append("    Buy ").append(t.quantity()).append(" @ ₹").append(SurgeAlertRules.fmt(t.entry(), 2))
                    .append(" → Sell @ ₹").append(SurgeAlertRules.fmt(t.exit(), 2)).append("  ").append(signedRupees(t.net())).append('\n');
        }
        String out = m.toString().strip();
        return out.length() > 4000 ? out.substring(0, 3990) + "\n…" : out;     // Telegram's limit is 4096 characters
    }

    /** "SENSEX 72400 PE 08 OCT 26" → "SENSEX 72400 PE (08 Oct)". */
    static String readable(String symbol) {
        String[] p = symbol.split(" ");
        if (p.length >= 6) {
            String month = p[4].charAt(0) + p[4].substring(1).toLowerCase(Locale.ROOT);
            return p[0] + " " + p[1] + " " + p[2] + " (" + p[3] + " " + month + ")";
        }
        return symbol;
    }

    private static String hhmm(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant().atZone(MarketTime.IST).toLocalTime().toString().substring(0, 5);
    }

    private static String signedRupees(double v) {
        return (v > 0 ? "+" : v < 0 ? "−" : "") + "₹" + SurgeAlertRules.fmt(Math.abs(v), 0);
    }

}
