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
            "ce_conditions", "pe_conditions", "config", "notes", "features", "legs");
    private static final String IST = "Asia/Kolkata";

    private final JdbcTemplate jdbc;

    HistoryController(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @GetMapping("/sessions")
    List<Map<String, Object>> sessions() {
        return query("select id, account, mode, session_date::text, feed, strategy_id, left(strategy_hash, 19) strategy_hash, "
                + "code_version, " + ist("started_at") + ", " + ist("ended_at") + ", status, summary::text summary, error, "
                + "configs::text configs "
                // every live session (the charts' day list) plus the newest 200 of any kind (replays pile up)
                + "from trade.session where mode = 'PAPER_LIVE' or id > (select coalesce(max(id), 0) - 200 from trade.session) "
                + "order by id desc");
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
        return query("select strategy_id, underlying, option_side, symbol, " + ist("opened_at") + ", " + ist("closed_at")
                + ", stages, exit_reason, realised, costs, net, quantity, lot_size, average_cost, average_exit "
                + "from trade.position where session_id = ? order by opened_at", id);
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
