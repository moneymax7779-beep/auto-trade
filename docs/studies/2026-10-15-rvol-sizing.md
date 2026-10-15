# Bigger size when futures volume confirms (ledger A-106)

Registered 15 Oct 2026 ~06:25 IST, before any result. User, ~06:20: "yes register and test the futures volume sizing",
after the 14 Oct context split (`docs/studies/2026-10-14-postmortem.md` §10).

## Evidence that led here (in-sample, stated now)

The live set without egb (A-095 run), directional entries only, PF by futures RVOL at entry:

| Futures RVOL at entry | Tick days (89 trades) | Bar days (610 trades) |
|---|---|---|
| ≥ 1.5 | 6.56 (30 trades) | 1.68 (155 trades) |
| 1.0–1.5 | 3.31 | 1.15 |
| < 1.0 | 2.38 | 1.07 |

It is the only context factor tried that holds on both data sets. It was found on these same days, so this test shows
what the rule would have done. It does not prove the rule works on unseen days.

## The rule (values fixed now, round numbers)

Risk v17 = v12, except that a **single-leg entry** is sized at **1.5 × its normal premium budget** when the futures
time-of-day RVOL at the entry snapshot is **≥ 1.5**.
- `sizing.rvol_boost_threshold: 1.5`, `sizing.rvol_boost_factor: 1.5`.
- Everything that caps size still applies afterwards: the per-trade caps, free capital, the 400-lot cap and the 2 %
  liquidity cap.
- **Amendment (~06:40, before any run).** The risk check refuses an entry larger than free capital; it does not shrink
  it. A plain 1.5× on a ₹5L budget at ₹5L capital would therefore turn boosted entries into refusals. The boost uses
  spare capital only:

  `budget = max(normal, min(1.5 × normal, 0.97 × free capital))`

  The 0.97 leaves room for the entry buffer. An entry whose normal budget already fills the capital is sized exactly as
  in v12.
- Adds follow the entry's plan as before, so a boosted pyramid entry has boosted adds.
- Straddles are unchanged (their sizing is direction-neutral).
- An unknown RVOL means no boost.

## Test

- **Image:** Mac, one image built from main (0e9ded6) plus this change.
- **Strategies:** the live set as deployed on 15 Oct: ebs-v2, ecr-v11, odb-v3, etr-v1, mac-v1, odm-v4.
- **Configs:** features v12, costs v2, ₹5L per day.
- **Runs:** all 24 tick days and 169 bar days with risk v17.
- **Control:** the A-095 run (the same set with risk v12, `.local/a95`).

**PASS** needs all of:
1. net ≥ control on the tick days;
2. net ≥ control on the bar days;
3. the worst day at most 25 % worse than the control's worst day, on each data set (a larger size must not create a
   much larger single-day loss).

A PASS is in-sample evidence. Going live would be the user's decision, with a forward review after 10 sessions.

## Result (15 Oct 06:24–07:05 IST; `.local/a106/`, image 0e9ded6-a106)

| | Control (risk v12) | Risk v17 | Change | Days changed | Worst day (both) |
|---|---|---|---|---|---|
| 24 tick days | +₹41,94,356 | +₹42,21,066 | **+₹26,710** | 4 | −₹1,31,634 |
| 169 bar days | +₹24,84,424 | +₹25,23,401 | **+₹38,977** | 15 | −₹1,29,522 |
| 14 Oct at live equity | −₹3,08,783 | −₹3,08,783 | 0 | – | – |

- Of the 19 changed days, 13 gained (+₹2.12L) and 6 lost (−₹1.47L).
  - Largest loss: 18 May bar day, −₹57,378.
  - Largest gain: 25 Sep bar day, +₹50,173 (+₹42,631 on the 25 Sep tick day).
- On 14 Oct nothing changed: odb's entries had already used all the capital.

**Verdict: PASS on all three criteria, but thin.**
- The rule adds about ₹66K over 193 days (about 1.5 % of the tick-day total) and does not make the worst day worse.
- It acts rarely: the boost may use only spare capital, and most entries already fill their budget.
- It is in-sample: the RVOL split that suggested it was measured on these days.
- Going live is the user's decision, with a review after 10 sessions.
