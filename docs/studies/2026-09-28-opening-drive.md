# Opening drive through prior-day levels (registered 2026-09-28, before computing)

Question (user, 2026-09-28, after NIFTY broke the prior-day ORL and PDL in the first minute and fell
157 points by 09:21): would a rule that buys the ATM option on an early break of a prior-day level
have worked on the other sessions? The rule was written after seeing 28 Sep, so **28 Sep is the design
day: reported, not counted in the verdict.**

## Data (zt-tiger-v2, read-only)
- Sessions: 2, 3, 4, 7, 8, 9, 10, 11, 15, 16, 17, 18, 21, 22, 23, 24, 25 Sep 2026 (+ 28 Sep, design
  day); NIFTY and SENSEX.
- Spot: 1-minute bars `market_candles` (today: MULTI_TICK, what the live engine sees).
- Futures volume: 1-minute `market_candles` INDEX_FUTURES_MINUTE_VOLUME_* (preference LIVE_V4, then
  RECOVERED_V1, then LIVE_V3).
- Options: `option_tick_snapshots`, nearest expiry, about one snapshot a minute (bid, ask).

## Levels
- PDL / PDH: low / high of the previous session's 1-minute bars 09:15–15:14 (zt's MULTI_TICK bars end
  at 15:14 on every day, so the same window is used for all days).
- Prior-day ORL / ORH: low / high of the previous session's bars 09:15–09:29.
- Previous-session source: BROKER_HISTORICAL_BACKFILL when it has all 360 bars, otherwise MULTI_TICK
  when it has at least 355 bars including 09:15 and 15:14; otherwise the levels of that day are not
  usable and the index-day is dropped.

## Rule (fixed)
- Put side: support levels PDL and prior ORL; call side: resistance levels PDH and prior ORH.
- A level counts only if the day's first bar opens beyond it on the untouched side (put: open > level;
  call: open < level). A gap already through the level is not a break.
- Trigger: the first 1-minute bar starting 09:15–09:24 that closes below a support level (put) or
  above a resistance level (call). One trade per index-day (the first trigger).
  (Implementation note fixed before computing: when one bar breaks two levels, the structure stop uses
  the broken level nearest the price, i.e. the tighter stop.)
- Volume filter: futures volume of the trigger minute ≥ 1.5 × the median volume of the same minute over
  the previous 10 sessions (at least 5 needed; otherwise the filter cannot be evaluated).
- Entry: the first option snapshot 0–90 s after the trigger bar closes; strike = that snapshot's ATM
  strike; buy at the ask. The quote must have bid > 0 and ask ≥ bid.
- Exits (first to happen; sell at the bid of the snapshot):
  1. Structure stop: a 1-minute bar closes back at or beyond the level (put: close ≥ level) → first
     snapshot at or after that bar's close.
  2. Premium stop: bid ≤ 0.75 × entry ask.
  3. Trailing stop: once the best bid since entry reaches 1.2 × entry ask, bid ≤ 0.8 × that best bid.
  4. Time: first snapshot at or after 10:00:00.
- Costs: costs v1 (₹20 per order, STT 0.1 % on sells, exchange, SEBI, stamp, GST), one lot
  (NIFTY 65, SENSEX 20).

## Data-quality rules (a failing item is dropped and counted, never patched)
- Index-day dropped if the day's MULTI_TICK bars miss any minute 09:15–10:00, or there is no option
  snapshot at the open (4 Sep, 16 Sep known before computing), or the previous-session levels fail the
  rule above.
- Trade dropped if the traded strike has a snapshot gap over 90 s between entry and exit, or no entry
  snapshot within 90 s.

## Reported
- Primary: triggers passing the volume filter. Secondary: all triggers (no volume filter).
- Per trade: index, level, time, strike, entry ask, exit bid, exit reason, net per lot, return on
  premium. Count, win rate, mean, median, total; days with no trigger.
- The design day (28 Sep) separately. ₹5L illustration only as a scaled number (ignores market
  impact at the open).

## What counts
Descriptive: at most ~30 index-days, all seen before. A positive result would justify forward PAPER
testing, not live money.

## Results (computed 2026-09-28; script and output in the session scratchpad `od/`)

### Data used and dropped
- 34 test index-days (17 sessions × NIFTY, SENSEX) + 2 design-day index-days.
- **Dropped 10:** 4 Sep and 16 Sep (no bars/quotes at the open, both indices); 8 Sep (MULTI_TICK bar
  09:25 missing, both); 22 Sep (previous session 21 Sep has 214 bars, ticks end 12:52, both); 25 Sep
  (previous session 24 Sep has 353 bars, ends 15:07, both).
- 24 usable test index-days: **2 triggers, 22 with no trigger.** On most days the open was already
  through the prior-day levels (e.g. NIFTY 2, 9, 10, 11, 24 Sep opened below PDL: a gap, not a break) or
  never reached them by 09:24.

### Trades (one lot, costs v1, bought at the ask, sold at the bid)
| Index | Day | Side | Level | Trigger | Futures vol ratio | Strike | Entry | Exit | Reason | Return | Net/lot |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NIFTY | 21 Sep | CE | prior ORH 23358.15 | 09:16 close 23362.05 | 0.27 | 23350 | 09:17:58 @ 92.65 | 10:00:30 @ 95.15 | time | +2.7 % | +₹104 |
| SENSEX | 21 Sep | CE | prior ORH 74591.17 | 09:15 close 74620.25 | 1.00 | 74600 | 09:16:58 @ 398.40 | 10:00:30 @ 434.05 | time | +8.9 % | +₹650 |
| NIFTY | 28 Sep (design) | PE | PDL 23021.20 (+ prior ORL) | 09:15 close 22975.60 | 1.17 | 23000 | 09:16:06 @ 89.20 | 10:00:33 @ 141.75 | time | +58.9 % | +₹3,353 |
| SENSEX | 28 Sep (design) | PE | PDL 73498.76 (+ prior ORL) | 09:15 close 73374.54 | 0.50 | 73400 | 09:16:06 @ 360.60 | 10:00:33 @ 568.25 | time | +57.6 % | +₹4,087 |

- **Primary (volume filter ≥ 1.5): 0 trades on the test days — and the design day itself fails the
  filter** (NIFTY 09:15 futures volume 45,240 vs a same-minute median of 38,578 = 1.17×). The "very
  high volume" of 28 Sep was high relative to the rest of that day, not relative to other opens
  (11, 15, 24 Sep opening minutes had 94k–115k).
- Secondary (no filter): 2 trades, both small wins (+₹104, +₹650 per lot), both on one day (21 Sep).

### Sensitivity (not registered; relaxes the data rules)
Using 22 and 25 Sep anyway (incomplete previous sessions) adds no triggers. 8 Sep triggers on both
indices (PDL break 09:15) but has a 91 s quote gap (09:25:40–09:27:11); allowing it: NIFTY 23700 PE
48.75 → 54.60 (+12 %, +₹327), SENSEX 75800 PE 252.55 → 279.05 (+10.5 %, +₹473), time exits.

### Reading
The setup is rare: 2 (4 with the relaxed data) triggers in 24 usable index-days, and the one big win
is the day it was designed on. All test-day triggers made small money at the 10:00 time exit and
none hit a stop, but 2–4 trades on 1–2 days are not evidence of anything. The volume filter as
written never passes. Not enough to build a strategy; if wanted, it can run as a forward PAPER
observation (record triggers, no orders) until it has 20+ occurrences.
