# Wall-break rider (wbr-v1): option writers covering at the wall, registered before any test (ledger A-057)

User, 7 Oct 2026 22:30 IST: "we can have this now, if not implemented?" (the wall-break rider proposed in the
evening review). A new strategy beside the live set; no existing strategy changes.

## Idea (market microstructure)
On expiry day the strike with the most call open interest above the index (the call wall) and the most put open
interest below it (the put floor) are where option writers defend. When the index moves toward the wall and the
wall's open interest falls minute after minute, writers are buying back (covering), and their buying feeds the move:
a short-covering cascade, the mechanism behind the big expiry afternoons of 24 Sep and 1 Oct (both SENSEX, both
design days: excluded from the test). The features already exist (features v11): `callWallWeakening` = the call
barrier strike's OI lower at each of 10, 5, 3 and 1 minutes ago and now, with the index up over 3 minutes;
`putFloorWeakening` the mirror.

## Rules (fixed now, not tuned after results)
- Scope: the index that expires today; entries 10:30–14:30; flat by 15:15.
- Enter CE when `callWallWeakening` holds on 2 consecutive snapshots (minutes), the index is above its VWAP proxy,
  and the futures OI state is not FRESH_SHORT; PE the mirror (`putFloorWeakening`, below VWAP, not FRESH_LONG).
  ATM, one position at a time; a re-entry needs a fresh 2-minute signal.
- Exits: a 3-minute close back through the last swing (as the trend rider); the trail of etr-v6 (armed at +50 % on
  the bid, exit 15 % below its peak); a time stop if the bid is not 10 % above the average premium within 15 minutes
  (a cascade either starts fast or not at all); a resting 30 % premium stop; flat at 15:15.
- Size: premium budget ₹2,50,000 (half the starting capital; risk v10 scales it), last in decision order so the
  existing strategies take the capital first on a common trigger.

## Test
Tick replays, live set (application.yml, 7 strategies) with and without wbr-v1, and wbr-v1 alone, risk v10, one
image: expiry days not used in the design: 29 Sep and 6 Oct (NIFTY, own capture); held-out 22 Sep (NIFTY, zt), read
once. 24 Sep and 1 Oct (design days) are run and reported but do not count. SENSEX test days (3, 10, 17 Sep) need
the archive drive and are added when it is connected; forward expiry days are replayed after each close.

**Reading, fixed now:** with fewer than 3 wbr trades over the counted days the result is INSUFFICIENT and the test
continues on forward expiry days until 8 counted expiry days. Otherwise PASS if (1) wbr-v1 alone nets > 0 over the
counted days, (2) the set with wbr nets more than the set without it, (3) no day where the account kill switch fires
with wbr but not without, and (4) the existing strategies keep ≥ 90 % of their net on every day they made money.
A PASS means a decision about live PAPER, not an automatic switch.

## Result (GCP, 7 Oct 22:35–23:15 IST, image 2e6cd50)

| day | live set | set + wbr | wbr alone | role |
|---|---|---|---|---|
| 29 Sep NIFTY | −1,494 | −1,494 | 0 | counted |
| 6 Oct NIFTY | −82,534 | −82,534 | 0 | counted |
| 22 Sep NIFTY | +1,93,161 | +45,551 | +19,869 | counted, held-out (read once) |
| 24 Sep SENSEX | +3,67,785 | +3,67,785 | 0 | design |
| 1 Oct SENSEX | +6,85,467 | +6,85,467 | 0 | design |

- wbr alone traded twice on the counted days, both 22 Sep NIFTY PE: 10:36–10:58 +₹92,937 (TRAIL_15) and 13:52–14:14
  −₹73,068 (PREMIUM_STOP). Beside the set the 10:36 entry was refused for capital (the early-confirm runner held it)
  and the 13:52 entry took capital from the straddle (+₹77,758 instead of +₹1,52,300): 22 Sep −₹1,47,610 vs the set.
- The signal never fired on SENSEX on any day, including both design days: 0 minutes of wall weakening. Cause found
  in own capture: BSE option OI updates about every 2.7 minutes (SENSEX 72200 PE, 1 Oct 12:00–13:00: 23 OI changes in
  22 of 60 minutes) against every minute on NSE (NIFTY 22600 PE, 6 Oct: 60 changes in 59 minutes). The feature needs OI
  strictly lower at 10, 5, 3 and 1 minutes ago and now, which stale BSE OI almost never satisfies.
**Reading: INSUFFICIENT** (2 trades on the counted days, < 3) and structurally blind on SENSEX, the index it was
designed from. Not live. A version that tests OI on BSE's update cadence would be a new registered variant (v2); its
design days stay excluded.

## A-058, wall-break rider v2: registered 7 Oct 23:25 IST, before any result
User, 23:20 IST: "yes build v2 and test it after the close". The v1 signal could not fire on SENSEX (BSE option OI
updates every ~2.7 minutes). Features gain two numbers, nothing existing changes: `callWallOiChangePct` and
`putFloorOiChangePct`, the OI change at the call barrier (put support) strike over 10 minutes, percent.
**wbr-v2** = wbr-v1 with the signal: CE when the call wall's OI is down ≥ 5 % over 10 minutes and the index is up over
3 minutes (PE: put floor down ≥ 5 %, index down), on 2 consecutive minutes; VWAP side, futures OI, window, exits and
size as v1. 5 % is judgment, fixed now.

Test after the 8 Oct close, one image, risk v10, the live set (7 strategies) with and without wbr-v2 and wbr-v2 alone:
counted 29 Sep, 6 Oct (NIFTY, own), 8 Oct (SENSEX, own, the first forward day), 22 Sep (NIFTY, zt, held-out, read
once for this hypothesis); design days 24 Sep and 1 Oct reported, not counted. **Reading as A-057**: < 3 wbr trades on
the counted days = INSUFFICIENT (continue on forward expiry days to 8 counted days); else PASS if wbr-v2 alone > 0,
set + wbr-v2 > set, no new kill switch, existing strategies keep ≥ 90 % on their winning days.

### A-058 result (GCP, 8 Oct 16:15–17:10 IST, image f97ca62)

| day | live set | set + wbr-v2 | wbr-v2 alone | role |
|---|---|---|---|---|
| 8 Oct SENSEX | +3,98,948 | +2,80,202 | −36,272 (7 trades) | counted (forward) |
| 29 Sep NIFTY | −1,494 | +12,714 | +14,208 (3) | counted |
| 6 Oct NIFTY | −82,534 | −82,534 | 0 | counted |
| 22 Sep NIFTY | +1,93,161 | +1,27,911 | +7,247 (4) | counted, held-out (once) |
| counted total | +5,08,081 | +3,38,293 | −14,817 (14) | |
| 24 Sep SENSEX | +3,67,785 | +5,05,848 | +3,18,811 | design |
| 1 Oct SENSEX | +6,85,467 | +9,78,366 | +1,84,075 | design |

- v2's signal fires on SENSEX now (8 Oct: 7 trades). On the counted days alone it lost ₹14,817 over 14 trades; on its
  two design days it made +₹5,02,886: it fits the days it was built from and not the others.
- Beside the set: 8 Oct the trend rider v1 made +₹1,66,476 instead of +₹3,31,285 (50 %), 22 Sep the straddle
  +₹77,758 instead of +₹1,52,300 (51 %): wbr-v2 takes their capital.
**Reading: FAIL** (rules 1, 2 and 4). Not deployed.
