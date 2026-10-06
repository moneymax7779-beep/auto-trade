# Signal audit and two candidate strategies from a combination search (ledger A-050, A-051), registered before the held-out read

User, 7 Oct 2026 00:20–00:45 IST: "don't miss a single valid best signal on both NIFTY and SENSEX … be brutally honest
and realistic", then "try all the best combinations, and derive the new strategies or adjust the existing strategies",
then "do a thorough analysis do replays and fine tune … redeploy … understand why not many trades, why loss trades …
trend day range bound day … bring the best 10cr system".

## 1. What the market offered and what the live set took (Upstox bars, 19 days, 8 Sep–6 Oct)
Swing legs of at least 0.35 % on the index (zigzag on one-minute highs/lows): 89 legs, 44 NIFTY, 45 SENSEX. For each,
the ATM option of the nearest expiry from the minute the move had gone 0.15 % to its peak during the leg (hindsight
exit): median +57 %; expiry-day legs median +120 %, other days +43 %. On the 12 replayed days, 53 legs offered ≥ 30 %;
the live set (replays 426–432, 534, 538, 554, 556, 550, 588) had a trade in 10 of them: 8 of 21 on expiry days, 2 of
32 on other days. "Not many trades" is by design: every non-expiry rule tested so far lost (A-034, A-035, A-036..A-040).

## 2. Trading every confirmed swing (the "never miss a signal" system)
Always in the direction of the last swing reversal of X % on one-minute closes, ATM option of the nearest expiry, entry
and exit at the next bar's open, 1.5 % round-trip cost on premium, flat at 15:15:

| X | day | trades | win | PF | mean/trade |
|---|---|---|---|---|---|
| 0.15 % | expiry | 55 | 31 % | 1.84 | +13.9 % |
| 0.15 % | other | 221 | 29 % | 0.80 | −1.6 % |
| 0.25 % | expiry | 28 | 39 % | 1.96 | +20.3 % |
| 0.25 % | other | 90 | 38 % | 1.15 | +1.6 % |

Compounded at half the equity per trade, every variant had a drawdown of 66–97 %. Taking every signal is not an edge;
on non-expiry days it loses.

## 3. Combination search
19 direction-aware filters (EMA 9/20 stack and side, VWAP proxy side, beyond/inside the opening range, beyond/inside
the previous day's range, day direction ≥ 0.3 % from the open, gap agreeing, futures RVOL > 1.5, futures OI rising,
option OI flow, VIX agreeing, breadth > 60 / < 40 % of constituents above their open, near the day's extreme, morning
/ afternoon, expiry / not) in all combinations of 1–3, on three swing sizes: 3,261 combinations. Split: train 8, 9, 10,
11, 15, 16, 17, 18, 21 Sep; test 28, 29, 30 Sep, 1, 5, 6 Oct; held-out 22–25 Sep (research.session_split), not read.
496 combinations had ≥ 10 train trades and PF ≥ 1.5; on test their median PF was 1.22 and only 38 % kept PF ≥ 1.5.
The best train combination (PF 14.1) had test PF 0.87. Most of the search is noise; two families held on both halves:

- **A-050, expiry swing rider (esr-v1):** on the index that expires today, no filters, always in the direction of the
  last 0.25 % swing reversal (ATM option), exit on the opposite 0.25 % reversal or 15:15. Train n 12 PF 1.74 mean
  +16 %; test n 12 PF 2.32 mean +25 %. Filters made expiry days worse at every size.
- **A-051, trend-day continuation (tdc-v1):** on an index that does not expire today, enter only on a 0.15 % swing
  reversal in the day's direction when the index is ≥ 0.3 % beyond the day's open in that direction and breadth agrees
  (> 60 % of constituents above their open for calls, < 40 % for puts); exit on the opposite 0.15 % reversal or 15:15.
  Train n 11 PF 7.35 mean +17 %; test n 8 PF 3.49 mean +9 %. (At 0.25 %: n 9 / 6, PF 2.47 / 3.75; at 0.35 % it fails.)

## Reading, fixed now (before the held-out read and before any engine replay)
- Held-out 22–25 Sep on bars, read once: each candidate keeps its rule only if held-out PF ≥ 1.2 and mean > 0.
- Then built as engine strategies and replayed from ticks (own capture 28 Sep–6 Oct; zt-tiger-v2 21 Sep–6 Oct,
  after hours) standalone and added to the live set. Go-live only if, on the tick replays, the set with the candidate
  nets more than the set without it and its worst day is no worse than the control's worst day − ₹50,000.
- Sample sizes are small (≤ 12 trades per half); a pass is a decision to run it, not proof of an edge. Forward days
  decide, and the daily review reports each candidate separately.

## Held-out read (22–25 Sep, bars, once, 7 Oct 01:00 IST)
- A-050 esr-v1: 4 trades, 2 wins, PF 1.73, mean +20.5 % (22 Sep NIFTY PE 10:39 +34 %, CE 14:15 −64 %; 24 Sep SENSEX
  PE 09:56 +160 %, CE 14:39 −48 %). **Passes**; goes to tick replays (engine strategy `expiry-swing-rider`, a new
  strategy beside the live set: the user asked, 01:05 IST, that existing strategies not be changed and that new
  ideas run as new strategies).
- A-051 tdc-v1: 7 trades, 1 win, PF 0.15, mean −10.4 %. **Fails**; not built. Every non-expiry directional rule tested
  so far has now failed out of sample (A-034, A-035, A-036..A-040, A-051).

## Tick replays (GCP own capture; expiry days not held-out: 29 Sep NIFTY, 1 Oct SENSEX, 6 Oct NIFTY)
Variants: C live set; E1 live set + esr-v1 (whole budget); E2 live set + esr-v2 (= v1 at half the budget, sizing only,
so the existing expiry strategies keep capital); A esr-v1 alone. 22 and 24 Sep are not replayed (held-out, already
read for this hypothesis). Results below when the batch ends.

### Result (GCP, 7 Oct 01:30–02:10 IST; control sessions 609/613/617, candidate sessions 621–629, image esr-test2)
The first candidate batch (esr-test) never traded: snapshots come once a minute exactly on the minute and the
strategy waited for a snapshot one second into the minute. Fixed (decide on the first snapshot of each minute) and
rerun; the control runs did not change.

| day | C live set | E1 set + esr-v1 (whole budget) | E2 set + esr-v2 (half) | A esr-v1 alone |
|---|---|---|---|---|
| 29 Sep NIFTY | 0 | −2,19,484 | −2,30,443 | −2,19,484 |
| 1 Oct SENSEX | +8,50,438 | −1,78,045 | +11,53,818 | −1,78,045 |
| 6 Oct NIFTY | −46,151 | +2,24,716 | +66,202 | +2,24,716 |
| total | +8,04,287 | −1,72,813 | +9,89,577 | −1,72,813 |

- 1 Oct, E1 and A: esr's first trade (SENSEX 72400 CE 10:00–11:24, 2,360 qty, −₹1,78,045) exceeded the ₹1,10,000 daily
  loss limit on its own and the account kill switch blocked everything after it, including the trend rider's
  +₹8,50,438 trade. In E2 the trend rider traded but at 680 qty instead of 2,040 (capital held by esr).
- 29 Sep: three to four reversals, all losers; ticks lose more than the bar estimate (bars: +4, +22, −16, −54, −64, −33 %).
- The bar test had no daily loss limit and no capital sharing; on ticks both decide the outcome.

**Reading: FAIL.** E1 nets less than C. E2 nets more but its worst day (−₹2,30,443) is far below C's worst day −
₹50,000 (−₹96,151). esr is not deployed; the live set is unchanged. As the user said (01:05 IST), a new strategy
must not disturb the existing ones; in one account with one capital pool and one daily loss limit, an always-in
expiry strategy does disturb them, at any size that matters.
