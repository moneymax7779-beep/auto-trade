package com.autotrade.trading;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.risk.RiskLimits;

/**
 * The live account's equity (risk v8/v9): starting capital plus the realised net of every finished live PAPER
 * session, with the sizing it implies for the next session and the history day by day. The same arithmetic as
 * {@link EquityLedger}, which the session applies at its start.
 */
@RestController
class EquityController {

    static final String ACCOUNT = "PAPER-1";

    private final TradingProperties properties;
    private final JdbcTemplate jdbc;

    EquityController(TradingProperties properties, DataSource target) {
        this.properties = properties;
        this.jdbc = new JdbcTemplate(target);
    }

    @GetMapping("/api/equity")
    Map<String, Object> equity() {
        RiskLimits limits = RiskLimits.from(ThresholdConfig.load(Path.of(properties.trading().riskFile())));
        List<Map<String, Object>> days = new ArrayList<>();
        double start = limits.startingCapital();
        double running = start;
        for (Map<String, Object> r : jdbc.queryForList("select session_date, coalesce(sum((summary->>'net')::numeric), 0) net "
                + "from trade.session where account = ? and mode = 'PAPER_LIVE' and status in ('DONE', 'STOPPED') and summary ?? 'net' "
                + "group by 1 order by 1", ACCOUNT)) {
            double net = ((Number) r.get("net")).doubleValue();
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", ((java.sql.Date) r.get("session_date")).toLocalDate().toString());
            day.put("equityBefore", Math.round(running));
            day.put("net", Math.round(net));
            running += net;
            day.put("equityAfter", Math.round(running));
            days.add(day);
        }
        RiskLimits next = EquityLedger.apply(limits, running - start);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", ACCOUNT);
        out.put("riskVersion", limits.hash() == null ? null : riskVersion());
        out.put("equityMode", limits.equityMode());
        out.put("startingCapital", Math.round(start));
        out.put("realisedNet", Math.round(running - start));
        out.put("equity", Math.round(next.capital()));
        out.put("budgetScale", Math.round(next.budgetScale() * 1000) / 1000.0);
        out.put("dailyLossLimit", Math.round(next.dailyLossLimit()));
        out.put("asOf", LocalDate.now(com.autotrade.core.time.MarketTime.IST).toString());
        out.put("days", days);
        return out;
    }

    private String riskVersion() {
        ThresholdConfig c = ThresholdConfig.load(Path.of(properties.trading().riskFile()));
        return c.version();
    }
}
