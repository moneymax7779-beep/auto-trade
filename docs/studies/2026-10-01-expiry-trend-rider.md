# Expiry trend rider (registered 2026-10-01 22:2x, before any code or result)

Question (user, 2026-10-01: "build it, test on archive and run shadow"): on the day an index expires, does
riding a trend that keeps making new day lows (highs) beyond the opening range — with breadth, futures OI
and ATM IV agreeing — make money for a bought ATM option, with re-entries and adds on each new extreme and an
exit on a 3-minute close back beyond the last swing? Mechanism: option sellers are short gamma on expiry and
their hedging pushes a running index further, while IV expands. **1 Oct 2026 is the design day (the idea came
from it): reported, not counted.**

## Rules (strategy `expiry-trend-rider`, file `expiry-trend-rider.v1.yaml`, version etr-v1; fixed)
- Scope: only the index that expires today (DTE 0); new entries and adds 11:00–14:30; flat at 15:15.
- Trend state for puts (calls mirror), all at the same snapshot:
  - spot below today's opening-range low (above the high for calls), opening range complete;
  - weighted mover breadth ≤ −50 (≥ +50);
  - futures OI state not FRESH_LONG or SHORT_COVERING (calls: not FRESH_SHORT or LONG_UNWIND);
  - ATM IV change over 3 minutes > 0.
- Trigger: the day's low is lower than at the previous snapshot (calls: the day's high higher).
- Entry: flat → buy 1 plan lot of the ATM put (premium budget ₹5,00,000 for a 4-lot plan, so about ₹1,25,000
  a lot); already holding puts → add 1 plan lot on the next trigger, at most 3 adds; holding calls → ignored.
- Exit (the whole position): a 3-minute close above the last swing high (calls: below the last swing low); or
  15:15; or the resting premium stop at −40 % (emergency only). After an exit the next trigger may enter again
  (no limit on trades, user's decision).
- Account: paper-risk v4 (₹5,00,000, daily loss limit ₹1,10,000), features v7, costs v1, base fills; the trend
  rider runs alone in the test (no other strategy competes for capital).

## Sessions (expiry days with complete data)
- Archive (zt-tiger-v2's archive files, restored into the scratch database `zt_archive`, never into zt):
  3 Sep (SENSEX), 8 Sep (NIFTY), 10 Sep (SENSEX), 15 Sep (NIFTY), 17 Sep (SENSEX); held-out (read once for this
  hypothesis, reported apart): 22 Sep (NIFTY), 24 Sep (SENSEX).
- Own capture: 29 Sep (NIFTY). Design: 1 Oct (SENSEX).
- Data rule: a day is dropped (and counted) if the expiring index's snapshots have more than 5 stale or missing
  minutes (last tick > 60 s old) between 11:00 and 15:15, or the replay delivers under 1,000,000 events.

## Reported
Every trade (index, day, side, entries and adds with times and prices, lots, premium, exit time, price and
reason, net); per day and in total for test, held-out and design apart; wins, worst trade, worst day.

## What counts
Descriptive, a handful of expiry days. It is a candidate for live PAPER only if it is net positive on the test
days and on the held-out days separately and no day loses more than ₹1,10,000. Shadow testing on new days runs
either way (user's decision); a live switch is the user's call.

## Results (computed 2026-10-01 23:3x; image `5c005a2-etr`, replay sessions 371–378)
Restore check: 3, 8, 10, 15, 17, 22, 24 Sep loaded in full into `zt_archive` with the manifest's content SHA-256
and row counts matching; the previous days' constituent rows were loaded for index weights (the archive drive
disconnected during the last file, 23 Sep, which only serves the dropped 24 Sep). Control: the live set on 22 Sep
from `zt_archive` reproduces the earlier zt replay exactly (3,725,189 events, +₹1,70,802).
Data rule: **dropped 17 Sep** (30 missing index minutes 11:00–15:15) and **24 Sep** (6 missing: the capture ends
15:08); 15 Sep has 1 missing minute (kept).

| Day | Group | Trades (contract, lots, entry → exit, reason, net) | Day net |
| --- | --- | --- | --- |
| 3 Sep SENSEX | test | 76700 PE 132 lots (3 entries 11:06–11:09, ₹3,95,406) 149.78 → 90.59 at 11:36, swing high reclaimed, **−₹1,57,127**; then the ₹1.1L kill switch blocked new entries | **−₹1,57,127** |
| 8 Sep NIFTY | test | 23600 PE 99 lots 13:52–14:06 19.10 → 13.89, swing, −₹33,941 | −₹33,941 |
| 10 Sep SENSEX | test | none | ₹0 |
| 15 Sep NIFTY | test | 23300 PE 92 lots 11:20–12:06 41.33 → 32.35, swing, −₹54,314; 23300 PE 132 lots 12:59–15:15 51.77 → 113.45, flat-by, **+₹5,27,223** | +₹4,72,909 |
| 29 Sep NIFTY | test (own capture) | none | ₹0 |
| 22 Sep NIFTY | held-out | 23350 PE 46 lots 11:47–13:36 41.43 → 44.25, swing, +₹8,047; 23300 PE 90 lots 13:55–14:14 47.00 → 29.70, premium stop, −₹1,01,833 | **−₹93,786** |
| 1 Oct SENSEX | design (not counted) | 72200 PE 102 lots 12:20–14:30 192.66 → 610.63, swing, +₹8,50,544 | +₹8,50,544 |

- Test days: **+₹2,81,841** over 4 trades (1 win). All of it is one trade (15 Sep afternoon, +₹5,27,223); without it
  the test days lose −₹2,45,382.
- Held-out: **−₹93,786** (2 trades, 1 small win).
- Worst day **−₹1,57,127 (3 Sep)**: a ₹3.95L position built in 3 minutes lost more than the daily limit before the
  swing exit; the kill switch only stops new entries.

## Reading
**Fails the registered criterion** on both counts: the held-out day is negative, and 3 Sep lost more than
₹1,10,000. The idea catches real expiry trends (15 Sep afternoon, 1 Oct) but the same rules buy the first leg
of moves that reverse (3 Sep, 8 Sep, 15 Sep morning, 22 Sep afternoon), and with ₹5L in three quick adds a
reversal costs more than the daily limit. Not a candidate for live PAPER. Shadow testing on new days runs anyway
(user's decision), so it keeps collecting unseen evidence without risk.
