-- Missing data (feature/missing-data, 15 Oct 2026): pre-open and positioning context.
-- ref.gift_nifty_quote: GIFT Nifty futures quotes (NSE IX, NIFTY FUTIDX) as fetched; point-in-time use filters on
-- fetched_at. One row per contract and exchange quote time (re-fetching an unchanged quote changes nothing).
create table ref.gift_nifty_quote (
    expiry           date             not null,
    exchange_ts      timestamptz      not null,
    fetched_at       timestamptz      not null,
    last_price       double precision not null,
    day_change       double precision,
    day_change_pct   double precision,
    contracts_traded bigint,
    source           text             not null default 'nseix-market-rate',
    primary key (expiry, exchange_ts)
);
create index gift_nifty_quote_fetched on ref.gift_nifty_quote (fetched_at);

-- ref.participant_oi: NSE participant-wise open interest in equity derivatives (contracts), end of session_date.
create table ref.participant_oi (
    session_date            date   not null,
    client_type             text   not null,   -- Client, DII, FII, Pro, TOTAL
    future_index_long       bigint not null,
    future_index_short      bigint not null,
    future_stock_long       bigint not null,
    future_stock_short      bigint not null,
    option_index_call_long  bigint not null,
    option_index_put_long   bigint not null,
    option_index_call_short bigint not null,
    option_index_put_short  bigint not null,
    option_stock_call_long  bigint not null,
    option_stock_put_long   bigint not null,
    option_stock_call_short bigint not null,
    option_stock_put_short  bigint not null,
    total_long              bigint not null,
    total_short             bigint not null,
    source                  text   not null default 'nse-archives',
    loaded_at               timestamptz not null default now(),
    primary key (session_date, client_type)
);
