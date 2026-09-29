# Futures-volume surge inside a compressed range, before the break (registered 2026-09-28, before computing)

Question (user, 2026-09-28, NIFTY 14:10–14:20 chart: "look at the future volume surge"): on 28 Sep
futures volume reached 4.0× its same-minute normal at 14:16 while the index was still inside its
range, one minute before the break close (14:17) and two before the 13.6× spike. Buying the put on
that surge would have paid ~60.50 instead of 77.35. Does a surge inside a compressed range, near an
edge, lead a break that an ATM option can capture, across the sessions we have?
**28 Sep is the design day**, and both thresholds below were drafted with it in view (its 14:16
surge was 4.01×, just over 4×; its close sat 12 % of the range above the low): reported, not counted.

## Data
As docs/studies/2026-09-28-range-break.md (same files: spot 1-minute bars, futures 1-minute volume
with 0 = missing, option snapshots about once a minute; sessions 2–25 Sep + 28 Sep, NIFTY, SENSEX).

## Rule (fixed)
- Box and compression exactly as the range-break study: the 90 one-minute bars before bar t,
  compressed when high − low ≤ 5 × ATR3m (Wilder 14 on the day's 3-minute bars).
- Signal: a bar starting 10:45–14:44 with a compressed box whose
  - futures volume ≥ 4 × the median of the same minute over the previous 10 sessions (≥ 5 needed), and
  - close still inside the box and near one edge: position = (close − low) / (high − low) ≤ 0.25 →
    put; ≥ 0.75 → call.
  One trade per index-day: the first signal.
- Entry: the first option snapshot 0–90 s after the signal bar closes; ATM strike of that snapshot;
  buy at the ask (bid > 0, ask ≥ bid).
- Exits (first to happen; sell at the snapshot bid):
  1. Failure: a 1-minute bar after the signal closes back past the box's midpoint (put: close ≥ mid).
  2. Stop: bid ≤ 0.80 × entry ask. 3. Target: bid ≥ 1.20 × entry ask.
  4. Time: first snapshot at or after entry + 30 minutes, or 15:10.
- Costs v1, one lot (NIFTY 65, SENSEX 20).
- Also reported, descriptively: whether the index closed beyond the near edge within 3 minutes.

## Data-quality rules
As the range-break study: an index-day is used only while every 1-minute bar from 09:15 to the
signal is present (a missing bar before any signal drops the index-day); missing bars before the
exit or a quote gap over 90 s drop the trade.

## Reported
Primary: 4×. Sensitivity: 3× (same rule otherwise). Per trade and totals; dropped index-days; the
design day separately. Descriptive: ~19 usable index-days, all seen before.

## Results (computed 2026-09-28; `rb/vl.py` in the session scratchpad)

- 34 test index-days: 19 dropped (a missing 1-minute bar before any signal, or NIFTY 15 Sep before
  the exit), 6 with no signal, **9 trades** (4×).

| Index | Day | Side | Signal | Vol × | Break ≤ 3 min | Entry → exit | Reason | Net/lot |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NIFTY | 10 Sep | PE | 13:55, 6 % above the low | 4.40 | no | 96.00 → 102.05 | time | +₹334 |
| NIFTY | 11 Sep | CE | 12:55 | 5.76 | no | 110.30 → 102.95 | failure (mid) | −₹538 |
| NIFTY | 18 Sep | PE | 11:33 | 4.38 | no | 100.70 → 93.90 | failure | −₹501 |
| NIFTY | 22 Sep | PE | 13:49 | 5.77 | no | 58.50 → 72.75 | target | +₹871 |
| NIFTY | 23 Sep | PE | 13:30 | 6.37 | 13:33 | 112.95 → 112.50 | time | −₹90 |
| NIFTY | 24 Sep | CE | 11:18 | 4.55 | no | 113.30 → 103.05 | failure | −₹726 |
| NIFTY | 25 Sep | PE | 12:37 | 5.73 | no | 80.50 → 98.35 | target | +₹1,102 |
| SENSEX | 10 Sep | CE | 11:19 | 5.00 | no | 162.00 → 143.00 | failure | −₹433 |
| SENSEX | 24 Sep | CE | 11:18 | 18.00 | 11:19 | 173.15 → 133.50 | stop | −₹845 |

- **4× (primary): 9 trades, 3 wins, mean −₹92, median −₹433, total −₹826 per lot.** A break beyond
  the near edge followed within 3 minutes in only 2 of 9 (and both of those lost); the two target
  hits came without an early break. **3× (sensitivity): 11 trades, 2 wins, total −₹2,993.**
- Design day: the rule's first signal was NIFTY 11:08 (4.49×, 5 % above the low), not 14:16:
  −7.3 % (−₹433); SENSEX 11:12, −10.8 % (−₹887). The 14:16 surge was never reached (one trade a day).

## Reading
A surge inside a compressed range near an edge did not predict a break here: breaks followed in 2
of 9, and the rule lost. The 28 Sep 14:16 case is real but was not typical even on its own day (the
morning signal failed first). Not a strategy.
