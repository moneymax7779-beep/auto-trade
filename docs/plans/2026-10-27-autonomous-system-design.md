# Autonomous trading system — design (27 Oct 2026)

User, 27 Oct 2026, after the NIFTY expiry session (PAPER-1 −₹1,39,083, kill switch at 14:18): "i want more intelligent
self reliant autonomous system not just works on the math or some strategies, i dont want quick fixes", then "yes write
the design document".

This document is the design. Nothing in it is built yet. Each phase is registered in `EXPERIMENT-LEDGER.md` before any
code or test, and each needs the user's go-ahead. PAPER only; LIVE money only on the user's explicit request
(`CLAUDE.md`).

It builds on, and partly replaces, three earlier plans:

| Plan | What happened to it |
|---|---|
| `docs/INTELLIGENT-TRADING-PLAN.md` (13 Oct): regime engine, opportunity scorer, conductor | Phases 2–5 were never built. Work went to new rules instead |
| `docs/plans/2026-10-22-trading-plan.md`: WS1 risk construction, WS2 entry timing, WS4 new edge sources | WS1 withdrawn before any test (A-166, user: "risk construction is not a solution"); WS2.1 compression veto FAIL (A-167); WS4.2 selling became the buy/sell design |
| `docs/plans/2026-10-24-buy-sell-design.md`: selling on the same platform | S0 forward tracker running (A-197); S1–S5 not started |

Since 22 Oct the ledger grew from A-165 to A-245: about 80 experiments, most of them single rules built the same night
from a chart, a pasted playbook or a course, and nearly all FAIL or INSUFFICIENT. **The process, not a missing rule, is
what this design changes.**

---

## 1. Evidence base

Every number below is from our own data unless marked [lit].

### 1.1 The live record

| Measure | Value |
|---|---|
| PAPER-1 from ₹5 L on 7 Oct (equity sizing, risk v17) to 27 Oct | 14 sessions, 6 winning / 7 losing / 1 flat, **−₹2,80,803 → equity ₹2,19,197 (−56 %)** |
| Of which since 22 Oct | −₹4,42,657 in 4 sessions |
| Two best days / two worst days | +₹5.35 L (15 Oct), +₹4.37 L (8 Oct) / −₹5.17 L (16 Oct), −₹3.09 L (14 Oct) |
| Current four-strategy set (ecr-v11, etr-v1, mac-v1, odm-v4), from 26 Oct | −₹1,38,101, −₹1,39,083 |
| ₹10 Cr target from ₹2.19 L, ~44 sessions left | ≈ 14.9 % compounded every session |

### 1.2 Replays versus reality

| Sample | Live set result | What it is |
|---|---|---|
| 28 tick days, 2 Sep–26 Oct (A-245 control) | +₹37.07 L | **In sample**: the strategies were chosen and shaped on these days |
| The same replay on the 5 forward days 19–26 Oct | −₹1,88,173 | Live lost too (live sizing and set differed slightly: 19 Oct had zbf) |
| 416 bar days, 2025–2026 (A-199) | +₹2.45 L, PF 1.02; 2025 −₹4.25 L | Close to break-even out of sample |
| Oct 2024–Dec 2025, 306 unseen days (A-168) | set PF 0.97 | |

Replay equals live when the inputs match (12 Oct shadow matched GCP to the rupee). **The gap is selection, not
execution.**

### 1.3 Evidence per live strategy

| Strategy | Out-of-sample evidence | Reading |
|---|---|---|
| ecr-v11 (expiry days) | 93 bar trades PF 1.87, positive in both years (A-199); 62 trades PF 1.78 on unseen days (A-168) | The only strategy with a durable record. Few trades |
| etr-v1 (expiry trend rider) | Inert before Aug 2026 on bars (no breadth history). With the backfilled weights: FAIL, second half −₹3.1 L, profit from 3 days (A-231) | Unproven; adds into reversals (22 Oct forensics; 27 Oct twice) |
| odm-v4 (opening drive) | PF 0.99 with backfilled breadth (A-231) | Unproven |
| mac-v1 (EMA cross) | PF 1.05 on 1,095 bar trades; 2025 −₹7.99 L; PF 0.92 on unseen days (A-168) | Break-even; forward read at 25 sessions (A-225) |

### 1.4 What is and is not predictable

| Question | Evidence | Conclusion |
|---|---|---|
| Direction of the next 30–60 minutes, or the rest of the day | Hit rates 47–57 % from price, flow, breadth, VIX, OI walls, writer flow, build-up, gamma regime, order flow, participant OI (A-068, A-094, A-163, A-164, A-165, A-232, A-233, A-236; day-anatomy study) | **Not predictable** with our inputs |
| How much the index will move | Impulses 0.7 a day with VIX < 12 vs 5.1 with VIX ≥ 17.5; wide recent range → impulse 1.6–1.9 × more likely; compressed → 0.45 × (day-anatomy, 396 index-days); [lit] realised volatility clusters | **Partly predictable**; not yet tested as a forecast |
| Whether an entry is a winner, at the entry minute | Every entry feature separates winners from losers at AUC 0.39–0.63 (loss forensics, 22 Oct) | Winners show themselves only after entry (first-30-minute MFE) |
| Where the money is in a move | The right ATM option makes +36 % in the 15 minutes after an impulse's onset, +0.3 % if bought at "confirmation" (day-anatomy) | Our breakout / cross / burst entries are confirmation entries |
| Who earns in index options | [lit] SEBI FY22–FY24: about 9 in 10 individual F&O traders lost money; profits to prop/algo desks and option sellers. Intraday short-option returns ≈ 0, the variance premium is earned overnight | A buyer-only system needs timing it does not have |

### 1.5 The research process

About 240 ledger rows: roughly 95 FAIL, 15 INSUFFICIENT, about 20 PASS (most thin, in sample or on 2–3 cases), the
rest operational or descriptive. With this many tests, several passes are expected by chance alone; none of the passes
has been corrected for that. Tick days have been reused for dozens of hypotheses; October has been fitted many times.

---

## 2. What "intelligent and autonomous" means here

Intelligence cannot create an advantage the market does not offer. It can:

1. **know what kind of market it is in**, with calibrated uncertainty, and say "I don't know";
2. **choose the kind of position** that the state favours — buy movement, sell movement, or stand aside — rather than
   guess direction;
3. **allocate capital by evidence**, so that strategies earn, lose and regain capital automatically from forward
   results;
4. **control risk across the whole book** rather than per strategy;
5. **learn every night**: post-mortem, reconcile, generate and test hypotheses under strict statistical rules;
6. **report on itself honestly**: its forecast calibration, its live-versus-replay gap, its research hit rate.

Autonomy covers the research and decision loop. Capital and LIVE stay with the user (section 9).

## 3. Design principles

1. **Forward evidence decides.** In-sample replay totals never allocate capital.
2. **Forecast what is forecastable.** Volatility, range and activity first; direction only if a test ever earns it.
3. **Position type follows the state model.** The same signal means different trades in different states.
4. **Risk is a portfolio property.** Exposure is summed across strategies and indices; size falls after losses.
5. **Every decision is reproducible.** The order path is deterministic Java fed by the same snapshots live and in
   replay. No language model in the order path.
6. **Every claim is checked against data.** Including chart readings by people or models (two pasted analyses on 27 Oct
   misread the chart: a −₹71 K put became a "+₹71 K reversal"; added call OI became "short covering").
7. **Multiple-testing aware.** Each hypothesis family has a test budget; passes are corrected for the number of tests.
8. **Held-out data is spent once.** The system tracks which days and months each family has used.
9. **Abstain when uncertain.** Low confidence means smaller size or no trade, and that is counted as a decision.
10. **Versioned and audited.** Thresholds in new `vN` files; every automatic decision logged with its reason.

## 4. Architecture

```
 market data: Upstox feed (live) · own capture md.* · zt-tiger-v2 (read-only) · hist.candle bars 2024–26 · VIX · GIFT ·
 participant OI · index weights · event calendar (new)
                                   |
                     feature engine (existing, features v12/v15)
                                   |
        +--------------------------+---------------------------+
        |                          |                           |
 (A) market-state model     (B) proposers                (C) implied-move reader
  expected range / impulse   existing strategies emit     ATM straddle → market's
  odds / day type, with      candidates only, with        expected move; IV, skew
  confidence                 their own stop and plan
        |                          |                           |
        +-------------> (D) allocator and decision layer <-----+
                         - is the position type right for the state? (buy movement / sell movement / none)
                         - capital per proposer from forward evidence (E)
                         - one position manager per index; adds only from open profit
                                   |
                         (F) risk core: per-trade risk at the stop, book exposure, drawdown throttle,
                             kill switch as backstop
                                   |
                         OMS + paper broker / Kite adapter (existing; selling legs from the buy/sell design)
                                   |
 (E) evidence store: every forward trade in R, per proposer and state; edge estimates; demotion tests
                                   |
 (G) nightly research loop: data QA → post-mortem → live/replay reconciliation → hypotheses → registered tests →
     weekly report                      ← (H) language model assists here only, every claim verified
```

### (A) Market-state model

Answers, per index at fixed decision times (09:20, 09:45, 10:15, 11:00, 12:00, 13:00, 14:00) and later every minute:

| Output | Definition |
|---|---|
| Expected rest-of-day range | Quantiles (25/50/75 %) of the index high−low from now to 15:15, in % of spot |
| Expected next-60-minute range | Same, for 60 minutes |
| Impulse odds | P(a ≥ 0.35 % move within 30 minutes), the day-anatomy definition |
| Day type (descriptive) | Trend / range / reversal probabilities, for reporting first |
| Confidence | From the model's calibration on unseen months; low → the decision layer sizes down |

Inputs, all point-in-time: VIX level and change; realised ranges of the last 1, 5 and 22 sessions; overnight gap and
GIFT Nifty; range and futures volume since the open; time of day; DTE and expiry flags; participant OI; breadth
dispersion; the ATM straddle's implied move; an event calendar (RBI, Budget, index heavyweights' results, US CPI/FOMC —
**not yet collected**).

### (B) Proposers

Today's strategies stop sending orders directly. Each emits a candidate: side, instrument, stop, target, its own
reason. They keep their rules (the user's rule: new strategies, not tweaks); the decision layer decides whether and
how big.

### (C) Implied-move reader

The ATM straddle price at a decision time is the market's expected move to expiry. The decision variable is the ratio
**forecast realised move ÷ implied move**. That is how a volatility trader frames it: buy movement when the forecast is
well above what options price in, sell (defined risk) when well below, stand aside in between.

### (D) Allocator and decision layer

- **Position type gate**: a buying proposal needs the state to favour movement (impulse odds and forecast/implied ratio
  above thresholds set by the Phase 1 study); a selling proposal the opposite. Today's 27 Oct pin would have been a
  "low expected movement" state by the afternoon; etr's two breakout buys would have been refused or shrunk.
- **Capital per proposer** from the evidence store: a fraction of the edge estimate's lower bound, not fixed budgets.
- **One position manager per index**: the first proposal opens; later proposals on the same index and side merge into
  it; adds come only from open profit (no same-minute doubling on 0DTE options).
- **Conflict rules**: opposite proposals on the same index cancel unless the state model says two-way day.

### (E) Evidence store and automatic promotion/demotion

| Element | Rule |
|---|---|
| Unit | Each forward trade's return in R (loss at the planned stop = −1R), net of costs |
| What counts | Live PAPER and forward shadow trades after a strategy's freeze date. In-sample replays never count |
| Edge estimate | Bayesian mean of R per trade, shrunk towards −(costs) with a prior worth 30 trades; reported with an 80 % interval |
| Stages | RESEARCH → SHADOW → PAPER-SMALL (¼ size) → PAPER-FULL. LIVE only by the user |
| Promotion | Lower 80 % bound > 0 after ≥ 30 forward trades → next stage (user notified; PAPER-FULL needs the user's approval) |
| Demotion | A one-sided CUSUM on R per trade crosses its limit → one stage down, automatically, logged and reported |
| Per state | Edge also kept per market state, so a strategy can be on in high-movement states and off in low ones |

This replaces the in/out decisions made in chat (zbf in, out, shadow, A-245; odb out; ebs out).

### (F) Risk core

| Layer | Rule (values set in a new versioned risk file, tested in Phase 0) |
|---|---|
| Per trade | Loss at the stop or premium stop ≤ a set % of equity |
| Book | Same-direction exposure across NIFTY and SENSEX counts as one unit; total open risk capped |
| Adds | Only from open profit; the loss at the stop after an add never exceeds the first unit's |
| Drawdown throttle | Size multiplier falls with drawdown from the equity peak (for example 1.0 → 0.5 at −15 % → 0.25 at −25 %) and recovers with new highs |
| State | Low model confidence → size multiplier < 1 |
| Daily loss / kill switch | Unchanged, as the last line |

**Decision needed (section 11):** the user withdrew per-trade risk caps on 22 Oct (A-166). Since then PAPER-1 lost
₹4.43 L in 4 sessions. This design depends on the risk core; Phase 0 tests it before anything is changed.

### (G) Nightly research loop

| Step | What runs (after 15:50, on the Mac and GCP) |
|---|---|
| 1. Data QA | Capture completeness per instrument, gaps, stale feeds, zt vs own capture agreement |
| 2. Post-mortem | Every trade: path, MFE/MAE in R, time to stop/target, the state model's view at entry, what the allocator did, missed impulses |
| 3. Reconciliation | Live vs same-day replay; any difference above a set amount is an alert |
| 4. Evidence update | Edge estimates, CUSUM demotion checks, state-model calibration on the new day |
| 5. Hypotheses | From recurring post-mortem patterns and from ideas the user brings (charts, playbooks, courses). Each is first matched against the ledger: "already tested as A-xxx, result …" before anything is built |
| 6. Registration | A ledger row written by the loop with a standard template (rule, data, halves, held-out, criteria, family, family's test count) |
| 7. Tests | Bars 2024–26 first (sample size), tick days second; walk-forward; held-out read once |
| 8. Multiple testing | Pass thresholds tightened by the family's number of tests (Benjamini–Hochberg on p-values from day-level bootstraps) |
| 9. Report | Daily one-page summary; weekly review with promotions/demotions proposed |

Budget: a fixed number of new hypotheses per week, so the loop cannot p-hack itself.

### (H) The language model's role

| Uses | Never |
|---|---|
| Writing post-mortems from the data the loop computed; turning user ideas into precise testable rules; matching ideas to past ledger rows; reviewing code; drafting reports | In the order path; deciding a trade; reading a chart without the data behind it |

Every factual claim a model makes about a session is checked against the database before it reaches a report.

## 5. Self-honesty dashboard

Reported daily and weekly, without being asked:

| Metric | Why |
|---|---|
| State-model calibration (reliability per decile; pinball loss vs the baseline) | Is the "intelligence" still informative? |
| Live vs replay gap | Engine faithfulness |
| Forward edge per proposer and per state, with intervals | What actually works |
| Research: tests run, passes, passes after correction, held-out spent | Is research finding anything real? |
| Drawdown vs throttle levels; days stood aside and what the market did | Risk discipline and the value of abstaining |
| Equity vs a risk-based objective | Replaces the ₹10 Cr curve (section 10) |

## 6. Governance

| The system may, on its own | Needs the user |
|---|---|
| Run research, register and run tests under the budget | Any change to the live PAPER set's proposers or the risk file |
| Demote a strategy one stage on a CUSUM breach (PAPER-FULL → PAPER-SMALL, → SHADOW) | Promotion to PAPER-FULL |
| Size down on drawdown or low confidence | Raising any cap or limit |
| Stand aside | Deploys to GCP |
| Report and alert | Anything LIVE (`CLAUDE.md`); account and broker settings |

Every automatic action is written to an audit table with its reason and the evidence values at that moment.

## 7. Phases and gates

Effort is in working sessions of focused build; dates depend on the user's go-ahead per phase.

| Phase | Builds | Gate to continue |
|---|---|---|
| **0. Stabilise** | (a) Risk core as `paper-risk.v19.yaml` + engine support for book exposure and the drawdown throttle; (b) multiple-testing audit of the ledger's passes; (c) evidence store backfilled from forward trades since 7 Oct | v19 on tick days and 416 bar days: worst day ≤ a set % of equity, net not worse than v17 by more than a set amount; the user approves deploying it |
| **1. Is movement forecastable, and does it pay?** | State model study on bars Oct 2024–Sep 2026 (spec in section 8) | **Kill criterion**: if the forecast/implied ratio does not sort straddle P&L on unseen months, the state layer has no economic value: stop and report |
| 2. Allocator and decision layer | Proposer interface, position-type gate, capital from evidence, position managers; strategies refactored with identical trades when the gate is off | Shadow alongside the live set for 20 sessions; nets more with no worse drawdown |
| 3. Selling side | Buy/sell design S1–S4 (legs, books, multi-day, margin, PAPER-SELL account), selling proposers chosen by the state model | Forward record on PAPER-SELL |
| 4. Research loop automation | Steps 1–9 of (G) as scheduled jobs; ledger templating; FDR control | Its first four weekly reports reviewed by the user |
| 5. Minute-level state + onset entries | State model every minute; research on onset (not confirmation) entries | Registered tests |

## 8. Phase 0 and Phase 1 specifications

### 8.1 Phase 0 (to register as three ledger rows on approval)

**0a Risk core v19.** Arms on the 28 tick days and the 416 bar days, ₹5 L chained (equity-scaled, as live): control
v17; v19 with per-trade risk, book exposure, add-from-profit and the drawdown throttle. Report: net, worst day, maximum
drawdown, longest drawdown, trades refused by each layer. Criteria fixed before the run: v19's maximum drawdown at most
half of v17's and its net at least 70 % of v17's on bars.

**0b Multiple-testing audit.** For every PASS in the ledger: number of tests in its family before it, its effect and
day-level bootstrap p-value, corrected status. Output: which passes survive; which live or shadow strategies rest on a
pass that does not.

**0c Evidence store.** Table of forward trades (live PAPER-1, SIGNAL-1, shadows) since each strategy's freeze date, in
R; the first edge estimates with intervals. Descriptive only.

### 8.2 Phase 1: forecastability study (to register on approval)

| Item | Specification |
|---|---|
| Data | NIFTY and SENSEX 1-minute bars Oct 2024–Sep 2026 (`hist.candle`), option bars for the ATM straddle, daily VIX, GIFT, participant OI; own ticks for October as a final check only |
| Targets | Rest-of-day range, next-60-minute range (quantiles), impulse odds within 30 minutes, at 09:20 / 09:45 / 10:15 / 11:00 / 12:00 / 13:00 / 14:00 |
| Baselines | B0: time-of-day average range scaled by VIX ÷ its 60-day average. B1: log-linear model on 1/5/22-session realised ranges (HAR-style) |
| Model | Gradient-boosted quantile regression and logistic regression; inputs as in 4(A) |
| Validation | Expanding window by month, purged by a day; model choices made on Oct 2024–Jun 2025 only; Jul 2025–Mar 2026 walk-forward test; Apr–Sep 2026 held out, read once |
| Statistical gate | Pinball loss ≥ 10 % better than B0 on the walk-forward test and on the held-out months; calibration within ±5 percentage points per decile |
| Economic gate (the one that matters) | Sort decision times by forecast realised move ÷ straddle-implied move. Top fifth: buying the ATM straddle earns > costs; bottom fifth: selling it (defined risk) earns > costs; both on walk-forward and held-out months, in both halves |
| Kill criterion | Economic gate fails on held-out months → the state layer is not built; we report it and fall back to Phase 0 risk + the evidence allocator only |

## 9. What we stop doing

1. Building a new strategy the same night from a chart, a pasted playbook or a course. Ideas enter the research queue,
   are matched against the ledger first, and are tested under the budget.
2. Using tick-day replay totals as forecasts or as reasons to change the live set.
3. Changing the live set after one or two sessions. Promotion and demotion follow the evidence rules.
4. Re-testing on October, and re-reading held-out days.
5. Treating a single day's outcome — a big win or a big loss — as a lesson without a registered test.

## 10. Expectations and the objective

- ₹10 Cr by 31 December is not reachable on any evidence we have; pursuing it forces sizes that make ruin likely. For
  scale [lit]: the best audited systematic funds earn well under 1 % a session; 2 % a session compounded would be
  exceptional.
- Proposed objective (the user's decision): **a positive forward record over 40–60 sessions with maximum drawdown
  ≤ 15–20 %**, then scale capital as the evidence allows.
- Phase 1 may conclude that movement is not forecastable enough to pay. That is a valid, cheap, useful answer: it
  tells us to stop building intelligence on top of buying options, and points to defined-risk selling and risk
  discipline.
- The first phases protect capital. Growth comes only after a proven edge.

## 11. Decisions needed from the user

1. Adopt this design as the plan, replacing the open items of the 13 Oct and 22 Oct plans?
2. Phase 0a: test a risk core (`paper-risk.v19`) on tick and bar days, given A-166 was withdrawn on 22 Oct?
3. Phase 1: run the forecastability study, with its kill criterion?
4. Replace the ₹10 Cr / 31 Dec target with the objective in section 10?
5. Until Phase 0 is decided: keep the live PAPER set unchanged, or reduce size now?
