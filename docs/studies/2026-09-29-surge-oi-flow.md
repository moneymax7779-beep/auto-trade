# Futures-volume surge + open-interest flow (registered 2026-09-29, before computing)

Question (user, 2026-09-29, NIFTY): at 10:07 a single 4,523-lot futures print (79× the same-minute
volume) came with futures OI +3.6 %, call OI rising and put OI falling, and the index rallied from
~10:20 to 12:08; at 12:30–12:32 a 4–10× surge came with call writing and put short covering (futures
OI flat) and a 46-point fall that stopped at the opening-range low. Does a futures-volume surge whose
OI flow agrees on a direction lead a move an ATM option captures? **29 Sep is the design day:
reported, not counted.**

## Data (read-only, after market hours)
- Futures 1-minute volume: zt `market_candles` INDEX_FUTURES_MINUTE_VOLUME_* (LIVE_V4, then
  RECOVERED_V1, then LIVE_V3; 0 = missing).
- Futures price and OI per minute: the last FUTURE tick of each minute (nearest-expiry contract) from
  zt `market_tick_records` (11–29 Sep) and the verified archive files (2–10 Sep).
- Options: zt `option_tick_snapshots`, nearest expiry, about one a minute (bid, ask, OI, ATM strike).
- Sessions 2, 3, 4, 7, 8, 9, 10, 11, 15, 16, 17, 18, 21, 22, 23, 24, 25, 28 Sep (test) + 29 Sep
  (design); NIFTY and SENSEX.

## Rule (fixed)
- Surge at minute t (bar starting 09:30–14:30): futures volume ≥ 5 × the median of the same minute
  over the previous 10 sessions (≥ 5 needed).
- Flow, measured from the last values of minute t − 1 to the last values of minute t + 1 (known at the
  close of t + 1, when the trade is taken; futures OI is published about a minute late):
  - Futures: |ΔOI| ≥ 0.2 % of OI; OI↑ price↑ (long build) or OI↓ price↑ (short covering) → +1;
    OI↑ price↓ (short build) or OI↓ price↓ (long unwinding) → −1; otherwise 0.
  - Calls, ATM ± 2 strikes (ATM as of minute t − 1): |Σ ΔOI| ≥ 1 % of Σ OI, with the ATM call's mid
    (bid+ask)/2 change: OI↑ mid↑ (buying) or OI↓ mid↑ (short covering) → +1; OI↑ mid↓ (writing) or
    OI↓ mid↓ (long unwinding) → −1; otherwise 0.
  - Puts, the same strikes: OI↑ mid↓ (writing) or OI↓ mid↓ (long unwinding) → +1; OI↑ mid↑ (buying)
    or OI↓ mid↑ (short covering) → −1; otherwise 0.
  - Signal: total ≥ +2 with no source negative → call; ≤ −2 with no source positive → put.
- Entry: the first option snapshot 0–90 s after minute t + 1 closes; the snapshot's ATM strike; buy
  at the ask (bid > 0, ask ≥ bid). One position per index at a time; signals while one is open are
  ignored; after an exit a new signal may trade.
- Exits (first to happen; sell at the snapshot bid): bid ≥ 1.20 × entry ask (target); bid ≤ 0.80 ×
  entry ask (stop); the first snapshot at or after entry + 30 minutes or 15:10.
- Costs v1, one lot (NIFTY 65, SENSEX 20).

## Data-quality rules (dropped and counted, never patched)
- A signal is not evaluable (skipped and counted) when a needed value is missing: futures ticks in
  minute t − 1 or t + 1, any of the 5 strikes on either side at t − 1 or t + 1, or volume history.
- A trade is dropped if the traded strike has a snapshot gap over 90 s before the exit.

## Reported
All surges (count, evaluable, signals by direction), every trade (index, day, time, volume ×, flow
scores, strike, entry, exit, reason, return, net per lot), totals; the design day apart.

## What counts
Descriptive, all sessions seen before; the two design-day events were chosen after seeing them.

## Results (computed 2026-09-29 evening; `so/` in the session scratchpad)

- Surges (≥ 5×, 09:30–14:30): NIFTY 320, SENSEX 162 minutes; 191 fell while a position was open;
  evaluable 175 + 82 (34 skipped for missing data); flow signals NIFTY 29 CE / 25 PE, SENSEX 20 CE / 17
  PE; 7 trades dropped for option quote gaps. **Futures OI moved ≥ 0.2 % in only 18 of 84 trades**:
  the signals come almost entirely from the option flow.

| Group | Trades | Wins | Mean/lot | Median/lot | Total/lot | Targets / stops |
| --- | --- | --- | --- | --- | --- | --- |
| **Test days (2–28 Sep)** | **70** | 24 | −₹227 | −₹336 | **−₹15,915** | 11 / 14 |
| calls | 38 | 12 | −₹258 | −₹458 | −₹9,819 | 3 / 8 |
| puts | 32 | 12 | −₹190 | −₹269 | −₹6,096 | 8 / 6 |
| NIFTY | 39 | 15 | −₹124 | −₹327 | −₹4,829 | 8 / 7 |
| SENSEX | 31 | 9 | −₹358 | −₹421 | −₹11,086 | 3 / 7 |
| Design day 29 Sep | 14 | 6 | −₹114 | −₹350 | −₹1,594 | 6 / 7 |

- The design-day events themselves: 10:07 (79×, long build / call buying / put long unwinding) →
  22600 CE 61.90 at 10:09:34, time exit 10:40 at 61.85 (−₹58; the rally came later, over two hours);
  12:31 (7.8×, call writing / put short covering) → 22650 PE 64.75 at 12:33:30, stopped at 45.60 at
  12:35 (−₹1,298; it bought the low of the fall).

## Reading
A futures-volume surge with agreeing OI flow did not lead a move an ATM option captured: 70 trades,
34 % winners, −₹15,915 per lot, losing on calls and puts and on both indices — and on the design day
too. Surges are frequent (480+ minutes) and the option-flow pattern flips within minutes, so the
signal mostly catches the end of a burst. Not a strategy.
