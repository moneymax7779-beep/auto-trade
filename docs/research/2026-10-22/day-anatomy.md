# NIFTY / SENSEX day anatomy and what precedes big intraday moves (1 Jan – 21 Oct 2026)

Prepared 22 Oct 2026 from the local `autotrade` database only (read-only: `hist.candle` 1-minute bars 1 Jan–6 Oct; own tick capture `md.*` 28 Sep–21 Oct). Nothing was written to any database; zt-tiger-v2 was not touched. All numbers are reproduced by the scripts in this folder (see section 9).

## 0. Executive summary

1. **Day types are evenly spread, not dominated by trends.** Over 396 index-days (198 NIFTY + 198 SENSEX): clean trend days (close in the outer 20 % of the range, efficiency ≥ 0.6) 20 %, gap-and-go 9 %, drift (efficiency 0.3–0.6) 28 %, V / inverted-V 20 %, range 12 %, gap-fill 10 %. NIFTY and SENSEX get the same type on 72 % of dates. October (own capture) was unusually trendy (41 % trend+gap-and-go vs 28–29 % in Jan–Sep).
2. **Extremes cluster at the open and the close.** 34 % of day highs and 28 % of day lows print in 09:15–09:29; 27 % of highs and 38 % of lows print after 14:00. On 71 % of days at least one extreme is set before 10:00 (uniform-time null ≈ 23 %). The day's largest 0.25 %-zig-zag leg starts in the first 15 minutes on 42 % of days (55 % of trend days); median leg 0.73 %, 160 minutes.
3. **Volatility regime dominates.** Impulses (≥ 0.35 % in ≤ 30 min, non-overlapping) average 2.58 per index-day: 0.66/day when India VIX opens < 12 vs 5.1/day when ≥ 17.5; Jan–May 3.7/day, Jun–Sep 1.1/day, October 3.0/day. A quarter start in 09:15–09:44; a second cluster 14:15–15:00 (stronger on expiry days).
4. **Compression does NOT precede expansion at 15–30 minutes; activity clusters.** A top-quintile 15-min range (vs its trailing-20-day norm at the same minute) makes an impulse in the next 15 minutes 1.9× more likely (1.6× stratified by hour and day-range-so-far; P1 1.87, P2 1.81, P3 1.34). Bottom-quintile (compressed) range: 0.45×. Futures volume (1.45×), VIX moving (1.4–1.6×), near-ATM option OI build-up (1.6×, 1.3× stratified) and an earlier impulse today (1.46×) also raise the odds. Proximity to ORH/ORL/PDH/PDL/PDC, VWAP or round numbers has no lift in Jan–Sep (0.65–1.05); only in October ticks (1.4–1.65×, wide CIs).
5. **None of this is directional, and it is priced.** Over Jan–Sep no state variable (15-min momentum, open-to-now trend, VWAP side, OR position, PE−CE OI flow, futures OI × price, breadth change, VIX change) predicts the sign of the next 30 minutes by more than ±0.01 % of index; hit rates 48–53 %. Consecutive 15-min returns persist 48.5–51 % (ρ −0.05…+0.04). An ATM straddle bought when an impulse is 2× more likely earns +0.22 % of premium in 15 min (CI −0.19…+0.65) — below costs.
6. **Entering after confirmation earns nothing.** A thrust of 0.10–0.35 % off the 10-minute extreme has zero mean continuation over 15–30 min (hit 45–51 %). Inside 738 impulses (bar days), the ATM option in the impulse direction makes +71 % onset→peak (+162 % on expiry days) and +36 % onset→+15 min, but only +0.3 % (CI −2.2…+3.2) from the threshold crossing to +15 min. Median move left after 0.35 % is crossed: 0.08 %.
7. **0DTE options are fairly priced on average.** Fixed-strike ATM straddles bought 09:45–14:30 on expiry days and held to 15:25 return −13 % … +8 % (CIs ±20–25 %), medians −13 … −37 %, win 25–45 %. Momentum vs fade legs: no reliable difference.
8. **Level breaks rarely run.** A first close beyond ORH/ORL/PDH/PDL extends ≥ 0.25 % before any close back inside on 17 % of breaks (random control 11 %); with a 0.10 % buffer 36 % vs 30 %. Mean 30-min drift +0.005 %. After 10:15 out of a wide range on high volume: 26 % / 45 %, +0.08 % drift (similar P1 and P2+P3); out of compression: 5–9 %.
9. **30-second microstructure.** Futures OFI is coincident with index moves (ρ 0.5–0.6) but leads the next 30 s only weakly (NIFTY 0.04, SENSEX 0.15). Before the 10 largest impulses per index, OFI and signed volume point against the coming move (end of the counter-move) and flip at onset (86 % of 0.25 % impulses have aligned OFI in the first minute after). The SENSEX index print lags NIFTY by up to 30 s (ρ 0.20 ± 0.02, 14 days) but SENSEX option prices do not (ρ 0.86 same bucket, 0.00 next).
10. **Ranked hypotheses (section 7):** compression veto; VIX/realized-range regime gate; trigger SENSEX option entries from NIFTY; expiry-day OI build-up veto; no confirmation chasing; wide-range + high-volume break filter; fade very large first-15-min moves; momentum as a regime switch. Only the first four are consistent in Jan–May and Jun–Oct.

## 1. Data, sets and method

**Sources.** `hist.candle` 1-minute bars: NIFTY 50 and SENSEX indices, INDIA VIX, all listed index futures (volume summed over contracts, OI summed with per-contract forward-fill), nearest-weekly-expiry options for strikes within the day range ± 300 (NIFTY) / ± 1,000 (SENSEX) points (6.3 M bars), constituent EQUITY bars (2 Jul–6 Oct). Own capture `md.*` aggregated to 30-second buckets: index ticks (NIFTY, SENSEX, VIX), near-month futures (last price, cumulative volume, OI, best-level and 5-level depth, Cont–Kukanov–Stoikov OFI, Lee–Ready-signed volume, spread), all nearest-expiry option ticks (LTP, OI, IV, spread), constituent ticks with previous close. Bars and ticks agree on overlap days (max 1-minute close difference 1.7–3.0 NIFTY points, 5–16 SENSEX points; identical futures volume; ATM straddle within 0.5 %).

**Sets.** ALL = 188 bar days (1 Jan–6 Oct) + 10 tick-only days (7–21 Oct) per index = 396 index-days; P1 = Jan–May bars (198); P2 = 1 Jun–25 Sep bars (166); P3 = own tick capture 28 Sep–21 Oct (32 index-days, built from ticks). Expiry day = the index whose nearest option expiry is that date (42 per index).

**Definitions.**
* Gap = 09:15 open vs previous 15:29 close. Opening range (OR) = 09:15–09:29 high/low. VWAP proxy = index typical price weighted by futures volume. Efficiency = |close − open| / range.
* Day type (precedence top-down): |gap| ≥ 0.40 % and previous close touched → gap-fill; |gap| ≥ 0.40 %, not filled, open→close in gap direction with efficiency ≥ 0.4 → gap-and-go; efficiency ≥ 0.6 and close in the outer 20 % → trend; low before high, open−low ≥ 0.4 R and close−low ≥ 0.6 R → V (mirror: inv-V); efficiency < 0.3 → range; else drift.
* Impulse: on 1-minute closes, first minute e with close ≥ X % from the most extreme close of the preceding ≤ W minutes; onset s = that extreme; peak = most extreme close within W minutes of onset; non-overlapping. Definitions 0.35 %/30 min and 0.25 %/15 min.
* Precursors at decision time t use only minutes ≤ t: 15/30-min range and 5/15-min futures volume ÷ trailing-20-index-day median at the same minute; range so far; 15-min futures OI change; ATM straddle 15-min change minus its time-of-day/expiry norm; near-ATM (ATM ± 2) option OI change (total, PE − CE) as % of near-ATM OI; distance to ORH/ORL/PDH/PDL/PDC (≤ 0.10 % = near), VWAP (≤ 0.05 %), round numbers (NIFTY 100s, SENSEX 500s); breadth (adv − dec)/n and its 15-min change; VIX 15/30-min change; 15-min and open-to-now returns; impulses already today.
* Lift: grid every 5 min 09:45–14:59 (24,948 points). Outcomes y35 = impulse onset in [t, t+14]; yfwd = 30-min max excursion ≥ 0.35 % (no hindsight). Quintile cut-offs fixed on P1, applied to P2/P3. 95 % CIs by day-clustered bootstrap. Stratified lift = within hour × range-so-far-tercile strata.

**Caveats.** (a) NIFTY and SENSEX are ~93 % sign-correlated per day; pooled n overstates independent evidence. (b) Dozens of conditions were screened; only consistency across P1, P2 and P3 counts. (c) P3 is 16 days of one trending month. (d) The held-out sessions 22–25 Sep (`research.session_split`) are inside P2 here (8 of 396 index-days); validate on sessions from 22 Oct onward. (e) Option OI in bars updates every 1–3 min; SENSEX futures are thin (25–30-point spread); futures ticks missing 28–29 Sep; breadth only from 2 Jul. (f) Close = 15:29 bar close, not the official close. (g) The impulse onset is by construction the extreme of the preceding window, so price-derived pre-onset statistics are tilted against the impulse direction; the forward grid analyses (C, E, F) have no such bias.

## 2. Day anatomy (task 1)

Index-days: 396 (198 NIFTY, 198 SENSEX); expiry days 84; tick-built days (7-21 Oct) 20.


#### Day-type frequency (row %), overall and by index / expiry

| group | n | trend-up % | trend-down % | drift-up % | drift-down % | range % | V % | inv-V % | gap-and-go % | gap-fill % | trend+gag % | day range % | eff | |gap| % |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 396 | 8 | 12 | 12 | 16 | 12 | 10 | 10 | 9 | 10 | 29 | 1.04 | 0.49 | 0.46 |

| group | n | trend-up % | trend-down % | drift-up % | drift-down % | range % | V % | inv-V % | gap-and-go % | gap-fill % | trend+gag % | day range % | eff | |gap| % |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | 198 | 8 | 12 | 11 | 16 | 15 | 12 | 9 | 10 | 8 | 29 | 1.02 | 0.48 | 0.45 |
| SENSEX | 198 | 8 | 13 | 13 | 17 | 9 | 9 | 11 | 9 | 13 | 29 | 1.06 | 0.50 | 0.48 |

| group | n | trend-up % | trend-down % | drift-up % | drift-down % | range % | V % | inv-V % | gap-and-go % | gap-fill % | trend+gag % | day range % | eff | |gap| % |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY expiry(0DTE) | 42 | 12 | 17 | 7 | 17 | 10 | 12 | 14 | 2 | 10 | 31 | 1.11 | 0.49 | 0.53 |
| NIFTY non-expiry | 156 | 6 | 10 | 12 | 16 | 16 | 12 | 8 | 12 | 8 | 29 | 1.00 | 0.47 | 0.43 |
| SENSEX expiry(0DTE) | 42 | 2 | 14 | 17 | 10 | 12 | 10 | 12 | 7 | 17 | 24 | 1.08 | 0.47 | 0.42 |
| SENSEX non-expiry | 156 | 9 | 13 | 12 | 19 | 8 | 9 | 10 | 9 | 12 | 31 | 1.05 | 0.51 | 0.49 |


#### By month (both indices)

| group | n | trend-up % | trend-down % | drift-up % | drift-down % | range % | V % | inv-V % | gap-and-go % | gap-fill % | trend+gag % | day range % | eff | |gap| % |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-01 | 40 | 8 | 15 | 10 | 22 | 5 | 15 | 18 | 0 | 8 | 22 | 0.96 | 0.45 | 0.22 |
| 2026-02 | 42 | 10 | 17 | 12 | 26 | 12 | 12 | 5 | 5 | 2 | 31 | 1.14 | 0.52 | 0.46 |
| 2026-03 | 38 | 0 | 11 | 24 | 5 | 11 | 13 | 13 | 21 | 3 | 32 | 1.58 | 0.45 | 1.07 |
| 2026-04 | 40 | 15 | 2 | 8 | 12 | 15 | 10 | 5 | 18 | 15 | 35 | 1.24 | 0.53 | 0.81 |
| 2026-05 | 38 | 0 | 0 | 11 | 11 | 8 | 5 | 18 | 18 | 29 | 18 | 1.12 | 0.45 | 0.38 |
| 2026-06 | 42 | 10 | 12 | 5 | 19 | 7 | 17 | 10 | 5 | 17 | 26 | 0.98 | 0.46 | 0.46 |
| 2026-07 | 46 | 4 | 17 | 22 | 9 | 11 | 4 | 15 | 13 | 4 | 35 | 0.77 | 0.50 | 0.36 |
| 2026-08 | 42 | 5 | 12 | 5 | 33 | 24 | 10 | 5 | 2 | 5 | 19 | 0.62 | 0.44 | 0.26 |
| 2026-09 | 42 | 7 | 19 | 14 | 7 | 19 | 10 | 7 | 10 | 7 | 36 | 0.79 | 0.53 | 0.32 |
| 2026-10 | 26 | 23 | 19 | 8 | 19 | 4 | 8 | 0 | 0 | 19 | 42 | 1.41 | 0.59 | 0.26 |


#### By gap size (open vs previous close)

| group | n | trend-up % | trend-down % | drift-up % | drift-down % | range % | V % | inv-V % | gap-and-go % | gap-fill % | trend+gag % | day range % | eff | |gap| % |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| <= -0.5% | 58 | 0 | 0 | 17 | 3 | 9 | 16 | 9 | 22 | 24 | 22 | 1.25 | 0.51 | 1.07 |
| -0.5..-0.2% | 62 | 0 | 19 | 5 | 16 | 11 | 13 | 13 | 11 | 11 | 31 | 1.10 | 0.45 | 0.33 |
| -0.2..+0.2% | 149 | 15 | 15 | 15 | 25 | 11 | 8 | 11 | 0 | 0 | 30 | 0.96 | 0.50 | 0.10 |
| +0.2..+0.5% | 70 | 11 | 14 | 13 | 16 | 16 | 10 | 9 | 3 | 9 | 29 | 0.96 | 0.48 | 0.31 |
| >= +0.5% | 55 | 0 | 9 | 5 | 7 | 13 | 9 | 4 | 27 | 25 | 36 | 1.08 | 0.49 | 1.14 |


#### By India VIX at 09:15

| group | n | trend-up % | trend-down % | drift-up % | drift-down % | range % | V % | inv-V % | gap-and-go % | gap-fill % | trend+gag % | day range % | eff | |gap| % |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| VIX <12 | 90 | 6 | 19 | 7 | 29 | 14 | 8 | 10 | 6 | 2 | 30 | 0.71 | 0.49 | 0.24 |
| VIX 12-14 | 124 | 7 | 15 | 11 | 14 | 15 | 14 | 10 | 6 | 7 | 29 | 0.89 | 0.47 | 0.39 |
| VIX 14-17.5 | 82 | 12 | 10 | 17 | 18 | 4 | 7 | 6 | 6 | 20 | 28 | 1.26 | 0.53 | 0.42 |
| VIX >=17.5 | 100 | 6 | 5 | 13 | 7 | 13 | 11 | 12 | 19 | 14 | 30 | 1.34 | 0.47 | 0.77 |


#### When the day high and day low are set (share of index-days)

| extreme | 09:15-09:29 % | 09:30-09:59 % | 10:00-10:59 % | 11:00-11:59 % | 12:00-12:59 % | 13:00-13:59 % | 14:00-14:59 % | 15:00-15:29 % |
|---|---|---|---|---|---|---|---|---|
| high (all, n=396) | 34 | 6 | 9 | 6 | 9 | 8 | 13 | 14 |
| high (expiry, n=84) | 36 | 6 | 11 | 6 | 10 | 8 | 10 | 14 |
| high (non-expiry, n=312) | 33 | 6 | 9 | 5 | 9 | 8 | 14 | 14 |
| low (all, n=396) | 28 | 7 | 9 | 5 | 6 | 8 | 19 | 19 |
| low (expiry, n=84) | 25 | 4 | 8 | 6 | 1 | 8 | 21 | 26 |
| low (non-expiry, n=312) | 28 | 8 | 9 | 5 | 7 | 8 | 18 | 17 |


At least one extreme set before 10:00: 71 % of days. Both extremes in the first 45 min or the last 30 min: 25 %. (Uniform-time null for 'one extreme before 10:00' with 45/375 minutes per extreme ≈ 23 %.)


#### Main move (leg between the two extremes in the close direction): start time and size

| group | 09:15-09:29 % | 09:30-09:59 % | 10:00-10:59 % | 11:00-11:59 % | 12:00-12:59 % | 13:00-13:59 % | 14:00-14:59 % | 15:00-15:29 % | median size % | median share of range | median duration min |
|---|---|---|---|---|---|---|---|---|---|---|---|
| all (n=396) | 67 | 8 | 13 | 6 | 4 | 1 | 1 | 0 | 0.87 | 1.00 | 252.50 |
| NIFTY (n=198) | 66 | 8 | 14 | 5 | 5 | 2 | 1 | 0 | 0.86 | 1.00 | 240.00 |
| SENSEX (n=198) | 68 | 8 | 12 | 7 | 3 | 1 | 2 | 0 | 0.94 | 1.00 | 257.50 |
| expiry (n=84) | 68 | 6 | 15 | 7 | 2 | 0 | 1 | 0 | 0.97 | 1.00 | 272.00 |
| trend days (n=116) | 77 | 5 | 6 | 5 | 5 | 1 | 1 | 0 | 1.03 | 1.00 | 340.00 |
| non-trend (n=280) | 63 | 9 | 16 | 6 | 3 | 1 | 1 | 0 | 0.84 | 1.00 | 222.00 |


#### Largest swing leg of the day (zig-zag on 1-min closes, 0.25 % reversal): start time, size, duration

| group | 09:15-09:29 % | 09:30-09:59 % | 10:00-10:59 % | 11:00-11:59 % | 12:00-12:59 % | 13:00-13:59 % | 14:00-14:59 % | 15:00-15:29 % | median size % | median duration min | median legs/day | leg in close direction % |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| all (n=396) | 42 | 9 | 11 | 15 | 11 | 6 | 5 | 1 | 0.73 | 159.50 | 3.00 | 87 |
| NIFTY (n=198) | 41 | 9 | 11 | 14 | 12 | 7 | 5 | 1 | 0.72 | 161.50 | 3.00 | 86 |
| SENSEX (n=198) | 43 | 10 | 12 | 15 | 10 | 5 | 5 | 1 | 0.74 | 156.50 | 4.00 | 88 |
| expiry (n=84) | 39 | 10 | 13 | 20 | 8 | 5 | 4 | 1 | 0.78 | 165.00 | 4.00 | 87 |
| non-expiry (n=312) | 43 | 9 | 11 | 13 | 12 | 6 | 5 | 0 | 0.71 | 157.00 | 3.00 | 88 |
| trend days (n=116) | 55 | 7 | 8 | 10 | 8 | 4 | 8 | 0 | 0.76 | 239.00 | 3.00 | 98 |
| non-trend (n=280) | 37 | 10 | 13 | 16 | 12 | 6 | 4 | 1 | 0.71 | 141.00 | 4.00 | 83 |


Time at which half of the final open→close move was first reached: 09:15-09:29 41 %, 09:30-09:59 16 %, 10:00-10:59 12 %, 11:00-11:59 8 %, 12:00-12:59 8 %, 13:00-13:59 7 %, 14:00-14:59 6 %, 15:00-15:29 2 %.


#### Summary statistics per index (medians; IQR in brackets)

| set | day range % | OR(15m) width % | efficiency | |gap| % median | OR ≥ 50 % of day range (% days) |
|---|---|---|---|---|---|
| NIFTY non-exp n=156 | 0.86 [0.62-1.25] | 0.38 [0.29-0.50] | 0.47 [0.27-0.69] | 0.28 | 43 |
| NIFTY expiry n=42 | 0.93 [0.75-1.45] | 0.42 [0.29-0.60] | 0.57 [0.20-0.72] | 0.23 | 40 |
| SENSEX non-exp n=156 | 0.94 [0.66-1.33] | 0.42 [0.31-0.58] | 0.56 [0.35-0.71] | 0.28 | 48 |
| SENSEX expiry n=42 | 1.00 [0.71-1.33] | 0.37 [0.31-0.54] | 0.47 [0.26-0.68] | 0.30 | 31 |


#### Gap behaviour

| gap bucket | n | gap filled same day % | open→close in gap direction % | mean open→close % | mean prev close→close % |
|---|---|---|---|---|---|
| <= -0.5% | 58 | 24 | 38 | 0.13 | -0.94 |
| -0.5..-0.2% | 62 | 39 | 71 | -0.26 | -0.59 |
| -0.2..+0.2% | 149 | 76 | 54 | -0.10 | -0.09 |
| +0.2..+0.5% | 70 | 59 | 53 | 0.05 | 0.36 |
| >= +0.5% | 55 | 25 | 47 | -0.17 | 0.97 |


#### Day-type mix by period (stability)

| group | n | trend-up % | trend-down % | drift-up % | drift-down % | range % | V % | inv-V % | gap-and-go % | gap-fill % | trend+gag % | day range % | eff | |gap| % |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| P1 Jan-May | 198 | 7 | 9 | 13 | 16 | 10 | 11 | 12 | 12 | 11 | 28 | 1.20 | 0.48 | 0.59 |
| P2 Jun-Sep | 166 | 7 | 14 | 12 | 17 | 15 | 10 | 8 | 8 | 8 | 29 | 0.78 | 0.49 | 0.35 |
| P3 28 Sep-21 Oct | 32 | 19 | 22 | 6 | 16 | 6 | 9 | 6 | 0 | 16 | 41 | 1.35 | 0.55 | 0.25 |


NIFTY and SENSEX get the same day type on 142/198 dates (72 %); same open→close sign on 93 %. Counts above therefore are ~1.3x over-stated as independent samples (two highly correlated indices).


**Reading.** On 2/3 of days the defining move starts from an extreme printed in the first 15 minutes; the single biggest zig-zag swing starts in the first 15 minutes on 42 % of days, otherwise 10:00–13:00, after 14:00 only 6 %. Gaps ≥ 0.5 % are filled the same day only 24–25 % of the time; their open→close is a coin flip with a slight fade (gap up: −0.17 %; gap down: +0.13 %). VIX ≥ 17.5 brings more gap-and-go days (19 % vs 6 %) and wider ranges (1.34 % vs 0.71 %) but not more trend days. Expiry days set their lows late more often (47 % after 14:00 vs 35 %).

## 3. Impulse moves and their precursors (task 2)


#### Impulses 0.35 % in <= 30 min (ALL set: 396 index-days)

Count 1020 = 2.58 per index-day (expiry days 3.02, non-expiry 2.46); up 50 %; median size 0.43 % (p90 0.64); median onset→threshold 19 min, onset→peak 26 min; median move left after the threshold is crossed 0.08 % (mean 0.13). Days with zero impulses: 27 %.

| onset slot | n | per day | per expiry day | per non-expiry day |
|---|---|---|---|---|
| 09:15 | 256 | 0.646 | 0.702 | 0.631 |
| 09:45 | 97 | 0.245 | 0.262 | 0.240 |
| 10:15 | 66 | 0.167 | 0.226 | 0.151 |
| 10:45 | 69 | 0.174 | 0.226 | 0.160 |
| 11:15 | 43 | 0.109 | 0.143 | 0.099 |
| 11:45 | 61 | 0.154 | 0.179 | 0.147 |
| 12:15 | 53 | 0.134 | 0.131 | 0.135 |
| 12:45 | 59 | 0.149 | 0.202 | 0.135 |
| 13:15 | 58 | 0.146 | 0.131 | 0.151 |
| 13:45 | 62 | 0.157 | 0.155 | 0.157 |
| 14:15 | 106 | 0.268 | 0.321 | 0.253 |
| 14:45 | 86 | 0.217 | 0.333 | 0.186 |
| 15:15 | 4 | 0.010 | 0.012 | 0.010 |


Rate by period: P1: 3.71/day (n days 198); P2: 1.14/day (n days 166); P3: 3.00/day (n days 32)


#### Impulses 0.25 % in <= 15 min (ALL set: 396 index-days)

Count 1763 = 4.45 per index-day (expiry days 5.10, non-expiry 4.28); up 49 %; median size 0.31 % (p90 0.46); median onset→threshold 11 min, onset→peak 14 min; median move left after the threshold is crossed 0.06 % (mean 0.09). Days with zero impulses: 20 %.

| onset slot | n | per day | per expiry day | per non-expiry day |
|---|---|---|---|---|
| 09:15 | 411 | 1.038 | 1.071 | 1.029 |
| 09:45 | 174 | 0.439 | 0.476 | 0.429 |
| 10:15 | 128 | 0.323 | 0.417 | 0.298 |
| 10:45 | 110 | 0.278 | 0.298 | 0.272 |
| 11:15 | 90 | 0.227 | 0.298 | 0.208 |
| 11:45 | 92 | 0.232 | 0.262 | 0.224 |
| 12:15 | 105 | 0.265 | 0.262 | 0.266 |
| 12:45 | 99 | 0.250 | 0.250 | 0.250 |
| 13:15 | 99 | 0.250 | 0.298 | 0.237 |
| 13:45 | 97 | 0.245 | 0.262 | 0.240 |
| 14:15 | 151 | 0.381 | 0.476 | 0.356 |
| 14:45 | 185 | 0.467 | 0.631 | 0.423 |
| 15:15 | 22 | 0.056 | 0.095 | 0.045 |


Rate by period: P1: 6.39/day (n days 198); P2: 1.89/day (n days 166); P3: 5.75/day (n days 32)


### B. Pre-onset precursors: impulses vs matched controls

Features are measured at the onset minute using only the preceding 15/30 minutes. Controls: 5 per impulse, same index, other day of the same set, onset time ±15 min, no impulse onset in [t−5, t+30]. Δ = impulse mean − control mean, 95 % CI by day-clustered bootstrap; AUC > 0.5 means larger values precede impulses.

| feature | impulse mean | control mean | Δ (ALL) [95 % CI] | AUC ALL | AUC P1 (n=534) | AUC P2 (n=133) | AUC P3 (n=62) |
|---|---|---|---|---|---|---|---|
| comp15 | 1.68 | 1.10 | +0.579 [+0.455,+0.723] | 0.72 | 0.74 | 0.70 | 0.66 |
| comp30 | 1.64 | 1.10 | +0.533 [+0.410,+0.674] | 0.70 | 0.73 | 0.67 | 0.65 |
| compday | 1.19 | 0.95 | +0.247 [+0.180,+0.332] | 0.62 | 0.61 | 0.67 | 0.66 |
| rngsofar | 1.38 | 1.09 | +0.289 [+0.171,+0.395] | 0.66 | 0.71 | 0.60 | 0.43 |
| vol15 | 2.17 | 1.38 | +0.791 [+0.486,+1.144] | 0.64 | 0.66 | 0.61 | 0.59 |
| vol5 | 2.50 | 1.63 | +0.872 [+0.495,+1.283] | 0.64 | 0.66 | 0.64 | 0.58 |
| absfoi15 | 0.50 | 0.36 | +0.139 [+0.049,+0.238] | 0.57 | 0.54 | 0.57 | 0.54 |
| strad15 | 0.79 | 0.25 | +0.536 [+0.106,+0.956] | 0.50 | 0.51 | 0.56 | 0.50 |
| optoi15 | 3.69 | 2.60 | +1.091 [+0.466,+1.670] | 0.56 | 0.57 | 0.49 | 0.48 |
| dlev | 0.21 | 0.14 | +0.071 [+0.039,+0.099] | 0.58 | 0.59 | 0.57 | 0.41 |
| near_lev | 0.41 | 0.53 | -0.119 [-0.167,-0.067] | 0.44 | 0.44 | 0.44 | 0.60 |
| near_or | 0.29 | 0.39 | -0.100 [-0.142,-0.055] | 0.45 | 0.46 | 0.44 | 0.60 |
| near_pd | 0.18 | 0.25 | -0.064 [-0.113,-0.020] | 0.47 | 0.45 | 0.49 | 0.55 |
| near_vwap | 0.10 | 0.18 | -0.079 [-0.107,-0.050] | 0.46 | 0.46 | 0.48 | 0.49 |
| near_round | 0.18 | 0.18 | -0.002 [-0.037,+0.032] | 0.50 | 0.50 | 0.51 | 0.48 |
| inside_or | 0.39 | 0.41 | -0.019 [-0.072,+0.033] | 0.49 | 0.48 | 0.50 | 0.54 |
| absbr | 0.42 | 0.38 | +0.039 [-0.034,+0.107] | 0.55 | – | 0.57 | 0.49 |
| absdbr15 | 0.10 | 0.07 | +0.031 [+0.013,+0.051] | 0.60 | – | 0.65 | 0.50 |
| dvix15 | 0.02 | -0.00 | +0.023 [+0.006,+0.039] | 0.50 | 0.49 | 0.52 | 0.49 |
| dvix30 | 0.03 | -0.01 | +0.030 [+0.006,+0.057] | 0.50 | 0.48 | 0.52 | 0.56 |
| absret15 | 0.17 | 0.08 | +0.097 [+0.084,+0.111] | 0.73 | 0.74 | 0.73 | 0.60 |
| nimp | 2.89 | 1.20 | +1.692 [+1.389,+1.964] | 0.75 | 0.74 | 0.68 | 0.55 |


#### Does the pre-onset state predict the impulse direction? (biased - see note)

NOTE: the onset is by construction the most extreme close of the preceding window, so price-derived directional features (ret15, retopen, vwapside, orpos, dvix15) are mechanically tilted against the impulse direction here; use section E (forward, no hindsight) instead. 
Share of impulses whose direction equals the sign of the feature at onset (50 % = no information). 95 % Wilson CI (ALL).

| feature (sign) | ALL | P1 | P2 | P3 |
|---|---|---|---|---|
| ret15 | 21 % [18-24] n=729 | 18 % (n=534) | 23 % (n=133) | 42 % (n=62) |
| retopen | 40 % [37-44] n=729 | 37 % (n=534) | 47 % (n=133) | 50 % (n=62) |
| vwapside | 37 % [34-41] n=729 | 38 % (n=534) | 29 % (n=133) | 45 % (n=62) |
| pcrflow15 | 46 % [42-49] n=728 | 45 % (n=533) | 40 % (n=133) | 68 % (n=62) |
| dbr15 | 39 % [30-49] n=95 | – | 37 % (n=46) | 40 % (n=45) |
| foi15x | 46 % [43-50] n=729 | 47 % (n=534) | 49 % (n=133) | 40 % (n=58) |
| orpos | 36 % [32-41] n=444 | 35 % (n=322) | 35 % (n=81) | 44 % (n=43) |
| dvix15 | 68 % [65-72] n=710 | 70 % (n=523) | 66 % (n=129) | 57 % (n=58) |


### C. Lift on a decision grid (every 5 min, 09:45-14:59)

Outcome y35 = an impulse (0.35 %/30 min) starts within the next 15 minutes; yfwd = the index moves ≥ 0.35 % either way within 30 minutes (no hindsight onset). Quintile thresholds are fixed on P1 (Jan-May) and applied unchanged to P2 and P3. Lift = P(y | condition) / P(y). CI: day-clustered bootstrap. "Strat." = lift after stratifying by hour-of-day × range-so-far tercile (controls for time-of-day and the day's volatility regime).

Base rates: ALL: P(y35)=8.6 %, P(yfwd)=7.4 % (n=24948); P1: P(y35)=12.5 %, P(yfwd)=10.8 % (n=12474); P2: P(y35)=3.8 %, P(yfwd)=2.9 % (n=10458); P3: P(y35)=9.0 %, P(yfwd)=9.8 % (n=2016)


| condition at t | share of grid | lift y35 ALL [CI] | strat. y35 | lift yfwd ALL [CI] | strat. yfwd | P1 y35 | P2 y35 | P3 y35 |
|---|---|---|---|---|---|---|---|---|
| comp15 top quintile (> 1.53) | 19% | 1.93 [1.76-2.10] | 1.59 | 2.03 [1.80-2.24] | 1.62 | 1.87 [1.70-2.07] | 1.81 [1.36-2.33] | 1.34 [0.92-1.72] |
| comp15 bottom quintile (<= 0.686) | 18% | 0.45 [0.36-0.56] | 0.58 | 0.35 [0.27-0.44] | 0.46 | 0.39 [0.30-0.49] | 0.69 [0.48-0.93] | 0.34 [0.00-0.80] |
| comp30 top quintile (> 1.52) | 19% | 1.85 [1.65-2.03] | 1.50 | 2.01 [1.78-2.29] | 1.57 | 1.82 [1.65-2.03] | 1.59 [1.17-2.07] | 1.33 [0.98-1.69] |
| comp30 bottom quintile (<= 0.696) | 19% | 0.39 [0.32-0.48] | 0.52 | 0.36 [0.27-0.46] | 0.49 | 0.30 [0.21-0.38] | 0.67 [0.47-0.89] | 1.03 [0.26-1.94] |
| compday top quintile (> 1.16) | 20% | 1.48 [1.35-1.60] | 1.37 | 1.45 [1.28-1.63] | 1.32 | 1.39 [1.27-1.53] | 1.83 [1.51-2.13] | 1.61 [0.83-2.30] |
| compday bottom quintile (<= 0.642) | 19% | 0.85 [0.76-0.95] | 0.90 | 0.79 [0.67-0.91] | 0.85 | 0.79 [0.68-0.90] | 0.90 [0.63-1.23] | 0.90 [0.60-1.27] |
| rngsofar top quintile (> 1.43) | 21% | 1.49 [1.28-1.71] | 1.06 | 1.59 [1.34-1.87] | 1.06 | 1.60 [1.39-1.85] | 1.12 [0.66-1.79] | 0.90 [0.73-1.10] |
| rngsofar bottom quintile (<= 0.711) | 19% | 0.41 [0.30-0.54] | 0.82 | 0.40 [0.29-0.54] | 0.88 | 0.35 [0.24-0.47] | 0.63 [0.35-0.90] | 1.51 [0.85-3.53] |
| vol15 top quintile (> 1.97) | 20% | 1.49 [1.32-1.66] | 1.31 | 1.48 [1.29-1.69] | 1.25 | 1.52 [1.32-1.75] | 1.46 [1.14-1.85] | 1.43 [0.77-1.97] |
| vol15 bottom quintile (<= 0.521) | 21% | 0.60 [0.49-0.72] | 0.72 | 0.57 [0.47-0.70] | 0.70 | 0.53 [0.42-0.65] | 0.83 [0.61-1.06] | 0.87 [0.48-1.33] |
| vol5 top quintile (> 2.2) | 21% | 1.45 [1.29-1.61] | 1.30 | 1.44 [1.27-1.62] | 1.26 | 1.45 [1.30-1.63] | 1.47 [1.19-1.78] | 1.48 [0.94-1.91] |
| vol5 bottom quintile (<= 0.452) | 21% | 0.59 [0.51-0.68] | 0.68 | 0.63 [0.52-0.75] | 0.75 | 0.51 [0.41-0.60] | 0.90 [0.71-1.09] | 0.81 [0.46-1.21] |
| absfoi15 top quintile (> 0.638) | 16% | 1.28 [1.11-1.47] | 1.16 | 1.25 [1.06-1.45] | 1.13 | 1.08 [0.93-1.26] | 1.51 [1.05-2.03] | 1.69 [0.35-3.05] |
| absfoi15 bottom quintile (<= 0.0634) | 25% | 0.76 [0.67-0.85] | 0.82 | 0.83 [0.73-0.95] | 0.90 | 0.76 [0.64-0.88] | 1.02 [0.77-1.22] | 0.78 [0.56-1.08] |
| strad15 top quintile (> 1.59) | 19% | 1.18 [1.06-1.30] | 1.10 | 1.19 [1.08-1.32] | 1.10 | 1.17 [1.04-1.31] | 1.13 [0.89-1.44] | 1.69 [1.30-2.03] |
| strad15 bottom quintile (<= -1.46) | 19% | 1.28 [1.15-1.43] | 1.20 | 1.30 [1.13-1.46] | 1.21 | 1.17 [1.03-1.30] | 1.39 [1.06-1.71] | 0.68 [0.46-1.03] |
| optoi15 top quintile (> 6.2) | 17% | 1.58 [1.43-1.74] | 1.30 | 1.46 [1.31-1.63] | 1.23 | 1.52 [1.36-1.72] | 1.24 [0.88-1.66] | 1.08 [0.69-1.48] |
| optoi15 bottom quintile (<= -0.195) | 21% | 0.97 [0.87-1.08] | 0.95 | 1.06 [0.94-1.19] | 1.00 | 0.86 [0.73-0.99] | 1.43 [1.19-1.72] | 1.21 [0.73-1.77] |
| dlev top quintile (> 0.259) | 17% | 1.53 [1.30-1.76] | 1.16 | 1.41 [1.18-1.67] | 1.01 | 1.33 [1.14-1.55] | 2.32 [1.58-3.23] | 0.79 [0.42-1.19] |
| dlev bottom quintile (<= 0.0377) | 23% | 0.88 [0.80-0.98] | 1.01 | 0.81 [0.71-0.92] | 0.96 | 0.88 [0.76-0.99] | 1.06 [0.86-1.31] | 1.59 [1.04-2.56] |
| absbr top quintile (> 0.567) | 23% | 1.35 [0.90-1.84] | 1.16 | 1.33 [0.89-1.79] | 1.14 | – | 1.27 [0.52-2.09] | 1.03 [0.54-1.51] |
| absbr bottom quintile (<= 0.133) | 20% | 1.05 [0.73-1.45] | 1.29 | 0.88 [0.52-1.32] | 1.10 | – | 1.12 [0.66-1.71] | 0.89 [0.50-1.57] |
| absdbr15 top quintile (> 0.12) | 19% | 1.27 [1.05-1.54] | 1.31 | 1.10 [0.85-1.39] | 1.14 | – | 1.36 [1.02-1.71] | 1.17 [0.91-1.46] |
| absdbr15 bottom quintile (<= 0) | 24% | 0.88 [0.66-1.08] | 0.82 | 0.97 [0.80-1.12] | 0.89 | – | 0.73 [0.50-1.02] | 1.03 [0.76-1.33] |
| dvix15 top quintile (> 0.08) | 16% | 1.59 [1.44-1.78] | 1.45 | 1.58 [1.42-1.77] | 1.44 | 1.38 [1.23-1.52] | 1.48 [1.10-1.94] | 1.67 [1.18-2.14] |
| dvix15 bottom quintile (<= -0.09) | 17% | 1.43 [1.28-1.58] | 1.32 | 1.55 [1.39-1.71] | 1.42 | 1.28 [1.12-1.44] | 1.43 [1.05-1.80] | 1.09 [0.84-1.41] |
| absret15 top quintile (> 0.153) | 15% | 2.35 [2.15-2.55] | 1.97 | 2.24 [2.02-2.45] | 1.85 | 1.90 [1.77-2.06] | 2.74 [1.90-3.55] | 1.81 [1.26-2.37] |
| absret15 bottom quintile (<= 0.0258) | 24% | 0.67 [0.61-0.75] | 0.72 | 0.63 [0.56-0.70] | 0.68 | 0.73 [0.63-0.82] | 0.72 [0.58-0.91] | 0.95 [0.61-1.27] |
| near_lev = 1 | 51% | 0.90 [0.84-0.97] | 1.02 | 0.86 [0.80-0.93] | 1.00 | 0.93 [0.84-1.01] | 0.95 [0.83-1.08] | 1.63 [1.17-2.38] |
| near_or = 1 | 37% | 0.84 [0.77-0.91] | 1.01 | 0.78 [0.70-0.85] | 0.97 | 0.88 [0.77-0.99] | 0.90 [0.77-1.06] | 1.65 [1.10-2.50] |
| near_pd = 1 | 24% | 0.90 [0.80-1.02] | 0.97 | 0.87 [0.76-1.01] | 0.96 | 0.86 [0.73-0.99] | 1.06 [0.80-1.31] | 1.54 [1.01-2.46] |
| near_vwap = 1 | 18% | 0.65 [0.55-0.78] | 0.80 | 0.74 [0.62-0.89] | 0.94 | 0.59 [0.48-0.73] | 0.97 [0.74-1.23] | 1.41 [0.75-2.29] |
| near_round = 1 | 18% | 1.05 [0.92-1.16] | 1.05 | 0.99 [0.87-1.12] | 1.00 | 1.01 [0.89-1.13] | 1.21 [0.92-1.52] | 0.64 [0.38-0.99] |
| inside_or = 1 | 42% | 0.97 [0.88-1.08] | 1.11 | 1.01 [0.91-1.10] | 1.19 | 0.98 [0.87-1.09] | 0.85 [0.68-1.01] | 1.34 [0.88-2.14] |
| expiry = 1 | 21% | 1.17 [0.95-1.42] | 1.17 | 1.04 [0.81-1.34] | 1.04 | 1.14 [0.88-1.43] | 1.27 [0.78-1.72] | 1.11 [0.42-1.85] |
| nimp >= 1 (an impulse already today) | 60% | 1.46 [1.36-1.56] | 1.30 | 1.46 [1.37-1.57] | 1.27 | 1.21 [1.14-1.30] | 1.81 [1.50-2.17] | 1.03 [0.94-1.16] |
| 09:45-10:59 | 24% | 1.09 [0.97-1.21] | 1.00 | 0.99 [0.87-1.13] | 0.99 | 1.20 [1.05-1.34] | 0.75 [0.47-1.01] | 0.90 [0.52-1.34] |
| 13:30-14:59 | 29% | 1.23 [1.12-1.33] | 1.01 | 1.30 [1.15-1.43] | 1.01 | 1.18 [1.07-1.29] | 1.65 [1.39-1.91] | 0.83 [0.47-1.12] |
| compression + volume: comp15 bottom quintile & vol5 top quintile | 1% | 0.41 [0.17-0.71] | 0.49 | 0.74 [0.32-1.22] | 0.92 | 0.46 [0.13-0.82] | 0.56 [0.00-1.34] | 0.00 [0.00-0.00] |
| compression + level: comp30 bottom quintile & near_lev | 11% | 0.37 [0.26-0.49] | 0.56 | 0.34 [0.23-0.48] | 0.56 | 0.26 [0.17-0.36] | 0.72 [0.42-1.03] | 4.92 [0.00-9.11] |
| expiry & after 13:00 | 8% | 1.33 [1.01-1.65] | 1.15 | 1.27 [0.88-1.68] | 1.05 | 1.24 [0.87-1.60] | 1.76 [1.22-2.41] | 1.05 [0.00-2.40] |


### D. After a thrust has started: does it continue? (entry-after-confirmation economics)

Trigger: close moves ≥ X % away from the lowest (highest) close of the last 10 minutes, outside 09:15-09:29 and after 15:00 excluded. Non-overlapping (next trigger only after 15 min). Forward return measured in the trigger direction from the trigger close. Control = same statistics for a random direction at random grid minutes (≈0).

| set | X % | n | mean fwd 15m % [CI] | mean fwd 30m % | fwd15 > 0 % | median MFE30 % | median MAE30 % | reaches 0.35 % from the 10-min extreme % |
|---|---|---|---|---|---|---|---|---|
| ALL | 0.10 | 5113 | +0.002 [-0.001,+0.006] | +0.005 | 50 | 0.08 | 0.09 | 20 |
| ALL | 0.15 | 2800 | -0.003 [-0.010,+0.003] | +0.001 | 49 | 0.10 | 0.10 | 32 |
| ALL | 0.20 | 1507 | -0.000 [-0.010,+0.008] | -0.001 | 48 | 0.10 | 0.12 | 48 |
| ALL | 0.25 | 814 | -0.007 [-0.022,+0.007] | +0.001 | 47 | 0.11 | 0.14 | 63 |
| ALL | 0.35 | 261 | -0.013 [-0.039,+0.010] | -0.009 | 45 | 0.15 | 0.16 | 92 |
| P1 | 0.10 | 3072 | +0.003 [-0.003,+0.009] | +0.007 | 50 | 0.10 | 0.10 | 24 |
| P1 | 0.15 | 1908 | -0.005 [-0.013,+0.003] | +0.000 | 48 | 0.11 | 0.11 | 35 |
| P1 | 0.20 | 1086 | -0.001 [-0.012,+0.011] | -0.001 | 48 | 0.12 | 0.13 | 50 |
| P1 | 0.25 | 593 | -0.008 [-0.026,+0.013] | +0.005 | 48 | 0.12 | 0.16 | 64 |
| P1 | 0.35 | 187 | -0.012 [-0.039,+0.016] | -0.007 | 46 | 0.17 | 0.16 | 91 |
| P2 | 0.10 | 1541 | -0.001 [-0.007,+0.004] | -0.003 | 48 | 0.06 | 0.07 | 10 |
| P2 | 0.15 | 611 | -0.006 [-0.017,+0.005] | -0.007 | 47 | 0.08 | 0.08 | 23 |
| P2 | 0.20 | 264 | -0.008 [-0.026,+0.009] | -0.012 | 48 | 0.07 | 0.10 | 39 |
| P2 | 0.25 | 130 | -0.023 [-0.049,+0.008] | -0.027 | 41 | 0.07 | 0.13 | 58 |
| P2 | 0.35 | 45 | -0.032 [-0.104,+0.038] | -0.026 | 38 | 0.05 | 0.19 | 91 |
| P3 | 0.10 | 501 | +0.010 [+0.000,+0.020] | +0.020 | 51 | 0.09 | 0.08 | 21 |
| P3 | 0.15 | 282 | +0.014 [+0.005,+0.023] | +0.017 | 55 | 0.10 | 0.09 | 34 |
| P3 | 0.20 | 157 | +0.018 [-0.003,+0.042] | +0.017 | 50 | 0.10 | 0.10 | 48 |
| P3 | 0.25 | 91 | +0.023 [-0.002,+0.051] | +0.019 | 51 | 0.13 | 0.10 | 66 |
| P3 | 0.35 | 29 | +0.006 [-0.035,+0.061] | +0.007 | 48 | 0.19 | 0.10 | 97 |


### E. Direction: does the state at t predict the sign of the next 30 minutes? (grid, no hindsight)

For each directional feature: mean of sign(feature)·ret30 in % (index points of edge per decision, day-clustered CI), and among grid points followed by a ≥0.35 % move (yfwd), the share whose larger excursion is in the sign of the feature.

| feature | ALL | P1 | P2 | P3 |
|---|---|---|---|---|
| ret15 | +0.001 [-0.001,+0.004]; 53 % of 1855 | +0.000 [-0.003,+0.004]; 52 % of 1350 | -0.001 [-0.004,+0.003]; 50 % of 307 | +0.017 [+0.010,+0.027]; 65 % of 197 |
| retopen | +0.001 [-0.004,+0.005]; 48 % of 1854 | -0.003 [-0.010,+0.004]; 46 % of 1349 | +0.002 [-0.003,+0.007]; 52 % of 307 | +0.015 [-0.006,+0.033]; 50 % of 198 |
| vwapside | +0.005 [+0.001,+0.009]; 52 % of 1855 | +0.007 [+0.000,+0.014]; 53 % of 1350 | -0.002 [-0.007,+0.004]; 48 % of 307 | +0.021 [+0.001,+0.038]; 56 % of 198 |
| orpos | +0.007 [+0.000,+0.012]; 51 % of 1066 | +0.007 [-0.002,+0.016]; 52 % of 757 | +0.002 [-0.006,+0.009]; 46 % of 193 | +0.026 [+0.008,+0.044]; 58 % of 121 |
| pcrflow15 | +0.002 [-0.001,+0.006]; 51 % of 1854 | +0.002 [-0.003,+0.007]; 51 % of 1349 | -0.000 [-0.004,+0.003]; 43 % of 307 | +0.016 [+0.004,+0.027]; 61 % of 198 |
| foi15x | +0.003 [+0.000,+0.006]; 52 % of 1855 | +0.004 [-0.000,+0.008]; 52 % of 1350 | +0.002 [-0.001,+0.004]; 57 % of 307 | +0.007 [-0.003,+0.017]; 52 % of 180 |
| dbr15 | +0.002 [-0.001,+0.006]; 58 % of 259 | – | +0.001 [-0.003,+0.005]; 53 % of 113 | +0.009 [-0.002,+0.025]; 62 % of 136 |
| dvix15 | -0.000 [-0.003,+0.003]; 48 % of 1798 | +0.002 [-0.002,+0.007]; 50 % of 1314 | -0.001 [-0.006,+0.002]; 48 % of 293 | -0.009 [-0.019,+0.001]; 37 % of 191 |


Conditional versions (ALL set):

| condition | set | n | mean signed ret30 % [CI] | hit % |
|---|---|---|---|---|
| |ret15| > 0.15 % (follow) | ALL | 3788 | +0.002 [-0.007,+0.010] | 49 |
| |ret15| > 0.15 % (follow) | P1 | 2579 | -0.001 [-0.010,+0.009] | 48 |
| |ret15| > 0.15 % (follow) | P2 | 837 | -0.007 [-0.025,+0.009] | 47 |
| |ret15| > 0.15 % (follow) | P3 | 371 | +0.039 [+0.012,+0.067] | 58 |
| |ret15| > 0.25 % (follow) | ALL | 1117 | +0.007 [-0.010,+0.025] | 50 |
| |ret15| > 0.25 % (follow) | P1 | 826 | -0.003 [-0.023,+0.015] | 49 |
| |ret15| > 0.25 % (follow) | P2 | 166 | +0.019 [-0.032,+0.078] | 50 |
| |ret15| > 0.25 % (follow) | P3 | 127 | +0.063 [+0.022,+0.096] | 61 |
| |retopen| > 0.5 % (follow day trend) | ALL | 7433 | +0.006 [-0.003,+0.015] | 53 |
| |retopen| > 0.5 % (follow day trend) | P1 | 4186 | +0.005 [-0.010,+0.017] | 52 |
| |retopen| > 0.5 % (follow day trend) | P2 | 2150 | -0.005 [-0.019,+0.009] | 48 |
| |retopen| > 0.5 % (follow day trend) | P3 | 1094 | +0.034 [+0.010,+0.050] | 62 |
| vol5 top quintile & |ret15|>0.1 (follow) | ALL | 2680 | +0.014 [+0.005,+0.025] | 52 |
| vol5 top quintile & |ret15|>0.1 (follow) | P1 | 1555 | +0.015 [+0.002,+0.027] | 51 |
| vol5 top quintile & |ret15|>0.1 (follow) | P2 | 811 | +0.004 [-0.012,+0.022] | 50 |
| vol5 top quintile & |ret15|>0.1 (follow) | P3 | 289 | +0.049 [+0.014,+0.081] | 62 |
| |pcrflow15| > 3 (follow put-writing) | ALL | 14136 | +0.006 [+0.001,+0.011] | 51 |
| |pcrflow15| > 3 (follow put-writing) | P1 | 7512 | +0.006 [-0.001,+0.012] | 51 |
| |pcrflow15| > 3 (follow put-writing) | P2 | 5488 | +0.002 [-0.003,+0.007] | 51 |
| |pcrflow15| > 3 (follow put-writing) | P3 | 1131 | +0.025 [+0.006,+0.042] | 55 |
| |dbr15| > 0.2 (follow breadth) | ALL | 393 | +0.003 [-0.015,+0.021] | 51 |
| |dbr15| > 0.2 (follow breadth) | P2 | 281 | -0.014 [-0.030,+0.003] | 45 |
| |dbr15| > 0.2 (follow breadth) | P3 | 104 | +0.041 [-0.004,+0.087] | 61 |
| |dvix15| > 0.15 (fade VIX: sign = -dvix) | ALL | 3622 | -0.004 [-0.013,+0.008] | 48 |
| |dvix15| > 0.15 (fade VIX: sign = -dvix) | P1 | 2404 | -0.006 [-0.018,+0.006] | 47 |
| |dvix15| > 0.15 (fade VIX: sign = -dvix) | P2 | 874 | -0.010 [-0.025,+0.006] | 49 |
| |dvix15| > 0.15 (fade VIX: sign = -dvix) | P3 | 344 | +0.023 [-0.005,+0.051] | 53 |



#### Impulse rate by India VIX at 09:15 (ALL set)

| VIX at open | index-days | 0.35 %/30m per day | same, onset ≥ 09:45 | 0.25 %/15m per day | median day range % |
|---|---|---|---|---|---|
| <12 | 90 | 0.66 | 0.54 | 1.11 | 0.65 |
| 12-14 | 124 | 1.52 | 1.06 | 2.59 | 0.73 |
| 14-17.5 | 82 | 3.23 | 2.41 | 5.76 | 1.11 |
| ≥17.5 | 100 | 5.08 | 3.85 | 8.70 | 1.26 |


### F. Are the precursors priced? ATM straddle return over the next 15 / 30 minutes (bars days, before costs)

Fixed-strike ATM straddle bought at the bar close of t. Quintile cut-offs as in section C (fixed on P1). Mean % of premium, day-clustered CI. A precursor is only useful to an option buyer if the straddle (or the right leg) gains more than the 0.5-1 % round-trip cost.

| condition at t | n | straddle 15 min % | straddle 30 min % | momentum leg 15 min % |
|---|---|---|---|---|
| all grid points | 23688 | -0.17 [-0.32,+0.01] | -0.27 [-0.56,+0.13] | -0.01 [-0.34,+0.44] |
| comp15 top quintile | 4250 | +0.22 [-0.19,+0.65] | +0.34 [-0.31,+1.05] | +0.45 [-0.82,+1.81] |
| vol5 top quintile | 4865 | +0.04 [-0.28,+0.38] | -0.24 [-0.91,+0.42] | +0.60 [-0.42,+1.69] |
| dvix15 top quintile | 3780 | +0.09 [-0.23,+0.45] | -0.07 [-0.67,+0.58] | +0.16 [-0.91,+1.37] |
| optoi15 top quintile | 3974 | -0.95 [-1.30,-0.57] | -1.70 [-2.44,-0.97] | -1.04 [-2.09,+0.11] |
| absret15 top quintile | 3418 | -0.15 [-0.50,+0.11] | -0.53 [-1.17,+0.03] | -0.24 [-1.26,+0.80] |
| strad15 top quintile | 4592 | -0.19 [-0.55,+0.22] | -0.47 [-1.13,+0.33] | +0.19 [-0.85,+1.36] |
| comp15 bottom quintile | 4486 | -0.44 [-0.62,-0.25] | -0.60 [-1.04,-0.19] | -0.53 [-1.12,-0.02] |
| comp15 top & vol5 top | 2138 | +0.19 [-0.41,+0.70] | +0.15 [-0.70,+1.01] | +0.93 [-0.81,+2.71] |
| expiry days | 5040 | -0.77 [-1.34,-0.05] | -1.26 [-2.62,+0.40] | -0.47 [-1.91,+1.15] |
| non-expiry days | 18648 | -0.00 [-0.06,+0.06] | +0.00 [-0.14,+0.13] | +0.11 [-0.13,+0.36] |
| expiry & comp15 top | 1127 | +0.02 [-1.45,+1.57] | +0.06 [-2.37,+2.68] | +0.68 [-3.59,+5.15] |


### G. Option P&L inside impulses (0.35 %/30 min, bars days): what is left for a late entry?

ATM option in the impulse direction. Entry at onset (perfect foresight, upper bound), at the threshold crossing (confirmation; the earliest non-hindsight entry for this definition), and 5 minutes after it. Exit at the impulse peak (hindsight) or a fixed 15 minutes after entry.

| days | n impulses (09:30-15:00) | onset→peak % | onset→+15m % | confirm→peak % | confirm→+15m % | confirm+5→+20m % |
|---|---|---|---|---|---|---|
| all | 738 | +70.55 [+62.28,+79.37] | +35.55 [+31.44,+40.12] | +10.58 [+8.48,+13.48] | +0.30 [-2.15,+3.15] | -0.91 [-3.83,+1.96] |
| expiry days | 179 | +161.80 [+140.84,+182.14] | +81.15 [+69.87,+92.65] | +20.61 [+13.65,+27.13] | -1.72 [-10.21,+6.70] | -6.48 [-14.79,+1.60] |
| non-expiry | 559 | +41.33 [+38.14,+44.66] | +20.95 [+19.28,+22.74] | +7.37 [+5.71,+8.96] | +0.94 [-0.44,+2.45] | +0.87 [-0.83,+2.44] |

#### Straddle 15-min drift by period for the notable conditions

| condition | period | n | straddle 15 min % [CI] |
|---|---|---|---|
| all grid points | P1 | 12474 | -0.24 [-0.45,-0.01] |
| all grid points | P2+28 Sep-6 Oct | 11214 | -0.08 [-0.27,+0.14] |
| optoi15 top quintile | P1 | 2495 | -1.24 [-1.81,-0.69] |
| optoi15 top quintile | P2+28 Sep-6 Oct | 1479 | -0.45 [-0.94,-0.00] |
| comp15 bottom quintile | P1 | 2495 | -0.46 [-0.81,-0.17] |
| comp15 bottom quintile | P2+28 Sep-6 Oct | 1991 | -0.42 [-0.67,-0.20] |
| comp15 top quintile | P1 | 2495 | +0.20 [-0.42,+0.78] |
| comp15 top quintile | P2+28 Sep-6 Oct | 1755 | +0.24 [-0.27,+0.74] |
| optoi15 top & non-expiry | P1 | 1509 | -0.08 [-0.26,+0.08] |
| optoi15 top & non-expiry | P2+28 Sep-6 Oct | 991 | -0.17 [-0.41,+0.06] |


**Reading.** 0.65 of the 2.58 daily impulses start 09:15–09:44; 14:15–15:00 is the second cluster (0.49/day, 0.65 on expiry days). Precursors of whether a move comes are activity measures (recent range, |return|, futures volume, VIX moving, near-ATM OI build-up, an earlier impulse), consistent in P1 and P2, weaker in P3; compression is an anti-precursor. Level proximity and round numbers carry nothing in Jan–Sep. Direction (E) is absent in Jan–Sep; October shows momentum (follow |ret15| > 0.25 %: +0.063 %/30 min, 61 %; follow day trend > 0.5 %: +0.034 %, 62 %) — regime-specific. The straddle test (F): "likely" states don't make a long straddle profitable; compressed states lose −0.44 %/15 min in both P1 and P2+; heavy near-ATM OI build-up −1.0 %/15 min, almost entirely on expiry days. G: the right ATM option gains +36 % in the 15 min after onset (+81 % expiry), but entering at the 0.35 % confirmation gets +0.3 %.

## 4. Time-of-day profile, the opening, and 0DTE afternoons (task 3)

### A. 15-minute slot profile (ALL set, 396 index-days)

|5m| = mean absolute 5-minute close-to-close return (bp; first slot measured from the 09:15 open). Persist = P(sign of this 15-min slot return = sign of previous slot). ρ = correlation of consecutive 15-min slot returns. Reversal = P(opposite sign and |this| ≥ 0.5·|prev|, given |prev| ≥ 0.10 %). Expiry = the index whose weekly options expire that day.

| slot | |5m| bp non-exp | |5m| bp expiry | persist % | ρ(prev,this) | reversal % | |15m| ≥ 0.25 % (% of days) |
|---|---|---|---|---|---|---|
| 09:15 | 12.28 | 12.31 | – | – | – | 39 |
| 09:30 | 7.40 | 7.71 | 52 | +0.03 | 18 (n=275) | 13 |
| 09:45 | 5.90 | 6.11 | 51 | -0.17 | 27 (n=181) | 8 |
| 10:00 | 5.46 | 5.57 | 52 | -0.07 | 31 (n=177) | 5 |
| 10:15 | 5.39 | 5.40 | 49 | -0.04 | 28 (n=165) | 5 |
| 10:30 | 4.75 | 5.12 | 51 | -0.04 | 23 (n=142) | 3 |
| 10:45 | 4.59 | 4.91 | 48 | -0.10 | 28 (n=115) | 4 |
| 11:00 | 4.42 | 4.28 | 43 | -0.15 | 31 (n=122) | 3 |
| 11:15 | 4.45 | 5.18 | 48 | +0.00 | 26 (n=109) | 5 |
| 11:30 | 4.33 | 4.55 | 46 | +0.06 | 14 (n=118) | 3 |
| 11:45 | 3.94 | 4.40 | 54 | +0.02 | 22 (n=94) | 2 |
| 12:00 | 4.49 | 4.50 | 53 | -0.02 | 29 (n=106) | 3 |
| 12:15 | 4.46 | 4.60 | 48 | +0.05 | 18 (n=114) | 4 |
| 12:30 | 4.49 | 4.59 | 52 | -0.07 | 27 (n=99) | 5 |
| 12:45 | 4.29 | 4.58 | 49 | -0.02 | 23 (n=111) | 5 |
| 13:00 | 4.33 | 4.39 | 46 | -0.00 | 22 (n=103) | 3 |
| 13:15 | 4.25 | 4.41 | 48 | -0.11 | 15 (n=89) | 3 |
| 13:30 | 4.64 | 5.19 | 49 | +0.10 | 30 (n=97) | 6 |
| 13:45 | 4.43 | 4.40 | 45 | -0.02 | 17 (n=101) | 2 |
| 14:00 | 4.73 | 5.50 | 47 | +0.01 | 25 (n=121) | 4 |
| 14:15 | 4.83 | 4.99 | 49 | -0.01 | 20 (n=123) | 3 |
| 14:30 | 4.95 | 5.82 | 47 | +0.12 | 23 (n=104) | 6 |
| 14:45 | 4.63 | 5.22 | 56 | +0.09 | 15 (n=145) | 5 |
| 15:00 | 6.30 | 6.75 | 46 | -0.01 | 28 (n=108) | 10 |
| 15:15 | 4.40 | 6.18 | 46 | -0.10 | 25 (n=183) | 9 |


Persistence / ρ of consecutive 15-min returns pooled over 09:45-15:15 by period:

| period | pairs | persist % | ρ |
|---|---|---|---|
| P1 | 4554 | 48.5 | -0.052 |
| P2 | 3818 | 49.0 | +0.005 |
| P3 | 736 | 51.2 | +0.039 |


### B. What the first 15 / 30 minutes say about the rest of the day


#### First 15 minutes (open → close of 09:29)

ROD = rest of day (close of the first window → 15:29 close). "Same dir" uses the sign of the first-window return. "Opp. extreme set" = the day extreme opposite to the first-window direction was already printed inside the first window. Columns P1 / P2+P3 = same-direction share by period (out-of-sample check).

| first-window return | n | ROD same dir % [CI] | mean ROD in first dir % | ROD ≥ +0.3 % same dir % | ROD ≥ 0.3 % against % | opp. extreme set in window % | median ROD range % | same dir P1 | same dir P2+P3 |
|---|---|---|---|---|---|---|---|---|---|
| ≤ -0.5 % | 32 | 41 [26-58] | -0.33 | 31 | 50 | 50 | 1.26 | 38 (n=21) | 45 (n=11) |
| -0.5..-0.2 % | 77 | 56 [45-66] | +0.15 | 34 | 21 | 66 | 0.82 | 55 (n=38) | 56 (n=39) |
| -0.2..+0.2 % | 204 | 53 [46-60] | +0.01 | 26 | 28 | 33 | 0.75 | 54 (n=91) | 52 (n=113) |
| +0.2..+0.5 % | 70 | 54 [43-65] | +0.10 | 37 | 29 | 53 | 0.87 | 50 (n=36) | 59 (n=34) |
| ≥ +0.5 % | 13 | 8 [1-33] | -0.37 | 8 | 54 | 31 | 1.19 | 8 (n=12) | 0 (n=1) |


#### First 30 minutes (open → close of 09:44)

ROD = rest of day (close of the first window → 15:29 close). "Same dir" uses the sign of the first-window return. "Opp. extreme set" = the day extreme opposite to the first-window direction was already printed inside the first window. Columns P1 / P2+P3 = same-direction share by period (out-of-sample check).

| first-window return | n | ROD same dir % [CI] | mean ROD in first dir % | ROD ≥ +0.3 % same dir % | ROD ≥ 0.3 % against % | opp. extreme set in window % | median ROD range % | same dir P1 | same dir P2+P3 |
|---|---|---|---|---|---|---|---|---|---|
| ≤ -0.5 % | 39 | 49 [34-64] | -0.21 | 28 | 44 | 64 | 1.03 | 46 (n=24) | 53 (n=15) |
| -0.5..-0.2 % | 82 | 56 [45-66] | +0.10 | 28 | 20 | 65 | 0.82 | 55 (n=47) | 57 (n=35) |
| -0.2..+0.2 % | 178 | 52 [44-59] | +0.02 | 25 | 26 | 38 | 0.67 | 57 (n=72) | 48 (n=106) |
| +0.2..+0.5 % | 77 | 49 [38-60] | -0.02 | 30 | 29 | 55 | 0.85 | 48 (n=42) | 51 (n=35) |
| ≥ +0.5 % | 20 | 50 [30-70] | +0.01 | 40 | 35 | 55 | 1.05 | 38 (n=13) | 71 (n=7) |


#### Gap direction × first-30-minute direction (|gap| ≥ 0.2 %)

| case | n | ROD continues first-30 dir % | mean ROD in first-30 dir % | gap NOT filled all day % |
|---|---|---|---|---|
| gap up, first 30 up | 54 | 63 | +0.04 | 74 |
| gap up, first 30 down | 71 | 51 | -0.03 | 42 |
| gap down, first 30 up | 54 | 37 | -0.11 | 52 |
| gap down, first 30 down | 66 | 50 | -0.05 | 82 |


#### First break of the 15-minute opening range (09:15-09:29), from 09:30

| first break | n | close beyond the broken side % | other side also broken later % | mean break→close in break dir % | break→close positive % |
|---|---|---|---|---|---|
| all | 394 | 55 | 35 | +0.003 | 54 |
| break before 10:00 | 293 | 56 | 37 | +0.037 | 55 |
| break 10:00-11:30 | 81 | 49 | 36 | -0.132 | 48 |
| break after 11:30 | 20 | 70 | 5 | +0.050 | 65 |


Days with no close outside the opening range all day: 1 %.


### C. Expiry-day (0DTE) afternoons vs other days of the same index

Straddle = ATM straddle of the nearest weekly expiry (on expiry days that is the 0DTE straddle). "Buyer wins at close" = |index move from t to 15:29| > straddle price at t (ignores the strike offset; approximates an ATM straddle bought at t and held to the close); "touch" = the max excursion after t exceeded the straddle price at some point.

| from | index | day | n | median straddle % of spot | median |move to close| % | buyer wins at close % | touch % | mean |move|/straddle | median straddle change to 15:27 % |
|---|---|---|---|---|---|---|---|---|---|
| 11:00 | NIFTY | expiry | 42 | 0.414 | 0.363 | 43 | 79 | 1.11 | -77 |
| 11:00 | NIFTY | other | 156 | 1.097 | 0.291 | 6 | 10 | 0.36 | -7 |
| 11:00 | SENSEX | expiry | 42 | 0.421 | 0.371 | 50 | 79 | 1.11 | -81 |
| 11:00 | SENSEX | other | 156 | 1.079 | 0.326 | 4 | 10 | 0.38 | -7 |
| 12:00 | NIFTY | expiry | 42 | 0.367 | 0.305 | 33 | 67 | 1.02 | -74 |
| 12:00 | NIFTY | other | 156 | 1.076 | 0.287 | 3 | 6 | 0.32 | -6 |
| 12:00 | SENSEX | expiry | 42 | 0.376 | 0.345 | 38 | 69 | 1.03 | -80 |
| 12:00 | SENSEX | other | 156 | 1.077 | 0.288 | 3 | 8 | 0.33 | -5 |
| 13:00 | NIFTY | expiry | 42 | 0.324 | 0.237 | 26 | 60 | 0.94 | -71 |
| 13:00 | NIFTY | other | 156 | 1.076 | 0.224 | 2 | 4 | 0.27 | -5 |
| 13:00 | SENSEX | expiry | 42 | 0.343 | 0.248 | 31 | 67 | 0.87 | -76 |
| 13:00 | SENSEX | other | 156 | 1.063 | 0.224 | 2 | 3 | 0.28 | -5 |
| 14:00 | NIFTY | expiry | 42 | 0.267 | 0.186 | 31 | 52 | 0.94 | -65 |
| 14:00 | NIFTY | other | 156 | 1.059 | 0.174 | 1 | 3 | 0.21 | -4 |
| 14:00 | SENSEX | expiry | 42 | 0.284 | 0.159 | 36 | 69 | 0.81 | -72 |
| 14:00 | SENSEX | other | 156 | 1.040 | 0.178 | 1 | 1 | 0.22 | -3 |
| 14:30 | NIFTY | expiry | 42 | 0.212 | 0.180 | 45 | 67 | 1.05 | -56 |
| 14:30 | NIFTY | other | 156 | 1.046 | 0.146 | 1 | 1 | 0.18 | -3 |
| 14:30 | SENSEX | expiry | 42 | 0.220 | 0.139 | 33 | 69 | 0.92 | -66 |
| 14:30 | SENSEX | other | 156 | 1.039 | 0.139 | 0 | 1 | 0.18 | -3 |


#### Afternoon activity (13:00-15:29): expiry vs non-expiry, same index

| index | day | n | median 13:00-close range % | mean |5m| bp | 0.25 %/15m impulses per afternoon | afternoon efficiency | median 15:00-15:29 range % |
|---|---|---|---|---|---|---|---|
| NIFTY | expiry | 42 | 0.51 | 4.8 | 1.62 | 0.52 | 0.31 |
| NIFTY | other | 156 | 0.49 | 4.7 | 1.46 | 0.51 | 0.24 |
| SENSEX | expiry | 42 | 0.59 | 5.5 | 2.14 | 0.42 | 0.32 |
| SENSEX | other | 156 | 0.48 | 4.7 | 1.40 | 0.54 | 0.24 |


### D. 0DTE option returns from actual option bars (fixed strike, bar closes, before costs)

Straddle: buy ATM CE+PE at the bar close of t, value at the 15:25 close (and at 15:29). Momentum leg: buy the ATM CE if the index rose over the previous 15 minutes, else the ATM PE; fade leg = the opposite option. Costs of 0.5-1 % of premium round trip are NOT deducted; subtract them from the means. CI: bootstrap over days. Non-expiry rows use the nearest weekly expiry (1-6 days to expiry).

| buy at | index | day | n | straddle → 15:25 mean % [CI] | median % | win % | straddle → 15:29 mean % | median best-exit % | momentum leg mean % | fade leg mean % |
|---|---|---|---|---|---|---|---|---|---|---|
| 09:45 | NIFTY | expiry | 40 | -6.4 [-30.6,+18.7] | -33 | 35 | -7.8 [-32.9,+18.3] | +28 | -19.2 [-58.4,+29.9] | +7.6 [-37.8,+58.5] |
| 09:45 | NIFTY | other | 148 | -0.3 [-2.8,+2.5] | -5 | 32 | -1.3 [-3.8,+1.5] | +6 | -0.7 [-8.1,+7.3] | +0.6 [-6.4,+8.1] |
| 09:45 | SENSEX | expiry | 40 | -9.7 [-33.0,+17.3] | -30 | 30 | -6.7 [-30.2,+21.4] | +26 | -3.3 [-44.9,+49.7] | -13.1 [-53.5,+28.4] |
| 09:45 | SENSEX | other | 148 | +0.4 [-2.4,+3.6] | -4 | 38 | -0.5 [-3.4,+2.6] | +8 | -3.8 [-11.6,+4.0] | +5.5 [-3.7,+16.2] |
| 11:00 | NIFTY | expiry | 40 | +7.5 [-17.7,+34.6] | -16 | 35 | +7.0 [-18.3,+34.9] | +42 | +1.1 [-46.9,+58.0] | +10.5 [-30.7,+55.6] |
| 11:00 | NIFTY | other | 148 | +1.5 [-1.1,+4.3] | -3 | 36 | +0.4 [-2.1,+3.1] | +6 | -3.6 [-10.7,+4.5] | +7.3 [-2.4,+16.5] |
| 11:00 | SENSEX | expiry | 40 | +3.8 [-19.2,+28.4] | -13 | 45 | +6.8 [-17.5,+32.2] | +41 | +21.0 [-36.0,+88.4] | +0.1 [-36.4,+42.7] |
| 11:00 | SENSEX | other | 148 | +2.8 [-0.0,+7.0] | -2 | 47 | +1.8 [-1.0,+5.9] | +7 | -5.3 [-13.3,+3.3] | +11.2 [+2.5,+21.4] |
| 12:00 | NIFTY | expiry | 40 | -4.1 [-24.9,+21.4] | -36 | 32 | -3.2 [-25.1,+23.2] | +22 | +1.7 [-34.7,+42.0] | -13.8 [-59.0,+41.3] |
| 12:00 | NIFTY | other | 148 | +0.7 [-1.4,+3.4] | -2 | 39 | -0.5 [-2.6,+2.1] | +5 | +5.9 [-2.5,+14.9] | -3.7 [-9.5,+2.0] |
| 12:00 | SENSEX | expiry | 40 | +3.6 [-19.5,+25.3] | -7 | 45 | +6.2 [-17.2,+28.0] | +47 | +38.2 [-19.0,+102.8] | -17.4 [-53.6,+22.6] |
| 12:00 | SENSEX | other | 148 | +2.5 [-0.4,+6.1] | -2 | 41 | +1.6 [-1.1,+5.3] | +5 | +6.0 [-2.8,+16.4] | -2.2 [-9.4,+5.0] |
| 13:00 | NIFTY | expiry | 40 | -13.3 [-33.8,+12.1] | -37 | 30 | -11.9 [-34.7,+13.8] | +30 | -0.8 [-35.9,+40.5] | -24.8 [-60.7,+16.1] |
| 13:00 | NIFTY | other | 148 | -0.3 [-1.9,+1.5] | -2 | 37 | -1.5 [-3.2,+0.4] | +3 | -5.5 [-11.0,+0.4] | +5.2 [-1.1,+11.5] |
| 13:00 | SENSEX | expiry | 40 | -10.0 [-29.6,+8.2] | -28 | 25 | -6.8 [-26.8,+13.2] | +27 | -31.0 [-61.6,+11.8] | +15.3 [-25.1,+57.7] |
| 13:00 | SENSEX | other | 148 | +1.4 [-1.3,+4.9] | -2 | 37 | +0.5 [-2.2,+4.2] | +3 | -3.4 [-9.3,+2.7] | +6.0 [-3.0,+16.3] |
| 14:00 | NIFTY | expiry | 40 | -10.9 [-31.0,+10.7] | -29 | 32 | -9.1 [-33.1,+15.4] | +20 | -11.4 [-53.9,+41.8] | -3.3 [-43.6,+48.2] |
| 14:00 | NIFTY | other | 148 | -0.9 [-1.9,+0.3] | -1 | 38 | -2.1 [-3.2,-0.8] | +2 | -1.7 [-5.5,+3.0] | -0.1 [-4.4,+4.1] |
| 14:00 | SENSEX | expiry | 40 | -4.3 [-25.1,+14.9] | -13 | 42 | -5.7 [-28.2,+15.5] | +44 | -9.5 [-49.7,+33.9] | +8.6 [-36.7,+53.3] |
| 14:00 | SENSEX | other | 148 | +0.3 [-1.2,+2.0] | -2 | 43 | -0.6 [-2.2,+1.4] | +3 | +2.3 [-2.9,+7.7] | -2.9 [-7.3,+1.9] |
| 14:30 | NIFTY | expiry | 40 | +4.2 [-18.0,+29.7] | -14 | 40 | +4.9 [-18.3,+30.4] | +30 | -0.5 [-41.8,+47.0] | +18.1 [-31.0,+73.9] |
| 14:30 | NIFTY | other | 148 | -1.1 [-1.8,-0.3] | -1 | 41 | -2.3 [-3.2,-1.3] | +2 | -0.9 [-4.6,+2.4] | -1.3 [-4.5,+2.4] |
| 14:30 | SENSEX | expiry | 40 | -2.3 [-24.5,+17.9] | -17 | 38 | -2.9 [-27.1,+20.7] | +29 | -0.3 [-43.5,+53.6] | +12.7 [-34.1,+66.7] |
| 14:30 | SENSEX | other | 148 | -0.2 [-1.2,+1.0] | -1 | 41 | -1.1 [-2.3,+0.1] | +3 | -1.0 [-4.5,+2.5] | -0.3 [-4.3,+3.4] |


**Reading.** |5-min| moves: 12.3 bp at 09:15–09:29, 7.4 bp 09:30–09:44, ~4.3–4.6 bp midday, 6.3 bp at 15:00. No slot shows direction persistence; 09:45 and 11:00 show mild mean reversion (ρ −0.17, −0.15). The first 15–30 minutes carry little information about the rest of the day (49–56 % same direction), except tails: first 15 min ≥ +0.5 % → lower rest-of-day on 12/13 days (mean −0.37 %); ≤ −0.5 % → bounce (mean +0.33 %, n = 32), mostly Jan–May. After a gap ≥ 0.2 %, a first 30 minutes in the gap direction leaves the gap unfilled on 74–82 % of days. 0DTE straddles: no unconditional edge; lottery-shaped payoff.

## 5. Level breaks (task 4)

Break = first 1-minute close beyond the level coming from the inside (ORH/ORL from 09:30, PDH/PDL from 09:16; breaks after 15:00 ignored). Continuation = the index extends ≥ 0.25 % beyond the level (bar high/low) before any close back inside. Fail = a close back inside comes first. "0.10 % buffer" = failure only on a close 0.10 % back inside the level (a stop just behind the level). Remainder = neither by 15:29. fwd30 = index return over the next 30 min in the break direction. Wilson 95 % CI. ALL set (bars to 6 Oct + ticks 7-21 Oct); P3 = own tick days computed separately.

#### By level and period

| subset | n | continue ≥0.25 % first % [CI] | same, 0.10 % buffer % | fail first % | mean fwd30 % |
|---|---|---|---|---|---|
| ORH | 251 | 16 [12-21] | 33 | 84 | +0.007 |
| ORL | 280 | 12 [9-16] | 33 | 88 | -0.004 |
| PDH | 140 | 22 [16-30] | 35 | 76 | +0.021 |
| PDL | 139 | 22 [16-30] | 45 | 78 | +0.001 |
| all levels | 810 | 17 [14-19] | 36 | 83 | +0.005 |
| all levels, P1 (Jan-May) | 414 | 21 [17-25] | 39 | 79 | +0.016 |
| all levels, P2 (Jun-27 Sep) | 323 | 9 [6-13] | 28 | 91 | -0.019 |
| all levels, P3 own ticks | 75 | 24 [16-35] | 47 | 75 | +0.034 |


#### Conditioned on time

| subset | n | continue ≥0.25 % first % [CI] | same, 0.10 % buffer % | fail first % | mean fwd30 % | continue P1 | continue P2+P3 |
|---|---|---|---|---|---|---|---|
| OR breaks: 09:30-10:15 | 334 | 12 [9-16] | 33 | 88 | -0.007 | 15 [10-21] (n=165) | 10 [6-16] (n=169) |
| OR breaks: 10:15-12:00 | 105 | 14 [9-22] | 32 | 86 | -0.016 | 22 [13-34] (n=55) | 6 [2-16] (n=50) |
| OR breaks: 12:00-13:30 | 48 | 15 [7-27] | 38 | 85 | +0.066 | 13 [5-30] (n=30) | 17 [6-39] (n=18) |
| OR breaks: 13:30-15:00 | 44 | 23 [13-37] | 34 | 75 | +0.033 | 32 [16-53] (n=22) | 14 [5-33] (n=22) |
| PD breaks: 09:30-10:15 | 158 | 22 [16-29] | 41 | 78 | -0.006 | 29 [20-39] (n=77) | 16 [10-26] (n=81) |
| PD breaks: 10:15-12:00 | 42 | 24 [13-39] | 45 | 76 | +0.029 | 16 [6-35] (n=25) | 35 [17-59] (n=17) |
| PD breaks: 12:00-13:30 | 37 | 14 [6-28] | 38 | 86 | +0.083 | 29 [13-53] (n=17) | 0 [-0-16] (n=20) |
| PD breaks: 13:30-15:00 | 42 | 29 [17-44] | 33 | 67 | -0.007 | 39 [22-59] (n=23) | 16 [6-38] (n=19) |


#### Conditioned on compression (30-min range vs norm, terciles)

| subset | n | continue ≥0.25 % first % [CI] | same, 0.10 % buffer % | fail first % | mean fwd30 % | continue P1 | continue P2+P3 |
|---|---|---|---|---|---|---|---|
| OR breaks: tight | 120 | 9 [5-16] | 26 | 90 | -0.025 | 14 [7-25] (n=57) | 5 [2-13] (n=63) |
| OR breaks: mid | 92 | 12 [7-20] | 32 | 88 | -0.014 | 19 [10-32] (n=48) | 5 [1-15] (n=44) |
| OR breaks: wide | 90 | 24 [17-34] | 47 | 76 | +0.067 | 27 [17-41] (n=51) | 21 [11-36] (n=39) |
| PD breaks: tight | 36 | 17 [8-32] | 36 | 83 | -0.018 | 26 [12-49] (n=19) | 6 [1-27] (n=17) |
| PD breaks: mid | 63 | 22 [14-34] | 40 | 76 | +0.007 | 30 [17-46] (n=37) | 12 [4-29] (n=26) |
| PD breaks: wide | 66 | 27 [18-39] | 45 | 71 | +0.065 | 29 [17-46] (n=34) | 25 [13-42] (n=32) |


#### Conditioned on break-minute 5-min futures volume vs norm (terciles)

| subset | n | continue ≥0.25 % first % [CI] | same, 0.10 % buffer % | fail first % | mean fwd30 % | continue P1 | continue P2+P3 |
|---|---|---|---|---|---|---|---|
| OR breaks: low | 173 | 10 [7-16] | 32 | 90 | -0.013 | 15 [9-24] (n=81) | 7 [3-14] (n=92) |
| OR breaks: mid | 192 | 14 [10-20] | 32 | 85 | +0.004 | 17 [11-25] (n=101) | 11 [6-19] (n=91) |
| OR breaks: high | 166 | 17 [12-23] | 36 | 83 | +0.014 | 20 [13-29] (n=90) | 13 [7-23] (n=76) |
| PD breaks: low | 83 | 14 [8-24] | 35 | 86 | -0.034 | 19 [10-33] (n=42) | 10 [4-23] (n=41) |
| PD breaks: mid | 64 | 22 [14-33] | 41 | 78 | +0.004 | 28 [15-46] (n=29) | 17 [8-33] (n=35) |
| PD breaks: high | 91 | 27 [19-37] | 42 | 70 | +0.052 | 31 [21-45] (n=54) | 22 [11-37] (n=37) |


#### Conditioned on trend so far (open→break in break dir)

| subset | n | continue ≥0.25 % first % [CI] | same, 0.10 % buffer % | fail first % | mean fwd30 % | continue P1 | continue P2+P3 |
|---|---|---|---|---|---|---|---|
| OR breaks: with (> +0.3 %) | 205 | 17 [13-23] | 38 | 83 | +0.023 | 19 [13-26] (n=123) | 15 [9-24] (n=82) |
| OR breaks: flat | 326 | 12 [9-16] | 30 | 88 | -0.012 | 16 [11-23] (n=149) | 8 [5-13] (n=177) |
| OR breaks: against (< -0.3 %) | 0 | – | – | – | – | – (n=0) | – (n=0) |
| PD breaks: with (> +0.3 %) | 91 | 30 [21-40] | 48 | 69 | +0.051 | 41 [28-56] (n=44) | 19 [10-33] (n=47) |
| PD breaks: flat | 160 | 19 [14-26] | 38 | 80 | -0.004 | 26 [18-37] (n=80) | 12 [7-22] (n=80) |
| PD breaks: against (< -0.3 %) | 28 | 14 [6-31] | 25 | 86 | -0.033 | 6 [1-26] (n=18) | 30 [11-60] (n=10) |


#### Conditioned on VWAP side at break

| subset | n | continue ≥0.25 % first % [CI] | same, 0.10 % buffer % | fail first % | mean fwd30 % | continue P1 | continue P2+P3 |
|---|---|---|---|---|---|---|---|
| OR breaks: beyond VWAP in break dir | 531 | 14 [11-17] | 33 | 86 | +0.001 | 17 [13-22] (n=272) | 10 [7-14] (n=259) |
| OR breaks: wrong side of VWAP | 0 | – | – | – | – | – (n=0) | – (n=0) |
| PD breaks: beyond VWAP in break dir | 224 | 24 [19-30] | 41 | 75 | +0.014 | 32 [24-41] (n=113) | 15 [10-23] (n=111) |
| PD breaks: wrong side of VWAP | 55 | 16 [9-28] | 38 | 84 | -0.002 | 14 [5-31] (n=29) | 19 [9-38] (n=26) |


#### Conditioned on gap in break direction

| subset | n | continue ≥0.25 % first % [CI] | same, 0.10 % buffer % | fail first % | mean fwd30 % | continue P1 | continue P2+P3 |
|---|---|---|---|---|---|---|---|
| OR breaks: gap with (> 0.2 %) | 171 | 20 [15-26] | 33 | 80 | +0.020 | 24 [16-34] (n=92) | 15 [9-25] (n=79) |
| OR breaks: small gap | 197 | 9 [5-13] | 33 | 91 | -0.001 | 8 [4-16] (n=96) | 9 [5-16] (n=101) |
| OR breaks: gap against (< -0.2 %) | 160 | 14 [9-20] | 34 | 86 | -0.013 | 21 [14-31] (n=81) | 6 [3-14] (n=79) |
| PD breaks: gap with (> 0.2 %) | 124 | 25 [18-33] | 42 | 75 | +0.020 | 30 [20-41] (n=71) | 19 [11-31] (n=53) |
| PD breaks: small gap | 125 | 20 [14-28] | 38 | 78 | +0.009 | 27 [17-39] (n=60) | 14 [7-24] (n=65) |
| PD breaks: gap against (< -0.2 %) | 30 | 20 [10-37] | 40 | 80 | -0.016 | 27 [10-57] (n=11) | 16 [6-38] (n=19) |


#### Conditioned on expiry day

| subset | n | continue ≥0.25 % first % [CI] | same, 0.10 % buffer % | fail first % | mean fwd30 % | continue P1 | continue P2+P3 |
|---|---|---|---|---|---|---|---|
| OR breaks: expiry | 112 | 14 [9-22] | 36 | 86 | +0.010 | 18 [10-29] (n=61) | 10 [4-21] (n=51) |
| OR breaks: non-expiry | 419 | 14 [11-17] | 32 | 86 | -0.001 | 17 [13-23] (n=211) | 10 [7-15] (n=208) |
| PD breaks: expiry | 66 | 20 [12-31] | 41 | 80 | -0.003 | 18 [9-34] (n=33) | 21 [11-38] (n=33) |
| PD breaks: non-expiry | 213 | 23 [18-29] | 40 | 76 | +0.015 | 31 [23-40] (n=109) | 14 [9-22] (n=104) |


#### Combinations (OR + PD breaks pooled)

| subset | n | continue ≥0.25 % first % [CI] | same, 0.10 % buffer % | fail first % | mean fwd30 % | continue P1 | continue P2+P3 |
|---|---|---|---|---|---|---|---|
| time < 10:15 & trend with | 200 | 18 [13-24] | 37 | 82 | +0.014 | 18 [12-27] | 18 [11-27] |
| time ≥ 10:15 & tight compression & high volume | 29 | 7 [2-22] | 24 | 93 | -0.033 | 20 [6-51] | 0 [0-17] |
| time ≥ 10:15 & wide range & high volume | 106 | 26 [19-36] | 45 | 73 | +0.081 | 28 [19-40] | 24 [13-39] |
| time ≥ 10:15 & beyond VWAP & trend with | 96 | 27 [19-37] | 49 | 72 | +0.068 | 36 [25-49] | 13 [6-27] |
| time ≥ 10:15 & against trend | 22 | 18 [7-39] | 27 | 82 | -0.012 | 7 [1-31] | 38 [14-69] |
| after 13:30 & expiry | 21 | 19 [8-40] | 38 | 81 | -0.027 | 18 [5-48] | 20 [6-51] |


#### Reference: random-time control

Random minute, random direction, same rule (pseudo-level placed the median break distance 0.017 % behind the entry; reach +0.25 % beyond it before a close back through it): 11 % [10-13] (n=1584); with the 0.10 % buffer 30 %. A level break is only informative if its continuation rate is clearly above this.


**Reading.** OR breaks fail first 84–88 % of the time, previous-day levels 76–78 %. Wide recent range, higher break volume and breaking with the day's trend help consistently in P1 and P2+P3; the best combination (after 10:15, wide range, high volume) gives 26 % / 45 % vs an 11 % / 30 % random control and +0.08 % 30-minute drift — small for an option buyer.

## 6. 30-second microstructure (task 5)

### Largest impulses on own tick days and their 5-minute run-up at 30-second resolution

Impulses detected on 1-minute closes built from own index ticks (P3, 16 days). The 10 largest per index with onset ≥ 09:25 (so that the 5-minute pre-window is inside the session) are taken from the 0.35 %/30 min list, topped up from 0.25 %/15 min. Directional quantities are multiplied by the impulse direction (positive = pointing the way the index then moved). Controls: 10 per impulse, same index, other tick days, onset ±15 min, no 0.25 % impulse starting within [−5, +15] min; controls are aligned with the sign of their own next-5-minute index move. OFI = order-flow imbalance at the best level (Cont-Kukanov-Stoikov) per 30 s divided by the average best-level size; svf = signed futures volume / volume; dimb = 5-level depth imbalance; bimb = whole-book total buy vs sell quantity imbalance. Futures ticks are missing on 28-29 Sep.


#### NIFTY: the 10 impulses

| date | onset | dir | size % | onset→peak min | def |
|---|---|---|---|---|---|
| 2026-10-19 | 09:28 | up | 0.81 | 9 | 0.35 |
| 2026-10-01 | 12:33 | down | 0.81 | 25 | 0.35 |
| 2026-10-16 | 09:36 | up | 0.66 | 30 | 0.35 |
| 2026-10-01 | 14:04 | up | 0.64 | 30 | 0.35 |
| 2026-09-29 | 11:05 | up | 0.60 | 26 | 0.35 |
| 2026-10-21 | 09:34 | down | 0.55 | 30 | 0.35 |
| 2026-10-08 | 11:06 | down | 0.54 | 22 | 0.35 |
| 2026-10-21 | 11:04 | down | 0.53 | 30 | 0.35 |
| 2026-10-19 | 09:37 | down | 0.53 | 22 | 0.35 |
| 2026-10-07 | 12:43 | down | 0.53 | 27 | 0.35 |


#### SENSEX: the 10 impulses

| date | onset | dir | size % | onset→peak min | def |
|---|---|---|---|---|---|
| 2026-10-19 | 09:30 | up | 0.79 | 7 | 0.35 |
| 2026-10-01 | 12:32 | down | 0.78 | 26 | 0.35 |
| 2026-10-16 | 09:36 | up | 0.68 | 30 | 0.35 |
| 2026-10-09 | 09:25 | up | 0.66 | 30 | 0.35 |
| 2026-10-21 | 09:40 | down | 0.62 | 28 | 0.35 |
| 2026-10-01 | 14:04 | up | 0.62 | 30 | 0.35 |
| 2026-09-29 | 11:05 | up | 0.60 | 28 | 0.35 |
| 2026-10-07 | 10:02 | up | 0.56 | 27 | 0.35 |
| 2026-10-15 | 15:13 | up | 0.55 | 15 | 0.35 |
| 2026-10-21 | 11:04 | down | 0.55 | 30 | 0.35 |


#### NIFTY: 5-minute pre-onset microstructure, impulses vs controls

| metric | impulse mean | impulse median | control mean | control median | AUC | n imp/ctl |
|---|---|---|---|---|---|---|
| OFI (aligned), mean of 10 buckets before onset | -2.72 | -1.99 | 0.21 | 0.12 | 0.23 | 9/78 |
| OFI (aligned), last 2 buckets | -5.08 | -4.78 | -0.18 | -0.01 | 0.22 | 9/78 |
| OFI (aligned), first 2 buckets after | 4.30 | 2.67 | 0.17 | 0.21 | 0.67 | 9/78 |
| signed-volume fraction (aligned), pre 5 min | -0.20 | -0.15 | -0.02 | -0.01 | 0.21 | 9/78 |
| signed-volume fraction, last 1 min | -0.42 | -0.41 | -0.06 | -0.05 | 0.23 | 9/78 |
| signed-volume fraction, first 1 min after | 0.01 | 0.10 | 0.05 | 0.07 | 0.47 | 9/78 |
| 5-level depth imbalance (aligned), pre | -0.01 | 0.04 | 0.03 | 0.03 | 0.42 | 9/78 |
| depth imbalance, last 1 min | 0.10 | -0.03 | 0.05 | 0.06 | 0.54 | 9/78 |
| total buy/sell qty imbalance (aligned), pre | 0.02 | 0.07 | 0.00 | -0.03 | 0.55 | 9/78 |
| futures spread (points), pre | 4.80 | 4.66 | 4.60 | 4.27 | 0.58 | 9/78 |
| futures spread, last 1 min | 4.14 | 4.24 | 4.58 | 3.89 | 0.48 | 9/78 |
| futures volume in pre 5 min (contracts) | 97673.33 | 117650.00 | 53392.50 | 43257.50 | 0.65 | 9/78 |
| mean |30 s index return| (bp), pre | 2.91 | 2.10 | 1.72 | 1.53 | 0.69 | 10/100 |
| index return over pre 5 min, aligned (%) | -0.19 | -0.11 | -0.00 | 0.00 | 0.09 | 10/100 |
| ATM straddle change over pre 5 min (%) | 0.81 | -1.67 | 0.22 | 0.07 | 0.28 | 10/100 |
| ATM IV change over pre 5 min (vol pts ×100) | 0.44 | -0.17 | 0.09 | 0.03 | 0.34 | 10/100 |
| ATM option spread (₹), pre | 0.27 | 0.29 | 0.24 | 0.25 | 0.58 | 10/100 |
| near-ATM option OI change pre 5 min (% of OI) | 1.47 | 0.87 | 3.22 | 2.02 | 0.38 | 10/100 |
| (PE − CE) OI change, aligned (% of OI) | -4.26 | -1.09 | 0.43 | 0.19 | 0.37 | 10/100 |
| ATM option in impulse dir: premium change over pre 5 min (%) | -20.10 | -11.20 | -0.33 | -0.59 | 0.11 | 10/100 |
| same option: change over the first 5 min after onset (%) | 26.17 | 16.77 | 6.74 | 5.09 | 0.74 | 10/100 |


Pre-window OFI pointed the impulse's way in 1/9 impulses; in the last minute 0/9; in the first minute after onset 7/9.


#### SENSEX: 5-minute pre-onset microstructure, impulses vs controls

| metric | impulse mean | impulse median | control mean | control median | AUC | n imp/ctl |
|---|---|---|---|---|---|---|
| OFI (aligned), mean of 10 buckets before onset | -2.78 | -2.59 | 0.17 | 0.24 | 0.30 | 9/80 |
| OFI (aligned), last 2 buckets | -2.72 | -2.53 | 0.22 | 1.09 | 0.33 | 9/80 |
| OFI (aligned), first 2 buckets after | 3.98 | 3.72 | 1.31 | 1.32 | 0.65 | 9/80 |
| signed-volume fraction (aligned), pre 5 min | -0.26 | -0.19 | -0.01 | 0.00 | 0.32 | 9/80 |
| signed-volume fraction, last 1 min | -0.43 | -1.00 | 0.03 | -0.00 | 0.34 | 9/60 |
| signed-volume fraction, first 1 min after | 0.24 | -0.00 | 0.08 | 0.07 | 0.55 | 9/58 |
| 5-level depth imbalance (aligned), pre | 0.07 | 0.12 | -0.03 | -0.03 | 0.71 | 9/80 |
| depth imbalance, last 1 min | 0.14 | 0.09 | -0.01 | -0.02 | 0.72 | 9/80 |
| total buy/sell qty imbalance (aligned), pre | -0.03 | -0.02 | -0.06 | -0.04 | 0.51 | 9/80 |
| futures spread (points), pre | 27.70 | 26.46 | 29.77 | 29.52 | 0.41 | 9/80 |
| futures spread, last 1 min | 30.12 | 30.82 | 30.32 | 29.37 | 0.48 | 9/80 |
| futures volume in pre 5 min (contracts) | 1017.78 | 740.00 | 508.75 | 290.00 | 0.69 | 9/80 |
| mean |30 s index return| (bp), pre | 2.54 | 2.10 | 1.56 | 1.45 | 0.77 | 10/100 |
| index return over pre 5 min, aligned (%) | -0.11 | -0.10 | 0.00 | 0.00 | 0.16 | 10/100 |
| ATM straddle change over pre 5 min (%) | 0.26 | -0.11 | -0.13 | -0.22 | 0.55 | 10/100 |
| ATM IV change over pre 5 min (vol pts ×100) | 0.82 | 0.23 | 0.02 | -0.01 | 0.67 | 10/100 |
| ATM option spread (₹), pre | 0.79 | 0.73 | 0.82 | 0.87 | 0.45 | 10/100 |
| near-ATM option OI change pre 5 min (% of OI) | 4.96 | 2.92 | 5.39 | 2.50 | 0.49 | 10/100 |
| (PE − CE) OI change, aligned (% of OI) | -3.84 | -2.65 | 0.46 | 0.78 | 0.27 | 10/100 |
| ATM option in impulse dir: premium change over pre 5 min (%) | -12.75 | -9.58 | -0.16 | -0.41 | 0.20 | 10/100 |
| same option: change over the first 5 min after onset (%) | 24.60 | 19.27 | 6.87 | 3.60 | 0.84 | 10/100 |


Pre-window OFI pointed the impulse's way in 2/9 impulses; in the last minute 1/9; in the first minute after onset 8/9.


#### All 0.25 %/15 min impulses on tick days (onset ≥ 09:25), both indices: OFI / signed volume alignment

| index | metric | n | % pointing the impulse way [CI] | mean (aligned) |
|---|---|---|---|---|
| NIFTY | OFI (aligned), mean of 10 buckets before onset | 70 | 34 [24-46] | -0.89 |
| NIFTY | OFI (aligned), last 2 buckets | 70 | 24 [16-35] | -2.70 |
| NIFTY | OFI (aligned), first 2 buckets after | 70 | 86 [76-92] | 4.03 |
| NIFTY | signed-volume fraction (aligned), pre 5 min | 70 | 39 [28-50] | -0.08 |
| NIFTY | signed-volume fraction, last 1 min | 70 | 27 [18-39] | -0.21 |
| NIFTY | signed-volume fraction, first 1 min after | 70 | 67 [56-77] | 0.13 |
| NIFTY | 5-level depth imbalance (aligned), pre | 70 | 56 [44-67] | 0.04 |
| NIFTY | total buy/sell qty imbalance (aligned), pre | 70 | 56 [44-67] | 0.03 |
| NIFTY | (PE − CE) OI change, aligned (% of OI) | 70 | 59 [47-69] | 1.10 |
| NIFTY | index return over pre 5 min, aligned (%) | 70 | 23 [15-34] | -0.06 |
| SENSEX | OFI (aligned), mean of 10 buckets before onset | 71 | 45 [34-57] | -0.67 |
| SENSEX | OFI (aligned), last 2 buckets | 71 | 37 [26-48] | -1.60 |
| SENSEX | OFI (aligned), first 2 buckets after | 71 | 86 [76-92] | 6.19 |
| SENSEX | signed-volume fraction (aligned), pre 5 min | 71 | 28 [19-40] | -0.15 |
| SENSEX | signed-volume fraction, last 1 min | 55 | 25 [16-38] | -0.32 |
| SENSEX | signed-volume fraction, first 1 min after | 58 | 62 [49-73] | 0.35 |
| SENSEX | 5-level depth imbalance (aligned), pre | 71 | 46 [35-58] | -0.01 |
| SENSEX | total buy/sell qty imbalance (aligned), pre | 71 | 55 [43-66] | 0.04 |
| SENSEX | (PE − CE) OI change, aligned (% of OI) | 71 | 66 [55-76] | 1.88 |
| SENSEX | index return over pre 5 min, aligned (%) | 71 | 27 [18-38] | -0.05 |

#### Lead-lag at 30 s over all buckets of the tick days (09:25-15:25)

Per-day Pearson correlation between a signal in bucket i and the index return in later buckets, averaged over days (± standard error across days). "Top-decile hit" = share of buckets in the top decile of |signal| (per index) where the next-2-minute index return has the signal's sign. fut-lead = futures mid-price return in bucket i minus index return in bucket i (basis move).

| index | signal (bucket i) | days | corr with ret(i) (same bucket) | corr with ret(i+1) | corr with ret(i+1..i+4) | top-decile hit, next 2 min |
|---|---|---|---|---|---|---|
| NIFTY | ofi | 14 | +0.598 ± 0.011 | +0.038 ± 0.011 | +0.024 ± 0.015 | 51 % (n=997) |
| NIFTY | svf | 14 | +0.414 ± 0.013 | +0.019 ± 0.012 | +0.013 ± 0.010 | 53 % (n=987) |
| NIFTY | dimb | 14 | -0.029 ± 0.008 | -0.012 ± 0.009 | -0.030 ± 0.012 | 46 % (n=957) |
| NIFTY | bimb | 14 | +0.069 ± 0.014 | -0.003 ± 0.011 | -0.041 ± 0.021 | 49 % (n=943) |
| NIFTY | futlead | 14 | -0.060 ± 0.018 | +0.010 ± 0.011 | -0.006 ± 0.004 | 47 % (n=978) |
| NIFTY | ret | 14 | +1.000 ± 0.000 | +0.038 ± 0.017 | +0.041 ± 0.013 | 52 % (n=1008) |
| SENSEX | ofi | 14 | +0.492 ± 0.012 | +0.154 ± 0.014 | +0.108 ± 0.013 | 59 % (n=992) |
| SENSEX | svf | 14 | +0.365 ± 0.013 | +0.067 ± 0.010 | +0.034 ± 0.014 | 53 % (n=3594) |
| SENSEX | dimb | 14 | -0.049 ± 0.012 | -0.027 ± 0.009 | -0.028 ± 0.013 | 51 % (n=970) |
| SENSEX | bimb | 14 | +0.113 ± 0.017 | +0.030 ± 0.021 | -0.011 ± 0.030 | 53 % (n=987) |
| SENSEX | futlead | 14 | -0.352 ± 0.028 | +0.014 ± 0.014 | +0.003 ± 0.010 | 50 % (n=987) |
| SENSEX | ret | 14 | +1.000 ± 0.000 | +0.057 ± 0.019 | +0.049 ± 0.014 | 53 % (n=1008) |

#### Cross-instrument lead-lag at 30 s (own tick days)

| signal → target | days | mean per-day corr ± se |
|---|---|---|
| NIFTY index ret(i) -> SENSEX index ret(i+1) | 14 | +0.204 ± 0.019 |
| SENSEX index ret(i) -> NIFTY index ret(i+1) | 14 | +0.015 ± 0.015 |
| NIFTY fut ret(i) -> NIFTY index ret(i+1) | 14 | +0.034 ± 0.014 |
| NIFTY fut ret(i) -> SENSEX index ret(i+1) | 14 | +0.155 ± 0.016 |
| SENSEX fut ret(i) -> SENSEX index ret(i+1) | 14 | +0.055 ± 0.009 |

#### Do ATM option prices lag the index at 30 s?

Signal = (CE return − PE return) of the fixed-strike ATM pair (a delta-signed option move). Correlation with the NIFTY index return in the same and in the previous bucket, and with the option's own index (pooled).

| options of | corr NIFTY ret(i) vs option(i) | corr NIFTY ret(i) vs option(i+1) | own index ret(i) vs option(i) (pooled) | own index ret(i) vs option(i+1) (pooled) |
|---|---|---|---|---|
| NIFTY | +0.838 ± 0.022 | -0.016 ± 0.011 | +0.680 | -0.015 |
| SENSEX | +0.862 ± 0.017 | -0.001 ± 0.012 | +0.635 | -0.012 |


**Reading.** The run-up into the largest impulses is the end of a counter-move: OFI, signed volume and price point against the coming impulse (OFI aligned in 1/9 NIFTY, 2/9 SENSEX) and flip at onset; the option in the impulse direction loses 10–20 % in the run-up and gains 17–26 % (medians) in the first 5 minutes. Run-up futures volume is ~2× controls (AUC 0.65–0.69); 30-s volatility higher (AUC 0.69–0.77); depth/book imbalances carry nothing reliable. OFI predicts the next 30 s weakly (NIFTY ρ 0.04, SENSEX 0.15). The SENSEX index print lags NIFTY (ρ 0.20 next bucket) but SENSEX options do not.

## 7. Entry-timing hypotheses, ranked (costs 0.5–1 % of premium round trip; validate on sessions from 22 Oct onward with option-price replays)

| # | Hypothesis | Evidence | Expected effect | Validation | Evidence / tradability |
|---|---|---|---|---|---|
| 1 | Compression veto: no new long option when 15-min range ≤ 0.69× (30-min ≤ 0.70×) its trailing-20-day norm at that minute | Impulse lift 0.45 (0.58 stratified; P1 0.39, P2 0.69, P3 0.34); straddle −0.44 %/15 min P1, −0.42 % P2+; breaks from compression continue 5–9 % | Removes ~19 % of entries with half the base move chance and steady bleed | A/B veto in tick replays of each live/shadow strategy on ≥ 15 new sessions | Strong / high |
| 2 | Regime gate: thresholds and position count scaled by VIX at 09:15 or trailing realized range (thresholds in σ units) | 0.66 → 5.1 impulses/day across VIX buckets; P1 3.7 vs P2 1.1/day | Fewer whipsaws in low vol; stable signal rate | Re-run strategies with k × VIX-implied σ thresholds vs fixed %; compare option P&L per regime | Strong / medium |
| 3 | Trigger SENSEX option entries from NIFTY index/futures, not the SENSEX index print | NIFTY → next-30-s SENSEX index ρ 0.20 ± 0.02; SENSEX options track NIFTY same bucket (ρ 0.86), no lag | Earlier fills by up to one 30-s bucket | Tick-day fill comparison: option ask at SENSEX-index trigger vs NIFTY-derived trigger; then paper | Strong / high |
| 4 | Expiry-day OI veto: skip 15 min when near-ATM OI rose > 6 % in 15 min | Straddle −1.24 %/15 min P1, −0.45 % P2+; non-expiry ≈ 0 | Avoid fastest 0DTE theta crush | Veto test on expiry-day trades, ≥ 8 new expiry days per index | Moderate / medium |
| 5 | No confirmation chasing (entries ≥ 0.25–0.35 % past a 10–30-min extreme) | Thrust continuation ≈ 0 for X 0.10–0.35 % in P1 and P2; confirm→+15 min +0.3 % | Avoid paying for done moves | Bucket live entries' option P&L by distance from the 30-min extreme | Strong (problem) / medium |
| 6 | Break filter: OR/PD breaks after 10:15, top-tercile 30-min range and break volume, stop 0.10 % behind level | 26 % / 45 % vs 11 % / 30 % control; fwd30 +0.08 %; P1 28 %, P2+P3 24 %; n = 106 | Small directional edge | Option replay, ≥ 40 trades, positive after 1 % costs | Moderate / low-medium |
| 7 | Fade first-15-min moves ≥ ±0.5 % | n = 13 / 32, mostly Jan–May | Retrace −0.37 % / +0.33 % | Extend bar history before 2026, then forward | Weak / medium |
| 8 | Momentum only when trailing-10-day 15-min return autocorrelation > 0 | October +0.063 %/30 min, 61 %; Jan–Sep 0 | Trend months on, chop off | Backtest switch on Jan–Sep bars, then forward | Weak / unknown |
| 9 | SENSEX OFI timing: delay entry ≤ 30 s if last-30-s futures OFI disagrees | Next-30-s ρ 0.15; top decile 59 % over 2 min | Small fill gain | Same fill test as #3 | Weak-moderate / low |

**Did not survive:** compression-then-breakout entries; round-number/VWAP magnets; VIX-change or breadth-change direction signals; PE−CE OI flow as a 30-min direction signal; "first 30 minutes set the day"; unconditional 0DTE afternoon straddle/leg buying.

## 8. Limitations

Only 188 bar days of 2026 with large regime differences; option analysis on bar closes (no bid/ask) with flat costs (tick spreads ₹0.25–0.30 NIFTY ATM, ₹0.7–0.9 SENSEX ATM); SENSEX futures too thin for clean flow; breadth only from July (unweighted); OFI from ~1/s best-level snapshots, no trade-by-trade data.

## 9. Files

`q.sh` (read-only psql), `extract_bars.sh`, `extract_options.py`, `extract_equity.py`, `extract_ticks.py` (→ `data/`), `core.py` (→ `data/days.pkl`, `data/tick30.pkl`), `check_overlap.py`, `lib.py`, `feats.py`, `t1_anatomy.py`, `t2_impulses.py`, `t2b_options.py`, `t3_timeofday.py`, `t3b_0dte.py`, `t4_breaks.py`, `t5_micro.py`, `t5b_cross.py`, `t5c_optlag.py`, `t6_extra.py`, `build_report.py`; section outputs `out/`; per-day table `data/anatomy.csv`.
