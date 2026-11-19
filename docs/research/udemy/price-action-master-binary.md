# Udemy — "Price Action Master Course For BINARY OPTIONS Advanced" — strategy documentation

Personal research notes from the user's Udemy subscription, read 19 Nov 2026. Written for implementation review.
**Nothing here is registered or tested.**

- Course id 5569202 · instructor Md Sydur Rahman · 14 lectures, 12.5 h · rating 4.5 (115) · last updated 20 Sep 2023.
- Lecture link: `https://www.udemy.com/course/price-action-master-course-for-binary-options-advanced/learn/lecture/<id>`.
- **How it was read:**
  - All 14 lectures were read end to end from the course's own English captions. These are auto-generated, so
    words are garbled in places: "sheer short" = sure shot, "rains" = range, "colour sense" = colour change.
  - About 45 video frames were then sampled across all 14 lectures to check what the drawings show.
  - 20 of those frames are kept in [`price-action-binary/`](price-action-binary/). They are course frames for
    private reference: do not publish them.
- The notes are in my own words; no course text is copied.
- **What the frames add.** The rules are spoken explicitly, so they are recoverable from the captions. The chart
  walk-throughs are not: the captions say "this one" or "here" and the picture is needed. Where a frame settled a
  question, it is cited (fig. NN).

---

## 0. Verdict first (brutally honest)

1. **This is a binary-options course for 1-minute forex.** Every trade is a fixed-payout bet that the next
   1-minute candle (sometimes 2 or 5 minutes) closes up or down.
   - The platforms are IQ Option and Pocket Option, including their broker-made "OTC" weekend feed. The pairs are
     EURUSD, GBPUSD, EURJPY and USDJPY.
   - The screens show payouts of **70–94 %** (fig. 08, 15, 19). At 80 % a bettor must win **55.6 %** of trades
     just to break even; at 70 % the figure is 58.8 %.
   - Nothing in the course is about index options, option prices, theta, spreads or holding a position.
2. **No evidence anywhere.** In 12.5 hours there is no backtest, no trade log, no count of setups, no loss
   series, no account statement. What the course offers instead:
   - Accuracy claims: "99.99 %" (L1), "more than 90 %" (L7, L9), "more than 95 %" when everything is combined
     (L7), and "guaranteed" profit (L1).
   - One student's homework tally of 10 wins in 13 (L8).
   - A demo assignment requiring 8–9 wins in 10 (L12).
   - All chart examples are picked after the fact. The failures that are shown get blamed on a rule that was not
     mentioned before the trade, for example a head-and-shoulders, an "extreme level" or colour alternation.
3. **The money-management tool is a Masaniello progression sold as an edge.** It is "Trusted Binary MM System",
   an Excel sheet (L14, fig. 20).
   - I rebuilt it from the standard recursion and reproduced his numbers exactly: +$20.33 for 4 wins in 10 and
     +$64.36 for 5 wins in 10, on $200 at 1.79 decimal odds. Script:
     [`price-action-binary-masaniello-check.py`](price-action-binary-masaniello-check.py).
   - Simulated over 200 k sessions, the progression **cannot change the expected value**:

     | Per-trade win rate | EV per $200 session (target 4 / 10) | Sessions that lose the whole $200 |
     |---|---|---|
     | 40 % (his "still profitable" claim) | **−$64.1** | 38 % |
     | 50 % | −$17.6 | 17 % |
     | 55.9 % (break-even at 79 % payout) | ≈ $0 | 9 % |
     | 60 % | +$8.2 | 5.5 % |

   - At 50 % with the 5 / 10 target his "$5,148 a month" becomes about −$35.6 per session, and 38 % of sessions
     lose the whole $200.
   - The claims that **"no one can lose ten trades"** and that **"40 % is profitable"** are false.
4. **The rule set is discretionary and self-contradicting in places** (section 6). A strong candle means
   continuation, except after the "third wave", when it means exhaustion. A pattern works, except when a colour
   sequence is "running". Several of these vetoes can only be applied once the outcome is known.
5. **What is genuinely useful** is the operational vocabulary, which is more precise than the textbook pages
   reviewed on 9 Nov (`docs/studies/2026-11-09-textbook-patterns-review.md`):
   - an "extreme level" defined by three kinds of reaction;
   - a flip level used only on its first or second retest;
   - the rule that a pattern needs an earlier momentum and a pullback;
   - a wave-count stage filter;
   - the "master candle" box;
   - the "colour sequence" idea.

   These can be coded and measured. Most of their mechanical cores have already failed here (section 8), so the
   prior is low.
6. **Transfer to our system is weak by construction.** The course's whole edge claim is about the direction of
   the next 1-minute candle.
   - On a 1-minute NIFTY / SENSEX candle that is about 1–2 bp of information. A-212 measured 1.4 bp per trade; the
     9 Nov review called the 1-minute candle "~1 bp".
   - An ATM option round trip (spread + costs) costs more than that move.
   - So even a real 55 % next-candle edge could not be traded with bought options on a 1-candle horizon. It could
     only serve as an entry-timing or filter input to strategies that hold for minutes to hours.

---

## 1. What the course is

| Item | Fact |
|---|---|
| Format | Recorded live Zoom/Telegram classes (L1–L12) plus two solo recordings (L13 OTC, L14 market hours, news and money management). Roughly 25–40 % of L3–L8 and L10–L12 is the instructor reviewing students' homework screenshots. |
| Medium | Hand-drawn candles in Paint over Notepad (fig. 01–07, 09–14, 16, 17), then IQ Option charts (1-minute, 5-second, daily once in L7). |
| Market | Binary options on major forex pairs; OTC weekend feed (L13). |
| Trade | Up or down for one candle of the chart's timeframe (1 min on a 1-minute chart, 2 min on a 2-minute chart); master candle = 5 min; after a chart-pattern reversal "3–5 minutes". |
| Entry style | "Margin of safety": wait until the price pulls back slightly beyond the level, then bet in the setup direction. This gives a better strike price for a binary. The exception is the "5-second confirmation" (S13). |
| Follow-ups mentioned | A separate "volume analysis" course; a YouTube channel; a Telegram group; the paid money-management sheet. |

---

## 2. Vocabulary — operational definitions

These are the building blocks every setup uses. Each row is written so it can be coded on 1-minute bars.

### 2.1 Candles and moves

| Term | Course definition | Coding note |
|---|---|---|
| **Momentum** | More than 3 same-colour candles in a row (4+) (L2) | `run_len ≥ 4`; its first candle = "momentum start point", a level |
| **Strong candle ("80 %")** | Marubozu or big body; a wick only on the side the move came from (fig. 04) | body/range ≥ ~0.7, wick against direction ≤ ~0.1 |
| "70 %" candle | Bullish/bearish body with small wicks | body/range 0.5–0.7 |
| **Natural candle** | Spinning top / small body, both wicks (fig. 04). Reverses at an unbroken level; continues at a broken (flipped) level | body/range ≤ 0.3 |
| **"Less bullish / less bearish" ("40 %")** | Small body with a big wick against its own colour (fig. 04) | weakness candle; reversal warning at an extreme level after many waves |
| Doji | No body (grey on some platforms) | body ≤ 5 % of range |
| News / bad candle | Much larger than recent candles, or long wicks both sides | range ≥ k × median(20) → reject |
| **Exhaustion / climax candle** | A strong candle after the 3rd wave into resistance: ends the move instead of continuing it (L8) | contradicts "strong candle = continuation" unless the stage is known |
| Retracement candle | One weak counter candle after a break, "filling the gap" before continuation | |
| Candle maths | Several small candles combined as one (e.g. three that add up to a doji) (L7) | |

### 2.2 Structure

| Term | Course definition | Coding note |
|---|---|---|
| **Trend** | Uptrend = higher lows and higher highs. A lower high after a higher high = "sequence broke" → stop taking buyer setups (L1) | swing pivots |
| **Major vs minor trend** | Trade only when the larger swing trend and the current leg agree (L1, L10) | |
| **Stage / wave count** | Wave 1–2 after a reversal = early stage: patterns with the trend work. Wave 3+ = late stage: expect a range, a chart pattern or a reversal at the next level; with-trend patterns there fail (L8, L9). The count restarts after a chart-pattern reversal. | impulse legs since the last reversal pattern |
| Range / sideways | Back-and-forth between two levels | |
| Channel | Parallel trendlines through 2 lows and 2 highs (L10, fig. 13) | |
| **Good market** | Medium candles, small wicks, smooth legs, ranges that break cleanly. "Left side good → right side good" (L14) | e.g. median body/range, doji share |
| **Bad market** | Many dojis, tiny candles, long wicks, jumpy; check on the 5-second chart → no trades (L3, L14) | doji share above a threshold over the last N candles |
| Psychological / logical movement | A repeating colour sequence in the recent swing (S12) | |

### 2.3 Levels (L2, L3, fig. 02, 03, 05)

| Level | Definition | How the course uses it |
|---|---|---|
| Support / resistance | Where buyers / sellers entered. Drawn **body to body**, not wick to wick | |
| **Extreme support / resistance** ("extreme buyers' / sellers' entry point") | A level that has shown all three reactions: a candle **closed exactly at it** then reversed, a **wick rejection**, and a **break that reversed back**. Momentum start points count too. | Price returning here after several waves → expect a range or reversal; never trade continuation into it |
| **Strong level / SNR (flip)** | Support → break → resistance, or the reverse. Needs 3 points (S, break, R). Drawn as a narrow 2-line zone, body-based | Range: fade the 1st and 2nd retest only, never the 3rd. Trend: trade only the with-trend retest (= break-retest) |
| Trendline support / resistance | Body to body, 2 points, trade the 3rd touch | S10 |
| **Hidden 1: gap up / down ("jump")** | Next candle opens beyond the prior close, both candles the same colour; the gap is a zone | Avoid a trade that closes exactly at it; after a momentum break, trade the retest beyond it |
| Hidden 2: price-difference level | Next candle opens slightly back inside the prior candle, then continues; the zone between the prior close and the new open | same |
| Hidden 3: 50 % of a large (non-news) candle | | same |
| Hidden 4: shaving level | A candle with no wick at one end (open = low or close = high) | same |
| Hidden 5: colour-change level | After a run of one colour, the first opposite candle: zone from its open to the extreme of the pair | Ranges only, and only after it has already rejected price twice |
| Hidden 6: doji ends | Gravestone high, dragonfly low, long-legged both ends | Ranges only; 50/50 in trends; many dojis = bad market |
| Hidden 7: round number | e.g. 1.35800 (5-digit FX) | Acts as a flip level and an extreme level |
| Nearest levels matter | Hours-old levels "sometimes" work; the closest levels matter most | |

**Universal level rule (L3, L4).** A candle that closes exactly at an unbroken level is a no-trade: it is 50/50,
since the level may reverse it or the momentum may break through. After a momentum candle breaks the level,
trade the next candle that comes back beyond the level, in the break direction. Price either retests with a wick
or prints one retracement candle, which the instructor says happens about 80 % of the time (L12).

### 2.4 Pattern key levels (L5–L8)

| Pattern | Shape | Key level(s) |
|---|---|---|
| Bullish / bearish engulfing | Second candle opens near the first's close and closes beyond its open, with its close near that open | First candle's open (the break point) plus any other level the second candle broke |
| Outside bar | Engulfing whose second candle is much bigger (by the course's own inconsistent test: closes past 50 % or not) | Only tradable if it **broke a level**; key = that level, else its 50 % and the break point |
| Piercing line / dark cloud cover | Second candle opens at or beyond the first's close (may gap) and closes past the first's 50 %, but not past its open | 50 % of the first candle |
| **Inside bar (continuation)** | After a momentum candle broke a level: one weak counter candle opening at the momentum candle's close that fails to reach its 50 % — and **closes on the far side of the broken level** | Rated "sure shot" / "90 %". Bet on continuation |
| Harami | Big "mother" candle, then a small "baby" that **gaps back inside** and closes inside it | Baby's open / the level |
| Morning / evening star | Trend candle, 1–2 small candles (any colour), opposite candle (or candle maths of several) | First candle's open (break point), or its 50 % if not broken |
| Wide range bar (WRB) | Larger-than-normal trend candle; the next 2 candles inside it, the 2nd not breaking the WRB's open | WRB open |

---

## 3. Context rules applied to every setup

All of these come from L1–L11. They are the course's real content: the claimed accuracy comes from these
filters, not from the patterns.

| # | Rule | Source |
|---|---|---|
| X1 | Trade only in the direction of the trend: bullish patterns in uptrends, bearish in downtrends. Counter patterns are ignored. | L4–L8 |
| X2 | The ideal trade is the **first pullback after a reversal**: a chart pattern at an extreme level (S1), the neckline breaks within 1–3 candles, then a retest or retracement candle, then any with-trend pattern. Claimed ">90 %". | L7, L9 |
| X3 | Late stage (3rd+ wave): stop with-trend trades near an extreme opposite level; only reversal patterns there, and only after visible weakness (40 % candles, shrinking bodies). | L4, L6, L8 |
| X4 | No trade when the entry candle closed exactly at any level (extreme, flip, hidden, round number). | L3, L4 |
| X5 | No trade during a colour alternation (R,G,R,G…), which "overrides every pattern". This applies especially where the same area showed alternation before. | L5, L6, L11 |
| X6 | No trade in a bad market; none 15 min before to 10–20 min after high-impact news; prefer the London–New York overlap. | L3, L13, L14 |
| X7 | Margin of safety: wait for a pullback slightly beyond the key level; if it does not come, skip (or use S13). "Avoiding a trade is better than losing." | L2, L12 |
| X8 | Use a level / pattern setup only once or twice: the 3rd point is safe, the 4th is not. | L2, L4, L10, L11 |

---

## 4. The setups (S1–S14)

Each row: the course's rule, its claim, what the frames show, and a coding sketch for a 1-minute index chart.
"Expiry" = the binary horizon. For us it becomes the measurement horizon (section 7).

### S1 — Chart-pattern reversal at an extreme level (L9, fig. 12)
- **Context:** 3–4 impulse waves, weakness, price at an extreme opposite level (horizontal or trendline), or a
  sudden range.
- **Pattern:**
  - Double / triple top (bottom) or head-and-shoulders (inverse), inside the range.
  - The legs between tops must be clean: no opposite-colour candle inside a leg.
  - Neckline = the last swing extreme between the tops.
- **Trigger:** the neckline breaks **within 1–3 candles** of the last top. If it takes 4+ candles, expect a range
  and wait for its break.
- **Entry:** after the retest or retracement candle, when a candle breaks the retracement extreme ("start"), take
  every with-trend setup. Expiry 1 candle, or 3–5 min from the start.
- **Claim:** ">90 %" for the first pullback setups after it.
- **Code:** pivot detector; equal-tops tolerance; neckline-break bar count ≤ 3.

### S2 — Trendline "third point, second candle" (L2, L3, fig. 01)
- **Context:** strong trend.
- **Points:**
  - Point 1 = the lowest low of a 2-candle reversal pair, after which **2 candles rose with no retracement
    candle**. Wicks are used here, unlike ordinary trendlines.
  - Point 2 = the same again, with the line passing through both candles of the pair and no break between the
    points.
- **Entry:** on the 3rd touch the first candle forms the reaction, then bet on the **2nd candle** in the trend
  direction, slightly beyond the line. 1-candle expiry.
- **Skip if:** a round number or an extreme opposite level is at the touch.
- **Admitted failure (L3):** a 3rd touch failed after "too many waves" because a head-and-shoulders formed.

### S3 — Flip level ("strong level") retest (L2, fig. 02)
- **Range market:** fade the 1st and 2nd return to an S→R / R→S flip zone; never the 3rd.
- **Trend market:** only the with-trend flip (resistance that became support in an uptrend), entered slightly
  beyond the zone, 1-candle expiry.
- **Skip if:** the pullback into it was a momentum run (≥ 4 candles) from an extreme opposite level.
- This is break-retest, already live here as **brtr-v1** (ORH/ORL/PDH/PDL, retest within 20 min and 0.3 ATR,
  runner exit). New element: the **1st / 2nd retest only** limit and body-based zones.

### S4 — Weak candle continuation (L4, fig. 06)
- **Rule:** in a momentum, a hammer / spinning top / doji / inverted hammer of the trend colour with no level
  ahead means continuation.
- **The "sure shot" variant:**
  - A momentum breaks a minor (not extreme) level.
  - A weak counter candle comes back to the broken level and closes beyond it.
  - Enter slightly beyond the level, with the trend.
- **Reversal variant:** the same candle at an extreme opposite level after many waves means reversal.

### S5 — Range third touch (L4, fig. 07)
- **Context:** a clear range whose edges have reversed price twice.
- **Entry:** fade the 3rd touch on any weak or indecision candle at the edge. The 4th+ touch is unsafe.
- **Claim:** a lower win ratio than the trend setups.

### S6 — Engulfing / outside bar after a pullback (L5, fig. 09)
- **Context:** trend (X1, X2).
- **Pattern:** a with-trend engulfing after a 1–2 candle pullback.
- **Entry:** slightly beyond the key level on the next candle's pullback. **A second entry is allowed** after one
  weak retracement candle (2 trades per setup).
- **Third-pattern rule (L6):** if the last two with-trend patterns ran with no retracement candle, the third may
  be entered directly.

### S7 — Piercing line / dark cloud cover (L6, fig. 10)
- As S6, with the key level at 50 % of the first candle.
- **Reversal use:** only at an extreme level, late stage, after visible weakness.

### S8 — Inside bar continuation after a level break (L6, L7)
- **Rule:**
  - A momentum candle breaks a minor level.
  - The next candle is a weak counter candle that opens at its close, stays inside it, fails to reach its 50 %
    and closes beyond the broken level.
  - Bet on continuation on the next candle.
- **Claim:** "sure shot", "90 %".
- **Not valid:** inside a range, or without a level break.

### S9 — Harami (L8)
- **Rule:** a gap-back-inside baby candle after a big mother candle.
  - With-trend after a pullback at a support or flip level in an early stage; or
  - reversal at an extreme level after weakness.
- **Fails:** counter-trend, or with-trend at a late stage into an extreme level.

### S10 — Morning / evening star (L7, fig. 11)
- **With-trend:** first pullback after a chart-pattern reversal ("best").
- **Reversal:** at an extreme level after many waves ("most of the time").
- **Key levels:** the break point or 50 %.

### S11 — Channel third touch (L10, fig. 13)
- **Channel:** 4 points (2 lows + 2 highs) joined by trendlines.
- **Uptrend channel:** trade only the lower line (with-trend) on the 3rd touch, on any weak candle; never fade the
  upper line, since a momentum can break it. Downtrend: mirror.
- **Use once** (twice at most).
- **The course's own examples:** the 3rd touch won and the 4th lost, twice.

### S12 — "Psychological / logical movement" colour sequence (L11, fig. 16)
- **Rule:**
  - Within about 30 min, if a colour sequence has repeated twice in the trend (e.g. 1 red then resume; G,G,R;
    G,G,G,R), bet that it repeats a third time: enter in the trend direction on the candle that completes it.
  - Never the 4th time.
- **Alternation (R,G,R,G):** the "most dangerous" case; patterns fail inside it. Only the 3rd alternation in the
  trend direction is traded.
- **Sequences carry across areas:** an area that alternated before will alternate again.
- **Wide range bar:**
  - Strong trend; a WRB; the next 2 candles inside it, the 2nd not breaking the WRB open.
  - Enter with the trend on the next candle.
- **Code:** a colour string over the last N candles; detect a period-k repeat × 2.

### S13 — 5-second confirmation (L1, L10, fig. 14, 15)
- **When:** a setup is valid (break + inside bar, flip retest) but the new 1-minute candle gaps away and may not
  come back.
- **Rule:**
  - Open the 5-second chart.
  - If one of the first 6 five-second candles (the first 30 s) is a medium-bodied candle in the trade direction,
    enter at once.
  - Reject dojis, tiny bodies, opposite colours and extra-large 5-s candles.
- **Default is still margin of safety:** a 5-s entry is mid-candle, so a weak close loses.
- **Our analogue:** first-30-second tick confirmation for a missed retest.

### S14 — Master candle (L12, fig. 17, 18)
- **Context:** trend established (after a reversal pattern, or 1–2 waves in, or after a support break); the
  market pauses in a small range.
- **Pattern:**
  - A larger-than-normal (not news-size) candle; its full range **including wicks** is the box.
  - The next **4 candles** (dojis count) must not close a body outside the box; wick pokes are allowed.
- **Trigger:** within the next 1–3 candles, a candle breaks the box **in the trend direction** → **5-minute
  expiry** with the trend. A counter-trend break → nothing. No break within 3 → nothing.
- **Strongest when** the box is also a double-bottom / top neckline at an extreme level.
- **Frame 18:** the only chart example, on EURJPY 1-minute: a box, then a bearish break. The live chart froze
  during the demo.
- **Code:** big = range ≥ 1.5 × median(20) range and ≤ news cap; `inside4`; a trend filter (X1/X2); break
  within 3; measure the 5-minute outcome.

### OTC rule (L13, fig. 19)
- **Rule:** on the OTC feed, trade only trend + momentum: price below (above) the **three moving averages** on
  the chart (spoken as "50, 30, 20"; fig. 19 shows three MAs, orange / green / purple, type not stated), after a
  chart pattern breaks. Avoid ranges.
- **Note:** the instructor himself calls OTC a broker-made, "not real" market; beginners should practise only
  there.

---

## 5. Sessions, news and money management (L13, L14)

| Item | Rule | Our equivalent |
|---|---|---|
| Hours | London–New York overlap. For Cambodia / Vietnam 20:00–23:00 local (19–23 platform time) is "trendy, low volatility" | NSE session-phase evidence already in the ledger (opening drive, 14:00+ expiry) |
| News | Avoid red / yellow events on an economic calendar: stop 15 min before, return 10–20 min after once candles are normal; or avoid :00 / :15 / :30 / :45 around releases | RBI / Budget / CPI and US data days; untested here as a filter |
| Pairs | EURUSD, GBPUSD, AUDUSD, EURGBP, EURJPY, USDJPY, USDCHF | n/a |
| Money management | Masaniello sheet: session of 10 trades, 4–5 wins required, stakes rise after losses (fig. 20) | **Reject.** It does not change EV and adds ruin risk (section 0.3). Our risk rules (follower-risk v14, sell-risk) are the opposite design |

---

## 6. Contradictions, gaps and hindsight

- **Strong candle:** continuation in L3 / L4, but "exhaustion / climax" when it comes after the 3rd wave (L8). The
  student was told off for not knowing which, and the deciding factor (wave count) is judged by eye.
- **Engulfing vs outside bar:** L5 and L6 give different tests (bodies near each other vs a 50 % cover); the
  instructor relabels students' patterns after the outcome.
- **Extreme levels both ways:** an extreme level is a reason to avoid continuation (L2) and a reason to expect
  reversal (L4). After it breaks, it is "still risky" (L4) but also a continuation key level (L4 chart).
- **Psychological movement as a universal excuse:** in L6 and L11, perfect patterns that lost were explained by
  alternation spotted afterwards. No rule says how many alternations, over what window, make a "running"
  sequence before the trade.
- **Every chart example is chosen after the fact.** No walk-through counts the setups it skipped or that failed.
  The few failures shown (trendline 4th touch, channel 4th touch, piercing line) each produce a new veto rule.
- **No sizing of "a little below the level":** it is never quantified in pips; it depends on watching the live
  candle.
- **Win-rate claims are inconsistent:** 99.99 % (L1), 90 % (L6, L7, L9), 95 % combined (L7), 80–90 % demo target
  (L12), and "40 % is enough with my tool" (L14). Taken together these give away that the edge is not known.
- **"Every concept works on any timeframe and market"** (L7) is asserted, shown once on a daily chart, and never
  tested.

---

## 7. What transfers to our system (NIFTY / SENSEX index options, PAPER)

| Course element | Transfer | Why |
|---|---|---|
| 1-candle binary bets | **No** | We buy options; 1-minute index moves (~1–2 bp) are below an ATM round trip. Even a real 55 % next-candle edge loses after costs at that horizon |
| Next-candle direction as a **signal** | Measurable | Can be scored as a hit rate on own tick-day 1-minute index candles, at no trading cost |
| Context filters (X1–X8) | Possible as entry filters on existing strategies | Similar filters failed before (section 8): treat as low prior |
| Flip level 1st / 2nd retest only (S3) | Possible as a brtr variant | brtr-v1 is live; new strategies must not disturb it ([[new-strategies-not-tweaks]]) → a separate shadow version |
| Master candle box (S14) | Possible as a new strategy, 5-min horizon → a bought-option hold of 5–30 min | Untested here in this exact form; the compression-break family is weak |
| Colour sequence (S12) | Measurable only | A test of serial dependence in 1-minute candle colour; prior ≈ none |
| Hidden levels (2.3) | Low | Levels held no better than placebo in A-315; adding 7 more level types multiplies false positives |
| 5-second confirmation (S13) | Our tick data can do this | Only matters if a parent setup has edge |
| News / session filters | Already partly in the system | No new test needed unless asked |
| Masaniello | **Never** | Section 0.3 |

---

## 8. Prior evidence in this repo (checked before proposing anything)

From `docs/EXPERIMENT-LEDGER.md` and `docs/studies/2026-11-09-textbook-patterns-review.md`:

| Course idea | Mechanical core | Ledger rows | Result |
|---|---|---|---|
| S1 double / triple top, H&S | Failed extreme, then a neckline break the other way | A-038, A-333, A-012, A-039 | **FAIL** (PF 0.97 held-out < 0; −6.0 L; −₹46 / lot; PF 0.47) |
| S4 / S6 / S7 / S10 continuation after a pullback | Pullback, then resumption | A-035, A-354, A-244, A-064 | **FAIL** (held-out −1.80 L; 1 win in 22; 0 trades; one-trade profit) |
| S6–S10 candle formations | 1–3 candle entry | A-200 (233 trades, −2.09 % / trade), A-201 (16 trades), A-202 (0 / 15) | **FAIL** |
| S5 range-edge fade | | A-039 PF 0.47; A-495 credit spread at S/R 34 / 46 stopped by the break | **FAIL** |
| S14 master candle / compression break | Box break | A-244 squeeze (PF 0.26), A-015, A-017, A-241, A-131..A-135 zbf, A-417 srb (A-140 cbk incomplete) | **FAIL** stand-alone (wins only on 0DTE trend days). The exact "big candle + 4 inside + break ≤ 3 with trend" form is **untested** |
| S3 break → retest | Flip retest | A-037, A-065, A-349, A-355 brtr-v1 | **PASS, LIVE** (28 tick days +6.94 L PF 2.25). The 1st / 2nd-retest limit is **untested** |
| Level quality (extreme / hidden / round) | Levels as reaction points | A-315 placebo, A-069, A-400 level trader FAIL, A-391 sweep FAIL | **FAIL** |
| Stage filter ("late stage", wave 3+) | Trend maturity | A-385 (a trend proven at the exit is mostly spent), A-394–A-396 regime gates FAIL | No entry rule from it survived |
| S12 colour sequence | Serial dependence of candle colour | none | **Untested** (expect ≈ 0) |
| S2 / S11 trendline / channel 3rd touch | Sloped-line touch | A-419 (pasted box-wedge with trendlines) | A-419 **FAIL** (as written it was mostly a range fade). The exact "third point, second candle" rule is **untested** |
| MA trend side (L13) | Price vs MAs | ma-cross live (mac-v34); A-205, A-206 MA filters FAIL | Covered |

---

## 9. Candidate tests (not registered; the user decides)

Every one needs its own ledger row with the rule fixed before data is read. Per [[tick-days-only-replays]], use
own-capture tick days; bar days only after asking.

| ID | Candidate | Cost | Prior | Recommendation |
|---|---|---|---|---|
| **P1** | **Next-candle screen (descriptive):** code S2, S4, S6–S12 and S14 exactly as written, with the course's filters X1, X3, X4, X5, X8. Measure on NIFTY + SENSEX 1-minute index candles of every tick day: setup count, next-1 and next-5 candle direction hit rate, mean move in bp, a shuffled-time null, and both halves. Pass bar fixed in advance: hit rate ≥ 55 % with CI above 50 % in both halves **and** mean 5-minute move ≥ an ATM round-trip cost. | Small (index candles only, no option replay) | Low | **Do first.** It tests the course's central claim directly and cheaply. If nothing passes, the course is closed |
| P2 | Master candle as a strategy (mcs-v1): S14 + X1/X2 trend context; buy ATM with the trend; hold 5 min (course) vs runner exit (brtr-style) | Medium (engine strategy + replay) | Low–medium | Only if S14 passes P1 |
| P3 | brtr shadow variant: retests 1 and 2 only, body-based zone | Small (one parameter) | Medium (parent is live) | Only after P1. Shadow only, must not touch live brtr-v1 |
| P4 | Colour-sequence veto on live entries (X5): skip entries while a 4-candle alternation runs | Small | Very low | Only if P1 shows alternation predicts losers |
| P5 | Late-stage veto (X3: wave ≥ 3 + extreme level ahead) on live with-trend entries | Medium | Low (A-385, A-394–A-396) | Not recommended unless P1 shows a stage effect |

---

## 10. Lecture index

| # | Id | Length | Topic | Figures |
|---|---|---|---|---|
| L1 | 39952710 | 30 min | Course overview; trend sequence; 5-s confirmation; hidden levels; major/minor trend | — |
| L2 | 39952956 | 67 min | S/R, extreme levels, momentum, trendline strategy (S2), strong flip level (S3) | 01, 02, 03 |
| L3 | 39953022 | 72 min | Homework; candle power table; 7 hidden levels; round numbers | 04, 05 |
| L4 | 39953054 | 66 min | Handling hidden levels; single-candle psychology (S4); range third touch (S5) | 06, 07, 08 |
| L5 | 39953076 | 62 min | Engulfing / outside bar (S6); OTC warning | 09 |
| L6 | 39953124 | 65 min | Piercing line / dark cloud (S7); inside bar (S8) | 10 |
| L7 | 39953176 | 56 min | Course summary framework; daily-chart demo; morning / evening star (S10) | 11 |
| L8 | 39953212 | 63 min | Harami (S9); wave stage; exhaustion candle; student 10 / 13 | — |
| L9 | 39953252 | 60 min | Candle-size sequence; chart patterns (S1) | 12 |
| L10 | 39953272 | 51 min | Channel (S11); 5-second confirmation (S13) | 13, 14, 15 |
| L11 | 39953304 | 49 min | Psychological / logical movement (S12); wide range bar | 16 |
| L12 | 39953358 | 49 min | Master candle (S14); rejection Q&A; 2-minute charts; 80–90 % demo assignment | 17, 18 |
| L13 | 39953386 | 13 min | OTC strategy (3 MAs); news; pairs | 19 |
| L14 | 39953400 | 44 min | Bad / good market; news calendar; hours; pairs; Masaniello money management | 20 |

### Figures ([`price-action-binary/`](price-action-binary/), course frames, private reference only)

| Fig | File | Shows |
|---|---|---|
| 01 | `01-l2-trendline-and-pattern-sketches.jpg` | Trendline 3rd-touch sketches, pattern boxes, downtrend line with entry mark |
| 02 | `02-l2-strong-flip-level.jpg` | Support → break → resistance flip zone in a range |
| 03 | `03-l2-extreme-levels-range.jpg` | Extreme support / resistance with the three reaction types boxed |
| 04 | `04-l3-candle-power-table.jpg` | Strong (80 %), bullish, natural and less-bullish candle classes, both colours |
| 05 | `05-l3-hidden-levels.jpg` | Gap, price-difference, colour-change, doji and round-number (3,100) levels |
| 06 | `06-l4-weak-candles-in-trend.jpg` | Weak candles: continuation at flipped levels vs reversal at the top level |
| 07 | `07-l4-range-third-touch.jpg` | Range with marked touches: the 3rd touch is the trade |
| 08 | `08-l4-iq-option-80pct-payout.jpg` | IQ Option EURUSD binary, 1-minute, payout +80 % |
| 09 | `09-l5-engulfing-in-structure.jpg` | Engulfing / outside bar setups placed in a reversal-then-trend structure |
| 10 | `10-l6-piercing-darkcloud-structure.jpg` | Piercing / dark cloud / inside bar along a V-shaped structure |
| 11 | `11-l7-star-patterns-in-structure.jpg` | Range → breakout → stars at pullbacks and at the top |
| 12 | `12-l9-double-top-at-extreme-level.jpg` | Double top at an extreme level after an impulse |
| 13 | `13-l10-channels.jpg` | Up and down channels with 3rd-touch ticks on the with-trend line only |
| 14 | `14-l10-five-second-confirmation.jpg` | Gap after a break; valid vs invalid 5-second confirmation candles |
| 15 | `15-l10-70pct-payout-5s-chart.jpg` | IQ Option 5-second chart, payout +70 % |
| 16 | `16-l11-colour-sequences-wrb.jpg` | Repeating colour sequences and wide-range-bar boxes |
| 17 | `17-l12-master-candle-sketch.jpg` | Master candle boxes along a reversal-trend-reversal path |
| 18 | `18-l12-master-candle-eurjpy.jpg` | The one live master-candle example (EURJPY 1-minute) |
| 19 | `19-l13-otc-three-moving-averages.jpg` | EURUSD OTC with three moving averages, payout +87 % |
| 20 | `20-l14-masaniello-sheet.jpg` | "Trusted Binary MM System" sheet: 10 trades, 4 wins, +$20.33 |
