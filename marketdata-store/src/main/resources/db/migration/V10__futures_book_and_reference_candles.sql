-- Futures order-book totals (Upstox full mode carries them; zt-tiger-v2 does not, so they stay null).
alter table md.future_tick add column total_buy_qty double precision;
alter table md.future_tick add column total_sell_qty double precision;

-- Reference series that are not traded underlyings (India VIX first), daily and one-minute bars.
-- bar_start is the bar's start instant; a daily bar starts at the session date's IST midnight.
create table ref.index_candle (
    symbol      text             not null,
    timeframe   text             not null check (timeframe in ('1day', '1minute')),
    bar_start   timestamptz      not null,
    session_date date            not null,
    open        double precision not null,
    high        double precision not null,
    low         double precision not null,
    close       double precision not null,
    source      text             not null,
    loaded_at   timestamptz      not null default now(),
    primary key (symbol, timeframe, bar_start)
);
create index index_candle_session on ref.index_candle (symbol, timeframe, session_date);
