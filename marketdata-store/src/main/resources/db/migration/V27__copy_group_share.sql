-- Family accounts (user, 29 Oct 2026: "family only ... start steps 1 and 2"): each family member is their own tenant, so
-- their capital, limits, kill switches, positions and audit stay apart from the owner's. A copy group (the owner's signal
-- source) is shared with such a tenant explicitly; the fan-out then gives that tenant its own trade batch for every
-- decision, and only its subscribed accounts get intents. A tenant without a share gets nothing, even with a subscription.
-- A share is never removed while the tenant's accounts hold positions: their exits come through it.
create table acct.copy_group_share (
    copy_group_id     bigint      not null references acct.copy_group (id),
    tenant_id         bigint      not null references acct.tenant (id),
    granted_by        bigint      not null references acct.app_user (id),
    granted_at        timestamptz not null default now(),
    primary key (copy_group_id, tenant_id)
);
create index copy_group_share_tenant on acct.copy_group_share (tenant_id);

-- Code review 29 Oct 2026: a subscription made or enabled during a session took every earlier ENTER and ADD of the day,
-- at the signal's old price ("demo", created 10:42, booked the 09:43 entry). From now on a follower takes an entry or add
-- only when its subscription was enabled before the decision (exits always flow). Existing rows count from this
-- migration, which is applied outside market hours.
alter table acct.strategy_subscription add column enabled_at timestamptz not null default now();
