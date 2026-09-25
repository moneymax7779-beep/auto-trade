-- Lifecycle replay outputs. Frames are per-minute observations (one per lane and underlying);
-- episodes are traded campaigns. Reports must keep the two apart.
create table research.lifecycle_frame (
    run_id        bigint           not null references research.run (id),
    lane          text             not null,
    session_date  date             not null,
    underlying    text             not null,
    snap_time     timestamptz      not null,
    spot          double precision,
    regime        text,
    ce_stage      text             not null,
    pe_stage      text             not null,
    ce_early      double precision,
    ce_confirm    double precision,
    ce_runner     double precision,
    pe_early      double precision,
    pe_confirm    double precision,
    pe_runner     double precision,
    direction     double precision,
    participation double precision,
    structure     double precision,
    continuation  double precision,
    orders        text,
    ce_conditions jsonb            not null,
    pe_conditions jsonb            not null,
    primary key (run_id, lane, underlying, snap_time)
);

create table research.episode (
    id            bigserial        primary key,
    run_id        bigint           not null references research.run (id),
    lane          text             not null,
    session_date  date             not null,
    underlying    text             not null,
    side          text             not null,
    symbol        text,
    entry_stage   text,
    stages        text[]           not null,
    exit_reason   text,
    tranches      integer          not null,
    max_quantity  bigint           not null,
    average_cost  double precision,
    gross         double precision not null,
    costs         double precision not null,
    net           double precision not null,
    risk          double precision,
    r_multiple    double precision,
    mfe_per_unit  double precision,
    mae_per_unit  double precision,
    opened_at     timestamptz,
    closed_at     timestamptz,
    legs          jsonb            not null
);
create index episode_run on research.episode (run_id, lane, session_date);

-- Held-out discipline: a strategy configuration may be replayed on each held-out session once.
create table research.held_out_use (
    strategy_hash text        not null,
    session_date  date        not null,
    run_id        bigint      not null references research.run (id),
    used_at       timestamptz not null default now(),
    primary key (strategy_hash, session_date)
);
