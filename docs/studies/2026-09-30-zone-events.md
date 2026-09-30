# Zone map and zone events (registered 2026-09-30, before computing)

Question (user, 2026-09-30, after the NIFTY chart review): the day's tradeable moves came from price
reacting to *zones* (nearby levels read as one) — a break that failed and reversed (10:32, 13:42), a
support that held (11:18–11:28), a second break after a failed first one (12:23) — not from the single
levels the strategies watch (NIFTY closed through ORH 8 times on 30 Sep). Before any strategy is built:
how often does each zone event happen across the recorded sessions, and what did the index and an ATM
option do after it? **30 Sep 2026 is the design day: reported, not counted.** Report only; no strategy,
no threshold file, nothing deployed.

## Data (read-only, after market hours)
- Per-minute feature snapshots computed by the engine itself (`bin/autotrade features`, features v7,
  source zt-tiger-v2), so every level is the value the live system had at that minute.
- Option quotes: zt `option_tick_snapshots`, nearest expiry, about one a minute (bid, ask).
- Sessions: those zt-tiger-v2 holds complete: 11, 15, 16, 17, 18, 21, 28, 29 Sep (test; all seen by
  earlier studies), 22, 23, 24, 25 Sep (held-out split, read once for this hypothesis, reported apart;
  earlier descriptive studies also used them, so they are not unseen for level ideas), 30 Sep (design).
  NIFTY and SENSEX.
- **Not used: 2–10 Sep.** They exist only in the archive; restoring it into the scratch database needs
  about 13 GB and the Mac had 13 GB free on 30 Sep. They can be added later with the same code.

## Zone map (fixed once per index-day, at the 09:30 snapshot, when the opening range is complete)
- Levels: ORH, ORL, PDH, PDL, prior-day ORH, prior-day ORL, day open, previous close (the engine's
  `structure.*` values at 09:30). A level missing at 09:30 is left out. Moving references (session
  mean, EMAs, swing points) are not zone levels.
- Merge: sort the levels; a level joins the current zone when its gap to the zone's highest level is
  ≤ 0.5 × ATR3m (the engine's `structure.atr3m` at 09:30). Zone = [lowest, highest] of its levels;
  a single level is a zone of zero width. Strength = number of levels merged.
- Sensitivity (reported, not primary): merge distance 0.25 × and 1.0 × ATR3m.

## Events (09:30 to 14:45, on 1-minute closes)
(Implementation note fixed before any result, 30 Sep 19:4x: `structure.lastBarClose` is the close of the
last *3-minute* bar, so it is not used. The 1-minute close of the bar ending at minute t is the snapshot's
`spot` at t — the last index tick before t:00.)
Band = 0.25 × ATR3m at 09:30. For a zone [lo, hi]:
- **BREAK_UP** at close t: close > hi, and the 10 closes before were all ≤ hi. Direction: bullish.
  **BREAK_DOWN**: close < lo, 10 closes before all ≥ lo. Bearish.
- **FAIL** (of a break): within 10 minutes after BREAK_UP a close ≤ hi (back inside or through) → event
  at that close, direction bearish; mirror for BREAK_DOWN (close ≥ lo → bullish).
- **ACCEPT**: a break with no FAIL in its 10 minutes → event at break + 10 minutes, the break's direction.
- **SECOND_BREAK**: a BREAK whose zone already had a FAILed break in the same direction earlier that
  day. Direction: the break's. (Counted also as a BREAK.)
- **HOLD**: price comes from above (10 closes before all > hi + band), a close lands within
  [lo, hi + band] (a test, no close below lo), and within the next 10 minutes a close ≥ hi + 2 × band
  with no close < lo in between → event at that close, bullish. Mirror from below (resistance holds) →
  bearish.
- Sensitivity: FAIL / ACCEPT window 5 and 20 minutes.
- An event is ignored when the zone is not yet crossed-able (opening range before 09:30) or after 14:45.

## Outcomes (fixed; nothing is tuned)
- Entry: the first option snapshot 0–90 s after the event close; strike = ATM at entry (nearest strike
  to spot); buy the event direction's option (bullish → CE, bearish → PE) at the ask (bid > 0, ask ≥ bid).
- Index move in the event direction at +5, +15, +30 minutes (points and in ATR3m units).
- Option: sell at the bid of the first snapshot at or after +5, +15, +30 minutes; return, and net per
  lot after costs v1 (one lot: NIFTY 65, SENSEX 20). Best and worst bid over the 30 minutes (MFE / MAE).
- Baseline: the same option measures for an entry every 5th minute 09:35–14:45 in both directions on the
  same index-days (what "just buying ATM" does here: theta and spread).

## Recorded, not filtered on (descriptive splits, small samples flagged)
Zone strength; expiry day or not; time bucket (09:30–11:00, 11:00–13:00, 13:00–14:45); futures OI
change 3 m (sign); ATM IV change 3 m (sign); premium response of the traded side; mover breadth sign
vs direction; futures RVOL (time-of-day); an OI wall (`options.callBarrierStrike` /
`putSupportStrike`) inside the zone ± one strike step.

## Data-quality rules (dropped and counted, never patched)
- An index-day is dropped if a snapshot or its `lastBarClose` is missing for more than 5 minutes
  between 09:30 and 14:45, or if ATR3m or the opening range is missing at 09:30.
- An event is dropped if no option quote exists 0–90 s after it, or the traded strike has a quote gap
  over 120 s before the +30-minute exit (shorter horizons still reported when complete).

## Reported
Zone maps per index-day; every event (index, day, zone, type, time, direction, spot, strike, entry,
+5/+15/+30 returns, MFE/MAE, net per lot at +15 and +30, splits); per event type: count, wins, mean,
median, total per lot at each horizon vs the baseline; test, held-out and design day apart; the
sensitivities; dropped index-days and events.

## What counts
Descriptive. A type that beats the baseline after costs on the test days *and* on the held-out days, with
enough events to matter, becomes a candidate for a registered strategy (step 2) and forward shadow
testing — not a conclusion. Anything seen only on 30 Sep counts for nothing.

## Results (computed 2026-09-30 evening; `zs/` in the session scratchpad; scripts
`2026-09-30-zone-events.py`, `2026-09-30-zone-events-splits.py`)

### A data-quality correction found after the first results (not in the registration)
The registered drop rule counted *missing* snapshots only. On four "partial capture" days
the snapshots exist but carry a **stale** price (last index tick more than 60 s old): 11 Sep
100 minutes (up to 6,023 s), 21 Sep 113 minutes (up to 6,751 s), 17 Sep 29 minutes (up to 545 s),
16 Sep 19 minutes (up to 402 s), both indices; every other day has at most 1 such minute. A price
that jumps when the data resumes makes false breaks and holds. The corrected run treats a snapshot
with `secondsSinceSpot` > 60 as missing, which drops those four days under the registered 5-minute
rule. **Both runs are reported; the corrected run is the one to read.** The first run is shown
because the correction was made after its results were seen.

### Index-days used (corrected run)
Test: 15, 18, 28, 29 Sep (8 index-days); held-out 22–25 Sep (8); design 30 Sep (2). Dropped: 11, 16,
17, 21 Sep (stale). One ACCEPT event had no option quote (not priced).

### Net per lot after costs, corrected run (merge 0.5 × ATR3m, 10-minute window)
| Type | Test +15 n / mean / win | Test +30 | Held-out +15 | Held-out +30 |
| --- | --- | --- | --- | --- |
| Baseline (every 5th minute, both sides) | 994 / −₹90 / 37 % | 974 / −₹117 / 38 % | 1,008 / −₹56 / 40 % | 996 / −₹43 / 42 % |
| BREAK | 34 / +₹220 / 56 % | 33 / +₹200 / 55 % | 65 / −₹25 / 32 % | 64 / −₹72 / 42 % |
| FAIL (fade the failed break) | 16 / −₹259 / 31 % | 15 / −₹111 / 33 % | 44 / −₹20 / 45 % | 44 / +₹140 / 48 % |
| ACCEPT | 16 / +₹33 / 69 % | 16 / +₹57 / 62 % | 20 / −₹132 / 45 % | 20 / −₹318 / 30 % |
| SECOND_BREAK | 7 / −₹22 / 57 % | 7 / +₹789 / 57 % | 26 / −₹214 / 27 % | 25 / −₹344 / 32 % |
| HOLD | 10 / −₹170 / 20 % | 10 / −₹317 / 30 % | 15 / +₹189 / 47 % | 15 / +₹457 / 67 % |

Design day 30 Sep (not counted): BREAK 29 events +₹251 / +₹107 mean at +15 / +30; FAIL 19, −₹141 / +₹24;
ACCEPT 8, +₹294 / +₹434; SECOND_BREAK 14, +₹277 / +₹219; HOLD 8, +₹366 / +₹949.

**No event type beats the baseline on both the test and the held-out days** at +15 or +30. The same
holds in every sensitivity of the corrected run (merge 0.25 × / 1.0 × ATR3m; window 5 / 20
minutes): BREAK, ACCEPT and SECOND_BREAK are positive on the test days and negative held-out; FAIL
negative on the test days in every configuration; HOLD negative on the test days (2–3 wins in 9–12)
in every configuration.

### The first run (as registered, stale days kept) — for the record
With 11, 16, 17 and 21 Sep included, HOLD looked positive on both sets at +15 in all five
configurations (test n = 26, +₹261; held-out +₹189). Its test gains came from the stale days
(16 Sep +₹2,725 and +₹1,475, 11 Sep +₹2,169, 17 Sep +₹1,475); on the clean test days it loses.
FAIL lost on the test days in that run too (−₹172 / −₹284 at +15 / +30).

### Splits (corrected run; descriptive, about 100 comparisons, so some positives are chance)
Positive on both test and held-out with ≥ 8 events each: BREAK on expiry days (test 10 / +₹558 at +15,
held-out 13 / +₹544; +30: +₹156 / +₹468), BREAK when ATM IV fell over the 3 minutes before
(+₹404 / +₹465), BREAK on single-level zones, NIFTY BREAKs, FAIL with ATM IV rising (+30 only). With
this many comparisons on 8 + 8 index-days these are hypotheses for a new registration on unseen
sessions, not findings. The expiry-day BREAK split agrees with what the live expiry strategies already do.

### Reading
Merging nearby levels into zones and reading breaks, failures and holds on 1-minute closes did **not**
produce an event that an ATM option buyer could trade profitably across these sessions. The fade of a
failed break — the best-looking chart trade on 30 Sep — lost on the clean test days, and the
support/resistance hold lost there too. Every type beats "just buy ATM" somewhere, but none does it
on both sets. The clean sample is small (16 index-days outside the design day, 2–10 Sep not used), so
this does not prove zones are useless; it shows the 30 Sep chart trades were not the typical outcome of
these events. Not a strategy; nothing deployed. Next evidence would come from forward sessions
(1 Oct onwards) and the 2–10 Sep archive, with a registration written before looking.
