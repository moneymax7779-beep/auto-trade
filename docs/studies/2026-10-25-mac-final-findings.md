# ma-cross: when to enter, when to exit — what the data supports (25 Oct 2026)

You asked for the best outcome on ma-cross: when to enter, when to exit, what data supports it, and brutal honesty. This
is the result of A-220 to A-224. All of them were registered before running, then tested with the research model (96 %
match to the engine's trades) and with engine replays on two years of bars and the 27 tick days.

## The honest bottom line

1. **ma-cross has no edge we can prove outside September–October 2026.**

   | Sample | mac-v1 result |
   |---|---|
   | Bars, Oct 2024–Jun 2025 | −₹5.26 L (PF 0.88) |
   | Bars, Jul–Dec 2025 | −₹23K (PF 0.99) |
   | Bars, 2026 | +₹5.85 L (PF 1.14) |
   | 27 tick days (Sep–Oct 2026), in the live set | +₹11.29 L |

   The bar runs are mac alone at engine fills. The tick days are the stretch the live set was chosen on, and they were
   an unusually trending period. Over two years it is roughly break-even before realistic option costs, and negative
   after them (the lab at ±0.75 % a side loses in every period).
2. **Its exit is not the problem.** The 60-minute exit is supported by data:
   - Trades that reach target typically do so in 42–55 minutes.
   - After 60 minutes the index's average further move is **+0.07R and −0.01R**, while the option keeps losing time
     value.
   - No market-data exit beat it out of sample: 13 tried, plus the conditional version in the engine (A-221).
3. **Its losses come from entering in chop.**
   - The swing stops cost the most: −₹77.7 L over two years of engine bars against +₹68.8 L from targets.
   - None of the stop or entry rules we could build improved this out of sample: wider and ATR stops, chop filters, the
     10:15 warm-up entry, and the anatomy filters (in-sample +₹28 L, out of sample −₹5.6 L).
4. **So the best outcome available today is not a better ma-cross rule. It's a better decision about where ma-cross
   runs and with how much money.** See the last section.

## What was tested today

| ID | Question | Result |
|---|---|---|
| A-220 | 12 exits instead of the fixed 60 min (structure trail, EMA20, VWAP, chandelier ATR, momentum fade, stall, volatility-scaled time, level targets, premium trail, combined) | None better out of sample; most worse everywhere |
| A-220b | Is 60 arbitrary? (sweep 20–120 min, cost check, what happened after minute 60) | The best holding time flips by regime: 2024–25 wanted longer, 2026 wanted about 60. 60 is the most robust choice |
| A-220c / A-221 | Conditional time stop: keep past 60 min only if ≥ +0.5R and on the right side of VWAP and EMA20, then trail | Lab: better in all three periods (not significant). **Engine: better in 2024–25 (+₹4.5 L), worse in 2026 (−₹97K) and on the tick days (−₹54K). FAIL** |
| A-222 | Stops (ATR, buffer), chop filters, the 10:15 warm-up entry, a combination | All FAIL. Wider stops are worse; the warm-up entries lose ₹11 L |
| A-223 | Trade anatomy (descriptive) | See below |
| A-224 | mac-v8: five entry filters from the anatomy, plus a data-based stop | Lab in-sample +₹28 L. **Engine on the unseen 7–23 Oct days: −₹5.62 L. FAIL** (it removed winners) |

## What the trade anatomy told us (A-223), and how far to trust it

These come from about 1,350 trades; "both periods" means 2024–25 and Jan–Sep 2026.

- **How winners behave.** 90 % of trades that reach target never go more than 0.6–0.73R against first. Only 1 in 9–10
  stopped trades had been +1R in profit first, so a breakeven move would save little.
- **Time.** A median of 42–55 minutes to target. After 60 minutes there is no average edge left: this is the data basis
  for the time exit.
- **Exit quality.** Winners keep 83–86 % of the best price they reached. The exits are already efficient.
- **The system's behaviour ("psychology").** No revenge-trading problem: trades after a loss did slightly better than
  average. The first trade of the day did slightly worse.
- **Context splits that looked consistent in both periods:**
  - worse: lunch-hour entries, entries against a 0.3 % opening gap, entries more than 1 ATR on the wrong side of VWAP,
    entries after a mild counter-drift, stops closer than 1.25 ATR;
  - better: entries 11:00–12:00.

  **They failed on fresh data (A-224).** Splitting 1,350 trades into about 40 buckets produces patterns by chance even
  when two periods agree. A-079 (the VWAP-side filter) had already shown this on the tick days. These are descriptions of
  the past, not rules.

## Recommendation

1. **Keep mac-v1 unchanged in the PAPER live set**, with the 60-minute exit. Nothing tested beats it out of sample.
   Every candidate (mac-v7, mac-v8) failed and stays a research config.
2. **Reconsider ma-cross in the real-money pilot.** ZERODHA-1's DRY_RUN subscribes to ecr and ma-cross with a fixed
   ₹2 L allocation. The evidence for ecr is much stronger than for ma-cross:
   - ecr: +₹4.68 L over 93 trades, PF 1.87, positive in both years (A-199);
   - ma-cross: PF 1.05 over 1,095 trades, −₹8.0 L in 2025, positive only in 2026.

   My recommendation is **ecr only for the first LIVE lots**, with ma-cross kept in PAPER and DRY_RUN until its forward
   record shows an edge. That's your call; nothing has been changed.
3. **Stop tuning ma-cross's rules.** The next useful step is to let the forward record (GCP PAPER and DRY_RUN) accumulate
   and judge ma-cross on 20–30 new sessions. Any further ma-cross idea should need a new kind of input, not another split
   of the same price data.
4. **Costs matter as much as rules.** ma-cross's average trade is small, so option spread and slippage decide whether it
   is profitable: the lab flips from −₹12.6 L to +₹0.6 L in 2026 between ±0.75 % and ±0.25 % a side. In live trading,
   limit orders and liquid strikes matter more for ma-cross than any exit tweak.

## Where everything is

- **Ledger:** A-220, A-220b, A-220c, A-221, A-222, A-223, A-224 in `docs/EXPERIMENT-LEDGER.md`.
- **Scripts:** `docs/studies/2026-10-25-mac-exit-lab.py`, `…-mac-entry-lab.py`, `…-mac-anatomy.py`, `…-a221-report.py`.
- **Reports:** `.local/a220/`, `.local/a221/`, `.local/a222-report.md`, `.local/a223/`, `.local/a224*`.
- **Research configs (not live):** `ma-cross.v7.yaml` (conditional time stop), `ma-cross.v8.yaml` (entry filters).
- **Code (off unless their config keys are set, so behaviour is unchanged):** SetupStrategy's conditional time stop and
  MaCross's filter keys.
- Nothing is committed, pushed or deployed.
