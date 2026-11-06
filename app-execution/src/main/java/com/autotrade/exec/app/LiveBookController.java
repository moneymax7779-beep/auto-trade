package com.autotrade.exec.app;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;

/**
 * The Overview page's "Auto-trade positions" panel (6 Nov 2026, user: "I just want the application traded positions shown in
 * a better UX"): one LIVE account's own positions only, never the broker's other holdings. Open positions with their latest
 * captured quote (md.option_tick, bid-based P&amp;L), best bid since entry and the resting stop; closed ones of the last
 * {@code days} with the exit price implied by the realised P&amp;L. Only positions whose entry was a LIVE intent count, so a
 * DRY_RUN rehearsal on the same account is not mixed into real money. Read-only.
 */
@RestController
class LiveBookController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** A quote older than this is not shown as current (the capture may have stopped). */
    static final int QUOTE_MAX_AGE_MIN = 10;
    /** A stop protects the position only in these states (CREATED is not sent yet; CANCEL_PENDING / UNKNOWN may not hold). */
    private static final List<String> STOP_WORKING = List.of("SUBMITTED", "ACKNOWLEDGED", "OPEN", "PARTIALLY_FILLED");

    /** The best bid since entry per open position, extended by each poll from where the last one stopped. */
    private record Best(double max, Timestamp upTo) {
    }

    private final java.util.Map<Long, Best> best = new java.util.concurrent.ConcurrentHashMap<>();

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;

    LiveBookController(Access access, DataSource dataSource) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @GetMapping("/api/execution/accounts/{id}/live-book")
    Map<String, Object> liveBook(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                 @PathVariable long id, @RequestParam(defaultValue = "7") int days) {
        Access.User user = access.user(subject);
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
        int window = Math.max(1, Math.min(days, 366));
        List<Map<String, Object>> rows = jdbc.queryForList("select p.id, p.strategy_id, p.underlying, p.option_side, p.symbol, "
                + "p.instrument_key, p.instrument_token, p.entry_trade_intent_id, p.quantity_open, p.average_entry, p.opened_at, p.closed_at, p.exit_reason, p.realised_gross, "
                + "p.charges, p.status, "
                + "(select coalesce(sum(o.quantity_filled), 0) from exec.order_execution o join exec.trade_intent x on "
                + "x.id = o.trade_intent_id where o.role = 'ENTRY' and (x.id = p.entry_trade_intent_id or (x.action = 'ADD' "
                + "and x.broker_account_id = p.broker_account_id and x.strategy_id = p.strategy_id and x.symbol = p.symbol "
                + "and x.created_at >= p.opened_at and x.created_at <= coalesce(p.closed_at, now())))) bought, "
                + "(select d.lot_size from exec.trade_batch b join exec.signal_decision d on d.id = b.signal_decision_id "
                + "where b.id = i.trade_batch_id) lot_size, "
                + "(select o.trigger_price from exec.order_execution o where o.broker_account_id = p.broker_account_id "
                + "and o.trade_intent_id = p.entry_trade_intent_id and o.role = 'STOP' and o.trigger_price > 0 "
                + "order by o.id desc limit 1) stop_trigger, "
                + "(select o.status from exec.order_execution o where o.broker_account_id = p.broker_account_id "
                + "and o.trade_intent_id = p.entry_trade_intent_id and o.role = 'STOP' and o.trigger_price > 0 "
                + "order by o.id desc limit 1) stop_status "
                + "from exec.position p join exec.trade_intent i on i.id = p.entry_trade_intent_id "
                + "where p.broker_account_id = ? and i.execution_mode = 'LIVE' "
                + "and (p.status <> 'CLOSED' or p.closed_at > now() - make_interval(days => ?)) order by p.opened_at desc",
                id, window);

        // the scopes that stop this account's entries (KillSwitches): every LIVE account, this account, a strategy of its tenant
        List<Map<String, Object>> killRows = jdbc.queryForList("select id, scope, tenant_id, engaged_at, case scope when "
                + "'GLOBAL_LIVE' then 'all LIVE accounts: ' || reason when 'STRATEGY' then strategy_id || ': ' || reason "
                + "else reason end text from exec.kill_switch where released_at is null and ((scope = 'GLOBAL_LIVE' and ?) or "
                + "(scope = 'ACCOUNT' and broker_account_id = ?) or (scope = 'STRATEGY' and tenant_id = ?)) order by engaged_at",
                a.tradeMode() == com.autotrade.account.TradeMode.LIVE, id, a.tenantId());

        LocalDate today = LocalDate.now(IST);
        List<Map<String, Object>> open = new ArrayList<>();
        List<Map<String, Object>> closed = new ArrayList<>();
        double openPnl = 0, atRisk = 0, realisedToday = 0;
        for (Map<String, Object> r : rows) {
            double entry = num(r.get("average_entry"));
            long bought = ((Number) r.get("bought")).longValue();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", r.get("id"));
            out.put("strategy", r.get("strategy_id"));
            out.put("underlying", r.get("underlying"));
            out.put("side", r.get("option_side"));
            out.put("symbol", r.get("symbol"));
            out.put("lotSize", r.get("lot_size"));
            out.put("entry", entry);
            out.put("opened", ist((Timestamp) r.get("opened_at")));
            if (!"CLOSED".equals(r.get("status"))) {
                long qty = ((Number) r.get("quantity_open")).longValue();
                out.put("quantity", qty);
                out.put("status", r.get("status"));
                Map<String, Object> q = quote(((Number) r.get("id")).longValue(), ((Number) r.get("instrument_token")).longValue(),
                        (Timestamp) r.get("opened_at"));
                out.putAll(q);
                Double stop = r.get("stop_trigger") == null ? null : num(r.get("stop_trigger"));
                boolean working = stop != null && STOP_WORKING.contains((String) r.get("stop_status"));
                out.put("stop", stop);
                out.put("stopStatus", r.get("stop_status"));
                out.put("stopWorking", working);
                Double bid = (Double) q.get("bid");
                if (bid != null) {
                    double pnl = (bid - entry) * qty;
                    out.put("pnl", round(pnl));
                    out.put("pnlPct", entry > 0 ? round(100 * (bid / entry - 1)) : null);
                    openPnl += pnl;
                    if (working) {
                        double risk = Math.max(0, (bid - stop) * qty);
                        out.put("atRisk", round(risk));
                        atRisk += risk;
                    }
                }
                Map<String, Object> hint = handSaleHint(r, killRows);
                if (hint != null) {
                    out.put("notAtBroker", hint);
                }
                open.add(out);
            } else {
                best.remove(((Number) r.get("id")).longValue());                // closed: its running best is done
                double gross = num(r.get("realised_gross")), charges = num(r.get("charges"));
                out.put("quantity", bought);
                out.put("exit", bought > 0 ? round(entry + gross / bought) : null);
                out.put("exitReason", r.get("exit_reason"));
                Timestamp closedAt = (Timestamp) r.get("closed_at");
                // a CLOSED row without its time (a hand fix) shows at its entry time instead of failing the whole book
                Timestamp shownAt = closedAt != null ? closedAt : (Timestamp) r.get("opened_at");
                out.put("closed", ist(shownAt));
                out.put("gross", round(gross));
                out.put("charges", round(charges));
                out.put("net", round(gross - charges));
                out.put("pct", bought > 0 && entry > 0 ? round(100 * gross / (bought * entry)) : null);
                if (closedAt != null && closedAt.toInstant().atZone(IST).toLocalDate().equals(today)) {
                    realisedToday += gross - charges;
                }
                closed.add(out);
            }
        }
        Map<String, Object> since = jdbc.queryForMap("select count(*) trades, coalesce(sum(p.realised_gross - coalesce(p.charges, 0)), 0) "
                + "net, min(p.opened_at) first from exec.position p join exec.trade_intent i on i.id = p.entry_trade_intent_id "
                + "where p.broker_account_id = ? and i.execution_mode = 'LIVE' and p.status = 'CLOSED'", id);
        List<Map<String, Object>> kills = new ArrayList<>();
        for (Map<String, Object> k : killRows) {
            Long tenant = k.get("tenant_id") == null ? null : ((Number) k.get("tenant_id")).longValue();
            kills.add(Map.of("id", k.get("id"), "text", k.get("text"),
                    "releasable", tenant == null ? user.admin() : user.mayAct(tenant)));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("openPnl", round(openPnl));
        summary.put("atRisk", round(atRisk));
        summary.put("realisedToday", round(realisedToday));
        summary.put("sinceLiveNet", round(num(since.get("net"))));
        summary.put("sinceLiveTrades", ((Number) since.get("trades")).intValue());
        summary.put("sinceLiveFrom", since.get("first") == null ? null
                : ((Timestamp) since.get("first")).toInstant().atZone(IST).toLocalDate().toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", a.displayName());
        out.put("mode", a.tradeMode().name());
        out.put("canAct", user.mayAct(a.tenantId()));
        out.put("killSwitches", kills);
        out.put("summary", summary);
        out.put("open", open);
        out.put("closed", closed);
        return out;
    }

    /**
     * An open position the reconciler found the broker no longer holds ("KEY: ours N, broker 0" in an engaged account
     * kill switch): sold outside the app. The window to price the hand sale from: since its stop was cancelled (else a
     * minute before the kill) until the kill, with the captured bids in it (the trader enters the real fill price).
     */
    private Map<String, Object> handSaleHint(Map<String, Object> r, List<Map<String, Object>> killRows) {
        String key = (String) r.get("instrument_key");
        Timestamp kill = null;
        for (Map<String, Object> k : killRows) {
            if ("ACCOUNT".equals(k.get("scope")) && key != null && String.valueOf(k.get("text")).matches(
                    "(?s).*" + java.util.regex.Pattern.quote(key) + ": ours \\d+, broker 0\\b.*")) {
                kill = (Timestamp) k.get("engaged_at");
            }
        }
        if (kill == null) {
            return null;
        }
        Timestamp cancelled = jdbc.queryForList("select max(updated_at) from exec.order_execution where trade_intent_id = ? "
                + "and role = 'STOP' and status = 'CANCELLED' and updated_at <= ?", Timestamp.class,
                r.get("entry_trade_intent_id"), kill).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        Timestamp from = cancelled != null && cancelled.after(new Timestamp(kill.getTime() - 15 * 60_000L)) ? cancelled
                : new Timestamp(kill.getTime() - 60_000L);
        Map<String, Object> bids = jdbc.queryForMap("select min(bid_px[1]) low, max(bid_px[1]) high, (array_agg(bid_px[1] "
                + "order by recv_ts))[1] first from md.option_tick where instrument_token = ? and recv_ts between ? and ? "
                + "and bid_px[1] > 0", ((Number) r.get("instrument_token")).longValue(), from, kill);
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("from", ist(from));
        hint.put("to", ist(kill));
        hint.put("bidLow", bids.get("low") == null ? null : num(bids.get("low")));
        hint.put("bidHigh", bids.get("high") == null ? null : num(bids.get("high")));
        hint.put("bidFirst", bids.get("first") == null ? null : num(bids.get("first")));
        return hint;
    }

    /** The newest captured quote of the contract (if recent) and the best bid since the entry (extended incrementally). */
    private Map<String, Object> quote(long position, long token, Timestamp since) {
        Map<String, Object> q = new LinkedHashMap<>();
        List<Map<String, Object>> last = jdbc.queryForList("select bid_px[1] bid, ask_px[1] ask, ltp, recv_ts from md.option_tick "
                + "where instrument_token = ? and recv_ts > now() - make_interval(mins => ?) and bid_px[1] > 0 "
                + "order by recv_ts desc limit 1", token, QUOTE_MAX_AGE_MIN);
        if (!last.isEmpty()) {
            Map<String, Object> l = last.getFirst();
            q.put("bid", num(l.get("bid")));
            q.put("ask", num(l.get("ask")));
            q.put("ltp", l.get("ltp") == null ? null : num(l.get("ltp")));
            q.put("quoteAt", ist((Timestamp) l.get("recv_ts")));
        } else {
            q.put("bid", null);
        }
        Best kept = best.get(position);
        Timestamp from = kept == null ? since : kept.upTo();
        Map<String, Object> more = jdbc.queryForMap("select max(bid_px[1]) m, max(recv_ts) t from md.option_tick "
                + "where instrument_token = ? and recv_ts > ? and bid_px[1] > 0", token, from);
        double max = Math.max(kept == null ? 0 : kept.max(), more.get("m") == null ? 0 : ((Number) more.get("m")).doubleValue());
        if (more.get("t") != null) {
            best.put(position, new Best(max, (Timestamp) more.get("t")));
        }
        q.put("best", max > 0 ? max : null);
        return q;
    }

    private static String ist(Timestamp t) {
        return t == null ? null : t.toInstant().atZone(IST).toLocalDateTime().toString();
    }

    private static double num(Object v) {
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
