# Straddle with a day-type filter and a loss cap, ₹5,00,000 capital (registered 2026-09-27, before computing)

Question (user, 2026-09-27): do the ideas from the straddle study hold up — trade only days that look like
trend days, cap the loss, avoid the open and the late afternoon — sized for a ₹5,00,000 account?

## Data
- Full-capture sessions only (partial-capture 4, 11, 16, 21 Sep excluded): 3, 8, 10, 15, 17, 18, 22, 23, 24,
  25 Sep × NIFTY and SENSEX = 20 index-days. 3/8/10 Sep from the restored archive (`zt_archive`).
- Sample A, expiry days (7): 3/10/17/24 Sep SENSEX, 8/15/22 Sep NIFTY. **Already seen** in the straddle
  study of 2026-09-26: in-sample, descriptive only.
- Sample B, non-expiry days (13): the other index-days, nearest weekly expiry. Not used by any straddle
  study before; the index days were seen by the directional strategies.
- Raw broker ticks: index CASH ticks for the filter, option ticks for fills.

## Rules (fixed before computing)
- **Decision at 11:00 IST** from the index's ticks since 09:15:
  - morning range `Rm` = high − low 09:15–11:00; ATM straddle mid `S` at 11:00 (strike = spot rounded to
    the step, nearest expiry);
  - implied rest-of-day move: `S` prices the expected absolute move to expiry; the expected range of a
    random walk over the morning (105 min) relative to that is `2·√(105 / T)`, with `T` = minutes from
    11:00 to expiry (expiry day: 270; later expiries count 375 minutes per trading day to expiry plus
    270 today);
  - **filter F1 (primary): trade only if `Rm / S ≥ 2·√(105 / T)`** — the morning realised at least the
    volatility the options imply (realised ≥ implied);
  - filter F2 (reported alongside, not tuned): directional efficiency `|P(11:00) − P(09:15)| / Rm ≥ 0.5`;
  - no filter (every day) is reported for comparison.
- **Trade**: buy the ATM straddle (1-strike OTM strangle reported alongside) at 11:00 + 250 ms, each leg at
  its ask on the first tick; sell at the bid. One trade per index-day; no entries before 09:30 or after
  14:00 (the only entry is 11:00).
- **Exit**: combined bid ≥ 1.30 × premium paid (target) · combined bid ≤ 0.80 × premium paid (loss cap,
  primary; 0.85 reported alongside) · otherwise at 15:15. Intraday only.
- **Sizing (₹5,00,000)**: premium budget 5 % of capital per trade (₹25,000): lots = floor(25,000 ÷ straddle
  cost per lot pair), at least 1; so the loss cap risks about ₹5,000 = 1 % of capital. Both indices may
  trade the same day. Capital fixed (no compounding). Full costs (brokerage ₹20+GST per order, STT,
  exchange, SEBI, stamp).
- **Report**: trades, filter decisions per day, hit/stop/time exits, net ₹, return on capital, largest
  loss, max drawdown of the daily equity curve; per sample.

## What counts
Nothing in Sample A counts as evidence (seen). Sample B is a first look, small (13 index-days). The test that
counts is forward: the same rules on expiry days from 29 Sep 2026 (ledger A-008).

## Results (computed 2026-09-27 after the rules above; unchanged output in the session scratchpad)

11:00 filter decisions — F1 (morning range ÷ ATM straddle ≥ 2·√(105/T)) said "trade" on 3 of 20 index-days:
15 Sep NIFTY (expiry, ratio 2.21 vs 1.25 needed), 22 Sep NIFTY (expiry, 1.32 vs 1.25), 15 Sep SENSEX
(non-expiry, 1.01 vs 0.64).

Straddle, +30 % target, −20 % loss cap (primary), 5 % of ₹5,00,000 premium per trade:

| Sample | Filter | Trades | Target / stop / 15:15 | Net ₹ | % of capital | Worst trade | Max drawdown |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: |
| A expiry (seen) | none | 7 | 3 / 4 / 0 | +982 | +0.20 % | −5,342 | −14,266 |
| A expiry (seen) | **F1** | 2 | 2 / 0 / 0 | **+14,230** | +2.85 % | +6,854 | 0 |
| A expiry (seen) | F2 | 5 | 2 / 3 / 0 | −894 | −0.18 % | −5,342 | −10,140 |
| B non-expiry (new) | none | 13 | 1 / 0 / 12 | −9,090 | −1.82 % | −2,850 | −13,344 |
| B non-expiry (new) | **F1** | 1 | 0 / 0 / 1 | +1,181 | +0.24 % | +1,181 | 0 |
| B non-expiry (new) | F2 | 7 | 0 / 0 / 7 | −8,753 | −1.75 % | −2,850 | −8,753 |
| A+B | none | 20 | 4 / 4 / 12 | −8,108 | −1.62 % | −5,342 | −20,680 |
| A+B | **F1** | 3 | 2 / 0 / 1 | **+15,411** | +3.08 % | +1,181 | 0 |

Loss cap −15 % instead of −20 %: for the straddle worse or equal in every group (15 Sep NIFTY fell 18.5 %
before reaching +30 %; A+B with F1 +₹4,896); for the strangle better in most groups (A+B unfiltered −₹16,938
vs −₹21,815) but still below the straddle. 1-strike OTM strangle: worse than the straddle in every group at
both caps (A+B with F1, −20 % cap: +₹2,429).

Reading: F1 kept both expiry winners on NIFTY and skipped all four expiry losers (3, 8, 10, 17 Sep,
−₹19,250 together), but also skipped 24 Sep SENSEX (+₹6,002). On the new sample it traded once. Evidence
from unseen days is therefore one trade; the forward test (A-008) decides.
