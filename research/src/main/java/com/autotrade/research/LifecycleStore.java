package com.autotrade.research;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.sim.OptionPositionSimulator;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OrderIntent;

import tools.jackson.databind.json.JsonMapper;

/** Writes lifecycle frames and episodes, and enforces single use of held-out sessions. */
public final class LifecycleStore implements FrameSink, AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int BATCH = 500;

    private final DataSource dataSource;
    private final long runId;
    private final List<Object[]> frames = new ArrayList<>();

    public LifecycleStore(DataSource dataSource, long runId) {
        this.dataSource = dataSource;
        this.runId = runId;
    }

    /** Sessions of {@code sessions} already used as held-out data by any version of this strategy. */
    public static List<LocalDate> heldOutAlreadyUsed(DataSource dataSource, String strategyId, List<LocalDate> sessions)
            throws SQLException {
        List<LocalDate> used = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select session_date from research.held_out_use where strategy_id = ? and session_date = any(?)")) {
            statement.setString(1, strategyId);
            statement.setArray(2, connection.createArrayOf("date", sessions.toArray()));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    used.add(rs.getObject(1, LocalDate.class));
                }
            }
        }
        return used;
    }

    /** Records held-out use before the run reads the sessions; the primary key rejects a second use. */
    public void claimHeldOut(String strategyId, String strategyHash, List<LocalDate> sessions) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("insert into research.held_out_use "
                     + "(strategy_id, session_date, strategy_hash, run_id) values (?, ?, ?, ?)")) {
            connection.setAutoCommit(false);
            for (LocalDate session : sessions) {
                statement.setString(1, strategyId);
                statement.setObject(2, session);
                statement.setString(3, strategyHash);
                statement.setLong(4, runId);
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
        }
    }

    public static Map<LocalDate, String> splits(DataSource dataSource) throws SQLException {
        Map<LocalDate, String> splits = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select session_date, split from research.session_split order by session_date");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                splits.put(rs.getObject(1, LocalDate.class), rs.getString(2));
            }
        }
        return splits;
    }

    @Override
    public void frame(String lane, FeatureSnapshot snapshot, Decision decision) {
        StringBuilder orders = new StringBuilder();
        for (OrderIntent order : decision.orders()) {
            if (!orders.isEmpty()) {
                orders.append(' ');
            }
            orders.append(order.action()).append(':').append(order.side()).append(':').append(order.lots())
                    .append(':').append(order.reason());
        }
        frames.add(new Object[] {lane, snapshot.session(), snapshot.underlying(),
                snapshot.time().atOffset(ZoneOffset.UTC), finite(snapshot.spot()), decision.state().regime(),
                decision.ce().stage().name(), decision.pe().stage().name(), finite(decision.ce().earlyScore()),
                finite(decision.ce().confirmScore()), finite(decision.ce().runnerScore()),
                finite(decision.pe().earlyScore()), finite(decision.pe().confirmScore()),
                finite(decision.pe().runnerScore()), finite(decision.state().direction()),
                finite(decision.state().participation()), finite(decision.state().structure()),
                finite(decision.state().continuation()), orders.isEmpty() ? null : orders.toString(),
                JSON.writeValueAsString(decision.ce().conditions()), JSON.writeValueAsString(decision.pe().conditions())});
        if (frames.size() >= BATCH) {
            flushFrames();
        }
    }

    public void episodes(List<Episode> episodes) throws SQLException {
        String sql = "insert into research.episode (run_id, lane, session_date, underlying, side, symbol, entry_stage, "
                + "stages, exit_reason, tranches, max_quantity, average_cost, gross, costs, net, risk, r_multiple, "
                + "mfe_per_unit, mae_per_unit, opened_at, closed_at, legs) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Episode e : episodes) {
                int i = 1;
                statement.setLong(i++, runId);
                statement.setString(i++, e.lane());
                statement.setObject(i++, e.session());
                statement.setString(i++, e.underlying());
                statement.setString(i++, e.side());
                statement.setString(i++, e.symbol());
                statement.setString(i++, e.entryStage());
                statement.setArray(i++, connection.createArrayOf("text", e.stages().toArray()));
                statement.setString(i++, e.exitReason());
                statement.setInt(i++, e.tranches());
                statement.setLong(i++, e.maxQuantity());
                statement.setObject(i++, finite(e.averageCost()));
                statement.setDouble(i++, e.gross());
                statement.setDouble(i++, e.costs());
                statement.setDouble(i++, e.net());
                statement.setObject(i++, finite(e.risk()));
                statement.setObject(i++, finite(e.r()));
                statement.setObject(i++, finite(e.maxFavourablePerUnit()));
                statement.setObject(i++, finite(e.maxAdversePerUnit()));
                statement.setObject(i++, e.openedAt() == null ? null : e.openedAt().atOffset(ZoneOffset.UTC));
                statement.setObject(i++, e.closedAt() == null ? null : e.closedAt().atOffset(ZoneOffset.UTC));
                statement.setString(i, JSON.writeValueAsString(legs(e.legs())));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static List<Map<String, Object>> legs(List<OptionPositionSimulator.Leg> legs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OptionPositionSimulator.Leg leg : legs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("side", leg.side().name());
            m.put("tag", leg.tag());
            m.put("decided", leg.decidedAt().toString());
            m.put("filled", leg.fill().time().toString());
            m.put("price", leg.fill().price());
            m.put("quantity", leg.fill().quantity());
            m.put("touch", leg.fill().touch());
            m.put("depthExhausted", leg.fill().depthExhausted());
            m.put("costs", leg.costs().total());
            out.add(m);
        }
        return out;
    }

    private void flushFrames() {
        if (frames.isEmpty()) {
            return;
        }
        String sql = "insert into research.lifecycle_frame (run_id, lane, session_date, underlying, snap_time, spot, "
                + "regime, ce_stage, pe_stage, ce_early, ce_confirm, ce_runner, pe_early, pe_confirm, pe_runner, "
                + "direction, participation, structure, continuation, orders, ce_conditions, pe_conditions) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Object[] frame : frames) {
                statement.setLong(1, runId);
                for (int i = 0; i < frame.length; i++) {
                    statement.setObject(i + 2, frame[i]);
                }
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("frame write failed", e);
        }
        frames.clear();
    }

    @Override
    public void close() {
        flushFrames();
    }

    private static Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }
}
