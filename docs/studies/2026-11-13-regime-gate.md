# A-396: regime gate in the engine (13 Nov 2026)

Question: does a day-regime gate on new entries improve the live five-strategy set (etr b375000, mac-v34, obc-v13,
wbr-v13, brtr-v1; risk v19; ₹5 L shared capital) when replayed in the engine on all 31 own-capture tick days?

Code: branch `research/a396-regime-gate` (`RegimeGate.java`, off unless the risk file has `regime_gate.mode`).
Risk files v24 (PASTED), v25 (VIX_SIZE), v26 (OR_SIZE) = v19 + the gate block. Gate-off identity matched the A-390
control to the rupee on 28 Sep, 22 Oct and 12 Nov.

R_OR = (09:15–09:44 spot range) / (open × VIX / 100 / √252), frozen at the first snapshot from 09:45.

## Arms vs control (registered criteria: > 0 overall, both halves, without best day, on the 20 non-chart days)

| Arm | Difference | H1 / H2 | Without best day | Trades | Max drawdown | Verdict |
|---|---|---|---|---|---|---|
| Control (v19) | net +68.33 L | | | 157 | −2.70 L | |
| G1 PASTED | −40.90 L | −38.17 / −2.73 L | −42.64 L | 80 | −1.33 L | FAIL |
| G2 VIX ≥ 15.5 → ×0.5 | −2.89 L | 0 / −2.89 L | −3.61 L | 184 | −1.33 L | FAIL |
| G3 R_OR < 0.45 → ×0.5 | −0.78 L | −0.35 / −0.43 L | −2.85 L | 177 | −1.45 L | FAIL |

- G1 refused 201 entries (47 before 09:45, 82 HIGH_VIX_CHOP, 41 EARLY_EXHAUSTION, 31 strategy not allowed).
  13 Oct alone cost −12.5 L: R_OR 1.05 was labelled exhaustion, but it was the set's trend day (wbr +8.46 L, etr +2.90 L).
- G2: VIX was below 15.5 on every H1 day, so this arm only ever acted in H2 and could not meet criterion 2. That
  should have been caught at registration (A-391's VIX halving was also H2-only).
- G3: halving freed capital, which took 20 extra trades; net almost unchanged, and negative without its best day.
- Lower drawdown in every arm comes mostly from 12 Nov. That is one day, was not a registered criterion, and is not evidence.

## Descriptive: control trades by R_OR bucket (entry time, index's own R_OR)

| Bucket | Index-days | Trades | Net | Per trade | Win rate |
|---|---|---|---|---|---|
| Entered before 09:45 | 33 | 36 | +30.57 L | +85K | 42 % |
| Narrow (< 0.45) | 29 | 83 | +16.76 L | +20K | 34 % |
| Mid (0.45–0.65) | 12 | 25 | +8.04 L | +32K | 48 % |
| Wide (> 0.65) | 6 | 12 | +13.87 L | +116K | 75 % |

The ordering agrees with A-395's bar result: wider opening ranges trend more. But narrow days are still profitable for
these strategies, so cutting them loses money. The pasted design cut the two best buckets: the pre-09:45 entries and
the wide days. Adding size on wide days is the only direction the data leans toward, but it rests on 12 trades on
6 index-days; testing it would need the 2026 bar days, which need the user's OK.

Script: `docs/studies/2026-11-13-regime-gate-analyze.py` (reads `.local/a390` and `.local/a396` progress files).
