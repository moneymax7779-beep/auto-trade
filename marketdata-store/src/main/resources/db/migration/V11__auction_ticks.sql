-- Per-stock closing-auction (and pre-open) state from the Upstox full feed: indicative equilibrium
-- price, reference price, equilibrium quantity and unmatched imbalance (positive = buyers in excess).
create table md.auction_tick (
    manifest_id      bigint           not null,
    session_date     date             not null,
    underlying       text             not null,
    recv_ts          timestamptz      not null,
    exch_ts          timestamptz,
    instrument_token bigint           not null,
    symbol           text             not null,
    iep              double precision not null,
    ref_price        double precision,
    eq_qty           bigint,
    imbalance_total  bigint,
    imbalance_market bigint,
    cas_eligible     boolean
);
select create_hypertable('md.auction_tick', by_range('recv_ts', interval '1 day'));
create index auction_tick_manifest on md.auction_tick (manifest_id, recv_ts);
create index auction_tick_session on md.auction_tick (underlying, session_date, recv_ts);
