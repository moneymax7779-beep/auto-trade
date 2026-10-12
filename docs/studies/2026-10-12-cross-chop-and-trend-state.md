# 12 Oct losses: the cross in midday chop, and the morning trend nobody holds (ledger A-079, A-080, A-081)

Registered 12 Oct 2026 ~16:00 IST, before any replay. User, 15:47: "why there is loss trade despite best and clear
moves today? ... do whatever is needed to achieve 10 cr goal".

## What happened on 12 Oct (live session 876, PAPER-1, neither index expiring)

NIFTY: 22,644 at the open, ORL 22,515 broken about 10:03, 22,390 by 11:15; a 22,363–22,400 range from 11:30 to 14:00
with the 3-minute ATR halving (29 → 14); a second leg down 14:00–14:45 to 22,309; 22,317 at 15:15. Price stayed
below the session VWAP from 09:45 to the close. SENSEX the same shape (72,832 → 71,686).

| Time | Strategy | Trade | Result |
|---|---|---|---|
| 12:39 | ma-cross | SENSEX 72000 CE, cross up inside the midday range, 220 of 720 filled at ₹475 (price ~240 points below VWAP) | swing stop 12:56, −₹8,815 |
| 12:39 | ma-cross | NIFTY 22400 CE, cross up | entry not filled |
| 12:54 | ma-cross | NIFTY 22400 PE, cross back down, 5,200 qty at ₹100.81 (1 day to expiry) | 60-minute time stop 13:54 at ₹92.18, −₹45,948; NIFTY fell ~55 points in the next 50 minutes |

Day −₹54,763. Equity ₹10,07,100 against ₹7,21,460 on the ₹10 crore curve (5L × 1.096⁴): ahead, but the rate now
needed is about 8.3 % a session for the remaining ~58 sessions.

No other strategy could act: ebs, ecr, egb, etr trade only an expiring index; odb's window is 09:16–09:25; EMA20 of
3-minute bars exists only from about 10:15, so neither the cross nor orc-v1 had a trend reading during the 09:30–10:15
fall (A-078: allowing orc an early trend lost 6 of 8 early entries on the tick days).

Known before today: the cross's out-of-sample record (A-070) lost on both indices on 18 and 23 Sep "through the
midday chop"; 11 of its 31 out-of-sample exits were time stops (mixed results).

## Three hypotheses, values fixed now (round numbers, from 12 Oct only; 12 Oct is not in the test data)

**A-079 mac-v2, cross with the VWAP.** mac-v1 plus: a cross up enters only with the index above the session VWAP, a
cross down only below it. A cross against the VWAP is a bounce inside the day's trend (12 Oct 12:39). Everything else
is mac-v1.

**A-080 mac-v3, cross without the time stop.** mac-v1 with no 60-minute time stop: exits at the 2R target, the swing
stop, 15:15 or the emergency premium stop. The time stop cut 12 Oct's put at the bottom of a range just before the leg.

**A-081 tsa-v1, trend state at 10:15.** A new strategy for the index that does not expire today. At the first 3-minute
close from 10:15 to 10:30 at which EMA20 exists and the index is below VWAP, EMA9 is below EMA20 and the index is below
the ORL, buy the ATM put (mirrored above VWAP / EMA9 above EMA20 / above ORH: the call). One trade per index per day.
Index stop at the session VWAP at entry; exit on a 3-minute close back through VWAP, the emergency premium stop (30 %)
or 15:15; no target, no time stop; ₹2,50,000 (scaled with equity like the rest). A-064 (trend-day rider, pullback
entries, FAIL) and A-076/A-078 (opening-range breaks, FAIL) are the priors: this is the only one that enters on the
trend's state once, without waiting for a pullback or a break.

## Test

Mac, one new image; the 22 tick days (zt archive), the live set's risk v12, features v11, costs v2. Held-out 22, 23,
24, 25 Sep, read once for each hypothesis; test = the other 18 days. Runs: control (live set), live set with mac-v2 in
place of mac-v1, live set with mac-v3 in place of mac-v1, live set + tsa-v1 (listed last). The control must reproduce
A-078's control to the rupee.

**A-079 / A-080 PASS** if on the test days the set with the variant nets more than the control and the variant's
profit factor is at least mac-v1's; and on the held-out days the variant set nets at least the control's.

**A-081 PASS** if on the test days tsa-v1 has ≥ 8 trades (else INCONCLUSIVE), net > 0, profit factor ≥ 1.3, no day
below −₹1,10,000, and the other strategies lose ≤ ₹50,000 to it; held-out tsa-v1 net ≥ 0.

A PASS is a decision for the user (the live set changes only on their word); a FAIL is recorded and not deployed.

## Result (Mac, image 11b73a8-a79, 12 Oct 15:59–16:57; `.local/a79/summary.txt`)

The control reproduced A-078's control on all 22 days to the rupee.

| Run | Test set net (18 days) | Cross trades / wins / net / PF (test) | Held-out set net | Verdict |
|---|---|---|---|---|
| control (mac-v1) | ₹23,80,558 | 39 / 25 / +₹9,10,301 / 4.15 | ₹4,76,320 | – |
| mac-v2, VWAP side (A-079) | ₹19,56,229 | 30 / 19 / +₹5,27,861 / 3.30 | ₹2,71,286 | FAIL |
| mac-v3, no time stop (A-080) | ₹17,79,554 | 31 / 18 / +₹3,89,069 / 1.72 | ₹2,24,116 | FAIL |
| + tsa-v1, trend state (A-081) | ₹17,94,736 | tsa 21 / 7 / +₹63,404 / 1.11 | ₹4,48,728 | FAIL |

- **The cross's midday losses pay for its winners.** Many of mac-v1's biggest targets were crosses against the VWAP
  (9 Sep, 17 Sep, 25 Sep, 5 Oct, 7 Oct +₹1.03L); filtering them cost more than the bounces it avoided.
- **The 60-minute time stop protects the cross.** Without it, positions drifted into the 25 % premium stop (−₹61K to
  −₹71K each) and blocked the next cross.
- **The 10:15 trend state is not a trend.** 11 of tsa-v1's 14 losers stopped within 30 minutes; its two big winners
  (15 Sep, 8 Oct) were days the trend rider and the cross were already catching, and it took ₹6.5L of their capital.

None is deployed. 12 Oct itself (the design day) was not replayed for these.
