package com.autotrade.exec.app;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.execution.BrokerAdapter;
import com.autotrade.execution.Canary;
import com.autotrade.execution.CanaryJournal;
import com.autotrade.execution.FollowerRisk;
import com.autotrade.execution.InstrumentKeys;
import com.autotrade.execution.KillSwitches;

/**
 * {@code POST /api/execution/accounts/{id}/canary} {@code {"instrumentKey": "NSE_FO|47168", "confirm": "CANARY <account
 * name> NSE_FO|47168"}}: the morning canary (multi-broker plan 16 Nov 2026, D1; live adapters plan step E) on one
 * account's real broker: one lot bought far below the market, read back by its tag, modified, cancelled, every step
 * audited ({@code CANARY_STEP}, {@code CANARY_RESULT}) and reported as JSON ({@link Canary}). It sends a real order, so
 * it runs only:
 * <ul>
 *   <li>by the platform admin, on an account they see, with the phrase typed exactly (the account and the contract);</li>
 *   <li>on a trading day between 09:20 and 15:00 IST (the book is open and settled, well before any square-off);</li>
 *   <li>on an enabled real-broker account with today's session, on a risk file with {@code live} limits (its LIVE guard
 *       caps the buy at one lot), through the adapter's own LIVE connection ({@code BrokerAdapter.live}: today Zerodha;
 *       other brokers once their LIVE connections exist), no GLOBAL_LIVE, account, tenant or contract kill switch;</li>
 *   <li>with no open position and no working order of ours in that contract (our book here; the broker's book again in
 *       the canary itself), and one canary at a time per account.</li>
 * </ul>
 * Nothing schedules it: this request is its only entry (and the canary refuses on a scheduler thread). The account's
 * trade mode is not changed; the canary's order is tagged as ours ({@code F0C...}) so reconciliation reads it as ours.
 */
@RestController
class CanaryController {

    /** Opens an account's LIVE connection behind its LIVE guard (null: the broker's adapter offers none). */
    interface LiveOpener {
        BrokerAdapter.Live open(BrokerAccount account, FollowerRisk.LiveLimits limits);
    }

    record Request(String instrumentKey, String confirm) {
    }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalTime FROM = LocalTime.of(9, 20), UNTIL = LocalTime.of(15, 0);
    private static final String WORKING = "('CREATED', 'SUBMITTED', 'ACKNOWLEDGED', 'OPEN', 'PARTIALLY_FILLED', "
            + "'CANCEL_PENDING', 'UNKNOWN', 'RECONCILIATION_REQUIRED')";

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TradingDays tradingDays;
    private final LiveOpener opener;
    private final Path dir;
    private final Clock clock;
    private final Canary.Settings settings;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

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

    @PostMapping("/api/execution/accounts/{id}/canary")
    Map<String, Object> canary(@RequestHeader(value = Access.HEADER, required = false) String subject,
                               @PathVariable long id, @RequestBody Request body) {
        Access.User user = access.user(subject);
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
        Access.require(user.admin(), "only the platform admin runs a canary");
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
        Integer held = jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ? and status "
                + "<> 'CLOSED' and (instrument_key = ? or instrument_token = ?)", Integer.class, id, key,
                InstrumentKeys.token(key));
        require(held == null || held == 0, HttpStatus.CONFLICT, a.displayName() + " holds a position in "
                + contract.symbol() + ": the canary runs on a contract with none");
        Integer working = jdbc.queryForObject("select count(*) from exec.order_execution e join exec.trade_intent i on "
                + "i.id = e.trade_intent_id where e.broker_account_id = ? and i.instrument_token = ? and e.status in "
                + WORKING + " and e.created_at > now() - interval '1 day'", Integer.class, id, InstrumentKeys.token(key));
        require(working == null || working == 0, HttpStatus.CONFLICT, a.displayName() + " has an order of ours working "
                + "in " + contract.symbol());
        require(running.add(id), HttpStatus.CONFLICT, "a canary is already running on " + a.displayName());
        try {
            FollowerRisk.LiveLimits limits = new FollowerRisk.LiveLimits(1, risk.live().maxOpenPositions(),
                    risk.live().strategies(), risk.live().defaults());
            BrokerAdapter.Live live = opener.open(a, limits);
            require(live != null, HttpStatus.CONFLICT, "no LIVE connection for " + a.brokerType() + " yet: its adapter "
                    + "offers none");
            // merge 16 Nov 2026: a LIVE connection the adapter refuses (the static-IP gate, a denied Upstox user) has none
            require(!live.isRefused(), HttpStatus.CONFLICT, "LIVE refused: " + live.description());
            CanaryJournal journal = new CanaryJournal(dataSource, a, user.subject(), dir);
            Canary.Report report;
            try {
                report = new Canary(live.connection(), contract, settings, journal, clock).run();
            } finally {
                try {
                    live.connection().close();
                } catch (RuntimeException e) {
                    // the connection's own clean-up; the report stands
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("account", a.displayName());
            out.put("connection", live.description());
            out.putAll(CanaryJournal.view(report));
            out.put("reportFile", journal.reportFile() == null ? null : journal.reportFile().toString());
            return out;
        } finally {
            running.remove(id);
        }
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
