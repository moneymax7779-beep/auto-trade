# Udemy — "The Complete Options Trading Course (Updated 2026)" — strategy documentation

Personal research notes from the user's Udemy subscription. All 107 lectures were read from the course's own caption
files in the user's Chrome on 19 Nov 2026 (plus the two short articles); the practice quiz (item 108) was not
read. The notes are in my own words: every rule, setting and example number, with the instructor's reasoning. No
course text is copied. Written for the user to review and choose from. **Nothing here is registered or tested.**

- Course id 1949606 · Wealthy Education (main presenter Steven Adams; the TradingView, day/swing, chart-pattern and
  candlestick sections have a different presenter) · 108 items, ~11 h · rating 4.36 (1,356) · updated 8 Nov 2026.
- Lecture link: `https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/<id>` (ids below).
- "Our evidence" = tests already in `docs/EXPERIMENT-LEDGER.md` (research checkout).

---

## 1. What the course is

| Part | Lectures | What it teaches | Use for us |
|---|---|---|---|
| Options foundation | L3–L8 | Contract basics, buy vs sell payoffs, S/R, EMA, MACD, position sizing | Background |
| **Options strategies** | **L9–L22** | **14 textbook strategies: bullish, bearish, neutral, each shown with one US stock example** | **The core: section 3** |
| TradingView how-to | L24–L34 | Platform tour plus generic Fibonacci, MA, MACD, Bollinger, RSI/stochastic, OBV, paper trading | A few rules, section 4 |
| Day and swing technical analysis | L35–L48 | Multiple time frames, S/R, Fibonacci, **pivot points**, Bollinger, stochastic, choppy and flat-market setups, risk | **The most concrete intraday rules: section 4** |
| Chart patterns | L49–L61 | H&S, double top/bottom, diamond, rounding, rectangle, cup and handle, triangles/wedges, flags, dead-cat bounce | Measured-move targets, section 4 |
| Candlesticks | L62–L73 | Single, double and triple candle patterns | One shared entry/stop rule, section 4 |
| TA fundamentals | L74–L84 | Repeats S/R, EMA, MACD, RSI, double top, H&S, flags, cup and handle | Duplicate |
| Value investing | L85–L104 | Intrinsic value, margin of safety, moats | Not applicable |
| "Training sessions" | L105–L107 | Compounding pitch, four "passive income" assets, broker choice, **SMA 60/180 daily crossover** | One trend filter, section 4 |

Only about 14 of the 107 lectures are options strategies. Most of the rest is generic chart reading, with US stocks,
forex and crypto as examples. Nothing is about Indian index options, weekly expiries or intraday option pricing.

---

## 2. Course-wide rules (options foundation, L3–L8)

| Item | Rule as taught |
|---|---|
| Support / resistance (L5, [12166136](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166136)) | A level needs ≥ 2 touches. Treat it as a zone (about $1 wide on a ~$100 stock), not one price. Older levels are stronger. Buy calls or sell puts at support, with a stop just below it. Buy puts or sell calls at resistance, with a stop just above it. |
| Trend by EMA (L6, [12166138](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166138)) | EMA = dynamic support in a bull trend, resistance in a bear trend. Very strong trends bounce off the 20 EMA. 50/200 golden cross = bullish, death cross = bearish. A trend change can mean sideways, not the opposite trend. |
| MACD 12/26/9 (L7, [12166140](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166140)) | Both lines above 0 = bull trend, below = bear. The trade signal is the MACD crossing its signal line. The zero-line cross lags. Divergence is a warning, not a signal. |
| Position sizing (L8, [12166142](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166142)) | contracts = (account × risk %) ÷ (option price × 100). Risk 5 % below $10K, 2 % for $10K–$100K, 1 % above $100K. Example: $10K × 5 % ÷ ($2 × 100) = 2.5 → 1 contract (he rounds down to 1, not 2). The formula treats the whole premium as the risk, so it is valid only for bought options. |
| Psychology (L46, L48) | Risk 1 % per with-trend trade and 0.5 % counter-trend. Stop for the day after 3–4 losses. Let stops work. |

---

## 3. The 14 options strategies (L9–L22)

Common context for all 14:
- The instructor trades **monthly US stock options**, held for weeks to months.
- Expiry choices are driven by time decay: buy ≥ 3 months out, sell 1–2 months out.
- Every example is one hand-picked stock shown **at entry only**, with its payoff numbers. No exit outcome, no series
  of trades, no win rate, no implied-volatility level and no costs are shown for any strategy.

### 3.1 Bullish

| # | Strategy | Construction | When | Entry trigger (chart) | Exit / stop / target | Max risk · max reward · breakeven | Course example |
|---|---|---|---|---|---|---|---|
| B1 | **Long call** (L9, [12166146](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166146)) | Buy ATM or ITM call, ≥ 3 months out | Stock expected to rise | Uptrend at or just above support; resistance break; MACD > 0 and crossed up; price on the 50 EMA; golden cross | Stop = a stock price (below support). Target ≥ 2×, ideally 3× the risk. Sell before the last month. | premium · unlimited · strike + premium | RY ~78.78: 3-month 80 call at 2.45, breakeven 82.45, target stock ~90 → call ~10 |
| B2 | **Covered call** (L10, [12166150](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166150)) | Own 100 shares + sell ATM / slightly OTM call 1–2 months out | Flat to slightly up | Range-bound, low-volatility stock (financials, insurers, dividend utilities) | Stop on the stock (support or about −5 %). Hold the call to expiry, roll monthly while the range holds. | stock − credit · (strike − cost) + credit · cost − credit | BAC range 28–31.5: buy 30.79, sell Oct 31 call 0.59 → cost 30.20, max gain 0.80 (~2 %/month) |
| B3 | **Synthetic call / protective put** (L11, [12166152](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166152)) | Long stock + buy ATM / slightly OTM put 1–2 months out | Holding stock into earnings or an event with gap risk | The event, not the chart (he says to ignore technicals) | After the event: up → sell the stock (keep or sell the put). Down → sell or exercise the put. | stock − strike + put · unlimited · stock + put | BBBY 19.12 + Oct 18 put 0.75 → max risk 1.87, breakeven 19.87 |
| B4 | **Bull call spread (debit)** (L12, [12166156](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166156)) | Buy ATM/near call, sell a higher strike, same expiry, ≥ 6 months out | Moderately bullish | Uptrend pulled back to support (200 EMA); MACD turning up from oversold | Close both, or leg out (buy back the short if rising fast; sell the long if a pullback is expected) | debit · width − debit · lower strike + debit | M: buy May 35 call 4.30, sell May 40 call 2.30 → debit 2, max 3, breakeven 37, target ~42.5 resistance |
| B5 | **Bull put spread (credit)** (L13, [12166158](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166158)) | Sell OTM put, buy a lower put, same expiry, ~1 month out | "Very high" belief the stock won't fall | At support or inside a range | Close as the short put decays, or leg out (sell the long put after a rise) | width − credit · credit · short strike − credit | TRV range 120–136 near 130: sell Oct 125 put 0.80, buy Oct 120 put 0.30 → credit 0.50, max risk 4.50 (risk 9 to make 1) |

### 3.2 Bearish

| # | Strategy | Construction | When | Entry trigger (chart) | Exit / stop / target | Max risk · max reward · breakeven | Course example |
|---|---|---|---|---|---|---|---|
| R1 | **Long put** (L14, [12166160](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166160)) | Buy ATM/ITM put, 3–6 months out (or LEAPS) | Stock expected to fall | Downtrend that rallied to resistance; MACD negative | Stop = stock breaks back above resistance. Target 2–3× the premium. Sell before the last month. | premium · strike − premium · strike − premium | GE ~12.67 at its down-trendline: Jan 12 put 0.40, target ~1.20, breakeven 11.60 |
| R2 | **Covered put** (L15, [12166164](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166164)) | Short 100 shares + sell ATM / slightly OTM put 1–2 months out | Down or flat | Downtrend; old support now resistance; 20 EMA below 50 EMA | Stop = cover the short above resistance | unlimited · (short − strike) + credit · short + credit | MU short 45 + Oct 43 put 1.00 → max gain 3, breakeven 46, stop ~47.22 |
| R3 | **Synthetic put / protective call** (L16, [12166166](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166166)) | Short stock + buy ATM / slightly OTM call 1–2 months out | Already short, into earnings/news | Downtrend hugging the 20 EMA, MACD negative, event close | Cover the stock at support and keep the call for a bounce, or sell the call after the event | strike − short + call · short − call · short − call | INTC short 46 + Oct 48 call 0.75 → max risk 2.75, breakeven 45.25 |
| R4 | **Bear call spread (credit)** (L17, [12166168](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166168)) | Sell OTM call, buy a higher call, same expiry, 1–2 months out | Down or below resistance | Downtrend or top of range; double top; descending-triangle break; 20 < 50 EMA; MACD negative | Close or let expire. He warns that one or two losses can wipe out several wins. | width − credit · credit · short strike + credit | GM ~33.5: sell 34 call 0.80, buy 36 call 0.25 → credit 0.55, max risk 1.45, breakeven 34.55 |
| R5 | **Bear put spread (debit)** (L18, [12166170](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166170)) | Buy ~OTM put, sell a lower put, same expiry, ≥ 6 months out | Moderately bearish | Downtrend; failed rally (lost 50 % of a big green candle and the 200 EMA); MACD turning down | Exit with 3–4 months left, or leg out at the bottom of a range | debit · width − debit · upper strike − debit | MO: buy Mar 60 put 3.50, sell Mar 55 put 1.50 → debit 2, max 3, breakeven 58 |

### 3.3 Neutral and volatility

| # | Strategy | Construction | When | Entry trigger | Exit | Max risk · max reward · breakevens | Course example |
|---|---|---|---|---|---|---|---|
| N1 | **Long call butterfly** (L19, [12166172](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166172)) | Buy 1 ITM, sell 2 ATM, buy 1 OTM call; equal spacing; ≤ 1 month | Tight range, low volatility, no news within the month, tight bid/ask | Price mid-range | Reverse or leg out. Max profit at the middle strike near expiry. | debit · spacing − debit · low + debit / high − debit | TRV ~130: 125 / 2×130 / 135 → debit 1.60, max 3.40, breakevens 126.60 / 133.40 |
| N2 | **Long put butterfly** (L20, [12166174](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166174)) | The same with puts | As N1 | Compare with N1 and pick the better price and bid/ask | As N1 | as N1 | AIG ~53: 51 / 2×53 / 55 puts → debit 0.70, max 1.30, breakevens 51.70 / 54.30 |
| N3 | **Long straddle** (L21, [12166176](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166176)) | Buy ATM call + ATM put, ~3 months out | Big move expected, direction unknown (earnings, regulator decision) | Ideally while the range narrows (cheaper options); IV rises into events | Exit both or leg out. Get out with ≥ 1 month left. Loses value while waiting. | debit · unlimited · strike ± debit | GIS ~42.5, earnings in 2 weeks: 42.5 call 2.10 + put 2.05 → debit 4.15, breakevens 38.35 / 46.65 |
| N4 | **Long strangle** (L22, [12166178](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12166178)) | Buy OTM call + OTM put, ~3 months out | As N3; cheaper but needs a bigger move | Before an event | As N3 | debit · unlimited · put strike − debit / call strike + debit | MYL ~37: 32.5 put 0.85 + 42.5 call 0.65 → debit 1.50, breakevens 31 / 44 |

Verbal slips in the captions, with the corrected values used above:
- L14 says "call" for the put.
- L17 says "44 55" for the 34.55 breakeven.
- L22 says "12:47" for the 31.00 breakeven.

### 3.4 Translating to NIFTY / SENSEX / BANKNIFTY options

| Course assumption | Index reality here | Consequence |
|---|---|---|
| Monthly stock options, entries 1–6 months out | Weekly index expiries; our books are 0–2 DTE intraday | The "≥ 3 months to avoid decay" and "sell 1 month for fast decay" rules have no direct equivalent. They would have to be re-cast as "far vs near weekly". |
| Stop on the stock price at S/R | Same idea on the index | Usable: an index-level stop is what our engine does now |
| Covered / synthetic positions need stock | Index futures exist; shorting the index is margin-heavy | B2, B3, R2 and R3 are not candidates for the PAPER-SELL account (S1 is defined-risk option books only) |
| Credit spreads 1–2 months, 1:4 to 1:9 risk/reward | Weekly credit spreads are the defined-risk building block S1 already has (`BookSpec.creditSpread`) | B5 and R4 are the only course strategies that fit S1 directly |
| Butterflies need a quiet month | Intraday iron butterfly sold at 10:00 lost (A-177) | N1 and N2 as *bought* butterflies are a different trade (debit, pin bet), not tested |
| Straddles before earnings | Index "events": RBI policy, Budget, elections, US CPI; a few per year | n is too small for a test with our rules |

---

## 4. Technical setups usable as entry triggers (L24–L83, L107)

These are where the course gives an explicit entry, stop and target. Each setup has a short ID for review.

| ID | Setup (lecture) | Rule as taught | Stop | Target / exit | Time frame in the course |
|---|---|---|---|---|---|
| T1 | **Pivot gap-against-trend reversal** (L41, [24763698](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/24763698)) | Daily trend decides the side. When the day gaps *against* that trend, wait for a hammer / reversal candle at S1 or S2 (price rarely trades beyond S3), buy in the trend's direction. He grades this an "A" trade; mid-range pivot trades are "B". | Below S3 (or below the pivot after a big bar) | S1 → pivot → R1 → R2; close by the session end | 15-min, QQQ; example: gap down, hammer near S3 ~182, stop < 181, exit ~184 at the close |
| T2 | **Flat-range volume breakout** (L47, [24763814](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/24763814)) | No trade while flat. Enter on the first large candle with a volume spike out of the range, in its direction. | Other side of the flat range | Not fixed; example ~2:1 (stop ~2.5, gain ~5) | 15-min |
| T3 | **Choppy-range stochastic** (L44, L46) | Only when the 50 EMA is flat. A stochastic (14) cross inside the 80/20 zone at range support/resistance. Prefer the with-trend side; 0.5 % risk counter-trend. | Exit at once if the range breaks | Other side of the range | 5-min |
| T4 | **With-trend pullback ("reversal")** (L45) | Daily downtrend. On 15-min, sell a MACD cross in the overbought zone at resistance, or a close above the upper Bollinger band. | Above the next resistance cluster / above the band | Lower band; trail behind successive resistances | 15-min |
| T5 | **Bollinger squeeze break** (L31, L43) | Bands contract, then the first close outside → trade the break direction; trail on the middle band | Middle band (trailing) | Next S/R | Presenter's favourite on short time frames |
| T6 | **Pattern measured moves** (L53, L54, L56, L57, L59, L60) | Enter on the neckline / boundary break (H&S, double top/bottom, rounding, rectangle, triangle before ~80 % of the way to the apex, flag/pennant) | Beyond the last shoulder / mid-pattern / other side of the flag | **Pattern height (pole length for flags) projected from the break**; book half, move the rest to breakeven | 4-h / daily |
| T7 | **Candle break rule** (L62–L73) | Hammer, shooting star, engulfing, harami, tweezer, morning/evening star: enter on the break of the pattern's high (bullish) or low (bearish), 5–10 pips beyond it | Other side of the pattern | Next S/R; skip if the room to the next level is smaller than the risk | Hourly / daily, at S/R, with the trend |
| T8 | **Dead-cat bounce short** (L61, [18462498](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/18462498)) | After a large gap/flush down, wait (often days) for the bounce to the broken gap or the 50 % Fibonacci of the drop, then sell a long red candle / failed retest. Avoid same-day V-reversals. | Above the bounce high | Retest of the lows | Daily / hourly |
| T9 | **SMA 60/180 slope-confirmed crossover** (L107, [12273876](https://www.udemy.com/course/the-complete-options-trading-course/learn/lecture/12273876)) | Daily. Long when SMA 60 crosses above SMA 180 *and both slope up*. Out (or short) when it crosses below *and both slope down*. A cross while the 180 still slopes the other way is ignored as noise. | None stated | Opposite valid cross | Daily, long-term |
| T10 | **Fibonacci confluence** (L28, L39, L40) | 38.2 / 50 / 61.8 % retracement only when it coincides with S/R, a round number and a reversal candle; never alone | Beyond the Fibonacci level | Prior swing; 161.8 % extension | Any |
| T11 | **Multiple time frames** (L38, L50) | Daily (weekly) sets the bias; take only that side on 1-h / 15 / 5-min | — | — | Filter |

What the course adds beyond the setups:
- **Room-to-target check** (L68): skip a pattern when the next level is closer than the stop.
- **Pattern-height targets** (T6).
- **"Gap against the daily trend at a pivot"** grading (T1).

Everything else repeats the TA basics we already use (EMA, MACD, RSI 70/30, S/R).

---

## 5. Assessment (honest)

1. **No evidence of an edge anywhere.** Across 107 lectures:
   - Not one backtest, win rate, trade series, drawdown or cost figure.
   - Option examples are shown at entry only.
   - Pattern examples are hand-picked charts where the move happened. Some overshoots are presented as the pattern
     working.
   - The training sessions make return claims with no support: "risk-free 10 %", 15–25 % a year, "double or triple
     your money in a few months", "guarantee".
2. **The options part is a correct textbook of payoffs**, useful as a reference for structures and breakevens:
   - It never discusses implied volatility levels, the Greeks beyond "decay is fastest in the last month",
     assignment, liquidity or slippage.
   - It never discusses why its credit spreads risk 3–9 to make 1. Its own warning that one or two losses can wipe
     out several wins (L17) is the real point and is not quantified.
3. **The intraday material is generic and mostly already tested here** (section 6). The repeated teaching that
   indicators confirm and price action triggers matches what our replays found: indicator-only triggers carry no
   direction (A-094, A-176, A-205).
4. **Market mismatch.** The setups are taught on US stocks, forex and crypto: daily charts, monthly options, gaps
   between sessions. Our trading is 0–2 DTE NIFTY/SENSEX/BANKNIFTY options intraday.
5. **Quality issues:**
   - Caption slips (call/put, wrong breakevens).
   - Duplicated sections (L74–L84 repeats L3–L7 and L49–L60).
   - About a fifth of the lectures (L85–L107) are value investing and promotion.
   - The final article is a coupon list for the publisher's other courses.

**Verdict:** a reasonable beginner reference for option structures (section 3 tables) and for pattern vocabulary. As a
source of trading rules for this system it is weak. Only four ideas are not already covered by earlier tests (C1,
C4, C6, C8 below), and none of them comes with evidence from the course.

---

## 6. Candidate rules for review (not registered)

Prior tests were checked first (ledger and `docs/research/`), per the rule after the A-494 mistake.

| ID | Candidate (index form) | Course source | Already tested here? | Status for review |
|---|---|---|---|---|
| C1 | **Pivot gap-against-trend reversal.** Daily trend up (e.g. index above its 50-day EMA); open gaps down; first 15-min reversal candle (hammer / bullish engulfing) at classic floor-pivot S1 or S2 → buy ATM call; stop below S3; targets pivot → R1; out by 15:15. Mirror for downtrends. | T1 (L41) | Partly: gap fades A-269 INSUFFICIENT, A-270 FAIL, A-277 entry ≈ break-even on 144 bar trades; floor pivots used only as targets (A-244). The *daily-trend + pivot S1/S2 + candle* combination is untested. | New element is small; the gap-fade family is weak. Low priority. |
| C2 | Flat-range / squeeze volume breakout | T2, T5 | Yes: A-414 (7 OR/compression/level rules: all lose 2024-25, win 2026), A-415 regime gate FAIL, A-082 ORB, A-417 srb, A-205 Bollinger expansion FAIL | Do not register (duplicate) |
| C3 | Candle-pattern break at S/R (hammer, engulfing, star) | T7 | Largely: A-201 INSUFFICIENT, A-202 0/15 winners, A-391 sweep reversal FAIL, A-315 levels = placebo, A-400 level trader FAIL | Do not register |
| C4 | **Measured-move target for break trades.** On sbr / level-break entries, the first target = box height projected from the break; half off there, rest to breakeven. | T6 | Not as such: A-438 fresh-top target FAIL; A-311 breakeven stop FAIL; partial-at-level FAIL (A-315 H3). The *box-height* target itself is untested. | Possible as an exit variant of sbr; one registration |
| C5 | Daily SMA 60/180 slope-confirmed trend as a side filter | T9, T11 | Mostly: regime/trend gates A-081, A-094, A-394–A-396 FAIL; ma-cross is live with its own rules | Do not register |
| C6 | **Dead-cat bounce (multi-day).** After an index day of ≤ −1.5 % with a gap, wait up to 5 sessions for a bounce to the gap edge or the 50 % retracement, then buy a put on the first 15-min bearish close there; stop above the bounce high; target the low. | T8 | Not tested (no multi-day trade in the ledger) | New. Needs the 2024–26 bars (ask before any bar-day replay) and a holding period beyond one session |
| C7 | Sizing: 1 % with-trend / 0.5 % counter-trend; stop after 3–4 losses | L46, L48 | Yes: A-375/A-376/A-377 sizing FAIL, A-071 two strikes FAIL, A-099 kill switch neutral | Do not register |
| C8 | **Directional credit spread at S/R for PAPER-SELL.** Uptrend at support → sell a weekly put spread below support (B5); downtrend at resistance → sell a call spread above it (R4); short strike beyond the level; max loss ≤ 2 % of the account (sell-risk v1). | B5, R4 | Not tested: A-177 iron fly FAIL, A-204 strangle FAIL and A-292 straddle PASS (weak) are all non-directional | **Registered and tested 19 Nov as A-495: FAIL** (46 books, −₹17,613, PF 0.47, both halves negative; 34 / 46 stopped by the level break). `docs/studies/2026-11-19-a495-directional-credit-spread.md` |
| C9 | Bought butterfly on quiet expiry afternoons (pin bet) | N1, N2 | Not tested as a debit trade (A-177 sold a fly; A-382: OI walls don't forecast pin vs move) | Weak prior (A-382). Low priority. |
| C10 | Event straddle (RBI, Budget, results of elections) | N3, N4 | Not tested; too few events for our rules | Descriptive at most |

C8 was tested first (A-495, FAIL). Of the rest, C4 then C6 remain untested. Each needs its own ledger row with the rule
fixed before any data is read.

---

## 7. Lecture index (ids)

- Foundation:
  - L3 12166132 · L4 12166134 · L5 12166136 · L6 12166138 · L7 12166140 · L8 12166142
- Bullish:
  - L9 12166146 · L10 12166150 · L11 12166152 · L12 12166156 · L13 12166158
- Bearish:
  - L14 12166160 · L15 12166164 · L16 12166166 · L17 12166168 · L18 12166170
- Neutral:
  - L19 12166172 · L20 12166174 · L21 12166176 · L22 12166178
- TradingView:
  - L24 41491256 … L34 41491354 (Fibonacci L28 41491302, Bollinger L31 41491330, RSI/stochastic L32 41491338, OBV
    L33 41491346)
- Day/swing:
  - L35 24763534 … L48 24763842 (pivots L41 24763698, choppy L46 24763794, flat L47 24763814)
- Patterns:
  - L49 18462328 … L61 18462498 (H&S L53 18462380, double top L54 18462398, triangles L59 18462464, flags L60
    18462486)
- Candles:
  - L62 18462536 … L73 18462658
- TA fundamentals:
  - L74 12976574 … L84 12977626
- Value investing:
  - L85 12273822 … L104 12273868
- Training sessions:
  - L105 12273870 · L106 12273872 · L107 12273876 (SMA 60/180)
