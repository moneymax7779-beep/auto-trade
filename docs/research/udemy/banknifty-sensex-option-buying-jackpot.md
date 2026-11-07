# Udemy — "BankNifty/Sensex Option Buying Jackpot Setup" — detailed study notes

Personal research notes (24 Oct 2026), read from the course's captions and article lectures on the user's Udemy plan.
Own words, full detail (timeframes, thresholds, stops, targets, the instructor's examples), with lecture links. Course text
is not copied.

- Course id 5092180 · 23 lectures · rating 4.2 (168 ratings). Instructor initials "SJ" (Sandip Jha).
- Base URL: `https://www.udemy.com/course/banknifty-option-buying-jackpot/learn/lecture/<id>`
- Index-only course (BankNifty, and since the Oct 2024 SEBI change that ended BankNifty weekly expiries, **SENSEX**;
  later live examples on **NIFTY**). Option **buying** only, intraday.
- Not read in detail: L1–L5 (option basics, platform demo), L18 (49-min trading psychology talk), L20 (bonus).

---

## L7–L8 · Notes
- Success needs three pillars: setup, risk management, psychology.
- L8: after SEBI's Oct 2024 rules stopped BankNifty weekly expiries, the instructor moved the same setups to **SENSEX**:
  both are 5-digit indices with the same round-number spacing and similar point moves.

## L9 · Jackpot Setup I — "early morning setup" (first 10-minute candle) · [lecture 35860050](https://www.udemy.com/course/banknifty-option-buying-jackpot/learn/lecture/35860050)
- Chart: **10-minute spot index chart** for the signal; **1-minute option chart** for execution.
- Condition: the **first 10-minute candle (09:15–09:25)** measured **body to body** (open to close) is **≥ 120 points on
  BankNifty** (≈ 0.3 % at 40,000) — for **SENSEX "near 150+ points"** (his examples 281 and 163 points).
- Trigger: **as soon as the low of that candle breaks**, buy an **ATM put** (no candle close needed). He calls it "only the
  selling setup" (bearish); every example is a put on a low break. Days where the candle was < 120 points are skipped
  even if the market then fell.
- Risk: **stop 40 option points** on a ~₹300–360 BankNifty put (≈ 11–13 %); **70 option points on SENSEX**.
- Target: **50–100 option points**; move the stop to **cost** once in profit, then **trail in 20–30-point steps**; or exit
  when a swing high on the index breaks.
- Examples: BankNifty "23rd": candle ~200 points, 40,000 PE bought ~360 at 09:25, trailed out at +130–140 option points.
  SENSEX 21 Oct (2024): first candle 163 points body; low broke at 09:27 → entry 09:28 in the **81,600 PE**, stop 70, exit
  ~10:02 when a swing high broke; he says ~₹2,800 per lot (₹28K on ten lots).
- He admits stop-outs happen ("rare"); no statistics are given.

## L10–L11 · Jackpot Setup II — "KITE break" scalping setup · [lecture 35860056](https://www.udemy.com/course/banknifty-option-buying-jackpot/learn/lecture/35860056)
- Chart: **3-minute spot chart**.
- Conditions:
  1. Preferably a **gap-up open** — the larger the gap and the **less of it filled**, the bigger the expected move ("gap
     is directly proportional to the reward"). If the first candle fills the gap, expect a weaker move.
  2. The **first 3-minute candle is a hammer / bullish-rejection candle** (long lower wick).
  3. Wait for the **first small bearish candle** (2nd, 3rd, … candle) **while the first candle's high stays unbroken**.
- Entry: **buy a put the moment the low of that first bearish candle breaks** (no close needed).
- Stop: the **swing high** formed by the bearish candle and its neighbours (often "one candle" of risk).
- Management: once the index moves **20–25 points** in favour, move the stop to cost; re-entry allowed after a
  cost-to-cost exit. Target **40–100 index points**; book half and trail the rest by swing highs.
- Reverse trade: after the first trade, if the **hammer's high later breaks on a candle close**, buy a call with the last
  swing low as the stop (an inside bar at the breakout is "double confirmation").
- He says setups work "season to season" — one setup works for a period, then stops; he chooses which setup is
  "active" by discretion.

## L12 · Jackpot Setup III — "Double KITE Inside Failure" (DKIF), bullish · [lecture 35860062](https://www.udemy.com/course/banknifty-option-buying-jackpot/learn/lecture/35860062)
- Chart: **3-minute spot chart**.
- Conditions:
  1. The **first two 3-minute candles are both hammers** (or dojis with a long lower wick and a small upper wick) —
     buyers pressing. With two hammers, **do not take the KITE short**; "60 % chance the day goes up".
  2. Then a **bearish inside-bar formation**: a big **black (bearish) candle** followed by a **green candle whose body is
     inside the black candle's body**.
- Entry: **buy a call when the high of the inside (green) candle breaks** — the bearish inside bar "fails". This enters
  near the day's low, before the hammers' high breaks (where most traders buy).
- Stop: the low of the formation. Target **at least 50 points**; book at 50 if the market turns, otherwise trail; exit
  on consolidation.

## L13–L17 · Round-number setups (1-minute chart, for full-time traders) · [lecture 35862118](https://www.udemy.com/course/banknifty-option-buying-jackpot/learn/lecture/35862118)
- **Round numbers** (L13): BankNifty/SENSEX levels that are **multiples of 500 / 1,000** (37,000, 37,500 …). For NIFTY
  his later examples use NIFTY round numbers (he does not define them; the natural analogue is 100 / 50).
- **Why** (L14): option writers cluster their short strikes at round numbers and keep stops **100–250 points** beyond
  them; when price holds near a writer's strike the writer is under pressure, and a break there triggers stop-loss
  buying/selling → fast moves.
- Chart: **1-minute spot and 1-minute option**. Avoid expiry days if you have less than a year's experience.
- **Setup I — trendline + round number (four patterns)** (L15):
  1. Price trends into a round number along a trendline, breaks it, pulls back to the trendline, breaks again → enter
     long only **after a pullback** with an engulfing or hammer candle (never on the first breakout).
  2. Price **holds support at the round number** (W-shape), then breaks out → enter long; no retest needed (the round
     number is behind you).
  3. Price **fails at the round number** as resistance → short only **after a breakdown and retest** (selling near a
     round number is "mostly fake" first).
  4. **Zig-zag / flag** at the round number, then the pole breaks → enter in the break direction.
  - Stop **40 option points** (some use 50–60). Sharper V/edges into the level = higher odds.
- **Setup II — range break near a round number** (L16): a range forms within ~30–40 points of a round number; enter
  on **breakout + retest** (without retest is riskier). Examples: 138-point and 500-point moves.
- **Setup III — support/resistance break and retest** (L17): a round number that held as support (or resistance) for a
  long time breaks and is retested from the other side → enter; stop 30–40 points; aim for **1:2 to 1:3**.

## L19 · India VIX for option buyers · [lecture 38558072](https://www.udemy.com/course/banknifty-option-buying-jackpot/learn/lecture/38558072)
- **VIX 15–20: best for option buyers** (momentum; "no-news" normal range).
- **VIX < 15: small moves**; buyers should take small losses and protect capital; scalping 10–15 points works;
  low-VIX **expiry days** can still give sharp late "gamma" moves.
- **VIX > 20: avoid buying** (premiums collapse fast, e.g. 300 → 150–200); better for sellers. Spikes above 20 come with
  news.

## L21–L23 · Live NIFTY trades (articles)
- 8 Aug 2025 "130+ points" (article mostly an image — no rule text), **11 Aug 2025**: NIFTY **flag at a round number**,
  breakout **above** the flag, **retest**, entry after the retest, stop below the flag / round number, held to **15:00**;
  ₹50K+ claimed.
- **24 Apr 2026**: bearish flag at a NIFTY round number, breakdown, held to **1:6 R:R**, exit ~13:43; 140+ points, ₹53K+
  claimed. (The article's wording mixes "uptrend" into a bearish trade — template text.)

---

## Rules worth testing (my extraction)
| # | Rule | Precise version for a test | Our evidence |
|---|---|---|---|
| J1 | First 10-min candle ≥ threshold → buy ATM PE on break of its low | SENSEX body ≥ 150 pts (≈ 0.19 %); NIFTY ≥ 0.19 %/0.3 % of spot; entry at the 1-min bar that trades below the 09:15–09:25 low; option stop −13 % (≈ 40/300), move to cost at +40 pts, trail 25-pt steps; flat 15:15. Variants: red candle only; mirror (green, high break → CE) | **A-200 FAIL**: 233 trades Oct 2024–Sep 2026, −2.1 % per trade, negative in all three periods |
| J2 | KITE break: gap-up + first 3-min hammer + first bearish candle low break → PE | Gap ≥ 0.2 %, hammer = lower wick ≥ 2× body and ≥ 60 % of range, first-candle high unbroken; stop = swing high; to cost at +25 BankNifty-equivalent pts; exit 40–100 pts or trail | **A-201 INSUFFICIENT**: 16 trades in 2 years, +5.2 % mean, CI −8 % to +23 % |
| J3 | DKIF: two opening 3-min hammers + bearish inside bar → CE on inside-bar high break | As defined; stop = formation low; target 50 pts min | **A-202**: 15 trades, 0 winners (−6.9 % mean) |
| J4 | Round-number break + retest | Round numbers SENSEX 500, NIFTY 100; range within 0.05 % of the level; break + retest within N minutes → entry | **not tested**; level/zone breaks generally FAIL here (A-131..A-145, swing-zone study) |
| J5 | India VIX 15–20 filter for option buying (avoid < 15 and > 20) | Day filter | **A-203**: on A-200's trades the 15–20 band was the worst (−4.1 % vs −0.9 % below 15) |
