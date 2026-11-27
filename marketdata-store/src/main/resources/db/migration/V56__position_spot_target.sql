-- The spot exit on an open position (user 27 Nov 2026: "I should exit from LIVE open position when it reaches the spot not
-- just on the percentage target"). The trader gives a level of the position's index (NIFTY, SENSEX, BANKNIFTY); the whole
-- position is sold when the captured index reaches it: a CE when the index is at or above the level, a PE when it is at or
-- below it. A take-profit at a touch (not a lock like V54's % target), beside the % target, the strategy's exits, the stop,
-- Exit and the square-off: whichever comes first. PAPER and LIVE, every broker, exit reason SPOT_TARGET.
-- One row per position; the watcher in the execution worker (com.autotrade.execution.SpotTargets) moves it:
--   WAITING -> FIRING (the index reached the level: a sale runs) -> DONE (sold by the spot exit)
--   FIRING -> SELLING (triggered, not all sold yet: sold again at next_try_at whatever the index, like a stop order)
--   -> FAILED (after its tries), ENDED (the position was closed by something else), CLEARED (the trader removed it).
-- Setting it again bumps generation and starts over at WAITING; every change the watcher makes checks the generation.
create table exec.position_spot_target (
    position_id       bigint      primary key references exec.position (id) on delete cascade,
    broker_account_id bigint      not null references acct.broker_account (id),
    underlying        text        not null,
    -- 'UP': sold when the index is at or above the level (a CE); 'DOWN': at or below it (a PE)
    direction         text        not null check (direction in ('UP', 'DOWN')),
    spot_level        numeric     not null check (spot_level > 0),
    generation        int         not null default 1,
    status            text        not null default 'WAITING'
                                  check (status in ('WAITING', 'FIRING', 'SELLING', 'DONE', 'ENDED', 'FAILED', 'CLEARED')),
    set_by            text        not null,
    set_at            timestamptz not null default now(),
    -- the index when it was set (null: no index price was known then)
    spot_at_set       numeric,
    by_admin          boolean     not null default false,
    admin_reason      text,
    -- the trigger: when the index reached the level and the index price that did it
    fired_at          timestamptz,
    fired_spot        numeric,
    last_try_at       timestamptz,
    next_try_at       timestamptz,
    tries             int         not null default 0,
    last_result       text,
    updated_at        timestamptz not null default now()
);
create index position_spot_target_account on exec.position_spot_target (broker_account_id);
create index position_spot_target_live on exec.position_spot_target (status)
    where status in ('WAITING', 'FIRING', 'SELLING');
