-- Admin exit desk (user 20 Nov 2026, decision 5): each member's one-time standing consent to the platform admin exiting
-- positions on their LIVE accounts, given (or withdrawn) by the workspace's OWNER on their Settings page. It never blocks
-- an exit (an exit reduces risk); the desk shows it per account and every admin exit writes it into its audit detail.
-- Null = not given (or withdrawn). Every change is also an exec.audit_log row (ADMIN_EXIT_CONSENT_ON / _OFF).
alter table acct.tenant add column admin_exit_consent_at timestamptz;
alter table acct.tenant add column admin_exit_consent_by bigint references acct.app_user (id);
comment on column acct.tenant.admin_exit_consent_at is
    'when the workspace OWNER consented to the platform admin exiting positions on its LIVE accounts; null = not given';
comment on column acct.tenant.admin_exit_consent_by is 'the OWNER (acct.app_user) who gave the consent';
