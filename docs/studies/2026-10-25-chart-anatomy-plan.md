# Chart anatomy plan (25 Oct 2026): what was already tested, what is new, what was registered

The anatomy itself is `docs/studies/2026-10-25-chart-anatomy-report.md` (54 charts, 27 tick days, 77 trades, read from
the :8097 preview; nothing from the code). This note maps every item of its plan (section 5) to the ledger, because
most of it had been tested here before the anatomy was written, and the user's standing decisions bind what can be
re-tested. Branch `research/a227-etr-non-expiring`.

## 1. Plan items against the ledger

| Plan item (report §5) | Prior test | Status | Action today |
|---|---|---|---|
| A. Regime engine (VWAP side, EMA slope, OR hold, PCR / OI drift since 09:15, volume surge) | A-094: logistic model on 18 inputs incl. the 10-minute option OI change, VIX, skew, other index: **FAIL** (test 50–65 %, probabilities within a few points of 50 %, no hold on tick days); price rules alone 47–57 % | closed | The one input A-094 did not try is the **cumulative** drift since the open: screened as **A-228** on the stored snapshots. **FAIL** on the Jul–Aug test rows (51.5 % at 10:30, strength ≥ 0.3, n = 33); 93–100 % only on the tick days, which are the days the pattern was seen on. No engine |
| B. ma-cross: VWAP-side gate | A-079 mac-v2: **FAIL** (the biggest targets were crosses against VWAP) | closed | none |
| B. ma-cross: chop / counter-drift / gap / stop-room filters | A-222, A-224 (mac-v8): **FAIL** out of sample (−₹5.6 L on 7–23 Oct) | closed | none |
| B. ma-cross: confirm-score agreement, cross-index consistency | not tested | **frozen by A-225** (user, 25 Oct: no new ma-cross rule tests until the 25-session forward read) | none; the 18 Sep 13:42 pair and the 5 / 12 Oct pairs are noted here for the forward read |
| B. ma-cross: scale-out + trail instead of TARGET; TIME_60 only when not in profit | A-080 no time stop **FAIL**; A-219 opposite-cross exit **FAIL**; A-220 12 exits **FAIL**; A-221 conditional time stop **FAIL** on 2026 and ticks; partial exits need an OMS reduce the engine does not have (A-035 note) | closed / frozen | none |
| C. odm: open-reclaim invalidation within 20 min | On 16 Oct and 22 Oct the day open was the session high, so the existing session-extreme stop is the same level; a reclaim exit saves minutes, not rupees. A-115 (VWAP exit) **FAIL**, A-153 (OI-against exit) **FAIL** | closed | none; the review's conclusion stands: size is the lever (risk v17) |
| C. odm: PCR / OI veto at entry | At 09:21 on 16 Oct the OI and RVOL readings matched odm's winners (tick-day review §3); the PCR rise came after entry | closed | none |
| C. odm: gap-size rule | 28 Sep and 12 Oct, odm's two best days (+₹2.2 L, +₹3.8 L), were gap-downs; gap-and-go (29 Sep study) lost on 5 of 6 | contradictory | none |
| C. odm: wider drive window (10-min bar beyond ORH/ORL before 10:00) | The 6, 13, 15 Oct and 9 Oct SX misses are the expiring index (A-092 INCONCLUSIVE), the breadth-veto quirk (A-217 **FAIL**: crowds etr) or a drive after 09:30 | mostly explained | none |
| C. odm: trail past 12:00 while beyond EMA20 | A-114 (odm-v9 extend-until with RVOL / futures VWAP) tested the extension idea; odm's 12:00 flat is the screen's exit (A-085) | closed | none |
| D. etr: continuation entry, runway, premium stop, no late re-entry, smaller initial size | A-042 time stop **worse**; A-059 profit lock **hurt**; A-073 time-value budget **FAIL** (refuses the start of the best moves); A-064 pullback entries **FAIL** on non-expiry; review: "nothing measured separates the losers" | closed | none |
| D. etr on the non-expiring index at 1–5 DTE | **not tested** | new | **A-227 trr-v1**: etr-v1's rules under a new key `trend-rider` on the index 1–5 trading days from expiry, half size. Built, unit-tested, replayed. **Alone it passes out of sample (Aug bars: 24 trades, +₹2.29 L, PF 1.99); the set fails the others-lose clause (−₹96,595, one day); on the tick days it costs the set −₹4.17 L by crowding etr's expiry runners** |
| E. ecr: lower threshold or retire | ecr is the one strategy positive in both years (A-199); it is the real-money pilot (A-226) | keep | none |
| F. data-gap guard (8 Oct NIFTY) | 8 Oct NIFTY's miss is the breadth-veto quirk (`.local/a217/vetoes.md`: vetoed 09:21, drive on at 09:24–09:30), not the volume gap | explained | none |
| 5.2a ORB-retest 09:40–10:45 | A-173 orb-v1 / v4, orc-v2 / v3: **FAIL** on 2024–25 and 2026 bars (only the Oct 2026 tick days positive); A-076 / A-078 orc **FAIL** | closed | none |
| 5.2b Trend-pullback | A-035 tps-v1 **FAIL**; A-064 tdr-v1 **FAIL**; A-081 tsa-v1 **FAIL** | closed | none |
| 5.2c Level-break impulse (volume ≥ 5× + OI bar) | A-157 / A-158 atm-oi-burst, A-183 / A-185 impulse triggers, A-187 / A-188 exits, A-131 zone-break-flow (zbf-v10 live one day, −₹1.49 L, A-149): none promoted | closed / shadow | none |
| 5.2d Expiry-range credit | A-196 / A-197 weekly strangle (S0 forward), A-193 late-expiry short strangle, A-213 masterclass strangle **FAIL** (CI includes 0) | pending forward | none |
| 5.3 Sizing by rupee risk, day-type throttle | A-026 delta sizing (paper-risk v6), A-166 risk construction, risk v17 / v18 | existing | none here; a sizing change is a risk-file row for the user |

## 2. What was built (A-227)

- `ExpiryTrendRiderConfig`: `scope.dte_max` (absent = `scope.dte`, so every etr file is unchanged) and the id from the
  file's `strategy:` key.
- `ExpiryTrendRider`: trades when `dte ≥ scope.dte && dte ≤ scope.dte_max`; `id()` = the file's key.
- `ExpiryTrendRiderFactory` accepts `expiry-trend-rider` and `trend-rider`; `TrendRiderProvider` registers the key.
- `config/strategy/trend-rider.v1.yaml` (trr-v1): etr-v1 values, dte 1–5, ₹2.5 L plan, 2 lots, 1 add.
- `TrendRiderTest`: own id beside etr's, trades at dte 1–5 and never at 0 or 6, etr-v1 unchanged, enter / add once /
  swing exit.
- Replays: `.local/a227/run.sh` (image a227-local: R = live set + trr-v1 on the 27 tick days; C parity on 1, 8,
  21 Oct; C and R on the 22 Aug 2026 bar days), `.local/a227/result.py` → `result.md`. 74 / 74 replays, parity exact.

### Result (25 Oct 16:59)

| Sample | trr own | trr trades / PF | set R − C | others |
|---|---|---|---|---|
| Aug 2026 bar days (out of sample, judged) | **+₹2,29,339** | 24, PF 1.99 | **+₹1,32,744** | −₹96,595 (all 4 Aug) |
| 27 tick days (design, reported) | +₹1,80,516 | 36 | **−₹4,17,322** | −₹5,97,838 |

Registered verdict: **FAIL** (the others-lose clause: −₹96,595 on the Aug days against the −₹50,000 limit); the
strategy's own gates (≥ 5 trades, net > 0, PF ≥ 1.3) passed. What decides it is the capital clash with etr: on the
tick days trr is positive on its own (21 Oct +₹2.96 L, the day the set had nothing; 8 Oct NIFTY +₹2.01 L; 13 Oct
+₹1.54 L) but it holds the shared ₹5 L into the expiry afternoons, so etr's runners on 15 Sep, 8 Oct, 14 Oct and
15 Oct entered at half size (−₹5.98 L to the others). This is the A-217 pattern again. The one variant worth a new
row is trr flat before etr's window on the other index's expiry day (or a ₹1.25 L plan); it would be judged on days
after Aug 2026 that nobody has looked at, and it is the user's call.

## 3. What the screen said (A-228)

`.local/a228/result.txt`: the cumulative OI drift calls the rest of the day on the tick days (93 % at 10:30, 100 % at
11:00 with strength ≥ 0.3) and does not on the Jul–Aug bar days (51.5 %, 55.6 %), nor on train (61 %, 51 %). The
tick days are where the pattern was seen, so they are not evidence. FAIL by the registered criterion; A-094's
conclusion stands. A forward read (the 10:30 drift computed nightly from the own capture, read once at 25 sessions)
is the only clean test left and is not registered.

## 4. Honest summary for the user

Of the plan's ~20 items, one was new and testable (A-227), one was screened and failed (A-228), and the rest were
tested in the last three weeks and failed out of sample or are frozen by the user's own 25 Oct decision on ma-cross.
The anatomy's day-by-day reading (which trades lost and why) agrees with the project's tick-day review of the same
day; where it proposed rules, the ledger had already tried them. A-227 shows the non-expiry trend gap does have a
flow-based answer on its own record (+₹2.29 L, PF 1.99 on unseen Aug bars) but not inside a ₹5 L set shared with etr;
the next step is a capital rule, not another entry rule.

## 5. Opening drive and ma-cross: timing and context, measured (A-230)

The chart anatomy proposed tuning both strategies from 27 days of charts. 27 days is too few to tune anything, so
A-230 measured every timing and context idea on two years of replayed trades: 391 odm trades (P1 Oct 2024–Jun 2025,
P2 Jul–Dec 2025, P3 Jan–6 Oct 2026) and 1,210 mac trades over the same periods. The factor list and the rule
("a change is a candidate only if all three periods agree, n ≥ 15 each") were fixed in the ledger before computing.
Full tables: `.local/a230/report.md`; script `docs/studies/2026-10-25-odm-mac-timing.py`.

### Opening drive (odm)

| Question | What two years of data say |
|---|---|
| Does the check time matter (09:21 / 09:24 / 09:27 / 09:30)? | 252 of 391 entries are at 09:21; the later checks are too few and inconsistent. No rule. |
| Is 12:00 the right exit? | Yes. No exit time from 10:00 to 15:15 beats 12:00 in all three periods; all means sit within ±0.11R. The best close is bimodal: 32–44 % before 10:00 (the peak trail takes these), 21–35 % after 13:30 (trend days). |
| Exit when the day open is reclaimed (16 Oct, 22 Oct)? | Those trades do lose (−25.5 / −18.3 / −7.4 % of premium). But exiting at the reclaim is **worse** than holding: −0.80 vs −0.76R, −0.64 vs −0.48R, −0.75 vs −0.44R. The reclaim happens at the stop level, so the stop already does this job. Refuted. |
| Does heavy opening futures volume confirm the drive? | The opposite: volume above 1.5× normal was worse in all three periods (n 32 / 14 / 20). Too few for a rule, and it contradicts the chart intuition. |
| Weekday, drive size, stop distance | Mixed, or only a middle bucket better: noise. |
| Skip big opening gaps? | The one real candidate. Keeping only days with \|gap\| ≤ 0.3 % improved every in-sample period (+₹1.5 L, +₹4.3 L, +₹5.3 L; PF 1.16→1.35, 0.99→1.34, 1.10→1.68). **On the unseen 7–23 Oct days it would have cost ₹3.47 L**: it skips the 9 Oct NIFTY call (+₹1.16 L) and the 12 Oct puts (+₹3.80 L) to avoid the 22 Oct put (−₹1.49 L). It does not transfer. |

### ma-cross (mac)

- **Timing** was already measured in A-223 on about 1,350 trades. Entries 11:00–12:00 are better and 12:00–13:00
  worse in both periods; entries against a 0.3 % gap, more than 1 ATR on the wrong side of VWAP, after a mild
  counter-drift, or with a stop under 1.25 ATR are worse. The filters built from these (mac-v8, A-224) lost ₹5.6 L
  on the unseen October days. The 60-minute exit is backed by the data: after minute 60 the index moves +0.07R /
  −0.01R on average.
- **The EMA warm-up**: mac's 3-minute EMA20 exists from about 10:15. Seeding it from the previous day (A-156) and
  entering at 10:15 (A-222) both lost.
- **New-kind inputs from the chart anatomy**, tested now:

  | Input | Result |
  |---|---|
  | The other index's EMA9 above or below EMA20 at entry | mixed (agreement better in P2 and P3, worse in P1) |
  | The other index crossed the same way within 15 minutes | mixed |
  | Futures volume in the 15 minutes before entry | mixed |

  The cross-index consistency idea from 5 and 12 Oct does not hold over two years.

### What this means for tuning

There is no data-backed tuning of odm or mac left to make on price, timing, volume or cross-index inputs. Every
consistent-looking split either failed on unseen days (odm's gap rule here; mac-v8 in A-224) or never was consistent.
The live rules stay. The levers that remain are not entry or exit rules:

1. **Capital between strategies.** That is what A-227 and A-229 are about.
2. **Size.** The odm losses on 16 and 22 Oct were failed breakdowns that no measured input predicted (tick-day
   review §3). Only size limits them, and that is the risk file's choice (risk v17 / v18).
3. **Forward evidence.** mac's 25-session read (A-225) and any forward shadow.

## 6. trr-v2 (A-229): flat on the other index's expiry day

Built as asked: a new `Strategy.observeOther` hook lets a strategy see the other index's snapshots (default no-op;
parity exact on 1, 8 and 21 Oct), and trr-v2 takes no entry on a day another index expires.

| Sample | trr-v2 own | trades / PF | set R − C | others |
|---|---|---|---|---|
| **9 unseen days** (Sep bars 1, 4, 11, 16, 21; held-out 22–25 Sep), judged | **−₹42,776** | 11 / 0.64 | **−₹47,228** | −₹4,451 |
| Aug 2026 bars (in-sample for v2) | +₹1,96,182 | 19 / 2.40 | +₹1,96,182 | 0 |
| 27 tick days (in-sample for v2) | −₹2,75,077 | 28 / 0.59 | −₹5,16,332 | −₹2,41,256 (14 Oct) |

**FAIL.** The capital fix did what it was meant to: on the expiry days etr's runners are untouched. But the rider
itself loses outside August: on the unseen September days and on the tick days. The rules that ride 0DTE gamma do not
carry to options 1–5 days from expiry. The non-expiry trend gap stays open; closing it needs a new kind of input.
