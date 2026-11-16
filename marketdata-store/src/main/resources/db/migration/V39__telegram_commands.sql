-- Telegram controls T0 + T1 (plan docs/plans/2026-11-16-telegram-controls-plan.md, user go-ahead 16 Nov 2026).
-- The bot now takes commands, so it reads its updates with an offset (each update is used up). Every update is kept
-- here first; the /start <code> chat link reads this table instead of asking Telegram, and a command is processed once
-- (update_id is Telegram's own, unique per bot).
-- Number: written as V38, renumbered V39 when merged after chart orders (V38__chart_orders, main 18e3a69).
create table exec.telegram_update (
    update_id      bigint      primary key,
    received_at    timestamptz not null default now(),
    sent_at        timestamptz,                         -- message.date (Telegram's clock): commands older than a minute are refused
    from_user_id   bigint,
    chat_id        bigint,
    chat_type      text,
    kind           text        not null check (kind in ('MESSAGE', 'CALLBACK', 'OTHER')),
    text           text,                                -- message text or callback data
    processed_at   timestamptz,
    outcome        text
);
create index telegram_update_start on exec.telegram_update (received_at) where text like '/start %';

-- A write command (kill, flatten, exit) waits for its confirm tap: PENDING → CONFIRMED → DONE / INCOMPLETE (ran, but
-- something is not known sold) / REFUSED / FAILED,
-- or EXPIRED / CANCELLED. The nonce in the button's callback data stops a tap on an old or forged card; the confirm is
-- one compare-and-set, so a second tap does nothing. A command CONFIRMED but not finished when the worker stopped is
-- set FAILED at the next start and never run again (an exit may already have gone out).
create table exec.telegram_command (
    id                bigserial   primary key,
    update_id         bigint      not null references exec.telegram_update (update_id),
    app_user_id       bigint      not null references acct.app_user (id),
    chat_id           bigint      not null,
    tenant_id         bigint      references acct.tenant (id),
    verb              text        not null check (verb in ('KILL_GLOBAL', 'KILL_TENANT', 'KILL_ACCOUNT', 'FLATTEN', 'EXIT')),
    broker_account_id bigint      references acct.broker_account (id) on delete set null,   -- removing an account
    position_id       bigint      references exec.position (id) on delete set null,         -- is never blocked by a card
    trade_mode        text,                                -- the account's mode when the card was shown
    nonce             text        not null,
    card_message_id   bigint,
    status            text        not null default 'PENDING'
                                  check (status in ('PENDING', 'CONFIRMED', 'DONE', 'INCOMPLETE', 'REFUSED', 'FAILED',
                                                    'EXPIRED', 'CANCELLED')),
    created_at        timestamptz not null default now(),
    confirmed_at      timestamptz,
    done_at           timestamptz,
    result            text
);
create index telegram_command_open on exec.telegram_command (status) where status in ('PENDING', 'CONFIRMED');

-- The poller's last successful getUpdates (one row): the status page and the watchdog see a stopped or hijacked poller.
create table exec.telegram_poller (
    id             int         primary key check (id = 1),
    worker_id      text        not null,
    last_ok_at     timestamptz,
    last_error     text,
    last_error_at  timestamptz
);
