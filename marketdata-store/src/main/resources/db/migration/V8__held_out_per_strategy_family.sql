-- Held-out sessions are consumed per strategy family, not per configuration hash: a new version of
-- the same strategy (new hash) must not reuse held-out sessions an earlier version already saw.
drop table research.held_out_use;

create table research.held_out_use (
    strategy_id   text        not null,
    session_date  date        not null,
    strategy_hash text        not null,
    run_id        bigint      not null references research.run (id),
    used_at       timestamptz not null default now(),
    primary key (strategy_id, session_date)
);
