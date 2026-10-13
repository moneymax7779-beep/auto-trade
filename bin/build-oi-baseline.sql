-- Features v12 (A-090): builds ref.oi_baseline from the stored one-minute bars (hist.candle), matching the live feature
-- OptionChainState.ceOiChange5m / peOiChange5m divided by the near-ATM OI (calls and puts within near_atm strikes).
-- The definition is set once below (psql variables) and stored in columns; the engine refuses a baseline whose
-- definition differs from the running features file's options_chain settings.
--   * the snapshot at time T uses the bar that closes at T (bar_start = T - 1 min); ATM = the index close at T rounded
--     to the strike step (NIFTY 50, SENSEX 100); the nearest expiry on or after the day;
--   * every contract of that expiry within the day's strike band gets a value at every minute: its last bar's OI
--     carried forward (bars exist only for minutes with a trade; the live chain keeps the last OI it saw), starting at
--     its first bar of the day (the live chain knows a contract from its first tick);
--   * OI change = OI(T) - OI(T - window); weight 1 within heavy strikes of ATM, outer_weight up to near_atm strikes;
--   * cells: underlying x side x calendar days to expiry (0-4, 4 = 4+) x 15-minute bucket from 09:15, T from 09:20 to
--     15:29 (bucket 24 = 15:15-15:29; the live z is NaN from 15:30). Statistics: median, MAD, count.
-- Run: docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -q < bin/build-oi-baseline.sql
\set ON_ERROR_STOP on
\set near_atm 3
\set heavy 2
\set outer_weight 0.5
\set window_min 5
\timing on
begin;

create temp table idx on commit drop as
select underlying, bar_start + interval '1 minute' t, (bar_start at time zone 'Asia/Kolkata')::date d, close spot,
       case underlying when 'NIFTY' then 50.0 else 100.0 end step
from hist.candle where kind = 'INDEX' and underlying in ('NIFTY', 'SENSEX')
  and (bar_start at time zone 'Asia/Kolkata')::time between '09:14' and '15:28';
create index on idx (underlying, t);

create temp table near on commit drop as
select underlying, (bar_start at time zone 'Asia/Kolkata')::date d, min(expiry) e
from hist.candle where kind = 'OPTION' and expiry >= (bar_start at time zone 'Asia/Kolkata')::date
group by 1, 2;

-- the day's strike band: every strike the ATM could be within near_atm of, plus one step
create temp table band on commit drop as
select underlying, d, min(round(spot / step) * step) - (:near_atm + 1) * min(step) lo,
       max(round(spot / step) * step) + (:near_atm + 1) * min(step) hi
from idx group by 1, 2;

create temp table opt on commit drop as
select o.underlying, n.d, n.e, o.strike, o.option_type side, o.bar_start + interval '1 minute' t, o.oi
from hist.candle o
join near n on n.underlying = o.underlying and n.d = (o.bar_start at time zone 'Asia/Kolkata')::date and o.expiry = n.e
join band b on b.underlying = o.underlying and b.d = n.d and o.strike between b.lo and b.hi
where o.kind = 'OPTION' and o.oi is not null;

-- every contract at every minute from its first bar of the day, OI carried forward
create temp table grid on commit drop as
with contracts as (select underlying, d, e, strike, side, min(t) first_t from opt group by 1, 2, 3, 4, 5),
g as (select c.underlying, c.d, c.e, c.strike, c.side, i.t, o.oi
      from contracts c join idx i on i.underlying = c.underlying and i.d = c.d and i.t >= c.first_t
      left join opt o on o.underlying = c.underlying and o.d = c.d and o.strike = c.strike and o.side = c.side and o.t = i.t),
k as (select *, count(oi) over (partition by underlying, d, strike, side order by t) grp from g)
select underlying, d, e, strike, side, t,
       first_value(oi) over (partition by underlying, d, strike, side, grp order by t) oi
from k;
create index on grid (underlying, d, strike, side, t);

create temp table x on commit drop as
with joined as (
  select i.underlying, i.d, i.t, g.e, g.side, abs(round((g.strike - round(i.spot / i.step) * i.step) / i.step)) off,
         g.oi, g.oi - p.oi change
  from idx i
  join grid g on g.underlying = i.underlying and g.d = i.d and g.t = i.t
   and abs(g.strike - round(i.spot / i.step) * i.step) <= :near_atm * i.step
  left join grid p on p.underlying = g.underlying and p.d = g.d and p.strike = g.strike and p.side = g.side
   and p.t = g.t - make_interval(mins => :window_min)
)
select underlying, d, t, (e - d) dte, sum(oi) near_oi,
       sum(case when side = 'CE' and change is not null then (case when off <= :heavy then 1.0 else :outer_weight end) * change end) ce,
       sum(case when side = 'PE' and change is not null then (case when off <= :heavy then 1.0 else :outer_weight end) * change end) pe
from joined group by 1, 2, 3, 4;

create temp table obs on commit drop as
select underlying, side, least(greatest(dte, 0), 4) dte_bucket,
       floor(extract(epoch from ((t at time zone 'Asia/Kolkata')::time - time '09:15')) / 900)::int minute_bucket, d, v
from (select underlying, d, t, dte, 'CE' side, ce / near_oi v from x where near_oi > 0 and ce is not null
      union all
      select underlying, d, t, dte, 'PE', pe / near_oi from x where near_oi > 0 and pe is not null) y
where (t at time zone 'Asia/Kolkata')::time >= '09:20' and (t at time zone 'Asia/Kolkata')::time < '15:30';

create temp table med on commit drop as
select underlying, side, dte_bucket, minute_bucket, percentile_cont(0.5) within group (order by v) median, count(*) n,
       min(d) first_day, max(d) last_day
from obs group by 1, 2, 3, 4;

delete from ref.oi_baseline;
insert into ref.oi_baseline (underlying, side, dte_bucket, minute_bucket, n, median, mad, first_day, last_day, params,
                             near_atm_strikes, heavy_weight_strikes, outer_strike_weight, window_min)
select m.underlying, m.side, m.dte_bucket, m.minute_bucket, m.n, m.median,
       percentile_cont(0.5) within group (order by abs(o.v - m.median)), m.first_day, m.last_day,
       format('near_atm %s, heavy %s, outer %s, window %s min, / near-ATM OI, OI carried forward, hist.candle bars',
              :near_atm, :heavy, :outer_weight, :window_min),
       :near_atm, :heavy, :outer_weight, :window_min
from med m join obs o using (underlying, side, dte_bucket, minute_bucket)
group by m.underlying, m.side, m.dte_bucket, m.minute_bucket, m.n, m.median, m.first_day, m.last_day;

select underlying, side, count(*) cells, sum(n) observations, min(first_day), max(last_day),
       round(avg(median)::numeric, 6) avg_median, round(avg(mad)::numeric, 6) avg_mad
from ref.oi_baseline group by 1, 2 order by 1, 2;
commit;
