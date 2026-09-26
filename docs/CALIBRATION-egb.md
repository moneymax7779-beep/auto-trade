# Expiry-day gamma breakout → retest → runner: design, rules and calibration

Registered 2026-09-26 **before** any feature distribution or trade result for this strategy was
computed. Ledger row: A-006 in `docs/EXPERIMENT-LEDGER.md`.

## Strategy (from the user's design, 2026-09-26)
Expiry day only (DTE 0), NIFTY and SENSEX (BANKNIFTY later: monthly expiry, no data). Signals come
from the underlying and futures; the option is chosen after the setup is valid.

States (CE shown, PE mirrored): WAIT → COMPRESSION → ARMED → EARLY (25%) → CONFIRMED (+45%) →
RETEST (waiting for the broken level to hold) → RUNNER (+30%) → TRAIL → EXIT.

- **COMPRESSION**: 30-minute range tight against today's usual 30-minute range, EMA9/EMA20
  converged, futures volume not rising. Remembered for 30 minutes after it ends.
- **ARMED**: directional structure (VWAP side, EMA9 over EMA20 and rising, higher lows) and spot within
  0.15 ATR of a trigger level ahead: ORH or PDH for CE, ORL or PDL for PE.
- **EARLY**: acceleration index (futures momentum, futures acceleration, RVOL slope, higher lows,
  EMA alignment and slope, VWAP side, mover breadth ≥ +50, realised gamma P&L > 0) at or above the
  threshold, spot at most 0.15 ATR through the level.
- **CONFIRMED**: a 3-minute close through the level (first or second), strong breakout bar (body ≥
  60 %, close in the top 25 %, upper wick ≤ 20 %), breakout-bar futures volume ≥ 1.2× the previous bars,
  futures momentum with the trade, room to the next level ≥ 0.5 ATR, option premium responding
  (response ≥ 0.6 when measurable).
- **RUNNER**: the traded level's retest HELD (RetestTracker), pullback futures volume ≤ the break
  bar's, futures OI not fresh shorts (fresh longs for PE), spot on the trade's side of EMA9.
- **Exits**: early probe not confirmed in 9 min or back through by 0.25 ATR; a 3-minute close back
  through the level by 0.10 ATR; retest FAILED; theta stop (6 minutes after entry the favourable spot
  move is below the contract's 6-minute directional breakeven x, where Δ·x + ½·Γ·x² = |Θ|·t, measured at
  entry — the option has not even paid its own decay); runner trail on the last 3-minute swing
  low (high) or a close through EMA9 with futures acceleration against; no new extreme for 6 minutes
  with acceleration against; fresh opposite futures buildup; flat by 15:15; resting premium stop 30 %.
- **Rejections**: more than the chop limit of VWAP crosses in 30 minutes, EMA9 sloping against,
  breakout into a level closer than 0.5 ATR, breakout wick above 40 %, spread above 1 %, premium not
  responding.
- **Option**: ATM or one strike ITM, whichever has |delta| closest to 0.55 with spread ≤ 1 %.
- **Size**: 25 / 45 / 30 % of 4 lots = 1 / 2 / 1. One campaign per side per expiry day. An entry the
  executor refuses before any position exists (risk limits shared with another strategy, no quote for
  the strike) is not a campaign: the side returns to watching and may try again 3 minutes later. An
  add that is refused leaves the campaign running at the size actually held.

Values given by the design: 25/45/30 sizing, 0.15 ATR, body 60 %, close near high, fresh-long vs
short-covering preference, ATM/1-ITM. Everything else is a placeholder or a rule below.

## Calibration rules (fixed before computing)
Data: features v6 on the calibration-period expiry days in zt-tiger-v2's raw ticks — **15 Sep
(NIFTY) and 17 Sep (SENSEX)** — continuous minutes 09:30–15:15. No trade result is used.

| Value | Rule |
|---|---|
| `gamma.regime_thresholds` (features v6) | 25th / 75th / 95th percentiles of ATM gammaPct (Γ·S/100) on those expiry-day minutes → LOW / NORMAL / HIGH / EXTREME. |
| `compression.range_vs_session_max` | 25th percentile of `rangeVsSession` on those minutes. |
| `compression.ema_gap_atr_max` | 25th percentile of `emaGapAtr` on those minutes. |
| `compression.volume_rate_ratio_max` | Principle: 1.0 (volume not rising). |
| `rejections.vwap_crosses_max` | 75th percentile of `vwapCrosses` (30 min) on those minutes (the choppiest quarter is rejected). |
| `early.acceleration_min` | Placeholder 0.75 (6 of 8 components): no data can calibrate it, because early entries never fired in any earlier backtest. |
| Exit timings (9, 6, 6 min), 0.25 / 0.10 ATR, 30 % stop, 0.5 ATR room, delta 0.55 | Placeholders carried from ecr-v6 where the same concept exists, otherwise stated as placeholders in the file. |

## Evaluation (fixed before computing)
- Behaviour check (descriptive): 15 Sep NIFTY and 17 Sep SENSEX expiries.
- Held-out, once for this family: 22 Sep NIFTY and 24 Sep SENSEX expiries. Caveat: those days were
  looked at under other strategies (the 22 Sep NIFTY put was the biggest winner of ecr-v3), so they
  are not pristine.
- Comparisons on the same days: (a) random ATM buys at the same number of minutes on the same expiry
  days, (b) variant without compression, (c) variant without the retest gate.
- The real test: forward PAPER from 29 Sep (NIFTY) and 1 Oct (SENSEX). No verdict before 20
  episodes; pass = stressed-lane average R > 0 and above the random baseline.

## Results (computed 2026-09-26 on 15 Sep NIFTY and 17 Sep SENSEX expiries, 690 minutes 09:30–15:15)

| Value | Measurement | Set to |
|---|---|---|
| `gamma.regime_thresholds` | ATM gammaPct p25 0.633, p75 0.779, p95 0.818 (min 0.558, max 0.856) | [0.633, 0.779, 0.818] |
| `compression.range_vs_session_max` | rangeVsSession p25 0.655 (median 0.809; n = 600) | 0.66 |
| `compression.ema_gap_atr_max` | emaGapAtr p25 0.300 (median 0.754) | 0.30 |
| `rejections.vwap_crosses_max` | vwapCrosses (30 min) p75 1.0 (median 0) | 1 |

Hand check at 15 Sep 13:00 (NIFTY 23300 ATM, 150 minutes to expiry): Γ = 0.0033 → gammaPct
0.0033 × 23,300 / 100 = 0.77; theta −₹0.16/min against the √T decay estimate V/(2T) = 54/300 = ₹0.18/min;
5-minute breakeven √(2 × 0.16 × 5 / 0.0033) = 22 points. On expiry afternoon a long ATM option needs
about a 22-point move every 5 minutes before gamma pays for theta (ignoring the delta term).

## Correction during implementation (2026-09-26, before any replay)
The first implementation of the theta stop used the gamma-only breakeven √(2·|Θ|·t/Γ) (22 points in
6 minutes on 15 Sep afternoon), which is the delta-hedged (straddle) view. A directional long option
also earns Δ·x, so its breakeven solves Δ·x + ½·Γ·x² = |Θ|·t (about 1.9 points in 6 minutes for
Δ 0.5, Γ 0.0033, Θ −₹0.16/min). The theta stop uses the directional breakeven; the gamma-only
breakeven stays a feature (`gamma.breakeven*`) and an acceleration component (realised gamma P&L > 0).
Found by a unit test, fixed before any trade result existed.

## Premise test (registered 2026-09-26 after the zero-trade replays, before computing)
The strategy traded nothing on the four expiry days, so it says nothing about the design's premise.
The premise itself can be tested on every expiry-day minute: "long gamma pays when realised movement
beats what the option prices". Rule, fixed before computing:
- Expiry index minutes 09:30–14:45 of 15 Sep (NIFTY), 17 Sep (SENSEX), 22 Sep (NIFTY), 24 Sep (SENSEX),
  features v6.
- Condition: realised gamma P&L over the last 5 minutes > 0 for the ATM call (and separately put).
- Trade: buy that ATM option at the ask 250 ms after the minute, sell at the bid 5 / 10 / 15 minutes
  later, raw ticks, one lot, full costs.
- Compare the average net per lot with all minutes (the unconditional baseline) and report the
  difference with a bootstrap 95% interval. Direction is not chosen (both sides reported), because
  the premise is about movement, not direction.

### Premise test results (unchanged output)

```
side   H  n all  mean all  n paying  mean paying    diff     95% CI of diff win% paying
CE     5   1200      -126       123          -65      68 [    -17,     156]         41%
CE    10   1193      -193       121         -201      -9 [   -118,     101]         32%
CE    15   1197      -259       126         -158     113 [    -12,     242]         32%
PE     5   1200        12       119           24      13 [    -93,     115]         48%
PE    10   1193        93       118          223     144 [     -8,     298]         61%
PE    15   1197       175       124          189      15 [   -148,     187]         53%
minutes with gamma paying: CE 10%, PE 10% of 1260/1260 minutes
```

Reading: unconditional ATM buying on these four expiry days lost on calls and made money on puts because three of the four days fell; that is direction, not gamma. In the 10% of minutes where long gamma had been paying, the differences against other minutes are not distinguishable from zero (every 95% interval includes 0; holds overlap, so the intervals are if anything too narrow). The premise is neither shown nor refuted by four days.

## Code review fixes (2026-09-26, after the replays above, before any forward session)
None of these changes a threshold or a rule that produced the results above; all replays had zero
trades, so none of the fixes could have changed a result.
- A refused ENTER used to end the side's campaign for the day (the strategy saw "flat" and took it for
  a stopped-out position). Now only a position the strategy has actually seen open ends the campaign.
- The armed level was kept after ARMED fell back to WATCH, so the side could not re-arm at the other
  trigger level. The level is now held only while armed or in a position.
- `early_distance` duplicated `armed_distance` exactly and was removed (the early entry already
  requires ARMED, which requires `armed_distance`).
- Market state: structure is now the two sides' structure bias (+100 CE only, −100 PE only) and
  continuation is not defined by this design (blank), instead of repeating direction three times.
- `gamma.regime_thresholds` must be three ascending values (checked on load, was an index error per
  snapshot); two versions of one strategy in one session are refused at start (they would share a
  position key); OMS rejections now record the strategy they were refused for.

## All seven expiry days (2026-09-26, code be54f52, runs 15–22)
Each expiry day replayed on the index that expires that day only. 3/8/10 Sep were restored from the
zt-tiger-v2 archive into a scratch database (`zt_archive` in autotrade-postgres; SHA-256 and row
counts matched the archive manifest) and had not been used for this strategy or its calibration.

| Day | Index | ecr-v6 (base / stressed ₹) | egb-v1 (base / stressed ₹) |
| --- | --- | --- | --- |
| 3 Sep | SENSEX | 0 trades | 0 trades |
| 8 Sep | NIFTY | 0 trades | 0 trades |
| 10 Sep | SENSEX | 74800 PE 10:59–11:03 INVALIDATED −659 / −698 | 74800 PE 14:19–14:21 CONFIRMED entry, INVALIDATED −854 / −949 |
| 15 Sep | NIFTY | 0 trades | 0 trades |
| 17 Sep | SENSEX | 0 trades | 0 trades |
| 22 Sep | NIFTY | 23400 PE 10:33–10:37 +259 / +207 | 0 trades |
| 24 Sep | SENSEX | 74200 PE 10:18–10:25 +195 / +329 | 0 trades |
| Total | | 3 trades, 2 wins, −205 / −162 | 1 trade, 0 wins, −854 / −949 |

Four trades over seven days is not a sample; nothing here supports or rejects either strategy.

## egb-v2: structure bias without swing structure (user's request, 2026-09-26; runs 23–26)
v2 removes "higher lows (CE) / lower highs (PE)" from the structure bias; everything else is v1
(swing structure is still one of the eight acceleration components, and is reported as the
`trend_swings` condition). The rule was removed after seeing it block the 22/24 Sep near misses, so
these replays are descriptive.

| Day | Index | egb-v2 (base / stressed ₹) |
| --- | --- | --- |
| 3 Sep | SENSEX | 0 trades |
| 8 Sep | NIFTY | 0 trades (PE armed 11 minutes, no early or confirmed entry) |
| 10 Sep | SENSEX | 74800 PE 14:19–14:21 INVALIDATED −854 / −949 (same as v1) |
| 15 Sep | NIFTY | 0 trades |
| 17 Sep | SENSEX | 0 trades |
| 22 Sep | NIFTY | 23350 PE: CONFIRMED 13:54 (2 lots) → RETEST 13:57 → retest held, RUNNER +1 lot 14:00 → MOMENTUM_FADE 14:07; +685 / +354 |
| 24 Sep | SENSEX | 0 trades (PE armed 3 minutes, acceleration 25–50 %, no confirmation) |
| Total | | 2 trades, 1 win, −169 / −595 |
