package com.autotrade.trading;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.sql.DataSource;

import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.SessionEventSource;
import com.autotrade.core.time.MarketTime;
import com.autotrade.md.store.LoadManifests;
import com.autotrade.md.store.StoredSessionSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Replays a session from auto-trade's own live capture (md.*): the same ticks, order books and auction data
 * the live session received, so a replay can reproduce live decisions exactly. The live feed also captures
 * INDIA_VIX under its own manifest; it is replayed with the traded underlyings whenever it was captured.
 *
 * <p>The reader holds one open cursor per md table for the whole replay, so it gets its own small read-only
 * pool (closed afterwards) instead of taking the session's connections.
 */
final class OwnCaptureSource implements SessionEventSource {

    static final String VIX = "INDIA_VIX";
    private static final int READER_POOL = 10;

    private final DataSource target;
    /** zt-tiger-v2 (read-only), for the opening minutes the own capture missed; null = no fill. */
    private final DataSource zt;

    OwnCaptureSource(DataSource target) {
        this(target, null);
    }

    OwnCaptureSource(DataSource target, DataSource zt) {
        this.target = target;
        this.zt = zt;
    }

    @Override
    public String name() {
        return "own-capture";
    }

    @Override
    public ReplayResult replay(LocalDate session, List<String> underlyings, Consumer<MarketEvent> sink)
            throws Exception {
        try (HikariDataSource reader = readerPool()) {
            List<String> all = new ArrayList<>(underlyings);
            if (!all.contains(VIX) && new LoadManifests(reader).active(session, VIX).isPresent()) {
                all.add(VIX);
            }
            String dir = System.getenv(HistoryCache.ENV);   // 22 Nov 2026: the day's ordered stream, cached for replays
            com.autotrade.md.store.ReplayCache cache = dir == null || dir.isBlank() ? null
                    : new com.autotrade.md.store.ReplayCache(java.nio.file.Path.of(dir, "ticks"), reader);
            Consumer<MarketEvent> out = zt == null ? sink : new OpeningFill(OpeningFill.zt(zt), session, underlyings, sink);
            return new StoredSessionSource(reader, cache).replay(session, all, out);
        }
    }

    /**
     * A-606 (24 Nov 2026): when the day's first captured event is after 09:15:05 and before 09:30 (20 Nov 2026: 09:17:02,
     * the live start was late), the index minutes from 09:15 up to that minute are taken from zt's MULTI_TICK minute bars
     * and replayed first as index ticks (open, the nearer extreme, the other extreme, close at :00 / :15 / :30 / :45), so
     * the day open, opening range, day high / low and the minute indicators match the exchange. Options, futures and books
     * stay as captured (nothing to fill them from). A failed read logs and replays the capture as it is.
     */
    static final class OpeningFill implements Consumer<MarketEvent> {
        static final LocalTime OPEN = LocalTime.of(9, 15);

        /** A minute bar of an index (bar start in IST). */
        record Bar(LocalDateTime start, double open, double high, double low, double close) {
        }

        /** Minute bars of {@code underlying} from {@code from} (inclusive) to {@code until} (exclusive), oldest first. */
        interface Bars {
            List<Bar> load(String underlying, LocalDateTime from, LocalDateTime until) throws Exception;
        }

        private final Bars bars;
        private final LocalDate session;
        private final List<String> underlyings;
        private final Consumer<MarketEvent> sink;
        private boolean checked;
        private int filled;

        OpeningFill(Bars bars, LocalDate session, List<String> underlyings, Consumer<MarketEvent> sink) {
            this.bars = bars;
            this.session = session;
            this.underlyings = underlyings;
            this.sink = sink;
        }

        /** zt-tiger-v2's MULTI_TICK index minute bars (read-only); an underlying without a zt key has none. */
        static Bars zt(DataSource zt) {
            return (underlying, from, until) -> {
                String key;
                try {
                    key = com.autotrade.md.zt.ZtSourceKeys.underlyingKey(underlying);
                } catch (IllegalArgumentException e) {
                    return List.of();
                }
                String sql = "select bar_start, open_price, high_price, low_price, close_price from market_candles "
                        + "where underlying_key = ? and source = 'MULTI_TICK' and timeframe = '1minute' "
                        + "and bar_start >= ? and bar_start < ? order by bar_start";
                List<Bar> out = new ArrayList<>();
                try (java.sql.Connection c = zt.getConnection(); java.sql.PreparedStatement st = c.prepareStatement(sql)) {
                    st.setString(1, key);
                    st.setObject(2, from);
                    st.setObject(3, until);
                    try (java.sql.ResultSet rs = st.executeQuery()) {
                        while (rs.next()) {
                            out.add(new Bar(rs.getTimestamp(1).toLocalDateTime(), rs.getDouble(2), rs.getDouble(3),
                                    rs.getDouble(4), rs.getDouble(5)));
                        }
                    }
                }
                return out;
            };
        }

        int filled() {
            return filled;
        }

        @Override
        public void accept(MarketEvent event) {
            if (!checked) {
                checked = true;
                LocalDateTime first = LocalDateTime.ofInstant(event.receivedAt(), MarketTime.IST);
                if (first.toLocalDate().equals(session) && first.toLocalTime().isAfter(OPEN.plusSeconds(5))
                        && first.toLocalTime().isBefore(LocalTime.of(9, 30))) {
                    fill(first.withSecond(0).withNano(0));
                }
            }
            sink.accept(event);
        }

        private void fill(LocalDateTime until) {
            List<IndexTick> ticks = new ArrayList<>();
            for (String u : underlyings) {
                int step = "NIFTY".equals(u) ? 50 : 100;
                try {
                    for (Bar b : bars.load(u, session.atTime(OPEN), until)) {
                        Instant t0 = b.start().atZone(MarketTime.IST).toInstant();
                        boolean highFirst = Math.abs(b.high() - b.open()) <= Math.abs(b.open() - b.low());
                        double[] path = {b.open(), highFirst ? b.high() : b.low(), highFirst ? b.low() : b.high(), b.close()};
                        for (int i = 0; i < path.length; i++) {
                            Instant at = t0.plusSeconds(15L * i);
                            ticks.add(new IndexTick(at, at, u, -1, path[i], null, (int) (Math.round(path[i] / step) * step)));
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("opening fill {} {}: zt not readable ({}); replaying the capture as it is", session, u, e.toString());
                }
            }
            ticks.sort(java.util.Comparator.comparing(IndexTick::receivedAt));
            ticks.forEach(sink);
            filled = ticks.size();
            LOG.info("opening fill {}: capture starts {}, {} index ticks from zt 09:15 to {}", session, until.toLocalTime(), filled,
                    until.toLocalTime());
        }
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OwnCaptureSource.class);

    private HikariDataSource readerPool() {
        if (!(target instanceof HikariDataSource main)) {
            throw new IllegalStateException("replay-source own needs the Hikari target data source");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(main.getJdbcUrl());
        config.setUsername(main.getUsername());
        config.setPassword(main.getPassword());
        config.setMaximumPoolSize(READER_POOL);
        config.setReadOnly(true);
        config.setPoolName("own-capture-reader");
        return new HikariDataSource(config);
    }
}
