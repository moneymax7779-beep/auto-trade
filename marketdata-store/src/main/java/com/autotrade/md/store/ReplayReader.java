package com.autotrade.md.store;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import javax.sql.DataSource;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionPhaseEvent;

/**
 * Streams the stored events of one or more manifests in replay order: receipt time, then underlying,
 * then source sequence, then event kind. Each table is read by its own ordered cursor and the cursors
 * are merged, so memory stays constant regardless of session size.
 */
public final class ReplayReader {

    /** The replay order. Every table cursor is sorted consistently with it. */
    public static final Comparator<MarketEvent> ORDER = Comparator
            .comparing(MarketEvent::receivedAt)
            .thenComparing(MarketEvent::underlying)
            .thenComparingLong(MarketEvent::sourceSequence)
            .thenComparing(MarketEvent::kind);

    private static final int FETCH_SIZE = 5_000;

    private final DataSource dataSource;

    public ReplayReader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Replays the given manifests into {@code sink}; returns the number of events delivered. */
    public long replay(List<Long> manifestIds, Consumer<MarketEvent> sink) throws SQLException {
        List<Cursor> cursors = new ArrayList<>();
        try {
            for (Source source : Source.values()) {
                cursors.add(new Cursor(dataSource.getConnection(), source, manifestIds));
            }
        } catch (SQLException e) {
            cursors.forEach(Cursor::close);
            throw e;
        }
        try {
            return EventStreams.merge(cursors, sink);
        } catch (SQLException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    private enum Source {
        INDEX("select underlying, recv_ts, exch_ts, src_seq, price, prev_close, atm_strike from md.index_tick"),
        FUTURE("select underlying, recv_ts, exch_ts, src_seq, instrument_token, symbol, expiry, price, volume, oi, "
                + "session_vwap, total_buy_qty, total_sell_qty, bid_px, bid_qty, bid_orders, ask_px, ask_qty, ask_orders "
                + "from md.future_tick"),
        OPTION("select underlying, recv_ts, exch_ts, src_seq, instrument_token, symbol, expiry, strike, option_type, "
                + "lot_size, ltp, volume, oi, total_buy_qty, total_sell_qty, bid_px, bid_qty, bid_orders, ask_px, "
                + "ask_qty, ask_orders, iv, delta, gamma, theta, vega, rho, analytics_ts, analytics_complete, "
                + "depth_complete from md.option_tick"),
        CONSTITUENT("select underlying, recv_ts, exch_ts, src_seq, instrument_token, symbol, price, prev_close, "
                + "cum_volume, session_vwap, weight_pct from md.constituent_tick"),
        SESSION_PHASE("select underlying, recv_ts, event_ts, 0::bigint as src_seq, session_phase, price_semantics, "
                + "price, official_final from md.session_phase_event"),
        AUCTION("select underlying, recv_ts, exch_ts, 0::bigint as src_seq, instrument_token, symbol, iep, ref_price, "
                + "eq_qty, imbalance_total, imbalance_market, cas_eligible from md.auction_tick");

        private final String select;

        Source(String select) {
            this.select = select;
        }

        String sql() {
            // collate "C" makes the database's underlying order match Java's String ordering.
            return select + " where manifest_id = any(?) order by recv_ts, underlying collate \"C\", src_seq";
        }
    }

    private static final class Cursor implements EventCursor {

        private final Connection connection;
        private final PreparedStatement statement;
        private final ResultSet rows;
        private final Source source;
        private MarketEvent head;

        Cursor(Connection connection, Source source, List<Long> manifestIds) throws SQLException {
            this.connection = connection;
            this.source = source;
            PreparedStatement prepared = null;
            try {
                connection.setAutoCommit(false);
                connection.setReadOnly(true);
                prepared = connection.prepareStatement(source.sql());
                prepared.setFetchSize(FETCH_SIZE);
                prepared.setArray(1, connection.createArrayOf("bigint", manifestIds.toArray()));
                rows = prepared.executeQuery();
                statement = prepared;
            } catch (SQLException e) {
                if (prepared != null) {
                    prepared.close();
                }
                connection.close();
                throw e;
            }
        }

        @Override
        public MarketEvent head() {
            return head;
        }

        @Override
        public boolean advance() throws SQLException {
            if (!rows.next()) {
                head = null;
                return false;
            }
            head = map();
            return true;
        }

        private MarketEvent map() throws SQLException {
            String underlying = rows.getString("underlying");
            Instant received = instant("recv_ts");
            long sequence = rows.getLong("src_seq");
            return switch (source) {
                case INDEX -> new IndexTick(received, instant("exch_ts"), underlying, sequence,
                        rows.getDouble("price"), nullableDouble("prev_close"), nullableInt("atm_strike"));
                case FUTURE -> new FutureTick(received, instant("exch_ts"), underlying, sequence,
                        rows.getLong("instrument_token"), rows.getString("symbol"), date("expiry"),
                        rows.getDouble("price"), nullableLong("volume"), nullableDouble("oi"),
                        nullableDouble("session_vwap"), nullableDouble("total_buy_qty"),
                        nullableDouble("total_sell_qty"),
                        depth("bid_px", "bid_qty", "bid_orders"), depth("ask_px", "ask_qty", "ask_orders"));
                case OPTION -> new OptionTick(received, instant("exch_ts"), underlying, sequence,
                        rows.getLong("instrument_token"), rows.getString("symbol"), date("expiry"),
                        rows.getDouble("strike"), OptionTick.OptionType.valueOf(rows.getString("option_type")),
                        nullableInt("lot_size"), rows.getDouble("ltp"), nullableLong("volume"), nullableDouble("oi"),
                        nullableDouble("total_buy_qty"), nullableDouble("total_sell_qty"),
                        depth("bid_px", "bid_qty", "bid_orders"), depth("ask_px", "ask_qty", "ask_orders"),
                        nullableDouble("iv"), nullableDouble("delta"), nullableDouble("gamma"),
                        nullableDouble("theta"), nullableDouble("vega"), nullableDouble("rho"),
                        instant("analytics_ts"), rows.getBoolean("analytics_complete"),
                        rows.getBoolean("depth_complete"));
                case CONSTITUENT -> new ConstituentTick(received, instant("exch_ts"), underlying, sequence,
                        rows.getLong("instrument_token"), rows.getString("symbol"), rows.getDouble("price"),
                        nullableDouble("prev_close"), nullableLong("cum_volume"), nullableDouble("session_vwap"),
                        nullableDouble("weight_pct"));
                case SESSION_PHASE -> new SessionPhaseEvent(received, instant("event_ts"), underlying,
                        rows.getString("session_phase"), rows.getString("price_semantics"), rows.getDouble("price"),
                        rows.getBoolean("official_final"));
                case AUCTION -> new AuctionTick(received, instant("exch_ts"), underlying, rows.getLong("instrument_token"),
                        rows.getString("symbol"), rows.getDouble("iep"), rows.getDouble("ref_price"),
                        rows.getLong("eq_qty"), rows.getLong("imbalance_total"), rows.getLong("imbalance_market"),
                        rows.getBoolean("cas_eligible"));
            };
        }

        private Instant instant(String column) throws SQLException {
            OffsetDateTime value = rows.getObject(column, OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        }

        private LocalDate date(String column) throws SQLException {
            return rows.getObject(column, LocalDate.class);
        }

        private Double nullableDouble(String column) throws SQLException {
            double value = rows.getDouble(column);
            return rows.wasNull() ? null : value;
        }

        private Long nullableLong(String column) throws SQLException {
            long value = rows.getLong(column);
            return rows.wasNull() ? null : value;
        }

        private Integer nullableInt(String column) throws SQLException {
            int value = rows.getInt(column);
            return rows.wasNull() ? null : value;
        }

        private DepthLevels depth(String prices, String quantities, String orders) throws SQLException {
            Object[] p = arrayValues(prices);
            Object[] q = arrayValues(quantities);
            Object[] o = arrayValues(orders);
            double[] priceValues = new double[p.length];
            long[] quantityValues = new long[q.length];
            int[] orderValues = new int[o.length];
            for (int i = 0; i < p.length; i++) {
                priceValues[i] = ((Number) p[i]).doubleValue();
            }
            for (int i = 0; i < q.length; i++) {
                quantityValues[i] = ((Number) q[i]).longValue();
            }
            for (int i = 0; i < o.length; i++) {
                orderValues[i] = ((Number) o[i]).intValue();
            }
            return DepthLevels.of(priceValues, quantityValues, orderValues);
        }

        private Object[] arrayValues(String column) throws SQLException {
            Array array = rows.getArray(column);
            return array == null ? new Object[0] : (Object[]) array.getArray();
        }

        @Override
        public void close() {
            try {
                rows.close();
                statement.close();
                connection.rollback();
            } catch (SQLException ignored) {
                // closing a read-only cursor; nothing to recover
            } finally {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // pool handles broken connections
                }
            }
        }
    }
}
