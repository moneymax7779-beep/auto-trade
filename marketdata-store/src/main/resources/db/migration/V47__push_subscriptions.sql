-- Mobile phase 4 (17 Nov 2026): web push to the installed app. One row per browser that allowed notifications; the
-- push service's endpoint is the browser's address for us (404/410 from it: the browser dropped it, the row goes).
-- Main holds V45 and V46 (Telegram, other branch); this is the next free number.
create table acct.push_subscription (
    id            bigserial   primary key,
    app_user_id   bigint      not null references acct.app_user (id) on delete cascade,
    endpoint      text        not null unique,
    p256dh        text        not null,
    auth          text        not null,
    user_agent    text,
    created_at    timestamptz not null default now(),
    last_ok_at    timestamptz,
    last_error    text,
    failures      int         not null default 0
);
create index push_subscription_user on acct.push_subscription (app_user_id);

-- The server's own VAPID key pair (RFC 8292), made once on first use when push is switched on. Its private half only
-- lets this server's messages through the push services; it reads nothing.
create table acct.push_vapid (
    id          int         primary key check (id = 1),
    public_key  text        not null,
    private_key text        not null,
    created_at  timestamptz not null default now()
);
