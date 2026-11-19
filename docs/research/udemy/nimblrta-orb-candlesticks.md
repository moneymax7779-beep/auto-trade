# Udemy — "Opening Range Breakout Strategy using Candlesticks" (NimblrTA) — strategy documentation

Personal research notes from the user's Udemy subscription, read 19 Nov 2026. Purpose: can anything here improve
our opening-range-breakout work (orb-v1 and its relatives)? **Nothing here is registered or tested.**

- **Course:**
  - Id 2290263, by Anurag Chandak ("ORB Academy").
  - It teaches the rules of a Twitter trader known as "Nimblr" (handle Deishma).
  - 20 lectures, about 2 h. Rating 4.43 (178). Updated 26 Apr 2025.
- **Lecture link:** `https://www.udemy.com/course/nimblrta/learn/lecture/<id>`.
- **How it was read:**
  - All 20 lectures were read end to end from the course's English captions.
  - 7 video frames were checked; they are kept in [`nimblrta-orb/`](nimblrta-orb/) for private reference only,
    do not publish them.
  - The notes are in my own words; no course text is copied.
- **Instruments:** NSE cash stocks (Kotak Bank, Wipro, Airtel, L&T, SBI, Infratel and others), Jan–Mar 2019,
  on 5-minute charts.
- **Indicators:** sold separately, as a TradingView invite-only script and a MetaTrader "ORB screener".

---

## 0. Verdict first (brutally honest)

1. **The breakout entry is fully mechanical and better specified than most ORB material:**
   - a two-step breakout made of strength candles;
   - a 30-minute CCI momentum gate;
   - an EMA 5 / 21 / 34 stack;
   - two stop rules and a pyramid rule.

   Every condition is a number, so it can be coded as written. That is the course's real value.
2. **The evidence is one vendor slide** (fig. 07). It shows the course owners' own alert service, 1 Sep 2018 –
   8 Apr 2019, on stocks:

   | Figure | Value |
   |---|---|
   | Trades | 1,264 |
   | Wins | 871 (69 %) |
   | Average win / average loss | ₹2,210 / ₹1,776 (ratio 1.24) |
   | "Kelly" | 44 % |

   That implies about +₹970 per trade. But:
   - it is not independent;
   - it covers only 7 months, on stocks, not indices or options;
   - costs, slippage and how stops were filled are not stated;
   - the percentage gains (+22 % / −18 %) can only be on margin, not on price.

   It is a claim, not evidence.
3. **Two parts must be thrown out:**
   - **"Time cycles"** (L15): moon transits by nakshatra lords and sub-lords, plus "void of course moon". This is
     astrology. The closing lecture (L19) is an advert for a paid, invite-only, 6-month "lunar master course"
     claiming "95 %+ accurate" cycles.
   - **The target table is keyed to brokers' cash-margin percentages** (fig. 03). That is a crude volatility
     proxy, and for NIFTY it gives targets of 1.6–2.6 % a day, wider than a normal day's range.
4. **The prior here is poor.** The opening-range breakout family has already been run many ways
   (A-082, A-173, A-409, A-410, A-414, plus orc, ocb and srb):
   - It makes money on our 2026 tick days and **loses on 2024–25 Upstox bars in every construction**.
   - The false first breaks cost more than the trends pay: win rates 17–35 %, median trade −8 to −14 % of premium
     in A-414.
   - This course's filters (CCI, EMA stack, two strength candles) are a different attempt at the same problem,
     **separating accepted breaks from false starts**. They are untested here (CCI appears nowhere in the ledger),
     so they deserve one honest test, not optimism.
5. **The one idea that targets our known weakness** is the **"DTR left" filter**: skip a breakout when the day has
   already used up most of its normal range. Our ORB losses cluster in false starts and late breaks. This filter is
   cheap to measure and has never been tested as an entry filter. A-314's "range so far" was a different thing: a
   range-day label, which failed.

---

## 1. The rule set as taught

### 1.1 Opening range (L4, fig. 01–02)
- Base chart: **5-minute**. The OR is the **first three 5-minute candles (09:15–09:30)**.
- **Wick lines:** ORBH = highest high of the three; ORBL = lowest low.
- **Body lines (pink dotted):** the **highest open** and the **lowest close** of the three candles. Note: the course
  says highest open and lowest close literally, not "highest body top / lowest body bottom".

### 1.2 Strength candle (L5)
- **Strength (decisive) candle:** body ÷ range > 50 %. Anything else is "indecisive".
- Worked examples: a body of 3.00 on a range of 4.65 = 64.5 % (strength); 8.70 on 11.55 = 75.3 % (strength).
- Charts mark strength candles with a star.

### 1.3 Breakout candles (L6, L7)
**Buy** (sell is the mirror):
1. **Breakout candle #1:**
   - the first strength candle that **closes above the upper body line**;
   - it must be **the highest candle of the day so far** (highest high and highest close).
2. **Breakout candle #2:**
   - a **later** strength candle whose **high and close are above ORBH**;
   - it must have **the highest high and highest close of the day** at its close.
   - It need not follow #1 immediately: indecisive candles may come in between, provided **#1's low is not broken**.
   - A strength candle that does not make a new day extreme does not count; wait for one that does. In the L&T
     example, the qualifying #2 came much later in the day.

### 1.4 Momentum gates (L8, L9)
- **CCI(34) on the 30-minute chart > +100** (sell: < −100).
  - If the two breakout candles are in place but the CCI is not yet beyond ±100, **wait**. The trade is taken on
    the first 5-minute close where it is (Wipro, L&T examples).
  - The course calls CCI "the heart of the system".
- **EMA 5 > EMA 21 > EMA 34 on the 5-minute chart** (sell: 5 < 21 < 34). The course calls the buy alignment TGC
  and the sell alignment TDC.

### 1.5 Entry (L10)
- **Enter on the close of the 5-minute candle** at which all of the following hold: the OR, both breakout candles,
  the CCI gate and the EMA stack.
- In the frames, the entries fall at about 09:35–10:00 (fig. 01, 02). This is an early-morning system.

### 1.6 Pyramid (L11)
- **Conditions:** an open position, then a pullback where **CCI(8, 5-minute) < −100** (oversold), while:
  - CCI(34, 30-minute) is still > +100;
  - the EMAs are still stacked.
- **Entry:** add on the close of a candle with a **higher low / higher close** than the previous candle.
- Sell: CCI(8) > +100 with a lower high.
- Examples show 2 adds per day.

### 1.7 Exits (L12–L14)

| Exit | Rule |
|---|---|
| **Targets (DTR)** | A "daily trading range" percentage looked up from the stock's cash-margin percentage (fig. 03). Example: margin 0.25 % → T1 1.62 %, T2 2.23 %, T3 2.62 %. **Buy targets = the day's LOW × (1 + DTR %)**; sell targets = the day's HIGH × (1 − DTR %). The expected day range is measured from the day's extreme, not from the entry |
| **HTF stop** (L13, fig. 04) | Find the most recent 30-minute candle that is **indecisive** (body ÷ range < 50 %), skipping strength candles. It can be the previous day's last 30-minute bar (15:15–15:30, only 3 five-minute bars). Stop = **0.234 % beyond its low** (buy) or high (sell). It trails as new indecisive 30-minute candles form. The course admits it can be deep, giving a poor reward-to-risk |
| **Dynamic stop (DSL)** (L14, fig. 05) | Buy: exit when **CCI(34, 30-minute) < +110 AND CCI(5-minute) < −60**. Sell: CCI(34, 30-minute) > −110 AND CCI(5-minute) > +60. The narration says CCI 34 on 5 minutes; the slide says **CCI 8** on 5 minutes, a contradiction to resolve before coding. All four examples exit well before the HTF stop; one was a winner given back to scratch |

### 1.8 "DTR done / DTR left" filter (L18, fig. 06)
- The screener sheet shows, per stock, how much of the expected daily range is already used ("DTR done") and how
  much remains ("DTR left").
- **Rule:** enter only if **DTR left is at least about 1 %**.
- The screener also filters on gaps, the previous-day high / low, candle count, CCI limits and EMA settings.

### 1.9 Risk / reward and sizing (L16, L17)
- Accuracy first; judge the **effective** reward-to-risk (average win ÷ average loss), not the planned one. The
  arithmetic on the slide is muddled: it mixes up the reward-to-risk ratio and the profit factor.
- **Kelly sizing.** Example: 55 % wins with a win/loss ratio of 1.3 → about 20 %. Recommendation: at most 20 % of
  capital per trade (≤ 5 trades a day), even when Kelly says 40–50 %.

### 1.10 Discarded
- **Time cycles / void-of-course moon** (L15): astrology.
- **Level 2 lunar course** (L19): an advert.
- **TradingView / MetaTrader setup** (L3, L18): tool installation only.

---

## 2. Course rules vs our orb-v1 (A-082)

| Element | NimblrTA (this course) | orb-v1 (`config/strategy/opening-range-breakout.v1.yaml`) |
|---|---|---|
| Opening range | 09:15–09:30, wick **and body** lines | 09:15–09:30 high / low |
| Candle timeframe | 5-minute | 15-minute closes |
| Entry window | From 09:30 (entries seen at 09:35–10:00) | **10:15–14:30** |
| Breakout test | Two strength candles: #1 beyond the body line and a new day extreme, #2 beyond the wick line and a new day extreme | One 15-minute close beyond the range |
| Momentum / trend gate | CCI(34, 30-minute) beyond ±100 **and** EMA 5 / 21 / 34 stacked (5-minute) | Futures RVOL ≥ 1.5 and the opposite side's OI building |
| Stop | Last indecisive 30-minute candle ± 0.234 % (trailing); or CCI dynamic stop | The breakout candle's extreme minute close; 30 % premium emergency stop |
| Target | DTR % from the day's extreme | 2R |
| Adds | Pyramids on CCI(8, 5-minute) oversold pullbacks | None |
| Late-break guard | "DTR left" ≥ ~1 % | None |
| Result here | — | A-082: +₹5.36 L alone on 23 tick days but FAIL (worst day, held-out); in the set it crowds out etr. A-173: orb-v1 FAIL on 2024–25 bars |

The two systems differ in almost every part except the 15-minute range itself. The course's version is closer to
the pasted "ORB + EMA rider" (A-409 / A-410, FAIL on 30 of 31 days) than to orb-v1. The course adds CCI and a
strict two-candle test that neither of those had.

---

## 3. What could improve our ORB work (candidates, not registered)

Every candidate needs its own ledger row with the rule fixed before data is read. Per [[tick-days-only-replays]],
replays use own-capture tick days; bar days only with the user's go-ahead (A-414 did use 2024–26 bars for
screening at the user's request).

| ID | Candidate | What it tests | Cost | Prior / note |
|---|---|---|---|---|
| **N1** | **NimblrTA ORB as written, on the index (screen):** NIFTY + SENSEX 5-minute index candles; OR, body / wick lines, both breakout candles, CCI(34, 30-minute) ±100, EMA 5 / 21 / 34, entry on the 5-minute close; exits = DSL or HTF stop or 15:15. Score in index points per trade, both halves, worst day, shuffled-day null (as A-409 did) | Whether the course's filters separate accepted breaks from false starts | Small (index candles only) | ORB family: tick days positive, 2024–25 bars negative. Needs prior-day bars for the 30-minute CCI (34 bars ≈ 2.7 days) and the 5-minute EMAs at 09:35; the engine has a bar backfill (features v12) |
| N2 | If N1 passes: **nim-orb-v1 engine strategy, alone, with ATM option prices**, on tick days; then the live set with shared capital (the crowding check that killed orb-v1 in A-082 / A-360) | Whether it survives option costs and capital sharing | Medium | Only after N1 |
| **N3** | **"Range left" entry filter on breakout strategies:** skip a break when (day high − day low so far) ≥ k × the 20-day average daily range, k fixed in advance (e.g. 0.7). Measure it on orb-v1's and brtr-v1's existing tick-day trades first (descriptive: skipped winners vs losers) | Late / exhausted breaks | Small (uses existing trade lists) | Untested as an entry filter (A-314 tested a range-day label: FAIL −13.2 L). Most ORB-type filters removed winners before (A-389, A-394–A-396), so expect the same unless the descriptive split is clean |
| N4 | **CCI dynamic exit as an alternative exit** on the existing orb-v1 trades (and brtr-v1 shadow): exit when CCI(34, 30-minute) loses +110 and CCI(5-minute) < −60 | Whether a momentum-loss exit beats the stop / 2R | Medium | Exit rules have mostly failed here (A-311, A-352, A-385: exits that cut early also cut the runners). Low prior |
| N5 | Two-strength-candle confirmation in place of orb-v1's single 15-minute close | Whether the confirmation alone helps | Small inside N1 | Part of N1; no separate row needed unless N1 is mixed |
| — | CCI(8) pyramids | Adds on pullbacks | — | Not proposed: pyramids exist in etr, and adding size to ORB trades multiplies its known false-start losses |
| — | DTR from broker margin, time cycles | — | — | **Reject** (section 0.3) |

**Results (19 Nov): N1 = A-498 FAIL** (the course version doesn't beat a plain 5-minute ORB in H2, though it doubles the mean per trade); **N3 = A-499 FAIL** (skipping late breaks removes mostly winners: brtr-v1 −4.48 L). **A-500 (2024–26 bars) FAIL**: the course version is positive in every year in index points but only +0.059 %/trade and shrinking (0.083 → 0.053 → 0.033 %), below option costs; the late-break variant does not hold (53 % wins). Study: `docs/studies/2026-11-19-a498-a499-nimblrta-orb.md`.

**Recommendation (as written before the tests): N1 first.** It is one descriptive index-point screen (the A-409 format) and answers whether the
course's specific filters change the ORB picture. N3 can run beside it at almost no cost, because it only re-reads
trade lists we already have. N2 or N4 only if N1 shows something in both halves.

---

## 4. Lecture index

| # | Id | Length | Topic | Fig |
|---|---|---|---|---|
| L1 | 14476820 | 1 min | Disclaimer (no captions) | — |
| L2 | 14425612 | 4 min | Introduction: "Nimblr", 40 years' experience claim | — |
| L3 | 14476866 | 9 min | TradingView indicator setup (invite-only script) | — |
| L4 | 14427108 | 9 min | Opening range: wick and body breakout lines | 01, 02 |
| L5 | 14427222 | 4 min | Strength candles (body ÷ range > 50 %) | — |
| L6 | 14427800 | 6 min | Breakout examples | — |
| L7 | 14427770 | 2 min | Breakout-candle conditions | — |
| L8 | 14427826 | 7 min | Multi-timeframe CCI (34 on 30 min; 34 on 5 min; 8 on 5 min) | 01, 02 |
| L9 | 14428128 | 5 min | EMA 5 / 21 / 34 alignment | 01, 02 |
| L10 | 14428336 | 7 min | Entering a new trade | 01, 02 |
| L11 | 14428352 | 8 min | Pyramid trades | — |
| L12 | 14428394 | 5 min | Targets from margin-based DTR | 03 |
| L13 | 14428446 | 10 min | HTF (30-minute indecisive candle) stop | 04 |
| L14 | 14428456 | 7 min | Dynamic CCI stop | 05 |
| L15 | 14428508 | 9 min | Moon "time cycles", void-of-course moon | — |
| L16 | 14428476 | 9 min | Risk / reward vs accuracy | — |
| L17 | 14428478 | 4 min | Kelly position sizing; claimed performance | 07 |
| L18 | 14444110 | 9 min | MetaTrader screener; DTR sheet (done / left) | 06 |
| L19 | 14444098 | 3 min | Level 2 lunar-course advert | — |

### Figures ([`nimblrta-orb/`](nimblrta-orb/), course frames, private reference only)

| Fig | File | Shows |
|---|---|---|
| 01 | `01-buy-breakout-example.jpg` | Kotak Bank buy: strength candles 1 and 2 crossing the body and wick lines, EMA stack, CCI34 (30-minute) > +100, entry candle |
| 02 | `02-sell-breakout-example.jpg` | Airtel sell: the same sequence, with CCI crossing −100 |
| 03 | `03-margin-dtr-target-table.jpg` | Margin % → target 1 / 2 / 3 % table |
| 04 | `04-htf-stop-30min-indecisive.jpg` | 30-minute candles over the 5-minute chart; the stop from the last indecisive 30-minute candle (279 → 278.3) |
| 05 | `05-dynamic-cci-stop.jpg` | DSL exit at 192.52 vs the HTF stop at 185.95; the slide's condition reads CCI8 (5-minute) < −60 |
| 06 | `06-dtr-sheet-done-left.jpg` | Google sheet with DTR done / left and targets for NIFTY 50 stocks |
| 07 | `07-claimed-performance.jpg` | The vendor's performance slide (1,264 trades, 69 % wins, ratio 1.24) |
