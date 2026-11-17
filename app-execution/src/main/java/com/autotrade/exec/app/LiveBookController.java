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
import com.autotrade.sim.CostModel;
import com.autotrade.sim.Side;

/**
 * The Overview page's "Auto-trade positions" panel (6 Nov 2026, user: "I just want the application traded positions shown in
 * a better UX"): one LIVE account's own positions only, never the broker's other holdings. Open positions with their latest
 * captured quote (md.option_tick, bid-based P&amp;L), best bid since entry and the resting stop; closed ones of the last
 * {@code days} with the exit price implied by the realised P&amp;L. Only positions whose entry was a LIVE intent count, so a
 * DRY_RUN rehearsal on the same account is not mixed into real money. Read-only.
 * <p>
 * 17 Nov 2026 (workspace positions, phase 1): a PAPER account (a broker account's paper twin) has the same book of its
 * PAPER entries ({@code bookMode}), so a member sees and exits paper positions on the Today page. Each open position also
 * says when the strategy no longer manages it ({@code strategyExitedAt}: the strategy exited that contract at least
 * {@link #EXIT_SETTLE} ago and this position is still open) and when it was a late join ({@code lateJoin}: bought more
 * than {@link #LATE_JOIN} after its signal, at the signal's price); the book carries the account's square-off time.
 */
@RestController
class LiveBookController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** A quote older than this is not shown as current (the capture may have stopped). */
    static final int QUOTE_MAX_AGE_MIN = 10;
    /** A stop protects the position only in these states (CREATED is not sent yet; CANCEL_PENDING / UNKNOWN may not hold). */
    private static final List<String> STOP_WORKING = List.of("SUBMITTED", "ACKNOWLEDGED", "OPEN", "PARTIALLY_FILLED");
    /** A strategy EXIT this old with the position still open is not a working exit (they fill or retry within a minute). */
    static final int EXIT_SETTLE_SEC = 120;
    private static final java.time.Duration EXIT_SETTLE = java.time.Duration.ofSeconds(EXIT_SETTLE_SEC);
    /** An entry intent made this long after its signal is a late join. */
    private static final java.time.Duration LATE_JOIN = java.time.Duration.ofSeconds(60);

    /** The best bid since entry per open position, extended by each poll from where the last one stopped. */
    private record Best(double max, Timestamp upTo) {
    }

    private final java.util.Map<Long, Best> best = new java.util.concurrent.ConcurrentHashMap<>();

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    /** Each broker's square-off (null in tests without the rules file). */
    private final com.autotrade.execution.BrokerRules rules;

    LiveBookController(Access access, DataSource dataSource) {
        this(access, dataSource, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    LiveBookController(Access access, DataSource dataSource, com.autotrade.execution.BrokerRules rules) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.rules = rules;
    }

    @GetMapping("/api/execution/accounts/{id}/live-book")
    Map<String, Object> liveBook(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                 @PathVariable long id, @RequestParam(defaultValue = "7") int days) {
        Access.User user = access.user(subject);
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
        int window = Math.max(1, Math.min(days, 366));
        // a paper account's book is its PAPER entries; a broker account's is its LIVE ones (never a DRY_RUN rehearsal)
        String bookMode = a.brokerType() == com.autotrade.account.BrokerType.PAPER ? "PAPER" : "LIVE";
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
                + "order by o.id desc limit 1) stop_status, "
                + "i.created_at intent_at, d0.decided_at signal_at, "
                // the strategy's own exit of this contract after the entry's signal (an EXIT closes the whole position)
                + "(select min(d.decided_at) from exec.signal_decision d where d0.id is not null "
                + "and d.copy_group_id = d0.copy_group_id and d.session_date = d0.session_date and d.strategy_id = p.strategy_id "
                + "and d.instrument_token = p.instrument_token and d.action = 'EXIT' and d.decided_at >= d0.decided_at) "
                + "strategy_exit_at "
                + "from exec.position p join exec.trade_intent i on i.id = p.entry_trade_intent_id "
                + "left join exec.trade_batch b0 on b0.id = i.trade_batch_id "
                + "left join exec.signal_decision d0 on d0.id = b0.signal_decision_id "
                + "where p.broker_account_id = ? and i.execution_mode = ? "
                + "and (p.status <> 'CLOSED' or p.closed_at > now() - make_interval(days => ?)) order by p.opened_at desc",
                id, bookMode, window);

        // the scopes that stop this account's entries (KillSwitches): every LIVE account, this account, a strategy of its tenant
        List<Map<String, Object>> killRows = jdbc.queryForList("select id, scope, tenant_id, engaged_at, case scope when "
                + "'GLOBAL_LIVE' then 'all LIVE accounts: ' || reason when 'STRATEGY' then strategy_id || ': ' || reason "
                + "when 'TENANT' then 'all your accounts: ' || reason "
                + "else reason end text from exec.kill_switch where released_at is null and ((scope = 'GLOBAL_LIVE' and ?) or "
                + "(scope = 'ACCOUNT' and broker_account_id = ?) or (scope in ('STRATEGY', 'TENANT') and tenant_id = ?)) "
                + "order by engaged_at", a.tradeMode() == com.autotrade.account.TradeMode.LIVE, id, a.tenantId());

        LocalDate today = LocalDate.now(IST);
        List<Map<String, Object>> open = new ArrayList<>();
        List<Map<String, Object>> closed = new ArrayList<>();
        double openPnl = 0, atRisk = 0, realisedToday = 0, bookedOnOpen = 0;
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
                Timestamp exitAt = (Timestamp) r.get("strategy_exit_at");
                if (exitAt != null && exitAt.toInstant().isBefore(java.time.Instant.now().minus(EXIT_SETTLE))) {
                    out.put("strategyExitedAt", ist(exitAt));
                }
                Timestamp intentAt = (Timestamp) r.get("intent_at"), signalAt = (Timestamp) r.get("signal_at");
                if (intentAt != null && signalAt != null
                        && intentAt.toInstant().isAfter(signalAt.toInstant().plus(LATE_JOIN))) {
                    out.put("lateJoin", Map.of("signalAt", ist(signalAt), "joinedAt", ist(intentAt)));
                }
                if (r.get("realised_gross") != null) {         // a partial Exit already sold some (17 Nov 2026)
                    double soFar = num(r.get("realised_gross")) - num(r.get("charges"));
                    out.put("bookedSoFar", round(soFar));
                    if (openedToday(r)) {
                        realisedToday += soFar;
                        bookedOnOpen += soFar;
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
                + "where p.broker_account_id = ? and i.execution_mode = ? and p.status = 'CLOSED'", id, bookMode);
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
        summary.put("bookedOnOpen", round(bookedOnOpen));               // partial sales of still-open positions, today
        summary.put("sinceLiveNet", round(num(since.get("net"))));
        summary.put("sinceLiveTrades", ((Number) since.get("trades")).intValue());
        summary.put("sinceLiveFrom", since.get("first") == null ? null
                : ((Timestamp) since.get("first")).toInstant().atZone(IST).toLocalDate().toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", a.displayName());
        out.put("mode", a.tradeMode().name());
        out.put("bookMode", bookMode);
        com.autotrade.execution.BrokerRules.Rule rule = rules == null ? null : rules.rule(a.brokerType());
        out.put("squareOff", rule == null || rule.squareOff() == null ? null : rule.squareOff().toString());
        out.put("canAct", user.mayAct(a.tenantId()));
        out.put("canStop", user.emergency(a.tenantId()));     // SUPPORT: Exit and Flatten without a grant (V29)
        out.put("killSwitches", kills);
        out.put("summary", summary);
        out.put("open", open);
        out.put("closed", closed);
        return out;
    }

    /** A quote older than this (seconds) makes the exit preview ask for the typed confirmation. */
    static final int PREVIEW_STALE_SEC = 10;
    /** A spread wider than this (% of the mid) does too. */
    static final double PREVIEW_WIDE_SPREAD_PCT = 3.0;
    /** And a LIVE exit of more lots than this. */
    static final int PREVIEW_TYPED_ABOVE_LOTS = 5;

    /**
     * What an Exit of {@code quantity} (whole lots; default all) of one open position would do now (17 Nov 2026, phase 2):
     * the price it is estimated at (the captured bid, as the exit itself prices it), whether the bid's size covers it,
     * the spread and the quote's age, the sale's charges and net, how many orders the exchange's freeze quantity splits a
     * LIVE sale into and how the broker order is worked, the warnings, and whether the trader must type EXIT. Read-only.
     */
    @GetMapping("/api/execution/accounts/{id}/positions/{positionId}/exit-preview")
    Map<String, Object> exitPreview(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                    @PathVariable long id, @PathVariable long positionId,
                                    @RequestParam(required = false) Long quantity) {
        Access.User user = access.user(subject);
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
        Map<String, Object> p = jdbc.queryForList("select p.id, p.underlying, p.symbol, p.instrument_token, p.instrument_key, "
                + "p.quantity_open, p.average_entry, d.lot_size from exec.position p join exec.trade_intent i on "
                + "i.id = p.entry_trade_intent_id join exec.trade_batch b on b.id = i.trade_batch_id join exec.signal_decision d "
                + "on d.id = b.signal_decision_id where p.id = ? and p.broker_account_id = ? and p.status = 'OPEN' "
                + "and p.quantity_open > 0", positionId, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "position " + positionId + " is not open"));
        long open = ((Number) p.get("quantity_open")).longValue();
        int lot = ((Number) p.get("lot_size")).intValue();
        long sell = quantity == null ? open : quantity;
        if (sell <= 0 || lot <= 0 || sell % lot != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, sell + " is not a whole number of lots of " + lot);
        }
        sell = Math.min(sell, open);
        double entry = num(p.get("average_entry"));
        long token = ((Number) p.get("instrument_token")).longValue();
        String underlying = (String) p.get("underlying");
        boolean paper = a.brokerType() == com.autotrade.account.BrokerType.PAPER;
        String mode = paper ? "PAPER" : a.tradeMode().name();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("positionId", positionId);
        out.put("symbol", p.get("symbol"));
        out.put("mode", mode);
        out.put("open", open);
        out.put("lotSize", lot);
        out.put("quantity", sell);
        out.put("lots", sell / lot);
        out.put("remaining", open - sell);
        out.put("entry", entry);
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> last = jdbc.queryForList("select bid_px[1] bid, ask_px[1] ask, bid_qty[1] bid_qty, "
                + "extract(epoch from now() - recv_ts)::float8 age from md.option_tick where instrument_token = ? "
                + "and recv_ts > now() - make_interval(mins => ?) and bid_px[1] > 0 order by recv_ts desc limit 1", token,
                QUOTE_MAX_AGE_MIN);
        Double bid = null, spreadPct = null;
        double age = Double.NaN;
        if (last.isEmpty()) {
            warnings.add("no quote in the last " + QUOTE_MAX_AGE_MIN + " minutes: the price below is an old one");
        } else {
            Map<String, Object> q = last.getFirst();
            bid = num(q.get("bid"));
            double ask = num(q.get("ask"));
            age = num(q.get("age"));
            out.put("bid", bid);
            out.put("ask", ask > 0 ? ask : null);
            out.put("quoteAgeSec", Math.round(age));
            if (ask > 0 && ask >= bid) {
                spreadPct = round(100 * (ask - bid) / ((ask + bid) / 2));
                out.put("spreadPct", spreadPct);
            }
            Long bidQty = q.get("bid_qty") == null ? null : ((Number) q.get("bid_qty")).longValue();
            out.put("bidQuantity", bidQty);
            if (age > PREVIEW_STALE_SEC) {
                warnings.add("the quote is " + Math.round(age) + " s old");
            }
            if (spreadPct != null && spreadPct > PREVIEW_WIDE_SPREAD_PCT) {
                warnings.add("wide spread: " + spreadPct + " % of the price");
            }
            if (bidQty != null && bidQty < sell) {
                warnings.add("the best bid is for " + bidQty + " only: the rest sells lower");
            }
        }
        // the price the exit itself uses (ExecutionLoop.flattenPrice): the bid of the last 5 minutes, else the signal's
        double price;
        String priceFrom;
        if (bid != null && age <= 300) {
            price = bid;
            priceFrom = "bid";
        } else {
            List<Double> ref = jdbc.queryForList("select reference_price from exec.signal_decision where instrument_token = ? "
                    + "and reference_price > 0 order by decided_at desc limit 1", Double.class, token);
            price = !ref.isEmpty() && ref.getFirst() != null ? ref.getFirst() : entry;
            priceFrom = !ref.isEmpty() && ref.getFirst() != null ? "signal" : "entry";
        }
        out.put("price", price);
        out.put("priceFrom", priceFrom);
        double gross = (price - entry) * sell;
        out.put("gross", round(gross));
        CostModel costs = rules == null ? null : rules.costs(a.brokerType());
        if (costs != null) {
            double charges = costs.costs(LocalDate.now(IST), "SENSEX".equals(underlying) ? "BSE" : "NSE", Side.SELL, price, sell)
                    .total();
            out.put("charges", round(charges));
            out.put("net", round(gross - charges));
        }
        long slices = 1;
        if (!paper) {
            long freeze = new com.autotrade.execution.FreezeQuantities(jdbc.getDataSource()).of(
                    com.autotrade.execution.InstrumentKeys.normalize((String) p.get("instrument_key"), token), underlying);
            slices = freeze > 0 ? (sell + freeze - 1) / freeze : 1;
            out.put("freezeQuantity", freeze > 0 ? freeze : null);
        }
        out.put("orders", slices);
        out.put("how", paper ? "paper: filled at the captured bid, no broker order" : how(a));
        if (!paper && open - sell > 0) {
            out.put("rest", (open - sell) + " stay open with a new stop at the broker");
        }
        out.put("warnings", warnings);
        boolean typed = !paper && (sell / lot > PREVIEW_TYPED_ABOVE_LOTS || !warnings.isEmpty());
        out.put("typeToConfirm", typed);
        return out;
    }

    /** How the account's sell is worked at the broker (its risk file's order rules). */
    private static String how(BrokerAccount a) {
        try {
            com.autotrade.execution.OrderRules o = com.autotrade.execution.FollowerRisk.load(java.nio.file.Path.of(a.riskFile()))
                    .orders();
            if (o != null) {
                return String.format(java.util.Locale.ROOT, "limit at the bid, re-priced every %.0f s up to %d times, then %.1f %% "
                        + "through the bid; its stop comes off first", o.exitChaseSec(), o.exitChaseMax(), o.exitCrossPct());
            }
        } catch (RuntimeException e) {
            // the description only; the exit itself loads the file again
        }
        return "sold at the broker by the account's order rules; its stop comes off first";
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

    /** Whether a partially sold open position's sales count as today's (it was opened today). */
    private static boolean openedToday(Map<String, Object> r) {
        Timestamp opened = (Timestamp) r.get("opened_at");
        return opened != null && opened.toInstant().atZone(IST).toLocalDate().equals(LocalDate.now(IST));
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
