# Udemy — "Options Trading MasterClass: Options With Technical Analysis" — detailed study notes

Personal research notes from the user's Udemy subscription, read in the course player's transcript panel (24 Oct 2026).
Written in my own words at full detail (every setting, condition, example number and the instructor's reasoning), with
a link per lecture to rewatch it with Udemy's own transcript. The course text itself is not copied (copyright).

- Course id 1568560 · 130 lectures · 11 sections. Base URL for lectures:
  `https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/<id>`
- Strategy sections: **5 (basic, 34 lectures, done)**, **6 (intraday, done)**, **8 (spreads, 41 — index-relevant parts done)**, **9 (neutral, 5, done)**,
  **10 (volatile, 4, done)**. Sections 1–4 and 7 are basics / Greeks (not read).
- "Our evidence" = tests already run in this repo (`docs/EXPERIMENT-LEDGER.md` in the main repo).

---

## Section 5 — Basic Strategies (swing trades on stock options)

Context: the instructor trades **monthly stock options** (US and Indian single stocks), holding days to weeks. These are
swing setups on daily / hourly charts, not intraday index rules. All notes below were checked against the lectures'
captions on 24 Oct 2026.

### L31 · Long call · [lecture 9481838](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9481838)
- Use only in an **established uptrend**. Loss limited to the premium; leverage is the appeal (the instructor's AAPL math:
  a $2 call controls 100 shares instead of $16,100 of stock).
- Daily chart: draw the uptrend line through the **first two swing lows** and extend it. Buy a call each time price
  **turns up after touching (or briefly dipping through) the line**, but **only if RSI(14) > 50** at that point.
- After entry switch to the **hourly** chart, draw a short-term trendline through the first two hourly lows of the new
  leg; hold while price stays above it, **exit when price closes below it**.
- Example 1 (AAPL): entry at the trendline bounce at **$161**, hourly-trendline exit at **$173**. With a $2 premium × 100:
  profit = (173 − 161) × 100 − 200 = **$1,000**; max loss $200.
- Example 2 (Facebook): range, then breakdown, then a new uptrend; bounce entry ~**$172**, hourly exit ~**$179**.

### L32 · Long put · [lecture 9481832](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9481832)
- Use in an **established downtrend**, or after a **double top / head-and-shoulders** in an uptrend.
- Daily chart: downtrend line through the first two swing highs; buy a put each time price **touches the line and turns
  down**, only if **RSI < 50**. No puts while price is back above the line.
- Hourly: down-trendline over the new leg; hold while below, exit when price crosses above.
- Textbook: stock 70, 70 put at 2 (×100 = 200). Stock 65 → +500 − 200 = +300; stock up → lose the 200.
- Chart examples: Twitter (~2-year downtrend, four put entries on the line, all with RSI < 50), Sun Pharma.

### L33 · When the trendline has too few touches · [lecture 11005282](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/11005282)
- In a strong-momentum phase the trendline may have only a few touches more than a year apart. Then use indicators for
  entries between touches.
- Timeframe pairs: **monthly options → weekly + daily charts** (entry on hourly, see L35); **weekly options → daily +
  hourly**.

### L34 · Indicator set-up for monthly options · [lecture 11005286](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/11005286)
- **Weekly chart, RSI(14):** mark where RSI crosses up through 50 (or comes up from oversold to 50) → calls only. Mirror
  for puts (RSI crossing down through 50, ignored if price is above the downtrend line).
- **Daily chart, SMA 14 and SMA 21 + RSI:** call entry when **MA14 > MA21 and RSI > 50**; hold the call while MA14 stays
  above MA21; exit when MA14 crosses below MA21 (RSI is then below 50). A trendline under the move can give an earlier
  exit.
- Example (RCOM, puts): MA14 crosses under MA21 with RSI < 50 → buy puts at **102.35**; exit signal at **63.95**.

### L35 · Monthly set-up continued (three timeframes) · [lecture 13696134](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/13696134)
- The daily MA signal can last months (example: long signal 10 Jun → exit 12 Oct), longer than one monthly option. So:
  **weekly = direction, daily = the permitted window, hourly = entries and exits.**
- Inside a daily "bullish window" (MA14 > MA21) take **only call** signals on the hourly chart: hourly MA14 crosses above
  MA21 with RSI > 50 → buy calls. Ignore hourly bearish crossovers in that window.
- Hourly exits (whichever comes first):
  1. Price closes below the trendline drawn under the hourly up-leg.
  2. Price **gaps up / runs very far from the trendline** (stretched) → take profit.
  3. Price reaches a resistance level where candles stall.
  4. The hourly MA set gives an opposite crossover.
- Puts mirror this (RCOM: daily put window 7 Jul → 3 Nov; hourly entries; one exit taken on a **bullish RSI divergence**,
  another when price was far from the trendline).

### L36–L56 · Live stock-option trades (11 trades, all shown as winners)
Each pair = an entry lecture and a result lecture. Premiums in → out are per share. Lot sizes are what the instructor
quoted at the time.

| L | Stock | Setup (as the instructor explains it) | Option | In → out | Days | P&L quoted |
|---|---|---|---|---|---|---|
| 36–37 | Reliance (1,383) | Uptrend, sharp up move, RSI > 50, hourly Bollinger Bands pointing up but narrowing; "moderately bullish" only up to resistance; max call OI at the 1,400 strike taken as the ceiling | 1,380 CE | 51.60 → 69.35 | 2 | ~₹8,000 (lot 500); exited at resistance + Fibonacci level, move "too sharp" |
| 38–39 | Tesla | Daily downtrend in a converging channel; price on the channel support; **hourly double bottom + positive RSI divergence**, RSI breaks its resistance line, gap-up open = entry; Fibonacci 61.8 % / 78.6 % zone as target | 240 CE (31 May exp.) | 13.10 → 22.75 | 4 | ~74 %; exit at the Fibonacci level with RSI overbought. Also showed a hedged alternative (sell 231 / buy 225 put spread) |
| 40–41 | Bharti Airtel (~330) | Uptrend, price resting on the trendline, a Fibonacci level acting as support, RSI > 50 | 330 CE | 10 → 15.65 | 7 | ₹10,458 (lot 1,851), >50 %; exit at a horizontal resistance |
| 42–43 | Vedanta (167) | Downtrend; **head-and-shoulders** on daily and hourly; support broken by price and by RSI ("failing divergence" = strong momentum); hourly MA set bearish | 170 PE (slightly ITM, next-month) | 9.80 → 15.70 | 7 | ₹13,340 (lot 2,300) on ~₹22.5K; exit because price was **far from both MAs** (bounce risk) |
| 44–45 | Yes Bank (170) | News gap-down (−30 %, quarterly loss), long bearish candle closing on a Fibonacci-cluster line, daily downtrend | 170 PE (ATM) | 16.90 → 32.70 | 15 | ₹27,650 (lot 1,750); exit near channel support 126.70 with a positive hourly RSI divergence |
| 46–47 | Apple (212.45) | Daily uptrend channel, price at the **upper channel line**, **shooting-star** candle, negative RSI divergence on daily and hourly | 210 PE (31 May) — or a 217.5/222.5 call credit spread | 4.10 → 9.05 (bid) | 5 | $505 per lot (>100 %); exit at a Fibonacci cluster / hourly channel support |
| 48–49 | Wipro (292) | Daily uptrend but a very sharp hourly up-move; hourly **negative RSI divergence**, MA set sell signal, RSI < 50, Bollinger Bands turning down → expected retracement | 290 PE ×2 lots | 4.35 → 8.60 | 19 | ₹13,600 per lot (lot 3,200), ~100 %; closed before election results |
| 50–51 | PNB | Daily downtrend channel, price at the upper line, MA set bearish; hourly **head-and-shoulders break on a long, high-volume candle**; target 81.10 | 90 PE (ITM, next month) | 7.85 → 10.70 | ~14 | ~₹20,000 (lot 7,000) on ~₹50K |
| 52 | Wockhardt (405.80) | Downtrend; pullback toward the down-trendline formed an hourly **head-and-shoulders**; neckline broken on a **high-volume** long red candle; pullback trendline broken; negative RSI divergence | 410 PE | 22 → 42 (bid; ask 48) | ~2 wks | ~100 %; notes the wide bid/ask spread |
| 53–54 | Oracle (54.56) | Daily double bottom → sharp rally → **rising wedge** with **negative RSI divergence**; red candle breaks the wedge support; hourly MA set sells; target Fibonacci 23.6 % (52.44), then 38.2 % | 54 PE | 0.89 → 2.45 | 27 | $156 per lot (175 %); exit 2 days before expiry with max put OI at 53 |
| 55–56 | ONGC (168.40) | Daily downtrend (line not confirmed, RSI near 70); hourly uptrend channel, price at the **upper channel line**, negative RSI divergence → expected pullback to the trendline | 165 PE (OTM) | 3.90 → 5.70 | 17 | ₹6,750 (lot 3,750), ~75 %; exit when price reached the trendline |

Recurring rules across these trades:
- **Entry confluence:** a chart pattern (H&S, double bottom, rising wedge, shooting star at a channel line) + an **RSI
  divergence** or RSI-50 side + the hourly **MA set** agreeing. Pattern breaks must come on **long, high-volume candles**.
- **Option choice:** next-month expiry, ATM or slightly ITM (OTM only for cheap counter-trend trades).
- **Exits:** a Fibonacci level or channel line as the target; or when price is **far from both moving averages**; or a
  divergence the other way; or roughly **+50–100 % on the premium**. He closes before known events (election results).
- **Hedging:** he says repeatedly he **never sells naked options** — any short option is paired with a long one.
- Caveat: only winning trades are shown; no losing trades, hit rate or drawdown — selection bias. Single stocks, not
  index options, and holding periods of days, not minutes.

### L57 · Covered call · [lecture 9481862](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9481862)
- Own 100 shares and sell an OTM call: extra income when you are only **moderately bullish**; upside capped at the
  strike; downside only cushioned by the premium.
- Textbook: buy at 67, sell the 77 call at 1 → max profit (77 − 67) × 100 + 100 = **$1,100**; at 64 the $300 paper loss
  becomes $200.
- Live set-up (AAPL): bought at **~$168** on a trendline bounce (the instructor's figures drift between 166, 168 and 170).
  Did **not** sell the call at entry; waited for the rise to **$175**, then, with a negative RSI divergence and the upper
  range line at ~183, sold the **January 177.5 call at 1.05** ($105).

### L58 · Covered call result · [lecture 13192326](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/13192326)
- 22 Dec 2017 → 22 Jan 2018: price stayed below 177.5; the call **expired worthless** (kept $105). He describes the
  **100-day MA** as the stock's "trade initiation zone" where buying calls / stock has worked, and expects a range
  between a strong support and the resistance line.

### L59 · Protective put · [lecture 9505930](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9505930)
- Buy the stock and buy a put near the stock price; the put is insurance during its life. Better than a stop order, he
  argues, because a stop can be triggered by a brief spike down and the put cannot.
- Max loss = (stock price − put strike) + premium; upside unlimited less the premium.
- When: at the start of a short-term uptrend or the end of a short-term downtrend, or to protect unrealised gains on
  shares you already hold when the near term is uncertain.
- Textbook example: stock 90, March put 3 (lot 100 → 300 paid). At 110 → +2,000 on shares − 300 = +1,700. At 70 → shares
  −2,000, put worth 2,000 → loss = 300 (the premium).
- Live example: Facebook at **183.29** (23 Feb 2018), testing its uptrend line, above the 50 and 100 DMA; resistance ~197.6,
  target ~195. Bought 100 shares and the **182.5 put at 4.10 ask** (expiry 23 Mar 2018). Cost 18,329 + 410 = 18,739.
  Scenario 195 → +1,171 on shares − 410 = **+761**; scenario 170 → shares −1,329, put intrinsic 12.5 × 100 = 1,250 →
  loss **489** instead of 1,329.

### L60 · Protective put result · [lecture 13192356](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/13192356)
- Facebook ranged for 14–15 days, then fell to **~160 by 26 Mar**. Unhedged loss would have been ~2,300; with the put the
  loss was capped at **489**.

### L61 · Writing (selling) puts · [lecture 9481790](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9481790)
- Sell a put with no shares: keep the premium if price stays flat or rises; loss grows as price falls. He calls it one of
  the riskiest strategies and says only to do it with chart analysis **and a stop-loss**.
- Payoff example: stock 200, sell the 200 put at 2 (lot 100 → +200). At/above 200 on expiry → keep 200. At 199 → −100 on
  assignment + 200 = +100. At 195 → −500 + 200 = −300.
- When to write puts:
  1. Established uptrend, at the end of a retracement — price falls to the rising trendline and turns back up.
  2. A double bottom inside an uptrend.
  3. The last lower-high of a downtrend is broken upward **and** RSI shows positive divergence (start of a new uptrend).
- Stops: Intel example — write the put at **37** after a trendline bounce, stop at **34** (a retracement back to the
  trendline / the 100 % retracement level, stop 1–2 below it). For the trend-reversal case the stop is the last low of
  the downtrend.
- Advantage: flexible — can be turned into spreads if the view changes. Disadvantage: large loss on a sharp fall.

### L62 · Conditions for selling option premium · [lecture 10980778](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/10980778)
The most rule-like lecture in Section 5 — a complete premium-selling recipe on the **daily chart**:
- **Moving averages: 50, 100, 200** (he says EMA).
- **Put writing (uptrend):**
  1. Trend: MA50 > MA100 > MA200 with clear distance between them.
  2. Retracement: **RSI(14) drops below 40** while the trend holds.
  3. Trigger: wait — do not sell while RSI is under 50; sell when **RSI crosses back above 50**.
  4. Strike: the **low of the trigger candle / low of the retracement** (e.g. trigger candle low 178.35 → sell the 178 put;
     Facebook 162.70 → 162 put; 168.84 → 168 put).
  5. Expiry: about **one month**.
  6. Hold while RSI stays above a **trendline drawn on RSI**; exit when RSI breaks that trendline (example: entry 7 Dec,
     exit 12 Jan, put far OTM / near worthless).
- **Call writing (downtrend):** mirror — MA50 < MA100 < MA200; pullback rally with **RSI above 60**; sell when **RSI
  crosses below 50**; strike = **high of the trigger candle** (examples: 247 call with price ~228; 260 call with price
  ~238); exit when RSI breaks its down-trendline (example exits at 283 and 200 marked on the chart).
- Core warning: never sell puts unless the stock is in an uptrend, never sell calls unless it is in a downtrend.

### Section 5 — rules worth testing (my extraction)
| # | Rule | Precise version for a test | Our evidence |
|---|---|---|---|
| U9 | Trend-pullback premium sale (L62): daily MA50>100>200, RSI dips < 40, sell put at the trigger-candle low when RSI re-crosses 50; mirror for calls | On NIFTY/SENSEX daily bars 2015–2026 with weekly or monthly options: sell the strike nearest the trigger-candle low, exit on RSI trendline break (approximate as RSI crossing back below 50) or expiry | **not tested** — closest is A-196 (non-directional weekly strangle, FAIL on CI) |
| U10 | Monthly direction filter: weekly RSI > 50 + daily MA14/21 + RSI 50 alignment, entries on hourly pullbacks | A filter for multi-day option buys on the index; we trade intraday, so only usable as a day-bias filter | **not tested** (A-094 regime model with other inputs FAIL) |
| U11 | Breakouts only on above-average volume | Index volume = futures volume; add RVOL ≥ 1 to a breakout entry | partly — A-114 odb-v9 (RVOL) in shadow; A-157/A-162 volume burst no edge out of October |
| U12 | Exit when price is far from both MAs (stretch) or at a level with divergence | Exit variant for multi-hour holds | stretch-reversion did not hold (swing zones study, 17 Oct) |
| — | Protective put / covered call | Hedging structures for stock holdings; not index intraday signals | n/a |

---

## Section 6 — Basic Strategies Part-2 (Intraday Trading)

### L63 · Trade set-up for weekly options (indicator settings) · [lecture 13696160](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/13696160)
**Settings**
- Three timeframes ("3-D charting") for weekly options: **hourly, 15-min, 5-min**.
- Moving averages **10 and 20** periods; **RSI** with the **50** level as the trend line; **Bollinger Bands** (default
  settings, not stated).

**Procedure**
1. Daily chart first: is price in an up- or downtrend? (example: daily downtrend.)
2. Hourly chart: signal when **MA10 < MA20 and RSI < 50** (bearish) — or MA10 > MA20 and RSI > 50 (bullish).
   - In a downtrend: **buy puts only, never calls**. Once hourly RSI goes back **above 50**, stop buying puts.
3. 15-min chart: confirm the same trend.
4. 5-min chart (same MA 10/20): enter in the hourly trend's direction when the 5-min MAs give the same signal and RSI is
   on the same side of 50.
5. **Bollinger filter:** while the bands are **flat and contracting → no trade either way**. When the bands **start to
   expand** and the MA set already gives the signal → enter (volatility is coming).

**Exits**
- Draw a **trendline** along the move; **hold while price stays beyond it** (below it for a put).
- **Take profit when a candle is far away from the trendline** (overstretched).
- **Exit on RSI divergence** against the trade (e.g. bullish divergence while holding a put) — exit at the first candle
  in the other colour after the divergence.

**Our evidence:** the MA-cross entry is close to **mac** (live; +₹9.8 L on 27 tick days, −₹5.49 L on 2024–25 bars). The
"no trade while the bands are narrow" filter resembles **A-167 compression veto (FAIL)**. **New for us:** requiring hourly +
15-min agreement before a 5-min entry; RSI-divergence exit.

### L64 · Live trade — R-Infra intraday (stock option) · [lecture 14812070](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/14812070)
- Hourly: stock in a downtrend under a falling trendline, currently resting on support; two support levels marked.
- 5-min: **gap-down open**; a candle closed **outside the lower Bollinger Band**, then consolidation, then price started
  falling again → expect a fall to the next support.
- Instrument: **ATM put** (spot 109.40 → strike 110 put at ₹12.45).
- **Stop:** at the recent peak on the chart (111.75, ≈ ₹2 above spot) → translated to the option as **≈ ₹1 below the
  entry premium (11.45)**, i.e. delta ≈ 0.5.

### L65 · R-Infra result · [lecture 14812072](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/14812072)
- About 2 hours later price reached the support level; put at 15.70 (bid 15.40).
- **Exit reason:** price at support **and RSI positive divergence** → expect a bounce, don't give back premium.
- Result: +₹2.95 a share on a ₹16,185 position (lot 1,300) ≈ **+25 % in ~2 hours**.
- Note: one illustrative stock-option trade; not evidence.

### L66 · Bank Nifty intraday, the day before weekly expiry · [lecture 13139210](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/13139210)
**Settings (intraday):** **5-min chart, EMA 5 and EMA 10**, RSI (50), Bollinger Bands.

**Entry rule:** the bands had been **narrow**; a **red candle** made them **expand**; **EMA 5 < EMA 10**; **RSI < 50** →
buy a put. Example: Bank Nifty 27,330 → bought the **27,000 put (OTM, ~330 points)** at **₹22**.

**Exit rule:** the **EMA set crosses back** (EMA 5 above EMA 10). Example exit with Bank Nifty at 27,133: put at ₹70,
sold at **₹75** → **+240 %**.

**Context for the next days (daily chart + option chain):**
- **Highest put OI strike = support** (27,000 here; a chart level at 27,055 agreed).
- **Highest call OI strike = resistance** (27,500), **second-highest call OI** (27,300) = nearer resistance.
- **Fibonacci 78.6 %** of the last daily swing (≈ 27,370) and a level resisted by three earlier peaks = strong level.
- Bollinger going sideways on the daily → expect a **range** between those levels; a break up targets ~28,400, a break
  down ~26,550.

**Our evidence:** OI walls as support/resistance: **A-119 (FAIL)**, **A-154 expiry pin study** (floors/ceilings hold as
often as chance). The **5-min EMA 5/10 + Bollinger-expansion + RSI-50 entry is not tested here** (mac uses a different
cross and no Bollinger condition) → candidate.

### L67 · Live trade — Nifty 50, the day before weekly expiry (3 Apr) · [lecture 14276172](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/14276172)
- 9:20 AM read: 5-min **doji** candles, Bollinger rising but **RSI negative divergence** → bearish sign.
- Hourly: Nifty in a **parallel channel**, touching the **channel's resistance line** and the **upper Bollinger Band**.
- Option chain: **highest call OI at 11,800** with Nifty at **11,750** → 11,800 = resistance; highest put OI at 11,600
  and 11,500 → downside targets.
- **View: bearish.** Two ways to trade it:
  1. Buy a put when the 5-min MA set gives the signal; or
  2. **Sell Nifty futures + buy the 11,800 call as a hedge** (call at ₹26) — capped upside risk, profits from a fall.

### L68 · Nifty 50 continued — expiry day result (4 Apr, 3 PM) · [lecture 14276178](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/14276178)
- Nifty fell ~150 points from 11,750 by 3 PM on expiry day; the 11,800 call expired at ₹0.05.
- **Futures + hedge P&L:** +150 points on the future − ₹26 for the call ≈ **+125 points ≈ ₹10,000 a lot** (lot 75).
- Position management: **4 lots** sold with 4 hedging calls; **2 closed the day before**, 2 held because the expiry-day
  highest put OI (11,600, 11,500) still pointed lower.
- Reasons for the bearish view: channel resistance, **3 doji candles on the daily after an up move** (taught as a short
  signal), RSI negative divergence.
- Next levels: if the support zone breaks → next zone; if it holds → expect an up move.

### L69 · Live trades on (US) expiry day — AAPL, BABA, MSFT, LRCX · [lecture 14121663](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/14121663)
**Day-trading rules shown (on the stock, not options):**
- Read the option chain at the open: price opening **at/near the highest call OI strike** → bearish bias; near the
  highest put OI strike → bullish bias.
- Entry: **MA 5/10 cross** in the bias direction; prefer a **wide** Bollinger range (volatility present).
- **If the opening candle is outside the Bollinger Band, wait for a candle that opens back inside the band**, then
  enter if the MA set and a falling band agree.
- **Stop:** the high of the candle before the MA crossover (or the opening candle's high after a gap-down; or the high
  of the pull-back rally).
- **Scale out:** exit **half** at the first good profit, **move the stop to breakeven**, target the next support (2nd
  highest put OI strike / chart support).
- Exit the rest at support/trendline, on a **hammer** or other reversal candle at the lower band, or on **RSI positive
  divergence**. A long red (**marubozu**) candle at support = expect more downside.
- The instructor's own caution: **on expiry day he does not recommend buying options** — if volatility is low, options
  don't pay; trade futures/shares instead. OI gives an idea of the move but price need not reach the max-OI strike.

### L70 · Live trade — pivot points, Reliance on expiry day · [lecture 14512878](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/14512878)
- After two days of sharp rises, price above **R1** at ~1,403 on expiry morning → expect a retracement.
- Option chain: highest call OI at 1,400, second at 1,420; resistance zone ~1,425; **RSI negative divergence**.
- Trade: **sell the stock future + buy the 1,400 call (₹11) as a hedge** → max risk ≈ ₹8 a share (11 − 3).
- Result by 3 PM: 1,403 → 1,372 ≈ **+₹15,000** (lot 500), most of it in the last ~10 minutes.
- Pivot behaviour that day: support at the **pivot**, touch of **R2** with a reversal candle, then a sharp fall to **S1**.

### L71 · Options trading with divergence (Nifty) · [lecture 10927474](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/10927474)
- Daily: Nifty broke a trendline, then a **symmetrical triangle** broke **down** (normally a continuation pattern), opened
  below the line and touched the **100 EMA**.
- **Volume rule:** a genuine breakout/breakdown of a consolidation needs a **volume spike**; average volume → treat the
  signal with suspicion.
- Fibonacci: price held the **50 %** retracement of the last swing (and the 100-period MA).
- 5-min: price in a **descending channel** but **RSI positive divergence**; draw a **resistance line on the RSI** — RSI
  breaking above it = early buy signal (even while RSI < 50).
- Plan for next morning: if the support holds, **buy the ATM call**; target **10,700** (the triangle's lower line /
  Fibonacci level); **stop below the 50 % Fibonacci / the red candle's low (~10,565)**; confirmation = RSI > 50 and price
  above the channel's upper line.

### L72 · Divergence continued · [lecture 10927478](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/10927478)
- Next day: **gap-up open outside the channel, RSI > 50, MA signals bullish** → bought the call; stop just below the
  marked line; target 10,700 reached; **exit when EMA 5 < EMA 10** (≈ 10,605 → 10,690).
- **Intraday settings stated: 5-min chart, EMA 5, 10 and 20.**
- Following day: gap-up above the line, then a drop inside 5 minutes; **RSI negative divergence**; **EMA 5 and 10 both
  below EMA 20** → bearish; confirmation = **RSI breaks a support line drawn on the RSI** with RSI < 50 → **buy the put**;
  **exit when both EMA 5 and 10 are back above EMA 20** (≈ 10,691 → 10,630).
- Instructor's claim: divergence is "the best tool" for intraday option buying.

### Section 6 — rules worth testing (my extraction)
| # | Rule | Precise version for a test | Our evidence |
|---|---|---|---|
| U1 | EMA 5/10 cross on 5-min + Bollinger expansion from narrow + RSI 50 side → buy ATM/near-OTM option; exit on the reverse cross | Bands' width rising after being below its 20-bar median; enter on the close; exit when EMA5 crosses back | **A-205** (18 tick days): +10.9 %/trade, win 40 %, CI −2.4 % to +27.3 % → FAIL on CI |
| U2 | Higher-timeframe agreement: trade the 5-min signal only in the direction of hourly (and 15-min) MA10/20 + RSI-50 | Add as a filter to mac and to U1 | **A-206 FAIL** (U1 + filter −2.6 %); on mac's tick-day trades the agreeing ones were not better (A-207) |
| U3 | EMA 5/10 vs EMA 20 exit (exit when both cross back over EMA 20) | Exit rule for mac-like entries | partly — A-079/A-080 tested cross exits |
| U4 | RSI divergence as exit (and as an entry trigger with an RSI trendline break) | Swing-based divergence on 5-min (two lower lows in price, higher low in RSI) | **A-207**: as an exit on U1, +11.1 % vs +10.9 % — no change |
| U5 | Scale out half at first target, stop to breakeven | Exit variant | A-065 runner split FAIL (brt) |
| U6 | Opening candle outside the Bollinger Band → wait for a candle that opens inside before entering | Entry delay filter | **A-207**: blocked 2 trades (both winners) |
| U7 | Option-chain max OI as support/resistance, bias from where price opens vs max-OI strikes | — | A-119 FAIL, A-154 FAIL |
| U8 | Futures + hedging option (sell future + buy call) for directional views | Execution structure, not a signal | n/a (needs futures support) |
| — | Instructor's caution: don't buy options on expiry day when volatility is low | — | consistent with A-189…A-193 |

---

## Sections 8–10 — Spreads, neutral and volatile strategies (index focus)

From 24 Oct 2026 the user asked to **focus on index strategies**. Single-stock spread walkthroughs (Facebook, Kotak,
L&T, Indiabulls, Jet Airways, Reliance, TCS, ONGC, ICICI, Nvidia, Invesco, Sun Pharma, IFCI) are summarised by their rule
only. Nifty lectures are written out in full.

### Spread mechanics (L81, L93, L100, L112, L114, L118 — generic)
| Spread | Legs | Use when (instructor's condition) | Max gain / loss |
|---|---|---|---|
| Bull call (L81 · [lecture 9481780](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9481780)) | buy lower-strike call, sell higher-strike call (debit) | uptrend; he **skips it when reward/risk is poor** (example: risk 17.45 for 22.50 → not taken) | width − debit / debit |
| Bull put (L93–94 · [lecture 9481784](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9481784)) | sell higher-strike put, buy lower-strike put (credit) | uptrend or range; long put strike at a **strong support**, short put above it; entry on a hammer at support | credit / width − credit |
| Bear call (L100–101 · [lecture 9497422](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9497422)) | sell lower-strike call, buy higher-strike call (credit) | downtrend / below resistance | credit / width − credit |
| Bear put (L112–113 · [lecture 9505928](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9505928)) | buy higher-strike put, sell lower-strike put (debit) | downtrend | width − debit / debit |
| Call back spread (L114–117 · [lecture 9481772](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9481772)) | sell 1 lower-strike call, buy 2 higher-strike calls | start of a short-term uptrend in a volatile stock; flat markets are the worst case (max loss at the long strike) | open / (width − credit) |
| Put back spread (L118–120 · [lecture 9497420](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9497420)) | sell 1 higher-strike put, buy 2 lower-strike puts | long-term downtrend touching its trendline, or short-term downtrend at resistance; best when a **sharp fall** is expected | open / (width − credit) |

- **L116–117 "profitable condition for call back spread"** ([lecture 11049096](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/11049096)): a **bullish reversal pattern** (inverse H&S
  on the hourly chart) breaking out while the daily is in an up-channel resting on its trendline and lower Bollinger
  Band → make the back spread a **small debit** (Invesco: buy 2 × 173 C at 3.43, sell 1 × 170 C at 5.33 → debit 1.53).
  Exit before expiry at **reward = 2 × risk** (made 2.32 on 1.53 risk in 12 days).

### L120 · Put back spread on NIFTY — "sloping-down head-and-shoulders" · [lecture 10493016](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/10493016)
The most index-specific rule in the course:
- Setup: a **head-and-shoulders whose neckline slopes down**, on any timeframe (hourly or **15-minute** NIFTY). He calls
  it highly reliable — "prices fall drastically when the neckline breaks".
- Confirmation in his example (15-min NIFTY, 17–18 May 2018): **strong negative RSI divergence**, **MA50 crossing below
  MA100**, a 15-min **close below the neckline**, then the next day **opening below the neckline** (NIFTY 10,670).
- Structure: normally a credit spread, but on this pattern make it a **debit** (more long puts): sell 1 × 10,700 PE
  (the neckline strike) at 105, buy 2 × 10,650 PE (slightly OTM) at 82 each → debit **59 points** (lot 75 → ₹4,425).
- Exit: at the **50 % Fibonacci retracement** of the prior up-move (NIFTY 10,445) with a **positive hourly RSI
  divergence** appearing — long puts +286, short put −172 → **+114 points = ₹8,550 (~193 %) in 5 days**.
- Also cites 2 Feb 2018: hourly sloping H&S → NIFTY fell ~600 points in 5 days.
- His claim: "90 % of the time > 200 % profit" — no record is shown; treat as marketing.

### L91–92 · NIFTY on the 2019 election-result day (event / volatility crush) · [lecture 14851576](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/14851576)
- Day before results (NIFTY 11,730, also weekly expiry next day): option-chain **max call OI at 12,000**, **max put OI at
  11,500** → expected range. ATM premiums very high (11,700 CE 253) → **do not buy options before an event: IV collapses
  after it.**
- Trade: **buy NIFTY futures + sell 12,000 CE (126.60) + buy 11,500 PE (120.45)** — the sold call's premium pays for the
  put; risk ≈ 200 points below 11,500; profit if NIFTY rises toward 12,000 while the call loses its event premium.
- Result day (L92 · [lecture 14857518](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/14857518)): NIFTY touched 12,040 then fell back. Intraday he exited when the **5-min MA set gave
  an exit**, **Bollinger Bands turned down** and **RSI showed a negative divergence**: futures sold ~11,980, 12,000 CE
  bought back at 88 (sold at 126.60). Both max-OI strikes expired near zero (12,000 CE ₹1, 11,500 PE ₹0.90) — NIFTY
  stayed between them. Reported ~₹30,000. Also noted a long trigger earlier that day: **gap-up hammer closing above the
  previous day's high** with a bullish MA crossover.

### L121–122 · Short strangle (with stops) · [lecture 9481802](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9481802)
- Sell an OTM call at range **resistance** and an OTM put at range **support**; profit if price stays inside.
- **Stops just beyond the range** (example IFCI range 20–35: sold 35 C and 20 P for 0.40 total × 22,000 = ₹8,800; stops
  at 35.5 and 19.5). Result: closed inside the range at expiry (kept the credit).
- Our evidence: A-193 short strangle FAIL in the current regime; A-196 weekly strangle CI includes 0; A-197 forward
  tracker running. **A-213 (24 Oct): this lecture's rule on NIFTY weeklies (strikes at the 10-day range edges or the max-OI
  strikes, stops 0.2 % beyond) FAIL** — positive both years but the validation CI includes 0; smaller worst cycle than
  A-196, smaller profit too.

### L123–124 · Collar (monthly income) — stock + protective put + covered call; not an index signal.

### L125 · Long call butterfly · [lecture 9517784](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9517784)
- Buy 1 lower, sell 2 middle, buy 1 upper (same expiry, middle halfway); for a **sideways forecast**; earns time decay
  with limited risk; max gain if price ends at the middle strike.

### L126–127 · Long straddle before events · [lecture 9481818](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/9481818)
- Buy ATM call + ATM put when a **big move is expected in a short time** — results, budget, elections, major news.
- Rules: **book both legs at ~200 % of the premium paid** (event effects are short-lived); if there is no big move,
  **exit quickly**; after a move, sell the profitable leg (trail it with a trendline) and keep the other in case of a
  retracement.
- Examples: Gujarat poll results — NIFTY −190 points in 30 min then +348 in the next hour (~300 % on both legs);
  Budget 2018 — bought the Feb 10,700 straddle; NIFTY rallied to 11,170 before the budget then fell sharply after.

### L128–129 · NIFTY Budget 2020 · [lecture 18406190](https://www.udemy.com/course/option-trading-option-strategies-with-technical-analysis/learn/lecture/18406190)
- Entered **~15 days before the budget** (16 Jan 2020): NIFTY at its all-time high (~12,350) inside a **broadening
  pattern** with **multiple negative RSI divergences**. No sellers at the ATM strike, so bought **12,200 PE at 136.65** and
  **12,400 CE** (a strangle, total cost 317.15 by his figures).
- Budget day (1 Feb 2020): NIFTY fell to ~11,661; 12,200 PE at 569.70, CE at 3.30. **Exited near 11,700** — the
  **38.2 % Fibonacci retracement** of the swing — for > 50 %.
- Follow-up: after a sharp fall expect a sharp pullback — **buy calls on the next session's gap-down if a support
  holds**: bought 11,700 CE at 69 on Monday → 369 by Wednesday (NIFTY 12,085, near a prior swing high/resistance →
  exit).

### Sections 8–10 — index rules worth testing (my extraction)
| # | Rule | Precise version for a test | Our evidence |
|---|---|---|---|
| U13 | Event long straddle: buy ATM straddle before a scheduled event, book at +200 % or exit soon after if no move | NIFTY events in our data: Union Budget (1 Feb 2025, 1 Feb 2026), RBI MPC decisions (10:00 IST), election results. Buy ATM straddle at the open of event day (or day before), exit at +X % / 1 h after the event | **not tested**; A-192 (expiry-day strangle +20 %) and A-195 (opening straddle +10 %) FAIL on ordinary days |
| U14 | Post-event IV crush: sell the max-OI OTM strike after the event / don't buy before it | Sell straddle/strangle right after a scheduled announcement | **not tested**; small n (≈ 14 RBI + 2 budgets in 2025–26) |
| U15 | Sloping-down H&S on 15-min NIFTY → put back spread / long puts; mirror for inverse H&S | Needs a pattern detector; nearest test is our box/level-break family | box/zone breaks FAIL (A-131..A-145), swing-zone study: no level edge |
| U16 | After a sharp event-day fall, buy calls when the next open holds a support (bounce) | — | A-176 / exhaustion reversal (2 Oct) FAIL |
| U17 | Exit cues: Fibonacci 38.2 / 50 % retracement, positive RSI divergence | Exit variant for multi-hour holds | partly — A-079/A-080 cross exits; divergence not tested (U4) |
