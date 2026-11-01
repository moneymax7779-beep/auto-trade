# NIFTY expiry short straddle as a PAPER shadow — build plan (1 Nov 2026)

Status: **documented, not built** (user, 1 Nov: "don't build now just document it"). PAPER only; nothing reaches a broker
or a follower account. The broader buy/sell design (books, overnight positions, margin, separate account) is
[2026-10-24-buy-sell-design.md](2026-10-24-buy-sell-design.md); this plan is the smallest slice of it that runs one
intraday short straddle on expiry day.

## 1. Why this strategy

Buying the expiry-afternoon straddle failed every test (A-285, A-286, A-287, A-289, A-290, A-291): on expiry afternoons
the options were priced above the movement that followed (tick days: afternoon realized vol below the 12:30 IV on 14 of 15
expiry index-days). A-292 tested the other side.

**Rule (A-292, chosen on Oct 2024 - Sep 2025 out of 30 variants, tested once on Sep 2025 - Sep 2026):**

| Step | Rule |
|---|---|
| Day | NIFTY's own expiry day only |
| Entry | 12:00 IST: sell the ATM call and put (strike = spot rounded to 50), 1 lot each |
| Credit | the two sale prices (≈ 87 points ≈ ₹5,650 a lot on the test year) |
| Stop | buy both back when their combined ask ≥ 1.5 × the credit |
| Break-even exit | buy both back when a one-minute index close is beyond strike ± credit |
| Otherwise | hold; both legs settle at intrinsic value at the close (15:30) |

**Evidence (₹ per lot of 65):**

| | Train Oct 2024 – Sep 2025 | Test Sep 2025 – Sep 2026 | Tick days (bids / asks) |
|---|---|---|---|
| Trades | 50 | 50 | 5 |
| Net | +₹1,826 (PF 1.03) | +₹15,850 (PF 1.33) | −₹173 |
| Won | 22 (44 %) | 23 (46 %) | 2 |
| Avg win / avg loss | +₹2,714 / −₹2,067 | +₹2,750 / −₹1,755 | |
| Worst trade | −₹7,877 | −₹4,125 | −₹2,664 |
| Max drawdown | −₹18,945 | −₹11,933 | |

Settled days won 21 of 21 (+₹58,367); break-even exits lost 26 of 28 (−₹38,887). Mean +₹177 a trade over 100 expiries
with a ₹2,800 standard deviation: **not statistically significant**. The iron-fly version (A-293) did not pass. The point
of the shadow is a forward record on expiries nobody has seen, not deployment.

Code that produced these numbers: `.local/a292/sell.py`, `.local/a292/stats.py`, `.local/a292/ticks.py`,
`.local/a292/ironfly.py`; ledger rows A-292 and A-293.

## 2. What the engine assumes today

Every position is long: the OMS buys to open and sells to close, P&L is (sell − buy) × quantity, marks are at the bid, the
capital check counts premium paid, and the straddle bracket fires when the combined bid rises (target) or falls (stop). The
paper broker already accepts a SELL with no holding (its holding goes negative), but nothing in the OMS sends one.

## 3. Changes, by module

### 3.1 `strategy-api`
- `OrderIntent.Action.SELL_STRADDLE`: strike offset (0 = ATM), lots, stop percent (combined value **above** the credit);
  factory `OrderIntent.shortStraddle(lots, stopPct, stage, reason)`.
- `PositionView`: a `short` flag; `PositionView.Leg` gains `ask` (a short is valued and bought back at the ask).
  `heldValueRatio` for a short = combined ask ÷ credit.

### 3.2 `oms` (highest risk: the live set runs on this code)
- `ManagedPosition`: `Direction { LONG, SHORT }`, final, default LONG; quantity stays positive. For SHORT:
  `averageCost` = average sale price; `unrealised` = (averageCost − lastAsk) × quantity; `boughtQuantity` keeps meaning
  "quantity that opened the position" (it drives `closedToday` and ENTRY_NOT_FILLED).
- `OrderManager`:
  - `enterShortStraddle(strategy, underlying, call, put, lots, stage, market, stopPct)`: margin check (§3.4), the
    existing `riskCheck`, two SHORT legs, `sell(leg, lots, stage)`.
  - `sell(...)`: mirror of `buy(...)`: SELL LIMIT at bid − entry buffer, whole lots, freeze-quantity slices; no
    participation cap or depth slicing at 1 lot.
  - Fill accounting in `accept(...)`: an ENTRY fill of a SHORT is a sale (sets `averageCost`); an EXIT fill is a buy-back
    (`realised += (averageCost − price) × filled`). Costs from `CostModel` with the matching side.
  - Exits: `buyAll(...)` at ask + exit buffer; `chaseExit` and the final-discount chase mirrored to the ask side;
    `chaseLegEntry` mirrored to the bid side.
  - `checkBrackets`: for SHORT legs, stop when combined ask ≥ (1 + stop) × credit; no target for this strategy.
  - `settleAtExpiry`: a SHORT leg is bought back at intrinsic value.
  - `reconcile`: expected broker quantity is −quantity for SHORT legs.
  - The long path must not change: every new branch keys on `direction == SHORT`.
- `view(...)`: the `short` flag, legs with their asks.

### 3.3 `broker-paper`
- `PaperBroker.settle`: for a negative holding, fill working BUY orders up to it at the settlement price (today it only
  fills SELLs for positive holdings).
- `book(...)` already handles a negative holding; add a test that a SELL-to-open, then BUY-to-close, round-trips to 0.

### 3.4 `risk`
- New risk file version (next `paper-risk.vN`): `short_margin_per_lot` (start at ₹1,20,000 per NIFTY straddle lot on
  expiry day, an estimate to be replaced with the broker's figure) and `max_short_lots` (1).
- The capital check for a short uses margin, not premium. The kill switch and daily loss limit are unchanged (they
  read `dayPnl()`, which already sums `net()`).

### 3.5 `app-trading-core`
- `TradingSession`: `case SELL_STRADDLE`: ATM call and put via `ContractResolver`, `oms.enterShortStraddle(...)`, log
  and candidate record as for `ENTER_STRADDLE`.
- **Never routed to real money**: a SELL_STRADDLE from any strategy is refused when the session is a signal source or
  has followers (`SignalPublisher` / execution worker) until the user decides otherwise.

### 3.6 Persistence, API, UI
- Flyway migration (next free number on main at build time; GCP is at V27): `trade.position.direction text not null
  default 'LONG'`. `PositionStore` writes and reads it.
- API: positions and trades carry `direction`.
- UI (`TradesTable.tsx`, `LivePositions.tsx`, replay pages): show SELL / BUY, entry price labelled credit for SELL, and
  the buy-back price as exit. P&L is already the stored `net`.

### 3.7 Strategy: `strategy-egb` `ExpiryShortStraddle`, config `expiry-short-straddle.v1.yaml` (ess-v1)
- 12:00 on NIFTY's own expiry day: `SELL_STRADDLE`, 1 lot, stop 50 %.
- Each minute: if the last one-minute index close is beyond strike ± credit (from `PositionView.legs`), exit
  (`BREAK_EVEN`).
- No flat-by: legs settle at the close (§3.2). zt-tiger-v2 replays end at 15:15, so on those days the legs are marked at
  the ask, not settled; say so in every report.
- `requiredFeatureSections()` empty (it reads spot only).

## 4. Tests and checks before any deploy

1. Unit: SHORT fill accounting, buy-back P&L, short bracket stop, short settlement, reconcile with negative holdings,
   margin refusal, paper round trip, the strategy's entry / break-even / no-trade-off-expiry cases.
2. **Long-path regression, to the rupee:** els-v10 / v12 / v14 / v16 / v17 / v18 on the 5 own-capture NIFTY expiries
   (cas8 results), and the live set (ecr-v11, etr-v1, mac-v1, odm-v4, obc-v13) on all tick days, identical trades and
   nets before and after.
3. Tick-day replays of ess-v1 (29 Sep, 6, 13, 19, 27 Oct, own capture): compare with A-292's quote-based numbers
   (+86.1 / +19.0 / −29.1 / −41.0 / −37.7 points); differences come only from fills, costs and buffers and are listed
   trade by trade (lots, quantity, credit, buy-back, exit reason).
4. The replays appear in the app's replay history (like the els runs on 1 Nov).

## 5. Shadow rollout

1. Build on a branch from main; the research branch `research/a283-expiry-atm` (els, settlement, held-strike theta,
   feature fail-fast, `Contract.strike`) is merged first or cherry-picked; its engine changes need the same long-path
   regression.
2. Merge to main only after §4 passes and the user agrees; deploy with `.local/deploy-exec.sh` outside 08:50–15:50 IST
   (the VMs are stopped until the morning of Mon 2 Nov; start zt-v2 containers by hand).
3. Add `config/strategy/expiry-short-straddle.v1.yaml` to `shadow-strategy-files` (the 22nd shadow, SHADOW-1 account).
   First possible session: the next NIFTY expiry after deploy (Tue 3 Nov if deployed Mon 2 Nov after the close).
4. Nightly sync brings the shadow sessions to the Mac; a ledger row (next A-number) registers the forward read before the
   first session: read once at 20 NIFTY expiries (about mid-March 2027), descriptive at 10. Forward PASS: net > 0, PF ≥
   1.3, worst trade ≥ −1.5 × credit, max drawdown ≤ 4 × the average credit.

## 6. Open decisions for the user

| Decision | Default in this plan |
|---|---|
| Lots | 1 |
| Margin per lot | ₹1,20,000 estimate until replaced with the broker's figure |
| Stop slippage | exit buffer as risk v17 for buys, mirrored |
| Also shadow the 14:00 entry (A-292's top train variant, both halves not positive) | no |
| Read date | 20 expiries (~mid-March 2027) |

## 7. Estimate and risk

About 2 working days: OMS and paper broker 1 day with tests, strategy + persistence + UI half a day, regression replays and
review half a day. The risk is in §3.2: the OMS serves the live PAPER set; a mistake in a shared code path changes live
trades. Every change is gated on `direction == SHORT`, and §4.2 must reproduce the long path to the rupee before merge.
