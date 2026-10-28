-- A complete day's chart data (the PCR series, the OI tape), computed once from md.option_tick and kept, so a restart or
-- a deploy does not read the day's ticks again (28 Oct 2026: a whole-day read takes ~3 minutes on the GCP VM). The
-- manifest_id is the day's ACTIVE md.load_manifest id when it was computed: a reloaded day does not match and is computed
-- again. The payload is the controller's own gzip'd encoding.
create table md.chart_day_cache (
    kind         text        not null,
    session_date date        not null,
    underlying   text        not null,
    manifest_id  bigint      not null,
    payload      bytea       not null,
    created_at   timestamptz not null default now(),
    primary key (kind, session_date, underlying)
);
