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
