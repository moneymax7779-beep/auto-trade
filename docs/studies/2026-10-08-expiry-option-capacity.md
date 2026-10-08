# Capacity of expiry-day ATM options for the trend rider (measurement, 8 Oct 2026; not a hypothesis test)

User, 8 Oct 20:50 IST: "run v7 alone and measure capacity for the liquidity cap". Own capture (GCP md.option_tick,
five-level books), the expiring index's ATM call and put (nearest 50 / 100 strike to the index), last quote of each
minute, 11:00–14:30 (the trend rider's window), on the four expiry days with own capture.

| day | index | ATM ask (median) | ask premium within 0.5 % of best (median / 10th pct) | all 5 levels (median / 10th) | premium traded per minute (median / 10th) |
|---|---|---|---|---|---|
| 29 Sep | NIFTY | ₹54.0 | ₹8.81L / ₹4.76L | ₹8.85L / ₹5.14L | ₹14.9 Cr / ₹8.0 Cr |
| 1 Oct | SENSEX | ₹167.0 | ₹1.71L / ₹0.92L | ₹1.71L / ₹0.92L | ₹14.5 Cr / ₹6.1 Cr |
| 6 Oct | NIFTY | ₹40.9 | ₹10.63L / ₹5.62L | ₹12.71L / ₹6.30L | ₹15.5 Cr / ₹8.0 Cr |
| 8 Oct | SENSEX | ₹144.8 | ₹1.95L / ₹0.95L | ₹1.95L / ₹0.96L | ₹13.5 Cr / ₹6.6 Cr |

Reading: the visible book is thin (SENSEX about ₹2L, NIFTY about ₹9–13L on the ask side) but it turns over hundreds of
times a minute (₹13–15 Cr traded per minute at one ATM strike). On 8 Oct live, single entry orders of ₹1.37–2.76L
went into SENSEX books showing ₹0.69–2.16L; the paper broker fills anything beyond the visible levels only 2 ticks
worse (costs v2 `depth_exhausted_extra_ticks: 2`), which is optimistic for orders larger than the book.
Proposed cap (not implemented): (1) slice each entry so no child order exceeds the ask premium within 0.5 % of the best
ask at that moment, each next slice priced on the next quotes; (2) a position's entry premium at most 2 % of the
option's premium traded in the previous minute (median ≈ ₹27–31L, 10th percentile ≈ ₹12–16L). At ₹10L equity rule 2
does not bind; rule 1 already does on SENSEX.

## A-062, risk v12 (liquidity cap): registered 8 Oct 21:25 IST, before any replay
User, 21:10 IST: "yes build and test the cap". risk v12 = v10 + (1) slicing of single-leg entries and adds to the ask
within 0.5 % of the best ask, the next slice on the next quote, stopping after the 10 s entry timeout or a 2 % run
of the ask; (2) at most 2 % of the option's premium traded in the last minute per entry or add. `paper-risk.v12.yaml`,
OrderManager `sendSlice` / `tradedPremiumLastMinute`.
Test: the live set (application.yml, 6 strategies), v10 vs v12, each day started from equity ₹9,97,219
(`replay-equity`, today's sizes), own capture 28, 29, 30 Sep, 1, 5, 6, 7, 8 Oct and zt-tiger-v2 22–25 Sep.
**PASS (mechanics) if:** every v12 trade that v10 also took bought ≥ 90 % of v10's quantity on ≥ 80 % of those trades,
and no v12 entry slice was larger than the ask within 0.5 % at its quote (checked against own capture). Reported, not
judged: the change in fill prices and in net. Going live is the user's decision: v12 makes paper fills more honest
and probably a little worse.
