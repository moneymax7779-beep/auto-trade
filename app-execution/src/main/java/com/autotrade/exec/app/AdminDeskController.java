package com.autotrade.exec.app;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.execution.KillSwitches;

/**
 * The platform admin's exit desk (user 20 Nov 2026: "As a admin user, I should be able to manually exit open positions
 * PAPER/LIVE from all trader/demo accounts"; plan docs/plans/2026-11-20-admin-exit-positions-plan.md, phases A, B, D):
 * <ul>
 *   <li>{@code GET /api/execution/admin/open-positions}: every open position of every tenant and account (LIVE and
 *       PAPER), with its account's broker, login state, the member's consent to admin exits, the quote and its age, P&amp;L
 *       and stop; the mirror accounts (V49) with what they mirror. The engine's own positions (SIGNAL-1, PAPER-1) are
 *       trading-core's ({@code GET /api/sessions/open-positions}), read by the page from there.</li>
 *   <li>{@code POST /api/execution/admin/exit-batch}: exits the chosen positions, LIVE first (largest premium at risk
 *       first), then PAPER; each through its account's own lane and the per-position Exit's path; LIVE order starts paced
 *       per broker app ({@link OrderPacer}); a result per position; the accounts' kill switches after, when asked.</li>
 *   <li>{@code POST /api/execution/admin/targets} and {@code .../targets/clear}: the admin's first target (user 25 Nov 2026:
 *       "admin for global to be exited from all LIVE all workspaces with the first target"): one % set on (or removed from)
 *       every position sent, in one book, a reason given and the members told per account; a result per position.</li>
 *   <li>{@code GET/POST /api/execution/workspaces/{tenant}/admin-exit-consent}: the member's standing consent (V51).</li>
 *   <li>{@code GET /api/execution/notices}: admin exits on the caller's accounts in the last 24 h (the in-app banner).</li>
 * </ul>
 * Only the platform admin ({@link Access.User#admin}) uses the desk; anyone else gets 403.
 */
@RestController
class AdminDeskController implements org.springframework.beans.factory.DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AdminDeskController.class);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** A quote older than this is shown as stale on the desk (red). */
    static final int STALE_QUOTE_SEC = 10;
    /** The most positions one batch may carry. */
    static final int MAX_BATCH = 200;
    /** A finished batch (its results, and its key's answer) is kept at least this long (review 20 Nov 2026). */
    static final Duration KEEP_FINISHED = Duration.ofMinutes(60);
    /** How long a stopping worker waits for running batches before it goes (the exits run on until the worker's lanes stop). */
    static final Duration STOP_WAIT = Duration.ofSeconds(30);

    /**
     * Runs one position's exit on its account's lane and judges it there (the worker's
     * {@link com.autotrade.execution.ExecutionLoop#exitPositionOutcome}; tests give their own).
     */
    interface BatchRunner {
        com.autotrade.execution.ExitOutcome run(long accountId, long positionId, Long quantity, String actor, LocalDate day,
                                                boolean byAdmin, Map<String, Object> audit) throws Exception;
    }

    /**
     * @param quantity how many of it to sell (whole lots), the rest staying open: the per-position Exit's partial (user 25
     *                 Nov 2026: "partial exits like the Overview page's open positions"); null, or at least what is open:
     *                 all of it
     */
    record BatchPosition(Long accountId, Long positionId, Long quantity) {

        BatchPosition(Long accountId, Long positionId) {
            this(accountId, positionId, null);
        }
    }

    /**
     * The book a batch acts on (user 25 Nov 2026: choose LIVE or PAPER on the exit desk): a position whose entry was LIVE
     * is the LIVE book's, every other the PAPER book's. A batch with LIVE or PAPER is refused as a whole (nothing sent, no
     * kill switch) when a position of the other book is in it, and PAPER never engages GLOBAL_LIVE.
     */
    enum Scope {
        LIVE, PAPER, BOTH;

        static Scope of(String s) {
            if (s == null || s.isBlank()) {
                return BOTH;                                      // as before the choice: older pages
            }
            try {
                return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope: LIVE, PAPER or BOTH");
            }
        }

        /** Whether a position of this entry mode is in this book. */
        boolean holds(String mode) {
            return this == BOTH || (this == LIVE) == "LIVE".equals(mode);
        }
    }

    /**
     * @param killAfter  engage the kill switch of each account with a LIVE position before its exits run (null: true when
     *                   any LIVE position is in it, user 20 Nov 2026 decision 2)
     * @param confirm    exactly "EXIT &lt;n&gt; POSITIONS", n the number of positions sent (a stale screen cannot exit more)
     * @param killPaper  with killAfter, the paper accounts' kill switches too (user 23 Nov 2026, plan 2026-11-23 decision 3:
     *                   LIVE accounts by default, paper ones only when asked; null: as killAfter was sent, so an explicit
     *                   killAfter true without it stops paper accounts too, as before)
     * @param globalLive engage GLOBAL_LIVE first (no LIVE entry anywhere until it is released; "Exit everything", plan
     *                   2026-11-23 decision 3; null: false)
     * @param scope      the book it acts on, LIVE, PAPER or BOTH ({@link Scope}; null: BOTH)
     */
    record Batch(List<BatchPosition> positions, String reason, Boolean killAfter, String key, String confirm,
                 Boolean killPaper, Boolean globalLive, String scope) {

        Batch(List<BatchPosition> positions, String reason, Boolean killAfter, String key, String confirm,
              Boolean killPaper, Boolean globalLive) {
            this(positions, reason, killAfter, key, confirm, killPaper, globalLive, null);
        }
    }

    record Consent(Boolean on) {
    }

    /**
     * The admin's first target on many positions at once (user 25 Nov 2026).
     *
     * @param pct     the % above each position's average entry (as the per-position target; null for a removal)
     * @param confirm exactly "TARGET &lt;n&gt; POSITIONS" (set) or "CLEAR &lt;n&gt; TARGETS" (removal), n the positions sent
     * @param scope   the book (LIVE, PAPER or BOTH; null: LIVE, the book this is for): a position of another book refuses
     *                the whole request (409, nothing set)
     */
    record Targets(List<BatchPosition> positions, Double pct, String reason, String confirm, String scope) {
    }

    /**
     * One batch, running in the background (review 20 Nov 2026: a LIVE batch can outlast the UI proxy's 150 s): its
     * planned order, kill switches and one row per position, filled in as each exit answers.
     */
    private static final class BatchRun {
        final String id;
        final String key;
        final Instant started = Instant.now();
        volatile Instant finished;
        final int count;
        final boolean killAfter;
        /** The book it acts on (LIVE, PAPER or BOTH). */
        volatile Scope scope = Scope.BOTH;
        final List<String> killed = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** The accounts whose kill switch this batch engaged (their members' message says entries are stopped). */
        final Set<Long> killedIds = ConcurrentHashMap.newKeySet();
        final Map<String, String> killFailed = new ConcurrentHashMap<>();
        /** GLOBAL_LIVE: "ENGAGED", "ALREADY" (another was on), "FAILED: why", or null when not asked. */
        volatile String globalLive;
        final List<Long> order;
        final Map<Integer, Map<String, Object>> rows = new ConcurrentHashMap<>();

        BatchRun(String id, String key, int count, boolean killAfter, List<Long> order) {
            this.id = id;
            this.key = key;
            this.count = count;
            this.killAfter = killAfter;
            this.order = order;
        }

        Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("batchId", id);
            out.put("status", finished == null ? "RUNNING" : "DONE");
            out.put("count", count);
            out.put("killAfter", killAfter);
            out.put("scope", scope.name());
            out.put("killed", List.copyOf(killed));
            out.put("killFailed", Map.copyOf(killFailed));
            out.put("globalLive", globalLive);
            out.put("order", order);
            List<Map<String, Object>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                results.add(rows.get(i));
            }
            out.put("results", results);
            out.put("answered", results.stream().filter(r -> r != null && !"PENDING".equals(r.get("outcome"))
                    && !"RUNNING".equals(r.get("outcome"))).count());
            out.put("startedAt", started.toString());
            out.put("finishedAt", finished == null ? null : finished.toString());
            return out;
        }
    }

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final ExecutionService service;
    private final AdminExits adminExits;
    private final ContractQuotes quotes;
    private final KillSwitches kills;
    private final com.autotrade.execution.PositionTargets targets;
    /** Batches by id, and the id each (admin, key) started (the same key never runs twice). */
    private final Map<String, BatchRun> runs = new ConcurrentHashMap<>();
    private final Map<String, String> byKey = new ConcurrentHashMap<>();
    /**
     * Second review 20 Nov 2026: the key's check, the planning and the start are one step. Before, a second request with
     * the same key arriving while the first was still planning (a double tap, a client retry) found its batch not yet in
     * {@link #runs}, took it for forgotten and started a second batch; and {@link #forgetOld} dropped a key still being
     * planned. Starts are rare (one admin) and planning is a few reads per position, so one lock is enough.
     */
    private final Object starting = new Object();
    private OrderPacer pacer = new OrderPacer(OrderPacer.DEFAULT_PER_SECOND);
    private BatchRunner runner;

    AdminDeskController(Access access, DataSource dataSource, ExecutionService service, AdminExits adminExits) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.service = service;
        this.adminExits = adminExits;
        this.quotes = new ContractQuotes(jdbc);
        this.kills = new KillSwitches(dataSource);
        this.targets = new com.autotrade.execution.PositionTargets(dataSource);
    }

    /** Tests: the exits run by {@code runner} instead of the worker. */
    AdminDeskController withRunner(BatchRunner runner) {
        this.runner = runner;
        return this;
    }

    /** Tests: the order pacing. */
    AdminDeskController withPacer(OrderPacer pacer) {
        this.pacer = pacer;
        return this;
    }

    private Access.User admin(String subject) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "the exit desk is the platform admin's");
        return user;
    }

    // ------------------------------------------------------------------------------------------------- phase A: read

    /** The columns of an open position on the desk (every tenant, every account). */
    private static final String OPEN = "select p.id, p.status, p.tenant_id, t.name tenant, p.broker_account_id, a.display_name account, "
            + "a.broker_type, a.trade_mode account_mode, a.enabled, a.connection_status, a.credential_profile_id, "
            + "cp.name broker_app, i.execution_mode, p.strategy_id, p.underlying, p.option_side, p.symbol, p.instrument_key, "
            + "p.instrument_token, p.quantity_open, p.average_entry, p.opened_at, d0.lot_size, sa.display_name source, "
            + "(select max(x.expires_at) from acct.broker_session x where x.broker_account_id = a.id and x.status = 'ACTIVE' "
            + "and x.expires_at > now()) session_until, "
            + "(select o.trigger_price from exec.order_execution o where o.broker_account_id = p.broker_account_id "
            + "and o.trade_intent_id = p.entry_trade_intent_id and o.role = 'STOP' and o.trigger_price > 0 "
            + "order by o.id desc limit 1) stop_trigger, "
            + "(select o.status from exec.order_execution o where o.broker_account_id = p.broker_account_id "
            + "and o.trade_intent_id = p.entry_trade_intent_id and o.role = 'STOP' and o.trigger_price > 0 "
            + "order by o.id desc limit 1) stop_status, "
            + "(select l.owner || '|' || (l.heartbeat_at > now() - interval '2 minutes') from exec.account_lease l "
            + "where l.broker_account_id = a.id) lease "
            + "from exec.position p join acct.broker_account a on a.id = p.broker_account_id "
            + "join acct.tenant t on t.id = p.tenant_id join exec.trade_intent i on i.id = p.entry_trade_intent_id "
            + "left join exec.trade_batch b0 on b0.id = i.trade_batch_id "
            + "left join exec.signal_decision d0 on d0.id = b0.signal_decision_id "
            + "left join acct.copy_group g on g.id = d0.copy_group_id "
            + "left join acct.broker_account sa on sa.id = g.signal_source_account_id "
            + "left join acct.broker_credential_profile cp on cp.id = a.credential_profile_id "
            + "where p.status <> 'CLOSED' and p.quantity_open > 0";

    @GetMapping("/api/execution/admin/open-positions")
    Map<String, Object> openPositions(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = admin(subject);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<Long, Map<String, Object>> consents = new LinkedHashMap<>();
        Map<Long, Map<Long, com.autotrade.execution.PositionTargets.View>> byAccount = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(OPEN + " order by i.execution_mode = 'LIVE' desc, t.name, "
                + "a.display_name, p.opened_at")) {
            long tenant = ((Number) r.get("tenant_id")).longValue();
            Map<String, Object> consent = consents.computeIfAbsent(tenant, adminExits::consent);
            Map<String, Object> row = row(r, consent);
            // the first target (user 25 Nov 2026): as the Overview's open positions show it; from outside the workspace it
            // is set with a reason (targetNeedsReason)
            com.autotrade.execution.PositionTargets.View target = byAccount.computeIfAbsent(((Number) r.get(
                    "broker_account_id")).longValue(), targets::forAccount).get(((Number) r.get("id")).longValue());
            row.put("manualTarget", target == null ? null : target.toMap());
            row.put("targetNeedsReason", user.byAdmin(tenant));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", Instant.now().toString());
        out.put("staleQuoteSec", STALE_QUOTE_SEC);
        out.put("positions", rows);
        out.put("mirrors", jdbc.queryForList("select a.id account_id, a.display_name account, a.tenant_id, t.name tenant, "
                + "a.mirror_of from acct.broker_account a join acct.tenant t on t.id = a.tenant_id where a.mirror_of is not "
                + "null and a.enabled order by t.name, a.display_name"));
        return out;
    }

    private Map<String, Object> row(Map<String, Object> r, Map<String, Object> consent) {
        boolean paperBroker = "PAPER".equals(r.get("broker_type"));
        String mode = (String) r.get("execution_mode");
        long qty = ((Number) r.get("quantity_open")).longValue();
        double entry = num(r.get("average_entry"));
        Integer lot = r.get("lot_size") == null ? null : ((Number) r.get("lot_size")).intValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", "LIVE".equals(mode) ? "LIVE" : "PAPER");
        out.put("tenantId", r.get("tenant_id"));
        out.put("tenant", r.get("tenant"));
        out.put("accountId", r.get("broker_account_id"));
        out.put("account", r.get("account"));
        out.put("mode", mode);                                    // the entry's mode: what this position is
        out.put("accountMode", r.get("account_mode"));
        out.put("broker", r.get("broker_type"));
        out.put("brokerApp", r.get("broker_app"));
        out.put("connection", paperBroker ? null : r.get("connection_status"));
        Timestamp until = (Timestamp) r.get("session_until");
        String connection = (String) r.get("connection_status");
        // a real broker's login: today's session and a connection not marked lost (paper: none needed)
        out.put("loggedIn", paperBroker ? null : until != null && !"TOKEN_EXPIRED".equals(connection)
                && !"TOKEN_REJECTED".equals(connection) && !"DISCONNECTED".equals(connection));
        out.put("sessionUntil", until == null ? null : until.toInstant().toString());
        out.put("lease", r.get("lease"));
        out.put("positionId", r.get("id"));
        // OPEN, or RECONCILIATION_REQUIRED: our record and the broker's disagree; an exit sends nothing then (review)
        out.put("status", r.get("status"));
        out.put("strategy", r.get("strategy_id"));
        out.put("source", r.get("source"));                       // the signal source it copies (SIGNAL-1)
        out.put("underlying", r.get("underlying"));
        out.put("side", r.get("option_side"));
        out.put("symbol", r.get("symbol"));
        out.put("lotSize", lot);
        out.put("quantity", qty);
        out.put("lots", lot == null || lot <= 0 ? null : qty / lot);
        out.put("entry", entry);
        out.put("opened", ist((Timestamp) r.get("opened_at")));
        Map<String, Object> q = quotes.latest((String) r.get("instrument_key"), (String) r.get("underlying"),
                ((Number) r.get("instrument_token")).longValue(), LiveBookController.QUOTE_MAX_AGE_MIN);
        Double bid = q == null ? null : num(q.get("bid"));
        out.put("bid", bid);
        out.put("ask", q == null || num(q.get("ask")) <= 0 ? null : num(q.get("ask")));
        out.put("quoteAt", q == null ? null : ist((Timestamp) q.get("recv_ts")));
        Long age = q == null ? null : Math.max(0, (System.currentTimeMillis() - ((Timestamp) q.get("recv_ts")).getTime())
                / 1000);
        out.put("quoteAgeSec", age);
        out.put("quoteStale", age == null || age > STALE_QUOTE_SEC);
        out.put("pnl", bid == null ? null : round((bid - entry) * qty));
        out.put("premiumAtRisk", round((bid != null ? bid : entry) * qty));
        out.put("stop", r.get("stop_trigger") == null ? null : num(r.get("stop_trigger")));
        out.put("stopStatus", r.get("stop_status"));
        out.put("consent", consent);
        return out;
    }

    // ------------------------------------------------------------------------------------------------- phase B: act

    /**
     * Starts exiting the chosen positions (user 20 Nov 2026) and answers at once (202) with the batch's id and planned
     * order; {@code GET .../exit-batch/{batchId}} follows it (review 20 Nov 2026: a LIVE batch can outlast the UI proxy's
     * 150 s). Refused as a whole (4xx, nothing sold) when the caller is not the platform admin, the phrase does not name
     * the count, there is no reason or key, or a position is listed twice. The same key again answers the same batch
     * (nothing runs twice) for as long as the batch is kept. Each position is answered on its own: SOLD, PARTLY, REFUSED
     * (why), ALREADY_CLOSED or UNKNOWN (an order may still work: do not sell by hand, check the broker's order book).
     */
    @PostMapping("/api/execution/admin/exit-batch")
    ResponseEntity<Map<String, Object>> exitBatch(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                                  @RequestBody Batch b) {
        Access.User user = admin(subject);
        List<BatchPosition> asked = b.positions() == null ? List.of() : b.positions();
        if (asked.isEmpty() || asked.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "positions: 1 to " + MAX_BATCH);
        }
        Access.require(("EXIT " + asked.size() + " POSITIONS").equals(b.confirm()), "confirm with \"EXIT " + asked.size()
                + " POSITIONS\" (the number of positions sent)");
        String reason = AdminExits.requireReason(b.reason());
        if (b.key() == null || b.key().isBlank() || b.key().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key: 1 to 100 characters (one per confirmation)");
        }
        Scope scope = Scope.of(b.scope());
        if (scope == Scope.PAPER && Boolean.TRUE.equals(b.globalLive())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GLOBAL_LIVE stops the LIVE book: not with scope PAPER");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (BatchPosition p : asked) {
            if (p == null || p.accountId() == null || p.positionId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "each position needs accountId and positionId");
            }
            if (p.quantity() != null && p.quantity() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "position " + p.positionId() + ": quantity must "
                        + "be above 0 (none: all of it)");
            }
            if (!seen.add(p.accountId() + ":" + p.positionId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "position " + p.positionId() + " is listed twice");
            }
        }
        synchronized (starting) {
            return start(user, b, scope, asked, reason);
        }
    }

    /** The key's check, the plan, the kill switches and the batch's start (under {@link #starting}). */
    private ResponseEntity<Map<String, Object>> start(Access.User user, Batch b, Scope scope, List<BatchPosition> asked,
                                                      String reason) {
        forgetOld();
        String k = user.subject() + ":" + b.key();
        String id = java.util.UUID.randomUUID().toString();
        String first = byKey.putIfAbsent(k, id);
        if (first != null) {
            BatchRun earlier = runs.get(first);
            if (earlier != null) {
                Map<String, Object> view = earlier.view();
                view.put("repeat", true);                        // the same confirmation again: nothing runs twice
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
            }
            byKey.put(k, id);                                     // its batch was forgotten: a new one
        }
        BatchRun run;
        List<Planned> plans = new ArrayList<>();
        try {
            for (int i = 0; i < asked.size(); i++) {
                plans.add(plan(i, asked.get(i)));
            }
            // user 25 Nov 2026: a batch never touches the book that is not chosen; refused as a whole, before any kill
            // switch or exit (a page showing one book sends only that book's rows: anything else is stale or wrong)
            List<String> outside = plans.stream().filter(p -> p.mode() != null && !scope.holds(p.mode()))
                    .map(p -> (p.account() == null ? "" : p.account().displayName() + " ") + p.symbol() + " (" + p.mode()
                            + ")").toList();
            if (!outside.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "not in the " + scope + " book chosen: "
                        + String.join(", ", outside) + "; nothing was sent");
            }
            List<Planned> runnable = plans.stream().filter(p -> p.answer() == null)
                    .sorted(Comparator.comparing((Planned p) -> !p.live()).thenComparing(Comparator.comparingDouble(
                            Planned::atRisk).reversed())).toList();
            boolean anyLive = plans.stream().anyMatch(Planned::live);   // any LIVE row sent, open or already closed
            run = new BatchRun(id, b.key(), asked.size(), b.killAfter() != null ? b.killAfter() : anyLive,
                    runnable.stream().map(Planned::positionId).toList());
            run.scope = scope;
            plans.forEach(p -> run.rows.put(p.index(), p.answer() != null ? p.answer() : answer(p, "PENDING", List.of())));
            runs.put(id, run);
            // 23 Nov 2026 (plan decision 3): GLOBAL_LIVE first, when asked: no LIVE entry anywhere while the batch runs
            if (Boolean.TRUE.equals(b.globalLive())) {
                try {
                    boolean on = kills.list(null).stream().anyMatch(sw -> "GLOBAL_LIVE".equals(sw.scope()));
                    if (!on) {
                        kills.engage(null, "GLOBAL_LIVE", null, null, null, "ADMIN EXIT BATCH by " + user.subject() + ": "
                                + reason, user.subject());
                    }
                    run.globalLive = on ? "ALREADY" : "ENGAGED";
                } catch (RuntimeException e) {
                    run.globalLive = "FAILED: " + e.getMessage();
                    log.error("admin exit batch {}: GLOBAL_LIVE not engaged", id, e);
                }
            }
            // review 20 Nov 2026: the kill switches go on BEFORE any exit (as a flatten does), so whatever happens to the
            // exits, entries are already stopped; each account on its own (one failure does not skip the others).
            // 23 Nov 2026 (plan decision 3): by default (killAfter not sent) the accounts with a LIVE position only; the
            // paper ones too when killPaper says so, or when killAfter was asked for explicitly without killPaper (as before)
            if (run.killAfter) {
                boolean paperToo = scope != Scope.LIVE
                        && (b.killPaper() != null ? b.killPaper() : Boolean.TRUE.equals(b.killAfter()));
                Set<Long> liveAccounts = new java.util.HashSet<>();
                plans.stream().filter(Planned::live).forEach(p -> liveAccounts.add(p.accountId()));
                for (BrokerAccount a : involved(plans).values()) {
                    if (!liveAccounts.contains(a.id()) && !paperToo) {
                        continue;
                    }
                    try {
                        // by_admin: the kill's own alert goes to the admin chat only; the member hears of it once,
                        // in the batch's message (second review 20 Nov 2026)
                        kills.engageAccount(a, "ADMIN EXIT BATCH by " + user.subject() + ": " + reason, user.subject(),
                                user.byAdmin(a.tenantId()) ? Map.of("by_admin", true) : Map.of());
                        run.killed.add(a.displayName());
                        run.killedIds.add(a.id());
                    } catch (RuntimeException e) {
                        run.killFailed.put(a.displayName(), String.valueOf(e.getMessage()));
                        log.error("admin exit batch {}: kill switch on {} not engaged", id, a.displayName(), e);
                    }
                }
            }
            Thread.ofVirtual().name("admin-exit-batch-" + id).start(() -> runBatch(run, user, plans, runnable, reason));
        } catch (RuntimeException e) {
            byKey.remove(k, id);
            runs.remove(id);
            throw e;
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(run.view());
    }

    /** A batch's progress and its rows as they answer (RUNNING, then DONE). */
    @GetMapping("/api/execution/admin/exit-batch/{batchId}")
    Map<String, Object> batch(@RequestHeader(value = Access.HEADER, required = false) String subject,
                              @PathVariable String batchId) {
        admin(subject);
        BatchRun run = runs.get(batchId);
        if (run == null) {
            // second review 20 Nov 2026: batches live in this worker's memory; after a restart one is gone mid-way, so the
            // answer says what to do rather than only how long finished ones are kept
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "batch " + batchId + " is not known to this worker "
                    + "(finished batches are kept " + KEEP_FINISHED.toMinutes() + " minutes; a restart forgets running ones): "
                    + "read the open positions again; " + com.autotrade.execution.ExitOutcome.UNKNOWN_ADVICE);
        }
        return run.view();
    }

    /**
     * The worker is stopping (second review 20 Nov 2026): batches live only in memory, so a running one is given
     * {@link #STOP_WAIT} to finish (Spring stops this controller before the worker's loop it uses), and one still running
     * then is written to the audit as ADMIN_EXIT_BATCH_STOPPED with its rows so far: after the restart its GET is a 404 and
     * the audit is where the admin finds which exits had not answered (each of those may still have sold).
     */
    @Override
    public void destroy() {
        long until = System.nanoTime() + STOP_WAIT.toNanos();
        try {
            while (runs.values().stream().anyMatch(r -> r.finished == null) && System.nanoTime() < until) {
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (BatchRun r : runs.values()) {
            if (r.finished != null) {
                continue;
            }
            log.error("admin exit batch {} (key {}) still running as the worker stops: {}", r.id, r.key, r.view());
            try {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("batch", r.id);
                detail.put("key", r.key);
                detail.put("results", r.view().get("results"));
                detail.put("advice", "rows RUNNING or PENDING here were not answered: "
                        + com.autotrade.execution.ExitOutcome.UNKNOWN_ADVICE);
                kills.audit(null, null, "worker", "ADMIN_EXIT_BATCH_STOPPED", detail);
            } catch (RuntimeException e) {
                log.error("admin exit batch {}: its stop not audited", r.id, e);
            }
        }
    }

    /** Batches finished more than {@link #KEEP_FINISHED} ago, and their keys, are forgotten (running ones never). */
    private void forgetOld() {
        Instant cut = Instant.now().minus(KEEP_FINISHED);
        runs.values().removeIf(r -> r.finished != null && r.finished.isBefore(cut));
        byKey.values().removeIf(id -> !runs.containsKey(id));
    }

    /** The accounts a batch acts on: those of positions it will exit or found already flat (never a mirror or unknown). */
    private static Map<Long, BrokerAccount> involved(List<Planned> plans) {
        Map<Long, BrokerAccount> out = new LinkedHashMap<>();
        plans.stream().filter(p -> p.account() != null && (p.answer() == null
                || "ALREADY_CLOSED".equals(p.answer().get("outcome")))).forEach(p -> out.putIfAbsent(p.accountId(),
                p.account()));
        return out;
    }

    /** One position of a batch as found before it is run. */
    /** {@code quantity}: part of it to sell (whole lots), null: all of it. */
    private record Planned(int index, long accountId, long positionId, BrokerAccount account, String mode, String symbol,
                           long open, double atRisk, String paceKey, Map<String, Object> answer, Long quantity) {

        Planned(int index, long accountId, long positionId, BrokerAccount account, String mode, String symbol, long open,
                double atRisk, String paceKey, Map<String, Object> answer) {
            this(index, accountId, positionId, account, mode, symbol, open, atRisk, paceKey, answer, null);
        }

        boolean live() {
            return "LIVE".equals(mode);
        }
    }

    /**
     * The batch in the background: LIVE first, then PAPER (paper costs nothing to delay by a few seconds); one dispatcher
     * starts the exits in the planned order (largest premium at risk first), each LIVE start paced per broker app; each
     * account works its own exits one at a time (its lane runs one exit at a time anyway), accounts in parallel; PAPER
     * starts once every LIVE exit has answered. Then each account's audit row and its members' message, each on its own.
     */
    private void runBatch(BatchRun run, Access.User user, List<Planned> plans, List<Planned> runnable, String reason) {
        LocalDate day = LocalDate.now(IST);
        try {
            for (boolean live : new boolean[] {true, false}) {
                List<Planned> phase = runnable.stream().filter(p -> p.live() == live).toList();
                if (phase.isEmpty()) {
                    continue;
                }
                Map<Long, ExecutorService> lanes = new LinkedHashMap<>();
                List<Future<?>> running = new ArrayList<>();
                try {
                    for (Planned p : phase) {
                        try {
                            pacer.acquire(p.paceKey());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            run.rows.put(p.index(), answer(p, "REFUSED", List.of("interrupted before it was sent")));
                            continue;
                        }
                        run.rows.put(p.index(), answer(p, "RUNNING", List.of()));
                        running.add(lanes.computeIfAbsent(p.accountId(), id -> Executors.newSingleThreadExecutor(
                                Thread.ofVirtual().name("admin-exit-" + id).factory()))
                                .submit(() -> run.rows.put(p.index(), exitOne(user, p, reason, run.key, day))));
                    }
                    for (Future<?> f : running) {
                        try {
                            f.get();                                  // each exit is bounded by its lane's own 120 s
                        } catch (java.util.concurrent.ExecutionException e) {
                            // exitOne answers every failure itself; nothing reaches here but a bug, logged below
                            log.error("admin exit batch {}: an exit did not answer", run.id, e.getCause());
                        }
                    }
                } finally {
                    lanes.values().forEach(ExecutorService::shutdown);   // never interrupted: an exit under way runs on
                }
            }
        } catch (Throwable t) {
            log.error("admin exit batch {} stopped", run.id, t);
        } finally {
            // a row that never answered (a bug above) must not read as refused: its exit may have been sent
            for (Planned p : plans) {
                Map<String, Object> row = run.rows.get(p.index());
                if (row == null || "RUNNING".equals(row.get("outcome"))) {
                    run.rows.put(p.index(), withDetail(answer(p, "UNKNOWN", List.of()), "the batch stopped while this "
                            + "exit was under way: " + com.autotrade.execution.ExitOutcome.UNKNOWN_ADVICE));
                } else if ("PENDING".equals(row.get("outcome"))) {
                    run.rows.put(p.index(), withDetail(answer(p, "REFUSED", List.of()), "not sent: the batch stopped first"));
                }
            }
            afterBatch(run, user, plans, reason);
            run.finished = Instant.now();
        }
    }

    /**
     * Each account's audit row for the batch and one message to its members (Telegram + push) when it is not the admin's
     * own, each account on its own (review 20 Nov 2026: one failure skips nobody else and never fails the batch). The
     * message names only what the exit did (sold, part sold, unknown): a position its strategy closed first is not said to
     * have been sold by the admin.
     */
    private void afterBatch(BatchRun run, Access.User user, List<Planned> plans, String reason) {
        for (BrokerAccount a : involved(plans).values()) {
            try {
                List<String> all = new ArrayList<>(), told = new ArrayList<>();
                for (int i = 0; i < run.count; i++) {                 // in the order sent, not the map's
                    Map<String, Object> r = run.rows.get(i);
                    if (r == null) {
                        continue;
                    }
                    if (!Long.valueOf(a.id()).equals(r.get("accountId"))) {
                        continue;
                    }
                    String line = r.get("symbol") + ": " + r.get("outcome") + (r.get("asked") == null ? ""
                            : " [" + r.get("asked") + " asked]") + (r.get("detail") == null ? "" : " (" + r.get("detail")
                            + ")");
                    all.add(line);
                    if (List.of("SOLD", "PARTLY", "UNKNOWN").contains(r.get("outcome"))) {
                        // the exit's own lines when it has them ("NIFTY 25450 CE: 130 sold at 131.20"): the member sees
                        // what was sold and at what price, not only the outcome's name
                        @SuppressWarnings("unchecked")
                        List<String> lines = (List<String>) r.get("lines");
                        // an UNKNOWN's detail can carry a raw failure (SQL, a broker's error text): the member gets
                        // the plain words instead (second review 20 Nov 2026); the admin's page and the audit keep it
                        told.add("UNKNOWN".equals(r.get("outcome")) ? r.get("symbol") + ": whether it sold is not known "
                                + "yet: " + com.autotrade.execution.ExitOutcome.UNKNOWN_ADVICE
                                : lines == null || lines.isEmpty() ? line : String.join("; ", lines));
                    }
                }
                Map<String, Object> detail = new LinkedHashMap<>(auditOf(user, a, reason));
                detail.put("key", run.key);
                detail.put("batch", run.id);
                detail.put("positions", all);
                detail.put("kill_after", run.killAfter);
                detail.put("scope", run.scope.name());
                kills.audit(a, user.subject(), "ADMIN_EXIT_BATCH", detail);
                if (run.killedIds.contains(a.id())) {
                    told.add(AdminExits.STOPPED);                     // its kill's alert went to the admin chat only
                }
                if (user.byAdmin(a.tenantId()) && !told.isEmpty()) {
                    adminExits.exited(a, user.subject(), reason, told);
                }
            } catch (RuntimeException e) {
                log.error("admin exit batch {}: {}'s audit or message failed", run.id, a.displayName(), e);
            }
        }
    }

    /**
     * The audit detail of an admin exit; when the consent cannot be read the exit still goes, its consent "unknown"
     * (review 20 Nov 2026: an exit reduces risk and never waits on a read of the consent).
     */
    private Map<String, Object> auditOf(Access.User user, BrokerAccount a, String reason) {
        try {
            return new LinkedHashMap<>(adminExits.audit(user, a, reason));
        } catch (RuntimeException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("by_admin", user.byAdmin(a.tenantId()));
            out.put("admin_reason", reason);
            out.put("consent", "unknown (" + e.getMessage() + ")");
            return out;
        }
    }

    /** A position as found now: its account, mode and risk, or the answer it gets without being run. */
    private Planned plan(int index, BatchPosition asked) {
        long accountId = asked.accountId(), positionId = asked.positionId();
        BrokerAccount account = accounts.byId(accountId).orElse(null);
        if (account == null) {
            return new Planned(index, accountId, positionId, null, null, null, 0, 0, null,
                    answer(accountId, null, positionId, null, null, "REFUSED", "no account " + accountId, List.of()));
        }
        if (account.role() == com.autotrade.account.AccountRole.SIGNAL_SOURCE) {
            // the engine's signal source (SIGNAL-1): its positions are not orders and are not exited here (user 20 Nov
            // 2026, decision 1); refused before the kill switches, so it is never stopped by a batch either
            return new Planned(index, accountId, positionId, null, null, null, 0, 0, null, answer(accountId,
                    account.displayName(), positionId, null, null, "REFUSED", account.displayName() + " is a signal "
                            + "source: its positions are the engine's, not exited from the desk", List.of()));
        }
        String mirror = Mirrors.of(jdbc, accountId);
        if (mirror != null) {
            Map<String, Object> a = answer(accountId, account.displayName(), positionId, null, null, "REFUSED",
                    account.displayName() + " is a mirror of " + mirror + ": exit at the source (" + mirror + ")", List.of());
            a.put("why", "MIRROR");
            a.put("mirrorOf", mirror);
            return new Planned(index, accountId, positionId, account, null, null, 0, 0, null, a);
        }
        Map<String, Object> p = jdbc.queryForList("select p.status, p.quantity_open, p.average_entry, p.symbol, "
                + "p.instrument_key, p.underlying, p.instrument_token, i.execution_mode, d0.lot_size from exec.position p "
                + "join exec.trade_intent i on i.id = p.entry_trade_intent_id "
                + "left join exec.trade_batch b0 on b0.id = i.trade_batch_id "
                + "left join exec.signal_decision d0 on d0.id = b0.signal_decision_id "
                + "where p.id = ? and p.broker_account_id = ?", positionId, accountId).stream().findFirst().orElse(null);
        if (p == null) {
            return new Planned(index, accountId, positionId, account, null, null, 0, 0, null, answer(accountId,
                    account.displayName(), positionId, null, null, "REFUSED", "no position " + positionId + " in "
                            + account.displayName(), List.of()));
        }
        String mode = (String) p.get("execution_mode");
        String symbol = (String) p.get("symbol");
        long open = ((Number) p.get("quantity_open")).longValue();
        if ("CLOSED".equals(p.get("status")) || open <= 0) {
            return new Planned(index, accountId, positionId, account, mode, symbol, 0, 0, null, answer(accountId,
                    account.displayName(), positionId, symbol, mode, "ALREADY_CLOSED", null, List.of()));
        }
        // a part of it (25 Nov 2026): whole lots, checked here so a wrong count sends nothing (at a broker the worker's
        // own check would come after the lane started, an UNKNOWN); at least what is open is all of it
        Long part = asked.quantity() == null || asked.quantity() >= open ? null : asked.quantity();
        if (part != null) {
            int lot = p.get("lot_size") == null ? 0 : ((Number) p.get("lot_size")).intValue();
            String why = lot <= 0 ? "its lot size is not known: only all of it can be exited"
                    : part % lot != 0 ? part + " is not a whole number of lots of " + lot : null;
            if (why != null) {
                Map<String, Object> a = answer(accountId, account.displayName(), positionId, symbol, mode, "REFUSED", why,
                        List.of());
                a.put("asked", part);
                return new Planned(index, accountId, positionId, account, mode, symbol, open, 0, null, a);
            }
        }
        Map<String, Object> q = quotes.latest((String) p.get("instrument_key"), (String) p.get("underlying"),
                ((Number) p.get("instrument_token")).longValue(), LiveBookController.QUOTE_MAX_AGE_MIN);
        double price = q == null ? num(p.get("average_entry")) : num(q.get("bid"));
        // real orders are paced per broker app (several accounts may share one); paper and simulators are not
        String pace = !"LIVE".equals(mode) ? null : jdbc.queryForList("select credential_profile_id from "
                + "acct.broker_account where id = ?", Long.class, accountId).stream().filter(java.util.Objects::nonNull)
                .findFirst().map(id -> "app:" + id).orElse("account:" + accountId);
        return new Planned(index, accountId, positionId, account, mode, symbol, open, price * (part == null ? open : part),
                pace, null, part);
    }

    /**
     * One position's exit through its account's lane, judged there against the position as it was just before the exit
     * ({@link com.autotrade.execution.ExitOutcome}). A failure thrown back here is judged from the position and our exit
     * orders read again: nothing changed and no order written is REFUSED, anything else UNKNOWN (review 20 Nov 2026).
     */
    private Map<String, Object> exitOne(Access.User user, Planned p, String reason, String key, LocalDate day) {
        Map<String, Object> audit = auditOf(user, p.account(), reason);
        audit.put("batch", key);
        boolean byAdmin = user.byAdmin(p.account().tenantId());
        long mark;
        try {
            mark = jdbc.queryForObject("select coalesce(max(id), 0) from exec.order_execution", Long.class);
        } catch (RuntimeException e) {
            mark = -1;                                            // not known: a failure below is then UNKNOWN
        }
        com.autotrade.execution.ExitOutcome o;
        try {
            BatchRunner run = runner != null ? runner : (account, position, quantity, actor, d, admin, detail) ->
                    service.exitPositionOutcome(account, position, actor, d, quantity, admin, detail);
            o = run.run(p.accountId(), p.positionId(), p.quantity(), user.subject(), day, byAdmin, audit);
        } catch (IllegalArgumentException e) {
            return withDetail(answer(p, "REFUSED", List.of()), String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            // second review 20 Nov 2026: the wait for the lane ended, the exit on it did not (it may sell)
            Thread.currentThread().interrupt();
            return withDetail(answer(p, "UNKNOWN", List.of()), "the wait for this exit was interrupted while it may still "
                    + "run: " + com.autotrade.execution.ExitOutcome.UNKNOWN_ADVICE);
        } catch (Exception e) {
            log.error("admin exit of position {} failed", p.positionId(), e);
            return afterFailure(p, mark, e);
        }
        Map<String, Object> a = answer(p, o.outcome(), o.lines());
        a.put("detail", o.detail());
        a.put("before", o.before());
        a.put("after", o.after());
        return a;
    }

    /** A thrown exit: REFUSED only when the position is as it was and no exit order of it was written since. */
    private Map<String, Object> afterFailure(Planned p, long mark, Exception e) {
        try {
            Map<String, Object> now = jdbc.queryForMap("select p.status, p.quantity_open, (select count(*) from "
                    + "exec.order_execution o where o.trade_intent_id = p.entry_trade_intent_id and o.role = 'EMERGENCY_EXIT' "
                    + "and o.id > ?) orders from exec.position p where p.id = ?", mark, p.positionId());
            boolean same = mark >= 0 && !"CLOSED".equals(now.get("status"))
                    && ((Number) now.get("quantity_open")).longValue() == p.open()
                    && ((Number) now.get("orders")).longValue() == 0;
            if (same) {
                return withDetail(answer(p, "REFUSED", List.of()), "the exit failed before any order was sent ("
                        + e.getMessage() + ")");
            }
        } catch (RuntimeException read) {
            log.warn("admin exit of position {}: not read again after its failure: {}", p.positionId(), read.getMessage());
        }
        return withDetail(answer(p, "UNKNOWN", List.of()), "the exit failed (" + e.getMessage() + ") after an order may "
                + "have been sent: " + com.autotrade.execution.ExitOutcome.UNKNOWN_ADVICE);
    }

    private static Map<String, Object> withDetail(Map<String, Object> answer, String detail) {
        answer.put("detail", detail);
        return answer;
    }

    private static Map<String, Object> answer(Planned p, String outcome, List<String> lines) {
        Map<String, Object> a = answer(p.accountId(), p.account() == null ? null : p.account().displayName(), p.positionId(),
                p.symbol(), p.mode(), outcome, null, lines);
        if (p.quantity() != null) {
            a.put("asked", p.quantity());                         // a part of it: how many were asked for
        }
        return a;
    }

    private static Map<String, Object> answer(long accountId, String account, long positionId, String symbol, String mode,
                                              String outcome, String detail, List<String> lines) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("accountId", accountId);
        a.put("account", account);
        a.put("positionId", positionId);
        a.put("symbol", symbol);
        a.put("mode", mode);
        a.put("outcome", outcome);
        a.put("detail", detail);
        a.put("lines", lines);
        return a;
    }

    // ------------------------------------------------------------------------------------------------- admin target

    /**
     * Sets one first target % on every position sent (user 25 Nov 2026: "admin for global to be exited from all LIVE all
     * workspaces with the first target, admin target"): the per-position target's lock ({@link
     * com.autotrade.execution.PositionTargets}), set as the admin's (V55) on members' accounts and as the owner's own on
     * their own workspace's. Refused as a whole (4xx, nothing set) without the phrase, a reason or a valid %, or with a
     * position of another book than the scope's. Each position answers SET, UNCHANGED (that % already stands) or REFUSED
     * (why: a mirror, a signal source, closed, the target selling now). Then per account an ADMIN_TARGET_BATCH audit row and,
     * on members' accounts, one message to the members.
     */
    @PostMapping("/api/execution/admin/targets")
    Map<String, Object> setTargets(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                   @RequestBody Targets t) {
        Access.User user = admin(subject);
        if (t == null || t.pct() == null || com.autotrade.execution.PositionTargets.pct(t.pct()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pct: more than 0 and at most "
                    + (int) com.autotrade.execution.PositionTargets.MAX_PCT + " (two decimals)");
        }
        return targetBatch(user, t, "TARGET " + count(t) + " POSITIONS", true);
    }

    /** Removes the first target from every position sent (the admin target's undo); as {@link #setTargets}. */
    @PostMapping("/api/execution/admin/targets/clear")
    Map<String, Object> clearTargets(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                     @RequestBody Targets t) {
        Access.User user = admin(subject);
        return targetBatch(user, t, "CLEAR " + count(t) + " TARGETS", false);
    }

    private static int count(Targets t) {
        return t == null || t.positions() == null ? 0 : t.positions().size();
    }

    /** One position of a target batch as found before anything is set: its account, book and symbol, or why it is refused. */
    private record TargetPlan(BatchPosition asked, BrokerAccount account, String mode, String symbol, String refused) {
    }

    private TargetPlan targetPlan(BatchPosition p) {
        BrokerAccount a = accounts.byId(p.accountId()).orElse(null);
        Map<String, Object> pos = jdbc.queryForList("select p.symbol, i.execution_mode from exec.position p join "
                + "exec.trade_intent i on i.id = p.entry_trade_intent_id where p.id = ? and p.broker_account_id = ?",
                p.positionId(), p.accountId()).stream().findFirst().orElse(null);
        String mode = pos == null ? null : (String) pos.get("execution_mode");
        String symbol = pos == null ? null : (String) pos.get("symbol");
        if (a == null) {
            return new TargetPlan(p, null, mode, symbol, "no account " + p.accountId());
        }
        if (a.role() == com.autotrade.account.AccountRole.SIGNAL_SOURCE) {
            return new TargetPlan(p, a, mode, symbol, a.displayName() + " is a signal source: its positions are the engine's");
        }
        String mirror = Mirrors.of(jdbc, a.id());
        if (mirror != null) {
            return new TargetPlan(p, a, mode, symbol, a.displayName() + " is a mirror of " + mirror + ": set it at the source");
        }
        return new TargetPlan(p, a, mode, symbol, pos == null ? "no position " + p.positionId() + " in " + a.displayName()
                : null);
    }

    private Map<String, Object> targetBatch(Access.User user, Targets t, String phrase, boolean set) {
        List<BatchPosition> asked = t == null || t.positions() == null ? List.of() : t.positions();
        if (asked.isEmpty() || asked.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "positions: 1 to " + MAX_BATCH);
        }
        Access.require(phrase.equals(t.confirm()), "confirm with \"" + phrase + "\" (the number of positions sent)");
        String reason = AdminExits.requireReason(t.reason());
        Scope scope = t.scope() == null || t.scope().isBlank() ? Scope.LIVE : Scope.of(t.scope());
        Set<String> seen = new LinkedHashSet<>();
        List<TargetPlan> plans = new ArrayList<>();
        for (BatchPosition p : asked) {
            if (p == null || p.accountId() == null || p.positionId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "each position needs accountId and positionId");
            }
            if (!seen.add(p.accountId() + ":" + p.positionId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "position " + p.positionId() + " is listed twice");
            }
            plans.add(targetPlan(p));
        }
        List<String> outside = plans.stream().filter(x -> x.mode() != null && !scope.holds(x.mode()))
                .map(x -> x.account().displayName() + " " + x.symbol() + " (" + x.mode() + ")").toList();
        if (!outside.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in the " + scope + " book chosen: "
                    + String.join(", ", outside) + "; nothing was set");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        Map<Long, List<String>> lines = new LinkedHashMap<>(), told = new LinkedHashMap<>();
        Map<Long, BrokerAccount> touched = new LinkedHashMap<>();
        for (TargetPlan x : plans) {
            BatchPosition p = x.asked();
            BrokerAccount a = x.account();
            Map<String, Object> r = answer(p.accountId(), a == null ? null : a.displayName(), p.positionId(), x.symbol(),
                    x.mode(), "REFUSED", x.refused(), List.of());
            if (x.refused() == null) {
                boolean byAdmin = user.byAdmin(a.tenantId());
                Map<String, Object> audit = null;
                if (byAdmin) {
                    audit = new LinkedHashMap<>(auditOf(user, a, reason));
                    audit.put("by_admin", true);
                    audit.put("admin_reason", reason);
                    audit.put("admin_target_batch", true);
                }
                try {
                    Optional<com.autotrade.execution.PositionTargets.View> before = targets.view(p.positionId());
                    if (set) {
                        com.autotrade.execution.PositionTargets.View v = targets.set(a, p.positionId(), t.pct(),
                                user.subject(), audit);
                        r.put("outcome", com.autotrade.execution.PositionTargets.changed(before, Optional.of(v)) ? "SET"
                                : "UNCHANGED");
                        r.put("detail", String.format(java.util.Locale.ROOT, "first target +%s %% = %.2f (%s)",
                                com.autotrade.execution.PositionTargets.pctText(v.pct()), v.price(), v.status()));
                        r.put("target", v.toMap());
                    } else {
                        boolean cleared = targets.clear(a, p.positionId(), user.subject(), audit);
                        r.put("outcome", cleared ? "CLEARED" : "UNCHANGED");
                        r.put("detail", cleared ? "first target removed" : "no first target on it");
                    }
                } catch (IllegalArgumentException e) {
                    r.put("detail", e.getMessage());
                }
                touched.putIfAbsent(a.id(), a);
                lines.computeIfAbsent(a.id(), k -> new ArrayList<>()).add(x.symbol() + ": " + r.get("outcome") + " ("
                        + r.get("detail") + ")");
                if (byAdmin && List.of("SET", "CLEARED").contains(r.get("outcome"))) {
                    told.computeIfAbsent(a.id(), k -> new ArrayList<>()).add(x.symbol() + ": " + r.get("detail"));
                }
            }
            results.add(r);
        }
        for (BrokerAccount a : touched.values()) {
            try {
                Map<String, Object> detail = new LinkedHashMap<>(auditOf(user, a, reason));
                detail.put("set", set);
                if (set) {
                    detail.put("pct", com.autotrade.execution.PositionTargets.pct(t.pct()));
                }
                detail.put("scope", scope.name());
                detail.put("positions", lines.get(a.id()));
                kills.audit(a, user.subject(), "ADMIN_TARGET_BATCH", detail);
                if (told.containsKey(a.id())) {
                    adminExits.targetSet(a, user.subject(), reason, told.get(a.id()));
                }
            } catch (RuntimeException e) {
                log.error("admin target batch: {}'s audit or message failed", a.displayName(), e);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope.name());
        out.put("count", results.size());
        out.put("results", results);
        return out;
    }

    // ------------------------------------------------------------------------------------------------- consent (V51)

    /** The workspace's consent to admin exits; {@code canChange}: the caller is its OWNER (only they give it). */
    @GetMapping("/api/execution/workspaces/{tenant}/admin-exit-consent")
    Map<String, Object> consent(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                @PathVariable long tenant) {
        Access.User user = access.user(subject);
        if (!user.sees(tenant) && !user.admin()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + tenant);
        }
        Map<String, Object> out = new LinkedHashMap<>(adminExits.consent(tenant));
        out.put("canChange", "OWNER".equals(user.roles().get(tenant)));
        return out;
    }

    /**
     * The member's one-time standing consent (user 20 Nov 2026, decision 5): given or withdrawn by the workspace's OWNER
     * only (never SUPPORT, even with a grant: it is their own word), with its time; audited. It never blocks an exit.
     */
    @PostMapping("/api/execution/workspaces/{tenant}/admin-exit-consent")
    Map<String, Object> setConsent(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                   @PathVariable long tenant, @RequestBody Consent c) {
        Access.User user = access.user(subject);
        if (!user.sees(tenant)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + tenant);
        }
        Access.require("OWNER".equals(user.roles().get(tenant)), "only the workspace's OWNER gives this consent");
        if (c == null || c.on() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "on: true or false");
        }
        if (c.on()) {
            jdbc.update("update acct.tenant set admin_exit_consent_at = now(), admin_exit_consent_by = ? where id = ?",
                    user.id(), tenant);
        } else {
            jdbc.update("update acct.tenant set admin_exit_consent_at = null, admin_exit_consent_by = null where id = ?",
                    tenant);
        }
        kills.audit(tenant, null, user.subject(), c.on() ? "ADMIN_EXIT_CONSENT_ON" : "ADMIN_EXIT_CONSENT_OFF", Map.of(
                "text", c.on() ? "the platform admin may exit positions on this workspace's LIVE accounts (I am told each time)"
                        : "consent withdrawn (an admin exit is still possible to stop trouble, and still told)"));
        Map<String, Object> out = new LinkedHashMap<>(adminExits.consent(tenant));
        out.put("canChange", true);
        return out;
    }

    // ------------------------------------------------------------------------------------------------- the banner

    /** Reads the audit's position lines (a jsonb array of text). */
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
            .build();

    /**
     * The admin's exits on the caller's accounts in the last 24 h, newest first (the in-app banner, decision 3); never the
     * caller's own actions; and (25 Nov 2026) the admin's first target set or removed (TARGET_SET / TARGET_CLEARED by_admin).
     * Second review 20 Nov 2026: a POSITION_EXIT that sold nothing (a refused paper exit, "position 7
     * is not open" after its strategy closed it) is not shown as "the admin exited" (a flatten always is: it stopped the
     * account); and the rows are read through the audit's (account, time) index, not by scanning the whole log backwards
     * for every member's 30-second poll.
     */
    @GetMapping("/api/execution/notices")
    List<Map<String, Object>> notices(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        Long[] tenants = user.tenants().toArray(new Long[0]);
        List<Map<String, Object>> out = new ArrayList<>();
        // 25 Nov 2026: the admin's first target set or removed on the member's position (its symbol and % as the line)
        for (Map<String, Object> r : jdbc.queryForList("select l.id, " + Iso.of("l.at") + " at, l.action, "
                + "a.display_name account, l.detail ->> 'admin_reason' reason, coalesce((l.detail -> 'positions')::text, "
                + "case when l.action = 'TARGET_SET' then json_build_array((l.detail ->> 'symbol') || ': first target +' || "
                + "trim_scale((l.detail ->> 'pct')::numeric) || ' %' || coalesce(' = ' || to_char((l.detail ->> 'price')"
                + "::numeric, 'FM999999990.00'), ''))::text end) positions "
                + "from exec.audit_log l join acct.broker_account a on a.id = l.broker_account_id "
                + "where l.broker_account_id = any(array(select b.id from acct.broker_account b where b.tenant_id = any(?))) "
                + "and l.at > now() - interval '24 hours' and l.action in ('POSITION_EXIT', 'FLATTEN', 'TARGET_SET', "
                + "'TARGET_CLEARED') "
                + "and l.detail ->> 'by_admin' = 'true' and coalesce(l.detail ->> 'actor', l.actor, '') not in (?, ?) "
                + "order by l.at desc, l.id desc limit 100", tenants, user.subject(), "telegram:" + user.subject())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            String positions = (String) m.get("positions");
            if ("POSITION_EXIT".equals(m.get("action")) && !acted(positions)) {
                continue;
            }
            out.add(m);
            if (out.size() == 20) {
                break;
            }
        }
        return out;
    }

    /** Whether an audit's position lines (JSON text) say something was sold or may have been ({@link AdminExits#acted}). */
    private static boolean acted(String positions) {
        if (positions == null) {
            return false;
        }
        try {
            List<?> lines = JSON.readValue(positions, List.class);
            return lines.stream().anyMatch(l -> l instanceof String line && AdminExits.acted(line));
        } catch (RuntimeException e) {
            return true;                                          // unreadable: shown rather than hidden
        }
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
