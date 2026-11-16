-- Chart orders (user, 14 Nov 2026: "I will draw a line in the chart for entry and exit and will also draw stop loss";
-- plan docs/plans/2026-11-14-chart-line-trading-plan.md, phase P0). The trader draws an entry, a stop and a target line
-- on the index chart and arms them for one follower account; the live session's ChartOrderEngine checks them on every
-- index tick and buys or sells through the manual order path (strategy "manual", one intent for that account only).
--
-- status: ARMED (waiting for the entry line) -> TRIGGERED (the buy is written) -> OPEN (a position is held)
--         -> EXITING (the sell is written) -> CLOSED; or CANCELLED (by the trader), VOID (the stop line was reached
--         first, or the first tick past the entry was beyond the no-chase limit), EXPIRED (valid_until passed or the
--         day ended), REFUSED (the buy was refused or did not fill).
-- version: raised by every change (a line moved, a status change); the engine acts only on the version it read.

create table exec.chart_order (
    id                bigserial        primary key,
    tenant_id         bigint           not null references acct.tenant (id),
    broker_account_id bigint           not null references acct.broker_account (id),
    copy_group_id     bigint           not null references acct.copy_group (id),
    session_date      date             not null,
    created_by        text             not null,
    underlying        text             not null check (underlying in ('NIFTY', 'SENSEX')),
    option_side       text             not null check (option_side in ('CE', 'PE')),
    -- BREAKOUT: the index moves the trade's way through the entry line; PULLBACK: it comes back to the line;
    -- FAR_BREAKOUT: a breakout armed with the index beyond the stop line (a plain stop-entry, no stop-first void)
    entry_kind        text             not null check (entry_kind in ('BREAKOUT', 'PULLBACK', 'FAR_BREAKOUT')),
    entry_price       double precision not null check (entry_price > 0),
    stop_price        double precision not null check (stop_price > 0),
    target_price      double precision not null check (target_price > 0),
    buffer_points     double precision not null check (buffer_points >= 0),
    no_chase_points   double precision not null check (no_chase_points > 0),
    lots              integer          not null check (lots between 1 and 10),
    valid_until       time             not null,
    status            text             not null check (status in ('ARMED', 'TRIGGERED', 'OPEN', 'EXITING', 'CLOSED',
                                                                  'CANCELLED', 'VOID', 'EXPIRED', 'REFUSED')),
    status_reason     text,
    version           integer          not null default 1,
    symbol            text,
    entry_intent_id   bigint           references exec.trade_intent (id),
    exit_intent_id    bigint           references exec.trade_intent (id),
    position_id       bigint           references exec.position (id),
    triggered_at      timestamptz,
    trigger_index     double precision,
    exit_trigger      text,
    exit_index        double precision,
    created_at        timestamptz      not null default now(),
    updated_at        timestamptz      not null default now()
);

-- one working chart order per account and index (the manual path holds one manual position per index)
create unique index chart_order_one_active on exec.chart_order (broker_account_id, underlying)
    where status in ('ARMED', 'TRIGGERED', 'OPEN', 'EXITING');
create index chart_order_day on exec.chart_order (session_date, status);

-- every change, with the index at that moment and who made it (the audit, and later the trader's journal)
create table exec.chart_order_event (
    id             bigserial        primary key,
    chart_order_id bigint           not null references exec.chart_order (id),
    at             timestamptz      not null default now(),
    kind           text             not null,
    actor          text             not null,
    index_price    double precision,
    detail         jsonb            not null default '{}'
);
create index chart_order_event_order on exec.chart_order_event (chart_order_id, id);
