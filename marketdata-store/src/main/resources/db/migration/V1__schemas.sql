-- auto-trade owns every schema below. Schemas for later phases are created empty so ownership and
-- naming are fixed from the start.
create extension if not exists timescaledb;

create schema if not exists ref;       -- reference data: weights, calendars, instruments
create schema if not exists md;        -- market data (cloned sessions and, from Phase 3, live capture)
create schema if not exists feat;      -- point-in-time feature snapshots (Phase 1)
create schema if not exists research;  -- session splits, experiments, replay runs
create schema if not exists acct;      -- tenants, users, broker accounts (Phase 5)
create schema if not exists trade;     -- signals, intents, orders, fills, positions (Phase 2-3)
create schema if not exists audit;     -- decision evidence (Phase 2)
create schema if not exists ops;       -- outbox and operational records (Phase 3)
