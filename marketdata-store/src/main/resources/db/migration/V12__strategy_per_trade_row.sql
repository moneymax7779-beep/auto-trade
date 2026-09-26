-- Several strategies can trade in one session: every trade row names its strategy, and decisions are
-- keyed by strategy too (existing rows all came from early-confirm-runner).
alter table trade.orders add column strategy_id text;
alter table trade.position add column strategy_id text;
alter table trade.rejection add column strategy_id text;
alter table trade.decision add column strategy_id text not null default 'early-confirm-runner';
alter table trade.decision drop constraint decision_pkey;
alter table trade.decision add primary key (session_id, underlying, strategy_id, snap_time);
update trade.orders set strategy_id = 'early-confirm-runner' where strategy_id is null;
update trade.position set strategy_id = 'early-confirm-runner' where strategy_id is null;
