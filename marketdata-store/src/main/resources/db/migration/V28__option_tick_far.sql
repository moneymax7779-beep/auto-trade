-- The next weekly and the current monthly expiry's options around ATM, NIFTY and SENSEX (multi-expiry OI research, user
-- 3 Nov 2026: "do step 1"): recorded only, in their own table with md.option_tick's shape, so no strategy, chart,
-- tracker, replay or export that reads md.option_tick changes. Compressed after 7 days (the user's floor for recent
-- data), not 3 like md.option_tick.
create table md.option_tick_far (like md.option_tick including defaults including constraints);
select create_hypertable('md.option_tick_far', by_range('recv_ts', interval '1 day'));
create index option_tick_far_replay on md.option_tick_far (manifest_id, recv_ts, src_seq);
create index option_tick_far_contract_time on md.option_tick_far (underlying, expiry, strike, option_type, recv_ts desc);
alter table md.option_tick_far set (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'manifest_id, instrument_token',
    timescaledb.compress_orderby = 'recv_ts, src_seq');
select add_compression_policy('md.option_tick_far', interval '7 days');
