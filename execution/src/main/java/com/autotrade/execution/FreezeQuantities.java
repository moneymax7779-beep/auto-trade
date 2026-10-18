package com.autotrade.execution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The exchange's freeze quantity (the largest quantity one order may carry) from our contract master (ref.instrument,
 * the daily Upstox snapshots): the contract's own, else the newest one listed for its underlying (it is set per
 * underlying), else 0 = unknown. Cached per key and underlying for the life of the worker.
 */
public final class FreezeQuantities {

    private final JdbcTemplate jdbc;
    private final Map<String, Long> cache = new ConcurrentHashMap<>();

    public FreezeQuantities(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public long of(String instrumentKey, String underlying) {
        return cache.computeIfAbsent(instrumentKey + "/" + underlying, k -> load(instrumentKey, underlying));
    }

    private long load(String instrumentKey, String underlying) {
        List<Long> own = jdbc.queryForList("select freeze_quantity from ref.instrument where instrument_key = ? "
                + "order by snapshot_id desc limit 1", Long.class, instrumentKey);
        if (!own.isEmpty() && own.getFirst() != null && own.getFirst() > 0) {
            return own.getFirst();
        }
        List<Long> any = jdbc.queryForList("select freeze_quantity from ref.instrument where underlying = ? "
                + "and type in ('CE', 'PE') and freeze_quantity > 0 order by snapshot_id desc limit 1", Long.class, underlying);
        return any.isEmpty() || any.getFirst() == null ? 0 : any.getFirst();
    }
}
