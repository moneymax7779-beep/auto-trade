-- A run is one execution of a research or PAPER job (feature extraction, lifecycle replay, ...).
-- It records exactly which code and config produced its outputs.
create table research.run (
    id           bigserial   primary key,
    kind         text        not null,
    status       text        not null check (status in ('RUNNING', 'DONE', 'FAILED')),
    started_at   timestamptz not null default now(),
    finished_at  timestamptz,
    code_version text        not null,
    source       text        not null,
    sessions     date[]      not null,
    underlyings  text[]      not null,
    config       jsonb       not null default '{}'::jsonb,  -- file name -> content hash
    notes        jsonb       not null default '{}'::jsonb,
    error        text
);

-- One feature snapshot per underlying per grid time. Key columns are typed for filtering; every
-- feature is in `features` (flattened section.field -> value, NaN stored as null).
create table feat.snapshot (
    run_id       bigint           not null references research.run (id),
    session_date date             not null,
    underlying   text             not null,
    snap_time    timestamptz      not null,
    phase        text             not null,
    spot         double precision,
    features     jsonb            not null,
    primary key (run_id, underlying, snap_time)
);

create index snapshot_session on feat.snapshot (session_date, underlying, snap_time);
