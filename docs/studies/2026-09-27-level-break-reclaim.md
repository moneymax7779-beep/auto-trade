# Support break → reclaim across 17 sessions (registered 2026-09-27, before computing)

Question (user, 2026-09-27, after studying 25 Sep NIFTY): spot broke ORL / day open / PDL, came back,
and then rallied; futures OI did not build on the break, put OI fell with seller-initiated put trades
after the low, call trades turned buyer-initiated. Does this pattern repeat across all sessions we
have, and what follows it? "Be genuine; if data is not correct, ignore it."

## Data
- Sessions: 2, 3, 4, 7, 8, 9, 10, 11, 15, 16, 17, 18, 21, 22, 23, 24, 25 Sep 2026 (17), NIFTY and
  SENSEX. 11–25 Sep from zt-tiger-v2 (read-only); 2–10 Sep read directly from the verified archive
  files (`/Volumes/Expansion/zt-tiger-v2-archive`, SHA-256 as in its manifest), no database restore.
- Raw ticks: index (CASH), the nearest-expiry future (FUTURE: price, cumulative volume, OI), options
  of the nearest weekly expiry (bid/ask/quantities, LTP, cumulative volume, OI).
- Levels (the engine's definitions): day open = first index tick at or after 09:15:00; ORL/ORH = low/
  high of index ticks 09:15:00–09:29:59; PDL/PDH = the previous session's low/high. Checked against the
  engine's own values (features v5 files, 11–25 Sep) before use; a level that does not match is not used.

## Data-quality rules (a failing item is dropped and counted, never patched)
- An index-day is used only if index ticks cover 09:15–15:15 with no gap over 60 s during 09:30–15:15;
  partial-capture days that fail this are dropped whole.
  (Implementation note found in a test run, before any result: zt-tiger-v2 records index ticks until
  15:14:59, so "covers to 15:15" is checked as a last tick at or after 15:14:00.)
- An event is dropped if, from 5 minutes before the break to 30 minutes after the reclaim, the index,
  the future or an ATM ± 2 option has a gap over 60 s, or an option quote is crossed or empty.
- PDL is not used when the previous session is not available.

## Event (fixed)
- Support levels: ORL, day open, PDL (only after the opening range completes).
- Break: the index trades below the level at a time 09:30–14:30, having been at or above it for the
  previous 10 minutes.
- Reclaim: within 10 minutes of the break the index is back at or above the level and stays there for
  60 s; the reclaim time is the start of that 60 s. No reclaim within 10 minutes = a "held break"
  (reported as the comparison group).
- Levels within 2 minutes of each other on the same break are one event (the first level broken).
- Depth = level − lowest index price between break and reclaim.

## Measured at each event
- Futures OI change from 1 minute before the break to the reclaim (%, once-a-minute publications).
- ATM ± 2 (ATM at the low) CE and PE OI change from the low to the reclaim.
- Buyer-initiated share of CE and PE trades (ATM ± 2) from the low to the reclaim (a trade at or above
  the previous ask is a buy, at or below the previous bid a sell, else by the mid).
- "25-Sep-like" = futures OI change ≤ +0.05 % and PE OI change < 0 and PE buyer share < 50 %.

## Outcomes (after the reclaim)
- Index change at +5, +15, +30 min; highest and lowest index price within 30 min; whether the level
  breaks again (trades below it) within 30 min.
- A trade: buy the ATM CE at the ask 250 ms after the reclaim, sell at the bid at +15 and +30 min, one
  lot, full costs (costs v1).
- Baseline for the same days: the same CE trade and index changes at every 15th minute 09:45–14:30.

## What counts
Descriptive only: every session here has been looked at before, and one of them (25 Sep) is where the
pattern was seen. Nothing here is evidence of an edge; it says whether the pattern repeats and how often.

## Results (computed 2026-09-27; unchanged output in the session scratchpad)

### Data used and dropped
- Levels: tick ORL / day open / PDL match the engine on every checked index-day (18, 22, 23, 25 Sep);
  25 Sep NIFTY ORH differs by 0.60 (not a level used here).
- **18 of 34 index-days dropped** by the coverage rule: 2 Sep (index gap 918 s), 4 Sep (ticks from
  13:34 only), 9 Sep (gap 132 s), 11 Sep (ticks end 13:04), 15 Sep (gap 152 s), 16 Sep (ticks from
  09:26), 17 Sep (gap 568 s), 21 Sep (ticks end 12:52), 24 Sep (ticks end 15:08). Used: 3, 7, 8, 10,
  18, 22, 23, 25 Sep, both indices.
- PDL not used on 7 index-days (previous session's MULTI_TICK candles incomplete and differing from the
  backfill). 7 events dropped for option quote gaps over 60 s.
- 45 reclaim events on 13 index-days, 7 held breaks.

### A flaw in the registered rule, found before reading the results as final
The registered reclaim time is the *start* of the 60 s hold, but the hold is only known 60 s later:
entering there is look-ahead. Results are shown both ways; **only the corrected version (entry and
outcomes from the confirmed hold, reclaim + 60 s) is honest.**

| Group (both indices) | Registered (look-ahead) CE +30 min | **Corrected CE +30 min** | Corrected CE +15 min |
| --- | --- | --- | --- |
| All reclaims (45) | +₹288 mean, win 56 % | **+₹159 mean, median −₹237, win 44 %** | +₹26 |
| 25-Sep-like (21 corrected) | +₹440 | **+₹311, median +₹154, win 57 %** | +₹130 |
| Not 25-Sep-like (24) | +₹196 | **+₹26, median −₹261, win 33 %** | −₹66 |
| Held break, no reclaim (7) | −₹461 | **−₹461, win 14 %** | −₹393 |
| Baseline, every 15 min (320) | −₹137 | −₹137, win 36 % | −₹100 |

### Concentration and the day the pattern came from (corrected version)
- All reclaims: three events are more than 100 % of the total; without 25 Sep SENSEX alone the mean is
  −₹2.
- **Excluding 25 Sep entirely** (the pattern was chosen there): all reclaims CE +30 min −₹46 mean,
  median −₹256, win 38 % (95 % CI of the mean −₹269 … +₹193); 25-Sep-like 18 events +₹1 at 30 min
  (median −₹80, CI −₹330 … +₹331) and +₹133 at 15 min (median +₹25, CI −₹66 … +₹350).
- The index itself drifted up after 25-Sep-like reclaims (up at +15 and +30 min in 67 % of 18 events),
  but a 1-lot ATM call did not turn that into money after costs and decay.

### Reading
After a support break is reclaimed and held, the index more often goes up than down over the next 15–30
minutes, and a break that is *not* reclaimed within 10 minutes more often keeps falling (n = 7). The
25-Sep-like order-flow conditions look better than the rest, but on the days other than 25 Sep the
difference is inside the noise (18 events), and the ATM-call trade is not profitable at 30 minutes.
Nothing here justifies a strategy; it is a hypothesis to register for forward testing if wanted.
