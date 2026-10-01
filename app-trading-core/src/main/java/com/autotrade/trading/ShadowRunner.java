package com.autotrade.trading;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.build.CodeVersion;
import com.autotrade.core.history.CombinedSessionHistory;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.instruments.InstrumentStore;
import com.autotrade.md.live.ReplayAsLiveFeed;
import com.autotrade.md.store.OwnSessionHistory;
import com.autotrade.md.store.ReferenceStore;
import com.autotrade.md.zt.ZtReadOnlyDataSource;
import com.autotrade.md.zt.ZtSessionHistory;
import com.autotrade.risk.RiskLimits;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.FillModel;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.StrategyFactory;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Shadow mode: candidate strategies run on a finished live day's own capture (the same ticks, books and
 * auction data the live session received, so the decisions are the ones they would have made live), on a
 * separate account (SHADOW-1) with the live risk, costs and fills. No order reaches any broker; the result is
 * stored like any replay and summarised for the alerts. Never touches the live session.
 */
final class ShadowRunner {

    static final String ACCOUNT = "SHADOW-1";
    private static final Logger log = LoggerFactory.getLogger(ShadowRunner.class);

    private final TradingProperties properties;
    private final DataSource target;

    ShadowRunner(TradingProperties properties, DataSource target) {
        this.properties = properties;
        this.target = target;
    }

    /** Runs the shadow strategies on {@code session}; returns the summary text, or null when there is nothing to run. */
    String run(LocalDate session) throws Exception {
        TradingProperties.Trading t = properties.trading();
        List<String> files = t.shadowStrategyFiles();
        if (files == null || files.isEmpty()) {
            return null;
        }
        List<ThresholdConfig> strategyFiles = files.stream().map(file -> ThresholdConfig.load(Path.of(file))).toList();
        ThresholdConfig featuresFile = ThresholdConfig.load(Path.of(t.featuresFile()));
        ThresholdConfig exchangeFile = ThresholdConfig.load(Path.of(t.exchangeFile()));
        ThresholdConfig costsFile = ThresholdConfig.load(Path.of(t.costsFile()));
        ThresholdConfig riskFile = ThresholdConfig.load(Path.of(t.riskFile()));
        FeatureConfig features = FeatureConfig.from(featuresFile, exchangeFile);
        List<StrategyFactory> strategies = new ArrayList<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (ThresholdConfig file : strategyFiles) {
            strategies.add(Strategies.create(file));
        }
        List<ThresholdConfig> all = new ArrayList<>(strategyFiles);
        all.addAll(List.of(featuresFile, exchangeFile, costsFile, riskFile));
        for (ThresholdConfig file : all) {
            hashes.put(file.sourceName(), file.contentHash());
        }
        TradingProperties.Source s = properties.source();
        try (HikariDataSource zt = ZtReadOnlyDataSource.open(new ZtReadOnlyDataSource.Settings(s.url(), s.username(),
                s.password(), s.passwordFile(), s.passwordKey(), "autotrade-shadow", 2))) {
            ReferenceStore reference = new ReferenceStore(target);
            SessionHistory history = new CombinedSessionHistory(new ZtSessionHistory(zt), new OwnSessionHistory(target),
                    reference);
            TradingSession.Settings settings = new TradingSession.Settings(TradingSession.Mode.PAPER_REPLAY, ACCOUNT,
                    session, t.underlyings(), t.tradeUnderlyingList(), features, history, strategies,
                    RiskLimits.from(riskFile), CostModel.from(costsFile), FillModel.from(costsFile, t.fillModel()),
                    new InstrumentStore(target).latest(session), hashes, CodeVersion.current());
            TradingSession shadow = new TradingSession(settings,
                    new ReplayAsLiveFeed(new OwnCaptureSource(target), session, t.underlyings(), 0), new TradeStore(target));
            Map<String, Object> result = shadow.run();
            log.info("shadow {} on {}: {}", files, session, result);
            return summary(session, files, shadow.status().get("session"), result);
        }
    }

    private String summary(LocalDate session, List<String> files, Object sessionId, Map<String, Object> result) {
        JdbcTemplate jdbc = new JdbcTemplate(target);
        StringBuilder text = new StringBuilder("Shadow " + session + " (" + String.join(", ",
                files.stream().map(f -> Path.of(f).getFileName().toString()).toList()) + "), session " + sessionId + ":\n");
        jdbc.query("select strategy_id, symbol, to_char(opened_at at time zone 'Asia/Kolkata', 'HH24:MI') o, "
                + "to_char(closed_at at time zone 'Asia/Kolkata', 'HH24:MI') c, quantity, lot_size, average_cost, "
                + "average_exit, exit_reason, net from trade.position where session_id = ? and quantity > 0 order by opened_at",
                rs -> {
                    text.append(String.format("• %s %s %s–%s %d lots %.2f → %.2f %s ₹%,.0f%n", rs.getString("strategy_id"),
                            rs.getString("symbol"), rs.getString("o"), rs.getString("c"),
                            rs.getLong("quantity") / Math.max(1, rs.getInt("lot_size")), rs.getDouble("average_cost"),
                            rs.getDouble("average_exit"), rs.getString("exit_reason"), rs.getDouble("net")));
                }, sessionId);
        Object net = result.get("net");
        Long live = jdbc.query("select (summary->>'net')::bigint from trade.session where mode = 'PAPER_LIVE' "
                + "and session_date = ? order by id desc limit 1", rs -> rs.next() ? rs.getLong(1) : null, session);
        text.append("Shadow net ₹").append(net).append(" · live net ₹").append(live == null ? "–" : live);
        return text.toString();
    }
}
