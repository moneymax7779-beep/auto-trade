# Check of a pasted chart diagnosis, and its sweep-reversal and coil-breakout rules (A-391)

User, 13 Nov 2026: pasted another assistant's reading of the 11 NIFTY replay charts (the same charts as A-389, with the
user's red swing lines) and asked: "please do this sincerely brutally honestly, don't just assume and Check this".

The rule was registered in the ledger (A-391 and a note row) before anything was computed. Script:
`docs/studies/2026-11-13-sweep-reversal-check.py`; output `.local/a391/result.md`.

## The sample problem

The charts come from the 29-day live-five PAPER_REPLAY batch (sessions 35390–35502, 140 trades, +68.78 L).

| | Days | Trades | Net |
|---|---|---|---|
| The 11 chart days | 11 | 48 | −4.22 L |
| The other 18 days | 18 | 92 | +73.00 L |

A diagnosis drawn only from the losing days will find the features of the trades that made the money and call them
mistakes.

## Part A: the factual claims

| Claim | Check |
|---|---|
| "10 charts" | There are 11. |
| Best session 6 Oct is "the 3rd chart"; 7 Oct is "the 2nd"; 3 Nov is "the 9th" | Wrong numbering: 6 Oct is the 9th, 7 Oct the 10th, 3 Nov the 3rd. |
| 6 Oct (+85.5 K) shows the winning pattern: a clean NIFTY trend above VWAP / EMA20 | The +85.5 K came from SENSEX. The only NIFTY trade (etr CE 12:39) **lost −34.0 K**. |
| 7 Oct won on a midday breakdown below VWAP / EMA20 | Right: ma-cross PE 12:21, +1.26 L. |
| 3 Nov −1.28 L "bought directly into stacked R 4x / R 6x walls" | The wbr CE at 13:43 was 0.49 % above VWAP. **No 4x+ resistance zone, PDH or ORH was above it at entry.** The R tags on the right of the chart are the zones as of the close, so they are hindsight. |
| Charts 5–8 had VIX > 16 and the largest drawdowns | VIX at the open: 27 Oct 17.16 and 26 Oct 16.86 (yes), 22 Oct 15.68 and 5 Oct 14.46 (no). The second-largest loss day, 4 Nov (−1.19 L), opened at 15.72. |
| Early obc / brt entries got chopped on these days | True on these days. But see F1 below. |

## Part A: its four filters on all 140 trades

A positive number means the filter removes net profit, so the filter hurts.

| Filter | Trades removed | Winners | Net removed | H1 | H2 | Chart days | Other days |
|---|---|---|---|---|---|---|---|
| F1 no brt / obc entry before 10:00 | 45 | 21 | **+35.74 L** | +28.56 L | +7.17 L | −2.24 L | +37.98 L |
| F1 before 10:15 | 54 | 25 | +37.38 L | +29.88 L | +7.50 L | −2.11 L | +39.48 L |
| F2 > 0.35 % beyond VWAP / EMA20 3m | 15 | 8 | +9.25 L | +0.64 L | +8.62 L | −1.88 L | +11.14 L |
| F2 > 0.50 % | 5 | 2 | +3.51 L | −0.37 L | +3.88 L | 0 | +3.51 L |
| F3 within 15 pts of a 4x+ wall or PDH / ORH (mirrored) | 23 | 12 | +16.36 L | +9.93 L | +6.43 L | +0.37 L | +15.99 L |
| F3 within 20 pts | 25 | 14 | +16.90 L | +10.31 L | +6.59 L | +0.37 L | +16.53 L |

- **F1, F2 and F3 each remove the trades that made the money.** On the chart days each one looks right, and on the
  other 18 days each one removes profit. This agrees with A-389 (in ATR units), A-321 and A-381.
- **F4 (VIX at the open > 16 → half size)** would have added +38.9 K, about 0.6 % of the batch, from 19 trades on
  5 days, all in H2. No H1 day opened above 16, so it can't be judged on halves.

  | VIX at the open | Days | Net |
  |---|---|---|
  | 16–17 | 3 | +1.48 L |
  | Above 17 | 2 | −2.26 L |

  The same idea failed out of sample before (A-208) and was not supported by A-234.

## Part B: the pasted rules, 31 tick days × NIFTY and SENSEX, 1 lot after costs

| Rule | Trades | Win | Net | PF | H1 | H2 | Without best day | Chart days | Other days |
|---|---|---|---|---|---|---|---|---|---|
| **B1 liquidity sweep / double-top reversal** | 43 | 30 % | **−13,249** | 0.54 | −8,698 | −4,551 | −15,442 | −2,355 | −10,894 |
| B1 exactly as coded (scans from 12:15) | 14 | 29 % | −3,669 | 0.54 | −3,351 | −318 | −5,281 | −1,351 | −2,318 |
| B1 + OI booster (reported only) | 23 | 39 % | −3,129 | 0.76 | −3,115 | −14 | −5,323 | +1,152 | −4,281 |
| **B2 coil breakout** | 22 | 41 % | +2,544 | 1.22 | +547 | +1,997 | −3,073 | −1,698 | +4,242 |

**Both rules FAIL.**
- **B1:** 29 of 43 trades hit the stop beyond the sweep wick. The other 14 were 13 targets and one flat at 15:15.
- **B2:** too few trades (22), PF below 1.3, and it is negative without its best day.

Problems in the pasted code itself:
- The loop starts at bar 60, which is 12:15. It cannot catch the 09:40 and 10:30 sweeps its own text cites.
- It sweeps the highest swing of the last 3 hours, not the most recent one.
- It needs the 3-minute EMA20, which exists only from 10:14.
- Its volume column is never used.

## Why the red lines don't become a strategy

- **A swing high or low is only known after price has turned away from it.** On the chart every red line marks a turn
  that happened.
- **In real time, a "tap and reject" at a prior swing fires many times.** Most of those times price goes through, and
  that is the 30 % win rate.
- **This is the same family that failed before:**
  - A-038 failed breakout
  - A-039 range-edge fade
  - A-130 stretch reversion
  - A-344 absorption reversal (2 trades)
  - A-315: levels hold no better than placebo levels
  - A-025: zone events

## Conclusion

The pasted diagnosis reads the losing days well, but it doesn't hold on the full sample. Nothing here should change the
live set or be registered as a new strategy.
