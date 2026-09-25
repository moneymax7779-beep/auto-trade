-- Typed market data. recv_ts is when the observation was received (the only time features may use);
-- exch_ts is the exchange/feed timestamp when one exists. src_seq and src_hash64 (first 8 bytes of
-- the source payload's SHA-256) give lineage back to the source row.
-- No foreign key to md.load_manifest: it would cost one lookup per row on bulk load; the loader
-- owns that integrity.

create table md.index_tick (
    manifest_id   bigint           not null,
    session_date  date             not null,
    underlying    text             not null,
    recv_ts       timestamptz      not null,
    exch_ts       timestamptz,
    src_seq       bigint           not null,
    src_hash64    bigint,
    price         double precision not null,
    prev_close    double precision,
    atm_strike    integer
);

create table md.future_tick (
    manifest_id      bigint           not null,
    session_date     date             not null,
    underlying       text             not null,
    recv_ts          timestamptz      not null,
    exch_ts          timestamptz,
    src_seq          bigint           not null,
    src_hash64       bigint,
    instrument_token bigint           not null,
    symbol           text             not null,
    segment          text,
    expiry           date,
    price            double precision not null,
    volume           bigint,
    oi               double precision,
    session_vwap     double precision,
    quote_source     text
);

create table md.option_tick (
    manifest_id        bigint             not null,
    session_date       date               not null,
    underlying         text               not null,
    recv_ts            timestamptz        not null,
    exch_ts            timestamptz,
    src_seq            bigint             not null,
    src_hash64         bigint,
    instrument_token   bigint             not null,
    symbol             text               not null,
    segment            text,
    expiry             date,
    strike             double precision   not null,
    option_type        text               not null check (option_type in ('CE', 'PE')),
    lot_size           integer,
    ltp                double precision   not null,
    volume             bigint,
    oi                 double precision,
    total_buy_qty      double precision,
    total_sell_qty     double precision,
    bid_px             double precision[] not null,
    bid_qty            bigint[]           not null,
    bid_orders         integer[]          not null,
    ask_px             double precision[] not null,
    ask_qty            bigint[]           not null,
    ask_orders         integer[]          not null,
    iv                 double precision,
    delta              double precision,
    gamma              double precision,
    theta              double precision,
    vega               double precision,
    rho                double precision,
    analytics_ts       timestamptz,
    analytics_complete boolean            not null,
    depth_complete     boolean            not null,
    quote_source       text
);

create table md.constituent_tick (
    manifest_id      bigint           not null,
    session_date     date             not null,
    underlying       text             not null,
    recv_ts          timestamptz      not null,
    exch_ts          timestamptz,
    src_seq          bigint           not null,
    src_hash64       bigint,
    instrument_token bigint           not null,
    symbol           text             not null,
    price            double precision not null,
    prev_close       double precision,
    cum_volume       bigint,
    session_vwap     double precision,
    weight_pct       double precision
);

-- Index values observed in named session phases (closing auction and after). CAS values are
-- indicative, not traded prices; price_semantics says which.
create table md.session_phase_event (
    manifest_id           bigint           not null,
    session_date          date             not null,
    underlying            text             not null,
    recv_ts               timestamptz      not null,
    event_ts              timestamptz      not null,
    session_phase         text             not null,
    price_semantics       text             not null,
    event_time_phase      text,
    received_time_phase   text,
    price                 double precision not null,
    official_final        boolean          not null,
    continuously_tradable boolean,
    source                text,
    idempotency_key       text             not null
);

create table md.candle (
    manifest_id            bigint           not null,
    session_date           date             not null,
    underlying             text             not null,
    instrument_key         text             not null,
    timeframe              text             not null,
    source                 text             not null,
    bar_start              timestamptz      not null,
    open                   double precision not null,
    high                   double precision not null,
    low                    double precision not null,
    close                  double precision not null,
    volume                 double precision,
    volume_complete        boolean,
    volume_source          text,
    volume_contract_symbol text,
    volume_contract_expiry date,
    mss                    integer
);

-- Option-chain OI profiles as published by the source (JSON kept whole; parsed in Phase 1).
create table md.oi_profile (
    manifest_id     bigint      not null,
    session_date    date        not null,
    underlying      text        not null,
    source_id       text        not null,
    expiry          date,
    received_at     timestamptz not null,
    available_at    timestamptz,
    membership_hash text,
    payload_hash    text,
    profile_hash    text,
    profile         jsonb       not null
);
create index oi_profile_manifest on md.oi_profile (manifest_id, available_at);

select create_hypertable('md.index_tick', by_range('recv_ts', interval '1 day'));
select create_hypertable('md.future_tick', by_range('recv_ts', interval '1 day'));
select create_hypertable('md.option_tick', by_range('recv_ts', interval '1 day'));
select create_hypertable('md.constituent_tick', by_range('recv_ts', interval '1 day'));
select create_hypertable('md.session_phase_event', by_range('recv_ts', interval '1 day'));
select create_hypertable('md.candle', by_range('bar_start', interval '7 days'));

create index index_tick_replay on md.index_tick (manifest_id, recv_ts, src_seq);
create index future_tick_replay on md.future_tick (manifest_id, recv_ts, src_seq);
create index option_tick_replay on md.option_tick (manifest_id, recv_ts, src_seq);
create index constituent_tick_replay on md.constituent_tick (manifest_id, recv_ts, src_seq);
create index session_phase_event_replay on md.session_phase_event (manifest_id, recv_ts);
create index candle_manifest on md.candle (manifest_id, instrument_key, timeframe, bar_start);

-- Compression: segments per manifest (and instrument), ordered for replay.
alter table md.index_tick set (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'manifest_id, underlying',
    timescaledb.compress_orderby = 'recv_ts, src_seq');
alter table md.future_tick set (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'manifest_id, instrument_token',
    timescaledb.compress_orderby = 'recv_ts, src_seq');
alter table md.option_tick set (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'manifest_id, instrument_token',
    timescaledb.compress_orderby = 'recv_ts, src_seq');
alter table md.constituent_tick set (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'manifest_id, instrument_token',
    timescaledb.compress_orderby = 'recv_ts, src_seq');
alter table md.session_phase_event set (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'manifest_id',
    timescaledb.compress_orderby = 'recv_ts');
alter table md.candle set (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'manifest_id, instrument_key, timeframe',
    timescaledb.compress_orderby = 'bar_start');

select add_compression_policy('md.index_tick', interval '3 days');
select add_compression_policy('md.future_tick', interval '3 days');
select add_compression_policy('md.option_tick', interval '3 days');
select add_compression_policy('md.constituent_tick', interval '3 days');
select add_compression_policy('md.session_phase_event', interval '3 days');
select add_compression_policy('md.candle', interval '14 days');
