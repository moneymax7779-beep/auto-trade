# Intelligent trading layer: plan (13 Oct 2026)

User, 13 Oct 2026 ~00:40 IST: "I want the application to intelligently think and act to trade ... just like professional
real options trader does, use levels OI changes, previous day week month into consideration, S&R EMA 9, 20, VWAP, RVOL,
other indices movement, future volumes ... enter at right time and exit at right time ... not miss any good move, give me
the plan to develop intelligent system, think about using AI". Then, ~01:10: "Please document the plan for now".

This plan sits on top of `IMPLEMENTATION-PLAN.md` (platform phases 0-7) and follows its principles: live and replay run
identical code, features are point-in-time, everything tunable is versioned config, and a strategy earns capital only by
passing replay, held-out and forward tests (`EXPERIMENT-LEDGER.md`).

## 1. Where the system stands

**Inputs are not the main gap.** The feature engine already computes about 250 values per minute and index:

| Group | Examples (feature records in `features/.../snapshot`) |
|---|---|
| Levels | PDH, PDL, prior close, ORH, ORL, the prior session's opening range, S&R zones with touch counts, level ladder, room to the next level, retest states |
| Trend and structure | VWAP, session mean, EMA9, EMA20 and slope, swings, higher lows / lower highs, ATR 1m/3m, bar shape |
| Futures | price, VWAP, basis, momentum, acceleration, OI, OI change and state (fresh long/short, covering, unwinding), time-of-day RVOL and its slope |
| Options | ATM strike, call/put OI change 1-10 min and flow labels, OI walls and their weakening, straddle, ATM IV, IV skew, Greeks, premium response, spreads |
| Volatility and regime | VIX and its change, IV percentile, realised vol, expected move, DTE, minutes to expiry |
| Breadth and auction | weighted momentum / day / mover breadth over the index constituents, closing-auction (CAS) features |
| Order book | futures and ATM option bid/ask depth and its change |

Every candidate entry already stores its context (`trade.candidate.context`), which made the 12 Oct context evaluation
possible (118 tick-day trades: IV rising, IV percentile ≥ 50 and strong breadth agreeing separated winners; options OI
"writing" did not; `.local/context-eval`).

**Missing inputs:** weekly and monthly levels (PWH/PWL/PMH/PML), other-index context (BANKNIFTY not recorded since
29 Sep), put/call ratio and max pain.

**The real gaps are the decision architecture and the data:**

1. **No regime awareness.** Six fixed rules run side by side; none knows whether the day is a trend, a range or a
   two-way day, so the cross trades midday chop and nothing looks for a trend at 09:20 (12 Oct: the fall happened inside
   the opening range, A-085).
2. **Capital goes to whoever asks first.** orb-v1, orc-v2 and odm-v1 each made money alone and lost money inside the
   set by taking the capital the trend rider's pyramid needs on expiry days (A-078, A-082, A-085); a fixed 50 % reserve
   did not fix it (A-084).
3. **Nobody owns a move from start to finish.** Each rule enters and exits on its own logic; a trend is traded in
   disconnected pieces or not at all. The 7 Oct audit found 10 of 53 big legs caught (2 of 32 on non-expiry days).
4. **Too little full data for learning.** 23 full tick days (options, OI, IV, breadth) and about 120 trades; 9 months
   of one-minute bars without option OI/IV (and no breadth before July). Of the 88 ledger rows, most hand-made filters
   failed on held-out days: the signature of small samples. A model trained on 120 trades would memorise them.

**What works and must be kept:** replay equals live (12 Oct shadow matched GCP to the rupee; the live session differed
by one limit order at the edge of the book); the expiry trend rider and the cross carry most of the profit; the
registration discipline stops overfitting.

## 2. Target architecture

```
market data (Upstox feed, zt capture, vendor history)
        |
feature engine (existing, + weekly/monthly levels, other indices, PCR, max pain)
        |
   +----+---------------------+------------------------+
   |                          |                        |
regime engine           signal sources            opportunity scorer
(day type / state       (existing strategies,     (model: chance an ATM option
 probabilities)          new playbooks)            gains 1R before losing 1R)
   |                          |                        |
   +-------------> conductor (decision layer) <--------+
                    - ranks every candidate by expected value (probability x payoff - costs - time decay)
                    - checks regime fit and capital; protects high-value expiry setups
                    - one position manager per index: entry style, scale-in, trailing exits,
                      partial profit at levels, decay-aware time stop, re-entry on continuation
        |
risk engine + OMS (existing, unchanged; PAPER)
        |
evaluation (daily report, missed-move audit, attribution, drift, equity vs goal)
        |
offline AI analyst (review, hypotheses, questions in plain language) - never in the order path
```

The conductor, the regime engine and the scorer are deterministic Java components fed by the same snapshots in live and
replay, so every decision is reproducible and testable.

## 3. Phases

| Phase | Deliverables | Done when |
|---|---|---|
| **1. Data** (start now, ongoing) | (a) Buy 1-2 years of tick options data: specification ready in `data-vendor-request.md`. (b) Record BANKNIFTY again (check it fits the two Upstox feed connections). (c) Add weekly/monthly levels, PCR, max pain, previous-day OI walls to the features (new features version). (d) Replays store the per-minute feature snapshot (today only the live session writes `feat.snapshot`). | The tick sample grows from 23 days towards hundreds; every replay day is reusable training data |
| **2. Opportunity map** (1 week) | For every minute and index: the outcome of buying the ATM call and put (best and worst move in R, net of costs, over 15/30/60 minutes). A daily missed-move report: every leg of ≥ 1.5 ATR, whether it was traded, the closest rule and what blocked it. | "Don't miss good moves" is a measured capture rate, reported every day |
| **3. Regime engine** (1-2 weeks) | Probabilities of trend / range / two-way / expiry-gamma day, updated every minute from point-in-time inputs: gap vs ATR, opening drive, breadth, IV and VIX change, futures OI state, the other index, day of week and DTE. | Accuracy known at 09:30, 10:15, 11:00 on unseen days; playbooks gated by regime (trend rules only when a trend is likely, fades only in ranges) |
| **4. Opportunity scorer** (2-3 weeks) | Gradient-boosted trees against a logistic-regression baseline, predicting P(ATM option +1R before -1R within 30 min) per minute, side and index. Walk-forward by day with purging, calibrated, per-trade explanations. Exported as a model file and evaluated inside the Java engine. Bars-only pre-training on Jan-Sep for price and futures inputs. | Beats the existing rules on months it never saw, then in shadow |
| **5. Conductor** (2-3 weeks) | Existing strategies become signal sources. Expected-value ranking, regime fit, capital allocation that protects expiry pyramids, one position manager per index (entry style by regime, scale-in, structure trails on swing / VWAP / lost level, partial profit at levels, decay-aware time stop, re-entry on continuation). | Nets more than the current live set over 15 shadow sessions with no worse drawdown |
| **6. Evaluation** (continuous) | One standard report after every session and every test: missed moves, entry-context attribution, per-rule results, live vs replay drift, equity vs the ₹10 crore curve. Every change registered first. | The same measurement every day |
| **7. AI analyst** (offline) | Claude reads the daily report, charts and OI tables after the close, writes the review and proposes hypotheses (which still go through registered tests); answers questions about the data in plain language. | – |

**Why no language model in the order path:** it cannot be replayed (a backtest could not reproduce its decisions), it
answers differently each time, and it takes seconds where entries are decided per minute or faster. The trading
intelligence is deterministic models; the language model speeds up research and review.

## 4. Gates (unchanged discipline)

1. Registered before results (ledger row with rule, days, criteria).
2. Replay on tuning days; one run on held-out days (22-25 Sep are now spent for opening-range ideas: A-082, A-084).
3. For models: walk-forward by day, purged, plus whole months never seen.
4. Forward shadow: 15 sessions in its own session and capital.
5. PAPER in the live set only by the user's decision; LIVE money only by the user's explicit request (CLAUDE.md).

## 5. Expectations

- No system catches every move. The aim is a higher capture of big legs and smaller chop losses, measured by the daily
  capture rate.
- The ₹10 crore goal needs about 8-9 % a session from ₹10.07L (12 Oct). Size cannot grow without limit: under the
  liquidity cap (risk v12), above roughly ₹1 crore of equity the budgets exceed what the visible book fills.

## 6. Status at writing (13 Oct 2026, ~01:15 IST)

- Live PAPER set (GCP, commit aa548cb): ebs-v2, ecr-v11, odb-v3, egb-v4, etr-v7, mac-v1; risk v12; features v11.
- Shadow (each in its own SHADOW-1 session after the close): orc-v1, orc-v2 (A-077/A-078), orb-v1 (A-083), odm-v1
  (A-086), odm-v2 and orb-v2 (IV filter, A-087), odm-v3 and orb-v3 (breadth filter, A-088). First forward day 13 Oct.
- Decisions open for the user: the data purchase; re-enabling BANKNIFTY; starting phases 1 and 2.
