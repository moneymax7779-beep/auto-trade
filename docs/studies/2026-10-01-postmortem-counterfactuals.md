# 1 Oct 2026 post-mortem: design-day counterfactuals (registered 2026-10-01 16:0x, before computing)

Design-day only (1 Oct, SENSEX expiry): reported as design evidence, never as proof. Each replays the live
set (ebs-v1, ecr-v8, odb-v2, egb-v3, features v7, paper-risk v4) with ONE file swapped, on the live image
`1a4d1d93ec0e`, source zt-tiger-v2. Compared with the same-source replay of the live set (session 318),
not with live, because live and replay differ on 1 Oct (order-book input, see the post-mortem).
- R1 `early-confirm-runner.v8norp.yaml`: premium_response_ratio_min −1000 (the PREMIUM_LAGGING exit and the
  premium-response gate off).
- R2 `expiry-gamma-breakout.v3mem60.yaml`: compression memory 60 minutes (v3: 30).
- R3 `expiry-breakout-straddle.v1rvs75.yaml`: compression range_vs_session_max 0.75 (v1: 0.66).
Reported: every trade (lots, qty, entry, exit, reason, net) and the session net vs session 318.
Any rule that looks better here must then be registered and tested on all recorded expiry days.

## Results (computed 2026-10-01 16:07; replay sessions 318–321)
- Baseline, live set from zt data (318): ECR SENSEX 72200 PE, 58 lots, entered 12:26 (live entered 12:24,
  61 lots: live and replay differ on `book_favourable`, so the confirm score was 82.66 live vs 79.66 in replay
  against a required 80), exit 12:28:01 PREMIUM_LAGGING, −₹5,664 (live +₹9,229).
- R1 (premium-response gate off, 319): the same entry; exit at the same minute on the next expiry-runner gate,
  EXPIRY_MOMENTUM_LOST: −₹5,664. The exits are a set of one-snapshot gates; removing one hands the exit to another.
- R2 (EGB compression memory 60, 320) and R3 (straddle range_vs_session_max 0.75, 321): no EGB or straddle
  trade; identical to 318. Neither threshold was the only blocker.
- Trail sensitivity (recorded bids, not a replay): the ECR position held with a trail from the best bid after
  +20 % would have made ₹3,331 (20 % trail), ₹3,95,853 (25 %), ₹3,53,044 (30 %), ₹3,10,222 (35 %) gross —
  a five-point change in one parameter moves the result by ₹3.9 lakh on one day, which is why none of these
  is a finding.
