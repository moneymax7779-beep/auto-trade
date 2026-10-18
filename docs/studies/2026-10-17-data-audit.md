# Data audit, 17 Oct 2026: is the data we test on correct?

Sources: Upstox 1-minute bars (`hist.candle`, Jan–Sep), our own tick capture (`md.*`, 28 Sep–16 Oct, Upstox V3 live feed),
zt-tiger-v2's tick capture (`zt_archive`, 2 Sep–13 Oct). Scripts and raw tables: `.local/data-audit/` (audit.md).

## Index prices: correct
- Three sources, same days: day highs/lows agree within 0–5 NIFTY points on 26 of 27 overlapping NIFTY days; SENSEX within
  0–25 points except where zt stopped earlier (below).
- Official close (the exchange's 'previous close' carried by the next day's feed): own capture's last tick = official close
  on every day; the bars' 15:29 close = official close on every day except SENSEX 23 Sep (−1.49) and 5 Oct (−70.24, all three
  sources: BSE's published close differs from the last value every source received).
- The official close is computed by the exchange, not the last traded index value: between 15:20 and the close it moved by
  up to ±75 NIFTY / ±219 SENSEX points. That is the "jump" at the right edge of the charts, not a data error.
- Bars: 185 trading days Jan–Sep, every one with all 375 minutes, for NIFTY, SENSEX and India VIX.

## Option prices: consistent
- Same contracts, same minutes, own capture vs zt (both Upstox): NIFTY median mid difference 0.00 points (p95 0.02–0.20),
  identical spreads (0.23–0.25 %) and OI; SENSEX median 0.19 points (p95 2.85; BSE ticks arrive less often).
- Own capture: every option row carries a bid, an ask and depth (checked on 6 days, 1.5–2.3 million rows per index-day).

## Limitations found (known, now measured)
- zt capture ends at 15:15 (360 minutes); its 'last' price is not the close.
- zt gaps in index ticks in market hours: 2 Sep 918 s (≈15 min), 17 Sep 568 s (≈9.5 min, 30 minutes missing that day),
  15 Sep 152 s, 9 Sep 132 s, 3 Sep 59 s, 8 Sep 47 s; and about 40 % fewer ticks per day before 15 Sep. Replays refuse
  entries during such gaps ("stale feed").
- zt records a moving window of strikes: a deep in-the-money position can lose its quotes (15 Sep SENSEX 75000 PE after 14:46),
  so a replay cannot close it (open-at-end check added to the test scripts).
- September zt days have no contract master (default tick and freeze limits used).
- Bars are 1-minute OHLC without bid/ask: bar replays fill at modelled prices and differ from tick replays (A-045: totals
  within 9–31 %, 40–71 % of trades match); breadth (constituents) exists only from July.
- The Sessions chart's prior-day level lines can be wrong (13 Oct SENSEX "PDH 72,631.93" vs the true 72,879.78); the
  strategies use the correct value. Fix in progress in a separate session.
- Not independently verifiable here: the option quotes against the exchange's own records (both captures come from the
  same Upstox feed). Index closes match the exchange, which supports the feed.
