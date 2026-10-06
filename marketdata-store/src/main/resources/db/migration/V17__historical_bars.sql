-- Historical one-minute bars pulled from Upstox (index, VIX, futures with OI, options with OI, constituents) for
-- replays of days the tick capture does not hold. A bar is known only at its close; the bar replay source
-- synthesises ticks from these. Weaker evidence than the tick capture (no order book, no auction, bar-close fills).
create schema if not exists hist;

create table hist.candle (
    instrument_key text not null,          -- Upstox key (an expired contract's expired_instrument_key)
    underlying     text not null,          -- NIFTY, SENSEX, INDIA_VIX
    kind           text not null,          -- INDEX, VIX, FUTURE, OPTION, EQUITY
    symbol         text,
    exchange_token text,                   -- the exchange's own code (BSE scrip code keys the SENSEX weights)
    exchange       text,                   -- NSE / BSE
    expiry         date,
    strike         double precision,
    option_type    text,                   -- CE / PE
    lot_size       integer,
    bar_start      timestamptz not null,
    open           double precision not null,
    high           double precision not null,
    low            double precision not null,
    close          double precision not null,
    volume         bigint,
    oi             double precision,
    source         text not null,          -- e.g. upstox-expired-v2, upstox-v3
    loaded_at      timestamptz not null default now(),
    primary key (instrument_key, bar_start)
);
create index candle_underlying_time on hist.candle (underlying, bar_start);
create index candle_contract on hist.candle (underlying, kind, expiry, strike, option_type);

create table hist.fetch_log (
    id             bigserial primary key,
    at             timestamptz not null default now(),
    underlying     text,
    kind           text,
    instrument_key text,
    from_date      date,
    to_date        date,
    candles        integer,
    status         text not null,          -- OK / EMPTY / FAILED
    error          text
);
