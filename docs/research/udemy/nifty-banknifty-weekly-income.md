# Udemy — "Nifty & BankNifty: Options Future Trading for Weekly Income" — study notes

Personal research notes (24 Oct 2026) from the course captions on the user's Udemy plan; own words; rule-bearing
sentences were extracted from long backtest walk-throughs (≈ 350K characters of captions), the rule lectures were read
in full. Course text is not copied.

- Course id 6412397 · 27 lectures, ~8 h · rating 4.3 (314). Base URL
  `https://www.udemy.com/course/nifty-bank-nifty-options-future-trading/learn/lecture/<id>`
- Option **selling** course (NIFTY first; BankNifty/SENSEX need wider wings). The instructor's main system ("Goldmine",
  claimed 44.7 % ROI in July 2026 on ₹20–24 L, 40 lots) is **not in the course** — it is sold on his own website. Only the
  generic neutral strategies below are taught here.

## Iron condor basics (L12–L15)
- Claim: the Indian market is range-bound 70–75 % of the time; sell OTM call + OTM put, buy further wings → defined risk.
- Example (NIFTY ~22,400): sell ±100 points, profit-probability ~68 %, max profit ~8 % of margin; adding wings 250
  points out cut margin from ~₹78K to ~₹30K per lot, max profit ~12.5 %, max loss ~8.5 %.
- Width by **ATR**: short strikes about **2 × daily ATR** away (example ATR 200 → 400 points out each side); reward/risk
  ~1:3, POP ~57 %.
- Filters: **IV** (if India VIX / IV ≤ ~10 premiums are too small; > 25 too risky); avoid iron condors on BankNifty and
  SENSEX (too volatile, e.g. 5,000 points in 22 sessions) or widen the wings a lot.

## L21–L24 · Iron fly + adjustment
- Sell ATM call + ATM put, buy wings a fixed distance away (his rule: **500 points** on NIFTY). Example: credit 265 + 207
  = ~450 points; max profit ~21 % vs max loss ~8 % of margin, but POP only ~35 %.
- Conditions: **India VIX < 15**, NIFTY daily **ATR < 250** (BankNifty < 550–600).
- Entry **10:30–10:45** (after the opening volatility). Exit at **+3–4 % of total capital**, or at **breakeven**.
- **Adjustment (L23):** when NIFTY comes within **0.2 %** of a breakeven (BankNifty/SENSEX **0.3 %**), close the threatened
  short leg and **sell a 0.2-delta option on that side, hedged**; do not close the whole position.

## L16–L17 · Delta-neutral iron condor (multi-day)
- Enter **10:30–11:00**; sell the **0.2-delta** call and put; hedge with cheap far options; exit at **+2 % of capital** or
  at **breakeven**; re-enter the next day after an exit.

## L18–L19 · "Daily income" (intraday, delta by day of week)
- Skip the day if **India VIX > 25** (3–4 days a year).
- Sell call and put at a **delta that depends on the day** (designed for the old Thursday NIFTY expiry):
  **Friday 0.35, Monday 0.30, Tuesday 0.25, Wednesday 0.20–0.15, Thursday (expiry) 0.20–0.15**; hedge each side with a
  far option costing ~₹5–10.
- Entry **10:30–10:45**. Exits: **+1 to 1.5 % of total capital**, or when NIFTY reaches the position's **breakeven** on
  either side, or **15:10** at the latest (he also tested 14:10).
- L19 walk-through (a January week; the year is not stated): 7–9 lots on ₹8.5–14 L margin; most days closed at +1 % between 11:45 and 14:20.

## L25 · The instructor's own 2024 comparison (honest numbers from his sheet)
- Delta-neutral (0.2 delta, +2 % or breakeven, multi-day): **+16.2 % for 2024** on his capital (he quotes ₹6.83 L);
  January +8.2 %; **June 2024 (election-result gap, NIFTY −800 at the open) −17.8 %**.
- Daily income, **14:10 exit: −0.7 % for 2024**; **15:10 exit: +18.3 %**. Monthly swings from +17 % to −29 % (June).
- He personally lost **₹32 L** on 4 Jun 2024 (a Friday +₹6.8 L position became −₹56 L intraday on Monday) — "ate up
  four to five months of profit".
- Takeaway: even the instructor's backtest shows **thin, regime-dependent** results with **event gaps** as the
  dominant risk; consistent with our A-193 (short strangle FAIL in the new regime) and A-196 (weekly strangle, CI
  includes 0, drawdowns ₹4.4–5.4 L).

## Rules worth testing (my extraction)
| # | Rule | Precise version for a test | Our evidence |
|---|---|---|---|
| W1 | Daily income: intraday hedged strangle by days-to-expiry delta, 10:30 entry, +1 % capital / breakeven / 15:10 exits, VIX ≤ 25 | NIFTY bars Oct 2024–Oct 2026; delta from Black-Scholes on each option's own implied vol; DTE ≥ 4 → 0.35, 3 → 0.30, 2 → 0.25, 1 and 0 → 0.175; wings = strikes nearest ₹5 | **A-204 FAIL**: 478 days, −₹133 per ₹1 L a day (65 % wins), negative in all three periods; six breakeven days = most of the loss |
| W2 | Iron fly with the 0.2 % breakeven adjustment, VIX < 15, ATR < 250 | Multi-day; needs a position model like A-196 | A-177 iron fly FAIL (different rules) |
| W3 | Delta-neutral 0.2-delta condor, +2 % or breakeven, multi-day | Close to A-196 | A-196 CI includes 0 |
