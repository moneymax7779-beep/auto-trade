package com.autotrade.execution;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.broker.BrokerException;
import com.autotrade.broker.Quote;

/**
 * The executable book of a contract now, from our own live capture (md.option_tick, the Upstox feed: five levels of
 * depth): the quote of a DRY_RUN route that reads a broker whose own quote API this build does not call (Kotak Neo). Only
 * a tick younger than {@link #MAX_AGE} counts; an older one, or none, is no quote ({@link BrokerException.Kind#RETRYABLE}:
 * the venue sends no order). The capture's book is not the broker's own, but the exchange's book is the same at every
 * broker; what differs (the broker's latency) is not modelled.
 */
public final class CapturedQuotes implements Function<String, Quote> {

    /** A captured tick older than this is not a quote of now. */
    static final Duration MAX_AGE = Duration.ofSeconds(10);

    private final JdbcTemplate jdbc;
    private final Supplier<Instant> clock;

    public CapturedQuotes(DataSource dataSource) {
        this(dataSource, Instant::now);
    }

    CapturedQuotes(DataSource dataSource, Supplier<Instant> clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    @Override
    public Quote apply(String instrumentKey) {
        long token = InstrumentKeys.token(instrumentKey);
        if (token < 0) {
            throw new BrokerException(BrokerException.Kind.TERMINAL, "no captured quote for " + instrumentKey
                    + ": not an option key");
        }
        Instant now = clock.get();
        List<Quote> found = jdbc.query("select bid_px, bid_qty, ask_px, ask_qty, ltp, recv_ts from md.option_tick "
                + "where instrument_token = ? and recv_ts <= ? and recv_ts > ? order by recv_ts desc limit 1", (rs, i) -> {
                    List<Quote.Level> bids = levels(rs.getArray("bid_px"), rs.getArray("bid_qty"));
                    List<Quote.Level> asks = levels(rs.getArray("ask_px"), rs.getArray("ask_qty"));
                    Quote.Level bid = bids.isEmpty() ? new Quote.Level(0, 0) : bids.getFirst();
                    Quote.Level ask = asks.isEmpty() ? new Quote.Level(0, 0) : asks.getFirst();
                    return new Quote(instrumentKey, bid.price(), bid.quantity(), ask.price(), ask.quantity(),
                            rs.getDouble("ltp"), rs.getTimestamp("recv_ts").toInstant(), bids, asks);
                }, token, Timestamp.from(now.plusSeconds(1)), Timestamp.from(now.minus(MAX_AGE)));
        if (found.isEmpty()) {
            throw new BrokerException(BrokerException.Kind.RETRYABLE, "no quote of " + instrumentKey
                    + " captured in the last " + MAX_AGE.toSeconds() + " s");
        }
        return found.getFirst();
    }

    /** Levels best first until the first empty one (price or quantity 0). */
    private static List<Quote.Level> levels(Array prices, Array quantities) throws SQLException {
        Object[] px = (Object[]) prices.getArray(), qty = (Object[]) quantities.getArray();
        List<Quote.Level> levels = new ArrayList<>();
        for (int i = 0; i < Math.min(px.length, qty.length); i++) {
            double p = px[i] == null ? 0 : ((Number) px[i]).doubleValue();
            long q = qty[i] == null ? 0 : ((Number) qty[i]).longValue();
            if (!(p > 0) || q <= 0) {
                break;
            }
            levels.add(new Quote.Level(p, q));
        }
        return levels;
    }
}
