# Five setup families, A B D E F (ledger A-036 to A-040): registered before any code or result

User's request, 6 Oct 2026, after reading the 6 Oct NIFTY and SENSEX charts: "work on these (A, B, D, E, F) ... ignore
expiry day". Read as: **none of these strategies trades an index on the day that index expires** (`scope.skip_expiring_index:
true`); the other index trades that day. 6 Oct is the design day of all five (reported apart, not counted).

Lessons carried over from A-035 (stated, because they come from the same days): exits are on index levels (structure stop,
target level), with only a wide 25 % resting premium stop as the emergency stop; size Rs 2,50,000 of ATM premium per trade
(half the earlier strategies). All values are round numbers fixed now. Levels used are those the features compute: day open,
ORH / ORL (after 09:30), PDH / PDL, prior ORH / ORL, VWAP (spot proxy), ATR(3m). Minute closes are the snapshot spot each
minute. Calls on the upside, puts mirrored, unless a family says otherwise.

## A-036 A. Opening-low reclaim (`opening-reclaim.v1`)
The day's low so far was made 09:15-09:30 within 0.5 x ATR of a prior-day level (prior ORH / ORL, PDH, PDL, previous close),
has held for at least 3 minutes, and a minute close then crosses above the day open (first time since that low), before
10:15 -> buy the call. Stop: index below the morning low. Target: the nearest of ORH / PDH / prior ORH at least 0.5 ATR above
the entry, else entry + 1.5 ATR. Time stop 30 minutes. Mirror: a morning high at a prior-day level, then a close below the
day open -> put.

## A-037 B. Break and retest (`break-retest.v1`)
A minute close beyond a level (ORH / ORL after 09:30, PDH, PDL) after the previous close was on the other side = a break.
Within 20 minutes the index comes back to within 0.3 ATR of the level without a minute close more than 0.3 ATR through it
(the retest held); entry on the first minute close above both previous closes, no more than 1 ATR above the level ->
call. Stop: index below the lowest close since the break. Target: the next level 0.5 ATR or more above, else +1.5 ATR.
Time stop 30 minutes. One trade per break. Entries 09:31-14:30. Mirror for breaks down.

## A-038 D. Failed breakout (`failed-breakout.v1`)
A minute close above a level (ORH after 09:30, PDH, prior ORH) that is also a new day high; within 10 minutes a minute close
back below that level (the break failed); then a minute close below both previous closes while the index has not exceeded the
failed high (a lower high) -> buy the put. Stop: index above the failed high. Target: the VWAP if at least 0.5 ATR below the
entry, else entry - 1.5 ATR. Time stop 30 minutes. One trade per failed break. Entries 09:45-14:30. Mirror at ORL / PDL /
prior ORL -> call.

## A-039 E. Range-edge fade (`range-fade.v1`)
A range is confirmed when, over the last 60 minute closes, the index crossed the VWAP at least 3 times, the closes span at
most 3 ATR, and no new day high or low was made in the last 30 minutes. Range low RL / high RH = the lowest / highest close
of those 60 minutes. Long: a minute close below RL (by at most 0.5 ATR), then within 5 minutes a close back above RL -> call;
stop: index below the sweep's lowest close; target RH - 0.25 ATR. Short mirrored at RH. Time stop 30 minutes. Entries
11:00-14:30.

## A-040 F. Closing-auction pressure (`auction-pressure.v1`)
At the first snapshot from 15:21 (the closing auction's market-and-limit phase) with constituent coverage >= 80 %: if the
weighted constituent auction return (`cas.weightedIepReturnPct`) is >= +0.15 % -> buy the call; <= -0.15 % -> the put.
Exit at 15:29 (options trade until 15:30). One trade per index per day. Seen on 5 and 6 Oct only (both positive and
right): those are design observations.

## Test (all five in one replay per day, research risk v7: no capital contention between them, no daily limit; results
attributed per family; real risk limits would refuse some overlapping trades)
- Test: 3, 8, 10, 15, 17 Sep (zt archive, Mac); 28, 29, 30 Sep, 1 Oct, 5 Oct (own capture, GCP).
- Held-out: 22, 24 Sep, read once for each family. Design day: 6 Oct, apart.

**PASS (each family)**: on the test days at least 8 trades (F: 6), net > 0, profit factor >= 1.3, no day below
-Rs 1,10,000; and held-out net >= 0. Fewer trades = INCONCLUSIVE. A PASS means a forward test on new days, not live.

## Result (Mac replays 486-492, GCP replays 505-510; all five together, research risk v7)
| Family | Verdict | Test: trades / wins / net / PF / worst day | Held-out | Design 6 Oct |
|---|---|---|---|---|
| B break and retest | FAIL (held-out only) | 31 / 12 / +1,34,034 / 1.86 / -22,833 | 3 trades -8,064 | +9,828 |
| D failed breakout | FAIL | 10 / 5 / -6,584 / 0.97 / -74,136 | -19,949 | -22,954 |
| A opening-low reclaim | INCONCLUSIVE | 5 / 1 / -52,596 / 0.44 | -1,040 | +35,945 |
| E range-edge fade | INCONCLUSIVE | 5 / 1 / -42,148 / 0.47 | none | none |
| F closing auction | INCONCLUSIVE | 5 / 1 / -23,031 / 0.21 | (no archive auction data) | none |

B met every test criterion (positive on both the archive days, +35,994, and the own-capture days, +98,040) and failed
only the held-out check, on 3 trades. Its losses are small index-level stops; its wins +7 % to +23 % of premium. It is
the one lead: the next step is a registered forward test in shadow on new days, not a live switch.

## Forward test of B (ledger A-041), registered 6 Oct before any forward day
B runs unchanged in shadow from 7 Oct (replayed after each session from own capture, account SHADOW-1, no orders). Judged
once after at least 15 sessions and 20 trades: PASS if net > 0, profit factor >= 1.3, no day below -Rs 1,10,000, and
both halves of the period net >= 0. No change in between; a changed rule is a new ledger row.
