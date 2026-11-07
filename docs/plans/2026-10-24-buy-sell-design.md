# Buying and selling strategies on one platform — design plan (24 Oct 2026)

Trigger: A-196 (the user's weekly NIFTY short strangle with adjustments, 650 qty) made +₹5.50 L (Oct 2024–Sep 2025) and
+₹9.96 L (Oct 2025–Sep 2026) on 1-minute bars, but failed its registered test (validation CI of the mean cycle P&L
[−₹4,577, +₹40,515]) with drawdowns of ₹4.4–5.4 L and single weeks of −₹3.9 L. The user proposed ₹20 L of capital for it
and a design that supports both buying and selling strategies. PAPER only throughout; nothing live without the user's
explicit decision.

## What the engine assumes today

| Area | Today | Selling needs |
|---|---|---|
| Orders (`strategy-api` `OrderIntent`) | ENTER = buy, EXIT = sell; one option side per strategy per index; `ENTER_STRADDLE` = two buys | BUY or SELL per leg, several legs at once |
| Positions (`oms` `ManagedPosition`) | one contract, long; premium stop "% below cost" | signed quantity; stops either way; a book of legs managed together |
| Holding | intraday: one `TradingSession` per day, `flat_by` 15:15, `EquityLedger` counts closed sessions | positions carried overnight to expiry, reconciled each morning, settled at expiry |
| Capital (`BudgetSizing`) | premium paid | margin (SPAN + exposure; extra on expiry day) |
| Risk (`risk` `RiskEngine`, `KillSwitch`) | daily loss limit on one shared account | live MTM of open shorts, max loss per book, overnight gap stress |
| Strike choice (`ContractResolver`) | ATM ± N strikes | by premium (closest to ₹30, 87.5 % of the other leg) or delta, from the chain |
| Spot after 15:15 | index feed (frozen 15:15–15:28) | synthetic spot (strike + call − put) |

## Design

1. **Separate accounts.** Buyers keep their account (₹5–6.6 L: ecr, etr, mac, odm). A new **PAPER-SELL account (₹20 L)**
   with its own capital, kill switch and equity ledger. Shared capital already cost money (A-145 crowding, A-184 kill
   switch). Build on the multi-account work of `feature/live-execution` (accounts, per-account kill switches, allocator).
2. **Domain model.** `LegIntent(BUY | SELL, side, ContractSelector, qty)` and `BookIntent` (open, adjust a leg, close all).
   Selectors: `AtmOffset` (today's behaviour), `PremiumNearest(₹)`, `PremiumRatio(other leg, k)`, `Delta(d)`. A book =
   signed legs with combined P&L and stop rules. Current strategies adapt with no behaviour change, proven by
   byte-identical trades on the 27-tick-day live-set replay before and after.
3. **Multi-day positions.** Open books persisted (new Flyway migration), reloaded and reconciled with the broker before
   09:15, expiring legs settled at the official close (synthetic/close price in paper). Intraday strategies keep
   `flat_by`; only book strategies may carry.
4. **Margin and risk.** Paper margin model (share of notional, calibrated once against a broker margin calculator; the
   broker margin API before any live use). Per-book max loss, max quantity, overnight gap stress (index ±3 % open must
   stay under a set share of capital). Lots fixed by the strategy (no new sizing schemes, per the user). A seller's kill
   switch blocks new entries/adjustments but never leaves a short unmanaged.
5. **Honest paper fills.** Sell at the bid, buy at the ask, walk the visible depth for size (extend risk v12's slicing
   to sells).
6. **First selling strategy `wss-v1`** = A-196's rules on the framework, shadow first on PAPER-SELL, judged on the
   forward record.

## Phases

| Phase | Work | Gate |
|---|---|---|
| **S0** | Forward-track A-196 without the engine: a daily job runs the frozen rules on that day's data and updates a ₹20 L paper equity curve; check fills against real bid/ask on the own-capture cycles | started 24 Oct 2026 |
| S1 | Domain model (legs, books, signed positions, selectors); buyers unchanged (identical replay) | after S0 runs |
| S2 | Multi-day positions, reconciliation, expiry settlement | S1 |
| S3 | Margin model, book risk, gap stress, PAPER-SELL account | S2 |
| S4 | `wss-v1` in shadow; engine replays on 2024–26 bars reproduce A-196 | S3 |
| S5 | Live only on the user's explicit decision; live branch needs SELL orders + margin checks | user |

## Risk notes

- ₹20 L is fine as a paper account. In real money A-196's history means ₹4.4–5.4 L drawdowns (22–27 %) and −₹3.9 L
  weeks; 1-minute fills likely understate the gap days.
- A hedged variant (long far wings) caps the worst case and margin — a separate registered test, not a change to A-196.
