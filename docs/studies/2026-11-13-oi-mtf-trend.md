# Multi-timeframe OI trend + level break, scalp and runner (A-386)

User, 13 Nov 2026, on the 12 Nov NIFTY OI panel: "read from 1 min 3min 5 min 15min 30min also look at the levels,
continuously increasing or decreasing OI shows the trend - why can't we derive some strategy even the scalping and
runner ... why we are missing them".

Rule registered in the ledger (A-386, plus a note row with the deviations) before anything was computed. Script:
`docs/studies/2026-11-13-oi-mtf-trend.py`; output `.local/a386/result.md`; trades `.local/a386/trades.json`.

## The test

- **Data:** 31 own-capture tick days (28 Sep – 12 Nov), NIFTY and SENSEX, 62 index-days. The nearest expiry, every
  1-minute close.
- **OI trend:** on the ATM ±5 strikes, net = Σ(Δput OI − Δcall OI) over 1, 3, 5, 15 and 30 minutes. "Up" means all
  five are ≥ 0 and the 5/15/30-minute values are > 0. "Down" is the mirror.
- **Price trend:** spot is above (or below) where it was 5, 15 and 30 minutes earlier.
- **Level break:** a break of a level within the last 5 minutes. Levels are the prior day's high, low and close,
  the prior day's opening range, today's open, and today's opening range.
- **Signals:**
  - **S** = level + price + OI.
  - **C** = level + price, no OI. C is the control.
  - **OI_ONLY** = OI trend alone. **OI_REV** = the opposite OI sign.
  - OI_ONLY and OI_REV are reported only.
- **Trade:** buy the ATM option at the next minute's first ask, 1 lot, after costs.
- **Exits:**
  - **Scalp:** +10 % / −10 % / 15 minutes.
  - **Runner:** −20 % stop; after +20 %, a 15 % giveback from the peak; flat at 15:15.
- **PASS** needed all of: ≥ 30 trades, PF ≥ 1.3, both halves positive, positive without the best day, and S beating C
  per trade in both halves.

## Result: FAIL for both arms

| Signal | Exit | Trades | Win | Net | PF | H1 | H2 | Per trade H1 / H2 |
|---|---|---|---|---|---|---|---|---|
| S (OI + level + price) | scalp | 197 | 47 % | −12.6 K | 0.83 | +1.4 K | −14.0 K | +16 / −132 |
| S | runner | 139 | 51 % | +14.6 K | 1.14 | +17.4 K | −2.8 K | +263 / −39 |
| C (level + price, no OI) | scalp | 329 | 48 % | −8.7 K | 0.92 | −1.0 K | −7.7 K | −6 / −45 |
| C | runner | 203 | 52 % | +34.2 K | 1.25 | +8.8 K | +25.4 K | +94 / +233 |
| OI trend alone | scalp | 872 | 48 % | −35.1 K | 0.88 | −9.2 K | −25.9 K | |
| OI trend alone | runner | 397 | 47 % | +8.7 K | 1.03 | +50.8 K | −42.2 K | |
| Opposite OI sign | runner | 67 | 46 % | +1.8 K | 1.03 | −5.0 K | +6.7 K | |

- **Adding OI to the level + price signal made it worse.** Per trade, S lost to C in the second half for both exits.
  This is the same result as A-324 (price-only control PF 4.67 against the OI rule's PF 1.23).
- **OI alone is a coin flip.** Following the OI trend and trading against it come out about the same (PF 1.03 both).
  The OI-alone runner's +50.8 K in H1 turned into −42.2 K in H2.
- **The scalp exit loses money with every signal.** A symmetric ±10 % exit with a 47–48 % hit rate cannot cover costs.
- **The best row is a control, not a candidate.** C runner (+34.2 K, PF 1.25) is a plain level-break runner with no OI.
  It is still below PF 1.3, and it is the same family as brtr, which is already live.
- **The OI trend is "on" much of the time:** 39 % of NIFTY minutes and 50 % of SENSEX minutes. A condition that holds
  half the day carries little information.
- **On the idea day (12 Nov NIFTY), S lost too:** scalp −1.2 K, runner −3.9 K.

## Why the big moves are missed (181 swings of ≥ 0.4 %)

| | Fired inside the swing | Median share of the swing already done at the first signal |
|---|---|---|
| S (with OI) | 55 % | 56 % (quartiles 40 % / 71 %) |
| C (no OI) | 69 % | 39 % (quartiles 26 % / 58 %) |
| Live five (alone replays, to 6 Nov) | held the swing's direction before its midpoint in 42 % | |

- **Waiting for OI to confirm costs about 17 points of the swing.** OI builds after price moves, so by the time all
  five windows agree, more than half the move is usually over. What is left is the part that reverses.
- **75 % of S signal minutes do fall inside a same-direction swing.** The signal is not wrong about direction. It is
  late. An option buyer pays for that lateness: the remaining move is short and the reversal hits the stop.
- **On the panel the 1–30 minute OI agreement looks predictive.** In hindsight the whole trend is visible on the panel.
  In real time the agreement only appears after the move.

## Checks

- Four sampled trades were checked against raw `md.option_tick`:
  - All four entries equal the raw first ask.
  - Every exit price lies inside that minute's raw bid range.
- **Bounded intra-minute optimism:** the scalp counts a target hit in the entry minute even if the high came before
  the fill. This flatters the result, and it still fails.
- **Loader fix:** the first cache loads filtered option ticks on `session_date` only, which scanned every compressed
  chunk (up to 50 minutes per day). `recv_ts` bounds were added. A reloaded day was identical to its earlier cache.

## Conclusion

- Multi-timeframe OI agreement is a description of a move that has already happened. With A-068, A-121/123, A-232,
  A-324 and A-383/384, OI-direction entries are now tested from every side and none survives.
- **No registration follows.** Bar-day checks are not needed for a tick-day FAIL.
