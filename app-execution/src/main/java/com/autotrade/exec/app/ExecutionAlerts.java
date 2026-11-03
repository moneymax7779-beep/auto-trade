package com.autotrade.exec.app;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Telegram alerts for the accounts that trade through a real broker session (DRY_RUN and LIVE), code review 3 Nov 2026:
 * the worker engaged kill switches (an exit the chase could not fill, a stop that could not be placed, an order whose
 * outcome is unknown, a reconciliation difference), lost broker sessions, instrument mismatches, flattens and the
 * end-of-day square-off (and, since this review, exit retries) only into exec.audit_log and the log, so nobody was told. Every {@code alert-ms} the audit rows
 * written since the last look are sent (an "already engaged" repeat once per reason and day), and for LIVE accounts each
 * filled or rejected order. Read-only on the database; a failed send is logged by the notifier, never thrown. Rows
 * before the worker started are not sent.
 */
@Component
class ExecutionAlerts {

    private static final Logger log = LoggerFactory.getLogger(ExecutionAlerts.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<String> ACTIONS = List.of("KILL_ACCOUNT", "KILL_ACCOUNT_ALREADY_ENGAGED", "BROKER_SESSION_LOST",
            "INSTRUMENT_MAPPING_MISMATCH", "EOD_SQUARE_OFF", "FLATTEN", "END_OF_DAY", "EXIT_RETRY");

    private final JdbcTemplate jdbc;
    private final TelegramNotifier telegram;
    private long lastAudit = -1;
    private long lastOrderCheck = -1;
    private final Set<String> sent = new HashSet<>();
    private LocalDate sentDay;

    ExecutionAlerts(DataSource dataSource, TelegramNotifier telegram) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.telegram = telegram;
    }

    @Scheduled(fixedDelayString = "${autotrade.execution.alert-ms:10000}", initialDelay = 10000)
    void check() {
        try {
            LocalDate today = LocalDate.now(IST);
            if (!today.equals(sentDay)) {
                sent.clear();
                sentDay = today;
            }
            for (String text : collect()) {
                telegram.send(text);
            }
        } catch (RuntimeException e) {
            log.warn("execution alerts: {}", e.getMessage());
        }
    }

    /** The messages due now (and the read positions moved past them). */
    List<String> collect() {
        if (lastAudit < 0) {                                       // first look: start from now, never replay history
            lastAudit = jdbc.queryForObject("select coalesce(max(id), 0) from exec.audit_log", Long.class);
            lastOrderCheck = jdbc.queryForObject("select coalesce(max(id), 0) from exec.order_execution", Long.class);
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select l.id, l.action, l.detail::text detail, a.display_name, "
                + "a.trade_mode from exec.audit_log l join acct.broker_account a on a.id = l.broker_account_id "
                + "where l.id > ? and l.action = any(?) and a.trade_mode in ('LIVE', 'DRY_RUN') order by l.id", lastAudit,
                ACTIONS.toArray(new String[0]))) {
            lastAudit = Math.max(lastAudit, ((Number) r.get("id")).longValue());
            String action = (String) r.get("action");
            String detail = (String) r.get("detail");
            if (action.equals("END_OF_DAY") && detail.matches(".*\"difference\"\\s*:\\s*\"(0\\.0|0|-0\\.0)\".*")) {
                continue;                                          // reconciled to the rupee: nothing to say
            }
            String text = r.get("display_name") + " (" + r.get("trade_mode") + "): " + action + " " + detail;
            if (action.equals("KILL_ACCOUNT_ALREADY_ENGAGED") && !sent.add(text)) {
                continue;
            }
            out.add(text + (action.startsWith("KILL_ACCOUNT") ? "\nNew entries stop; exits continue. Check the Accounts "
                    + "page and release the switch there once it is understood." : ""));
        }
        // today's LIVE orders placed since the worker started that filled or were rejected: one message each
        for (Map<String, Object> r : jdbc.queryForList("select e.id, e.role, e.order_side, e.quantity_filled, "
                + "e.average_fill_price, e.status, e.raw_error, e.client_tag, a.display_name, coalesce(p.symbol, '') symbol "
                + "from exec.order_execution e join acct.broker_account a on a.id = e.broker_account_id "
                + "left join exec.position p on p.entry_trade_intent_id = e.trade_intent_id and p.broker_account_id = "
                + "e.broker_account_id where e.id > ? and a.trade_mode = 'LIVE' and e.status in ('FILLED', 'REJECTED') "
                + "and (e.updated_at at time zone 'Asia/Kolkata')::date = (now() at time zone 'Asia/Kolkata')::date "
                + "order by e.id", lastOrderCheck)) {
            if (!sent.add("order:" + r.get("id") + ":" + r.get("status"))) {
                continue;
            }
            out.add(r.get("display_name") + " (LIVE): " + r.get("role") + " " + r.get("order_side") + " " + r.get("symbol")
                    + " " + r.get("status") + (r.get("status").equals("FILLED") ? " " + r.get("quantity_filled") + " at "
                    + r.get("average_fill_price") : ": " + r.get("raw_error")) + " (" + r.get("client_tag") + ")");
        }
        return out;
    }
}
