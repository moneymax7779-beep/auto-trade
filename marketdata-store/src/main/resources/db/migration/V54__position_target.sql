-- Manual first target on an open position (user 24 Nov 2026: "I should be able to manually enter the 1st target in % once
-- the position is opened ... so in case it reverses should close at the target entered manually"). Answers the same day:
-- a lock, not a take-profit (nothing happens until the bid reaches entry x (1 + %); after that, a bid back at or below that
-- price sells the whole position), beside the strategy's own exits (whichever comes first), PAPER and LIVE, every broker.
-- One row per position; the watcher in the execution worker (com.autotrade.execution.PositionTargets) moves it:
--   WAITING -> ARMED (the bid reached the price; the price is fixed then: armed_price)
--   ARMED -> FIRING (a fresh bid at or below armed_price: a sale runs) -> DONE (sold by the target)
--   FIRING -> SELLING (triggered, not all sold yet: sold again at next_try_at whatever the bid, like a stop order)
--   -> FAILED (after its tries), ENDED (the position was closed by something else), CLEARED (the trader removed it).
-- Setting it again bumps generation and starts over at WAITING; every change the watcher makes checks the generation.
create table exec.position_target (
    position_id       bigint      primary key references exec.position (id) on delete cascade,
    broker_account_id bigint      not null references acct.broker_account (id),
    target_pct        numeric     not null check (target_pct > 0 and target_pct <= 1000),
    generation        int         not null default 1,
    status            text        not null default 'WAITING'
                                  check (status in ('WAITING', 'ARMED', 'FIRING', 'SELLING', 'DONE', 'ENDED', 'FAILED',
                                                    'CLEARED')),
    set_by            text        not null,
    set_at            timestamptz not null default now(),
    -- when the bid first reached the target, that bid, and the price (and average entry) the lock holds from then on
    armed_at          timestamptz,
    armed_bid         numeric,
    armed_price       numeric,
    armed_entry       numeric,
    -- the trigger (first sale) and its bid; the latest try, the next one, how many failed, and what the exit said
    fired_at          timestamptz,
    fired_bid         numeric,
    last_try_at       timestamptz,
    next_try_at       timestamptz,
    tries             int         not null default 0,
    last_result       text,
    updated_at        timestamptz not null default now()
);
create index position_target_account on exec.position_target (broker_account_id);
create index position_target_live on exec.position_target (status) where status in ('WAITING', 'ARMED', 'FIRING', 'SELLING');
