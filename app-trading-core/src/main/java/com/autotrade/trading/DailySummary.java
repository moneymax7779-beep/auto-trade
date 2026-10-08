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

    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_EXPIRY = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);
    private static final DateTimeFormatter EXPIRY = new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("dd MMM yy").toFormatter(Locale.ENGLISH);

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
     * The message text (pure: tested without a database). User, 8 Oct: "just say this is profit/loss followed by the
     * trades", then "make it more readable and simple": the result leads each trade; the expiry is shown only when the
     * option does not expire that day.
     */
    static String text(LocalDate day, double dayNet, List<Trade> trades) {
        StringBuilder m = new StringBuilder();
        m.append(dayNet >= 0 ? "🟢 Profit " : "🔴 Loss ").append(signedRupees(dayNet)).append(" · ")
                .append(day.format(SHORT_DAY)).append(" (PAPER)\n");
        if (trades.isEmpty()) {
            return m.append("No trades today.").toString();
        }
        long won = trades.stream().filter(t -> t.net() > 0).count();
        m.append(trades.size()).append(trades.size() == 1 ? " trade: " : " trades: ").append(won).append(" won, ")
                .append(trades.size() - won).append(" lost · after charges\n");
        for (Trade t : trades) {
            m.append('\n').append(t.net() > 0 ? "✅ " : "❌ ").append(signedRupees(t.net())).append("  ").append(contract(t.symbol(), day))
                    .append('\n').append("     ").append(t.time()).append(t.closed() == null ? "" : "–" + t.closed())
                    .append(" · ").append(SurgeAlertRules.fmt(t.quantity(), 0)).append(" qty · ")
                    .append(SurgeAlertRules.fmt(t.entry(), 2)).append(" → ").append(SurgeAlertRules.fmt(t.exit(), 2));
        }
        String out = m.toString();
        return out.length() > 4000 ? out.substring(0, 3990) + "\n…" : out;     // Telegram's limit is 4096 characters
    }

    /** "SENSEX 72400 PE 08 OCT 26" on 8 Oct → "SENSEX 72400 PE"; "NIFTY 22450 PE 13 OCT 26" → "NIFTY 22450 PE · exp 13 Oct". */
    static String contract(String symbol, LocalDate day) {
        String[] p = symbol.split(" ");
        if (p.length < 6) {
            return symbol;
        }
        String name = p[0] + " " + p[1] + " " + p[2];
        try {
            LocalDate expiry = LocalDate.parse(p[3] + " " + p[4] + " " + p[5], EXPIRY);
            return expiry.equals(day) ? name : name + " · exp " + expiry.format(SHORT_EXPIRY);
        } catch (java.time.format.DateTimeParseException e) {
            return symbol;
        }
    }

    private static String hhmm(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant().atZone(MarketTime.IST).toLocalTime().toString().substring(0, 5);
    }

    private static String signedRupees(double v) {
        return (v > 0 ? "+" : v < 0 ? "−" : "") + "₹" + SurgeAlertRules.fmt(Math.abs(v), 0);
    }

}
