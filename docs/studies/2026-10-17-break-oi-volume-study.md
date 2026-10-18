# Level / zone breaks with OI flow and futures volume (tick days, 2 Sep – 16 Oct 2026)

User, 17 Oct 2026, on charts of 7 Sep and 1 Oct NIFTY: support and resistance zones form during the day; when the
price breaks a zone or a key level, use the OI change at that minute (1–3 minute windows) to read the trend and enter
at once. "yes run on tick days only". An event study, not a strategy: no stop, exit or sizing was chosen.

## Data and definitions (fixed before the first run; script `.local/break-study/study.py`)

- 58 index-days: NIFTY 29 and SENSEX 29, 2 Sep – 16 Oct. Days with fewer than 250 minutes of option data were left out.
  - **Index minutes:** Upstox bars to 25 Sep, own capture from 28 Sep.
  - **Futures volume:** the same split of sources.
  - **Option OI:** zt-tiger-v2's minute snapshots (zt_archive copy), nearest expiry, ATM ±3 strikes.
- **Levels:** ORH/ORL, PDH/PDL, the prior day's ORH/ORL and PWH/PWL.
  - **Zones:** three or more 1-minute fractal pivots within 0.3 ATR, counted from the minute the third pivot is known.
  - **Break:** the first 1-minute close through a level, per direction, from 09:30 to 14:29. Breaks in the same direction within 5 minutes are merged into one.
- **OI flow at the break minute:**
  - **Agrees:** calls building and puts unwinding (down breaks) in some 1, 2 or 3 minute window, mirrored for up breaks.
  - **Strong:** at or above the day's 80th percentile so far.
- **Volume surge:** futures 1-minute volume at least 3× the day's median so far.
- **Outcome:** from the break close, whether +1 ATR (or +2 ATR) in the break's direction comes before −1 ATR, plus the 30- and 60-minute moves.
  - **Baseline:** every minute, both directions.

## Results (1,114 breaks; `.local/break-study/results.md`, `breaks.csv`)

| Group | 2–23 Sep: n / 1 ATR first / 2 ATR first | 24 Sep–16 Oct: n / 1 ATR first / 2 ATR first |
|---|---|---|
| baseline, every minute | 50 % / 33 % | 50 % / 35 % |
| all breaks | 540 / 49 % / 35 % | 574 / 53 % / 38 % |
| flow agrees | 163 / 54 % / 33 % | 171 / 54 % / 33 % |
| flow disagrees | 187 / 45 % / 32 % | 192 / 53 % / 41 % |
| volume ≥ 3× | 139 / 54 % / 38 % | 136 / 56 % / 46 % |
| flow agrees + volume ≥ 3× | 49 / 61 % / 46 % | 40 / **42 %** / 38 % |
| flow agrees, strong + volume ≥ 3× | 6 / 83 % / 67 % | 2 / 0 % / 0 % |
| fixed levels only, flow agrees + volume ≥ 3× | 10 / 70 % / 60 % | 16 / **38 %** / 31 % |

- **Breaks alone are coin flips,** 49–53 % against a baseline of 50 %. Most of the 1,114 are zone breaks (904).
- **OI flow agreeing adds about 4 points on the first 1 ATR (54 % in both halves) but nothing on 2 ATR (33 %, below baseline).**
  - The 60-minute move after agreeing flow was −0.65 ATR in September and +0.49 in October: it does not mark a trend.
- **The full combination of break, agreeing flow and a volume surge does not hold.** It won 61 % in September and 42 % in October; on fixed levels, 70 % and then 38 %.
  - The strong-flow version has 8 cases.
- **The volume surge alone is the most consistent:** +1 ATR first 54–56 % and +2 ATR first 38–46 %, against 33–35 % in both halves. That is small, and it was not the hypothesis.
- **The user's two examples were real but not typical.**
  - 7 Sep NIFTY 09:54 ORL: flow agreed, volume 1.5×, and it went 2 ATR.
  - 1 Oct NIFTY 12:06: the zone/ORL break was merged with 12:10; flow agreed, volume 1.45× at the first break (4.8× at 12:07, 6.2× at 12:10), and it went 2 ATR, +11.6 ATR in 60 minutes.

## Conclusion

On these 58 index-days, the OI flow at the break minute does not separate breaks that run from breaks that fail, and
the combination with a volume surge reverses between the two halves. Nothing here supports building the
break + OI entry as a strategy. If anything is followed up, it is the futures volume surge at a break, and only on the
bar days, which were not read for this study.

## Follow-up: quiet price with one-sided positioning (user, 17 Oct: "yes run it on tick days")

The 1 Oct NIFTY pattern: from 10:45 to 11:30 the price was flat while call OI rose, put OI fell and futures OI rose. Then the
12:07 break went down. Script `.local/break-study/signature.py`; definitions fixed before the run.
- **Intervals:** 15 minutes, 09:30–14:30, ATM ±5 strikes.
- **Bearish signature:** call OI up, put OI down, futures OI up, index down. The bullish signature is mirrored.
- **Quiet:** the interval's index move is at most 0.5 ATR.
- **Signal:** 2 (30-minute) or 3 (45-minute) consecutive quiet signature intervals.
- **Outcomes:** as above, plus which side of the run's range broke first.
- **Controls:** quiet runs without a signature, scored both ways.

- **As registered, the pattern occurred once in 58 index-days: 1 Oct NIFTY itself** (k = 2 and k = 3). It can't be tested.
- **Loosened after seeing that (post hoc, so descriptive only).** At quiet ≤ 1.5 ATR, without the futures condition and with k = 2, there were 31 cases.
  - **+1 ATR first:** 61 % overall; 71 % on 2–23 Sep (14) and 53 % on 24 Sep–16 Oct (17).
  - **+2 ATR first:** 71 % and then 41 %, against a control of 35 %.
  - **60-minute move:** +1.09 and then −0.92 ATR.
  - With the futures condition (≤ 1.5 ATR, k = 2), 17 cases: 2 ATR first 56 % and then 38 %.
- **The range-break measure is biased for the signature groups.** The signature requires a move its way, so the run ends near that edge of its range, and 76–100 % "right side" is largely mechanical. The ATR measures are the fair ones.
- **Same shape as the break study: good in September, not in October.** Too few cases and inconsistent halves. Not built.

## Replays (A-131 zbf-v1, A-132 qpb-v1; user 17 Oct: "do replay on tick days only")

The user chose the entry ("Both, as two strategies"), the stop ("Level back + OI reversed") and the exit ("Hold to 15:15").
Each strategy ran alone on the 27 tick days: image zq1, risk v17, features v12, starting capital every day; summary in
`.local/zq1/summary.txt` (worktree).

| Strategy | Test 20 days: trades / wins / net / PF / worst day | Held-out (seen by the study) | Design days |
|---|---|---|---|
| zbf-v1 | 57 / 13 / +₹14.86L / 2.30 / −₹1.38L | 7 / 2 / +₹5.59L | 4 / 1 / −₹38K |
| qpb-v1 | 26 / 11 / +₹4.09L / 1.53 / −₹1.48L | 5 / 1 / +₹2.42L | 3 / 1 / +₹4.62L |

- **zbf-v1: all of the profit is expiry-day 0DTE trend trades.** On the test days that is 10 trades, +₹17.0L, PF 10.35.
  - The best two: 13 Oct NIFTY CE 31→156, +₹8.71L, and 8 Oct SENSEX PE 140→456, +₹8.37L.
  - The other 47 test trades lost −₹2.15L, PF 0.78.
  - **The level-back + OI-reversed exit fired 51 times and won once.** Every trade held to 15:15 won (15 of 15).
- **qpb-v1:** one day (15 Sep, +₹5.54L) carries the test net; without it −₹1.44L. The range-back exit won 0 of 10.
- **Both FAIL as registered:** three test days each fell below −₹1.1L.
  - zbf-v1's expiry-day profit is the trend rider's ground already, and in the live set it would compete for the same capital.
  - Not deployed; no bar-day run (the user asked for tick days only).

### Stop and exit variants (A-133 to A-135; user 18 Oct: "run all 3 stop replays", "also run the target 2 ATR exit on tick days")

All runs alone on the 27 tick days. The figures are for the 20 test days. Script `.local/zq2/split.py` splits expiry-day 0DTE trades from the rest.

| Version | Stop / exit | Trades / wins | Net | PF | Days < −₹1.1L | Expiry-day 0DTE | Other trades | Without best 2 days |
|---|---|---|---|---|---|---|---|---|
| zbf-v1 | level back + OI reversed / 15:15 | 57 / 13 | +₹14.86L | 2.30 | 3 | +₹17.01L | −₹2.15L (0.78) | −₹2.22L |
| zbf-v2 | level back / 15:15 | 75 / 16 | +₹18.53L | 3.13 | 1 | +₹17.92L | +₹0.60L (1.08) | +₹1.45L |
| zbf-v3 | 1 ATR / 15:15 | 47 / 12 | +₹10.21L | 1.81 | 4 | +₹16.27L | −₹6.06L (0.39) | −₹6.87L |
| zbf-v4 | level back + OI reversed / 2 ATR target | 77 / 24 | −₹4.51L | 0.74 | 3 | +₹2.98L | −₹7.49L (0.43) | −₹6.57L |
| qpb-v1 | range back + OI reversed / 15:15 | 26 / 11 | +₹4.09L | 1.53 | 3 | +₹1.66L | +₹2.43L (1.47) | −₹4.24L |
| qpb-v2 | range back / 15:15 | 26 / 11 | +₹4.49L | 1.62 | 3 | +₹1.66L | +₹2.83L (1.59) | −₹3.84L |
| qpb-v3 | 1 ATR / 15:15 | 29 / 7 | +₹3.36L | 1.43 | 2 | +₹2.47L | +₹0.89L (1.17) | −₹5.46L |
| qpb-v4 | range back + OI reversed / 2 ATR target | 30 / 19 | +₹5.83L | 2.04 | 1 | +₹2.13L | +₹3.69L (1.94) | +₹2.25L |

- **All eight FAIL as registered.** Each has at least one test day below −₹1.1L, and zbf-v4's net is negative. Nothing is deployed.
- **zbf needs to hold to 15:15.** A target removes its expiry-day winners.
  - Best stop: back inside the level, without the OI condition.
  - Outside expiry days, its best version only breaks even.
- **qpb-v4 is the most even result** of the eight.
  - 25 of 25 targets won; 4 of 5 held-out trades won.
  - Positive both on and off expiry days, and positive without its best two days.
  - Worst day: 29 Sep, −₹1,18,557.
  - Its entry was loosened after looking at these same days, so this is in sample.

### zbf-v5, the four fixes (A-136; user 18 Oct: "build all four fixes and replay on tick days")

zbf-v5 is zbf-v2 with four changes:
1. A 3-close confirmation window.
2. Time-of-day RVOL ≥ 2.
3. Reclaimed PDL and lost PDH count as breaks.
4. Profit protection: armed at +1 ATR; then exit on 3 closes of OI flow against the trade, or a 1.5 ATR giveback.

I chose the volume and protection settings before the run; they were not tuned afterwards.

| 20 test days | zbf-v2 | zbf-v5 |
|---|---|---|
| Trades / wins | 75 / 16 | 104 / 27 |
| Net / PF | +₹18.53L / 3.13 | +₹5.33L / 1.31 |
| Expiry-day 0DTE trades | +₹17.92L | +₹1.06L |
| Other trades (PF) | +₹0.60L (1.08) | +₹4.27L (1.43) |
| Days < −₹1.1L | 1 | 1 (5 Oct −₹1.46L) |
| Without the best 2 days | +₹1.45L | +₹0.24L |

- **The fixes made the non-expiry trades pay** (PF 1.43). The protection exits won 36 of 44 trades.
- **The same protection exits cut the expiry-day runners** that made v2's profit.
- **The level-back stop fired 94 times and won once.** With the confirmation window and reclaimed levels, the rule takes about 5 trades a day.
- **2–8 Sep had no RVOL** (fewer than 5 prior sessions of futures volume), so no trades were possible on those days.
- **FAIL as registered.** Not deployed.

### zbf-v6: v5 without profit protection on expiry days (A-137)

| 20 test days | v2 | v5 | v6 |
|---|---|---|---|
| Trades / wins | 75 / 16 | 104 / 27 | 92 / 23 |
| Net / PF | +₹18.53L / 3.13 | +₹5.33L / 1.31 | +₹11.39L / 1.69 |
| Expiry-day 0DTE (PF) | +₹17.92L (20.0) | +₹1.06L (1.15) | +₹6.81L (1.94) |
| Other trades (PF) | +₹0.60L (1.08) | +₹4.27L (1.43) | +₹4.57L (1.50) |
| Days < −₹1.1L | 1 | 1 | 3 |
| Without the best 2 days | +₹1.45L | +₹0.24L | −₹1.98L |

**Holding on expiry days helps on 8 Oct and hurts on 13 Oct:**
- **8 Oct:** the SENSEX put held to 15:15 made +₹5.95L.
- **13 Oct:** the NIFTY 09:34 call was held through a reversal to the premium stop, −₹1.13L.
  - The daily loss limit then refused the 13:34 call.
  - That 13:34 call was v2's +₹8.71L trade.

FAIL; not deployed.

### Why zbf-v6 missed the marked entries and exits (user's charts, 18 Oct; data `.local/zbf-v6-marks/` in the main repo)

Per-minute engine conditions (trade.decision), futures 1-minute volume, ATM ±3 OI changes (zt) at each mark:
- **1 Oct NIFTY 12:08 / 12:11 PE: signalled (break + flow + RVOL) and REJECTED by the kill switch.** Three SENSEX 72400 PE
  whipsaws (10:16, 10:27, 10:50: −₹15K, −₹68K, −₹59K) plus the 10:02 NIFTY CE had reached the ₹1.1L daily limit by 10:51.
- **5 Oct NIFTY 10:26 PE: signalled (PE OI −63L/min, RVOL 11×, fut OI +19K) and REJECTED for capital** (SENSEX PE took the
  slot the same minute). The earlier PDH / prior-ORH / ORH loss at 09:57–10:02 could not fire: a 09:50 wobble
  (22,612 → 22,610) had already consumed the PDH down-break (one break per level per day).
- **6 Oct NIFTY (expiry) 09:41 ORH/PDH break: flow yes, RVOL no; 09:43 RVOL yes, price back below.** Expiry-day OI builds on
  both sides (09:19: CE +156L, PE +163L in 3 min), so "one side building, the other unwinding" rarely holds.
- **6 Oct SENSEX 09:40 / 09:49 breaks: no volume.** SENSEX futures trade 0–400 contracts a minute; OI changes ≈ 0.
- **15 Oct NIFTY 09:32 / 09:34 ORH break: flow yes, RVOL 0.4–1.7.** The surge (RVOL 5–8×) came 09:54–10:00, 20 minutes
  after the 3-close window, with the index 80 points higher.
- **13 Oct NIFTY (expiry) 09:34 CE, not exited at the 10:00–10:20 stall:** v6 has no protection on expiry days by design.
  The stall's data: call writing resumed (CE OI +21L 10:02, +23L 10:10), futures OI unwinding every minute 10:00–10:11,
  RVOL 0.3–0.8, flow_down 10:12–10:23. Premium 62 → 109 (09:58) → 85 (10:20) → 41 (11:26 premium stop).
- RVOL ≥ 2 is nearly always true on SENSEX expiry days (271 of 385 minutes on 1 Oct): not a filter there.

### A-138: zbf unblocked (v7 NIFTY only, v8 half budget)

| 20 test days | v5 | v7 (NIFTY only) | v8 (½ budget, both) |
|---|---|---|---|
| Capital / kill-switch refusals (27 days) | 72 / 13 | 0 / 6 | 0 / 4 |
| Trades / wins | 104 / 27 | 72 / 22 | 148 / 36 |
| Net / PF | +₹5.33L / 1.31 | +₹4.85L / 1.43 | +₹1.57L / 1.12 |
| Expiry-day 0DTE (PF) | +₹1.06L (1.15) | +₹2.44L (1.74) | −₹1.35L (0.78) |
| Other trades (PF) | +₹4.27L (1.43) | +₹2.40L (1.30) | +₹2.92L (1.43) |
| Days < −₹1.1L | 1 | 2 | 0 |
| Without the best 2 days | +₹0.24L | +₹0.46L | −₹1.82L |

The refusals were real, but removing them did not add money: the formerly refused trades lost on balance. The two
marked entries were taken (1 Oct 12:08 PE +₹4.26L; 13 Oct 13:34 CE +₹0.93L) and were outweighed by the SENSEX
whipsaws that now filled (1 Oct −₹1.08L, 15 Oct −₹0.72L in v8). Both FAIL; not deployed.

### Out of sample on the 169 bar days (A-142 to A-144, 18 Oct evening)

| 23 Jan–31 Aug (148 days) | Net | PF Jan–May / Jun–Aug | Days < −₹1.1L | Non-expiry PF | Months positive | Verdict |
|---|---|---|---|---|---|---|
| qpb-v4 (A-142) | −₹4.07L | 0.81 / 0.95 | 2 | 0.78 / 0.80 | – | FAIL |
| zbf-v2 (A-143) | +₹13.69L | 1.35 / 1.40 | 1 | 1.60 / 1.58 | 6 of 8 | FAIL (one day) |
| **zbf-v7 (A-144, v5 on NIFTY only)** | **+₹16.89L** | **1.59 / 1.53** | **0** | **1.90 / 1.49** | **8 of 8** | **PASS** |

zbf-v7 is the only rule from 17–18 Oct to pass on days it never saw.

**Caveats:**
- **Bar fills are coarse.** On the 16 shared September days, bars made +₹5.39L against the ticks' +₹9.30L, and only 26 of 66 tick entries coincide.
- **Its tick-day test (A-138a) had 2 bad days.** Those are the days the v5 fixes were shaped on.
- **It ran alone.** Next: a bar run inside the live set (capital crowding, kill switch), then a forward shadow.
