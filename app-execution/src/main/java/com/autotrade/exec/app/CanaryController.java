package com.autotrade.exec.app;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.execution.BrokerAdapter;
import com.autotrade.execution.Canary;
import com.autotrade.execution.CanaryJournal;
import com.autotrade.execution.FollowerRisk;
import com.autotrade.execution.KillSwitches;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/execution/accounts/{id}/canary} {@code {"instrumentKey": "NSE_FO|47168", "confirm": "CANARY <account
 * name> NSE_FO|47168"}}: the morning canary (multi-broker plan 16 Nov 2026, D1; live adapters plan step E) on one
 * account's real broker: one lot bought far below the market, read back by its tag, modified, cancelled, every step
 * audited ({@code CANARY_STEP}, {@code CANARY_RESULT}) and reported as JSON ({@link Canary}). It sends a real order, so
 * it runs only:
 * <ul>
 *   <li>by the platform admin who also has the authority to switch that account LIVE (review 16 Nov 2026, as
 *       {@link LiveSwitchController}: OWNER or ADMIN of the account's workspace, SUPPORT only with the member's grant),
 *       with the phrase typed exactly (the account and the contract);</li>
 *   <li>on a trading day between 09:20 and 15:00 IST (the book is open and settled, well before any square-off);</li>
 *   <li>on an enabled real-broker account with today's session, on a risk file with {@code live} limits (its LIVE guard
 *       caps the buy at one lot), through the adapter's own LIVE connection ({@code BrokerAdapter.live}: today Zerodha;
 *       other brokers once their LIVE connections exist), no GLOBAL_LIVE, account, tenant or contract kill switch;</li>
 *   <li>with no open position and no working order of ours in ANY contract (our book here; the broker's book again in
 *       the canary itself; review 16 Nov 2026: the trading LIVE guard counts the canary's working buy as a contract
 *       held), and one canary at a time per account.</li>
 * </ul>
 * The run takes many seconds (three book waits, the instrument list), so the request only starts it (review 16 Nov 2026:
 * a load balancer's 504 invites a retry): {@code 202} with its {@code runId}, the run on its own virtual thread, and
 * {@code GET /api/execution/accounts/{id}/canary/{runId}} returns the steps so far and then the result (the last
 * {@value #KEEP_RUNS} runs in memory; older ones, or after a restart, from their audit rows). The account stays locked to
 * one run until the run ends. Something left at the broker engages the account's kill switch and is alerted
 * ({@code CANARY_LEFTOVER}, {@link CanaryJournal}).
 * <p>
 * Nothing schedules it: this request is its only entry, refused on a scheduler thread (the run's own thread is not one,
 * so the request's thread is checked here; the canary checks its own as well). The account's trade mode is not changed;
 * the canary's order is tagged as ours ({@code F0C...}) so reconciliation reads it as ours.
 */
@RestController
class CanaryController {

    private static final Logger log = LoggerFactory.getLogger(CanaryController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Opens an account's LIVE connection behind its LIVE guard (null: the broker's adapter offers none). */
    interface LiveOpener {
        BrokerAdapter.Live open(BrokerAccount account, FollowerRisk.LiveLimits limits);
    }

    record Request(String instrumentKey, String confirm) {
    }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalTime FROM = LocalTime.of(9, 20), UNTIL = LocalTime.of(15, 0);
    /** Runs kept in memory for {@code GET} (older ones are read from their audit rows). */
    static final int KEEP_RUNS = 50;
    private static final String WORKING = "('CREATED', 'SUBMITTED', 'ACKNOWLEDGED', 'OPEN', 'PARTIALLY_FILLED', "
            + "'CANCEL_PENDING', 'UNKNOWN', 'RECONCILIATION_REQUIRED')";

    /** One run: what is known so far (steps as they happen, then the result). */
    static final class Run {
        final String id;
        final long accountId;
        final String account;
        final String actor;
        final String instrumentKey;
        final Instant startedAt;
        final List<Map<String, Object>> steps = new CopyOnWriteArrayList<>();
        volatile String connection;
        volatile String reportFile;
        /** The final report's view (null while running). */
        volatile Map<String, Object> result;

        Run(String id, BrokerAccount a, String actor, String instrumentKey, Instant startedAt) {
            this.id = id;
            this.accountId = a.id();
            this.account = a.displayName();
            this.actor = actor;
            this.instrumentKey = instrumentKey;
            this.startedAt = startedAt;
        }

        Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runId", id);
            out.put("accountId", accountId);
            out.put("account", account);
            out.put("actor", actor);
            out.put("instrumentKey", instrumentKey);
            out.put("runStartedAt", startedAt.toString());
            out.put("connection", connection);
            Map<String, Object> r = result;
            out.put("status", r == null ? "RUNNING" : "DONE");
            if (r == null) {
                out.put("steps", List.copyOf(steps));
            } else {
                out.putAll(r);
                out.put("reportFile", reportFile);
            }
            return out;
        }
    }

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TradingDays tradingDays;
    private final LiveOpener opener;
    private final Path dir;
    private final Clock clock;
    private final Canary.Settings settings;
    /** The account switch's reason while a canary runs (review 16 Nov 2026, D3): entries held, exits go on. */
    static final String FENCE = KillSwitches.HOLD + "new entries held while the canary runs";
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final Map<String, Run> runs = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Run> eldest) {
            return size() > KEEP_RUNS;
        }
    });

    @Autowired
    CanaryController(Access access, DataSource dataSource, TradingDays tradingDays, ExecutionService service,
                     BrokerLoginProperties brokers, @Value("${autotrade.execution.canary.dir:}") String dir) {
        this(access, dataSource, tradingDays, service::openLive, dir == null || dir.isBlank()
                ? Path.of(brokers.storeDir()).resolve("canary") : Path.of(dir.trim()), Clock.systemUTC(),
                Canary.Settings.DEFAULT);
    }

    CanaryController(Access access, DataSource dataSource, TradingDays tradingDays, LiveOpener opener, Path dir,
                     Clock clock, Canary.Settings settings) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.dataSource = dataSource;
        this.tradingDays = tradingDays;
        this.opener = opener;
        this.dir = dir;
        this.clock = clock;
        this.settings = settings;
    }

    /** The phrase the admin types for this account and contract. */
    static String phrase(BrokerAccount account, String instrumentKey) {
        return "CANARY " + account.displayName() + " " + instrumentKey;
    }

    /**
     * The account, when this user may run a canary on it: the platform admin, with the authority to switch it LIVE
     * (review 16 Nov 2026: {@code sees} alone let SUPPORT without the member's grant, or an ADMIN of another tenant,
     * send a real order into a member's account).
     */
    private BrokerAccount authorised(Access.User user, long id) {
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
        Access.require(user.admin(), "only the platform admin runs a canary");
        Access.require(user.manages(a.tenantId()), "a canary sends a real order into " + a.displayName() + ": only an "
                + "OWNER or ADMIN of its workspace, or SUPPORT with the member's grant, may (as for its LIVE switch)");
        return a;
    }

    @PostMapping("/api/execution/accounts/{id}/canary")
    ResponseEntity<Map<String, Object>> canary(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                               @PathVariable long id, @RequestBody Request body) {
        require(!Canary.onSchedulerThread(), HttpStatus.CONFLICT, "a canary is never run by a scheduler: only by the "
                + "user's own request");
        Access.User user = access.user(subject);
        BrokerAccount a = authorised(user, id);
        String key = body == null || body.instrumentKey() == null ? "" : body.instrumentKey().trim();
        require(!key.isEmpty(), HttpStatus.BAD_REQUEST, "name the contract (instrumentKey, e.g. NSE_FO|47168)");
        require(phrase(a, key).equals(body.confirm()), HttpStatus.BAD_REQUEST, "type \"" + phrase(a, key)
                + "\" to confirm: a canary sends a real order (one lot, far below the market) to " + a.brokerType());
        require(a.brokerType() != BrokerType.PAPER, HttpStatus.CONFLICT, a.displayName() + " is a paper account");
        require(a.enabled(), HttpStatus.CONFLICT, a.displayName() + " is disabled");
        ZonedDateTime now = clock.instant().atZone(IST);
        LocalDate today = now.toLocalDate();
        require(tradingDays.isTradingDay(today) && !now.toLocalTime().isBefore(FROM) && now.toLocalTime().isBefore(UNTIL),
                HttpStatus.CONFLICT, "a canary runs on a trading day from " + FROM + " to " + UNTIL + " IST, not "
                        + today + " " + now.toLocalTime().withNano(0));
        FollowerRisk risk;
        try {
            risk = FollowerRisk.load(Path.of(a.riskFile()));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "risk file " + a.riskFile() + " not readable");
        }
        require(risk.live() != null, HttpStatus.CONFLICT, "risk file " + a.riskFile() + " has no live limits: the canary "
                + "goes through the account's LIVE guard");
        Integer session = jdbc.queryForObject("select count(*) from acct.broker_session where broker_account_id = ? "
                + "and status = 'ACTIVE' and expires_at > now()", Integer.class, id);
        require(session != null && session > 0, HttpStatus.CONFLICT, "no " + a.brokerType() + " session today: log in "
                + "first");
        List<String> global = jdbc.queryForList("select reason from exec.kill_switch where released_at is null and scope "
                + "= 'GLOBAL_LIVE'", String.class);
        require(global.isEmpty(), HttpStatus.CONFLICT, "GLOBAL_LIVE is engaged: " + global);
        String engaged = new KillSwitches(dataSource).engaged(a, "canary", key);
        require(engaged == null, HttpStatus.CONFLICT, "a kill switch is engaged: " + engaged);
        Canary.Contract contract = contract(key, today);
        // any contract (review 16 Nov 2026): the trading LIVE guard would count the canary's working buy as one more
        // contract held beside a real position, and could refuse a real entry at max open positions
        Integer held = jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ? and status "
                + "<> 'CLOSED'", Integer.class, id);
        require(held == null || held == 0, HttpStatus.CONFLICT, a.displayName() + " holds " + held + " position(s) of "
                + "ours: the canary runs only when the account holds none, in any contract");
        Integer working = jdbc.queryForObject("select count(*) from exec.order_execution e where e.broker_account_id = ? "
                + "and e.status in " + WORKING + " and e.created_at > now() - interval '1 day'", Integer.class, id);
        require(working == null || working == 0, HttpStatus.CONFLICT, a.displayName() + " has " + working + " order(s) "
                + "of ours working: the canary runs only when none is, in any contract");
        require(running.add(id), HttpStatus.CONFLICT, "a canary is already running on " + a.displayName());
        Run run = new Run(UUID.randomUUID().toString(), a, user.subject(), key, clock.instant());
        runs.put(run.id, run);
        try {
            Thread.ofVirtual().name("canary-" + id + "-" + run.id).start(() -> execute(run, a, contract, risk));
        } catch (RuntimeException | Error e) {
            running.remove(id);
            runs.remove(run.id);
            throw e;
        }
        return ResponseEntity.accepted().location(URI.create("/api/execution/accounts/" + id + "/canary/" + run.id))
                .body(run.view());
    }

    /** A run's steps so far, or its result: from memory, else (an older run, a restart) from its audit rows. */
    /** The indices a canary's contract may be on (the platform's own: their options are in the contract master). */
    static final Set<String> INDICES = Set.of("NIFTY", "SENSEX", "BANKNIFTY");

    /**
     * The canary's contract for the Brokers page (18 Nov 2026, Kotak step 3): the nearest expiry not past today of the
     * index's option at that strike and side, from the latest contract master of that index, with the phrase to type.
     * Same authority as the run. Reads only the contract master (no quotes: the canary checks the live quote itself).
     */
    @GetMapping("/api/execution/accounts/{id}/canary/contract")
    Map<String, Object> contract(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                 @PathVariable long id, @RequestParam String index, @RequestParam double strike,
                                 @RequestParam String side) {
        BrokerAccount a = authorised(access.user(subject), id);
        String underlying = index == null ? "" : index.trim().toUpperCase(java.util.Locale.ROOT);
        String type = side == null ? "" : side.trim().toUpperCase(java.util.Locale.ROOT);
        require(INDICES.contains(underlying), HttpStatus.BAD_REQUEST, "index: one of " + new java.util.TreeSet<>(INDICES));
        require(type.equals("CE") || type.equals("PE"), HttpStatus.BAD_REQUEST, "side: CE or PE");
        require(strike > 0 && strike < 1_000_000, HttpStatus.BAD_REQUEST, "strike: a positive number");
        LocalDate today = clock.instant().atZone(IST).toLocalDate();
        List<Map<String, Object>> rows = jdbc.queryForList("select instrument_key, trading_symbol, expiry, lot_size from "
                + "ref.instrument where snapshot_id = (select max(snapshot_id) from ref.instrument where underlying = ?) "
                + "and underlying = ? and type = ? and strike = ? and expiry >= ? order by expiry limit 1", underlying,
                underlying, type, strike, java.sql.Date.valueOf(today));
        require(!rows.isEmpty(), HttpStatus.NOT_FOUND, "no " + underlying + " " + fmt(strike) + " " + type
                + " expiring from " + today + " in the contract master");
        Map<String, Object> r = rows.getFirst();
        String key = (String) r.get("instrument_key");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instrumentKey", key);
        out.put("symbol", r.get("trading_symbol"));
        out.put("expiry", String.valueOf(r.get("expiry")));
        out.put("lotSize", r.get("lot_size"));
        out.put("phrase", phrase(a, key));
        return out;
    }

    private static String fmt(double strike) {
        return strike == Math.rint(strike) ? String.valueOf((long) strike) : String.valueOf(strike);
    }

    @GetMapping("/api/execution/accounts/{id}/canary/{runId}")
    Map<String, Object> run(@RequestHeader(value = Access.HEADER, required = false) String subject,
                            @PathVariable long id, @PathVariable String runId) {
        Access.User user = access.user(subject);
        BrokerAccount a = authorised(user, id);
        Run run = runs.get(runId);
        if (run != null && run.accountId == id) {
            return run.view();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("select action, actor, detail::text detail from "
                + "exec.audit_log where broker_account_id = ? and action in ('CANARY_STEP', 'CANARY_RESULT', "
                + "'CANARY_LEFTOVER') and detail ->> 'runId' = ? order by id", id, runId);
        require(!rows.isEmpty(), HttpStatus.NOT_FOUND, "no canary run " + runId + " on " + a.displayName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("accountId", id);
        out.put("account", a.displayName());
        out.put("actor", rows.getFirst().get("actor"));
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> result = null;
        Map<String, Object> leftover = null;
        for (Map<String, Object> r : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = JSON.readValue((String) r.get("detail"), LinkedHashMap.class);
            d.remove("runId");
            switch ((String) r.get("action")) {
                case "CANARY_STEP" -> steps.add(d);
                case "CANARY_RESULT" -> result = d;
                default -> leftover = d;
            }
        }
        out.put("status", result != null ? "DONE" : "UNKNOWN");
        if (result != null) {
            out.putAll(result);
            out.put("reportFile", result.get("report"));
        } else {
            out.put("message", "no result recorded: the service may have stopped during the run; check the broker's "
                    + "order book for the canary's order");
        }
        if (leftover != null) {
            out.put("leftoverDetail", leftover);
        }
        out.put("steps", steps);
        return out;
    }

    /** The run itself, on its own virtual thread; the account's lock is released only when it ends. */
    private void execute(Run run, BrokerAccount a, Canary.Contract contract, FollowerRisk risk) {
        CanaryJournal journal = new CanaryJournal(dataSource, a, run.actor, dir, run.id);
        Canary.Journal tracked = new Canary.Journal() {
            @Override
            public void step(Canary.Step step) {
                run.steps.add(CanaryJournal.view(step));
                journal.step(step);
            }

            @Override
            public void result(Canary.Report report) {
                journal.result(report);
            }

            @Override
            public void leftover(Canary.Report report) {
                journal.leftover(report);
            }
        };
        Canary.Report report = null;
        KillSwitches kills = new KillSwitches(dataSource);
        Long fence = null;
        try {
            FollowerRisk.LiveLimits limits = new FollowerRisk.LiveLimits(1, risk.live().maxOpenPositions(),
                    risk.live().strategies(), risk.live().defaults());
            BrokerAdapter.Live live = null;
            try {
                live = opener.open(a, limits);
            } catch (RuntimeException e) {
                report = notRun(a, contract, Canary.Result.FAIL, "FAIL: the LIVE connection did not open (nothing was "
                        + "sent): " + e);
            }
            if (report == null && live == null) {
                report = notRun(a, contract, Canary.Result.REFUSED, "REFUSED: no LIVE connection for " + a.brokerType()
                        + " yet: its adapter offers none");
            } else if (report == null && live.isRefused()) {
                // merge 16 Nov 2026: a LIVE connection the adapter refuses (the static-IP gate, a denied Upstox user)
                report = notRun(a, contract, Canary.Result.REFUSED, "REFUSED: LIVE refused: " + live.description());
            }
            if (report != null) {
                tracked.result(report);                              // nothing sent: still audited and filed
                return;
            }
            run.connection = live.description();
            try {
                // review 16 Nov 2026 (D3): no strategy entry on the account while the canary runs (its buy would be one
                // more contract held, and a real fill of the contract would have been taken for the canary's). The
                // account's switch stops new entries; it is released when the run ends, kept when it left something
                fence = kills.engage(a.tenantId(), "ACCOUNT", a.id(), null, null, FENCE + " (run " + run.id + ")",
                        run.actor);
            } catch (RuntimeException e) {
                report = notRun(a, contract, Canary.Result.FAIL, "FAIL: new entries could not be held for the run "
                        + "(nothing was sent): " + e.getMessage());
                tracked.result(report);
                live.connection().close();
                return;
            }
            try {
                report = new Canary(live.connection(), contract, settings, tracked, clock).run();
            } catch (Throwable t) {
                // the canary handles every RuntimeException itself; anything else may have left its order working
                log.error("canary {} on account {} ended unexpectedly: {}", run.id, a.id(), t.toString());
                report = unexpected(a, contract, run, t);
                tracked.leftover(report);
                tracked.result(report);
            } finally {
                try {
                    live.connection().close();
                } catch (RuntimeException e) {
                    // the connection's own clean-up; the report stands
                }
            }
        } catch (Throwable t) {
            log.error("canary {} on account {}: {}", run.id, a.id(), t.toString());
            if (report == null) {
                report = notRun(a, contract, Canary.Result.FAIL, "FAIL: " + t);
            }
        } finally {
            if (fence != null) {
                try {
                    // released always (second review 16 Nov 2026): a leftover's kill, and any other engaged meanwhile,
                    // has its own row (a hold is never taken for one); only an unexpected end keeps it
                    kills.release(fence, a.tenantId(), run.actor);
                } catch (RuntimeException e) {
                    log.error("canary {} on account {}: its entry hold #{} was not settled: {}", run.id, a.id(), fence,
                            e.toString());
                }
            }
            run.reportFile = journal.reportFile() == null ? null : journal.reportFile().toString();
            run.result = CanaryJournal.view(report);
            running.remove(a.id());
        }
    }

    /** The report of a run that sent nothing (no LIVE connection). */
    private Canary.Report notRun(BrokerAccount a, Canary.Contract contract, Canary.Result result, String message) {
        Instant at = clock.instant();
        return new Canary.Report(result, a.brokerType().name(), contract.instrumentKey(), contract.symbol(),
                contract.lotSize(), null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, false, false,
                message, at, at, List.of());
    }

    /** The canary ended with something other than a RuntimeException: its order may still be working. */
    private Canary.Report unexpected(BrokerAccount a, Canary.Contract contract, Run run, Throwable t) {
        Instant at = clock.instant();
        return new Canary.Report(Canary.Result.FAIL, a.brokerType().name(), contract.instrumentKey(), contract.symbol(),
                contract.lotSize(), null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, false, true,
                "FAIL: the canary ended unexpectedly (" + t + ") after " + run.steps.size() + " step(s): its order may "
                        + "still be working at the broker: check and cancel it by hand", run.startedAt, at, List.of());
    }

    /** The contract as our master lists it (latest snapshot), not expired. */
    private Canary.Contract contract(String key, LocalDate today) {
        List<Map<String, Object>> rows = jdbc.queryForList("select trading_symbol, lot_size, tick_size, expiry from "
                + "ref.instrument where instrument_key = ? order by snapshot_id desc limit 1", key);
        require(!rows.isEmpty(), HttpStatus.BAD_REQUEST, key + " is not in the contract master");
        Map<String, Object> r = rows.getFirst();
        Object expiry = r.get("expiry");
        LocalDate expires = expiry instanceof java.sql.Date d ? d.toLocalDate() : null;
        require(expires == null || !expires.isBefore(today), HttpStatus.BAD_REQUEST, key + " expired on " + expires);
        try {
            return new Canary.Contract(key, (String) r.get("trading_symbol"), ((Number) r.get("lot_size")).intValue(),
                    ((Number) r.get("tick_size")).doubleValue());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static void require(boolean ok, HttpStatus status, String why) {
        if (!ok) {
            throw new ResponseStatusException(status, why);
        }
    }
}
