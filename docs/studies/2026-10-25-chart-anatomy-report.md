# Chart anatomy and improvement plan: NIFTY and SENSEX, 27 tick days (2 Sep – 23 Oct 2026)

Source: the 54 screenshots in `charts/2026-10-25-live-set-tick-days` (live set ecr-v11, etr-v1, mac-v1, odm-v4, risk v17, ₹5 L/day). Every number below was read off the charts and trade tables; nothing was taken from the project code. Day-type labels (trend / swing / chop) are my own classification from the price path, VWAP side and OI drift.

---

## 1. Scoreboard (what the 27 days actually produced)

| Metric | Value |
|---|---|
| Net, 27 days | **+₹39.0 L** (Sep +11.2 L, Oct +27.8 L) |
| Days positive / negative / zero | 19 / 7 / 1 |
| Average per day | ₹1.45 L |
| Best day | 1 Oct +9.25 L (SX ETR 0DTE runner) |
| Worst day | 16 Oct −1.99 L (ODM PE on both, gap-down V-reversal) |
| Trades | 77 (NF 43, SX 34) |
| Top 5 trades | +₹27.3 L = **70 % of total P&L** |

By strategy:

| Strategy | n | Net | Win % | Avg win | Avg loss | Comment |
|---|---|---|---|---|---|---|
| ETR (expiry-trend-rider, 0DTE) | 14 | **+22.1 L** | 43 | 4.53 L | −0.64 L | All P&L from 5 runners on trend days; 8 of 14 lose |
| MAC (ma-cross) | 50 | +11.3 L | 60 | 0.56 L | −0.28 L | Workhorse; bleeds on chop and counter-trend |
| ODM (opening-drive) | 12 | +5.9 L | 75 | 1.04 L | −1.16 L | 7/7 on trend days; 3 gap-reversal losers cost 3.5 L |
| ECR (early-confirm-runner) | 1 (+1 unfilled) | −0.2 L | 0 | — | — | Effectively dormant |

By exit reason:

| Exit | n | Net | Note |
|---|---|---|---|
| FLAT_BY (time flat 12:00 / 15:15) | 14 | +34.6 L | The only exit that lets winners run |
| TARGET | 20 | +16.2 L | 100 % win but caps every runner (1 Oct NF, 23 Oct NF) |
| TIME_60 | 20 | +1.0 L | 60 % win, avg +19 K: gives back most of the open gain |
| STOP | 15 | −7.8 L | Avg −52 K |
| SWING_HIGH/LOW_RECLAIMED (ETR) | 7 | −4.8 L | 0 % win, avg −69 K: the 0DTE stop is always wide |

By day type (my classification):

| Day type | n trades | Net | Win % |
|---|---|---|---|
| Trend down | 24 | +24.3 L | 58 |
| Trend up | 10 | +10.1 L | 70 |
| Swing (2–4 legs of 100+ pts) | 28 | +6.8 L | 61 |
| Chop | 15 | **−2.2 L** | 47 |

The system is a trend-day system. It earns on 7–8 trend days, breaks even on swing days, and pays rent on chop days. Nothing in the live set identifies the day type, so it trades every day the same way.

---

## 2. Day-by-day anatomy

Expiry: NIFTY Tuesday (19 Oct Monday because 20 Oct was a holiday), SENSEX Thursday. "Untraded" = a leg of ≥100 NF / ≥300 SX points with no position.

| Day | Net | Shape | What was traded | What was missed / went wrong |
|---|---|---|---|---|
| 2 Sep Wed | +20.7 K | V-day 23858→23786→23900 | 2 MAC PE, TIME_60, tiny | Confirm scores oscillated 20–80 all day: no edge, correct to be small |
| 3 Sep Thu (SX exp) | −1.35 L | SX −250 then bounce then −300 | NF MAC PE +63 K TARGET; **SX ETR 0DTE PE −1.98 L** (152 lots, ₹4.7 L premium, entered 11:06 at the pullback low, stopped −42 % in 30 min) | The leg it was positioned for came 11:45–13:00, after the stop. Entry at exhaustion of a leg, not on continuation |
| 7 Sep Mon | +23.4 K | Trend down 23860→23740, below VWAP all day | MAC CE #1 counter-trend STOP −24 K in 7 min; MAC CE at the low +34 K; SX CE +13 K | The clean 09:30–11:30 down leg had no PE. MAC bought CE under a falling VWAP |
| 8 Sep Tue (NF exp) | −15.2 K | Chop 23620–23710 | SX MAC PE +2 K; **NF ETR 0DTE PE at the exact day low** 13:52 −17.6 K | ETR entered after the drop, not during it |
| 9 Sep Wed | +1.06 L | Swing, 4 legs | NF MAC PE ×2 TARGET +82 K; SX MAC PE +93 K; SX PE −36 K (swing low), SX CE −32 K (bounce top) | 12:15–12:45 SX +250 rally untraded; two SX losers bought exactly at leg extremes |
| 10 Sep Thu (SX exp) | −37 K | Chop then 14:45 ORL break | 3 MAC TIME_60 (−22 K, +5 K, +0.2 K), ECR SX PE INVALIDATED −20 K in 4 min | #3 CE was +40 pts at 12:20 and gave it all back by the 12:51 time exit; the 14:45 breakdown on both indices untraded |
| 15 Sep Tue (NF exp) | **+4.34 L** | Trend down 23500→23170, PE confirm 75+ all day | NF ETR PE #1 −54 K (chop phase); SX MAC CE −41 K (counter-trend); **NF ETR 0DTE PE 12:59–15:15 +5.30 L** | The day's whole profit is one runner. ETR #1 was the same idea 90 min too early |
| 17 Sep Thu (SX exp) | +2.40 L | Swing, 80–100 pt legs | ODM CE +38 K (held through a 60 pt pullback to breakeven); 3 MAC NF wins +242 K; SX ETR CE −40 K (bought 0DTE CE at 74590 after a 300 pt rally) | SX ECR CE not filled. MAC on a clean swing day is good |
| 18 Sep Fri | −37.8 K | Chop AM, trend up 13:45–15:00 | 6 MAC whipsaws; **PE bought on both at 13:42–13:45 while the CE confirm score was 80+** → both STOP | The 14:00–15:00 rally (+75 NF / +270 SX) untraded. MAC ignores its own confirm score |
| 28 Sep Mon | +3.27 L | Gap-down trend day | ODM PE both +2.20 L (2.5 h); NF MAC PE 14:09–14:19 +1.18 L (10 min); SX PE +41 K; 2 counter-trend/chop MAC −52 K | Good day. Futures-volume surges at 09:20 and 14:10 marked both winning legs exactly |
| 29 Sep Tue (NF exp) | +1.55 L | NF 3 legs of 100–140 pts | **NF: zero trades on its own expiry**; SX ODM PE +61 K, SX MAC CE ×2 +94 K | Same shapes on SX fired, NF did not: per-index gating difference on NF expiry |
| 30 Sep Wed | +37.8 K | Chop then 13:45–14:45 breakdown | NF MAC CE +38 K | **Breakdown (−180 NF / −600 SX) untraded; SX zero trades all day.** SX PCR collapsed 1.73→0.69 from 13:30, volume surge 14:45: the OI panel called it, nothing listened |
| 1 Oct Thu (SX exp) | **+9.25 L** | Trend down | NF MAC PE #2 +86 K TARGET; **SX ETR 0DTE PE 12:20–14:30 +8.43 L** | After the NF TARGET exit at 12:24 NIFTY fell a further 260 pts with no NF position (≈ +3–4 L left) |
| 5 Oct Mon | +1.03 L | −180 / −700 morning, V-bottom, rally | NF MAC CE +1.20 L; SX MAC PE −17 K **at the same minute NF bought CE** | Morning decline 10:00–12:00 untraded on both; no cross-index consistency |
| 6 Oct Tue (NF exp) | −1.23 L | Slow grind up +160 / +550 | NF ETR 0DTE CE ×2 −1.23 L (right direction, stopped by 30 pt noise, theta); **SX zero trades on +550** | PCR 1.02→2.17 = put writing all morning; a pullback-to-EMA20 entry would have worked. ODM fired on neither despite a +100/+300 opening drive |
| 7 Oct Wed | +1.03 L | Swing, −100/+100 then −100 | NF MAC PE 12:21–13:09 +1.03 L | SX same shape (−400, +350) zero trades |
| 8 Oct Thu (SX exp) | +3.64 L | Trend down −380 / −700 | SX ETR 0DTE PE 11:23–13:36 +3.96 L; ETR #2 re-entry at lows −32 K | **NF zero trades on a −380 trend day** (chart says no futures volume on that server → volume-gated signals never fired). PCR 0.31/0.26 |
| 9 Oct Fri | +1.59 L | Trend up | NF ODM CE +1.16 L; 2 MAC CE +43 K | SX ODM did not fire on a +600 drive |
| 12 Oct Mon | +3.21 L | Gap-down trend | ODM PE both +3.80 L (hard FLAT_BY 12:00); MAC CE both −59 K at the bounce top, PCR 0.45 | 13:30–15:00 continuation untraded after the 12:00 flat |
| 13 Oct Tue (NF exp) | **+7.12 L** | Trend up | **NF ETR 0DTE CE 14:08–15:15 +6.61 L**; SX ODM CE +1.01 L; SX MAC PE −50 K counter-trend | NF opening drive +270 (09:15–09:50) untraded; ODM fired only on SX |
| 14 Oct Wed | +1.46 L | Range then 13:00 waterfall then V | 12:54 PE pair +3.15 L (volume surge, call build + put unwind); 10:42 CE pair −99 K (bounce tops in range); 14:12 PE pair −70 K (after V-bounce) | Two of three MAC pairs lost; the one with the OI/volume confirmation made everything |
| 15 Oct Thu (SX exp) | +2.65 L | Trend up +350 / +1300 | SX ETR 0DTE CE 11:40–14:06 +2.29 L; NF MAC PE late +36 K | NF +350 rally untraded. ODM did not fire (drive began 09:30). SX PCR 1.18→14.7 |
| 16 Oct Fri | **−1.99 L** | Gap-down below PDL, V-reversal at 09:35, +200 / +700 by 10:15 | ODM PE both at 09:21, STOP at 10:06/10:15 (−1.22 L, −77 K) | Held 45–55 min while price reclaimed the open; PCR 0.70→1.07 by 10:00 was put writing (bullish). Rest of day flat, nothing traded |
| 19 Oct Mon (NF exp) | +32 K | Trend down −300 / −1100 | SX ODM PE +22 K only (held through a 1.5 h bounce, flat at 12:00, **then SX fell 500 more**); NF ETR PE +58 K; ETR re-entry at lows −48 K | NF 09:30–12:00 decline untraded |
| 21 Oct Wed | **0** | Trend down −400 / −1300, below EMA20 all day | **Nothing on either index** | Open was not a drive (15 min chop then rollover at 09:50), so ODM idle; one EMA cross at 09:45 and no re-cross, so MAC idle; not expiry, so ETR idle. PCR 0.99→0.35. The largest SX move of the set, untraded |
| 22 Oct Thu (SX exp) | −1.49 L | NF gap-down V (same as 16 Oct); SX 500–600 pt expiry swings | NF ODM PE 09:24 STOP −1.49 L (177→106, −40 %) | SX expiry with four 400–600 pt legs: zero trades |
| 23 Oct Fri | +1.24 L | Morning decline −350 / −1000, 13:36 +100 / +400 spike | NF MAC CE 13:36–13:40 **+1.24 L in 4 min** | Morning decline untraded on both; SX spike untraded |

---

## 3. The seven failure mechanisms (ranked by rupees)

**F1. Opening-drive on gap-down V-reversals: −3.48 L (16 Oct ×2, 22 Oct).**
All three: open below PDL, first 5-min drive down, ODM PE at 09:21–09:24, price reclaimed the day open within 15 minutes, position held another 40–55 minutes to a −40 % premium stop. Each time the OI panel showed PCR rising in the first 15 min (put writers selling into the gap). The strategy has no "the drive failed" invalidation; it only has a premium stop.

**F2. ETR entries at leg exhaustion on 0DTE: −4.64 L (3 Sep, 17 Sep, 6 Oct ×2, 8 Oct #2, 19 Oct #3, 8 Sep, 15 Sep #1).**
Every ETR loser entered after a 200–300 pt leg had already run (or at the exact day extreme), with the swing-reclaim stop 30–45 % of premium away. Every ETR winner entered mid-trend with a fresh continuation under way (12:59 on 15 Sep, 12:20 on 1 Oct, 11:23 on 8 Oct, 14:08 on 13 Oct, 11:40 on 15 Oct). ETR's edge is the runner, not the entry; the entry needs a continuation trigger and a runway check.

**F3. Counter-trend MAC entries: ≈ −3.6 L.**
CE below a falling VWAP (7 Sep #1, 15 Sep SX, 28 Sep NF, 12 Oct both, 9 Sep SX) or PE above a rising one (13 Oct SX, 5 Oct SX). MAC has no VWAP-side or OI-drift gate. On 18 Sep both indices bought PE at 13:42 while the CE confirm score was 80+ and the 14:00 breakout went the other way.

**F4. Chop-day whipsaws: −2.2 L across 15 trades (18 Sep, 10 Sep, 8 Sep, 2 Sep).**
Average win +6 K, average loss −32 K. These days are identifiable by 10:30: PCR hugging 1.0, the "calls − puts added" lines crossing repeatedly, no VWAP separation, confirm scores oscillating 20–80.

**F5. Exit leakage (opportunity, not realised loss): roughly 5–7 L.**
TARGET exits on 1 Oct NF (then −260 pts more) and 23 Oct; TIME_60 exits giving back open profit (10 Sep #3 +40 pts → +5 K; 9 Oct, 29 Sep); ODM hard FLAT_BY at 12:00 on 12 Oct and 19 Oct while price was still below EMA20 (SX fell 500 more after the 19 Oct flat).

**F6. Coverage gaps (opportunity, largest of all).**
- 09:30–10:30 dead zone: ODM fires at 09:21 or not at all; MAC's first entry is ~10:30. The 09:30–11:30 legs on 7 Sep, 5 Oct, 8 Oct, 19 Oct, 21 Oct, 23 Oct were all untraded.
- Smooth trend days with a single EMA20 cross: MAC needs a re-cross, so a one-way day produces zero MAC signals (21 Oct both, 8 Oct NF, 6 Oct SX, 23 Oct mornings).
- SX silent when NF fires on the identical shape (7 Oct, 23 Oct, 6 Oct, 30 Sep).
- Expiry index silent on its own expiry when the day is swingy rather than trending (29 Sep NF, 22 Oct SX, 10 Sep SX).
- Data gaps turn into trading gaps: 8 Oct NF had no futures volume on that server and produced no trades on a −380 day.

**F7. Sizing asymmetry.**
ETR uses ₹4.4–4.7 L premium on a 0DTE with a 30–45 % stop (−1.5 to −2 L per loser). MAC uses ₹2.45 L. ODM uses ₹3.7 L with a 40 % stop. The strategies with the widest stops carry the largest size.

---

## 4. What the OI and volume panels were saying (and the system was not using)

From 28 Sep onward the capture includes option OI and futures volume. On the trend days the signal is visible by 10:30 and monotonic:

| Day | PCR 09:15 → 10:30 → close | "calls − puts added" | Outcome |
|---|---|---|---|
| 8 Oct | 0.99 → 0.6 → 0.31 | calls build, puts flat from 09:30 | −380 / −700 trend down |
| 21 Oct | 0.99 → 1.4 → 0.35 | lines cross at 10:30 then diverge | −400 / −1300 trend down |
| 6 Oct | 1.02 → 1.5 → 2.17 | puts build all morning | +160 / +550 grind up |
| 9 Oct | 1.03 → 2.0 → 2.98 (SX 6.6) | puts build | trend up |
| 15 Oct | 0.80 → 1.5 → 2.51 (SX 14.7) | puts build | +350 / +1300 |
| 30 Sep (chop then break) | 1.18 → 1.0 → 0.65 | flat then calls build from 13:30 | 13:45 breakdown |
| 22 Oct SX, 14 Oct, 2 Sep | hugs 1.0, lines cross repeatedly | — | chop / V-swings |

The 5-minute OI change bars give the acceleration moment: a large put-unwind bar (orange negative) together with a call-build bar (blue positive) and an amber futures-volume surge preceded the winning legs on 1 Oct 12:20, 14 Oct 12:54, 7 Oct 13:00, 23 Oct 13:36, 28 Sep 14:10. That combination never appeared before a losing MAC entry in the set.

---

## 5. Plan

### 5.1 Fix the existing four (in order of rupees recovered)

**A. Regime engine (new shared component, feeds all four).**
Compute at 10:15 and roll every 5 min, per index:
1. Price vs VWAP and EMA20 slope (sign and separation in ATR units).
2. PCR drift: ln(PCR_now / PCR_09:15). |drift| > 0.3 → trend, sign gives direction. Drift within ±0.15 and the calls/puts-added lines crossing more than twice → range.
3. Opening range: broke ORH/ORL and held 15 min → trend; back inside → range.
4. Futures volume: a ≥5× surge in the direction of (1) upgrades the regime to "impulse".
Output: `TREND_UP / TREND_DOWN / RANGE / UNDECIDED` plus a confidence. Every strategy reads it.

**B. MAC (ma-cross).**
- Direction gate: only CE when regime ≠ TREND_DOWN and price ≥ VWAP; only PE when regime ≠ TREND_UP and price ≤ VWAP. In RANGE allow both sides but at half size with target = range midpoint.
- Confirm-score agreement: require own-side confirm ≥ 60 and opposite ≤ 45 at entry (would have blocked the 18 Sep 13:42 PE pair).
- Cross-index consistency: if the other index fired the opposite side within 5 min, skip (5 Oct, 12 Oct).
- Exit: replace TARGET with scale-out (50 % at target, trail the rest on EMA20 or last swing); replace TIME_60 with "at 60 min, exit only if not ≥ +15 % premium; otherwise trail". Expected: 1 Oct NF, 23 Oct, 10 Sep #3 all improve.
- No entry in the last 60 minutes unless regime = impulse (the 14:12 pair on 14 Oct, 14:27 on 19 Oct).

**C. ODM (opening-drive-momentum).**
- Gap-reversal invalidation: if price reclaims the day open (PE) / loses it (CE) within 20 min of entry, exit at market. Would turn 16 Oct (−1.99 L) into ≈ −0.4 L and 22 Oct (−1.49 L) into ≈ −0.3 L.
- OI veto at entry: no PE if PCR has risen > 10 % since 09:15 (put writers absorbing the gap); no CE if it has fallen > 10 %.
- Gap-size rule: open > 0.6 % below PDL → require the first-15-min low to hold for 5 min before PE (gap-fill base rate is high).
- Drive detection window: extend from "first 5 min" to "any 10-min bar closing beyond ORH/ORL before 10:00" so 6 Oct, 13 Oct NF, 15 Oct, 9 Oct SX drives qualify.
- FLAT_BY 12:00: convert to "at 12:00 exit only if price has crossed back over EMA20; else trail at EMA20 until 14:30". 12 Oct and 19 Oct SX would have run.

**D. ETR (expiry-trend-rider).**
- Entry only on continuation, not exhaustion: price pulls back to EMA20 (or VWAP) in a TREND regime, holds for 3 bars, resumes with a 1-min close beyond the pullback high/low. No entry after three consecutive same-direction legs without a pullback.
- Runway check: distance to the next level (PDL/PDH/weekly) ≥ 0.4 % of spot; else skip.
- Stop in premium terms (−25 %), not swing-reclaim, with a time stop of 25 min if not ≥ +10 %.
- No fresh ETR after 14:15 unless the previous ETR of the day closed in profit (8 Oct #2, 19 Oct #3).
- Size: cap initial 0DTE premium at ₹2.5 L; add ₹2 L once +20 % and the stop moves to cost (pyramid into the runner instead of starting at ₹4.7 L).
- Allow ETR on the non-expiry index at 1DTE/2DTE on trend days with the same rules, at 60 % size (1 Oct NF, 8 Oct NF, 21 Oct both).

**E. ECR.** One fill, one unfilled in 27 days. Either lower the confirm threshold and let it take the 09:30–10:30 continuation (see 5.2 a), or retire it.

**F. Data-gap guard.** If futures volume or OI is missing for an index, fall back to price-only versions of the gates rather than silently producing no signals (8 Oct NF).

### 5.2 Three new strategies for the gaps

**a. ORB-retest (09:40–10:45).** 15-min opening range. Entry on a 1-min close beyond ORH/ORL, retest of the level within 10 min that holds, regime direction agreeing (or UNDECIDED with OI drift agreeing). Weekly (not 0DTE) ATM option, ₹2 L premium, stop at the range midpoint, scale-out at 1× range, trail rest. Covers 7 Sep, 5 Oct, 8 Oct NF, 19 Oct, 21 Oct, 23 Oct mornings, 13 Oct NF drive.

**b. Trend-pullback (10:30–14:30, TREND regime only).** The MAC replacement on one-way days. Buy with-trend option on a pullback to EMA20 that holds (no close beyond it) and resumes; re-enter on every subsequent pullback while the regime holds; exit on a close across EMA20 or 15:10. Covers 21 Oct both, 6 Oct SX, 8 Oct NF, 15 Oct NF, 19 Oct NF morning.

**c. Level-break impulse (any time, 10:00–14:45).** Trigger = 1-min close through ORH/ORL/PDH/PDL/day-open with a ≥5× futures-volume surge and a same-direction 5-min OI bar (call build + put unwind for PE, the reverse for CE). Short-hold momentum: ₹2 L premium, stop at the broken level, exit at 2× the 1-min range or 20 min. Covers 30 Sep 13:45, 23 Oct 13:36 SX, 14 Oct 12:54, 10 Sep 14:45, 28 Sep 14:10.

**d. (Optional, if risk v17 permits short premium.) Expiry-range credit.** On the expiry index when regime = RANGE at 11:00 (22 Oct SX, 8 Sep NF, 10 Sep SX, 29 Sep NF, 2 Sep), sell the ATM straddle sized so a 1 % move = −₹1 L, hedge each side with the ORH/ORL break (buy the breakout option on strategy c) and flatten at 14:30. This is the one shape the current set cannot monetise at all.

### 5.3 Sizing and daily risk

- Per-trade risk, not per-trade premium: size every entry so stop-loss = ₹60–80 K (MAC/ORB/impulse) or ₹1 L (ODM/ETR initial). Pyramid only into open profit.
- Day-type throttle: after the regime engine says RANGE, cap the day at 3 trades per index and halve size. After two consecutive losses on one index, that index stops for 60 min.
- The ₹5 L daily cap was never reached (worst −2 L); the binding constraint is the chop bleed, not the cap.

### 5.4 Counterfactual on the seven losing days (same signals, with A–D applied)

| Day | Actual | With fixes | Mechanism |
|---|---|---|---|
| 3 Sep | −1.35 L | ≈ +0.5 L | ETR exhaustion rule blocks 11:06; continuation entry 11:50 after the bounce fails |
| 8 Sep | −15 K | ≈ 0 | ETR blocked at day low (no runway) |
| 10 Sep | −37 K | ≈ +0.3 L | RANGE → half size; impulse strategy on the 14:45 ORL break |
| 18 Sep | −38 K | ≈ +0.8 L | Confirm-score gate blocks the 13:42 PE pair; impulse CE on the 14:00 break |
| 6 Oct | −1.23 L | ≈ +1.5 L | ETR pullback entries; trend-pullback on SX +550 |
| 16 Oct | −1.99 L | ≈ −0.4 L | ODM reclaim invalidation at 09:40 |
| 22 Oct | −1.49 L | ≈ −0.3 L | Same; SX expiry range credit optional |

Sum of losing days: −6.2 L actual → roughly +2.4 L. Add the coverage days (21 Oct, 8 Oct NF, 30 Sep, 1 Oct NF continuation, 5 Oct morning) and the realistic uplift on this set is in the +15–25 L range on top of +39 L, with the standard deviation of daily P&L lower because the chop bleed and the three gap-reversal stops disappear.

### 5.5 Validation order

1. Build the regime engine and back-label all 27 days; check it agrees with the day-type column above by 10:30 on at least 22 of 27.
2. Re-run the 27 days with only the MAC direction gate and the ODM reclaim invalidation (two small changes, largest rupee effect).
3. Add the ETR continuation entry and premium stop; confirm the five runners are still taken (they all had a pullback-and-resume within 20 min of the actual entry).
4. Add ORB-retest and trend-pullback; measure the 09:30–10:30 and one-way-day coverage.
5. Add the impulse strategy last; it is the most data-dependent (needs volume + OI).
6. Only then revisit sizing.
