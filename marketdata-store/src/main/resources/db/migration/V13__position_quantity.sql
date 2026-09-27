-- Quantity and prices of each position (the trade tables showed only P&L). New rows are written by
-- the trading service; existing rows are backfilled from their orders' fills where a session has one
-- position per strategy and contract (always so far).
alter table trade.position add column quantity bigint;
alter table trade.position add column lot_size integer;
alter table trade.position add column average_cost double precision;
alter table trade.position add column average_exit double precision;

with last_event as (
    select distinct on (client_order_id) client_order_id, filled, average_price
    from trade.order_event order by client_order_id, at desc, id desc
), fills as (
    select o.session_id, coalesce(o.strategy_id, '') strategy_id, o.symbol, o.order_side,
           sum(e.filled) quantity, sum(e.filled * e.average_price) / nullif(sum(e.filled), 0) price
    from trade.orders o join last_event e using (client_order_id)
    where e.filled > 0
    group by 1, 2, 3, 4
), single as (
    select session_id, coalesce(strategy_id, '') strategy_id, symbol
    from trade.position group by 1, 2, 3 having count(*) = 1
), priced as (
    select s.session_id, s.strategy_id, s.symbol, b.quantity, b.price cost, x.price exit_price
    from single s
    join fills b on b.session_id = s.session_id and b.strategy_id = s.strategy_id and b.symbol = s.symbol
                and b.order_side = 'BUY'
    left join fills x on x.session_id = s.session_id and x.strategy_id = s.strategy_id and x.symbol = s.symbol
                and x.order_side = 'SELL'
)
update trade.position p
set quantity = priced.quantity, average_cost = priced.cost, average_exit = priced.exit_price
from priced
where p.session_id = priced.session_id and coalesce(p.strategy_id, '') = priced.strategy_id
  and p.symbol = priced.symbol;

-- lot sizes of the index options traded so far (the instrument master for September 2026)
update trade.position set lot_size = case underlying when 'NIFTY' then 65 when 'SENSEX' then 20
    when 'BANKNIFTY' then 30 end where lot_size is null;
