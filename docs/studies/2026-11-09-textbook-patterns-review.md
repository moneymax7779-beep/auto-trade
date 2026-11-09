# Textbook chart patterns, candlesticks and indicators vs. the ledger: review and design (9 Nov 2026)

Request (user, 9 Nov): review five articles (Samco chart patterns, Groww candlesticks, a TradingView NIFTY
option-strategy note, AlgoTest "best indicator for option trading", Dhan chart patterns) and design "a best
strategy that works on any scenario" for NIFTY and SENSEX index-option buying, using OI, futures volume and
bid/ask. Real money is at stake, so this is written the way the ledger is: what was tested, on which days,
what it returned. Nothing here is a new replay; every number is quoted from `docs/EXPERIMENT-LEDGER.md`
or a study already in `docs/studies/`. Where an article's idea has not been tested here, it says so.

## 1. What the five articles actually contain

| Article | Content | Rules with numbers | Statistics / backtests | NIFTY / SENSEX specifics |
|---|---|---|---|---|
| Samco, "10 best chart patterns" (9 Nov 2026) | Cup-and-handle, rounding bottom, head-and-shoulders (both), triangles (3), double / triple tops and bottoms, flags, pennants, rising wedge | Entry on the neckline / boundary break "with volume", stop beyond the pattern, target = pattern height | None. "Triple tops are more reliable than double tops" is asserted, not shown | None |
| Groww, "38 candlestick patterns" (23 Sep 2026) | 21 bullish + 17 bearish one-to-three-candle formations | None at all (formation + "what it indicates") | None | None |
| TradingView, "NIFTY option strategies guidance" (Dec 2025) | Definitions of long call / put, bull call and bear put spreads, long / short straddle and strangle, protective put, Greeks | None | None | Lot size "50" (stale), weekly expiry "every Thursday" (stale: NIFTY expires Tuesday since Sep 2025), RBI / Budget as event days |
| AlgoTest, "best indicator for option trading" (Aug 2026) | RSI, Bollinger, IV, IMI, MFI, PCR, OI price/OI matrix, MACD, EMA 20/50; indicator combinations | Only the standard overbought / oversold levels (RSI 70/30, MFI 80/20, PCR 1) and EMA 20/50 | None. Promotes the vendor's backtester | Generic Nifty / BankNifty mentions; "highest call-OI strike is resistance, highest put-OI strike support" |
| Dhan, "top trading patterns" (18 Feb 2026) | Head-and-shoulders, double top / bottom, ascending / descending triangle, wedges, broadening formation | Entry after the break, stop beyond the shoulder / high / trendline, measured target | None. The FAQ itself says false breakouts are "more frequent" intraday | None |

Verdict on the sources: five pages of definitions. Not one gives a tested rule, a sample size, a win rate, a
profit factor or a drawdown, and none is about index options or expiry days. They are what a textbook
chapter is: a vocabulary. A vocabulary is not a strategy, and the question "which of these works" cannot be
answered from them. It can be answered from the ledger, because almost every mechanical core these patterns
share has already been replayed here on own-capture tick days and on two years of Upstox bars.

## 2. Each idea against the ledger

A chart pattern is a story told about three or four mechanical events: a level break, a failed break, a
retest, a compression, a continuation after a pullback, a volume surge, an indicator cross. Those events are
what a replay can test. The table maps every article idea to the rows that tested its core.

| Article idea | Mechanical core | Ledger rows | Result | Verdict |
|---|---|---|---|---|
| Double / triple top or bottom, head-and-shoulders | New extreme not held, then the neckline (swing) breaks the other way | A-038 failed-breakout, A-333 mac reverse, A-012 break-reclaim, A-039 range-edge fade | A-038 10 trades PF 0.97, held-out < 0; A-333 −6.0 L; A-012 call after a reclaimed support break −₹46 mean per lot; A-039 PF 0.47 | **FAIL** (four independent reversal constructions, none positive out of sample) |
| Triangle / wedge / pennant break, "box" break | Close beyond a compressed range, volume confirms | A-015 range break on volume surge, A-016 volume lead, A-017 ecr-v9 box level, A-241 mps/mpa box break, A-244 Squeeze / Tight Box, A-131..A-135 zbf zone break | A-015 +₹63 per lot carried by one expiry trade; A-017 box trades −₹31 K without 22 Sep; A-241 all profit = one 29 Sep trade; A-244 Squeeze 16 trades PF 0.26, −₹4.67 L; zbf passes alone on bars but −7.5 L inside the live set (A-145) | **FAIL** as a stand-alone buy. Compression breaks lose on the first break and pay only on 0DTE trend days, which etr / wbr already take |
| Flag / pennant = continuation after a pullback in a trend | Pullback to EMA / VWAP, then resumption | A-035 trend-pullback scalper, A-354 bcr-v1 continuation, A-244 VWAP pullback, A-064 trend-day rider | A-035 PF 1.23, held-out −₹1.80 L; A-354 ticks 1 win in 22, PF 0.04, bars PF 1.03 / 0.93; A-244 VWAP 0 trades; A-064 one trade = the whole profit | **FAIL** (four attempts). The pullback's extreme is retaken within 2–20 minutes on most tick days |
| Break → retest → go (the one pattern every article shares) | Level break, pullback to the level, entry on the resumption, runner exit | A-037 brt-v1, A-065 / A-349 / A-355 brtr-v1 | brtr-v1 28 tick days +₹6.94 L PF 2.25; 2025 bars +₹13.8 L PF 1.35; better than v2, v4, v5, v6 on every test | **PASS and LIVE** from 9 Nov (A-356). This is the only textbook pattern that survived, and only with runner exits, no target and no time stop |
| Cup-and-handle, rounding bottom, broadening formation | Multi-day formations | — | Not tested: they form over days, the system holds intraday | Not applicable to 0–2 DTE buying |
| Candlestick patterns (hammer, engulfing, first-candle, pin bar) | Entry on a 1–3 candle formation | A-200 first 10-min candle, A-201 gap + hammer, A-202, A-203 by VIX band | A-200 233 trades, −2.09 % per trade, negative in all three periods; A-202 15 trades, 0 winners; A-201 16 trades in two years | **FAIL**. On a 1-minute index the candle carries ~1 bp of information, less than the option spread |
| RSI / MACD / Bollinger / EMA alignment | Indicator thresholds and crosses | A-205 EMA 5/10 + Bollinger + RSI, A-206 multi-timeframe agreement, A-207 RSI-divergence exit, A-066 / A-332 EMA cross | A-205 CI includes 0, 88 trades ≈ 45 decisions; A-206 second half −12.7 % win 13 %; EMA cross = ma-cross: +9.7 L PF 2.95 on 22 tick days but PF 1.01 on 486 bar days (A-307) | **FAIL** as filters; the EMA cross is live as mac-v34 and is the one indicator rule kept, on tick evidence only |
| VWAP candle / VWAP side | Entry candle relative to VWAP | A-212 (675 NIFTY trades, two years), A-079, A-316 / A-317 | A-212 +0.63 % pooled, negative Jul–Dec 2025, index moves 1.4 bp a trade; A-079 FAIL; A-317 forward (read ~3 Dec) | **FAIL** on bars; VWAP side is a forward test, not a rule |
| "Highest call-OI strike = resistance, highest put-OI strike = support" | OI walls as levels | A-154 expiry pin (78 days), A-301 wall veto, A-057 / A-058 / A-119 wall breaks, A-327..A-341 wbr | A-154 walls hold as often as chance; A-301 the veto removed one winner; wall-break rider wbr-v13 LIVE 8 Nov, wbr-v9 OI signal PASS in A-339 | Walls as **static levels FAIL**; a wall being **unwound** (OI covering) is the live wbr signal |
| PCR as bias, PCR extremes as contrarian | PCR level or drift | A-268 closing PCR → next day, A-228 10:30 OI drift, A-132 one-sided positioning | A-268 FAIL; A-228 test 51.5 % vs 54.5 % for "side of VWAP"; A-132 FAIL | **FAIL** |
| OI price/OI matrix (long build-up, short covering …) | Futures OI state with price | A-233 build-up matrix, A-068 writer flow, A-324 / A-325 persistent backing and writer flip | A-233 no edge; A-068 IC −0.03; A-324 control without OI better; A-325 −28 L | **FAIL** as an entry or exit; the live obc-v13 uses a 3-minute near-ATM OI burst with an EMA cross and is the only OI construction that passed its screen (A-260, +33 L both halves alone) |
| IV: buy low IV, avoid high IV, IV crush exits | IV level / rank filters | A-312 IV-collapse exit, A-313 high-IV skip, wbr-v9 IV ≥ 0.35 skip (A-329) | A-312 cuts winners −9.46 L; A-313 −13.7 L (skips the +10 L 15 Oct trade); wbr IV cap +3.6 L in sample only | **FAIL** for buyers: the biggest winners start in high IV on trend days |
| Long straddle / strangle | Buy both sides before a move | A-008, A-192, A-195, A-285..A-291 | All FAIL or INSUFFICIENT; expiry afternoons priced above realised movement on 14 of 15 index-days | **FAIL** |
| Short straddle / strangle | Sell premium | A-193, A-196, A-204, A-213 (FAIL / INSUFFICIENT); **A-292** 12:00 NIFTY expiry short straddle +50 % / break-even stop | A-292 test year PF 1.40, +₹15,850 a lot a year, 23 of 50 won; tick days 5 trades −₹173 | **PASS (thin)**; plan `docs/plans/2026-11-01-expiry-short-straddle-shadow.md`, not built (needs OMS short support) |
| Spreads (bull call, bear put, iron fly) | Capped risk / capped reward | A-177 iron fly, A-263 spreads, A-293 iron fly on A-292 | all FAIL: the cap removes the runners that carry every buyer here | **FAIL** for this system |
| "Volume confirms the break" | Futures volume ≥ k × median at the break | A-015, A-016, A-185 day-high break with 5× volume, A-228 | A-185 bars n=643 −2.02 % per trade; volume condition "adds nothing" | **FAIL** as a filter. Volume at the break is post hoc: on 14 Oct it marked the winning leg, on bars it marks nothing |
| Bid/ask imbalance | Order-book persistence | inside ecr-v4+ as a sub-score (A-003..A-005); never tested alone | ecr's EARLY state never fired in 6 weeks; ecr-v11 ~break-even (A-320: 2 trades −1.27 L) | **Untested stand-alone**; the data exists (depth snapshots). Low priority: A-320 shows the fill, not the imbalance, is what costs money at size |

Count: of roughly 20 distinct ideas in the five articles, 17 have a tested core here and 16 of those failed
on the same criteria every live strategy had to pass (both halves positive, held-out positive, no single
trade more than half the net, worst day bounded). The one survivor is break-retest with runner exits, which
went live today. That is the brutal part: the material the user sent describes a world in which these
patterns work; 356 ledger rows describe the one we trade in, and in it they do not, not at 1-minute
resolution on 0–2 DTE index options after the spread.

## 3. "Works in any scenario" is the wrong specification

The request asks for a strategy that works on every kind of day, or reacts to the kind of day. The evidence
on both halves of that sentence:

1. **No strategy here earns on every day type, and none should be expected to.** The 27-day chart anatomy
   (`2026-10-25-chart-anatomy-report.md`) found the live set earns on 7–8 trend days, breaks even on swing
   days and pays rent on chop days; the top five trades were 70 % of the P&L; etr's whole +22 L was five 0DTE
   runners. A-320's 25-day replay of the four live strategies: +54.6 L base, of which H1 +50.5 L and H2 +4.1 L.
   Every passing buyer is a trend-day strategy with a small, bounded cost on the other days. The article
   patterns that are supposed to cover the other days (reversals, range fades, straddles) are the ones that
   failed hardest (section 2).
2. **Detecting the day type early enough to act has failed every time.** A-094: an 18-input regime model
   (flow + price) was worse than a coin at 10:15 and no better than "side of VWAP" at 11:00. A-228: the
   10:30 PCR-drift read was 93 % on the days it was designed on and 51.5 % on the test days. A-164: the
   dealer-gamma regime did not separate trend from range. A-314: skipping entries when the day "looks like a
   range so far" cost −13.2 L because the range-like mornings are where mac's turns and etr's expiry
   breakdowns start. A-313 (high-IV skip) and A-312 (IV-collapse exit) removed the biggest winners. A
   classifier that is right 55 % of the time at 11:00 cannot gate a system whose profit is five trades.
3. **Hold-time, not entry, is where the day type matters, and that part is solved as far as the data allows.**
   A-110..A-112 and the obc anatomy (A-260) found one robust exit rule: on trend days hold late (flat 15:15),
   everything earlier (partials, trails, targets, breakeven stops A-311, MFE trails A-352) loses money out of
   sample. obc-v11 → v13, brtr-v1 and etr-v1 all carry that rule.

So the honest design answer is not a smarter single strategy. It is a **portfolio whose members earn on
different day types, sized so the losers' rent is bounded, with the day-type reaction done by the exits and
the loss cap rather than by a predictor**. That is what the live set already is on the buy side. What it lacks
is a member that earns on the days the buyers lose.

## 4. The design, in the evidence's own terms

### 4.1 What stays exactly as it is

The six live PAPER strategies from 9 Nov: ecr-v11, etr-v1, mac-v34, obc-v13, wbr-v13, brtr-v1 (A-356). Each
passed its registered gates; each has a known failure mode and a known cost on its bad days. Per the user's
standing rule (new strategies, not tweaks), nothing in this review changes a live file. Their exposure map:

| Member | Earns on | Pays rent on | Mechanism the articles name, in their words |
|---|---|---|---|
| etr-v1 | 0DTE trend days of the expiring index, 11:00–15:15 | swingy expiry days (entries after the leg has run: F1 in the anatomy) | "trend continuation", "ride the breakout" |
| obc-v13 | opening-hour trend days (09:21–11:01), both indices | gap-reversal mornings, capped by the 0.4 % gap rule | "OI confirms the EMA cross" |
| mac-v34 | days with two-way swings that resolve (the 14 Oct, 30 Oct type) | one-way days (no re-cross: 21 Oct zero trades) and chop whipsaws | "EMA cross" |
| brtr-v1 | non-expiry trend days after a level break and retest | days where the retest is the reversal | "break and retest", the one textbook pattern that passed |
| wbr-v13 | expiry days where a wall is being covered | expiry days with a false covering signal | "OI wall", but the wall going away, not the wall |
| ecr-v11 | rarely trades; break-even | — | "breakout with confirmation" |

### 4.2 The missing member: a seller for the days buyers lose

Every buyer above loses on range and chop days because premium decays while they wait for a move that does
not come. The only construction in 356 rows that **earns on those same days** is A-292: on NIFTY's own expiry
day, at 12:00, sell the ATM straddle; buy it back at +50 % of the credit against you or on a 1-minute close
beyond strike ± credit; otherwise let it settle. Test year (Sep 2025–Sep 2026, out of sample, read once):
PF 1.40, 23 of 50 won, worst day −73 % of one credit, +₹15,850 a lot a year, max drawdown −227 % of a credit.
Thin in rupees, but it is the only PASS whose P&L is negatively correlated with etr's: etr's five big runners
were on exactly the expiry afternoons where the straddle would have been stopped, and the straddle earns on
the 29 of 50 afternoons where etr sat idle or lost.

This is what "reacts to the type of market day" means when a classifier is not available: the book carries
one position that wins if the afternoon trends (etr, wbr) and one that wins if it does not (A-292), with the
stop on each bounded in advance, and the day decides which one pays.

What it needs, none of it built (user, 1 Nov: "don't build now, just document it"):
`docs/plans/2026-11-01-expiry-short-straddle-shadow.md` lists the slice: OMS short support (sell to open,
buy to close), a margin model for the paper risk file, settlement at intrinsic, and the shadow registration
(the next free ledger id, read once at 20 expiries). Until that exists the design in section 3 has a hole on range days
and the honest statement is: the live set is a trend-day buyer with a loss cap, nothing more.

### 4.3 Sizing is the other half of "any scenario"

A-320 (execution stress, in sample): the same 63 trades returned 75 % of their base P&L at 1 s latency and
25 bp adverse fill, and 56 % at 2 s / 50 bp, with the loss coming from **unfilled and smaller entries on the
fastest winners**, not from price. A-353 kept the ₹1.25 L premium budget per entry by the user's decision.
Any attempt to "make it work on every day" by adding members must respect that the capital is shared: orb-v1
(A-082, alone PF 3.17) and zbf-v7 (A-145, alone PASS on bars) each **lost money for the set** by taking
capital from etr on expiry days. The rule from those two rows: a new member is judged inside the set, on the
four-plus-one comparison, never alone. That is also why A-262 is on record as a FAIL for obc-v13 inside the
set even though the user kept it live.

### 4.4 What I would not build, and why

- Any reversal pattern (double top, head-and-shoulders, hammer, RSI extremes): four reversal constructions
  failed; the turns on tick days happen at the open and in the last hour (swing-zones study, 392 index-days),
  not at pattern necklines.
- Any compression break as a buyer: Squeeze −4.67 L, box breaks all one-trade profits.
- Any indicator filter on the existing members: A-096 (rolling PF gate), A-103 (quiet window), A-313 (IV),
  A-314 (range-so-far), A-316 (levels, partials, hour filter) all removed more winners than losers. The live
  members' profit is in their tails; every filter so far has clipped the tail.
- A day-type classifier as a gate: A-094, A-228, A-164. A forward read of the 10:30 drift (A-228's only
  clean follow-up) is cheap and harmless if the user wants it registered, but it must not gate anything until
  it has 25 new sessions.
- Spreads and iron flies: they cap the runner that is the only source of profit (A-177, A-263, A-293).

### 4.5 The one untested item from the articles worth a cheap screen

Bid/ask imbalance as a **standalone** confirmation (AlgoTest lists it implicitly under OI and order flow; the
user names it). It sits inside ecr as a sub-score and has never been read alone. The own capture has depth
snapshots from 28 Sep. A descriptive event study (not a strategy): at each live-member entry on the 28 tick
days, the 60-second bid/ask-quantity imbalance of the ATM option and the future, against the trade's outcome.
Cost: one script, no replay, no held-out use. If the imbalance separates the stopped entries from the
runners by more than the "side of VWAP" baseline (66.7 % at 11:00, A-094), it earns a registered veto test
on the next 20 forward sessions; if not, it closes like A-068 did for writer flow. My expectation, stated
before running it: no edge, for the same reason volume at the break has none (A-185): the book at a
1-minute index level is dominated by market-maker quoting, not by the directional flow the pattern books
describe.

## 5. Summary for the user

1. The five articles contain no tested rule and no statistics. They are vocabulary.
2. Sixteen of the seventeen testable ideas in them have already failed here on own-capture tick days and on
   two years of bars. The seventeenth, break-retest with runner exits, is brtr-v1 and went live today.
3. A strategy that works on every day does not exist in this data, and the attempts to predict the day type
   early enough to switch (A-094, A-228, A-164, A-314) were all worse than doing nothing.
4. What does exist: a portfolio of trend-day buyers with late exits and a bounded daily loss (live), plus one
   seller that earns on the days the buyers lose (A-292, PASS thin, not built). Building that seller as a
   PAPER shadow is the one piece of new work this review supports.
5. One cheap untested screen: bid/ask imbalance at the live members' entries, descriptive, expectation negative.

Not done in this review: no replay was run, no held-out session was read, no strategy file was changed, and
the live set is as A-356 left it (deploy after 15:50 IST pending the user).
