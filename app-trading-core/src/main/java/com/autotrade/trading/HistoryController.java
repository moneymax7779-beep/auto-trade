package com.autotrade.trading;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autotrade.config.ThresholdConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only history for the UI: trading sessions, research runs, feature snapshots and config
 * files. Times are returned as IST wall-clock strings; JSON columns as objects.
 */
@RestController
@RequestMapping("/api")
class HistoryController {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> JSON_COLUMNS = Set.of("summary", "configs", "ce_scores", "pe_scores", "state",
            "ce_conditions", "pe_conditions", "config", "notes", "features", "legs", "entry_context", "entry_features",
            "exit_features");
    private static final String IST = "Asia/Kolkata";
    private final SessionRunner runner;
    private static final String SESSION_COLUMNS = "select id, account, mode, session_date::text, feed, strategy_id, "
            + "left(strategy_hash, 19) strategy_hash, code_version, " + ist("started_at") + ", " + ist("ended_at") + ", status, "
            + "summary::text summary, error, configs::text configs from trade.session ";
    /** A run's config identity: the hash of its config files (risk, features, every strategy version). */
    private static final String CONFIG_KEY = "md5(coalesce(configs::text, ''))";
    /** The data a session ran on: "bars" (Upstox one-minute bars, coarse) or "ticks" (live, zt or own tick capture). */
    private static final String DATA_KIND = "case when feed like '%bars%' then 'bars' else 'ticks' end";
    /** Most sessions a filtered list returns, and most runs the run list returns. */
    static final int MAX_FILTERED_SESSIONS = 2000;
    static final int MAX_RUNS = 500;

    private final JdbcTemplate jdbc;

    /** @param runner the trading service's session runner (null in tests: no running session is consulted) */
    HistoryController(DataSource dataSource, SessionRunner runner) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.runner = runner;
    }

    /**
     * The sessions list. Without filters: every live session (the charts' day list) plus the newest 200 of any kind (replays
     * pile up). With the Sessions page's run or strategy filter ({@code code}, {@code strategy}, {@code configs} = the
     * config key of {@link #sessionRuns}, {@code mode}, {@code data} = bars / ticks; blank = absent): every matching session,
     * newest first, at most {@link #MAX_FILTERED_SESSIONS}.
     */
    /** The Sessions page's optional filters, bound from the query string; all null = no filter. */
    record SessionFilter(String code, String strategy, String configs, String mode, String data) {
        boolean isEmpty() {
            return blank(code) && blank(strategy) && blank(configs) && blank(mode) && blank(data);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    @GetMapping("/sessions")
    List<Map<String, Object>> sessions(SessionFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return query(SESSION_COLUMNS + "where mode = 'PAPER_LIVE' or id > (select coalesce(max(id), 0) - 200 from trade.session) "
                    + "order by id desc");
        }
        StringBuilder where = new StringBuilder("where true");
        List<Object> args = new ArrayList<>();
        condition(where, args, "code_version", filter.code());
        condition(where, args, "strategy_id", filter.strategy());
        condition(where, args, CONFIG_KEY, filter.configs());
        condition(where, args, "mode", filter.mode());
        condition(where, args, DATA_KIND, filter.data());
        args.add(MAX_FILTERED_SESSIONS);
        return query(SESSION_COLUMNS + where + " order by id desc limit ?", args.toArray());
    }

    /** Appends {@code and <expression> = ?} when the value is given (the expression is a constant, never user input). */
    private static void condition(StringBuilder where, List<Object> args, String expression, String value) {
        if (!SessionFilter.blank(value)) {
            where.append(" and ").append(expression).append(" = ?");
            args.add(value);
        }
    }

    /**
     * Every run, newest first (at most {@link #MAX_RUNS}), for the Sessions page's dropdowns: the sessions with the same mode, code version,
     * strategy set, config files and data kind (bars or ticks; a replay batch's zt days and own-capture days are one run). A day
     * replayed twice counts once, with its latest session's net.
     */
    @GetMapping("/sessions/runs")
    List<Map<String, Object>> sessionRuns() {
        return query("with s as (select id, mode, code_version, strategy_id, configs, " + CONFIG_KEY + " config_key, "
                + DATA_KIND + " data, session_date, feed, started_at, (summary ->> 'net')::numeric net from trade.session), "
                + "latest as (select distinct on (mode, code_version, strategy_id, config_key, data, session_date) * from s "
                + "order by mode, code_version, strategy_id, config_key, data, session_date, id desc), "
                + "counts as (select mode, code_version, strategy_id, config_key, data, count(*) sessions, max(id) last_id, "
                + "max(started_at) last_started from s group by 1, 2, 3, 4, 5) "
                + "select l.mode, l.code_version, l.strategy_id, l.config_key, l.data, "
                + "(array_agg(l.configs::text order by l.id desc))[1] configs, string_agg(distinct l.feed, ',') feeds, count(*) days, "
                + "c.sessions, min(l.session_date)::text date_from, max(l.session_date)::text date_to, coalesce(sum(l.net), 0) net, "
                + "to_char(c.last_started at time zone '" + IST + "', 'YYYY-MM-DD HH24:MI:SS') last_started "
                + "from latest l join counts c using (mode, code_version, strategy_id, config_key, data) "
                + "group by l.mode, l.code_version, l.strategy_id, l.config_key, l.data, c.sessions, c.last_id, c.last_started "
                + "order by c.last_id desc limit ?", MAX_RUNS);
    }

    /** One underlying's decisions; {@code strategy} selects one strategy when several traded (default: all). */
    @GetMapping("/sessions/{id}/decisions")
    List<Map<String, Object>> decisions(@PathVariable long id, @RequestParam String underlying,
                                        @RequestParam(required = false) String strategy) {
        return query("select to_char(snap_time at time zone '" + IST + "', 'HH24:MI') t, strategy_id, spot, ce_stage, "
                + "pe_stage, ce_scores::text ce_scores, pe_scores::text pe_scores, state::text state, orders "
                + "from trade.decision where session_id = ? and underlying = ? and (?::text is null or strategy_id = ?) "
                + "order by snap_time, strategy_id", id, underlying, strategy, strategy);
    }

    /** The last decision of every underlying and strategy in the session: where each ended the day. */
    @GetMapping("/sessions/{id}/last-decisions")
    List<Map<String, Object>> lastDecisions(@PathVariable long id) {
        return query("select distinct on (underlying, strategy_id) underlying, strategy_id, to_char(snap_time at time zone '"
                + IST + "', 'HH24:MI') t, spot, ce_stage, pe_stage, ce_scores::text ce_scores, pe_scores::text pe_scores, "
                + "state::text state, orders from trade.decision where session_id = ? "
                + "order by underlying, strategy_id, snap_time desc", id);
    }

    @GetMapping("/sessions/{id}/orders")
    List<Map<String, Object>> orders(@PathVariable long id) {
        return query("select o.client_order_id, o.strategy_id, o.underlying, o.option_side, o.role, o.order_side, "
                + "o.order_type, o.symbol, "
                + "o.quantity, o.limit_price, o.trigger_price, " + ist("o.sent_at") + ", e.status, e.filled, "
                + "e.average_price from trade.orders o left join lateral (select status, filled, average_price "
                + "from trade.order_event where client_order_id = o.client_order_id order by id desc limit 1) e on true "
                + "where o.session_id = ? order by o.sent_at", id);
    }

    @GetMapping("/sessions/{id}/positions")
    List<Map<String, Object>> positions(@PathVariable long id) {
        return query("select id, strategy_id, underlying, option_side, symbol, " + ist("opened_at") + ", " + ist("closed_at")
                + ", stages, exit_reason, realised, costs, net, quantity, lot_size, average_cost, average_exit "
                + "from trade.position where session_id = ? order by opened_at", id);
    }

    /**
     * The feature snapshot run of a session: LIVE_FEATURES for a live session, REPLAY_FEATURES for a replay started with
     * replay-snapshots, the run of its day that started nearest the session (none: no snapshots were kept).
     */
    /**
     * The run holding the session's feature snapshots: the one the session recorded (V28, 5 Nov 2026); for a session
     * before that (or one that kept none), the run of its kind and date started nearest to it within 5 minutes and not
     * recorded by any other session, so a replay without snapshots never borrows another replay's.
     */
    private static final String SNAPSHOT_RUN = "(select coalesce(s.snapshot_run_id, (select r.id from research.run r "
            + "where r.kind = case when s.mode = 'PAPER_LIVE' then 'LIVE_FEATURES' else 'REPLAY_FEATURES' end "
            + "and s.session_date = any(r.sessions) and abs(extract(epoch from r.started_at - s.started_at)) < 300 "
            + "and not exists (select 1 from trade.session o where o.snapshot_run_id = r.id and o.id <> s.id) "
            + "order by abs(extract(epoch from r.started_at - s.started_at)) limit 1)) from trade.session s where s.id = ?)";

    /**
     * Each trade of the session with what was known at its entry and its exit (user, 5 Nov 2026: "add entry point and exit
     * point in the chart to see what data it holds for better tuning on the strategies or deriving new strategy"): the
     * strategy's own entry context (the features and conditions it decided on, from trade.candidate), and the full feature
     * snapshot at the entry and exit minutes where the session kept snapshots (live sessions; replays with
     * replay-snapshots).
     */
    @GetMapping("/sessions/{id}/trade-context")
    List<Map<String, Object>> tradeContext(@PathVariable long id) {
        return query("select p.strategy_id, p.underlying, p.option_side, p.symbol, " + ist("p.opened_at") + ", "
                + ist("p.closed_at") + ", p.exit_reason, p.quantity, p.average_cost, p.average_exit, p.net, "
                + "c.reason entry_reason, c.context::text entry_context, "
                + "(select f.features::text from feat.snapshot f where f.run_id = " + SNAPSHOT_RUN + " and f.underlying = "
                + "p.underlying and f.snap_time <= p.opened_at order by f.snap_time desc limit 1) entry_features, "
                + "(select f.features::text from feat.snapshot f where f.run_id = " + SNAPSHOT_RUN + " and f.underlying = "
                + "p.underlying and f.snap_time <= p.closed_at order by f.snap_time desc limit 1) exit_features "
                + "from trade.position p left join lateral (select reason, context from trade.candidate c "
                + "where c.session_id = p.session_id and c.underlying = p.underlying and c.side = p.option_side "
                + "and c.approved and c.action = 'ENTER' and c.at <= p.opened_at "
                + "and (p.strategy_id is null or c.strategy_id = p.strategy_id) order by c.at desc limit 1) c on true "
                + "where p.session_id = ? and p.quantity > 0 "
                // a position is stored when it closes: in the running session an approved entry without one is a trade
                // still open (a finished session has none open: such a candidate never became a trade)
                + "union all select c.strategy_id, c.underlying, c.side, null, " + ist("c.at")
                + ", null, null, null, null, null, null, c.reason, c.context::text, "
                + "(select f.features::text from feat.snapshot f where f.run_id = " + SNAPSHOT_RUN + " and f.underlying = "
                + "c.underlying and f.snap_time <= c.at order by f.snap_time desc limit 1), null "
                + "from trade.candidate c where c.session_id = ? and ? and c.approved and c.action = 'ENTER' and not exists "
                + "(select 1 from trade.position p where p.session_id = c.session_id and p.underlying = c.underlying "
                + "and p.option_side = c.side and (p.strategy_id is null or p.strategy_id = c.strategy_id) "
                + "and p.opened_at >= c.at and p.opened_at < c.at + interval '5 minutes') "
                + "order by 5", id, id, id, id, id, isRunning(id));
    }

    /** Whether {@code id} is the session running now (its open positions are not stored yet). */
    private boolean isRunning(long id) {
        TradingSession running = runner == null ? null : runner.current();
        return running != null && Long.valueOf(id).equals(((Number) running.status().getOrDefault("session", -1L)).longValue());
    }

    /** A malformed {@code time} is the caller's error (400), not the server's. */
    @org.springframework.web.bind.annotation.ExceptionHandler(java.time.format.DateTimeParseException.class)
    org.springframework.http.ResponseEntity<Map<String, Object>> badTime(java.time.format.DateTimeParseException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", "bad time: " + e.getParsedString()));
    }

    /**
     * The session as of one moment on one index (the chart's pinned minute; a paused replay's clock): each strategy's
     * last decision by then, the feature snapshot by then (when kept), the positions open then marked at the option's last
     * traded price by then (own capture), and the net of those closed by then.
     */
    @GetMapping("/sessions/{id}/at")
    Map<String, Object> at(@PathVariable long id, @RequestParam String underlying, @RequestParam String time) {
        java.time.LocalTime t = java.time.LocalTime.parse(time);
        List<Map<String, Object>> session = query("select session_date::text d from trade.session where id = ?", id);
        if (session.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        java.time.LocalDate day = java.time.LocalDate.parse((String) session.getFirst().get("d"));
        java.sql.Timestamp at = java.sql.Timestamp.from(day.atTime(t).atZone(com.autotrade.core.time.MarketTime.IST).toInstant());
        java.sql.Timestamp from = java.sql.Timestamp.from(at.toInstant().minusSeconds(900));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("time", t.toString());
        out.put("underlying", underlying);
        out.put("decisions", query("select distinct on (strategy_id) strategy_id, to_char(snap_time at time zone '" + IST
                + "', 'HH24:MI') t, spot, ce_stage, pe_stage, ce_scores::text ce_scores, pe_scores::text pe_scores, "
                + "state::text state, orders from trade.decision where session_id = ? and underlying = ? and snap_time <= ? "
                + "order by strategy_id, snap_time desc", id, underlying, at));
        List<Map<String, Object>> snap = query("select to_char(snap_time at time zone '" + IST + "', 'HH24:MI') t, phase, spot, "
                + "features::text features from feat.snapshot where run_id = " + SNAPSHOT_RUN + " and underlying = ? "
                + "and snap_time <= ? order by snap_time desc limit 1", id, underlying, at);
        out.put("snapshot", snap.isEmpty() ? null : snap.getFirst());
        String mark = "(select o.ltp from md.option_tick o where o.session_date = ?::date and o.symbol = ? "
                + "and o.recv_ts > ? and o.recv_ts <= ? order by o.recv_ts desc limit 1)";
        List<Map<String, Object>> open = query("select p.strategy_id, p.option_side, p.symbol, " + ist("p.opened_at") + ", "
                + "p.quantity, p.average_cost, " + mark.replace("o.symbol = ?", "o.symbol = p.symbol") + " mark "
                + "from trade.position p where p.session_id = ? and p.underlying = ? and p.quantity > 0 and p.opened_at <= ? "
                + "and (p.closed_at is null or p.closed_at > ?) order by p.opened_at", day.toString(), from, at, id, underlying,
                at, at);
        // positions the running session holds are stored only when they close: add those opened by then
        TradingSession running = runner == null ? null : runner.current();
        if (running != null && isRunning(id)) {
            Object rows = running.status().get("openPositions");
            if (rows instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> p && underlying.equals(p.get("underlying")) && p.get("opened") != null
                            && !java.time.LocalTime.parse(p.get("opened").toString()).isAfter(t)) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("strategy_id", p.get("strategy"));
                        row.put("option_side", p.get("side"));
                        row.put("symbol", p.get("symbol"));
                        row.put("opened_at", day + " " + p.get("opened"));
                        row.put("quantity", p.get("boughtQuantity"));        // as the stored rows: the quantity bought
                        row.put("average_cost", p.get("averageCost"));
                        row.put("mark", jdbc.queryForList("select " + mark, Double.class, day.toString(), p.get("symbol"), from, at)
                                .stream().findFirst().orElse(null));
                        open.add(row);
                    }
                }
            }
        }
        out.put("open", open);
        out.put("closedNet", jdbc.queryForObject("select coalesce(sum(net), 0) from trade.position where session_id = ? "
                + "and underlying = ? and closed_at <= ?", Double.class, id, underlying, at));
        return out;
    }

    /**
     * Every closed position of the sessions of one mode (default: the live PAPER sessions), with its day and
     * contract, for the Performance page: win/loss counts, premium points, the red/green calendar. Small enough
     * to aggregate in the browser, where the symbol and strategy toggles live.
     */
    @GetMapping("/performance")
    List<Map<String, Object>> performance(@RequestParam(defaultValue = "PAPER_LIVE") String mode) {
        return query("select s.id session_id, s.session_date::text session_date, p.strategy_id, p.underlying, p.option_side, "
                + "p.symbol, " + ist("p.opened_at") + ", " + ist("p.closed_at") + ", p.exit_reason, p.realised, p.costs, p.net, "
                + "p.quantity, p.lot_size, p.average_cost, p.average_exit from trade.position p join trade.session s on s.id = p.session_id "
                + "where s.mode = ? and p.closed_at is not null and (s.mode <> 'PAPER_LIVE' or s.account not like 'SIGNAL-%') "
                + "order by s.session_date, p.opened_at", mode);     // the live account only, never the signal source too
    }

    @GetMapping("/sessions/{id}/rejections")
    List<Map<String, Object>> rejections(@PathVariable long id) {
        return query("select strategy_id, underlying, intent, reason, " + ist("at") + " from trade.rejection where session_id = ? "
                + "order by id", id);
    }

    @GetMapping("/runs")
    List<Map<String, Object>> runs() {
        return query("select id, kind, status, " + ist("started_at") + ", " + ist("finished_at") + ", code_version, source, "
                + "sessions::text sessions, underlyings::text underlyings, config::text config, notes::text notes, error "
                + "from research.run order by id desc limit 200");
    }

    @GetMapping("/runs/{id}/episodes")
    List<Map<String, Object>> episodes(@PathVariable long id) {
        return query("select lane, session_date::text, underlying, side, symbol, entry_stage, stages, exit_reason, "
                + "tranches, max_quantity, average_cost, gross, costs, net, risk, r_multiple, mfe_per_unit, mae_per_unit, "
                + ist("opened_at") + ", " + ist("closed_at") + " from research.episode where run_id = ? "
                + "order by lane, session_date, opened_at", id);
    }

    @GetMapping("/runs/{id}/frames")
    List<Map<String, Object>> frames(@PathVariable long id, @RequestParam String session, @RequestParam String underlying,
                                     @RequestParam(defaultValue = "base") String lane) {
        return query("select to_char(snap_time at time zone '" + IST + "', 'HH24:MI') t, spot, regime, ce_stage, pe_stage, "
                + "ce_early, ce_confirm, ce_runner, pe_early, pe_confirm, pe_runner, direction, participation, structure, "
                + "continuation, orders from research.lifecycle_frame where run_id = ? and lane = ? "
                + "and session_date = ?::date and underlying = ? order by snap_time", id, lane, session, underlying);
    }

    /** Sessions and underlyings with stored feature snapshots (for the replay scrubber). */
    @GetMapping("/snapshots/sessions")
    List<Map<String, Object>> snapshotSessions() {
        return query("select session_date::text, underlying, count(*) snapshots, max(run_id) run_id from feat.snapshot "
                + "group by 1, 2 order by 1 desc, 2");
    }

    @GetMapping("/snapshots")
    List<Map<String, Object>> snapshots(@RequestParam String session, @RequestParam String underlying) {
        return query("select to_char(snap_time at time zone '" + IST + "', 'HH24:MI') t, phase, spot, "
                + "(features ->> 'structure.orHigh')::float or_high, (features ->> 'structure.orLow')::float or_low, "
                + "(features ->> 'structure.vwapSpotProxy')::float vwap, (features ->> 'structure.ema20')::float ema20 "
                + "from feat.snapshot where run_id = (select max(run_id) from feat.snapshot where session_date = ?::date "
                + "and underlying = ?) and underlying = ? order by snap_time", session, underlying, underlying);
    }

    @GetMapping("/snapshots/at")
    ResponseEntity<Map<String, Object>> snapshotAt(@RequestParam String session, @RequestParam String underlying,
                                                   @RequestParam String time) {
        List<Map<String, Object>> rows = query("select to_char(snap_time at time zone '" + IST + "', 'HH24:MI') t, phase, "
                + "spot, features::text features from feat.snapshot where run_id = (select max(run_id) from feat.snapshot "
                + "where session_date = ?::date and underlying = ?) and underlying = ? "
                + "and to_char(snap_time at time zone '" + IST + "', 'HH24:MI') = ?", session, underlying, underlying, time);
        return rows.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(rows.getFirst());
    }

    /** Every versioned config file with its identity, status and content hash (invalid files are reported). */
    @GetMapping("/configs")
    List<Map<String, Object>> configs() throws IOException {
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Path.of("config"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("file", path.toString());
                try {
                    ThresholdConfig config = ThresholdConfig.load(path);
                    row.put("id", config.id());
                    row.put("version", config.version());
                    row.put("status", config.status().name());
                    row.put("hash", config.contentHash());
                } catch (RuntimeException e) {
                    row.put("error", e.getMessage());
                }
                files.add(row);
            }
        }
        return files;
    }

    @GetMapping("/configs/content")
    ResponseEntity<Map<String, Object>> configContent(@RequestParam String file) throws IOException {
        Path root = Path.of("config").toAbsolutePath().normalize();
        Path path = Path.of(file).toAbsolutePath().normalize();
        if (!path.startsWith(root) || !path.toString().endsWith(".yaml") || !Files.isRegularFile(path)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(Map.of("file", file, "text", Files.readString(path)));
    }

    private List<Map<String, Object>> query(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        for (Map<String, Object> row : rows) {
            for (String column : JSON_COLUMNS) {
                if (row.get(column) instanceof String text) {
                    row.put(column, JSON.readTree(text));
                }
            }
            row.replaceAll((key, value) -> value instanceof java.sql.Array array ? arrayValues(array) : value);
        }
        return rows;
    }

    private static Object arrayValues(java.sql.Array array) {
        try {
            return List.of((Object[]) array.getArray());
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    private static String ist(String column) {
        String name = column.contains(".") ? column.substring(column.indexOf('.') + 1) : column;
        return "to_char(" + column + " at time zone '" + IST + "', 'YYYY-MM-DD HH24:MI:SS') " + name;
    }
}
