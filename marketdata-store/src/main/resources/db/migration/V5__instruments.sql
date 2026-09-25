-- Contract-master snapshots, one per trading day per source file. Lot size, freeze quantity, tick
-- size and expiry change over time, so every lookup names the snapshot it used.
create table ref.instrument_snapshot (
    id            bigserial   primary key,
    snapshot_date date        not null,
    source        text        not null,
    file_sha256   text        not null,
    loaded_at     timestamptz not null default now(),
    instruments   integer     not null,
    unique (snapshot_date, source, file_sha256)
);

create table ref.instrument (
    snapshot_id     bigint           not null references ref.instrument_snapshot (id),
    instrument_key  text             not null,
    exchange_token  text,
    segment         text             not null,
    exchange        text             not null,
    type            text             not null check (type in ('CE', 'PE', 'FUT')),
    underlying      text             not null,
    underlying_key  text,
    trading_symbol  text             not null,
    expiry          date,
    strike          double precision not null,
    lot_size        integer          not null,
    tick_size       double precision not null,
    freeze_quantity bigint           not null,
    weekly          boolean          not null,
    primary key (snapshot_id, instrument_key)
);

create index instrument_lookup on ref.instrument (snapshot_id, underlying, expiry, type, strike);
