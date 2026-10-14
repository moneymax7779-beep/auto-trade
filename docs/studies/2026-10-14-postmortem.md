# 14 Oct 2026 post-mortem: signals, trades, kill switch, data, and the ₹10 crore goal

User, ~19:30 IST: a thorough review of today's signals and trades. What worked, what did not and why. What data is
missing, and why the system could not trade more. Any kill-switch issues. Whether the strategies find the best moves,
and what to do for the ₹10 crore goal. "Run as many replays as you want."

**Data.** 14 Oct was synced from GCP to the Mac at 19:37–19:43 (`.local/gcp-sync/load-1014.*`). Every md.* row count
matches. The sync also brought the trade CSVs, decisions, candidates, live feature snapshots and the trading-core log.

**Replay = live.** The Mac replay from own capture at the live equity (₹10,69,295) reproduces live session 894 to the
rupee: −₹3,08,783.

## 1. The day

- **Indices.** No index expired. SENSEX expires 15 Oct.
  - NIFTY opened 22,715.75, 3.25 points under PDH 22,719.
  - It ranged 22,656–22,758 until 13:00, fell to 22,559 by 13:30 (the day's one real leg) and closed near 22,625.
  - SENSEX moved the same way (73,048 → 72,579).
- **Pre-open (09:00–09:08, captured, used by no feature).**
  - NIFTY: the indicative open was flat (+0.003 %), with 26 of 50 stocks below their previous close.
  - SENSEX: 20 of 30 stocks had sell imbalances.

## 2. Signals and trades (live, PAPER-1)

| Time | Strategy | Signal | Outcome |
|---|---|---|---|
| 09:17 | odb-v3 | NIFTY 22750 CE, BREAK_PDH (spot 22,747; open 0.014 % under PDH) | 6,240 qty, ₹10.35L premium (≈ 97 % of equity); back under PDH at 09:18; −₹1,83,366 |
| 09:24 | odb-v3 | NIFTY 22700 CE, fresh BREAK_PDH | 5,980 qty; back under PDH at 09:25; −₹1,25,417 |
| 10:42 | ma-cross | NIFTY + SENSEX calls (cross up inside the morning range) | refused: the daily loss limit, kill switch engaged now |
| 11:24 | ma-cross | puts | refused: kill switch |
| 12:54 | ma-cross | NIFTY + SENSEX puts, start of the 13:00–13:30 fall | refused: kill switch. In replay: both hit the 2R target by 13:08, **+₹6,83,121** |
| 14:12 | ma-cross | puts | refused: kill switch |

The other strategies had no trigger:
- ebs, ecr, egb and etr trade only an expiring index.
- odm needs a 0.2 % drive by 09:21–09:30, and there was none.

The shadow strategies, each alone, all lost or stayed flat:

| Shadow strategy | Today |
|---|---|
| orb-v1, v2, v3 | −₹1,52,111 |
| orc-v1, v2 | −₹27,208 |
| odm-v1, v2, v3, v5 | no trade |

**Replays at live equity (`.local/pm1014`, `.local/a97`):**

| Set | 14 Oct |
|---|---|
| as deployed | −₹3,08,783 |
| without odb | +₹3,60,522 |
| with odb-v5 (A-097) | +₹3,60,522 |
| with odb-v6 (A-098) | −₹3,41,863 |
| kill switch off (A-099) | +₹51,739 |

## 3. Why it went wrong

1. **A noise break.**
   - The open sat on the level, so the first minute's wiggle was a "break".
   - odb's structure stop sits at the level, so the trade lasted 65 seconds.
   - odb's whole record has this shape:
     - wins when price never comes back (+22 %, +67 %, +24 %);
     - losses within 1–2 minutes (−9 % to −18 %).
2. **Sizing, the real problem.**
   - Every live strategy's premium budget is 50–100 % of equity (₹5L or ₹2.5L × equity/₹5L). Stops are 20–40 % of
     premium.
   - So one stop costs 10–20 % of the account, and two in a row cross the 22 % daily limit.
   - At ₹5L this happened on 4 of 194 replayed days. Today it happened in 8 minutes.
3. **The kill switch did exactly what it is built to do.**
   - It is checked at the next entry attempt, so it is "engaged" at 10:42, not at 09:25. Nothing slipped through in
     between.
   - Its cost today was the day's best signal.
   - Its real weakness: it caps the next entry, not the loss of one trade.

## 4. Tests run tonight (all registered before results; ledger A-095 to A-099)

| Row | Test | Result |
|---|---|---|
| A-095 | live set without egb-v4 | PASS: +₹75,795 on bar days, no change on tick days |
| A-096 | ma-cross rolling 20-trade PF gate | FAIL: −₹29,597; it halves the drawdown but misses recoveries; settings-dependent |
| A-097 | odb-v5, open ≥ 0.1 % from the level | FAIL: fixes 14 Oct (+₹6.69L) but −₹1.66L over 193 days |
| A-098 | odb-v6, breadth ≥ ±50 agrees | FAIL: −₹3.5L to −₹5.2L; still took the 09:24 poke today |
| A-099 | kill switch off (measurement) | neutral over history: 189 of 193 days identical, −₹12,963 on tick days and +₹18,870 on bar days without it; 14 Oct +₹51,739 without it |

What this means:
- The rules that would have fixed today cost more on other days. 14 Oct was a tail day of the strategy's normal
  distribution, made large by size.
- The design lessons are in the sizing, not in another filter.

## 5. Data: what we have, what we do not use, what we lack

**Have and use:**
- Index, futures and option ticks with depth; the option chain OI near ATM; IV and Greeks.
- Constituent ticks and breadth; VIX; prior-day, week and month levels; OI z-scores; one-minute bars from January.

**Have but do not use:**
- **The pre-open auction:** 09:00–09:08 IEP and buy/sell imbalance for every constituent. It is the cleanest read of
  the opening's intent, and today it said "no buyers".
- **The closing-auction history**, used only by CAS.

**Lack:**
1. Index options tick history beyond 24 days: the vendor request is ready, not sent.
2. GIFT Nifty and global cues before 09:15 (US futures, Asia, USD/INR, crude): no source connected.
3. FII/DII and participant-wise derivatives OI (NSE publishes it daily after the close).
4. BANKNIFTY, not recorded since 29 Sep (feed connection budget).
5. Breadth before July 2026: bar days cannot test any breadth rule on January–June.

## 6. Do the strategies find the best moves?

| Result | Strategies |
|---|---|
| Profit engines | etr (expiry trend rider, PF 5.6 on tick days), ma-cross (PF 2.4 on tick days, 1.15 on bar days) |
| Steady small earners | odb, ecr |
| Too few trades to judge | odm |
| Dead weight | egb (A-095) |

- **Today:** the one big leg (13:00–13:30) was found by ma-cross at 12:54 and refused by the kill switch.
- **Nothing reads the opening auction or the morning's first 15 minutes as a whole.** Every opening rule reacts to one
  level or one bar.

## 7. Sizing and the ₹10 crore goal (`.local/sizing/kelly.py`)

**Equity.**
- ₹10,69,295 before today; ₹7,60,512 after.
- The required curve (₹5L × 1.096ⁿ) is at ₹8.66L after 6 sessions, so equity is now below it.
- From ₹7.6L, ₹10 crore needs about 9.3 % a session for the remaining ~55 sessions.

**Daily returns at ₹5L sizing:**

| Days | Mean per day | Days green |
|---|---|---|
| Tick days (Sep–Oct) | +36 % | 80 % |
| Bar days (Jan–Aug) | +0.9 % | 47 % |

On bar days, today's sizing has zero long-run growth and half size grows fastest. On tick days, bigger is always
better.

**Bootstrap of 55 sessions** from the 173 days, starting at ₹7.6L. No liquidity cap is modelled, so these are upper
bounds:

| Sizing | Median end | Chance of ₹10 Cr | Chance of ending below today | Typical worst drawdown |
|---|---|---|---|---|
| Half | ₹26L | 0 % | 4 % | 29 % |
| As now | ₹54L | 2.8 % | 8 % | 52 % |
| 1.5× | ₹78L | 11.6 % | 12 % | 69 % |

**Honest reading.**
- At any size the system's own history supports, ₹10 crore by 31 Dec is a low-probability outcome (about 3 % at
  today's size).
- Bigger size raises that chance only by raising the chance of a large loss.
- Above about ₹20–25L the 400-lot cap and the 2 % liquidity cap stop the size growing anyway (13 Oct chain).

## 8. Kill-switch override for excellent signals (ledger A-100), registered 14 Oct ~21:20 IST, before results

User, ~21:15: "Kill switch is OK but if the signal is excellent override and trade."

**Step 1: is "excellent" detectable before the trade?**
- **The grade, fixed now** from the 12 Oct context evaluation of 118 tick-day trades (not fitted to 14 Oct). A signal is
  A-grade when both hold at the entry snapshot:
  - ATM IV percentile ≥ 50, or ATM IV rising over 3 minutes;
  - mover breadth ≥ +50 for a call, ≤ −50 for a put.
- **Validation set:** every trade of the live set on the bar days 1 Jul–31 Aug. These days were never used to design
  the grade. Breadth exists there from July, and the IV and breadth values come from A-094's REPLAY_FEATURES snapshots
  (run f75550eb4877-a94, whose trades equal the f75550 control).
- The 24 tick days are reported, but they overlap the design sample.
- **PASS** needs all of:
  - at least 30 A-grade trades;
  - A-grade PF ≥ 2.0;
  - A-grade PF ≥ 1.5 × the PF of the other trades.

**Step 2, only if step 1 passes.** Risk v16 = v12 plus an override.
- After the account daily loss limit engages, an A-grade entry is still allowed at half its normal size.
- At most 2 override entries a day.
- No override once the day's loss reaches 33 % of equity (the hard floor).
- It is replayed on all days against the control. Deploying it is the user's decision.

**Already known on 14 Oct (design day, not counted):**
- A-grade: 09:24 odb (−₹1.25L), 11:24 and 12:54 NIFTY puts (12:54 +₹2.87L), 14:12 puts (−₹1.06L).
- Not A-grade: 10:42 calls (−₹2.16L), 12:54 SENSEX put (+₹3.96L).

### A-100 step 1 result (~21:25 IST, `.local/a100/`)

| Sample | A-grade trades | Other trades |
|---|---|---|
| Jul–Aug bar days (validation; 184 trades, 99 without breadth) | 15, PF 2.80, +₹3.02L | 70, PF 1.44, +₹3.24L |
| Tick days (overlap the design sample, reported only) | 36, PF 4.81, +₹31.4L | 46, PF 2.05, +₹8.0L |

- **By strategy on the validation days:**
  - etr: 3 A-grade trades, PF 5.48.
  - ma-cross: 3 A-grade trades, all losses.
  - odm: 5 A-grade trades, PF 0.63.
- **Verdict: FAIL at step 1** (15 A-grade trades, < 30). The direction supports the grade (PF ratio 1.95), but the
  sample cannot prove it. Step 2, the override, is not built.
- **Next:** no code is needed. Every candidate's stored context already holds `moverBreadth`, `ivPercentile` and
  `atmIvChange3m`, so the grade can be computed for every forward trade. The override can be re-registered once at
  least 30 graded trades exist on days not used to design it.

## 9. ma-cross quiet window (ledger A-103), registered 14 Oct ~23:05 IST, before results

User, ~23:00: "don't allow ma-cross in-between 10.30 AM to 12.30PM unless it is exceptional data satisfies, in general
my observation in between 10.30AM to 12.30PM is noisy".

**The change.** mac-v4 = mac-v1, except that an entry signalled from 10:30 to 12:30 (12:30 exclusive) needs the A-grade.
- **A-grade** (A-100's definition): ATM IV percentile ≥ 50 or ATM IV rising over 3 minutes, and mover breadth ≥ +50
  for a call or ≤ −50 for a put. An unknown value fails.
- Outside that window, mac-v4 is mac-v1. Exits are never blocked.
- On bar days before July there is no breadth, so mac-v4 makes no entries from 10:30 to 12:30 there.

**Prior evidence, stated now.** The 14 Oct bar-day bucket analysis of mac-v1 (438 trades, `.local/loss-days`):

| Entry time | PF |
|---|---|
| 09:00–11:00 | 1.14 |
| 11:00–12:30 | 1.11 |
| 12:30–14:00 | 1.06 |
| 14:00–16:00 | 1.48 |

The bar-day data does not single out 10:30–12:30. This is a test of the user's observation, not a confirmation of it.

**Test.**
- The live set with mac-v4 in place of mac-v1, on all 24 tick days and 169 bar days.
- Control: the f75550 run. 14 Oct is reported apart.

**PASS** needs all of:
- set net ≥ control on the tick days and on the bar days;
- mac-v4's own PF ≥ mac-v1's on the same days;
- no day worse than control by more than ₹50,000.
