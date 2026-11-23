-- Desk trades (plan docs/plans/2026-11-23-owner-exit-all-and-desk-trades-plan.md, feature B; user 23 Nov 2026: "start with
-- desk trades PAPER only"): the owner's own trade, sent to the paper books of all or chosen accounts at once. Each target
-- is one decision (strategy 'desk'), one MANUAL batch and one intent, run through the follower risk path like any entry;
-- never fanned out. PAPER only in this version: a real account's trade goes to its paper twin.
create table exec.desk_order (
    id              bigserial   primary key,
    created_by      text        not null,
    created_at      timestamptz not null default now(),
    session_date    date        not null,
    underlying      text        not null,
    option_side     text        not null check (option_side in ('CE', 'PE')),
    instrument_token bigint     not null,
    symbol          text        not null,
    lot_size        int         not null check (lot_size > 0),
    -- the book when it was sent: the order's reference price for its stop and target
    reference_ask   numeric     not null check (reference_ask > 0),
    reference_bid   numeric,
    sizing          text        not null check (sizing in ('PCT_EQUITY', 'FIXED_LOTS')),
    sizing_value    numeric     not null check (sizing_value > 0),
    stop_pct        numeric     not null check (stop_pct > 0 and stop_pct < 100),
    target_pct      numeric     check (target_pct is null or target_pct > 0),
    mode            text        not null default 'PAPER' check (mode = 'PAPER'),
    reason          text        not null check (length(trim(reason)) >= 3),
    idempotency_key text        not null,
    -- set once when the group exit is asked (by the owner, the stop or the target); the watcher sells what is left
    exit_reason     text,
    exit_requested_at timestamptz,
    exit_requested_by text,
    unique (created_by, idempotency_key)
);
create index desk_order_day on exec.desk_order (session_date, id);

create table exec.desk_order_target (
    desk_order_id       bigint not null references exec.desk_order (id) on delete cascade,
    -- the account chosen, and the paper account the trade went to (itself, or its paper twin)
    requested_account_id bigint not null references acct.broker_account (id),
    broker_account_id   bigint references acct.broker_account (id),
    equity              numeric,
    lots                int,
    quantity            bigint,
    trade_intent_id     bigint references exec.trade_intent (id),
    refusal             text,
    primary key (desk_order_id, requested_account_id)
);
create index desk_order_target_intent on exec.desk_order_target (trade_intent_id);
