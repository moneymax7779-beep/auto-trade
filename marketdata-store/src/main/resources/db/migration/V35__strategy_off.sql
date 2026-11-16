-- The owner's OFF switch per strategy (user 11 Nov 2026: "as a Owner - I should be able to select/disselect which
-- strategies which are PAPER/LIVE eligible to be in PAPER/LIVE or disselect from both PAPER and LIVE"). A row = that
-- strategy is OFF: the live sessions (PAPER-1, SIGNAL-1 and every other PAPER_LIVE session except SHADOW-*) refuse its
-- new entries and adds, so no paper account and no real-money account opens anything from it. Exits of positions it
-- already holds always run. No row = on (PAPER, and LIVE where V32/V33 and the account's risk file say so). Replays and
-- shadows never read it. Standing until the owner changes it.
create table acct.strategy_off (
    strategy    text        primary key,
    changed_by  text        not null,
    changed_at  timestamptz not null default now()
);
