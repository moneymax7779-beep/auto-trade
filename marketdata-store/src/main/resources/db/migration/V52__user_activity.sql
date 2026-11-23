-- User activity (plan docs/plans/2026-11-23-user-activity-logging-plan.md; user 23 Nov 2026: "User logging - from which
-- browser, how many open sessions, how much time on the application"). Written only by trading-core's ActivityRecorder
-- (one background writer, never on a request or trading thread). Users are keyed by their sign-in name (the Keycloak
-- preferred_username, = acct.app_user.external_subject when they have an app user); no tokens or session ids are stored.

-- One sign-in: a Keycloak SSO session (its sid, else the subject and auth_time), kept across trading-core restarts.
create table acct.user_login (
    id              bigserial   primary key,
    subject         text        not null,
    login_key       text        not null unique,          -- sha256 of the sid (or subject|auth_time), hex
    signed_in_at    timestamptz not null,                  -- Keycloak's auth_time when given, else the first sight
    last_seen_at    timestamptz not null,                  -- the last request of this sign-in (any tab, polls too)
    ended_at        timestamptz,
    end_reason      text        check (end_reason in ('LOGOUT', 'IDLE', 'MAX_AGE', 'REVOKED')),
    ip              inet,                                  -- the client address (rightmost untrusted X-Forwarded-For)
    forwarded_chain text,                                  -- the X-Forwarded-For chain as received (checking the rule)
    user_agent      text,
    browser         text,
    browser_version text,
    os              text,
    os_version      text,
    device_type     text        check (device_type in ('DESKTOP', 'MOBILE', 'TABLET', 'OTHER')),
    installed_app   boolean     not null default false     -- seen running as the installed app (display-mode standalone)
);
create index user_login_subject_idx on acct.user_login (subject, signed_in_at desc);
create index user_login_open_idx on acct.user_login (last_seen_at) where ended_at is null;

-- One open tab (or installed-app window) of a sign-in; its time is what the page reported, clamped by the server.
create table acct.user_tab (
    id              bigserial   primary key,
    login_id        bigint      not null references acct.user_login (id) on delete cascade,
    tab_id          uuid        not null,
    opened_at       timestamptz not null,
    last_beat_at    timestamptz not null,
    closed_at       timestamptz,
    visible_seconds integer     not null default 0,
    active_seconds  integer     not null default 0,
    last_path       text,                                  -- the route only, never a query string
    unique (login_id, tab_id)
);
create index user_tab_open_idx on acct.user_tab (last_beat_at) where closed_at is null;

-- The seconds of each minute a user had the app on screen (any tab), at most 60: two tabs side by side count once.
create table acct.user_minute (
    subject         text        not null,
    minute          timestamptz not null,
    visible_seconds smallint    not null default 0 check (visible_seconds between 0 and 60),
    active_seconds  smallint    not null default 0 check (active_seconds between 0 and 60),  -- with input in the last 2 min
    primary key (subject, minute)
);

-- One row a user a day (IST), kept after the raw rows above expire.
create table acct.user_activity_day (
    subject         text    not null,
    day             date    not null,
    logins          integer not null,
    devices         integer not null,
    tabs            integer not null,
    visible_seconds integer not null,
    active_seconds  integer not null,
    first_seen      timestamptz,
    last_seen       timestamptz,
    by_browser      jsonb   not null default '{}'::jsonb,  -- visible seconds by "browser / os" (tab totals)
    primary key (subject, day)
);
