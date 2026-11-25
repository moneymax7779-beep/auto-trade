package com.autotrade.exec.app;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Telegram alerts per position (user 17 Nov 2026: "the traders/owner should receive on Telegram when the BUY/SELL and
 * profit/loss 10% now, 20% now, 30% now, 50% now, 100% now book partial or full"; answers: LIVE accounts and the members'
 * paper twins, profit and loss levels, Book buttons with the confirm card):
 * <ul>
 *   <li>BUY when a position appears, SELL when part of it is sold, SOLD when it closes (reason and net);</li>
 *   <li>the premium P&amp;L from the entry (captured bid of the last minute) crossing {@link #PROFIT_LEVELS} or
 *       {@link #LOSS_LEVELS}: once per position and level, the highest one crossed, with "Book half" (2 lots or more) and
 *       "Book all" buttons that open Telegram's own Exit confirm card ({@code b:POSITION:LOTS}, 0 = all).</li>
 * </ul>
 * Sent to the workspace's members with a linked chat (buttons for those who may act) and to the admin chat, each chat
 * once. A restart starts from what is open then (nothing replayed, levels already reached are not said again). Only for
 * LIVE entries on broker accounts and PAPER entries on paper twins; the owner's own paper and research accounts never.
 */
@Component
class PositionAlerts {

    private static final Logger log = LoggerFactory.getLogger(PositionAlerts.class);
    static final int[] PROFIT_LEVELS = {10, 20, 30, 50, 100};
    static final int[] LOSS_LEVELS = {-10, -20, -30, -50};
    /** A quote older than this is not used for a level (a stale bid would say a move that is not there). */
    static final int QUOTE_MINUTES = 1;

    /** One message to one chat (buttons null: none). */
    record Message(String chat, String text, List<List<TelegramBot.Button>> buttons) {
    }

    /** What was last seen of a position, and the levels already said. */
    private static final class Seen {
        long open;
        String status;
        int profit;                                   // the highest profit level said (0: none)
        int loss;                                     // the lowest loss level said (0: none)
    }

    private final JdbcTemplate jdbc;
    private final Access access;
    private final ContractQuotes quotes;
    private final TelegramNotifier notifier;
    private final TelegramCommands commands;
    private final Map<Long, Seen> seen = new HashMap<>();
    private boolean seeded;
    /** Mobile phase 5 (merge with the position alerts): each alert to the same people's phones too (nothing while off). */
    private WebPushSender push;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void webPush(WebPushSender push) {
        this.push = push;
    }

    PositionAlerts(DataSource dataSource, Access access, TelegramNotifier notifier, TelegramCommands commands) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.access = access;
        this.quotes = new ContractQuotes(jdbc);
        this.notifier = notifier;
        this.commands = commands;
    }

    @Scheduled(fixedDelayString = "${autotrade.execution.position-alert-ms:15000}", initialDelay = 15000)
    void check() {
        try {
            for (Message m : collect(notifier.adminChat(), commands != null && commands.acceptsButtons())) {
                if (m.buttons() == null || commands == null) {
                    notifier.sendTo(m.chat(), m.text());
                } else {
                    commands.bot().send(m.chat(), m.text(), m.buttons());
                }
            }
        } catch (RuntimeException e) {
            log.warn("position alerts: {}", e.getMessage());
        }
    }

    /** The messages due now; {@code adminChat}: the owner's chat (null: none); {@code buttons}: commands are on. */
    synchronized List<Message> collect(String adminChat, boolean buttons) {
        List<Message> out = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList("select p.id, p.tenant_id, a.display_name, a.broker_type, "
                + "a.trade_mode, p.strategy_id, p.underlying, p.symbol, p.instrument_key, p.instrument_token, p.quantity_open, "
                + "p.average_entry, p.status, p.exit_reason, p.realised_gross, p.charges, i.created_at bought_at, "
                + "(select coalesce(sum(o.quantity_filled), 0) from exec.order_execution o where o.trade_intent_id = i.id "
                + "and o.role = 'ENTRY') bought, d.lot_size from exec.position p "
                + "join acct.broker_account a on a.id = p.broker_account_id "
                + "join exec.trade_intent i on i.id = p.entry_trade_intent_id "
                + "left join exec.trade_batch b on b.id = i.trade_batch_id "
                + "left join exec.signal_decision d on d.id = b.signal_decision_id "
                + "where a.account_role = 'FOLLOWER_TRADE' and ((a.broker_type = 'PAPER' and a.paper_of_account_id is not null "
                + "and i.execution_mode = 'PAPER') or (a.broker_type <> 'PAPER' and i.execution_mode = 'LIVE')) "
                + "and (p.status <> 'CLOSED' or p.closed_at > now() - interval '1 hour') order by p.id");
        Set<Long> present = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            present.add(id);
            long open = ((Number) r.get("quantity_open")).longValue();
            String status = (String) r.get("status");
            Seen s = seen.get(id);
            Double pct = "CLOSED".equals(status) ? null : pct(r);
            if (s == null) {
                s = new Seen();
                s.open = open;
                s.status = status;
                seen.put(id, s);
                Timestamp bought = (Timestamp) r.get("bought_at");
                boolean fresh = bought != null && bought.toInstant().isAfter(java.time.Instant.now().minusSeconds(600));
                if (!seeded || "CLOSED".equals(status) || !fresh) {
                    mark(s, pct);                          // what is there at the start (or old): said as reached
                    continue;
                }
                out.addAll(to(r, adminChat, buy(r), null));
                mark(s, pct);
                continue;
            }
            if (open < s.open && !"CLOSED".equals(status)) {
                out.addAll(to(r, adminChat, sold(r, s.open - open, open), null));
            }
            if ("CLOSED".equals(status) && !"CLOSED".equals(s.status)) {
                out.addAll(to(r, adminChat, closed(r), null));
            }
            s.open = open;
            s.status = status;
            if (pct != null) {
                int profit = 0, loss = 0;
                for (int level : PROFIT_LEVELS) {
                    profit = pct >= level ? level : profit;
                }
                for (int level : LOSS_LEVELS) {
                    loss = pct <= level ? level : loss;
                }
                if (profit > s.profit) {
                    s.profit = profit;
                    out.addAll(to(r, adminChat, level(r, profit, pct), buttons ? book(r) : null));
                }
                if (loss < s.loss) {
                    s.loss = loss;
                    out.addAll(to(r, adminChat, level(r, loss, pct), buttons ? book(r) : null));
                }
            }
        }
        seen.keySet().retainAll(present);
        seeded = true;
        return out;
    }

    /** The premium P&L % from the entry at the captured bid of the last minute, or null. */
    private Double pct(Map<String, Object> r) {
        double entry = ((Number) r.get("average_entry")).doubleValue();
        Map<String, Object> q = quotes.latest((String) r.get("instrument_key"), (String) r.get("underlying"),
                ((Number) r.get("instrument_token")).longValue(), QUOTE_MINUTES);
        if (q == null || entry <= 0) {
            return null;
        }
        return 100 * (((Number) q.get("bid")).doubleValue() / entry - 1);
    }

    private static void mark(Seen s, Double pct) {
        if (pct == null) {
            return;
        }
        for (int level : PROFIT_LEVELS) {
            s.profit = pct >= level ? level : s.profit;
        }
        for (int level : LOSS_LEVELS) {
            s.loss = pct <= level ? level : s.loss;
        }
    }

    // ------------------------------------------------------------------------------------------------ the texts

    private static String head(Map<String, Object> r) {
        String mode = "PAPER".equals(r.get("broker_type")) ? "PAPER" : "LIVE";
        return r.get("display_name") + " (" + mode + ") #" + r.get("id") + " ";
    }

    private static String lots(Map<String, Object> r, long qty) {
        Number lot = (Number) r.get("lot_size");
        return lot == null || lot.intValue() <= 0 ? qty + " qty" : qty / lot.intValue() + " lot(s), " + qty + " qty";
    }

    private static String buy(Map<String, Object> r) {
        long qty = ((Number) r.get("quantity_open")).longValue();
        return head(r) + "BUY " + r.get("symbol") + ": " + lots(r, qty) + " at " + money2(r.get("average_entry")) + " ("
                + r.get("strategy_id") + ")";
    }

    private static String sold(Map<String, Object> r, long sold, long left) {
        return head(r) + "SELL " + r.get("symbol") + ": " + lots(r, sold) + " sold, " + left + " qty still open. Booked so "
                + "far " + rupees(num(r.get("realised_gross")) - num(r.get("charges")));
    }

    private static String closed(Map<String, Object> r) {
        double net = num(r.get("realised_gross")) - num(r.get("charges"));
        long bought = ((Number) r.get("bought")).longValue();
        double entry = num(r.get("average_entry"));
        String pct = bought > 0 && entry > 0 ? String.format(Locale.ROOT, " (%+.1f %%)", 100 * num(r.get("realised_gross"))
                / (bought * entry)) : "";
        return head(r) + "SOLD all " + r.get("symbol") + ": " + reason((String) r.get("exit_reason")) + ", net " + rupees(net)
                + pct;
    }

    private String level(Map<String, Object> r, int level, double pct) {
        long qty = ((Number) r.get("quantity_open")).longValue();
        double entry = num(r.get("average_entry"));
        double bid = entry * (1 + pct / 100);
        return head(r) + r.get("symbol") + (level > 0 ? " +" : " ") + level + " % now (" + String.format(Locale.ROOT,
                "%+.1f %%", pct) + "): bid " + money2(bid) + " vs entry " + money2(entry) + ", " + rupees((bid - entry) * qty)
                + " open on " + lots(r, qty) + ". Book partial or full?";
    }

    /** "Book half" (2 lots or more) and "Book all": each opens the Exit confirm card. */
    private static List<List<TelegramBot.Button>> book(Map<String, Object> r) {
        long id = ((Number) r.get("id")).longValue(), qty = ((Number) r.get("quantity_open")).longValue();
        Number lot = (Number) r.get("lot_size");
        List<TelegramBot.Button> row = new ArrayList<>();
        long lots = lot == null || lot.intValue() <= 0 ? 0 : qty / lot.intValue();
        if (lots >= 2) {
            row.add(new TelegramBot.Button("Book half (" + lots / 2 + " lots)", "b:" + id + ":" + lots / 2));
        }
        row.add(new TelegramBot.Button("Book all", "b:" + id + ":0"));
        return List.of(row);
    }

    /** The message to every member chat of the workspace and the admin chat, each chat once. */
    private List<Message> to(Map<String, Object> r, String adminChat, String text, List<List<TelegramBot.Button>> buttons) {
        long tenant = ((Number) r.get("tenant_id")).longValue();
        if (push != null) {                       // members and admins; the sender sends a text once per phone
            WebPushSender.Message m = WebPushSender.Message.of(text);
            WebPushSender.Message toPositions = new WebPushSender.Message(m.title(), m.body(), "/positions", m.urgent());
            push.toMembers(tenant, toPositions);
            push.toAdmins(toPositions);
        }
        Map<String, Message> byChat = new LinkedHashMap<>();
        for (Map<String, Object> m : jdbc.queryForList("select u.telegram_chat_id chat, u.external_subject subject "
                + "from acct.tenant_membership m join acct.app_user u on u.id = m.app_user_id where m.tenant_id = ? "
                + "and u.status = 'ACTIVE' and u.telegram_chat_id is not null order by u.id", tenant)) {
            boolean acts = false;
            try {
                acts = access.user((String) m.get("subject")).emergency(tenant);
            } catch (RuntimeException e) {
                // no rights read: the message without buttons
            }
            byChat.putIfAbsent((String) m.get("chat"), new Message((String) m.get("chat"), text, acts ? buttons : null));
        }
        if (adminChat != null) {
            byChat.putIfAbsent(adminChat, new Message(adminChat, text, buttons));
        }
        return new ArrayList<>(byChat.values());
    }

    private static String reason(String code) {
        if (code == null) {
            return "closed";
        }
        return switch (code) {
            case "TRADER_EXIT" -> "exited by you";
            case "ADMIN_EXIT" -> "exited by the admin";                // admin exit desk, 20 Nov 2026
            case "MANUAL_TARGET" -> "sold at the first target";        // the manual / admin first target (24-25 Nov 2026)
            case "MANUAL" -> "sold by hand";
            case "EOD_SQUARE_OFF" -> "square-off";
            case "BROKER_STOP", "STOP" -> "stop";
            case "TARGET" -> "target";
            case "FLATTEN" -> "flattened";
            default -> code.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static double num(Object v) {
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    private static String money2(Object v) {
        return String.format(Locale.ROOT, "%.2f", num(v));
    }

    private static String rupees(double v) {
        long r = Math.round(v);
        return (r < 0 ? "-" : r > 0 ? "+" : "") + "Rs " + String.format(Locale.ROOT, "%,d", Math.abs(r));
    }
}
