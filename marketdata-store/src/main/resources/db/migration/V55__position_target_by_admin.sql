-- The admin's first target (user 25 Nov 2026, the exit desk: "Members too with reason, also admin for global to be exited
-- from all LIVE all workspaces with the first target, admin target"): the platform admin (SUPPORT, or no membership) may set
-- a position's first target from the exit desk, one position or every open position of a book at once, always with a reason
-- the members are told. by_admin marks such a target (the member's page says "the admin's target"); admin_reason is why.
-- Setting it again (by anyone) replaces both, as it replaces the % and starts over.
alter table exec.position_target
    add column by_admin     boolean not null default false,
    add column admin_reason text;
