-- Live execution, L0 (docs/LIVE-TRADING-PLAN.md, 16 Oct 2026; user: "go with option A, Zerodha first"). Additive only:
-- no existing table changes. acct.* = who trades with which broker account and how much capital; exec.* = the
-- durable signal -> batch -> intent -> order pipeline for follower accounts. Names follow zt-tiger-v2's
-- MULTI_USER_MULTI_BROKER_BLUEPRINT so the two can be bridged later. PAPER-1, shadows and replays keep writing trade.*.
-- Secrets are never stored here: *_ref columns name an entry in the secret store (GCP Secret Manager).

create schema if not exists exec;       -- signal decisions, trade batches and intents, broker orders, positions

-- ---------------------------------------------------------------- identity

create table acct.tenant (
    id         bigserial   primary key,
    name       text        not null unique,
    kind       text        not null default 'INDIVIDUAL' check (kind in ('INDIVIDUAL')),
    status     text        not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at timestamptz not null default now()
);

-- A person who logs in to auto-trade (Keycloak subject). App login is never broker login.
create table acct.app_user (
    id               bigserial   primary key,
    external_subject text        not null unique,
    email            text,
    display_name     text        not null,
    status           text        not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    created_at       timestamptz not null default now()
);

create table acct.tenant_membership (
    tenant_id   bigint      not null references acct.tenant (id),
    app_user_id bigint      not null references acct.app_user (id),
    role        text        not null check (role in ('OWNER', 'ADMIN', 'TRADER', 'VIEWER', 'AUDITOR')),
    created_at  timestamptz not null default now(),
    primary key (tenant_id, app_user_id)
);

-- ---------------------------------------------------------------- brokers and accounts

-- Broker app credentials (Kite Connect app, Upstox app ...): references into the secret store, never values.
create table acct.broker_credential_profile (
    id                bigserial   primary key,
    tenant_id         bigint      not null references acct.tenant (id),
    broker_type       text        not null check (broker_type in ('ZERODHA', 'UPSTOX', 'KOTAK_NEO')),
    owner_app_user_id bigint      references acct.app_user (id),
    scope             text        not null default 'USER_PRIVATE' check (scope in ('TENANT_SHARED', 'USER_PRIVATE')),
    name              text        not null,
    api_key_ref       text        not null,
    api_secret_ref    text        not null,
    redirect_uri      text,
    registered_ips    text[]      not null default '{}',
    status            text        not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    unique (tenant_id, name)
);

-- The execution boundary: every order, fill and position belongs to exactly one broker account. external_broker_user_id
-- is for display and reconciliation only and never decides ownership (the same broker user may appear twice).
-- legacy_account_label ties a row to the existing trade.session.account label (PAPER-1, SHADOW-1) without touching it.
create table acct.broker_account (
    id                      bigserial      primary key,
    tenant_id               bigint         not null references acct.tenant (id),
    app_user_id             bigint         not null references acct.app_user (id),
    broker_type             text           not null check (broker_type in ('PAPER', 'ZERODHA', 'UPSTOX', 'KOTAK_NEO')),
    credential_profile_id   bigint         references acct.broker_credential_profile (id),
    display_name            text           not null,
    external_broker_user_id text,
    legacy_account_label    text           unique,
    account_role            text           not null default 'DISABLED'
                                           check (account_role in ('SIGNAL_SOURCE', 'FOLLOWER_TRADE', 'FOLLOWER_VIEW_ONLY',
                                                                   'DISABLED')),
    connection_status       text           not null default 'PENDING_CONNECT'
                                           check (connection_status in ('PENDING_CONNECT', 'ACTIVE', 'TOKEN_EXPIRED',
                                                                        'TOKEN_REJECTED', 'DISCONNECTED', 'DISABLED')),
    trade_mode              text           not null default 'PAPER' check (trade_mode in ('PAPER', 'DRY_RUN', 'LIVE')),
    live_trading_enabled    boolean        not null default false,
    allocated_capital       numeric(14, 2) not null check (allocated_capital > 0),
    equity_mode             text           not null default 'FIXED' check (equity_mode in ('FIXED', 'COMPOUNDING')),
    equity_from             date           not null,
    risk_file               text           not null,
    size_step_pct           numeric(5, 2)  not null default 0 check (size_step_pct between 0 and 100),
    enabled                 boolean        not null default false,
    created_at              timestamptz    not null default now(),
    updated_at              timestamptz    not null default now(),
    unique (tenant_id, display_name),
    -- live needs a real broker, LIVE mode and broker credentials; a paper account can never be switched live
    check (not live_trading_enabled or (trade_mode = 'LIVE' and broker_type <> 'PAPER' and credential_profile_id is not null)),
    check (broker_type <> 'PAPER' or trade_mode = 'PAPER')
);

-- One broker session (daily access token) per broker account; never shared between accounts.
create table acct.broker_session (
    id                bigserial   primary key,
    broker_account_id bigint      not null references acct.broker_account (id),
    token_ref         text        not null,
    acquired_at       timestamptz not null,
    expires_at        timestamptz not null,
    last_validated_at timestamptz,
    last_failure_code text,
    status            text        not null check (status in ('ACTIVE', 'EXPIRED', 'REJECTED', 'REVOKED')),
    created_at        timestamptz not null default now()
);
create unique index broker_session_one_active on acct.broker_session (broker_account_id) where status = 'ACTIVE';

-- A signal source and the follower accounts that take its decisions.
create table acct.copy_group (
    id                       bigserial   primary key,
    tenant_id                bigint      not null references acct.tenant (id),
    name                     text        not null,
    signal_source_account_id bigint      not null references acct.broker_account (id),
    enabled                  boolean     not null default false,
    created_at               timestamptz not null default now(),
    unique (tenant_id, name)
);

-- Which of the signal source's strategies (by strategy id; the versions are the signal source's files) a follower takes,
-- at which size and mode.
create table acct.strategy_subscription (
    id                bigserial     primary key,
    copy_group_id     bigint        not null references acct.copy_group (id),
    broker_account_id bigint        not null references acct.broker_account (id),
    strategy_id       text          not null,
    enabled           boolean       not null default false,
    mode_override     text          check (mode_override in ('PAPER', 'DRY_RUN', 'LIVE')),
    size_multiplier   numeric(6, 3) not null default 1 check (size_multiplier > 0),
    created_at        timestamptz   not null default now(),
    unique (copy_group_id, broker_account_id, strategy_id)
);

-- ---------------------------------------------------------------- capital and ledger

-- Dated additions (+) or withdrawals (-) of an account's allocated capital, audited.
create table acct.capital_movement (
    id                bigserial      primary key,
    broker_account_id bigint         not null references acct.broker_account (id),
    effective_date    date           not null,
    amount            numeric(14, 2) not null check (amount <> 0),
    reason            text           not null,
    by_app_user_id    bigint         references acct.app_user (id),
    created_at        timestamptz    not null default now()
);

-- One row per account and session day: our own figures, the broker's, and their difference (acknowledged by a person
-- before the next live start when non-zero).
create table acct.ledger_day (
    broker_account_id bigint         not null references acct.broker_account (id),
    session_date      date           not null,
    opening_equity    numeric(14, 2) not null,
    realised_gross    numeric(14, 2) not null,
    charges           numeric(14, 2) not null,
    net               numeric(14, 2) not null,
    closing_equity    numeric(14, 2) not null,
    trades            integer        not null,
    broker_net        numeric(14, 2),
    broker_charges    numeric(14, 2),
    difference        numeric(14, 2),
    acknowledged_at   timestamptz,
    acknowledged_by   bigint         references acct.app_user (id),
    created_at        timestamptz    not null default now(),
    primary key (broker_account_id, session_date)
);

-- ---------------------------------------------------------------- execution pipeline

-- A broker-neutral decision of a signal source (strategies on a virtual account that is never refused). ENTER and ADD
-- are published when the virtual account's risk approves them; EXIT when its position closes, for any reason (strategy
-- exit, premium stop, trail, square-off, session end). A straddle's two legs share leg_group.
create table exec.signal_decision (
    id                bigserial        primary key,
    copy_group_id     bigint           not null references acct.copy_group (id),
    signal_session_id bigint           not null,
    session_date      date             not null,
    decided_at        timestamptz      not null,
    strategy_id       text             not null,
    priority          integer          not null,
    underlying        text             not null,
    action            text             not null check (action in ('ENTER', 'ADD', 'EXIT')),
    option_side       text             not null check (option_side in ('CE', 'PE')),
    leg_group         text,
    stage             text,
    reason            text             not null,
    instrument_token  bigint           not null,
    instrument_key    text,
    symbol            text             not null,
    lot_size          integer          not null,
    reference_price   double precision,
    premium_budget    double precision,
    reference_capital double precision,
    lots              integer,
    idempotency_key   text             not null unique,
    created_at        timestamptz      not null default now()
);
create index signal_decision_day on exec.signal_decision (copy_group_id, session_date, decided_at);

-- One decision (or a manual, kill-switch or recovery action) expanded to many accounts.
create table exec.trade_batch (
    id                 bigserial   primary key,
    tenant_id          bigint      not null references acct.tenant (id),
    copy_group_id      bigint      references acct.copy_group (id),
    signal_decision_id bigint      references exec.signal_decision (id),
    source             text        not null check (source in ('SIGNAL', 'MANUAL', 'AUTO_EXIT', 'RECOVERY', 'KILL_SWITCH')),
    requested_by       bigint      references acct.app_user (id),
    idempotency_key    text        not null,
    status             text        not null check (status in ('CREATED', 'FANNED_OUT', 'DONE', 'FAILED')),
    created_at         timestamptz not null default now(),
    unique (tenant_id, idempotency_key)
);

-- One executable command for one broker account. A worker claims it (row lock), re-validates account, session, quote,
-- capital and risk immediately before the broker call, and never retries an uncertain order without reconciling.
create table exec.trade_intent (
    id                bigserial        primary key,
    tenant_id         bigint           not null references acct.tenant (id),
    trade_batch_id    bigint           not null references exec.trade_batch (id),
    broker_account_id bigint           not null references acct.broker_account (id),
    broker_type       text             not null,
    execution_mode    text             not null check (execution_mode in ('PAPER', 'DRY_RUN', 'LIVE')),
    strategy_id       text             not null,
    priority          integer          not null,
    underlying        text             not null,
    action            text             not null check (action in ('ENTER', 'ADD', 'EXIT')),
    option_side       text             not null check (option_side in ('CE', 'PE')),
    instrument_token  bigint,
    symbol            text,
    quantity          bigint           check (quantity > 0),
    state             text             not null check (state in ('CREATED', 'VALIDATED', 'REJECTED', 'SKIPPED',
                                                                 'EXECUTING', 'EXECUTED', 'FAILED', 'CANCELLED',
                                                                 'RECONCILIATION_REQUIRED')),
    reject_code       text,
    reject_reason     text,
    idempotency_key   text             not null,
    claimed_at        timestamptz,
    claimed_by        text,
    attempts          integer          not null default 0,
    created_at        timestamptz      not null default now(),
    updated_at        timestamptz      not null default now(),
    unique (tenant_id, broker_account_id, idempotency_key)
);
create index trade_intent_open on exec.trade_intent (broker_account_id, state, created_at)
    where state in ('CREATED', 'VALIDATED', 'EXECUTING', 'RECONCILIATION_REQUIRED');

-- Every broker order attempt (entry slices, protective stop, exit) with the normalised state.
create table exec.order_execution (
    id                 bigserial        primary key,
    tenant_id          bigint           not null references acct.tenant (id),
    trade_intent_id    bigint           not null references exec.trade_intent (id),
    broker_account_id  bigint           not null references acct.broker_account (id),
    broker_type        text             not null,
    role               text             not null check (role in ('ENTRY', 'STOP', 'EXIT', 'EMERGENCY_EXIT')),
    client_tag         text             not null,
    broker_order_id    text,
    attempt_no         integer          not null,
    order_side         text             not null check (order_side in ('BUY', 'SELL')),
    order_type         text             not null,
    quantity_requested bigint           not null check (quantity_requested > 0),
    quantity_filled    bigint           not null default 0,
    limit_price        double precision,
    trigger_price      double precision,
    average_fill_price double precision,
    status             text             not null check (status in ('CREATED', 'SUBMITTED', 'ACKNOWLEDGED', 'OPEN',
                                                                  'PARTIALLY_FILLED', 'FILLED', 'CANCEL_PENDING',
                                                                  'CANCELLED', 'REJECTED', 'EXPIRED', 'UNKNOWN',
                                                                  'RECONCILIATION_REQUIRED')),
    raw_status         text,
    raw_error          text,
    created_at         timestamptz      not null default now(),
    updated_at         timestamptz      not null default now(),
    unique (broker_account_id, client_tag)
);
create unique index order_execution_broker_order on exec.order_execution (broker_account_id, broker_type, broker_order_id)
    where broker_order_id is not null;

-- A position is bound to the broker account (and broker) that opened it; its exit routes there.
create table exec.position (
    id                    bigserial        primary key,
    tenant_id             bigint           not null references acct.tenant (id),
    broker_account_id     bigint           not null references acct.broker_account (id),
    entry_trade_intent_id bigint           not null references exec.trade_intent (id),
    strategy_id           text             not null,
    underlying            text             not null,
    option_side           text             not null check (option_side in ('CE', 'PE')),
    instrument_token      bigint           not null,
    symbol                text             not null,
    quantity_open         bigint           not null check (quantity_open >= 0),
    average_entry         double precision not null,
    opened_at             timestamptz      not null,
    closed_at             timestamptz,
    exit_reason           text,
    realised_gross        double precision,
    charges               double precision,
    status                text             not null check (status in ('OPEN', 'CLOSED', 'RECONCILIATION_REQUIRED'))
);
create index position_open on exec.position (broker_account_id, status) where status <> 'CLOSED';
