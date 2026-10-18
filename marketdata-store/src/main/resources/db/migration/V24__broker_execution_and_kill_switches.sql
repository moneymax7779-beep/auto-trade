-- Live execution L2 (19 Oct 2026, user: "work on rest of the implementation for multi user and multi broker support").
-- What a broker-routed follower needs beyond L1 (additive only):
--   exec.position.instrument_key / stop_pct: the contract the exit and the protective stop are sent for (ours, e.g.
--     NSE_FO|47168, mapped per broker by the adapter) and the premium stop the broker-side stop rests at.
--   exec.kill_switch: GLOBAL_LIVE (every LIVE account), ACCOUNT, STRATEGY (a tenant's strategy) and INSTRUMENT switches
--     (live plan section 4, "Kill switches"); an engaged switch refuses entries and adds, never exits.
--   exec.audit_log: every manual or automatic protective action (kill, release, flatten, session errors).
alter table exec.position add column instrument_key text;
alter table exec.position add column stop_pct double precision;
-- exec.order_execution.booked_quantity: how much of a protective stop's fill the position has booked (restart-safe).
alter table exec.order_execution add column booked_quantity bigint not null default 0;

create table exec.kill_switch (
    id                bigserial   primary key,
    tenant_id         bigint      references acct.tenant (id),
    scope             text        not null check (scope in ('GLOBAL_LIVE', 'ACCOUNT', 'STRATEGY', 'INSTRUMENT')),
    broker_account_id bigint      references acct.broker_account (id),
    strategy_id       text,
    instrument_key    text,
    reason            text        not null,
    engaged_by        text        not null,
    engaged_at        timestamptz not null default now(),
    released_by       text,
    released_at       timestamptz,
    check (scope <> 'ACCOUNT' or broker_account_id is not null),
    check (scope <> 'STRATEGY' or (tenant_id is not null and strategy_id is not null)),
    check (scope <> 'INSTRUMENT' or instrument_key is not null)
);
create index kill_switch_engaged on exec.kill_switch (scope) where released_at is null;

create table exec.audit_log (
    id                bigserial   primary key,
    tenant_id         bigint      references acct.tenant (id),
    broker_account_id bigint      references acct.broker_account (id),
    actor             text        not null,
    action            text        not null,
    detail            jsonb       not null default '{}'::jsonb,
    at                timestamptz not null default now()
);
create index audit_log_account on exec.audit_log (broker_account_id, at);
