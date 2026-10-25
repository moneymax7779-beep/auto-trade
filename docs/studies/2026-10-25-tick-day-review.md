# Overnight review — 24/25 Oct 2026

For your morning review. Everything below comes from replays and raw ticks on this Mac. All of these are research
results; nothing was deployed, committed or pushed. Ledger entries: A-212 to A-219 and the A-173 rerun
(`docs/EXPERIMENT-LEDGER.md`).

## 1. In one page

| Question | Short answer |
|---|---|
| Best opening-drive (odm) version? | **Keep odm-v4.** Inside the live set every alternative differs by only 1–3 trades. The "better" totals come from extra morning trades that take capital the expiry rider (etr) needs later. |
| 6 Oct: why no odm trade? | NIFTY was the **expiring index** (odm skips it by design), and the up-move came **09:45–10:00, after odm's 09:21–09:30 window**. SENSEX had no 0.2 % drive. |
| 6 Oct: why did etr lose? | Both calls were bought **after the move**, in the midday range, on 0DTE options. On the 27 tick days etr lost on 8 of 14 trades (−₹5.1 L) but its 6 winners made **+₹27.2 L**. Nothing measured separates the losers. |
| 10 & 18 Sep: is ma-cross's 60-minute exit wrong? | **No.** Its 17 time exits made **+₹55K** in total. ma-cross loses on its swing stops (−₹3.31 L) and two premium stops (−₹1.26 L). Holding to the opposite cross was worse in 11 of 17 trades (A-219). |
| 16 Oct: why did odm buy puts with no volume support? | odm's entry has no volume or OI condition. At entry, volume (RVOL 1.20) and OI (both sides building) **looked the same as odm's winners**, so a filter on them would not have helped. |
| 21 Oct: why no trades on a −2 % day? | It was not an expiry day, so etr and ecr were idle. odm's 09:21 up-drive was vetoed (correctly). ma-cross's averages only exist from about 10:15 (3-minute EMA20), and the fall began at 09:45. **The live set has no strategy for a non-expiry trend.** A-173 tested four candidates overnight: **all fail** out of sample (section 5). |
| Bugs found | (1) odm: a filter veto at 09:21 uses up the day's trade (A-216 / A-217). (2) The session chart drew stale prior-day levels; fixed, uncommitted. (3) One replay image was built without `clean` and ran old code; the run was redone and logged. |

**Your decisions:** see section 7.

## 2. odm variants inside the live set (A-215 … A-218)

Setup: the live set (ecr-v11, etr-v1, mac-v1 + odm), 27 tick days, fixed ₹5 L a day, risk v17. The control
(odm-v4) reproduces A-210 to the rupee on every day.

| odm variant | What it changes | Set total | odm itself | Cost to the others | Registered test |
|---|---|---|---|---|---|
| **v4 (live)** | — | +₹39,02,068 | +₹5,89,035 (12) | — | — |
| v9 (fresh drive, GCP shadow) | no entry unless the drive made a new extreme in the last 3 min | +₹38,06,295 | +₹7,84,231 (11) | −₹2,90,969 | descriptive (A-218) |
| v15 (room to next level) | no entry with PDH/PDL/ORH/ORL/PWH/PWL/PMH/PML within 1 ATR ahead | +₹39,49,504 | +₹7,63,459 (12) | −₹1,26,989 | "PASS", but not because of the rule (A-216) |
| v16 (retry after a veto) | a breadth-vetoed check no longer uses up the day's trade | **+₹40,78,587** | +₹9,29,534 (15) | −₹1,63,981 | FAIL: crowds etr (A-217) |

The whole picture comes from five trades:
- **8 Oct:** v9 and v16 add a NIFTY put (09:27, +₹1.62 L). It still holds capital at 11:23, so etr's big SENSEX put
  gets half size (+₹2.32 L instead of +₹3.96 L). Net for the set: −₹1,768.
- **15 Sep:** v16 adds a SENSEX put (09:27, +₹1.53 L) with no clash. This is v16's real gain.
- **30 Sep:** v15 and v16 add a SENSEX call (09:24, +₹25K).
- **9 Oct:** v9 skips a winning call (+₹1.16 L), because the drive was stale at 09:30.
- **22 Oct:** v9 and v15 skip the losing put (−₹1.49 L). ecr then buys three SENSEX calls in the afternoon (−₹1.27 L)
  that odm's loss had kept out through the kill switch.

**Recommendation:** keep odm-v4 live; keep odm-v9 in shadow.

The quirk behind v16 is real. A filter veto at 09:21 ends odm's day: 14 times on the 27 tick days, and 12 of those
drives were still on at a later check (`.local/a217/vetoes.md`). But the extra morning winners only help if they don't
crowd etr on expiry afternoons. If you want to pursue it, the next step is a registered test of **v16 with odm flat by
11:00 on days when etr can trade**. That fixes the clash rather than the entry. I have not run it.

## 3. Your questions, day by day

### 6 Oct (session 14767)
- **odm:** NIFTY expired that day, and odm-v4 skips the expiring index (A-092 tested allowing it: no extra trades on
  23 days, inconclusive). The index path: 09:21 22,573 → 09:30 22,602 (no 0.2 % drive) → **10:00 22,660**, after the
  window. SENSEX moved under 0.2 % by 09:30.
- **Levels:** your chart's 6 Oct levels are right (PDH 22,621.8, PML 22,569.65). The bars cover that day, so the stale
  level problem does not apply.
- **The level-break idea** is what odb did. odb came out of the live set on 24 Oct (A-184): 16 tick-day trades,
  −₹32K, 10 failing within 3 minutes.
- **etr:**
  - #1: NIFTY 22700 CE at 11:46 (₹46.92 × 5,200), exited SWING_LOW_LOST at 12:12 (₹33.15), −₹72,101.
  - #2: at 12:45 (₹57.61 × 2,145), exited PREMIUM_STOP at 13:45 (₹34.07, −41 %), −₹50,715.

  The index was in a 22,690–22,725 range from 11:30, so a 0DTE call loses time value even when price holds. etr only
  starts at 11:00, by which time 6 Oct's move was done.

  etr's record on the 27 tick days: 14 trades, 6 winners (+₹27.2 L), 8 losers (−₹5.1 L), net +₹22.0 L. At entry, VWAP
  distance, futures RVOL and range-vs-expected show no difference between losers and winners. The losers are its
  normal cost. Earlier etr exit changes (A-059 profit lock, A-073 time-value budget, etr-v7) all hurt.

### 10 Sep and 18 Sep: ma-cross time exits (A-219)
- Exit totals on the 27 tick days:

  | Exit | Trades | Net |
  |---|---|---|
  | TARGET | 19 | +₹14,91,337 |
  | TIME_60 | 17 | +₹55,384 |
  | FLAT_BY | 1 | +₹39,096 |
  | STOP | 11 | −₹3,31,049 |
  | PREMIUM_STOP | 2 | −₹1,26,233 |

- **The data-based alternative tested:** hold until mac's own signal turns against the trade (the opposite EMA9/EMA20
  cross).
  - It was better in 6 of 17 trades and worse in 11. The total was +337 index points, but +271.5 of that is one trade
    (1 Oct). The second half had 1 trade, worse.
  - In 9 of 17 the opposite cross came **within 0–25 minutes of the time exit**: the move had already stalled by
    minute 60.
- **Your days:**
  - 10 Sep #1 (−₹22.3K) would have gained 44.5 points by holding to 12:34.
  - 10 Sep #3 and #4, and 18 Sep #1 and #4, would have done worse (−5 to −11 points).
  - 18 Sep #5 (−₹19.5K) was a swing STOP, not a time exit.
- **Earlier:** A-080 removed the time stop and lost ₹6.0 L, because held trades ran into the swing or premium stop.
- **Conclusion:** keep the 60-minute stop. ma-cross's weak spot is its entries in chop (A-079 VWAP side and A-208 VIX
  filters both failed) and the 10:15 warm-up (see 21 Oct).

### 16 Oct (NIFTY and SENSEX puts at 09:21, −₹1,99,496)
- **The day:** a gap-up open above the prior-day high (NIFTY 23,065 vs PDH 23,056), a drop back through it (09:21
  22,964), a low at **09:36** (22,914), then a rally to 23,149 by 10:36. That took both puts to their stops at the
  session high.
- **At entry, the losing puts against odm's winners:**

  | Input | 16 Oct losers | odm's winners |
  |---|---|---|
  | Futures RVOL (NIFTY) | 1.20 | 0.49–3.62 |
  | Futures RVOL (SENSEX) | none (futures too thin) | — |
  | OI z-scores | calls 1.6 / puts 1.1, both building | 29 Sep: 2.3 / 2.6; 12 Oct (+₹2.97 L): 0.3 / −0.4 |
  | Breadth | −64 / −78 | similar |

- **Rules already tested on this trade, none of which skips it:**
  - level break (A-214): the drive had broken PDH;
  - room to the next level (A-215): 7–10 ATR of room;
  - fresh drive (odm-v9): fresh.
- **Earlier exit and filter tests:** the VWAP exit (A-115) and the OI-against exit (A-153) both failed.
- **Conclusion:** this is a failed breakdown that no tested input predicted. The only lever that cuts such losses is
  size, which is risk v17's choice.

### 21 Oct (no trades, NIFTY 23,121 at 09:40 → 22,650 at 14:47)
- **Not an expiry day:** NIFTY expired 19 Oct (20 Oct holiday) and SENSEX on 22 Oct, so etr and ecr had nothing to
  trade.
- **odm:** NIFTY's 09:21 *up* drive and SENSEX's 09:24 up drive were vetoed by breadth. That was right: the day then
  fell. Under v16 the drive check would continue, but the drive was up, not down.
- **ma-cross:** its EMA9/EMA20 run on 3-minute candles, and EMA20 needs 20 of them, so it exists only from about 10:15.
  The fall began at 09:45. When EMA20 first existed, EMA9 was already below it, so there was no cross to see. The only
  cross of the day came at 15:09, after the 14:30 cutoff. Seeding the averages from the previous day was tested
  (A-156, mac-v5) and failed on bars (−₹2.84 L).
- **The gap:** a mid-session non-expiry trend has no strategy in the live set. That's A-173 (section 5).

## 4. Other tests run tonight

| ID | Idea | Result |
|---|---|---|
| A-212 | Udemy "13 strategies", Strategy 2 (VWAP opening/signal/entry candle) on 2 years of NIFTY bars | **FAIL**: +0.63 % / +0.30 % per trade, Jul–Dec 2025 negative, CI includes 0 |
| A-213 | Udemy MasterClass Section 9: weekly short strangle at the range edges / max-OI strikes with stops beyond | **FAIL**: positive both years, but the validation CI includes 0. A quarter of A-196's drawdown, and less profit |
| A-214 | odm + level break (your 22 Oct idea) | **FAIL**: removes winners. The 22 Oct put came after a real PDL break; the chart showed stale levels |
| A-215 / A-216 | odm + room to the next level | Split FAIL. Engine "PASS" only through a side effect (the veto quirk) |
| A-217 | odm retry after a filter veto | FAIL (crowds etr), set +₹1.51 L |
| A-218 | odm variants side by side | descriptive, section 2 |
| A-219 | ma-cross time stop vs opposite cross | FAIL, keep the time stop |

## 5. A-173: trend-day capture (run overnight, all FAIL)

- **Candidates, each alone:**
  - orb-v1: 15-minute opening-range breakout, 2R target;
  - orb-v4: orb-v1 + premium trail (+20 % arm, 15 % giveback);
  - orc-v2: opening-range continuation, can enter before 10:15, VWAP trail;
  - orc-v3: orc-v2 + the same trail.
- **Samples:** Oct 2024–Dec 2025 bars (305 days), 2026 bars (165 days), the 27 tick days.
- **Pass (registered 23 Oct):** net > 0 and PF ≥ 1.2 in both 2024–25 halves, and net > 0 on the 2026 bars. A trail
  variant is preferred if it improves net in at least 2 of the 3 samples and in both 2024–25 halves.
- **Result: all four FAIL.** The run finished at 08:11 on image a217-local, 1,988 of 1,988 replays. The 23 Oct partial
  run's image had been deleted, so everything was rerun.

  | Candidate | 2024–25 H1 | 2024–25 H2 | 2026 bars | Tick days |
  |---|---|---|---|---|
  | orb-v1 | −₹13.0 L (PF 0.66) | −₹11K (0.99) | +₹2.1 L (1.08) | +₹7.0 L (2.39) |
  | orb-v4 (+trail) | −₹2.9 L (0.90) | −₹1.9 L (0.90) | −₹11K (1.00) | +₹8.6 L (3.45) |
  | orc-v2 | −₹11.5 L (0.60) | −₹5.2 L (0.71) | −₹8.0 L (0.71) | +₹6.6 L (1.91) |
  | orc-v3 (+trail) | −₹8.2 L (0.65) | −₹5.1 L (0.68) | −₹9.6 L (0.59) | +₹7.5 L (2.25) |

- **By month** (`.local/a173/by-month.md`): every candidate is mixed or negative month after month from Oct 2024 to
  Sep 2026. Only the **October 2026 tick days** are strongly positive for all four (+₹4.0 L to +₹9.0 L).
- **21 Oct:** they would have caught it (orb +₹2.22 L, orc +₹2.88 L). Over two years, though, they lose.
- **Conclusion:** the tick days, an unusually trending Sep–Oct 2026, flatter every trend rule; the bars show no lasting
  edge. The non-expiry trend gap stays open. orb-v1 keeps collecting a forward record as a GCP shadow (A-083).
- **What would be needed:** a candidate that holds on the 2024–25 bars. None of the opening-range family does. The
  remaining ideas, VWAP rules (A-079, A-081, A-212), day-extreme breaks (A-185) and the masterclass rules
  (A-205/A-206), have also failed. I would not register another trend rule without a new kind of input.

## 6. Fixes and bugs found

1. **Session chart levels:** the replay chart drew prior-day levels from a weeks-old stored bar session (22 Oct showed
   6 Oct's high). Fixed in `LevelsService`, which now uses the own tick capture and only the actual previous trading
   day. The :8097 preview runs it. Uncommitted on `feature/live-execution`; the same change sits uncommitted in the
   other session's worktree `claude/pensive-joliot-3fbe81`. Commit it once, in one place.
2. **odm veto quirk:** documented (A-216/A-217). Live behaviour is unchanged.
3. **Replay images:** always `mvn clean install` before building a replay image. A-216's first run silently used the
   old strategy jar; it was caught and redone.

## 7. Decisions for you

1. **odm:** keep v4 (recommended), or register "v16 + flat by 11:00 on expiry days".
2. **A-173:** all four candidates failed, so there is nothing to promote. Accept the non-expiry trend gap for now
   (orb-v1's shadow keeps a forward record).
3. **Commit:** everything tonight is uncommitted on `feature/live-execution`:
   - the levels fix;
   - the odm v15/v16 code, configs and test;
   - ledger entries A-212 … A-219 and the A-173 result;
   - the study scripts.

   Say "commit" and I'll commit by exact path.
4. **Monday 26 Oct:**
   - start the GCP VM before 08:30
     (`gcloud compute instances start zt-autotrade --zone=asia-south1-a --project=zt-tiger-v2-prod --account=satyajava704@gmail.com`);
   - do the Kite login for ZERODHA-1 before 09:15;
   - after 15:45, compare ZERODHA-1 with PAPER-1 (DRY_RUN day 1 of 3).
