-- One row per load of one session for one underlying. Market-data rows carry the manifest id;
-- readers use only ACTIVE manifests, so a reload is swapped in atomically by flipping status.
create table md.load_manifest (
    id              bigserial primary key,
    kind            text        not null check (kind in ('CLONE', 'CAPTURE')),
    session_date    date        not null,
    underlying      text        not null,
    source_name     text        not null,
    source_detail   text        not null,
    datasets        text[]      not null,
    status          text        not null check (status in ('RUNNING', 'ACTIVE', 'FAILED', 'SUPERSEDED')),
    tool_version    text        not null,
    started_at      timestamptz not null default now(),
    finished_at     timestamptz,
    source_counts   jsonb       not null default '{}'::jsonb,
    loaded_counts   jsonb       not null default '{}'::jsonb,
    source_digests  jsonb       not null default '{}'::jsonb,
    loaded_digests  jsonb       not null default '{}'::jsonb,
    notes           jsonb       not null default '{}'::jsonb,
    error           text
);

create unique index load_manifest_one_active
    on md.load_manifest (session_date, underlying)
    where status = 'ACTIVE';

create index load_manifest_session on md.load_manifest (session_date, underlying, id);
