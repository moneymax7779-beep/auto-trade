# Experiment ledger

One row per hypothesis. Register before running; append results, never rewrite. Nothing is
promoted without passing its held-out gate. Receipts are `research.run` ids in the own database
and reports under `.local/reports/` (not committed).

Gates: **G1** replay on tuning sessions; **G2** one replay on held-out sessions (enforced:
`research.held_out_use` allows each strategy family one use of each held-out session, whatever the
version, so a later version needs held-out sessions no earlier version has seen);
**G3** forward PAPER; **G4** LIVE on one lot.

Rules:

1. A strategy change is a new strategy file version, hence a new configuration hash and a new row.
2. Sessions: from ecr-v3 on (user decision 2026-09-25), strategies are evaluated on market data from
   the week of 21 Sep 2026 onward, including each new session as it is captured, and not on older
   data (`evaluation.sessions_from` in the strategy file). The original split (tuning 11–21 Sep,
   held-out 22–25 Sep) applied to A-001; A-002 consumes 22–25 Sep for the early-confirm-runner family,
   so later versions are judged on sessions from 28 Sep onward.
3. Every result reports both fill models (base 250 ms / 0 bp, stressed 1 s / 25 bp) after dated costs,
   and keeps frames (per-minute observations) apart from episodes (traded campaigns).
4. Changing a threshold after seeing held-out results closes the row as CLOSED_FAIL; the new
   threshold gets a new row and needs fresh held-out data.

| ID | Registered | Hypothesis | Strategy config | Sessions (tuning / held-out) | Pass criterion | Result | Status | Receipts |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A-001 | 2026-09-25 | The early-confirm-runner lifecycle with the design conversation's default thresholds (ecr-v2: ChatGPT values plus placeholder lifecycle/exit rules) has positive expectancy on ATM index options after costs | `early-confirm-runner.v2.yaml` `sha256:79a32078a052…`; features v2 `f885efcfe0ca…`; exchange v2 `b22b96a9fb89…`; costs v1 `dfbfaebf5da2…` | 11, 15, 16, 17, 18, 21 Sep / 22–25 Sep | G1: stressed-lane average R > 0 over ≥ 10 episodes. G2 (run only if G1 passes): stressed-lane average R > 0 on held-out | G1 (run 3, 2026-09-25): 6 episodes in 6 sessions, all NIFTY/SENSEX **CE entered at CONFIRMED**; EARLY never fired (0 minutes with all 10 early conditions; breadth ≥ +50 held in 2–9% of near-level minutes, early distance in 1–7%); no PE trade (ORL broken in 13 minutes, never with RVOL ≥ 1.25). Base −₹172 (avg −0.03 R), stressed −₹664 (avg −0.05 R), costs ₹631. SENSEX CE 2/2 wins +₹2,527; NIFTY CE 1/4 −₹2,699. Exits: futures reversal 3/3 wins, invalidated 2/2 losses. Held-out not run (G1 not passed). | CLOSED_FAIL at G1 (too few episodes, negative R) | run 3; `.local/reports/lifecycle-run-3.md` |
| A-002 | 2026-09-25 | The same lifecycle and thresholds as A-001 (no tuning; ChatGPT values kept by user decision), evaluated on this week's market data | `early-confirm-runner.v3.yaml` `sha256:aa2aaa573b21…` (identical thresholds to v2; adds `evaluation.sessions_from: 2026-09-21`); features v2, exchange v2, costs v1 as A-001 | 21–25 Sep 2026 (21 Sep partial; 22–25 Sep were held-out and are consumed by this run) | Stressed-lane average R > 0 over ≥ 10 episodes | Run 4 (2026-09-25): 6 episodes in 5 sessions, all entered at CONFIRMED (EARLY never fired; breadth ≥ +50 held in 1–7% of near-level minutes). Base +₹1,145 (avg +0.30 R, median −0.22 R), stressed +₹791 (avg +0.27 R, median −0.24 R), costs ₹562. 2 wins of 6; one trade (22 Sep NIFTY PE on expiry day, +₹3,293, +2.67 R) is more than the whole total; the other five net −₹2,148. First PE trades (4). 23 Sep: no trade. 21 Sep's two trades also appear in A-001. | CLOSED_INCONCLUSIVE (6 of the 10 required episodes; positive mean from a single trade) | run 4; `.local/reports/lifecycle-run-4.md` |
| A-003 | 2026-09-26 | The fully implemented design (ecr-v4: volatility regime, order-book confirmation, breakout volume, acceptance travel/volume, runner and expiry-runner gates, CAS mode; all ChatGPT values unchanged) has positive expectancy after costs | `early-confirm-runner.v4.yaml` `sha256:ffff869da107…`; features v3 `0c98fbf27b35…`; exchange v2; costs v1; risk v3 for PAPER | Evaluation: sessions from 28 Sep 2026 onward (22–25 Sep are consumed for this family) | Stressed-lane average R > 0 over ≥ 10 episodes on sessions from 28 Sep | Descriptive run 5 on 21–25 Sep (already-seen sessions, **not evidence**): 5 episodes, base −₹1,681 (avg −0.08 R, median −0.04 R), stressed −₹1,687; all entered at CONFIRMED. The new expiry-runner exit `PREMIUM_LAGGING` closed the 22 Sep NIFTY PE after 4 minutes at +₹259 (v3 held it to +₹3,293). No position was open at 15:15, so CAS runner mode did not trigger; CAS entries cannot occur on zt data (no per-stock auction data). Regimes seen: LOW, NORMAL, HIGH (no EXTREME). | SUPERSEDED by A-004 before any evaluation session (placeholders recalibrated by rule, see docs/CALIBRATION-v5.md) | run 5 (`LIFECYCLE_DESCRIPTIVE`); `.local/reports/lifecycle-run-5.md` |
| A-004 | 2026-09-26 | ecr-v4 with its placeholders set by pre-registered distribution rules (not trade results) has positive expectancy after costs | `early-confirm-runner.v5.yaml` `sha256:b6e6dfd9b6e3…`; features v4 `234935418e5c…`; exchange v2; costs v1; risk v3 | Evaluation: sessions from 28 Sep 2026 onward (PAPER live from 28 Sep; calibration used 11–18 Sep feature distributions only) | Stressed-lane average R > 0 over ≥ 10 episodes on sessions from 28 Sep | — | OPEN (live PAPER from 28 Sep) | docs/CALIBRATION-v5.md |
