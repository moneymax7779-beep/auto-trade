-- Features v12 (A-090): the normal 5-minute change of near-ATM option OI as a share of near-ATM OI, by underlying, side,
-- calendar days to expiry (0-4, 4 = four or more) and 15-minute bucket of the session, measured on hist.candle with
-- the live feature's strikes and weights (bin/build-oi-baseline.sql). The live z-score is (value - median) / (1.4826 x mad).
create table ref.oi_baseline (
    underlying    text not null,
    side          text not null,                 -- CE / PE
    dte_bucket    integer not null,
    minute_bucket integer not null,              -- 0 = 09:15-09:29
    n             integer not null,
    median        double precision not null,
    mad           double precision not null,
    first_day     date not null,
    last_day      date not null,
    params        text not null,                 -- strikes, weights and window used, for the record
    built_at      timestamptz not null default now(),
    primary key (underlying, side, dte_bucket, minute_bucket)
);
