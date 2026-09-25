-- Index constituent weights as observed in each session's data.
create table ref.index_weight (
    session_date  date             not null,
    underlying    text             not null,
    symbol        text             not null,
    weight_pct    double precision not null,
    weight_source text,
    manifest_id   bigint           not null,
    primary key (session_date, underlying, symbol)
);

-- Which sessions are for tuning and which are held out. Declared before any study runs;
-- a study that reads HELD_OUT sessions more than once for the same hypothesis is invalid.
create table research.session_split (
    session_date date        primary key,
    split        text        not null check (split in ('TUNING', 'HELD_OUT')),
    declared_at  timestamptz not null default now(),
    note         text
);

insert into research.session_split (session_date, split, note) values
    ('2026-09-02', 'TUNING',   null),
    ('2026-09-03', 'TUNING',   'SENSEX expiry'),
    ('2026-09-04', 'TUNING',   'partial capture'),
    ('2026-09-07', 'TUNING',   null),
    ('2026-09-08', 'TUNING',   'NIFTY expiry'),
    ('2026-09-09', 'TUNING',   null),
    ('2026-09-10', 'TUNING',   'SENSEX expiry'),
    ('2026-09-11', 'TUNING',   'partial capture'),
    ('2026-09-15', 'TUNING',   'NIFTY expiry'),
    ('2026-09-16', 'TUNING',   'partial capture'),
    ('2026-09-17', 'TUNING',   'SENSEX expiry'),
    ('2026-09-18', 'TUNING',   null),
    ('2026-09-21', 'TUNING',   'partial capture'),
    ('2026-09-22', 'HELD_OUT', 'NIFTY expiry'),
    ('2026-09-23', 'HELD_OUT', null),
    ('2026-09-24', 'HELD_OUT', 'SENSEX expiry'),
    ('2026-09-25', 'HELD_OUT', null);
