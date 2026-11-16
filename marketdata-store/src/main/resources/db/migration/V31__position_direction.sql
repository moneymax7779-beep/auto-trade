-- A-358 (9 Nov 2026): short option positions (the PAPER expiry short straddle, expiry-short-straddle.v1).
-- LONG = bought to open (every row before this migration); SHORT = sold to open, where average_cost is the average
-- sale price (the credit received), average_exit the average buy-back, quantity the quantity sold, and realised and
-- net keep their meaning (rupees made or lost).
alter table trade.position add column direction text not null default 'LONG';
