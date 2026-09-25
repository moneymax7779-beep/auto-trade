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
2. Sessions: tuning 11, 15, 16, 17, 18, 21 Sep 2026; held-out 22–25 Sep 2026 (2–10 Sep are archived
   off zt-tiger-v2 and not replayable until restored).
3. Every result reports both fill models (base 250 ms / 0 bp, stressed 1 s / 25 bp) after dated costs,
   and keeps frames (per-minute observations) apart from episodes (traded campaigns).
4. Changing a threshold after seeing held-out results closes the row as CLOSED_FAIL; the new
   threshold gets a new row and needs fresh held-out data.

| ID | Registered | Hypothesis | Strategy config | Sessions (tuning / held-out) | Pass criterion | Result | Status | Receipts |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A-001 | 2026-09-25 | The early-confirm-runner lifecycle with the design conversation's default thresholds (ecr-v2: ChatGPT values plus placeholder lifecycle/exit rules) has positive expectancy on ATM index options after costs | `early-confirm-runner.v2.yaml` `sha256:79a32078a052…`; features v2 `f885efcfe0ca…`; exchange v2 `b22b96a9fb89…`; costs v1 `dfbfaebf5da2…` | 11, 15, 16, 17, 18, 21 Sep / 22–25 Sep | G1: stressed-lane average R > 0 over ≥ 10 episodes. G2 (run only if G1 passes): stressed-lane average R > 0 on held-out | G1 (run 3, 2026-09-25): 6 episodes in 6 sessions, all NIFTY/SENSEX **CE entered at CONFIRMED**; EARLY never fired (0 minutes with all 10 early conditions; breadth ≥ +50 held in 2–9% of near-level minutes, early distance in 1–7%); no PE trade (ORL broken in 13 minutes, never with RVOL ≥ 1.25). Base −₹172 (avg −0.03 R), stressed −₹664 (avg −0.05 R), costs ₹631. SENSEX CE 2/2 wins +₹2,527; NIFTY CE 1/4 −₹2,699. Exits: futures reversal 3/3 wins, invalidated 2/2 losses. Held-out not run (G1 not passed). | CLOSED_FAIL at G1 (too few episodes, negative R) | run 3; `.local/reports/lifecycle-run-3.md` |
