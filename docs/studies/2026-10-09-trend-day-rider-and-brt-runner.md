# Trend-day rider (A-064) and break-retest runner split (A-065), registered before any code or result

User's request, 9 Oct 2026 16:10 IST, after the 9 Oct postmortem ("yes register 2 and 3 and test them"). 9 Oct was a
trend day on both indices (low 09:24–09:25, high 14:18; NIFTY 22350 CE +128 % low to high) and the system took one
30-minute slice of it: break-retest SENSEX 72100 CE 09:39 → 10:09 time stop, +₹64,644, while the option went on to
₹803 (11:30) and ₹937 (14:18) through a pullback to ₹672 (12:00). 9 Oct is the design day of both ideas and is never
counted as evidence; it is reported apart.

Both are new strategy files. No live strategy file changes (user's rule: new ideas as new strategies).

## A-064 trend-day rider (`trend-day-rider.v1.yaml`, tdr-v1)
Different from the trend pullback scalper that failed (A-035: 6–10 % resting premium stops, +8 % targets, 15-minute
time stop, trend proof of a few minutes): tdr-v1 waits for an hour of proof and rides with an index-level exit.

Calls (puts mirrored), decided on minute closes of the index:
- **Window**: new entries 10:30–14:30; flat by 15:15; never the index on its expiry day.
- **Trend proof**, all at the entry minute:
  1. the last 60 minute closes all above the session VWAP (futures VWAP less basis, features v11);
  2. the close at least 0.3 % above the day's open;
  3. a new session-high close within the last 30 minutes;
  4. day breadth (index-weighted constituents vs previous close, −100..+100) at least +20.
- **Trigger**: within the last 10 minutes a close came within 0.5 ATR(3m) of EMA20 (or below it, still above VWAP by
  rule 1), and this close is above the previous two closes. Each pullback is traded at most once; entries are not
  otherwise limited.
- **Exits**: structure stop = the lowest close of those 10 minutes; trail = a 3-minute close (09:15-based bars) below
  the session VWAP; flat by 15:15; resting premium stop 25 % (emergency). No target, no time stop.
- **Position**: ATM, nearest expiry, budget ₹2,50,000 (scaled by equity under risk v12, like break-retest), 1 lot intent.

## A-065 break-retest runner split (`break-retest-runner.v1.yaml` brtr-v1 + `break-retest.v2.yaml` brt-v2)
The split is two strategies on the same signal, each half of break-retest's budget:
- **brt-v2** = brt-v1 with `premium_budget` ₹1,25,000 (exits unchanged: structure stop, target level, 30-minute time
  stop, flat by 15:15);
- **brtr-v1** = brt-v1's entry exactly (same code path and values), ₹1,25,000, exits: the same structure stop, the
  3-minute-close-below-VWAP trail of A-064 (above for puts), flat by 15:15, premium stop 25 %. No target, no time stop.
Compared with brt-v1 at ₹2,50,000 alone. brtr-v1 at ₹2,50,000 alone is reported too (descriptive).

## Test (both), fixed now
GCP, image of the commit that adds the files; features v11, costs v2, risk v12, `replay-equity` 10,61,863 (equity at the
end of 9 Oct), one replay at a time.
- **Test days**: own capture 28, 29, 30 Sep, 1, 5, 6, 7, 8 Oct (8 days). All were seen before (other hypotheses).
- **Held-out**: 22, 23, 24, 25 Sep (zt-tiger-v2, `research.session_split`), read once for these hypotheses.
- **Design day**: 9 Oct, reported apart.
- Runs: each candidate standalone; then the live set (ebs-v2, ecr-v11, odb-v3, egb-v4, etr-v7, brt-v1) with tdr-v1
  added, and with brt-v1 replaced by brt-v2 + brtr-v1, to measure capital interaction.

**A-064 PASS** if on the test days: at least 5 trades (fewer = INCONCLUSIVE), net > 0, profit factor ≥ 1.5, no day
below −₹1,06,000 (10 % of equity); held-out net ≥ 0; and in the live-set run the other strategies' test-day net falls
by no more than ₹50,000 against the live set alone.

**A-065 PASS** if the split's net (brt-v2 + brtr-v1) ≥ brt-v1's net on the test days and on the held-out days, at least
3 test trades reach a target or time stop (where the two differ; fewer = INCONCLUSIVE), and no test day of the split is
below −₹1,06,000.

A pass makes a candidate eligible for live PAPER; going live is the user's decision.
