# ETR peak trail (etr-v6) and expiry-day capital split (ebs-v3): registered before any result (ledger A-048, A-049)

User, 7 Oct 2026 01:00 IST, after the ₹10 crore goal review: "yes build 1 and 2 and test them after the close".
Both are changes to the engine that earned 69 % of the replay record (expiry-trend-rider, A-029), so both are judged on
the same days with the same discipline as every other rule: values fixed here, one variant each, no tuning after the
results, held-out days read once.

## What the record says (the reason for each change)
- Capital: in the 13 replayed days the trend rider's entries or adds were refused for capital 9 times. Six were its own
  earlier stages filling the account; three, all on 22 Sep (a held-out day), were the expiry-breakout straddle holding
  the whole ₹5L until 14:00 while the rider's trigger fired at 13:54, 13:55 and 14:01. Opening drive already has a
  ₹1,00,000 expiry-day budget, so the "opening drive blocks ETR" premise of the review was wrong; the contention is
  straddle vs rider, both expiry strategies. **ebs-v3** = ebs-v2 with `position.premium_budget: 250000`.
- Runner management: on 1 Oct the held put peaked near ₹804 around 14:00 and the swing exit fired at 14:30 at ₹610,
  a quarter below the peak; 15 Sep ran into the 15:15 flat-by; 24 Sep exited on the swing at ₹285. The A-042 time stop
  made things worse (it freed the slot for a bad re-entry), so this is a trail on the option itself, not a clock.
  **etr-v6** = etr-v1 plus: once the held option's bid is 50 % above the average premium, exit when the bid falls 15 %
  below its running peak (`TRAIL_15`); swing exit, flat-by and the 40 % resting stop unchanged. 50 and 15 are judgment
  (the swing exit tolerated a 25 % giveback on the design day); they are not fitted and will not be changed after the
  result. 1 Oct is the design day and is not evidence for v6.

## Test (after the 7 Oct close)
Image from branch `exp/a048-a049` (code = main 9718ed5 plus the trail), features v11, risk v9 (= v4 at ₹5L in replay),
costs v2. Four variants of the live set on each day, from the same image:
- C control: ebs-v2, ecr-v11, odb-v3, egb-v4, etr-v1 (the live set);
- S split: C with ebs-v3;
- T trail: C with etr-v6;
- ST: both.
Days: GCP own capture 28, 29, 30 Sep, 1, 5, 6 Oct and zt-tiger-v2 23, 25 Sep (read after hours) = 8 test days, of
which 23, 25, 29 Sep, 1, 6 Oct are expiry days. The archive days 3, 8, 10, 15, 17 Sep need the Expansion drive on the
Mac and are added when it is connected. Held-out 22 and 24 Sep: read once, after the test days, both expiry days.
1 Oct counts for S, not for T or ST (design day of the trail).

## Reading, fixed now
- A-048 (S) PASS if, over the test days, S net ≥ C net and on the days the straddle traded S loses no more than half of
  C's straddle profit on those days; held-out: S net ≥ C net − ₹50,000.
- A-049 (T) PASS if, over the test days excluding 1 Oct, T net ≥ C net and T keeps ≥ 90 % of C's profit on each day
  where C made ≥ ₹2,00,000 (a trail must not cut the runners it is meant to protect); held-out: T net ≥ C net.
- ST is reported, not judged: it goes live only if both pass. A PASS means a decision about live PAPER, not an
  automatic switch; the compounding sizing now live (risk v9) multiplies any unverified rule's losses.

## Test-day result (GCP, 7 Oct 16:15–17:53 IST, sessions with code_version 'a048-a049')

| day | C live set | S ebs-v3 | T etr-v6 | ST both |
|---|---|---|---|---|
| 28 Sep | +3,65,097 | +3,65,097 | +3,65,097 | +3,65,097 |
| 29 Sep | 0 | 0 | 0 | 0 |
| 30 Sep | −1,11,378 | −1,11,378 | −1,11,378 | −1,11,378 |
| 1 Oct (design day for T) | +8,50,438 | +8,50,438 | +9,32,455 | +9,32,455 |
| 5 Oct | 0 | 0 | 0 | 0 |
| 6 Oct | −46,151 | −46,151 | −46,151 | −46,151 |
| 23, 25 Sep (zt) | failed | failed | failed | failed |

- The straddle (ebs) did not trade on any test day, so S equals C everywhere: A-048's rule holds only vacuously.
- The trail armed only on 1 Oct, the day it was designed from (excluded): v6 exited the 72200 PE at 13:01 at ₹586.29
  (+₹8,00,964, v1 held to 14:30 for +₹8,50,438) and re-entered the 71500 PE 13:43–14:06 for +₹1,31,491. On every
  other test day T equals C: A-049's rule also holds only vacuously.
- 23 and 25 Sep (zt, non-expiry days on which neither strategy trades) failed: "temporary file size exceeds
  temp_file_limit" on zt-tiger-v2. Fixed on our side (paged tick reads, phase events read up front; d6cc716).
**Reading so far: no evidence either way from the test days.** The held-out days 22 Sep (NIFTY expiry, the straddle
and the rider both traded) and 24 Sep (SENSEX expiry, the rider traded twice) are the only informative days; they
run once tonight on image d6cc716 (night2 batch), and the archive expiry days 3, 10, 15, 17 Sep need the Expansion drive.

## Held-out result (22, 24 Sep, zt, read once; GCP night2 batch, image d6cc716, 7 Oct 20:40–21:00 IST)

| day | C | S ebs-v3 | T etr-v6 | ST |
|---|---|---|---|---|
| 22 Sep NIFTY | +1,78,342 | +1,21,032 | +1,78,342 | +1,21,032 |
| 24 Sep SENSEX | +2,37,954 | +2,37,954 | +3,03,164 | +3,03,164 |
| total | +4,16,296 | +3,58,986 | +4,81,506 | +4,24,196 |

- 22 Sep: at half budget the straddle made +₹76,093 instead of +₹1,52,300 (both legs 2,990 instead of 5,980); the freed
  capital let expiry-gamma-breakout trade (+₹18,896). The trend rider was unchanged (+₹7,976).
- 24 Sep: the trail closed the 73900 PE at 14:02 at ₹315.55 (TRAIL_15) where v1's swing exit came at 14:36 at ₹285.31.
**A-048: FAIL** (held-out −₹57,310 vs C, beyond −₹50,000). **A-049: PASS** on the registered rule; thin evidence
(the trail acted on one independent day plus its design day). Going live is the user's decision.

Re-check of 24 Sep (7 Oct 22:00 IST, image 0ce03d3): the first 24 Sep replays stopped at 15:08 of the replayed day
(zt-tiger-v2's sequence numbers jump by about 2.2e10 there; the paged reader walked the gap and the idle connection of
the other index was closed by zt's idle-in-transaction timeout). With short per-page connections and gap skipping,
all six replays ran the full day (3,740,111 events, last snapshot 15:40) and reproduced the same nets: C and S
+₹2,37,954, T and ST +₹3,03,164, A-055 v10 +₹3,24,311, v11 +₹3,20,151. The verdicts stand.
