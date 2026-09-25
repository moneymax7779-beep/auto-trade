package com.autotrade.md.store;

import java.util.List;

/** The market-data tables and their bulk-load column order. {@link MdRowEncoder} writes in this order. */
public enum MdTable {

    INDEX_TICK("md.index_tick", true, List.of(
            "manifest_id", "session_date", "underlying", "recv_ts", "exch_ts", "src_seq", "src_hash64",
            "price", "prev_close", "atm_strike")),

    FUTURE_TICK("md.future_tick", true, List.of(
            "manifest_id", "session_date", "underlying", "recv_ts", "exch_ts", "src_seq", "src_hash64",
            "instrument_token", "symbol", "segment", "expiry", "price", "volume", "oi", "session_vwap",
            "quote_source")),

    OPTION_TICK("md.option_tick", true, List.of(
            "manifest_id", "session_date", "underlying", "recv_ts", "exch_ts", "src_seq", "src_hash64",
            "instrument_token", "symbol", "segment", "expiry", "strike", "option_type", "lot_size", "ltp",
            "volume", "oi", "total_buy_qty", "total_sell_qty", "bid_px", "bid_qty", "bid_orders", "ask_px",
            "ask_qty", "ask_orders", "iv", "delta", "gamma", "theta", "vega", "rho", "analytics_ts",
            "analytics_complete", "depth_complete", "quote_source")),

    CONSTITUENT_TICK("md.constituent_tick", true, List.of(
            "manifest_id", "session_date", "underlying", "recv_ts", "exch_ts", "src_seq", "src_hash64",
            "instrument_token", "symbol", "price", "prev_close", "cum_volume", "session_vwap", "weight_pct")),

    SESSION_PHASE_EVENT("md.session_phase_event", false, List.of(
            "manifest_id", "session_date", "underlying", "recv_ts", "event_ts", "session_phase",
            "price_semantics", "event_time_phase", "received_time_phase", "price", "official_final",
            "continuously_tradable", "source", "idempotency_key")),

    CANDLE("md.candle", false, List.of(
            "manifest_id", "session_date", "underlying", "instrument_key", "timeframe", "source", "bar_start",
            "open", "high", "low", "close", "volume", "volume_complete", "volume_source",
            "volume_contract_symbol", "volume_contract_expiry", "mss")),

    OI_PROFILE("md.oi_profile", false, List.of(
            "manifest_id", "session_date", "underlying", "source_id", "expiry", "received_at", "available_at",
            "membership_hash", "payload_hash", "profile_hash", "profile"));

    private final String qualifiedName;
    private final boolean tick;
    private final List<String> columns;

    MdTable(String qualifiedName, boolean tick, List<String> columns) {
        this.qualifiedName = qualifiedName;
        this.tick = tick;
        this.columns = columns;
    }

    public String qualifiedName() {
        return qualifiedName;
    }

    /** Tick tables carry src_seq/src_hash64 and are verified by sequence digest. */
    public boolean isTick() {
        return tick;
    }

    public List<String> columns() {
        return columns;
    }

    public String copySql() {
        return "COPY " + qualifiedName + " (" + String.join(", ", columns) + ") FROM STDIN";
    }
}
