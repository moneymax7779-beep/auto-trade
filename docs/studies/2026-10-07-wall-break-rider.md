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
