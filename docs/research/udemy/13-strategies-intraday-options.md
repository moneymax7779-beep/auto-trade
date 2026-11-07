# Udemy — "13 Strategies for Intraday & options" — triage notes

Read 24 Oct 2026 (course id 5498250, 34 lectures, rating 4.6 from 288). Only the 14 "Explanation" lectures were scanned
(openings in full, then searched for entry / stop / target wording); the "Backtesting" lectures were not read.
Base URL `https://www.udemy.com/course/13-strategies-for-intraday-options/learn/lecture/<id>`.

All on **5-minute charts** (one on 1-minute), for any instrument; generic retail indicator rules:

| # | Strategy | Rule as taught | Overlap with our tests |
|---|---|---|---|
| 1 | 15-minute ORB | high/low of the first three 5-min candles; enter on the candle after the breakout | A-082 ORB (alone PF 3.17, FAIL in the set) |
| 2 | VWAP | a candle **closes** across VWAP ("opening candle"); the next candle breaks its high (low) = "signal candle"; the next breaks the signal candle's high (low) = "entry candle", enter; skip if a candle breaks the other side first, or if the entry candle is "far" from VWAP (not quantified). Stop (Backtesting lecture only): exit when a candle breaks back through VWAP. Target: not stated. His count: 100 trades on indices and stocks, 48 targets / 34 stops / 18 neither; no R, P&L or costs; rates it 7/10 | **A-212 FAIL** (24 Oct, 2 years of NIFTY bars, both exits); A-079, A-081 FAIL |
| 3 | VWAP + VWMA | VWAP with a volume-weighted MA | as above |
| 4 | McGinley Dynamic 35/50/70 | three McGinley lines aligned | MA-cross family (mac live; A-156 FAIL) |
| 5 | Bollinger Band + ADX | instructor says it gives many false signals and he does not use it | – |
| 6 | "6C" | high/low of the **6th 5-min candle (09:40–09:45)**; after a breakout candle, enter when the next candle breaks the breakout candle's high/low; skip or wait for a retest if the breakout candle is very large | ORB family |
| 7 | VWAP + EMA | as 2 with an EMA | A-079 |
| 8 | Demand / supply zones | discretionary | swing-zone study (no level edge) |
| 9 | CPR | central pivot range from the prior day | not tested; pivots not in our features |
| 10 | SMA 5/8/13 scalping (1-min) | MA stack scalps | MA-cross family |
| 11a | "12 PM" | same as 6C on the **12:00 candle**; only in trending markets | ORB family |
| 11b | EMA 20/35 | EMA pair | MA-cross family |
| 12–13 | "Algo setups" | TradingView indicator signals (chandelier exit + another) | – |

- **No stop-loss or target is stated** in the explanation lectures (only "advanced traders keep small stops"), so none of
  these can be tested as taught without inventing the exits. Not registered.
