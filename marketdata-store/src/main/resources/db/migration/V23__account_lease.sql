-- Live execution L1, execution workers (16 Oct 2026, user: "continue L1, build the live feed tee and workers").
-- One writer per broker account: a worker holds a Postgres advisory lock for the account on its own connection for as
-- long as it trades it, and records the takeover here; epoch grows by one at every takeover and every order the worker
-- sends carries it (fencing: an order with an older epoch than the account's current one came from a stale worker).
create table exec.account_lease (
    broker_account_id bigint      primary key references acct.broker_account (id),
    owner             text        not null,
    epoch             bigint      not null,
    acquired_at       timestamptz not null,
    heartbeat_at      timestamptz not null
);
alter table exec.order_execution add column lease_epoch bigint;
