# Expiry day 15:20–15:30, second by second (own capture)

One CSV per expiry day, 601 rows (15:20:00–15:30:00, one per second; a second without a tick carries the last value).
Columns: `index_feed` (see the warning), `synthetic_spot` (ATM strike + call mid − put mid, put–call parity on the
expiring options), `futures` (near month), then for the expiring ATM ± 5 strikes (ATM taken from the index at 15:20)
each CE / PE `ltp`, `bid`, `ask`, `oi`.

| Day | Index | Expiry |
|---|---|---|
| 29 Sep, 6 Oct, 13 Oct, 19 Oct | NIFTY | same day |
| 1 Oct, 8 Oct, 15 Oct, 22 Oct | SENSEX | same day |

The 20 earlier zt-tiger-v2 tick days cannot be used: that archive ends at 15:15.

**Warning – the index feed freezes.** On every expiry day the index value in our capture stops changing at 15:15 and
jumps once at about 15:28–15:29 to the final close (29 Sep NIFTY 22,683.75 → 22,716.20). Futures and options keep
trading normally. Use `synthetic_spot` (or NIFTY futures; SENSEX futures barely trade) for anything in this window.

## Summary (synthetic spot; ATM = the 15:20 index-feed strike)

| Day | Index | Spot 15:20 → 15:30 | Range in the 10 min | ATM CE 15:20 / 15:25 / 15:30 | ATM PE 15:20 / 15:25 / 15:30 | Biggest 10-second move CE / PE |
|---|---|---|---|---|---|---|
| 29 Sep | NIFTY 22700 | 22,675.7 → 22,716.1 (+40) | 22,612.7–22,716.6 | 11.70 / 0.85 / 16.15 | 42.00 / 54.25 / 0.05 | 433 % / 314 % |
| 1 Oct | SENSEX 72000 | 71,969.1 → 71,909.9 (−59) | 71,741.1–72,005.3 | 51.45 / 39.85 / 0.10 | 82.40 / 59.15 / 90.25 | 130 % / 235 % |
| 6 Oct | NIFTY 22700 | 22,714.3 → 22,775.9 (+62) | 22,714.3–22,776.0 | 52.90 / 48.75 / 76.10 | 7.20 / 1.05 / 0.05 | 26 % / 100 % |
| 8 Oct | SENSEX 71500 | 71,417.0 → 71,592.9 (+176) | 71,391.7–71,609.2 | 37.90 / 21.25 / 92.90 | 119.80 / 93.15 / 0.15 | 89 % / 164 % |
| 13 Oct | NIFTY 22700 | 22,706.9 → 22,721.9 (+15) | 22,700.9–22,746.1 | 33.75 / 22.00 / 21.90 | 11.50 / 5.40 / 0.05 | 61 % / 196 % |
| 15 Oct | SENSEX 74000 | 74,037.8 → 74,315.2 (+277) | 73,953.5–74,316.1 | 80.00 / 170.50 / 314.90 | 42.75 / 11.30 / 0.05 | 88 % / 104 % |
| 19 Oct | NIFTY 22850 | 22,888.2 → 22,889.0 (+1) | 22,884.3–22,948.6 | 46.00 / 97.60 / 39.10 | 7.95 / 0.40 / 0.05 | 61 % / 200 % |
| 22 Oct | SENSEX 73300 | 73,350.7 → 73,249.6 (−101) | 73,037.7–73,421.6 | 115.75 / 84.55 / 0.15 | 62.70 / 119.95 / 50.45 | 127 % / 87 % |

What the numbers say (8 days, descriptive only):

- **Every option ends at its intrinsic value** (e.g. 15 Oct ATM 74000 CE 314.90 with the close at 74,315): the losing
  side ends at 0.05 and the winning side at (close − strike).
- **The last 10 minutes still move the index 0.1–0.5 %** (15 Oct SENSEX +277 points; 29 Sep NIFTY dipped to 22,613
  near 15:22 and then rose 100 points), so ATM premiums swing hard: 26–433 % within 10 seconds.
- **No direction bias in 8 days**: 5 up, 3 down, 1 almost flat.
- **ATM spreads stay tight** (0.05–4 ₹), so fills are not the obstacle; the size of the swings is.
- 19 Oct: the 15:20 index-feed ATM (22850) was one strike off the real spot (~22,888); the files hold ATM ± 5, so the
  nearer strike is included.
