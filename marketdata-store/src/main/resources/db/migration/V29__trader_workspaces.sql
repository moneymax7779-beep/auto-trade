-- Trader workspaces (user, 7 Nov 2026; docs/plans/2026-11-07-trader-workspaces-plan.md): every family member runs their
-- own workspace (tenant): their brokers, the day's broker and its PAPER/LIVE switch, their own kill switch and loss limit.
-- The platform admin keeps the platform and helps a member only through SUPPORT and the member's grant.
-- Additive, except four check constraints that gain a value. ACCOUNT_MODE workspaces (the owner's) behave as before.

-- ---------------------------------------------------------------- brokers: Groww next to Kite, Upstox and Kotak Neo

alter table acct.broker_account drop constraint broker_account_broker_type_check,
    add constraint broker_account_broker_type_check
        check (broker_type in ('PAPER', 'ZERODHA', 'UPSTOX', 'KOTAK_NEO', 'GROWW'));
alter table acct.broker_credential_profile drop constraint broker_credential_profile_broker_type_check,
    add constraint broker_credential_profile_broker_type_check
        check (broker_type in ('ZERODHA', 'UPSTOX', 'KOTAK_NEO', 'GROWW'));

-- What the Brokers page shows about a broker app without ever showing a secret: when its keys were set and by whom, the
-- last characters of the API key, and the last check (a read call after a login). Non-secret broker settings (Kotak
-- Neo's mobile number, ...) in settings; references to further secrets (Kotak Neo's consumer secret, Groww's TOTP seed
-- is never stored) in secret_refs.
alter table acct.broker_credential_profile
    add column settings        jsonb       not null default '{}',
    add column secret_refs     jsonb       not null default '{}',
    add column secret_set_at   timestamptz,
    add column secret_set_by   bigint      references acct.app_user (id),
    add column key_hint        text,
    add column verified_at     timestamptz,
    add column last_check_error text;

-- ---------------------------------------------------------------- roles: SUPPORT and the member's grant

alter table acct.tenant_membership drop constraint tenant_membership_role_check,
    add constraint tenant_membership_role_check
        check (role in ('OWNER', 'ADMIN', 'TRADER', 'VIEWER', 'AUDITOR', 'SUPPORT'));

-- A member lets SUPPORT manage their workspace for a while (or SUPPORT opens a break-glass hour with a reason, which the
-- member is told about at once). Never deleted: revoked or expired grants stay as the record.
create table acct.support_grant (
    id          bigserial   primary key,
    tenant_id   bigint      not null references acct.tenant (id),
    granted_to  bigint      not null references acct.app_user (id),
    granted_by  bigint      not null references acct.app_user (id),
    reason      text,
    break_glass boolean     not null default false,
    granted_at  timestamptz not null default now(),
    expires_at  timestamptz not null,
    revoked_at  timestamptz,
    revoked_by  bigint      references acct.app_user (id),
    check (expires_at > granted_at),
    check (not break_glass or (reason is not null and length(trim(reason)) >= 5))
);
create index support_grant_open on acct.support_grant (tenant_id, granted_to) where revoked_at is null;

-- ---------------------------------------------------------------- people: invites and their own Telegram

alter table acct.app_user
    add column relationship     text,
    add column telegram_chat_id text,
    add column last_seen_at     timestamptz;

create table acct.invite (
    id               bigserial   primary key,
    username         text        not null,
    display_name     text        not null,
    relationship     text        not null,
    email            text,
    tenant_id        bigint      not null references acct.tenant (id),
    keycloak_user_id text,
    delivery         text        not null check (delivery in ('EMAIL', 'TEMP_PASSWORD', 'EXISTING_LOGIN')),
    status           text        not null default 'PENDING' check (status in ('PENDING', 'ACCEPTED', 'REVOKED')),
    created_by       bigint      not null references acct.app_user (id),
    created_at       timestamptz not null default now(),
    accepted_at      timestamptz
);
create index invite_username on acct.invite (username);

-- A one-time code the member sends to the bot (/start <code>) so their chat id can be linked to their login.
create table acct.telegram_link (
    code        text        primary key,
    app_user_id bigint      not null references acct.app_user (id),
    created_at  timestamptz not null default now(),
    expires_at  timestamptz not null,
    used_at     timestamptz
);

-- ---------------------------------------------------------------- the day's broker and its PAPER/LIVE switch

-- ACCOUNT_MODE: every enabled follower trades in its own mode (as before). DAILY_SWITCH: the member picks one broker for
-- the day and switches it between PAPER (default every morning) and LIVE; entries go only there.
alter table acct.tenant
    add column trading_control           text           not null default 'ACCOUNT_MODE'
                                                        check (trading_control in ('ACCOUNT_MODE', 'DAILY_SWITCH')),
    add column default_broker_account_id bigint         references acct.broker_account (id),
    add column daily_loss_limit          numeric(14, 2) check (daily_loss_limit is null or daily_loss_limit > 0);

-- A real broker account's paper twin: the PAPER-broker account that takes the member's entries while that broker is
-- chosen in PAPER (same capital, strategies and limits, kept in step). Positions never move between the two.
alter table acct.broker_account
    add column paper_of_account_id bigint unique references acct.broker_account (id),
    add constraint broker_account_paper_twin_check check (paper_of_account_id is null or broker_type = 'PAPER');

-- Append-only: every choice of broker and every PAPER/LIVE switch, by a person or by the system (SESSION_LOST, ...).
create table acct.trading_day_event (
    id                bigserial   primary key,
    tenant_id         bigint      not null references acct.tenant (id),
    broker_account_id bigint      references acct.broker_account (id),
    mode              text        not null check (mode in ('PAPER', 'LIVE')),
    reason            text        not null,
    changed_by        bigint      references acct.app_user (id),
    changed_at        timestamptz not null default clock_timestamp(),
    check (mode = 'PAPER' or broker_account_id is not null)
);
create index trading_day_event_tenant on acct.trading_day_event (tenant_id, changed_at desc, id desc);

-- The workspace's state at a moment: the latest event at or before it; its mode only on the same IST day (every day
-- starts PAPER), its broker carried over; with no event at all, the workspace's default broker in PAPER.
create function acct.trading_state(p_tenant bigint, p_at timestamptz)
    returns table (broker_account_id bigint, mode text, since timestamptz)
    language sql stable as $$
    select case when e.id is null then t.default_broker_account_id else e.broker_account_id end,
           case when e.id is not null and (e.changed_at at time zone 'Asia/Kolkata')::date
                                          = (p_at at time zone 'Asia/Kolkata')::date then e.mode else 'PAPER' end,
           e.changed_at
    from acct.tenant t
    left join lateral (select x.id, x.broker_account_id, x.mode, x.changed_at from acct.trading_day_event x
                       where x.tenant_id = t.id and x.changed_at <= p_at
                       order by x.changed_at desc, x.id desc limit 1) e on true
    where t.id = p_tenant
$$;

-- Where an entry decided at p_at goes: null (Off, or nothing chosen); a PAPER-broker account itself; the real account
-- when the day is LIVE there and it can trade LIVE now (eligible, enabled, session ACTIVE); otherwise its paper twin.
create function acct.entry_target(p_tenant bigint, p_at timestamptz)
    returns bigint
    language sql stable as $$
    select case when a.id is null then null
                when a.broker_type = 'PAPER' then a.id
                when s.mode = 'LIVE' and a.trade_mode = 'LIVE' and a.live_trading_enabled and a.enabled
                     and a.connection_status = 'ACTIVE' then a.id
                else twin.id end
    from acct.trading_state(p_tenant, p_at) s
    left join acct.broker_account a on a.id = s.broker_account_id
    left join acct.broker_account twin on twin.paper_of_account_id = a.id
$$;

-- ---------------------------------------------------------------- the member's own kill switch

alter table exec.kill_switch drop constraint kill_switch_scope_check,
    add constraint kill_switch_scope_check check (scope in ('GLOBAL_LIVE', 'TENANT', 'ACCOUNT', 'STRATEGY', 'INSTRUMENT')),
    add constraint kill_switch_tenant_check check (scope <> 'TENANT' or tenant_id is not null);

-- ---------------------------------------------------------------- existing family workspaces

-- A family workspace is one a copy group is shared with (V27). It switches daily; its member owns it (a TRADER becomes
-- OWNER, a VIEWER stays VIEWER); the admin who added it becomes SUPPORT; its only follower account is its default broker.
update acct.tenant t set trading_control = 'DAILY_SWITCH'
where exists (select 1 from acct.copy_group_share s where s.tenant_id = t.id);
update acct.tenant_membership m set role = 'OWNER'
where m.role = 'TRADER' and exists (select 1 from acct.copy_group_share s where s.tenant_id = m.tenant_id);
update acct.tenant_membership m set role = 'SUPPORT'
where m.role = 'ADMIN' and exists (select 1 from acct.copy_group_share s where s.tenant_id = m.tenant_id);
update acct.tenant t set default_broker_account_id = (select min(a.id) from acct.broker_account a
                                                      where a.tenant_id = t.id and a.account_role = 'FOLLOWER_TRADE')
where t.trading_control = 'DAILY_SWITCH'
  and (select count(*) from acct.broker_account a where a.tenant_id = t.id and a.account_role = 'FOLLOWER_TRADE') = 1;

-- A real broker follower already in a family workspace gets its paper twin now (same settings and subscriptions), so a
-- PAPER day has somewhere to trade.
insert into acct.broker_account (tenant_id, app_user_id, broker_type, display_name, account_role, connection_status,
                                 trade_mode, allocated_capital, equity_mode, equity_from, risk_file, size_step_pct,
                                 daily_loss_limit, enabled, paper_of_account_id)
select a.tenant_id, a.app_user_id, 'PAPER', a.display_name || ' paper', 'FOLLOWER_TRADE', 'ACTIVE', 'PAPER',
       a.allocated_capital, a.equity_mode, a.equity_from, a.risk_file, a.size_step_pct, a.daily_loss_limit, a.enabled, a.id
from acct.broker_account a join acct.tenant t on t.id = a.tenant_id
where t.trading_control = 'DAILY_SWITCH' and a.broker_type <> 'PAPER' and a.account_role = 'FOLLOWER_TRADE'
  and not exists (select 1 from acct.broker_account x where x.paper_of_account_id = a.id);
insert into acct.strategy_subscription (copy_group_id, broker_account_id, strategy_id, enabled, mode_override,
                                        size_multiplier, enabled_at)
select s.copy_group_id, twin.id, s.strategy_id, s.enabled, null, s.size_multiplier, s.enabled_at
from acct.strategy_subscription s join acct.broker_account twin on twin.paper_of_account_id = s.broker_account_id
on conflict (copy_group_id, broker_account_id, strategy_id) do nothing;
