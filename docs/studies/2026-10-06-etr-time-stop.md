# ETR time stop (ledger A-042): registered before any code or result

User's request, 6 Oct 2026, after the 6 Oct ETR loss ("test the time stop"). On 6 Oct ETR bought NIFTY 22700 CE at 12:39
on a new day high; the option peaked +24 % at 12:43, then NIFTY went nowhere for 1 h 48 min and the call lost 37 %
(about two thirds of the loss was expiry-day time decay; IV rose 26.9 % -> 32.6 %). ETR exits only on a swing break,
at 15:15 or at its 40 % premium stop, so a stalled trade bleeds until one of those comes.

## Rule (etr-v4 = etr-v1 + one exit)
Exit the whole position when the index has made no new extreme in the trade's direction (a new day high for calls, low
for puts) for 30 minutes, counted from the later of the entry and the last new extreme. Everything else is etr-v1
(entries, adds, swing exit, 15:15 flat, 40 % stop). 30 is a round number; it is not fitted.

## Test
Replays of the live set (ebs-v2, ecr-v11, odb-v3, egb-v4, ETR last), features v11, risk v4: control with etr-v1,
variant with etr-v4, same image. ETR trades only the expiring index, so only expiry days matter:
- test: 3, 8, 10, 15, 17 Sep (zt archive, Mac), 29 Sep, 1 Oct (own capture, GCP);
- held-out: 22, 24 Sep, read once for this hypothesis;
- design day: 6 Oct, apart.

**PASS** only if: test net with etr-v4 >= etr-v1 - Rs 50,000; held-out net with etr-v4 >= etr-v1 - Rs 50,000; and the
1 Oct runner keeps at least 75 % of its etr-v1 profit (the time stop must not cut the trend days that make the money).

## Result (Mac replays 493-506, GCP 513-518)
| Day | Set | v1 | v4 | Difference |
|---|---|---|---|---|
| 15 Sep | test | +4,72,909 | +4,95,004 | +22,095 (a loser stopped at 12:01; the runner untouched) |
| 1 Oct | test | +8,50,544 | +6,72,474 | -1,78,070 (runner exited 13:30 at 609.34, 99.7 % kept; re-entry 13:43 lost -1,75,518) |
| other test days | test | | | 0 |
| 22 Sep | held-out | +1,78,849 | +1,53,619 | -25,230 |
| 24 Sep | held-out | +2,38,355 | +2,38,355 | 0 |
| 6 Oct | design | -46,149 | -8,087 | +38,062 |

Test total -1,55,975: FAIL. The time stop itself did what it was meant to (stalled trades cut, the runner exited near
its top); the loss came from the re-entry the freed position allowed on an exhausted move. A v5 that forbids a re-entry
after a time-stop exit is fitted to this result and can be judged only on new days (forward test).

## Forward test of ETR v5 (ledger A-043), registered 6 Oct before any forward day
etr-v5 = etr-v4 + no new ETR entry for the rest of that index's day after a time-stop exit. Shadow from 7 Oct, each
strategy in its own session: etr-v1 standalone (control) and etr-v5. Judged once after >= 8 expiry days with an ETR trade:
PASS if v5 net >= v1 net, v5 worst day >= v1 worst day, and on v1's >= Rs 2L days v5 keeps >= 75 % of v1's total.
