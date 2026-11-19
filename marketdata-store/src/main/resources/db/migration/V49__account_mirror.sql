-- A paper account that mirrors an engine paper session account (19 Nov 2026, user on DEMO-1: "it should be as same as
-- PAPER-1", answer "Mirror PAPER-1"). With mirror_of set (the session account's label, e.g. 'PAPER-1'), the account
-- takes no trades of its own (FanOut skips it) and its book and today's totals show that account's PAPER_LIVE trades
-- (trade.position) instead of its own exec.position rows. Only a PAPER account can mirror; null = trades as before.
alter table acct.broker_account add column mirror_of text;
alter table acct.broker_account add constraint broker_account_mirror_paper
    check (mirror_of is null or broker_type = 'PAPER');
comment on column acct.broker_account.mirror_of is
    'engine session account (trade.session.account, PAPER_LIVE) whose trades this PAPER account shows instead of its own; null = none';
