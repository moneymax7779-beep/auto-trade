# Time-value budget for the expiry trend rider (etr-v8, ledger A-073)

Registered 2026-10-11 before any replay. User: "register and test the time-value budget", after the review of
6 Oct NIFTY (session 797): the trend rider bought NIFTY 22700 CE at 11:46 (46.93 → 33.15, −₹72,269, the index fell
about 30 points through the last swing low) and again at 12:45 (57.64 → 34.08, −₹50,828, premium stop) while NIFTY
moved sideways between about 22,680 and 22,730. On the second trade roughly 13.5 of the 23.6 lost per unit was time
value bleeding on expiry afternoon, not the index going the wrong way. 6 Oct is the design day.

## Rule

A new entry (adds unchanged) needs the ATM option's time value per leg, in index points, to be at most 0.5 × the
index's high-low range over the last 60 minutes. Time value per leg = (ATM straddle − |spot − ATM strike|) / 2, from the
feature snapshot. Why 0.5: an at-the-money option moves about half as much as the index (delta ≈ 0.5), so the index
has to travel about twice the time value just to earn it back; the rule asks that the market has recently been moving
that much. Missing data (no straddle, under 30 minutes of history) never blocks. Everything else as etr-v7.

## Test

The 11 expiry days among the 22 tick days on the Mac: NIFTY 8, 15, 22, 29 Sep, 6 Oct; SENSEX 3, 10, 17, 24 Sep,
1, 8 Oct. Held-out 22 and 24 Sep (read once). Design day 6 Oct reported apart. Test = the other 8. Two runs on one new
image: control (live set, etr-v7) and v8 (live set with etr-v8 in etr-v7's place). The control must reproduce the
earlier sessions on these days.

## Pass criteria (fixed now)

PASS if, on the 8 test days: trend-rider net with v8 ≥ with v7; the set's net ≥ control − ₹50,000; at least 3 entries
blocked (else INCONCLUSIVE). Held-out: trend-rider net with v8 ≥ v7 − ₹50,000.

Stated before the result: the rule may block the 1 Oct SENSEX entry (+₹8.46L, time value about 194 points at 12:20)
if the hour before it moved less than about 390 points. If it does, the rule fails, however well it does elsewhere.

## Result (11 Oct 2026, 12:03 IST; image c61f805-a73; `.local/a73/summary.txt`)

The control reproduced the earlier sessions on all 11 days to the rupee.

| Part | Set, control | Set, v8 | Trend rider, control | Trend rider, v8 |
|---|---|---|---|---|
| Test (8 days) | +19,97,630 | +15,08,844 | +14,26,118 (8 trades) | +8,38,021 (4 trades) |
| Held-out (22, 24 Sep) | +3,92,497 | +25,992 | +2,37,415 (3) | −1,29,090 (1) |
| Design (6 Oct) | −1,23,097 | 0 | −1,23,097 (2) | 0 |

**FAIL.** Trend rider on test days −5,88,097 against v7 (bar: ≥ 0); set −4,88,786 (bar: ≥ −50,000); held-out trend
rider −3,66,505 (bar: ≥ −50,000). Blocked entries on 8 of 11 days (criterion (c) met).

What it did: it blocked the losers it was meant to block (3 Sep −1,95,283; 8 Sep −17,520; 15 Sep 11:20 −54,323; the
two 6 Oct trades) and the runners with them: 15 Sep 12:59 +5,28,566 (no trade at all with v8), 24 Sep 13:27 +2,50,462
(v8 entered later, 13:55, at a worse strike, −1,29,090), 1 Oct delayed 12:20 → 12:30 (+8,46,200 → +6,30,766), 8 Oct
delayed 11:23 → 11:26 (+3,94,331 → +2,83,108).

Why: the rule compares the option's time value with how far the index moved in the last hour. A trend usually starts
out of a quiet hour, so at the start of the best moves the last hour's range is small and the option looks "expensive".
The rule therefore says no exactly when a breakout begins, and yes only after the move has run, when entering is
late. On these days a quiet last hour was a sign of an imminent move, not of a stalling market. The 6 Oct losses were
real, but "time value against recent range" cannot tell 6 Oct from 15 Sep or 1 Oct. Not deployed.
