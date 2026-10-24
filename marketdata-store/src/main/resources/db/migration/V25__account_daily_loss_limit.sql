-- Account management (user, 24 Oct 2026: "start with account management on the Accounts page"): a follower account's
-- own daily loss limit in rupees. When set it replaces the risk file's daily_loss_pct for that account; null keeps the
-- percentage. Additive: no existing column or row changes meaning.
alter table acct.broker_account
    add column daily_loss_limit numeric(14, 2) check (daily_loss_limit is null or daily_loss_limit > 0);
