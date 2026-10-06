# Trend pullback scalper (ledger A-035): registered before any code or result

User's request, 6 Oct 2026, after reading the NIFTY and SENSEX charts of 6 Oct: "fix the alert and build the scalper".
On 6 Oct both indices offered five or six clean setups (a level break and its retest, pullbacks to tested support
inside the trend) worth +8 % to +39 % of an ATM option's premium each, while the live strategies made one losing
trade (ETR held a runner through a dead range). Scalping targets of 2-5 % of premium are inside the bid-ask spread,
costs and one minute's noise, so v1 aims at +8 % (+15 % on the expiring index) with a stop under the pullback.

## Rule, v1 (`trend-pullback-scalper.v1.yaml`), both directions; every value a round number fixed now
Calls in an up-trend (puts mirrored in a down-trend):
1. **Trend**: the index above the spot VWAP; its EMA20 of minute closes higher than 10 minutes ago; a new day high
   within the last 45 minutes.
2. **Pullback**: since that day high H, the index fell to a low L at least 0.75 x ATR(3m) and at most 3 x ATR(3m)
   below H, with L still above the VWAP.
3. **Turn (entry)**: a minute close above the two previous minute closes, at least 0.3 x ATR(3m) above L and still
   below H (no chasing the high). One entry per pullback; a new day high starts the next one. Entries 09:45-14:30.
4. **Exits, whichever comes first**: the option's bid at +15 % over the average cost on the index that expires that
   day, +8 % on any other (target); the index back below L (stop); 15 minutes after entry (time stop); 15:15.
   A resting premium stop, 10 % (expiring index) / 6 % (other), backs up the structure stop.
5. **Size**: Rs 5,00,000 of ATM premium, one entry, no adds (PAPER).

Not in v1: the opening-low reclaim and the failed-breakout short of 6 Oct (different mechanics), and taking half
off at the target while trailing the rest (the OMS cannot reduce a position yet).

## Test
Standalone replays (only this strategy), features v11, risk v4.
- Test: 3, 8, 10, 15, 17 Sep (zt archive), 28, 29, 30 Sep, 1 Oct (own capture, Mac) and 5 Oct (own capture, GCP).
- Held-out: 22, 24 Sep, read once for this hypothesis.
- 6 Oct: the design day, reported apart, not counted.

**PASS** only if, on the test days: at least 20 trades, win rate >= 40 %, net > 0, profit factor >= 1.3 and no day
below -Rs 1,10,000; and on the held-out days net >= 0. Fewer than 20 test trades = INCONCLUSIVE.
Reported per trade: index, side, entry / exit time, index points, premium %, net, exit reason.

## Result (Mac replays 479-485, GCP replays 499-504)
| Set | Trades | Wins | Net | Profit factor | Worst day |
|---|---|---|---|---|---|
| Test (10 days) | 53 | 22 (42 %) | +2,52,341 | 1.23 | 3 Sep -1,32,760 (10 Sep -1,17,868) |
| Held-out (22, 24 Sep) | 11 | 3 | -1,80,309 | 0.43 | 24 Sep -1,29,670 |
| Design day 6 Oct | 7 | 2 | -95,177 | 0.59 | |

Test days: 3 Sep -1,32,760; 8 Sep +1,29,144; 10 Sep -1,17,868; 15 Sep +1,27,887; 17 Sep -67,867; 28 Sep -89,553;
29 Sep +93,156; 30 Sep +25,268; 1 Oct -10,113; 5 Oct +2,95,047 (without 5 Oct: -42,706).
Verdict: FAIL (profit factor < 1.3, two days below -1.1L, held-out negative). Most losses were the resting premium
stop (6 % / 10 %) firing within 1-4 minutes, before the structure stop; on 6 Oct the rule also entered the midday range
on the expiring index four times. Any v2 (structure stop only, a range filter, smaller size) is fitted to these days
and can be judged only on days from 7 Oct.
