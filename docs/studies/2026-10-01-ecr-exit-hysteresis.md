# ECR expiry-runner exit hysteresis (registered 2026-10-01 16:2x, before computing)

Question (1 Oct post-mortem, proposal 1): ECR's expiry-runner exits (OPPOSITE_WALL, PREMIUM_LAGGING,
EXPIRY_MOMENTUM_LOST) fire on a single one-minute snapshot. On 1 Oct the SENSEX 72200 PE was sold at 210 four
minutes after entry and was bid 691.95 thirty minutes later. Does requiring the exit condition on **2
consecutive snapshots** improve ECR on expiry days? **1 Oct is the design day: reported, not counted.**

## Variant
- Control: the live set (ebs-v1, ecr-v8, odb-v2, egb-v3, features v7, paper-risk v4).
- `early-confirm-runner.v8h.yaml` = ecr-v8 + `exits.expiry_runner_exit_confirm: 2`: an expiry-runner exit is sent
  only when one of its three conditions holds on this snapshot and held on the previous one (any of the three
  each time). Everything else unchanged: premium stop, invalidation, trail, futures reversal, expiry stall,
  CAS, FLAT_BY. Without the key the behaviour is ecr-v8's (one snapshot).
- Both run on the same new image (the control must reproduce earlier replays of these days).

## Sessions (expiry days zt-tiger-v2 holds without stale data)
Test: 15 Sep (NIFTY), 29 Sep (NIFTY). Held-out: 22 Sep (NIFTY), 24 Sep (SENSEX), read once for this hypothesis.
Design: 1 Oct (SENSEX). Not used: 17 Sep (SENSEX, stale prices), 3, 8, 10 Sep (archive restore needs about
13 GB; 19 GB free on 1 Oct).

## Reported
Every trade in both variants (strategy, contract, lots, qty, entry, exit, reason, net); ECR net and the session
net per day; test, held-out and design apart.

## What counts
A candidate only if ECR's net improves on the test days AND the held-out days and its worst trade is not
worse. With about one ECR trade per expiry day the sample is a handful of trades: a pass means "worth a
forward shadow test", not a conclusion.

## Results (computed 2026-10-01 16:5x; image `ec8e5c2-a027`, replay sessions 322–331)
Control check: the control reproduces the earlier replays of these days exactly (22 Sep +₹1,70,802, 24 Sep
+₹9,476, 1 Oct −₹5,664), so the new code changes nothing without the key.

| Day | Group | ECR control (ecr-v8) | ECR v8h |
| --- | --- | --- | --- |
| 15 Sep NIFTY | test | no trade | no trade |
| 29 Sep NIFTY | test | no trade | no trade |
| 22 Sep NIFTY | held-out | 23400 PE 151 lots 10:33–10:37, 39.13 → 41.10, PREMIUM_LAGGING, **+₹18,266** | 10:33–10:38, → 40.30, EXPIRY_MOMENTUM_LOST, **+₹10,400** |
| 24 Sep SENSEX | held-out | 74200 PE 59 lots 10:18–10:25, 208.14 → 216.68, EXPIRY_MOMENTUM_LOST, **+₹9,476** | 10:18–10:27, → 214.22, FUTURES_REVERSAL, **+₹6,583** |
| 1 Oct SENSEX | design | 72200 PE 58 lots 12:26–12:28, 213.74 → 209.34, PREMIUM_LAGGING, −₹5,664 | 12:26–12:33, → 223.06, FUTURES_REVERSAL, +₹10,202 |

(Other strategies are identical in both variants: 22 Sep straddle +₹1,52,536.)

- Test days: **no ECR trade in either variant**, so the test condition cannot be met.
- Held-out: ECR **+₹27,742 → +₹16,983 (−₹10,759)**: on both held-out days the runner really was ending, and
  waiting one more minute sold lower.
- Design day: −₹5,664 → +₹10,202; the extra minute let the position reach 12:33, where FUTURES_REVERSAL (a
  one-snapshot exit not covered by this change) closed it, still before the 12:41–12:58 fall.

## Reading
Fails the registered criterion: worse on the held-out days, untestable on the test days, better only on the
day it was designed from. Not a candidate; nothing changes live. The general lesson stands: the runner's
exits are many one-snapshot rules, and delaying one of them simply hands the exit to the next.
