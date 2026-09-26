# Calibration of placeholder values for strategy v5 (registered 2026-09-26, before computing)

User instruction (2026-09-26): "choose what is best" for the values the design did not give.

## Method (fixed before looking at any data)

1. Every value taken from the ChatGPT design is kept unchanged.
2. A placeholder is set either by a stated principle or by a percentile of the feature's own
   distribution. No value is chosen by looking at trades, P&L or R.
3. Data: features v3 computed on the 12 sessions before the evaluation window
   (2, 3, 4, 7, 8, 9, 10, 11, 15, 16, 17, 18 Sep 2026; NIFTY and SENSEX; continuous trading unless
   stated). Sessions from 21 Sep on are not used.
   *Amended before computing results:* zt-tiger-v2 keeps full ticks only for 11, 15, 16, 17 and
   18 Sep in that range (2–10 Sep hold 1–35K rows each; their ticks are archived), so sessions with
   fewer than 500K events are excluded. Calibration data = 11, 15, 16, 17, 18 Sep (11 and 16
   partial; 15 Sep NIFTY expiry, 17 Sep SENSEX expiry). CAS scales use the phases in which the
   indicative index moves (market-and-limit, limit-only), not the frozen reference calculation.
4. Dead-rule check: a threshold that the calibration data never (or almost never, < 1% of eligible
   minutes) reaches is reported; for a ChatGPT value it is kept but flagged, for a placeholder the
   percentile rule applies.

## Rules per placeholder

| Placeholder | Rule |
|---|---|
| `level_acceptance.min_travel_atr` | Median travel (ATR3m) beyond ORH/ORL at the first minute with 2 closes beyond: "at least a typical accepted break". |
| `expiry_runner.opposite_wall_score_min` | 75th percentile of the call-barrier / put-support score on expiry-day minutes when that side's OI flow is building. |
| `volatility.iv_trend_threshold` (features) | 70th percentile of \|ATM IV change over 5 min\|: RISING/FALLING = the top 30% of moves. |
| `vol_regime.adjustments` stops | Premium stop scales with volatility: 25% × (median realised vol in the regime ÷ median in NORMAL), rounded to 5%, capped at 50%; kept at the v4 value when the regime has < 60 calibration minutes. |
| `vol_regime.adjustments` sizes | Constant premium at risk: lots × stop% ≤ 4 × 25% (the NORMAL budget), rounded down. |
| `cas_mode.scales.move_atr` | 75th percentile of \|indicative one-minute change\| ÷ ATR1m in CAS minutes: a top-quartile move scores ≈ 0.88. |
| `cas_mode.scales.iep_return_pct` | 75th percentile of \|indicative return vs reference\| in CAS minutes. |
| `confirmation.breakout_candle.volume_ratio_min` | Principle: 1.2, the design's own ratio for the same bar's range; dead-rule check only. |
| `order_book.imbalance_min` 0.15 (design value) | Kept; dead-rule check on the futures book (live only) and the ATM call-minus-put edge. |
| `order_book.confirm_points` | Principle: smaller than the smallest step between the design's confirm levels (62 → 66 = 4), so the book alone never moves a setup across a window: 3. |
| `vol_regime.adjustments` confirm_add | Principle: one regime step above NORMAL = +5 (the design's steps are 4–8); EXTREME +10. |
| `vol_regime.realized_escalation_pct` | Principle: the design's EXTREME boundary (90). |
| CAS coverage 60%, futures alignment 0.5, expiry band +5 / hold 60, imbalance-change scale | Principle only (no per-stock auction data exists to calibrate): majority of index weight; futures match at least half the move; half a design band step (10) stricter on expiry. |
| Feature windows (ladder 15, new high 15, volume baseline 10, straddle 10/3, realised 30, min history 5) | Principle: the design's own windows (15-minute opening range, 10/3-minute OI windows, 10 three-minute bars, one trading week). |
| Earlier v2 placeholders (entry 09:30, flat 15:15, 75/66 confirm, wick 20%, premium response 0.6, exits, 25% stop) | Unchanged: structural (opening range, CAS start) or bracketed by the design's own numbers. |
| Risk account limits | Unchanged: account choices, not strategy parameters. |

## Results (computed 2026-09-26 on 11, 15, 16, 17, 18 Sep; 3,590 continuous minutes)

| Placeholder | Measurement | Was (v4) | Now (v5) |
|---|---|---|---|
| `min_travel_atr` | median travel at the 2nd close beyond: 0.82 ATR (n = 10 breaks; p25 0.63, p75 0.92) | 0.5 | **0.8** |
| `opposite_wall_score_min` | 75th percentile of expiry-day barrier scores while OI builds: 69.6 (n = 545) | 60 | **70** |
| `iv_trend_threshold` (features v4) | 70th percentile of \|ΔIV 5 m\|: 0.0014 (n = 3,518) | 0.002 | **0.0014** |
| HIGH stop / size | median realised vol 0.089 vs NORMAL 0.055 (1.63×; 851 min) → 40%; 2 lots | 35%, 0.5 | **40%**, 0.5 |
| EXTREME stop / size | median realised vol 0.082 (1.49×; 543 min) → 35%; 2 lots | 40%, 0.5 | **35%**, 0.5 |
| LOW | median realised vol 0.050 (0.92×; 324 min) → 25%; 4 lots | 25%, 1.0 | unchanged |
| CAS `move_atr`, `iep_return_pct` | 75th percentile of the indicative one-minute move = 0: the recorded indicative index is **frozen** from 15:15 to about 15:29 | 0.5, 0.20 | unchanged (not calibratable) |
| breakout `volume_ratio_min` 1.2 | met in 25% of minutes | — | kept (not dead) |
| `order_book.imbalance_min` 0.15 | call-minus-put edge ≥ 0.15 in 35% of minutes; futures book: no data in zt | — | kept (not dead) |

Two consequences recorded as they were found:

- EXTREME comes out with a narrower stop than HIGH. In these sessions EXTREME minutes came from the
  IV/VIX percentile, not from larger realised moves, and the registered rule is applied as written.
- Because the recorded indicative index is frozen, v5 counts the index-level IEP components (and
  futures/CAS alignment) only while the indicative value has moved in the last 180 s
  (`cas_mode.index_fallback_needs_moving_indicative`). Without per-stock auction data and with a
  frozen index, the CAS score rests on futures direction/acceleration and option/IV response, and
  no CAS entry can be taken (futures agreement cannot be measured).

Files: `early-confirm-runner.v5.yaml` `sha256:b6e6dfd9b6e3…`, `features.v4.yaml` `sha256:234935418e5c…`,
`paper-risk.v3.yaml` `sha256:4c721da1bc53…`. No trade result was looked at to choose any value.
