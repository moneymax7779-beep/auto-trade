# A-398: the pasted diagnosis's four entry rules, in the engine, with shared capital

13 Nov 2026. User: "can we do something seriously consider these" (the four points from the pasted chart
diagnosis, A-391 Part A). A-391 screened them by deleting trades, which ignores what the freed capital does next.
Here each rule is an entry refusal inside the engine, and the live five are replayed with Rs 5 L shared.

## Set-up

- Code: `RegimeGate` mode `VETO_TABLE` (branch research/a396-regime-gate, worktree ../auto-trade-a396,
  uncommitted). The engine refuses an entry when the day's table lists (minute, index, side, strategy or `*`, rule)
  for an enabled rule. Image `autotrade-trading-core:regime-a398`.
- Tables: `.local/a398/veto.py`, point-in-time. An entry in minute m sees data to the close of minute m-1.
  - Index 1-min OHLC.
  - Chart VWAP = futures VWAP minus the trailing 15-min basis.
  - EMA20 of 3-min closes.
  - Zones confirmed by then.
  - PDH/PDL; ORH/ORL from 09:30.
  - SENSEX points = NIFTY points × 3.25.
- Rules:
  - **R1 TIGHT_BOX** (brtr, obc): refuse 09:20–10:15 while the trailing 15-min range is < 50 pts. Changed from the
    session range before any replay; see the A-398 note row.
  - **R2 CHASE** (all): refuse when the spot is > 100 pts beyond VWAP and > 100 pts beyond EMA20(3m), in the
    trade's direction.
  - **R3 WALL** (all): refuse a CE when a ≥ 4-touch resistance, PDH or ORH is 0–15 pts above the spot. Refuse a PE
    when the mirror-image support is 0–15 pts below.
  - **R4 LATE_MAC** (ma-cross, VIX open > 16): refuse a CE at or above VWAP/PDH, and a PE at or below VWAP/PDL.
- Risk v27–v31 = v19 + the gate (R1, R2, R3, R4, all four).
- Days: the 31 own-capture tick days. H1 = 28 Sep–19 Oct (15), H2 = 21 Oct–12 Nov (16).
- Control: A-390 `ctl`, +68.33 L, max drawdown −2.70 L.
- Identity check: with the gate on but no matching rows, 28 Sep, 22 Oct and 12 Nov matched ctl to the rupee.

## Result

| Arm | Diff vs ctl | H1 / H2 | Without best day | 20 non-chart days | Refusals | Verdict |
|---|---|---|---|---|---|---|
| R1 tight box | −1.19 L | −1.20 / +0.01 | −1.27 L | +0.12 L | 6 | FAIL |
| R2 chase | −4.57 L | 0 / −4.57 | −5.13 L | −4.43 L | 4 | FAIL |
| R3 wall | −5.83 L | −5.00 / −0.83 | −6.58 L | −5.47 L | 41 | FAIL |
| R4 late ma-cross | +2.58 L | — / +2.58 (H2 only) | +0.27 L | +2.03 L | 14 | passes as registered, not real |
| All four | −13.21 L | −4.98 / −8.23 | −13.96 L | −13.26 L | 58 | FAIL |

Where each difference comes from (trades matched on index, strategy, side and entry minute):

| Arm | ctl trades removed (winners) | Their net | Trades added or retimed | Their net | Same trades resized |
|---|---|---|---|---|---|
| R1 | 5 (2) | +1.08 L | 1 | 0 | −0.11 L |
| R2 | 3 (3) | +4.60 L | 3 | +0.03 L | 0 |
| R3 | 31 (16) | +21.44 L | 26 | +14.23 L | +1.38 L |
| R4 | 7 mac (4) + 1 wbr retimed | mac +0.19 L, wbr −0.49 L | 1 (wbr) | +1.97 L | +0.30 L |
| All | 41 (20) | +25.47 L | 29 | +12.95 L | −0.70 L |

## What happened, rule by rule

- **R1.** It had almost nothing to act on: no opening range on these days was under 50 pts. On 6 Oct it refused
  the obc CE (+80K) and the brtr CE (+39K). The freed capital made the etr loser bigger. The "trapped in a
  30–50 pt box" picture does not match these days' early entries.
- **R2.** It refused only obc entries, and every refused trade was a winner. On 6 Nov the obc CE at 09:49 was
  100+ pts beyond VWAP and EMA and made +4.19 L on the set's trend day. The capital went to two mac PE losers
  (−80K). A "chase" on a trend day is the continuation the strategy exists for.
- **R3.** Breakouts start next to the level they break, so a rule against entering 0–15 pts under a wall refuses
  the breakout entries themselves. On 6 Nov it refused both brtr CEs (+4.11 L); they went through the level and
  held to the close. brtr fell from +6.32 L to +2.11 L. The rule did save 9 Nov's obc PE (−91K), but brtr then
  took 7 small trades instead.
- **R4.** Its own refusals lost money (−0.19 L on 7 ma-cross trades). The refused trades were:
  - 26 Oct: two PEs, −55K.
  - 12 Nov: a PE, −36K.
  - 29 Oct: a CE that hit its target, +47K.
  - 11 Nov: two CEs (+5K and −0.3K) and a PE that made +59K.

  The whole +2.58 L comes from a capital-timing accident on 29 Oct. With no mac CE at 13:27, wbr entered at
  13:37 instead of 14:28 and made +1.97 L instead of −49K. That is not something the rule can claim.

## Honest reading

- **Chart days.** On the 11 days the diagnosis came from, the rules changed the set by −1.30, −0.14, −0.36 and
  +0.55 L (R1–R4), and +0.05 L all together. The losses in the charts are real, but these rules do not separate
  them from the winners. The same conditions (tight early range, extended from VWAP, entry under a level,
  counter-move on a high-VIX day) are present at the big winners.
- **Consistency with earlier tests.** This is the fourth time a loss-description filter has removed more profit
  than loss in this set (A-391 F1–F3, A-394, A-396 G1). The set's money is in a few large continuation trades,
  and every one of these rules looks like a reason to skip a continuation.
- **R4.** It technically meets the registered criteria (1), (3) and (4) and was declared H2-only. I am not
  recommending it: the mechanism it claims (avoid late counter-trend crosses) lost money on its own refusals.
  A bar-day replay would be needed before calling it anything, and that needs the user's OK.
- **Drawdown.** It fell in R4 (−2.15 vs −2.70 L) and in All (−2.29 L). As in A-396, that comes from one or two
  days; it is not a registered criterion.

Files: `.local/a398/` (veto.py, config/regime tables, jobs.txt, analyze.py, result.md, tick/logs).
