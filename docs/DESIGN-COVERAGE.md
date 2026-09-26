# Design coverage: ChatGPT design → auto-trade

Every element of the design conversation (2026-09-25) and where it lives. Feature definitions are
in `config/features/features.v3.yaml` (v4: calibrated IV trend), strategy rules in `config/strategy/early-confirm-runner.v4.yaml` (v5: calibrated placeholders, see `docs/CALIBRATION-v5.md`),
risk in `config/risk/paper-risk.v3.yaml`. Thresholds are the ChatGPT values; anything the design
did not give a number for is marked `placeholder` in those files. Nothing is calibrated.

Data column: **replay** = computable from zt-tiger-v2 recordings (and replays of them);
**live** = needs the Upstox feed; **VIX** = Upstox public candles (`bin/autotrade vix-backfill`) and
live polling.

## Lifecycle and scores

| Design element | Implementation | Data |
|---|---|---|
| WATCH → ARMED → EARLY_ENTRY → CONFIRMED → RUNNER → EXIT | `EarlyConfirmRunnerStrategy` stages | replay |
| Early CE AND list (distance < 0.15 ATR / 0.10 expiry, > VWAP, EMA9 > EMA20, EMA9 rising, swing low intact, higher lows, futures momentum and acceleration, RVOL slope, breadth > +50) | `conditions()`, first ten conditions | replay |
| Probe 30/40/30 (conservative 25/50/25), expiry early 20–30% | `sizing`, `EcrConfig.tranches` | — |
| EARLY / CONFIRM / RUNNER scores; runner weights 25/25/20/15/10/5, runner ≥ 70 | `Scorer` | replay |
| Direction / participation / structure / continuation states | `MarketState` | replay |
| Required confirm score by time of day (75/62/70/62/66) | `required_confirm_score_by_window` | — |

## Structure and levels

| Design element | Implementation | Data |
|---|---|---|
| ORH/ORL, PDH/PDL, previous close, day open, VWAP, EMA9/20, session mean, swings | `StructureState` | replay |
| Level acceptance: closes beyond, retest | `LevelAcceptance`, `StructureFeatures.orh*` | replay |
| Acceptance: time above level, distance travelled after break, volume after break | `LevelFeatures.*MinutesBeyond / *TravelAtr / *VolumeAfterBreak` (v3); runner structure uses travel ≥ 0.5 ATR and volume ≥ 1.0× | replay |
| Level ladder, progressive reclaims | `LevelFeatures.ladder*` (1-minute crossings over 15 min); structure sub-score | replay |
| Breakout quality: body > 60%, close top 25%, small upper wick, range > 1.2× average, volume ratio | `breakout_bar` + v4 `breakout_volume` (futures volume of the bar vs previous bars ≥ 1.2) | replay |

## Futures and participation

| Design element | Implementation | Data |
|---|---|---|
| FUT_MOM 30 s / 1 m / 3 m, normalised by ATR, acceleration | `FuturesState` | replay |
| Basis and Δbasis | `FuturesFeatures.basis*` | replay |
| Price/OI state; state score +2 … −2 | `OiState`; v4 `runner.futures_state_score` drives the sub-score | replay |
| Time-of-day RVOL, bands 1.0/1.25/1.5/2.0, RVOL slope | `FuturesState.rvol*` | replay |
| Weighted constituent breadth −100…+100, concentration | `BreadthState` | replay |
| Order-book imbalance (bid − ask)/(bid + ask), persistence 10–20 s, small confirmation only | `BookFeatures` (v3): futures book (**live**: Upstox book totals), ATM call/put books (replay: total quantities); v4 ±3 confirm points when held over 20 s ≥ 0.15. Options compared call-minus-put because NSE option books are bid-biased | replay + live |

## Options

| Design element | Implementation | Data |
|---|---|---|
| Near-ATM OI Δ 1/3/5/10 m, OI velocity (accelerating/fading) | `OptionChainState.flow` | replay |
| Call barrier / put floor scores, wall weakening | `OptionChainState.barrier/weakening` | replay |
| Premium momentum, higher highs, option volume expansion, spread | `PremiumFeatures` (v3); v4 `iv_options` sub-score | replay |
| ATM straddle; "straddle stops falling" | `straddleChangeLookback/Recent`, `straddleCompressionEnded` (v3) | replay |
| ATM CE/PE IV, 1 ITM / 1 OTM IV, ΔIV 1/3/5 m, skew | `OptionsFeatures`, `PremiumFeatures.itm*/otm*` | replay |
| IV percentile | `VolatilityFeatures.atmIvPercentile`: same minute of earlier sessions from zt `option_tick_snapshots`, expiry days compared only with expiry days | replay |
| Premium response ratio (IV-crush detector); runner requires ≥ 0.6 | `premiumResponse*`; v4 runner gate `premium_response_ok` | replay |
| IV trend while underlying moves | `ivTrend` RISING/FLAT/FALLING; v4 scores FALLING as 0 | replay |

## Regimes

| Design element | Implementation | Data |
|---|---|---|
| DTE regimes NORMAL / NEAR / EXPIRY, weights per regime | `regime()`, `regime.weights` | replay |
| Minutes to expiry, weekend/holiday gap ahead | `RegimeFeatures.minutesToExpiryClose`, `nextSessionGapDays` | — |
| Expected daily and remaining move; room to the next level vs expected move | `RegimeFeatures.expectedMove*`, `LevelFeatures.expectedReach*`; v4 runner component | replay |
| India VIX level, Δ 5 m / 15 m / day, percentile 60 d / 252 d | `VolatilityFeatures.vix*` (v3) from `ref.index_candle` + live ticks/polling | VIX |
| Realised volatility, ATR percentile | `realizedVol`, `realizedVolPercentile`, `atrPercentile` (same minute of earlier sessions) | replay |
| Volatility regime LOW/NORMAL/HIGH/EXTREME (< 20 / 20–70 / 70–90 / > 90) | `VolatilityRegime`: VIX leads NIFTY, IV leads SENSEX/BANKNIFTY, realised-vol escalation | replay + VIX |
| Regime changes size, confirmation, stop distance | v4 `vol_regime.adjustments` (HIGH: half size, +5 confirm, 35% stop; EXTREME: +10, 40%, no probe) → `OrderIntent.premiumStopPct`, OMS per-position stop | — |
| Expiry runner: new highs, futures momentum, level holding, EMA9, RVOL elevated, no opposite wall, premium efficient | stall exit, trail, invalidation, v4 `expiry_runner` gates and exits (`OPPOSITE_WALL`, `PREMIUM_LAGGING`, `EXPIRY_MOMENTUM_LOST`) | replay |

## Closing auction (CAS)

| Design element | Implementation | Data |
|---|---|---|
| Separate regime from 15:15; CAS timings configuration-driven | exchange file `cas.*`, `SessionPhase` CAS phases | — |
| CAS data excluded from normal statistics | structure/ATR/EMA use continuous trading only; percentiles stop at 15:15 | replay |
| Reference = 15:00–15:15 VWAP | stocks: Upstox `rp`; index: mean spot 15:00–15:15 (`CasFeatures.referenceIndex`) | replay + live |
| Indicative index, IEP velocity 20 s / 1 m / 3 m, acceleration | `CasFeatures.indicative*` | replay |
| Futures/CAS basis, basis change, divergence | `futuresVsIndicative`, `casBasisChange1m`, `futuresFollowRatio` | replay |
| CAS_ORH_CROSS distinct from breakout | `orhCross/orlCross`; the breakout logic does not run in CAS | replay |
| Constituent IEP return × weight (CAS pressure) | `weightedIepReturnPct` | **live** |
| Normalised imbalance, weighted, imbalance velocity | `weightedImbalance`, `weightedImbalanceChange` | **live** |
| CAS breadth, top-3 concentration | `casBreadthPct`, `topConcentration` | **live** |
| Liquidity confidence (tradable quantity vs history) | `auctionTurnoverCr` (absolute). A percentile needs recorded auction history, which is not captured yet | **live** (partial) |
| CAS score weights 15/10/15/15/5/10/10/10/5/5, bands 55/65/75/85 | `CasScorer` (renormalises without per-stock data) | replay + live |
| CAS_RUNNER: hold while CAS agrees, tighten/exit when it disagrees | v4 `cas_mode`: a RUNNER at 15:15 is held while the CAS score ≥ 55, exits `CAS_DISAGREES` below, `CAS_END` at 15:35 | replay |
| CAS entries by band | v4 entry window 15:20–15:30, bands 65/75/85, one per side, only with per-stock data and futures agreeing | **live** |
| CAS_EXPIRY_MODE | v4 `cas_mode.expiry`: bands +5, hold threshold 60, smaller size | — |
| Square-off before the 15:40 derivatives close | risk v3 `square_off_at: 15:36`, CAS entry window exception | — |

## Known limits

- Per-stock auction data exists only on the live Upstox feed; zt-tiger-v2 records constituent prices
  that hold until the auction close. CAS entries therefore cannot be replayed from past data, and
  CAS runner management in replays uses the index-level score.
- The futures order book is only available on the Upstox feed.
- CAS liquidity against history and auction-data replays need the live feed captured into own tables
  (the plan's "capture" gap).
- IV percentile history is as deep as zt-tiger-v2's `option_tick_snapshots` (18 sessions on
  2026-09-26); expiry-day percentiles stay null until 5 earlier expiry days exist.
