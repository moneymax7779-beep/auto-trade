package com.autotrade.trading;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autotrade.config.ThresholdConfig;

/**
 * The strategies this service trades (live, PAPER) and runs in shadow, each with its config file's identity,
 * scope and version notes, and its record: every session it ran in, its trades and net P&amp;L. A straddle's two
 * legs are one trade (positions opened together are grouped).
 */
@RestController
class StrategiesController {

    private final TradingProperties properties;
    private final JdbcTemplate jdbc;

    StrategiesController(TradingProperties properties, DataSource target) {
        this.properties = properties;
        this.jdbc = new JdbcTemplate(target);
    }

    @GetMapping("/api/strategies")
    Map<String, Object> strategies() {
        TradingProperties.Trading t = properties.trading();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("live", cards(t.strategyFileList(), "PAPER-1", "PAPER_LIVE"));
        out.put("shadow", cards(t.shadowStrategyFiles() == null ? List.of() : t.shadowStrategyFiles(),
                ShadowRunner.ACCOUNT, "PAPER_REPLAY"));
        out.put("featuresFile", t.featuresFile());
        out.put("riskFile", t.riskFile());
        return out;
    }

    private List<Map<String, Object>> cards(List<String> files, String account, String mode) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (String file : files) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("file", file);
            try {
                ThresholdConfig config = ThresholdConfig.load(Path.of(file));
                card.put("strategy", config.strategy());
                card.put("version", config.version());
                card.put("status", config.status().name());
                card.put("source", config.has("source") ? String.valueOf(config.get("source")) : null);
                card.put("hash", config.contentHash());
                card.put("scope", config.has("scope") ? config.getMap("scope") : Map.of());
                card.put("notes", headerNotes(Path.of(file)));
                card.put("sessions", record(config.strategy(), account, mode));
            } catch (RuntimeException | IOException e) {
                card.put("error", e.getMessage());
            }
            cards.add(card);
        }
        return cards;
    }

    /** The comment block above {@code version:}: the file's purpose and every version's change. */
    static String headerNotes(Path file) throws IOException {
        StringBuilder notes = new StringBuilder();
        for (String line : Files.readAllLines(file)) {
            String s = line.strip();
            if (!s.startsWith("#")) {
                if (s.isEmpty()) {
                    continue;
                }
                break;
            }
            notes.append(s.replaceFirst("^#\\s?", "")).append('\n');
        }
        return notes.toString().strip();
    }

    private List<Map<String, Object>> record(String strategy, String account, String mode) {
        return jdbc.queryForList("with t as (select session_id, opened_at, sum(net) net from trade.position "
                + "where strategy_id = ? group by 1, 2) "
                + "select s.id, s.session_date::text date, s.status, "
                + "(select k from jsonb_object_keys(s.configs) k where k like ? limit 1) file, "
                + "count(t.opened_at) trades, count(*) filter (where t.net > 0) wins, coalesce(sum(t.net), 0)::bigint net "
                + "from trade.session s left join t on t.session_id = s.id "
                + "where s.account = ? and s.mode = ? and '+' || s.strategy_id || '+' like ? "
                + "group by s.id order by s.session_date desc, s.id desc limit 60",
                strategy, strategy + ".v%", account, mode, "%+" + strategy + "+%");
    }
}
