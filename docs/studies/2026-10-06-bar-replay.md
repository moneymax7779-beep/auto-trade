# Bar replay: Upstox one-minute history as a second evidence class (ledger A-045), registered before any result

User's request, 6 Oct 2026 ("yes start" to the backtesting-data plan). Every hypothesis so far was judged on about ten
test days and two held-out days from the tick captures (zt-tiger-v2's archive, 2 Sep-1 Oct; auto-trade's own capture
from 28 Sep). Upstox (the account is on the Plus plan) serves one-minute bars with volume and open interest for expired
options and futures, and one-minute index, VIX and equity bars from January 2022. Bars can give every hypothesis
registered test and held-out months instead of days, if a replay from bars is close enough to a replay from ticks.

## What a bar replay is
`--mode=fetch-bars` pulls the bars into `hist.candle`; `--autotrade.trading.replay-source=bars` replays a day from them
through the unchanged engine. Each bar becomes four synthetic ticks (open; low and high in the bar's direction; the close
at 59.5 s), so nothing is known before the bar closes. Futures carry a cumulative volume, OI and a VWAP from bar closes;
options one depth level at the last price (no spread) and no IV or greeks (the features model them); constituents the
day's weights. Absent: the order book (spread, imbalance, depth), the closing auction, session-phase events, IV history,
and ticks within a minute (fills are at bar prices).

## Calibration (this study's question)
On the days both sources hold, 28, 29, 30 Sep, 1, 5, 6 Oct (own tick capture on the GCP VM), replay the live strategy set
(ebs-v2, ecr-v11, odb-v3, egb-v4, etr-v1; features v11; risk v4; costs v2) once from ticks and once from bars, and the
A-037 break-and-retest strategy standalone, and compare per day and per strategy:
- trades: same entries (minute, side, strike) or not; the premium paid and the net;
- the day's net;
- feature agreement at the entry minutes: spot, VWAP proxy, ATR(3m), futures RVOL, ATM IV.

**Reading, fixed now:** bars are usable for *screening* (ranking ideas, finding candidate rules) if, over these days, the
set's total net from bars is within 25 % of the tick total and at least 70 % of the tick trades appear in the bar replay
at the same minute and side. They are never the final evidence: a rule found on bars is still judged on tick days
(own capture, forward) before it goes live. If the agreement is worse, the bar replay is reported as such and used only
for coarse questions (does a rule trade at all, how often, which days), with the disagreement stated each time.

## Known limits (stated, not hidden)
- The brokerage in costs v2 (Rs 30) applies to both replays; execution from bars is optimistic (no spread, bar prices).
- Upstox history of expired contracts: how far back it reaches is unknown until the first pull; strikes ATM +-10 of the
  week's index range only; the contract's last 8 days only.
- A bar day needs the previous bar days too (RVOL over 10 sessions, the previous session's levels): the pull should start
  at least 15 trading days before the first day to be replayed.

## Result (6 Oct 2026, evening; GCP VM, own tick capture vs Upstox bars; costs v2, features v11, risk v4)
Pull: 1,720,524 bars for 8 Sep–5 Oct (700 option contracts of the week's ATM ±10 strikes, 80 constituents, 4 futures,
index, VIX). Expired-contract history reaches at least 8 Sep; the 6 Oct contracts appear in the expired list only after
their day, so 6 Oct is not in this round. One problem logged: SENSEX 70700 CE 01 OCT had no candles.

Tick replays (sessions 534–553, code 132de01):

| day | live set, ticks | break-retest, ticks |
|---|---|---|
| 28 Sep | +3,65,097 (NIFTY 22950 PE 09:16→10:00) | 0 |
| 29 Sep | 0 | −1,494 |
| 30 Sep | −1,11,378 (SENSEX 72700 CE 09:21, 09:24) | +53,259 (6 trades) |
| 1 Oct | +8,50,438 (SENSEX 72200 PE 12:20→14:30, 2,040) | +11,353 (2) |
| 5 Oct | 0 | +34,450 (3) |
| total | +11,04,157 | +97,568 (12 trades) |

Bar replays took four rounds. Each round fixed an artefact of the synthetic ticks found in the previous one; the
strategies, features and risk were never touched.

| round | synthetic ticks | unfilled entries | live set, bars | break-retest, bars |
|---|---|---|---|---|
| 1 | open, extreme, extreme, close at 1/20/40/59.5 s | 8 of 13 | +4,83,255 | +1,17,862 |
| 2 | each price twice, 1 s apart | 4 of 13 | +2,76,585 | +1,45,246 |
| 3 | r2 + quotes before the index tick at an instant | 4 of 13 (no change) | +2,76,585 | +1,45,246 |
| 4 | r3 + options/futures quote the next open 100 ms before the minute | 0 of 10 | +11,15,806 | +1,20,638 |

Why the entries did not fill (rounds 1–3): a marketable limit is priced at the quote the feature snapshot saw. The
snapshot at :00 is taken from the state before any event at or after :00, so the quote was the previous bar's close
(orders table: 28 Sep 09:16:01 limit ₹351.85 = 09:15 close ₹351.65 + 4 ticks; the 09:16 bar opened at ₹356.50). In a
tick stream the quote at the boundary is about the next open, hence round 4.

Round 4 against the registered rule:
- Totals: live set +1.1 % of the tick total, break-retest +23.6 %. Both within 25 %, but the live-set agreement is one
  trade's doing: 1 Oct ETR is identical (minute, strike, 2,040 qty; +₹8,64,505 vs +₹8,50,438, exit one bar later).
  28 Sep: both replays fire the opening-drive put at 09:16 on both indices and capital admits one; ticks took NIFTY
  (+₹3,65,097), bars SENSEX (+₹2,83,116). 30 Sep: bars trade once at 09:25 (−₹31,815), ticks twice at 09:21 and 09:24
  (−₹1,11,378).
- Trades at the same minute and side: live set 1 of 4 (2 of 4 counting the 28 Sep other-index trade); break-retest
  4 of 12 (30 Sep 10:03, 10:41, 12:18, 12:21), one a minute off (11:49 vs 11:50), 6 tick trades missing (1 Oct both,
  5 Oct all three), 2 bar trades ticks never took (29 Sep 09:36 PE, 30 Sep 11:38). Required: 70 %.

**Reading: FAIL on the registered rule.** Fills now agree; signals do not, at 25–50 % trade-level agreement. Bars are
used only for coarse questions (does a rule trade at all, how often, which days, order of magnitude), and every bar
result is reported with this disagreement. Tick days (own capture, forward) remain the evidence. The next step, if
pursued, is signal-level: compare the feature values at the tick entry minutes between the two sources (spot, VWAP
proxy, ATR, RVOL, breadth, the retest detection), which bars lack the order book, spreads, auction and sub-minute path
for. The round 2–4 changes to `BarReplaySource` (and its tests) are in the working tree, not committed.

## Correction, later the same night: coverage, not signals, explained most of the missing trades
The first pull had no NIFTY option bars after 29 Sep and no SENSEX option bars after 1 Oct: the Plus expired-instruments
list shows an expiry only after its day, and the fetcher never pulled the contracts still alive. So on 30 Sep (NIFTY),
1 Oct (NIFTY) and 5 Oct (both) the bar replay had nothing to buy, and five of the six "missing" break-retest trades of
round 4 fall exactly there. Two more fetcher changes (round 6, sessions 600–607): live contracts of unexpired expiries
come from the instrument master through the public endpoint, and the current day comes from Upstox's intraday
endpoint, which serves the whole day after the close (today's expired options included, with OI). A fifth round
crashed on a rate limit while re-pulling stored contracts; the fetcher now skips ranges already in `hist.fetch_log` and
survives one contract's failure. While doing this: `InstrumentStore.latest` grouped snapshots by the full source string
(one per day's file), so every master it returned held each contract once per day loaded (1,510 rows for 232 NIFTY
6 Oct options); fixed to group by exchange family.

Final comparison (r4 for 28–29 Sep, whose coverage was complete; r6 for 30 Sep–6 Oct; 6 Oct ticks from sessions 588–589):

| day | live set, ticks | live set, bars | break-retest, ticks | break-retest, bars |
|---|---|---|---|---|
| 28 Sep | +3,65,097 (NIFTY PE 09:16) | +2,83,116 (SENSEX PE 09:16) | 0 | 0 |
| 29 Sep | 0 | 0 | −1,494 (1) | +46,851 (1, different) |
| 30 Sep | −1,11,378 (09:21, 09:24) | −31,815 (09:25) | +53,259 (6) | +59,128 (9: 5 same + 11:50, 3 extra) |
| 1 Oct | +8,50,438 (ETR 12:20, 2,040) | +8,64,505 (same) | +11,353 (2) | +26,565 (2 same) |
| 5 Oct | 0 | 0 | +34,450 (3) | −22,438 (5: 2 same, 3 extra) |
| 6 Oct | −46,196 (ETR 12:39, 2,470) | −1,48,076 (ETR 11:50 extra; 12:39 same contract, one add more, premium stop) | +9,768 (2) | +30,134 (2: 1 same, 1 extra) |
| total | +10,57,961 | +9,67,730 (−8.5 %) | +1,07,336 (14) | +1,40,240 (+30.7 %) |

Against the registered rule: the live set's total is within 25 % but only 2 of its 5 trades (3 counting 28 Sep) match
by minute and side; break-retest matches 10 of 14 trades (71 %) but its total is 31 % off. **Still a FAIL, narrowly**,
and the picture is now: entries agree most of the time, position paths differ (adds, exits at bar prices, capital
contention between indices at the same minute), and a few signals differ. Bars are used for coarse questions and for
ranking candidate rules, always with this disagreement stated; tick days decide. A different reading needs a new
registered rule, not a re-reading of this one.
