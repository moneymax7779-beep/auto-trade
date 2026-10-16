-- Live execution L1, sizing parity (16 Oct 2026, user: "continue L1, follow straddles and fix the sizing parity").
-- The inputs a follower needs to size exactly as its own risk file would (as PAPER-1's risk engine does):
--   exec.signal_decision.context: strategy_lots / intended_lots (the strategy's plan units, e.g. a first tranche of 1 of
--     4), liquidity_lots (risk v12 participation cap on the virtual account; absent = no cap), rvol_tod (risk v17 boost),
--     stop_pct; straddle legs carry call_ask / put_ask.
--   exec.position.sizing_scale: the scale fixed at the entry (budget / plan premium), applied to every later add.
alter table exec.signal_decision add column context jsonb not null default '{}'::jsonb;
alter table exec.position add column sizing_scale double precision;
