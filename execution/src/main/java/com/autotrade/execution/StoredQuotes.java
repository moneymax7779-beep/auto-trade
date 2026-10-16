package com.autotrade.execution;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Replay quotes for the follower simulator's retries (L1): the best ask of one option contract at a moment, from the
 * stored option ticks: auto-trade's own capture (md.option_tick, from 28 Sep 2026) first, else zt-tiger-v2's capture
 * in the local archive copy (zt_archive.market_tick_records, IST-local receive times), within the last five minutes.
 * A live or DRY_RUN worker asks its broker instead (L2). NaN when no quote is stored.
 */
public final class StoredQuotes implements QuoteSource {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate own;
    private final JdbcTemplate archive;          // null when no archive is configured

    public StoredQuotes(DataSource target, DataSource ztArchive) {
        this.own = new JdbcTemplate(target);
        this.archive = ztArchive == null ? null : new JdbcTemplate(ztArchive);
    }

    @Override
    public double askAt(LocalDate day, String underlying, long token, Instant at) {
        List<Double> asks = own.queryForList("select ask_px[1] from md.option_tick where session_date = ? "
                        + "and instrument_token = ? and recv_ts <= ? and recv_ts > ? and ask_px[1] > 0 "
                        + "order by recv_ts desc limit 1", Double.class, java.sql.Date.valueOf(day), token,
                Timestamp.from(at), Timestamp.from(at.minusSeconds(300)));
        if (!asks.isEmpty() && asks.getFirst() != null) {
            return asks.getFirst();
        }
        if (archive == null) {
            return Double.NaN;
        }
        String key = "SENSEX".equals(underlying) ? "BSE:INDEX:SENSEX" : "NSE:INDEX:" + underlying;
        asks = archive.queryForList("select ((payload_json::jsonb)->'askDepth'->0->>'price')::float8 from market_tick_records "
                        + "where underlying_key = ? and session_date = ? and tick_type = 'OPTION' and instrument_token = ? "
                        + "and received_at <= ? and received_at > ? order by received_at desc limit 1", Double.class, key,
                java.sql.Date.valueOf(day), token, Timestamp.valueOf(at.atZone(IST).toLocalDateTime()),
                Timestamp.valueOf(at.minusSeconds(300).atZone(IST).toLocalDateTime()));
        Double ask = asks.isEmpty() ? null : asks.getFirst();
        return ask != null && ask > 0 ? ask : Double.NaN;
    }
}
