# ITM strikes and delta-based risk sizing on the live strategies (registered 2026-09-30, before computing)

Question (user, 2026-09-30, after the chart review): would the live strategies do better (a) buying one
strike in the money instead of ATM, and (b) sizing every single-leg entry to a fixed rupee risk at its
own stop, computed from the option's delta and the stop's distance in index points? Execution only:
no signal, entry or exit rule changes. Replays of the live strategy set; nothing deployed.

## Variants (each replays all four live strategies on one ₹5,00,000 account, straddle first)
- **A — live (control):** ebs-v1, ecr-v8, odb-v2, egb-v3, features v7, paper-risk v4.
- **B — ITM:** A with `opening-drive.v2itm.yaml` (`position.strike_offset: 1`) and
  `early-confirm-runner.v8itm.yaml` (LOW / NORMAL regimes `strike_offset: 1`; HIGH / EXTREME were
  already 1). EGB (already picks ATM or 1 ITM by delta) and the straddle (ATM by design) unchanged.
- **C — delta sizing:** A with `paper-risk.v6.yaml` = v4 + `sizing.delta_risk_per_trade: 10000`
  (2 % of capital, the same rupee risk as v5's cap). For a single-leg entry the risk per unit is the
  smaller of (i) |delta| × the index-point distance from spot to the strategy's structure stop and
  (ii) the premium stop (stop % × ask) — the exit that comes first. Lots = ₹10,000 / (risk per unit ×
  lot size), never more than the strategy's own premium budget or free capital (v4 limits). Structure
  stop: opening drive — the broken level; ECR (continuous-session entries) — the level plus its
  invalidation band × ATR3m. EGB and ECR closing-auction entries have no structure distance and use
  (ii) only. Delta: the contract's latest feed delta; missing or zero → (ii). The straddle keeps v4
  sizing (v5 already measured the premium-stop cap on it).
- **D — both:** B + C.

## Sessions
zt-tiger-v2 sessions without stale data (A-025's check): test 15, 18, 28, 29, 30 Sep; held-out 22, 23,
24, 25 Sep (read once for this hypothesis, reported apart). Not used: 11, 16, 17, 21 Sep (stale prices)
and 2–10 Sep (archive restore needs disk). All variants run on the same image.

## Reported
Per variant: every trade (strategy, index, day, contract, lots, qty, entry, exit, reason, premium, net),
count, wins, total net, worst trade, largest premium; per strategy; test and held-out apart; the
difference from A for B, C, D.

## What counts
Descriptive, few trades (A traded about a dozen times on these days in earlier replays). A variant is a
candidate only if it improves on A on both the test and the held-out sessions without a larger worst
loss (C, D) or a lower win count (B). Otherwise nothing changes live.

## Results (computed 2026-10-01 00:57; image `1a4d1d9-a026`, replay sessions 281–316)
Control check: A on the new image reproduces live 30 Sep exactly (−₹69,677), so the new code changes
nothing with v6 off. 15, 18, 29 Sep (test) and 23, 25 Sep (held-out): no trades in any variant.

| Day | Trade | A live | B ITM | C delta sizing | D both |
| --- | --- | --- | --- | --- | --- |
| 28 Sep | opening drive NIFTY PE 09:16–10:00 (time exit) | 91 lots 23000 PE 83.92 → 140.14, ₹4,96,376, **+₹3,30,990** | 70 lots 23050 PE 108.84 → 174.37, **+₹2,96,700** | 7 lots, ₹38,175, +₹25,439 | 6 lots 23050 PE, +₹25,422 |
| 28 Sep | opening drive SENSEX PE | refused (capital in use) | refused | 2 lots 73400 PE 371.95 → 559.15, +₹7,404 | not filled twice |
| 30 Sep | opening drive SENSEX CE 09:21–09:23 (structure stop) | 72 lots 72700 CE, ₹4,96,793, **−₹69,677** | 62 lots 72600 CE 401.87 → 349.38, −₹66,090 | 37 lots, ₹2,55,219, −₹35,841 | 33 lots 72600 CE, −₹35,671 |
| 22 Sep | ECR NIFTY PE 10:33–10:37 | 151 lots 23400 PE, +₹18,266 | 93 lots 23450 PE, +₹17,738 | 39 lots, +₹4,683 | 27 lots 23450 PE, +₹5,131 |
| 22 Sep | straddle NIFTY 23300 13:54–14:14 (target) | +₹1,52,536 | same | same | same |
| 24 Sep | ECR SENSEX 74200 PE 10:18–10:25 | 59 lots, +₹9,476 | same (HIGH regime: already 1 ITM) | 5 lots, +₹775 | 5 lots, +₹775 |

| Total | A | B | C | D |
| --- | --- | --- | --- | --- |
| Test (15, 18, 28, 29, 30 Sep) | **+₹2,61,313**, 2 trades, 1 win, worst −₹69,677 | +₹2,30,610, 2 / 1, worst −₹66,090 | −₹2,998, 3 / 2, worst −₹35,841 | −₹10,249, 2 / 1, worst −₹35,671 |
| Held-out (22–25 Sep) | **+₹1,80,278** | +₹1,79,750 | +₹1,57,993 | +₹1,58,442 |
| Held-out without the (identical) straddle | +₹27,742 | +₹27,214 | +₹5,458 | +₹5,906 |

- **B (ITM) is worse than A on both sets** (−₹30,703 test, −₹528 held-out): the ITM option cost more per
  lot and moved a smaller percentage (28 Sep +60 % vs +67 %; 30 Sep −13.1 % vs −13.8 %), so fewer lots and
  a smaller gain; the loss on 30 Sep barely changed.
- **C (delta sizing) halves the worst loss (−₹35,841 vs −₹69,677) but gives up about 99 % of the test
  profit and 80 % of the held-out directional profit.** On 28 Sep it traded both indices (A's NIFTY
  position took the capital) but at 7 and 2 lots.
- **The delta model under-states the risk of a close structure stop.** 30 Sep: the stop was 27 points
  away, |delta| 0.50, so 13.5 a unit was expected and 37 lots were bought for "₹10,000 of risk"; the real
  loss was 47.7 a unit (IV fell about 1.5 points, and the exit filled below the level) — 3.6 × the
  estimate, −₹35,841.
- ECR's entry is the first tranche of its plan (the cap sizes the whole plan), so its actual risk at
  entry was below ₹10,000 (24 Sep ₹20,809 of premium).

## Reading
Neither change improves the live set on both the test and the held-out sessions: B loses a little on
both; C and D cut the worst loss but also cut the profit by far more. Nothing changes live. With 6
single-leg trades across 9 sessions this is a thin sample; it says ITM does not help these entries and
that delta sizing from a tight structure stop needs a vega / slippage allowance before it controls risk.
