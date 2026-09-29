# Intraday range break with a futures-volume surge (registered 2026-09-28, before computing)

Question (user, 2026-09-28, NIFTY chart: a 58-point range 10:15–14:00, broken at 14:17 on a futures
volume spike, then −42 points by 14:39 and back by 14:53): is "range → break on a volume surge" a
tradable setup on ATM options across the sessions we have, and does entry speed (first tick through
the level vs the 1-minute close) decide it? **28 Sep is the design day** (the ≤ 5 × ATR range limit
was drafted with it in view: its range was 4.96 × ATR): reported, not counted in the verdict.

## Data (zt-tiger-v2, read-only; 2–10 Sep from the verified archive files, no restore)
- Sessions 2, 3, 4, 7, 8, 9, 10, 11, 15, 16, 17, 18, 21, 22, 23, 24, 25 Sep (+ 28 Sep); NIFTY, SENSEX.
- Spot 1-minute bars (MULTI_TICK); futures 1-minute volume (LIVE_V4, then RECOVERED_V1, then
  LIVE_V3); option snapshots (nearest expiry, about one a minute, bid/ask); for the tick entry, index
  CASH ticks and option ticks (best bid/ask) in the break minute.

## Rule (fixed)
- ATR3m: Wilder, 14 periods, on 3-minute bars built from the day's 1-minute bars aligned to 09:15
  (first true range = high − low; no previous-session seed: it is warm long before 10:45).
- Box at a bar starting at minute t: high and low of the 90 one-minute bars before it, [t − 90, t).
  Compressed when box high − box low ≤ 5 × ATR3m (ATR as of the last 3-minute bar closed before t).
- Break: a bar starting 10:45–14:44 whose close is beyond a compressed box (above the high → call,
  below the low → put). One trade per index-day: the first qualifying break.
- Volume surge: futures volume of the break minute ≥ 3 × the median of the same minute over the
  previous 10 sessions (≥ 5 needed, otherwise not evaluable). (Implementation note fixed before any
  result: zt records some futures minutes as volume 0, which is a gap in its record, not a quiet
  minute; a 0 is treated as missing, both for the break minute and in the history.)
- **Entry M (minute close)**: the first option snapshot 0–90 s after the break bar closes; strike =
  that snapshot's ATM strike; buy at the ask (bid > 0, ask ≥ bid).
- **Entry T (first tick)**: the first index tick beyond a compressed box in a bar starting
  10:45–14:44; ATM strike at that tick's price (nearest strike: NIFTY 50, SENSEX 100); buy at the ask
  of that option's first tick 0–5 s later. The volume of the minute is not known yet, so T is measured
  **without** the volume filter (honest), and with it only as a look-ahead upper bound.
- Exits (first to happen; sell at the bid of the option snapshot, about once a minute):
  1. Back inside: a 1-minute bar closes back inside the box (put: close ≥ box low) → first snapshot
     at or after that bar's close.
  2. Stop: bid ≤ 0.80 × entry ask.
  3. Target: bid ≥ 1.20 × entry ask.
  4. Time: first snapshot at or after entry + 30 minutes, or 15:10, whichever is earlier.
- Costs: costs v1, one lot (NIFTY 65, SENSEX 20).

## Data-quality rules (a failing item is dropped and counted, never patched)
- A break is evaluated only if its 90 box bars, the break bar and the bars to the exit are all
  present; otherwise it is dropped and the next break that day is not taken instead (the day is
  dropped for that index).
- Trade dropped if the traded strike has a snapshot gap over 90 s between entry and exit, no entry
  quote within the window, or a crossed / empty quote at entry.

## Reported
- Primary: M with the volume filter. Secondary: M without it; T without it; T with it (look-ahead).
- Per trade: index, day, side, box, break time, volume ratio, strike, entry, exit, reason, return,
  net per lot. Count, wins, mean, median, total; index-days dropped and why; days with no break.
- The design day separately.

## What counts
Descriptive: ~34 index-days, all seen before. A positive primary result would justify forward PAPER
observation, not a live strategy.

## Results (computed 2026-09-28; script, data and output in the session scratchpad `rb/`)

### Data used and dropped
- 34 test index-days + 2 design-day. **15 test index-days dropped** because a 1-minute spot bar is
  missing before any break (NIFTY 2, 3, 4, 8, 9, 16, 17 Sep; SENSEX 2, 4, 8, 9, 11, 16, 17, 21 Sep);
  SENSEX 3 Sep kept only for T (a bar missing before the M exit). SENSEX 22 Sep: no break.
- SENSEX futures volume is missing (0 or absent) in many break minutes, so the volume filter could
  not be evaluated for most SENSEX breaks (shown "—"): the filtered results are mostly NIFTY.

### Results per lot after costs (test days, 28 Sep excluded)
| Variant | Trades | Wins | Mean | Median | Total | Exits |
| --- | --- | --- | --- | --- | --- | --- |
| **M, volume ≥ 3× (primary)** | 8 | 2 | +₹8 | −₹38 | +₹63 | 6 back inside, 1 target, 1 time |
| M, no volume filter | 16 | 3 | −₹138 | −₹152 | −₹2,209 | 13 back inside, 1 target, 2 time |
| T (first tick), no filter | 18 | 4 | −₹60 | −₹130 | −₹1,074 | 16 back inside, 1 target, 1 time |
| T with the filter (look-ahead, upper bound) | 3 | 2 | +₹362 | +₹206 | +₹1,086 | |

- The only target hit in any variant is **22 Sep NIFTY PE 13:53** (expiry day): M +₹701, T +₹1,016.
  Without it the primary total is −₹638 and T-with-filter +₹70.
- **Most first breaks were false**: the index closed back inside the box within 1–5 minutes
  (13 of 16 M, 16 of 18 T).
- Speed: entering on the first tick was cheaper than the minute close on the same breaks (mean
  −₹60 vs −₹138) but still negative; the problem is false breaks, not only late entry.

### The design day
- The rule's first break on 28 Sep was NIFTY 11:11 (back inside after 3 minutes, −₹210 M), not the
  14:17 break on the user's chart; SENSEX's primary trade was its 14:18 break, −8.3 % (−₹698).
- The chart's NIFTY 14:17 break under the same exits (descriptive): box 22,809.45–22,848.65 (39 points),
  22800 PE bought 14:18:13 at 77.35, never back inside, never at −20 % or +20 % (best +16 %), time exit
  14:48:30 at 76.20: −1.5 %.

### Reading
No edge: the primary is flat (+₹63 over 8 trades, one trade carries it), the unfiltered versions lose,
and first breaks of a 90-minute box are mostly false. A volume surge helps (filtered beats
unfiltered), but the one convincing trade is a single expiry-day afternoon. Not a strategy.
