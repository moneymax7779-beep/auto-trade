-- The owner's global LIVE strategy list (user 9 Nov 2026: "as admin I should be able to select or deselect the live
-- strategies at global"; "its not per day as long as I want to keep or not keep as live strategy"). One row at most:
-- no row = not set (each account's risk-file default applies). A strategy not in the list trades on paper accounts only,
-- never with real money. An account's own choice (V32 live_strategies) still overrides it for that account, and neither
-- can go beyond the account's risk file (live.strategies).
create table acct.live_strategy_policy (
    id          int         primary key check (id = 1),
    strategies  text[]      not null,
    changed_by  text        not null,
    changed_at  timestamptz not null default now()
);
