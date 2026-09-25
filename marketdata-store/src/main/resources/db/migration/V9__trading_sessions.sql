-- PAPER (later LIVE) trading records. One trading session per account per market day and mode.
create table trade.session (
    id           bigserial   primary key,
    account      text        not null,
    mode         text        not null check (mode in ('PAPER_LIVE', 'PAPER_REPLAY')),
    session_date date        not null,
    feed         text        not null,
    strategy_id  text        not null,
    strategy_hash text       not null,
    configs      jsonb       not null,
    code_version text        not null,
    started_at   timestamptz not null default now(),
    ended_at     timestamptz,
    status       text        not null check (status in ('RUNNING', 'DONE', 'FAILED', 'STOPPED')),
    summary      jsonb       not null default '{}'::jsonb,
    error        text
);

create table trade.orders (
    client_order_id text             primary key,
    session_id      bigint           not null references trade.session (id),
    underlying      text             not null,
    option_side     text             not null,
    role            text             not null check (role in ('ENTRY', 'STOP', 'EXIT')),
    order_side      text             not null,
    order_type      text             not null,
    symbol          text             not null,
    quantity        bigint           not null,
    limit_price     double precision not null,
    trigger_price   double precision,
    sent_at         timestamptz      not null
);

create table trade.order_event (
    id              bigserial        primary key,
    client_order_id text             not null references trade.orders (client_order_id),
    status          text             not null,
    filled          bigint           not null,
    average_price   double precision,
    last_quantity   bigint           not null,
    last_price      double precision,
    at              timestamptz      not null,
    message         text
);
create index order_event_order on trade.order_event (client_order_id, id);

create table trade.position (
    id          bigserial        primary key,
    session_id  bigint           not null references trade.session (id),
    underlying  text             not null,
    option_side text             not null,
    symbol      text             not null,
    opened_at   timestamptz      not null,
    closed_at   timestamptz,
    stages      text[]           not null,
    exit_reason text,
    realised    double precision not null,
    costs       double precision not null,
    net         double precision not null
);

create table trade.decision (
    session_id   bigint           not null references trade.session (id),
    underlying   text             not null,
    snap_time    timestamptz      not null,
    spot         double precision,
    ce_stage     text             not null,
    pe_stage     text             not null,
    ce_scores    jsonb            not null,
    pe_scores    jsonb            not null,
    state        jsonb            not null,
    orders       text,
    primary key (session_id, underlying, snap_time)
);

create table trade.rejection (
    id         bigserial   primary key,
    session_id bigint      not null references trade.session (id),
    underlying text        not null,
    intent     text        not null,
    reason     text        not null,
    at         timestamptz not null
);

create table ops.kill_switch_event (
    id         bigserial   primary key,
    session_id bigint      references trade.session (id),
    scope      text        not null,
    engaged    boolean     not null,
    reason     text,
    at         timestamptz not null,
    by_whom    text
);
