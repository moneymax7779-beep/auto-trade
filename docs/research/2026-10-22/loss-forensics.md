# Loss forensics: live strategy set on 25 tick-replay days (2 Sep to 21 Oct 2026)

Written 22 Oct 2026. Everything here is **in-sample and descriptive**. These 25 days were used to choose many of the rules being examined, so no number below is an out-of-sample estimate. All P&L figures are net ₹ after costs, taken from `trade.position`.

## 0. Scope and headline numbers

- Sessions 10175 to 10271 (one per day, replay-as-live, paper-risk v17, features v12). There are 81 positions with quantity > 0 across 24 days. 21 Oct (session 10271) ran with 4,620 decisions but opened no position. The `ebs` straddle on 10 Sep is stored as 2 legs, so it counts as 2 of the 81.
- Total net is **+₹38,66,609**: 45 winners totalling +₹54.58L and 36 losers totalling −₹15.91L. Profit factor 3.43.
- The P&L is fat-tailed:
  - 6 `etr` winners made +₹27.17L, 70 % of all net profit.
  - The 10 worst trades lost −₹9.74L, 61 % of all gross losses.
  - 6 of 24 days lost money, −₹7.01L in total. These were 16 Oct −1.99L, 14 Oct −1.49L, 3 Sep −1.35L, 6 Oct −1.23L, 30 Sep −0.57L and 18 Sep −0.38L.

| strategy | n | win% | net ₹ | PF | net / premium deployed | gross losses ₹ |
|---|---|---|---|---|---|---|
| etr (expiry-trend-rider) | 14 | 43% | 22,04,420 | 5.30 | +53.8 % | −5,12,749 |
| mac (ma-cross) | 42 | 62% | 8,58,654 | 3.21 | +8.2 % | −3,89,021 |
| odm (opening-drive-momentum) | 10 | 80% | 6,30,130 | 4.16 | +27.9 % | −1,99,496 |
| ebs (expiry-breakout-straddle, 2 legs) | 2 | 50% | 1,51,752 | 1.83 | +30.6 % | −1,82,674 (CE leg) |
| odb (opening-drive) | 12 | 33% | 41,574 | 1.14 | +1.6 % | −2,87,447 |
| ecr (early-confirm-runner) | 1 | 0% | −19,921 | 0 | −8.1 % | −19,921 |

## 1. Top 10 findings, ranked by ₹ impact

All findings are in-sample. "Δ" is the change in net P&L if a rule had been applied to these same trades. Capital re-use and kill-switch interactions are not modelled.

1. **ETR pyramiding is the largest single P&L lever, in both directions.** Adds contributed **+₹15.83L on 6 winners** and **−₹2.57L on 5 losers**, a net of +₹13.26L.
   - The worst trade of the period was 3 Sep SENSEX PE, −₹1,98,193. Its 880-lot initial clip would have lost only −₹45,476.
   - On that trade the position went from 880 to 3,040 lots in **4 minutes** (11:06 to 11:10). The adds were at +4 %, +14 % and +22 % premium over the first fill.
   - It peaked at +27.7 % at +10 min. The index then V-reversed by −0.19 ADR by +30 min and the position exited on SWING_HIGH_RECLAIMED at −41.9 %.
   - On every losing runner, all adds were done within 1 to 4 minutes of the first fill. On winners the last add came 3 to 55 minutes after the first fill (median 14.5).
   - Caveat: this is partly endogenous, because losers exit early and so have no time to add later. It describes how the losses happened. It is not a usable filter.

2. **ETR winners gave back ₹15.1L of ₹42.3L peak open profit** (median give-back 35 % of the peak move).
   - Replacing the swing trail with "hold to 15:15 with only the 40 % premium stop" would have been **−₹3.42L worse** overall.
   - That result is unstable: +₹4.16L in Sep (the 15 Sep first entry would have become +₹4.29L) against −₹7.58L in Oct (1 Oct alone falls from +₹8.43L to +₹1.39L).
   - Conclusion: the give-back is the price of the swing trail. There is no evidence that a simpler exit is better.

3. **ODB losses are zero-buffer level stops whipsawed in the opening auction.** The 8 odb losers lost −₹2.87L. All 8 were level re-cross stops (STRUCTURE_PDH, PRIOR_ORH or PDL).
   - Median hold was **1 minute**, and 7 of 8 never had a minute close above breakeven.
   - After the stop, the index moved a median **+0.28 ADR in the trade's direction** within 60 min. The stopped contract's premium then peaked a median **+38.6 % above the exit price**. The best examples are 15 Oct 09:17 and 09:20 SENSEX CE, which went to +217 % and +187 % within 60 min.
   - Side split: odb CE went 1 win / 7 losses (−₹88.8K) and odb PE went 3 wins / 1 loss (+₹1.30L).
   - On premium deployed, odb made −6.4 % on non-expiry days (8 trades, −₹1.43L) and +45.9 % on expiry days (4 trades, +₹1.84L, where one 15 Oct trade made +₹1.88L).

4. **ODB "no gap toward the level" filter (in-sample, tiny).** Rule: skip an odb entry unless the open gapped toward the trade by more than 0.1 ADR.
   - It would remove 7 trades (1 win, 6 losses) for **Δ +₹2.41L**: +₹95K in Sep (1 day, 2 trades) and +₹1.46L in Oct (3 days, 5 trades).
   - The mechanism is plausible: without a gap, PDH/PDL sits inside the opening noise, and a stop placed exactly at the level gets hit within a minute.
   - Evidence is only **4 day-events** and needs fresh days.
   - Removing the level stop instead gives an inconclusive result. Keeping the re-entries it enabled gives +₹2.02L. Allowing only the first entry per day gives −₹88K.

5. **ODM 16 Oct, −₹1.99L, was a single correlated bet.** It was a NIFTY + SENSEX PE pair at 09:21 on a day that turned V-up.
   - Both legs went green first: NIFTY premium +19.6 % and SENSEX +6.5 % at +15 min.
   - Neither reached the +50 % trail-arming level. Both then rode down to the far session-extreme stop (−48.8 % and −32.0 % premium).
   - Only 2 odm losers exist, both on that day, so no generalisable filter can be drawn. The "far from PDL" rule removes exactly those two trades, which makes it overfitting by construction.
   - An early-cut exit is clearly wrong for odm: winners dip early, and cutting at −5 % to −10 % at +5/+10 min would have cost −₹4.4L to −₹5.1L.

6. **The same signal on both indices doubles the bet.** There were 10 NIFTY/SENSEX pairs with the same strategy and same side, entered no more than 5 min apart.
   - 8 of 10 pairs had the same outcome.
   - The losing pairs lost −₹3.04L combined (16 Oct odm −1.99L, 12 Oct mac −0.59L, 18 Sep 13:42 mac −0.42L, 18 Sep 10:33 mac −3.7K).
   - The effective number of independent bets is therefore well below 81.

7. **MAC losses are ordinary 1R stop-outs. No pre-entry context feature separates them from winners better than chance.** mac had 16 losers totalling −₹3.89L.
   - 10 of these were index swing STOPs: −₹2.96L in total, median premium −9.3 %, median hold 18.5 min.
   - The stops were not whipsaws. After a stop the index kept going against the trade (median net −0.05 ADR over the next 60 min). Holding the 10 stopped trades to the 60-min time exit, with only the 25 % premium stop, would have lost ₹4.44L instead of ₹2.96L (−₹1.48L). Dropping both the stop and the target on all 42 mac trades would have cost −₹1.04L.
   - A scan of 151 single-threshold rules on 15 pre-entry features found a best rule of +₹84,935 (prior-60-min move > 0.05 ADR). That is **below what label-shuffled P&L produces** (median best +₹65K, 95th percentile +₹1.46L; 32 % of shuffles beat it).
   - The strongest univariate feature is an "extended" prior hour (mom60: AUC 0.20, Cohen d −1.17), but the filter built from it flips sign in Oct at a nearby threshold.
   - The hand-written hypotheses all lose money once winners forgone are counted: wrong side of PDC −₹1.19L, against VWAP −₹3.86L, against OR −₹2.77L, counter-trend day type −₹1.73L.

8. **MAC on SENSEX is roughly breakeven; MAC on NIFTY carries the strategy.**
   - SENSEX: 18 trades, +₹17.8K. That is +2.6 % on premium in Sep and −7.7 % in Oct.
   - NIFTY: 24 trades, +₹8.41L. That is +12.0 % in Sep and +19.3 % in Oct.
   - The ordering holds in both halves. Removing SENSEX mac in ₹ terms is still mixed (Sep −₹93K, Oct +₹75K), so this is a sizing question, not a proven filter.

9. **Early-failure exit for ETR (in-sample, small).** Rule: exit at +10 min if the premium is still more than 5 % below the first fill.
   - It cuts 4 trades, all eventual losers, for **Δ +₹83K** (+₹8K Sep, +₹76K Oct).
   - For mac the same idea is threshold-fragile: −5 % at +15 min gives +₹68K (9 of 9 cut trades were losers), but −5 % at +10 min gives −₹1.59L.

10. **Losers are visible early after entry, but almost nothing visible at entry predicts them.**
    - **After entry** (all 79 directional trades), the path separates them clearly:
      - favourable index excursion in the first 30 min: median 0.22 ADR for winners, 0.05 for losers (AUC 0.86);
      - adverse excursion in the first 15 min: 0.04 against 0.11 ADR (AUC 0.16).
    - **At entry**, nearly everything is noise: VWAP-proxy side, opening-range side, PDH/PDL side, compression, ATM-straddle change, distance from the day's extreme, and VIX level or change all have AUC 0.39 to 0.63.
    - The best pre-entry feature is gap in the trade direction: median 0.14 ADR for winners and −0.02 for losers (AUC 0.63, d 0.39). This drives finding 4.
    - Contrary to the "late entries are bad" prior, entries after 13:30 had the best PF (7.72, 14 trades). No strategy entered in 09:45 to 10:30.

Smaller items:
- The 10 Sep `ebs` straddle CE leg (−₹1.83L) is one leg of a +₹1.52L straddle, so it is not a loss.
- The one `ecr` trade (−₹19.9K, INVALIDATED after 4 min) is n = 1.

## 2. Why the losers lost, strategy by strategy

- **odb** (−₹2.87L of losses).
  - The cause is breakouts through PDH/PRIOR_ORH in the first 10 minutes on days with no gap toward the level, with the stop exactly at the level.
  - The level was re-crossed within 1 to 2 minutes in 7 of 8 losses. On the 14 Oct NIFTY CE pair, premium was −7 % to −24 % within 2 minutes.
  - Losers were mostly CE (7 of 8), in two re-entry pairs: 30 Sep SENSEX −₹95K and 14 Oct NIFTY −₹1.49L.
  - Opening-range features are undefined here because entries come before 09:30.
- **mac** (−₹3.89L).
  - A normal 2:1 payoff system: 62 % win, average win ₹48.0K against average loss ₹24.3K.
  - Losers failed immediately. Median favourable index excursion in the first 30 min was 0.04 ADR, against 0.16 for winners (AUC 0.94). Median adverse excursion in the first 15 min was 0.11 ADR, against 0.03.
  - Their context at entry looked the same as the winners'.
- **etr** (−₹5.13L of losses against +₹27.17L of wins).
  - Most of the loss came from 3 Sep (−₹1.98L, adds into a V-reversal) and 6 Oct (two NIFTY CE trades, −₹1.23L, on a day that trended up and then stalled).
  - The three after-13:30 losers were small (−₹17.6K, −₹32.0K, −₹48.2K). Each had post-entry index MFE15 of at most 0.064 ADR as theta set in, against one +₹6.61L winner in the same time bucket.
  - 7 of 8 losers went green first (₹2.52L combined maximum open profit).
- **odm** (−₹1.99L). One day and one correlated pair, as described in finding 5.

## 3. Definitions and method

**Data**
- Index 1-min bars come from `hist.candle` (kind INDEX) up to 6 Oct, and are built from `md.index_tick` (first/max/min/last price per minute of `coalesce(exch_ts, recv_ts)`) from 7 Oct.
- The traded contract and the ATM CE/PE of the same expiry come from `hist.candle` OPTION bars up to 6 Oct, and from `md.option_tick` LTP minute bars after that.
- Fills come from `trade.orders` joined to `trade.order_event` with `last_quantity > 0`. They reconcile exactly to `position.quantity` and `average_cost` for all 81 positions.

**Entry spot and ADR**
- Entry spot is the close of the last index bar that started before the entry minute. For example, an entry at 11:06:00 uses the 11:05 bar.
- ADR is the mean (high − low) of the previous 5 sessions. It normalises every index distance.

**Day type**, from O/H/L/C, with R = H − L:
- trend_up if C − O ≥ 0.5R; trend_down if O − C ≥ 0.5R;
- otherwise V_up if O − L ≥ 0.5R and C − L ≥ 0.5R (sold off, then recovered);
- otherwise V_down if H − O ≥ 0.5R and H − C ≥ 0.5R;
- otherwise range.
- "So far" applies the same rule to the bars before entry, using the entry spot as C.

**Entry features**
- All directional features are signed so that + means in the trade's direction (CE = +1, PE = −1).
- `gap_adr_signed` = (open − previous close) / ADR × side.
- `from_open_adr` = (spot − open) / ADR × side.
- `vwap_dist_adr` uses a VWAP proxy, the mean of minute closes since 09:15. The index has no volume.
- `vs_OR`: the opening range is 09:15 to 09:29. It is "forming" before 09:30.
- `vs_PD` and `vs_PDC` are relative to the previous day's high, low and close.
- `dist_extreme_adr`: distance from the day's high (CE) or low (PE) so far.
- `mom15/30/60` = index move over the last 15, 30 or 60 min / ADR × side.
- `comp15` / `comp30` = range of the last 15 or 30 bars ÷ mean range of the complete 15-bar blocks since 09:15. Needs at least 30 bars.
- `straddle_chg15/30` = ATM straddle (strike nearest the entry spot, same expiry) % change over the last 15 or 30 min.
- `prem_runup15` = the traded contract's % change over the 15 min before entry.

**Post-entry features**
- MFE/MAE use index bar highs and lows over the 15, 30 and 60 bars from the entry minute.
- Premium MFE and "went green" use minute closes of the held contract against breakeven (average cost + costs / quantity).
- Post-exit measures use the next 60 bars.
- Expiry day means the nearest listed expiry of that index (taken from the option data in a 5-minute window at the open) equals the session date. This correctly gives NIFTY expiries on Tuesdays (and Mon 19 Oct) and SENSEX on Thursdays.
- Month halves: Sep = 2 to 30 Sep (12 days, 46 positions); Oct = 1 to 21 Oct (13 days, 35 positions).

**Key SQL** (read-only)
```sql
-- trades
select p.id, s.session_date, p.strategy_id, p.underlying, p.option_side, p.symbol, p.quantity, p.average_cost, p.average_exit,
       p.exit_reason, p.net, p.costs, p.opened_at at time zone 'Asia/Kolkata', p.closed_at at time zone 'Asia/Kolkata', p.stages
from trade.position p join trade.session s on s.id = p.session_id
where p.session_id in (10175, ..., 10271) and p.quantity > 0;
-- fills
select p.id, o.role, e.at, e.last_quantity, e.last_price
from trade.position p
join trade.orders o on o.session_id = p.session_id and o.symbol = p.symbol and o.strategy_id = p.strategy_id
                   and o.sent_at between p.opened_at - interval '2 s' and p.closed_at + interval '5 s'
join trade.order_event e on e.client_order_id = o.client_order_id and e.last_quantity > 0;
-- index minute bars from ticks (7-21 Oct)
select underlying, date_trunc('minute', coalesce(exch_ts, recv_ts)) m,
       (array_agg(price order by coalesce(exch_ts, recv_ts), src_seq))[1], max(price), min(price),
       (array_agg(price order by coalesce(exch_ts, recv_ts) desc, src_seq desc))[1]
from md.index_tick where session_date between '2026-09-28' and '2026-10-21' and underlying in ('NIFTY','SENSEX')
  and (coalesce(exch_ts, recv_ts) at time zone 'Asia/Kolkata')::time between '09:15' and '15:30' group by 1, 2;
-- option minute bars (per contract, after 6 Oct)
select date_trunc('minute', recv_ts), (array_agg(ltp order by recv_ts))[1], max(ltp), min(ltp), (array_agg(ltp order by recv_ts desc))[1]
from md.option_tick where session_date = :d and underlying = :u and expiry = :e and strike = :k and option_type = :t
  and recv_ts between :d 03:45Z and :d 10:00Z and ltp > 0 group by 1;
```

**Scripts** (all in this folder; Python 3 stdlib only, data read through `docker exec ... psql`)
- `extract.py` / `extract_opt.py`: trades, expiry calendar, option bars.
- `features.py`: produces `features.csv`, one row per trade with all features and paths.
- `summaries.py`, `contrast.py`, `filters.py` (rules, scan and permutation), `exits.py` (give-back, post-exit, counterfactuals), `early.py`, `extra.py` (return on premium, correlated pairs, pyramiding), `adds.py`, `vix.py`, `paths.py`, `fillcheck.py`, `cmp_sources.py`.

## 4. Caveats

- **In-sample:** the 25 days chose many of these strategies' rules (mac, odm, etr and odb versions were all registered or adjusted with these days visible).
- **Small samples:** every per-strategy filter rests on 2 to 12 trades and often on 1 to 4 days. A rule that removes one day's trades is not evidence.
- **Multiple comparisons:** about 23 hand-written rules plus 151 scanned rules were examined. The permutation check shows the best mac rule is at chance level, and nothing else was corrected for multiplicity.
- **Counterfactuals are simplified:**
  - They use LTP minute bars, not bid/ask.
  - Premium stops are assumed filled at their level.
  - Runner adds are treated as if bought at entry.
  - Re-entries and capital sharing are not re-simulated.
  - The kill switch (₹1.1L daily loss) and free-capital effects of removing a trade are not modelled. Removing a loser can free capital for a later winner, or remove a winner's capital.
- **Give-back and MFE** use minute closes of LTP. Real exits happen at the bid, so true MFE is lower.
- **Index proxies:** VWAP is a mean-of-closes proxy, since the index has no volume. PDC is the 15:29 bar close, not the official close.
- **Day types** use one rule with 0.5R thresholds and are not validated. Full-day type is not knowable at entry.
- **Fat tails:** the P&L is dominated by a handful of etr expiry-day winners, so any rule touching etr is judged on 1 to 3 trades.

## 5. Data issues found

1. **09:15 open differs between sources.** On the 6 overlapping days (28 Sep to 6 Oct), the tick-built 09:15 bar open differs from the `hist.candle` open by up to 15 pts on NIFTY and 18 pts on SENSEX. Minute closes agree closely (mean absolute difference ≤ 0.21 pts, max 3.0). Gap and OR features on 7 to 21 Oct therefore carry about ±0.03 ADR uncertainty.
2. **Extra 15:30 bar.** NIFTY `md.index_tick` produces a 15:30 bar (376 bars a day) and SENSEX does not. It was dropped.
3. **Missing option minutes.** `md.option_tick` has only 365 of 375 minutes for SENSEX 74000 CE 15 Oct, the etr +₹2.29L trade.
4. **One entry fill outside the LTP range.** Of 408 fills, only 1 entry is outside the contract's LTP minute range by more than 0.5 %: 8 Oct etr SENSEX 71500 PE at 14:01, filled at 114.69 against a minute high of 109.9 (+4.4 %). Its 15:15 exit at 102.0 is 0.5 % below the minute low.
5. **Two unfilled entries.** Two positions with quantity 0 (ENTRY_NOT_FILLED) are excluded: 8 Sep odb SENSEX PE and 17 Sep ecr SENSEX CE.
6. **VIX coverage.** `hist.candle` INDIA_VIX (kind VIX) ends on 6 Oct, so VIX context covers only 51 of the trades. It shows no signal: AUC 0.51 to 0.54.
7. **No stop distance recorded.** `trade.decision` does not store mac's swing-stop distance. Only the `held` conditions are recorded, so stop-distance analysis was not possible from the decision log.
8. **21 Oct traded nothing.** 21 Oct opened no positions despite a full decision log. This is not a data fault, but the day is in the 25-day count.


## Appendix A. Summary tables

### Summary tables (81 positions, ebs straddle = 2 legs)

#### By strategy

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| ebs | 2 | 1 | 50% | 151,752 | 334,426 | -182,674 | 1.83 | -182,674 |
| ecr | 1 | 0 | 0% | -19,921 | 0 | -19,921 | 0.00 | -19,921 |
| etr | 14 | 6 | 43% | 2,204,420 | 452,862 | -64,094 | 5.30 | -198,193 |
| mac | 42 | 26 | 62% | 858,654 | 47,988 | -24,314 | 3.21 | -50,041 |
| odb | 12 | 4 | 33% | 41,574 | 82,255 | -35,931 | 1.14 | -91,359 |
| odm | 10 | 8 | 80% | 630,130 | 103,703 | -99,748 | 4.16 | -122,264 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### By time-of-day bucket (entry)

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| 09:15-09:45 | 22 | 12 | 55% | 671,704 | 96,554 | -48,694 | 2.38 | -122,264 |
| 10:30-12:00 | 25 | 13 | 52% | 484,549 | 77,301 | -43,364 | 1.93 | -198,193 |
| 12:00-13:30 | 20 | 11 | 55% | 1,769,493 | 201,225 | -49,332 | 4.99 | -182,674 |
| 13:30-15:30 | 14 | 9 | 64% | 940,863 | 120,097 | -28,003 | 7.72 | -48,183 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### By exit reason

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| FLAT_BY | 9 | 8 | 89% | 1,749,842 | 222,734 | -32,033 | 55.63 | -32,033 |
| INVALIDATED | 1 | 0 | 0% | -19,921 | 0 | -19,921 | 0.00 | -19,921 |
| PREMIUM_STOP | 2 | 0 | 0% | -98,898 | 0 | -49,449 | 0.00 | -50,715 |
| STOP | 12 | 0 | 0% | -495,203 | 0 | -41,267 | 0.00 | -122,264 |
| STRUCTURE_PDH | 4 | 0 | 0% | -184,311 | 0 | -46,078 | 0.00 | -91,359 |
| STRUCTURE_PDL | 1 | 0 | 0% | -10,551 | 0 | -10,551 | 0.00 | -10,551 |
| STRUCTURE_PRIOR_ORH | 3 | 0 | 0% | -92,585 | 0 | -30,862 | 0.00 | -67,819 |
| SWING_HIGH_RECLAIMED | 6 | 3 | 50% | 1,027,419 | 432,520 | -90,047 | 4.80 | -198,193 |
| SWING_LOW_LOST | 3 | 1 | 33% | 117,127 | 228,805 | -55,839 | 2.05 | -72,101 |
| TARGET | 16 | 16 | 100% | 1,052,329 | 65,771 | 0 | inf | 13,185 |
| TARGET_30 | 2 | 1 | 50% | 151,752 | 334,426 | -182,674 | 1.83 | -182,674 |
| TIME_10:00 | 4 | 4 | 100% | 329,021 | 82,255 | 0 | inf | 21,052 |
| TIME_60 | 15 | 9 | 60% | 62,936 | 17,361 | -15,552 | 1.67 | -36,249 |
| TRAIL_15 | 3 | 3 | 100% | 277,652 | 92,551 | 0 | inf | 60,625 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### By exit class

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| premium stop | 14 | 0 | 0% | -594,101 | 0 | -42,436 | 0.00 | -122,264 |
| structure exit | 18 | 4 | 22% | 837,178 | 381,591 | -49,228 | 2.21 | -198,193 |
| target/trail | 21 | 20 | 95% | 1,481,733 | 83,220 | -182,674 | 9.11 | -182,674 |
| time/flat | 28 | 21 | 75% | 2,141,799 | 107,959 | -17,907 | 18.09 | -36,249 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### Underlying expiry day (index expires today) vs not

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| expiry day | 21 | 9 | 43% | 2,520,642 | 362,306 | -61,676 | 4.41 | -198,193 |
| non-expiry | 60 | 36 | 60% | 1,345,967 | 61,032 | -35,467 | 2.58 | -122,264 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### Contract 0DTE vs longer

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| 0DTE | 21 | 9 | 43% | 2,520,642 | 362,306 | -61,676 | 4.41 | -198,193 |
| 1+DTE | 60 | 36 | 60% | 1,345,967 | 61,032 | -35,467 | 2.58 | -122,264 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### Strategy x expiry day

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| ebs / exp | 2 | 1 | 50% | 151,752 | 334,426 | -182,674 | 1.83 | -182,674 |
| ecr / exp | 1 | 0 | 0% | -19,921 | 0 | -19,921 | 0.00 | -19,921 |
| etr / exp | 14 | 6 | 43% | 2,204,420 | 452,862 | -64,094 | 5.30 | -198,193 |
| mac / non-exp | 42 | 26 | 62% | 858,654 | 47,988 | -24,314 | 3.21 | -50,041 |
| odb / exp | 4 | 2 | 50% | 184,391 | 104,578 | -12,383 | 8.45 | -17,769 |
| odb / non-exp | 8 | 2 | 25% | -142,817 | 59,932 | -43,780 | 0.46 | -91,359 |
| odm / non-exp | 10 | 8 | 80% | 630,130 | 103,703 | -99,748 | 4.16 | -122,264 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### Strategy x time bucket

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| ebs 12:00-13:30 | 2 | 1 | 50% | 151,752 | 334,426 | -182,674 | 1.83 | -182,674 |
| ecr 10:30-12:00 | 1 | 0 | 0% | -19,921 | 0 | -19,921 | 0.00 | -19,921 |
| etr 10:30-12:00 | 5 | 2 | 40% | 300,318 | 312,468 | -108,206 | 1.93 | -198,193 |
| etr 12:00-13:30 | 5 | 3 | 60% | 1,341,047 | 477,113 | -45,146 | 15.85 | -50,715 |
| etr 13:30-15:30 | 4 | 1 | 25% | 563,055 | 660,895 | -32,613 | 6.75 | -48,183 |
| mac 10:30-12:00 | 19 | 11 | 58% | 204,152 | 34,544 | -21,978 | 2.16 | -50,041 |
| mac 12:00-13:30 | 13 | 7 | 54% | 276,694 | 63,959 | -28,503 | 2.62 | -41,520 |
| mac 13:30-15:30 | 10 | 8 | 80% | 377,808 | 52,498 | -21,087 | 9.96 | -22,675 |
| odb 09:15-09:45 | 12 | 4 | 33% | 41,574 | 82,255 | -35,931 | 1.14 | -91,359 |
| odm 09:15-09:45 | 10 | 8 | 80% | 630,130 | 103,703 | -99,748 | 4.16 | -122,264 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### By month half

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| Oct | 35 | 18 | 51% | 2,679,344 | 189,117 | -42,633 | 4.70 | -122,264 |
| Sep | 46 | 27 | 59% | 1,187,265 | 76,067 | -45,608 | 2.37 | -198,193 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### Strategy x month

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| ebs Sep | 2 | 1 | 50% | 151,752 | 334,426 | -182,674 | 1.83 | -182,674 |
| ecr Sep | 1 | 0 | 0% | -19,921 | 0 | -19,921 | 0.00 | -19,921 |
| etr Oct | 9 | 5 | 56% | 1,984,227 | 437,452 | -50,758 | 10.77 | -72,101 |
| etr Sep | 5 | 1 | 20% | 220,193 | 529,910 | -77,429 | 1.71 | -198,193 |
| mac Oct | 11 | 6 | 55% | 257,622 | 64,598 | -25,993 | 2.98 | -50,041 |
| mac Sep | 31 | 20 | 65% | 601,032 | 43,004 | -23,550 | 3.32 | -41,520 |
| odb Oct | 8 | 2 | 25% | 17,514 | 104,892 | -32,045 | 1.09 | -91,359 |
| odb Sep | 4 | 2 | 50% | 24,060 | 59,619 | -47,589 | 1.25 | -67,819 |
| odm Oct | 7 | 5 | 71% | 419,981 | 123,895 | -99,748 | 3.11 | -122,264 |
| odm Sep | 3 | 3 | 100% | 210,149 | 70,050 | 0 | inf | 37,853 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### By day

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| 2026-09-02 | 2 | 2 | 100% | 20,692 | 10,346 | 0 | inf | 5,905 |
| 2026-09-03 | 2 | 1 | 50% | -134,676 | 63,517 | -198,193 | 0.32 | -198,193 |
| 2026-09-07 | 3 | 2 | 67% | 23,444 | 23,856 | -24,268 | 1.97 | -24,268 |
| 2026-09-08 | 3 | 2 | 67% | 5,828 | 11,726 | -17,624 | 1.33 | -17,624 |
| 2026-09-09 | 5 | 3 | 60% | 106,266 | 58,329 | -34,360 | 2.55 | -36,249 |
| 2026-09-10 | 5 | 2 | 40% | 114,598 | 169,748 | -74,966 | 1.51 | -182,674 |
| 2026-09-15 | 3 | 1 | 33% | 434,067 | 529,910 | -47,922 | 5.53 | -54,323 |
| 2026-09-17 | 5 | 4 | 80% | 240,362 | 69,985 | -39,577 | 7.07 | -39,577 |
| 2026-09-18 | 6 | 2 | 33% | -37,807 | 6,292 | -12,598 | 0.25 | -22,675 |
| 2026-09-28 | 6 | 4 | 67% | 317,010 | 92,215 | -25,926 | 7.11 | -29,745 |
| 2026-09-29 | 3 | 3 | 100% | 154,863 | 51,621 | 0 | inf | 39,096 |
| 2026-09-30 | 3 | 1 | 33% | -57,382 | 37,796 | -47,589 | 0.40 | -67,819 |
| 2026-10-01 | 3 | 2 | 67% | 925,045 | 464,742 | -4,439 | 209.39 | -4,439 |
| 2026-10-05 | 2 | 1 | 50% | 102,982 | 119,773 | -16,791 | 7.13 | -16,791 |
| 2026-10-06 | 2 | 0 | 0% | -122,816 | 0 | -61,408 | 0.00 | -72,101 |
| 2026-10-07 | 1 | 1 | 100% | 102,603 | 102,603 | 0 | inf | 102,603 |
| 2026-10-08 | 4 | 2 | 50% | 375,224 | 208,904 | -21,292 | 9.81 | -32,033 |
| 2026-10-09 | 3 | 3 | 100% | 159,352 | 53,117 | 0 | inf | 11,087 |
| 2026-10-12 | 4 | 2 | 50% | 321,313 | 190,004 | -29,348 | 6.47 | -39,164 |
| 2026-10-13 | 3 | 2 | 67% | 711,738 | 380,890 | -50,041 | 15.22 | -50,041 |
| 2026-10-14 | 2 | 0 | 0% | -149,155 | 0 | -74,578 | 0.00 | -91,359 |
| 2026-10-15 | 5 | 3 | 60% | 428,078 | 150,948 | -12,383 | 18.28 | -17,769 |
| 2026-10-16 | 2 | 0 | 0% | -199,496 | 0 | -99,748 | 0.00 | -122,264 |
| 2026-10-19 | 4 | 2 | 50% | 24,476 | 40,228 | -27,990 | 1.44 | -48,183 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### By full-day type (index of the trade)

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| V_down | 8 | 5 | 62% | 182,980 | 63,547 | -44,918 | 2.36 | -67,819 |
| V_up | 12 | 8 | 67% | 220,671 | 48,309 | -41,450 | 2.33 | -122,264 |
| range | 7 | 2 | 29% | 595,959 | 422,432 | -49,781 | 3.39 | -91,359 |
| trend_down | 38 | 21 | 55% | 1,558,816 | 104,783 | -37,743 | 3.43 | -198,193 |
| trend_up | 16 | 9 | 56% | 1,308,183 | 189,822 | -57,174 | 4.27 | -182,674 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### By day-type-so-far at entry

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| V_down | 7 | 3 | 43% | 689,698 | 311,518 | -61,214 | 3.82 | -198,193 |
| V_up | 9 | 5 | 56% | 127,262 | 63,337 | -47,356 | 1.67 | -91,359 |
| range | 14 | 8 | 57% | 383,839 | 84,817 | -49,117 | 2.30 | -182,674 |
| trend_down | 33 | 18 | 55% | 1,395,117 | 109,728 | -38,666 | 3.41 | -122,264 |
| trend_up | 18 | 11 | 61% | 1,270,693 | 141,185 | -40,334 | 5.50 | -72,101 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

#### By underlying

| group | n | wins | win% | net ₹ | avg win | avg loss | PF | worst |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 42 | 26 | 62% | 2,156,199 | 108,304 | -41,232 | 4.27 | -122,264 |
| SENSEX | 39 | 19 | 49% | 1,710,410 | 139,053 | -46,580 | 2.84 | -198,193 |
| **all** | 81 | 45 | 56% | 3,866,609 | 121,287 | -44,203 | 3.43 | -198,193 |

## Appendix B. Winners vs losers: entry and post-entry features

AUC = probability that a random winner has a higher value than a random loser (0.5 = no separation). mfe/mae/rest_of_day are post-entry, not usable as filters.

#### All directional trades (ebs excluded) (W=44, L=35)

| feature | nW | nL | mean W | mean L | median W | median L | Cohen d | AUC(W>L) |
|---|---|---|---|---|---|---|---|---|
| gap_adr_signed | 44 | 35 | 0.14 | -0.02 | 0.14 | -0.02 | 0.39 | 0.63 |
| from_open_adr | 44 | 35 | 0.23 | 0.15 | 0.21 | 0.17 | 0.13 | 0.54 |
| vwap_dist_adr | 44 | 35 | 0.07 | 0.10 | 0.07 | 0.07 | -0.21 | 0.46 |
| dist_PDlevel_adr | 44 | 35 | -0.24 | -0.28 | -0.21 | -0.05 | 0.05 | 0.52 |
| dist_extreme_adr | 44 | 35 | 0.24 | 0.25 | 0.15 | 0.06 | -0.01 | 0.56 |
| mom15_adr | 32 | 25 | 0.09 | 0.10 | 0.08 | 0.08 | -0.11 | 0.47 |
| mom30_adr | 32 | 25 | 0.11 | 0.10 | 0.11 | 0.10 | 0.19 | 0.54 |
| mom60_adr | 32 | 25 | 0.06 | 0.14 | 0.03 | 0.11 | -0.72 | 0.27 |
| comp15 | 32 | 25 | 0.74 | 0.79 | 0.73 | 0.78 | -0.23 | 0.45 |
| comp30 | 32 | 25 | 0.89 | 1.02 | 0.89 | 0.94 | -0.49 | 0.39 |
| range_sofar_adr | 44 | 35 | 0.73 | 0.70 | 0.67 | 0.52 | 0.07 | 0.58 |
| straddle_chg15_pct | 32 | 25 | -2.16 | -2.65 | -0.95 | -2.00 | 0.15 | 0.55 |
| straddle_chg30_pct | 32 | 25 | -3.58 | -3.84 | -1.65 | -2.70 | 0.05 | 0.58 |
| straddle_pct_spot | 44 | 35 | 0.94 | 0.86 | 1.05 | 0.88 | 0.24 | 0.57 |
| prem_runup15_pct | 32 | 25 | 14.26 | 15.73 | 6.50 | 6.80 | -0.08 | 0.44 |
| fill_vs_prevclose_pct | 44 | 35 | -0.14 | -0.22 | 0.00 | 0.00 | 0.09 | 0.55 |
| mfe15_adr | 44 | 35 | 0.16 | 0.06 | 0.15 | 0.05 | 1.17 | 0.81 |
| mae15_adr | 44 | 35 | 0.06 | 0.13 | 0.04 | 0.11 | -0.83 | 0.16 |
| mfe30_adr | 44 | 35 | 0.25 | 0.08 | 0.22 | 0.05 | 1.35 | 0.86 |
| mae30_adr | 44 | 35 | 0.06 | 0.16 | 0.04 | 0.14 | -1.14 | 0.13 |
| mfe60_adr | 44 | 35 | 0.36 | 0.13 | 0.34 | 0.08 | 1.24 | 0.87 |
| mae60_adr | 44 | 35 | 0.07 | 0.22 | 0.04 | 0.17 | -1.34 | 0.11 |
| rest_of_day_adr | 44 | 35 | 0.35 | 0.01 | 0.32 | -0.09 | 0.66 | 0.70 |
| day_range_adr | 44 | 35 | 1.18 | 1.16 | 1.01 | 1.01 | 0.03 | 0.55 |

#### mac (W=26, L=16)

| feature | nW | nL | mean W | mean L | median W | median L | Cohen d | AUC(W>L) |
|---|---|---|---|---|---|---|---|---|
| gap_adr_signed | 26 | 16 | 0.23 | 0.04 | 0.27 | 0.04 | 0.41 | 0.63 |
| from_open_adr | 26 | 16 | 0.04 | -0.19 | 0.05 | -0.06 | 0.36 | 0.60 |
| vwap_dist_adr | 26 | 16 | -0.00 | 0.01 | 0.04 | 0.05 | -0.11 | 0.48 |
| dist_PDlevel_adr | 26 | 16 | -0.35 | -0.56 | -0.36 | -0.62 | 0.23 | 0.58 |
| dist_extreme_adr | 26 | 16 | 0.39 | 0.51 | 0.34 | 0.22 | -0.33 | 0.49 |
| mom15_adr | 26 | 16 | 0.08 | 0.07 | 0.06 | 0.07 | 0.03 | 0.50 |
| mom30_adr | 26 | 16 | 0.09 | 0.07 | 0.08 | 0.09 | 0.27 | 0.51 |
| mom60_adr | 26 | 16 | 0.03 | 0.09 | 0.01 | 0.07 | -1.17 | 0.20 |
| comp15 | 26 | 16 | 0.69 | 0.73 | 0.70 | 0.74 | -0.23 | 0.44 |
| comp30 | 26 | 16 | 0.83 | 0.94 | 0.85 | 0.93 | -0.59 | 0.35 |
| range_sofar_adr | 26 | 16 | 0.79 | 0.85 | 0.75 | 0.80 | -0.17 | 0.48 |
| straddle_chg15_pct | 26 | 16 | -1.05 | -1.08 | -0.65 | -0.80 | 0.02 | 0.49 |
| straddle_chg30_pct | 26 | 16 | -1.56 | -1.84 | -1.15 | -1.60 | 0.20 | 0.57 |
| straddle_pct_spot | 26 | 16 | 1.02 | 0.98 | 1.06 | 0.98 | 0.16 | 0.55 |
| prem_runup15_pct | 26 | 16 | 6.67 | 5.56 | 5.15 | 5.10 | 0.22 | 0.51 |
| fill_vs_prevclose_pct | 26 | 16 | 0.06 | 0.06 | 0.05 | 0.10 | 0.00 | 0.50 |
| mfe15_adr | 26 | 16 | 0.14 | 0.04 | 0.12 | 0.04 | 1.38 | 0.85 |
| mae15_adr | 26 | 16 | 0.04 | 0.12 | 0.03 | 0.11 | -1.63 | 0.11 |
| mfe30_adr | 26 | 16 | 0.22 | 0.04 | 0.16 | 0.04 | 1.62 | 0.94 |
| mae30_adr | 26 | 16 | 0.05 | 0.17 | 0.03 | 0.16 | -1.96 | 0.07 |
| mfe60_adr | 26 | 16 | 0.34 | 0.06 | 0.31 | 0.06 | 1.48 | 0.95 |
| mae60_adr | 26 | 16 | 0.06 | 0.22 | 0.03 | 0.23 | -1.85 | 0.06 |
| rest_of_day_adr | 26 | 16 | 0.18 | -0.11 | 0.16 | -0.05 | 0.67 | 0.67 |
| day_range_adr | 26 | 16 | 1.00 | 1.21 | 0.95 | 1.01 | -0.44 | 0.42 |

#### etr (W=6, L=8)

| feature | nW | nL | mean W | mean L | median W | median L | Cohen d | AUC(W>L) |
|---|---|---|---|---|---|---|---|---|
| gap_adr_signed | 6 | 8 | -0.11 | -0.14 | 0.01 | -0.03 | 0.07 | 0.54 |
| from_open_adr | 6 | 8 | 0.81 | 0.79 | 0.78 | 0.71 | 0.05 | 0.53 |
| vwap_dist_adr | 6 | 8 | 0.30 | 0.33 | 0.30 | 0.29 | -0.29 | 0.48 |
| dist_PDlevel_adr | 6 | 8 | 0.11 | 0.16 | 0.08 | 0.24 | -0.07 | 0.46 |
| dist_extreme_adr | 6 | 8 | 0.01 | 0.01 | 0.01 | 0.01 | -0.35 | 0.42 |
| mom15_adr | 6 | 8 | 0.15 | 0.13 | 0.15 | 0.12 | 0.35 | 0.64 |
| mom30_adr | 6 | 8 | 0.19 | 0.14 | 0.21 | 0.13 | 0.54 | 0.71 |
| mom60_adr | 6 | 8 | 0.23 | 0.24 | 0.20 | 0.18 | -0.11 | 0.55 |
| comp15 | 6 | 8 | 0.95 | 0.84 | 0.95 | 0.84 | 0.49 | 0.64 |
| comp30 | 6 | 8 | 1.18 | 1.16 | 1.10 | 1.07 | 0.08 | 0.62 |
| range_sofar_adr | 6 | 8 | 1.00 | 0.93 | 0.91 | 0.78 | 0.15 | 0.60 |
| straddle_chg15_pct | 6 | 8 | -6.97 | -5.17 | -6.00 | -5.40 | -0.40 | 0.41 |
| straddle_chg30_pct | 6 | 8 | -12.35 | -8.01 | -10.40 | -6.55 | -0.82 | 0.31 |
| straddle_pct_spot | 6 | 8 | 0.42 | 0.39 | 0.43 | 0.39 | 0.41 | 0.65 |
| prem_runup15_pct | 6 | 8 | 47.18 | 35.46 | 50.25 | 29.20 | 0.76 | 0.71 |
| fill_vs_prevclose_pct | 6 | 8 | -0.35 | -0.15 | -0.10 | 0.00 | -0.14 | 0.39 |
| mfe15_adr | 6 | 8 | 0.13 | 0.06 | 0.10 | 0.05 | 1.03 | 0.81 |
| mae15_adr | 6 | 8 | 0.05 | 0.09 | 0.05 | 0.08 | -0.89 | 0.21 |
| mfe30_adr | 6 | 8 | 0.22 | 0.07 | 0.19 | 0.05 | 1.20 | 0.83 |
| mae30_adr | 6 | 8 | 0.06 | 0.13 | 0.06 | 0.12 | -1.45 | 0.12 |
| mfe60_adr | 6 | 8 | 0.38 | 0.08 | 0.31 | 0.09 | 1.59 | 0.96 |
| mae60_adr | 6 | 8 | 0.06 | 0.15 | 0.06 | 0.13 | -2.12 | 0.00 |
| rest_of_day_adr | 6 | 8 | 0.53 | 0.28 | 0.42 | 0.07 | 0.48 | 0.73 |
| day_range_adr | 6 | 8 | 1.74 | 1.32 | 1.61 | 0.97 | 0.53 | 0.76 |

#### odb (W=4, L=8)

| feature | nW | nL | mean W | mean L | median W | median L | Cohen d | AUC(W>L) |
|---|---|---|---|---|---|---|---|---|
| gap_adr_signed | 4 | 8 | 0.23 | 0.01 | 0.21 | -0.01 | 1.63 | 0.89 |
| from_open_adr | 4 | 8 | 0.31 | 0.18 | 0.27 | 0.18 | 0.93 | 0.73 |
| vwap_dist_adr | 4 | 8 | 0.01 | 0.08 | -0.00 | 0.08 | -1.52 | 0.11 |
| dist_PDlevel_adr | 4 | 8 | 0.14 | 0.01 | 0.14 | -0.01 | 1.24 | 0.72 |
| dist_extreme_adr | 4 | 8 | 0.06 | 0.03 | 0.06 | 0.02 | 0.77 | 0.69 |
| range_sofar_adr | 4 | 8 | 0.43 | 0.29 | 0.41 | 0.28 | 1.32 | 0.78 |
| straddle_pct_spot | 4 | 8 | 0.77 | 1.02 | 0.71 | 1.01 | -0.77 | 0.27 |
| fill_vs_prevclose_pct | 4 | 8 | 0.10 | -0.76 | 0.30 | -0.50 | 0.75 | 0.75 |
| mfe15_adr | 4 | 8 | 0.27 | 0.10 | 0.23 | 0.11 | 1.78 | 0.91 |
| mae15_adr | 4 | 8 | 0.03 | 0.21 | 0.02 | 0.15 | -1.30 | 0.00 |
| mfe30_adr | 4 | 8 | 0.41 | 0.15 | 0.39 | 0.15 | 1.75 | 0.88 |
| mae30_adr | 4 | 8 | 0.03 | 0.21 | 0.02 | 0.15 | -1.30 | 0.00 |
| mfe60_adr | 4 | 8 | 0.49 | 0.29 | 0.50 | 0.33 | 0.89 | 0.75 |
| mae60_adr | 4 | 8 | 0.06 | 0.21 | 0.02 | 0.15 | -1.04 | 0.19 |
| rest_of_day_adr | 4 | 8 | 0.97 | 0.15 | 1.10 | -0.28 | 1.13 | 0.80 |
| day_range_adr | 4 | 8 | 1.45 | 1.08 | 1.50 | 1.05 | 0.96 | 0.73 |

#### odm (W=8, L=2)

| feature | nW | nL | mean W | mean L | median W | median L | Cohen d | AUC(W>L) |
|---|---|---|---|---|---|---|---|---|
| gap_adr_signed | 8 | 2 | 0.01 | -0.12 | 0.01 | -0.12 | 0.56 | 0.69 |
| from_open_adr | 8 | 2 | 0.37 | 0.21 | 0.31 | 0.21 | 0.76 | 0.88 |
| vwap_dist_adr | 8 | 2 | 0.17 | 0.04 | 0.16 | 0.04 | 1.53 | 1.00 |
| dist_PDlevel_adr | 8 | 2 | -0.34 | -1.08 | -0.55 | -1.08 | 1.40 | 1.00 |
| dist_extreme_adr | 8 | 2 | 0.03 | 0.07 | 0.03 | 0.07 | -1.05 | 0.16 |
| range_sofar_adr | 8 | 2 | 0.51 | 0.35 | 0.48 | 0.35 | 0.85 | 0.75 |
| straddle_pct_spot | 8 | 2 | 1.15 | 1.30 | 1.17 | 1.30 | -0.92 | 0.44 |
| fill_vs_prevclose_pct | 8 | 2 | -0.73 | -0.45 | -0.20 | -0.45 | -0.17 | 0.59 |
| mfe15_adr | 8 | 2 | 0.20 | 0.13 | 0.21 | 0.13 | 0.66 | 0.75 |
| mae15_adr | 8 | 2 | 0.14 | 0.07 | 0.09 | 0.07 | 0.60 | 0.50 |
| mfe30_adr | 8 | 2 | 0.32 | 0.16 | 0.30 | 0.16 | 1.16 | 0.88 |
| mae30_adr | 8 | 2 | 0.14 | 0.07 | 0.09 | 0.07 | 0.60 | 0.50 |
| mfe60_adr | 8 | 2 | 0.37 | 0.16 | 0.41 | 0.16 | 1.43 | 0.94 |
| mae60_adr | 8 | 2 | 0.14 | 0.52 | 0.09 | 0.52 | -3.00 | 0.00 |
| rest_of_day_adr | 8 | 2 | 0.48 | -0.51 | 0.54 | -0.51 | 2.76 | 1.00 |
| day_range_adr | 8 | 2 | 1.21 | 0.69 | 1.18 | 0.69 | 1.62 | 1.00 |

#### Categorical context, all directional trades

| feature | value | n | wins | win% | net ₹ | losses ₹ |
|---|---|---|---|---|---|---|
| vs_vwap | against | 20 | 12 | 60% | 516,269 | -199,315 |
| vs_vwap | with | 59 | 32 | 54% | 3,198,588 | -1,209,319 |
| vs_OR | against | 14 | 8 | 57% | 276,977 | -196,631 |
| vs_OR | forming | 21 | 11 | 52% | 555,561 | -486,943 |
| vs_OR | inside | 21 | 13 | 62% | 467,448 | -145,854 |
| vs_OR | with | 23 | 12 | 52% | 2,414,871 | -579,206 |
| vs_PD | against | 11 | 7 | 64% | 58,021 | -277,039 |
| vs_PD | inside | 38 | 20 | 53% | 1,892,416 | -671,301 |
| vs_PD | with | 30 | 17 | 57% | 1,764,420 | -460,294 |
| vs_PDC | against | 23 | 10 | 43% | 159,937 | -549,253 |
| vs_PDC | with | 56 | 34 | 61% | 3,554,920 | -859,381 |
| daytype_sofar | V_down | 7 | 3 | 43% | 689,698 | -244,857 |
| daytype_sofar | V_up | 9 | 5 | 56% | 127,262 | -189,424 |
| daytype_sofar | range | 12 | 7 | 58% | 232,087 | -112,026 |
| daytype_sofar | trend_down | 33 | 18 | 55% | 1,395,117 | -579,990 |
| daytype_sofar | trend_up | 18 | 11 | 61% | 1,270,693 | -282,337 |
| daytype_full | V_down | 8 | 5 | 62% | 182,980 | -134,755 |
| daytype_full | V_up | 12 | 8 | 67% | 220,671 | -165,798 |
| daytype_full | range | 7 | 2 | 29% | 595,959 | -248,906 |
| daytype_full | trend_down | 38 | 21 | 55% | 1,558,816 | -641,631 |
| daytype_full | trend_up | 14 | 8 | 57% | 1,156,431 | -217,544 |
| side | CE | 35 | 17 | 49% | 1,120,236 | -643,675 |
| side | PE | 44 | 27 | 61% | 2,594,621 | -764,959 |
| und | NIFTY | 42 | 26 | 62% | 2,156,199 | -659,716 |
| und | SENSEX | 37 | 18 | 49% | 1,558,658 | -748,918 |
| tod | 09:15-09:45 | 22 | 12 | 55% | 671,704 | -486,943 |
| tod | 10:30-12:00 | 25 | 13 | 52% | 484,549 | -520,365 |
| tod | 12:00-13:30 | 18 | 10 | 56% | 1,617,741 | -261,312 |
| tod | 13:30-15:30 | 14 | 9 | 64% | 940,863 | -140,014 |
| month | Oct | 35 | 18 | 51% | 2,679,344 | -724,764 |
| month | Sep | 44 | 26 | 59% | 1,035,513 | -683,870 |

#### Categorical context, mac

| feature | value | n | wins | win% | net ₹ | losses ₹ |
|---|---|---|---|---|---|---|
| vs_vwap | against | 16 | 9 | 56% | 385,904 | -188,764 |
| vs_vwap | with | 26 | 17 | 65% | 472,750 | -200,257 |
| vs_OR | against | 14 | 8 | 57% | 276,977 | -196,631 |
| vs_OR | inside | 20 | 12 | 60% | 351,305 | -145,854 |
| vs_OR | with | 8 | 6 | 75% | 230,372 | -46,536 |
| vs_PD | against | 10 | 7 | 70% | 256,214 | -78,846 |
| vs_PD | inside | 20 | 10 | 50% | 229,720 | -239,742 |
| vs_PD | with | 12 | 9 | 75% | 372,720 | -70,433 |
| vs_PDC | against | 20 | 9 | 45% | 119,379 | -293,264 |
| vs_PDC | with | 22 | 17 | 77% | 739,275 | -95,757 |
| daytype_sofar | V_down | 4 | 2 | 50% | 64,398 | -26,743 |
| daytype_sofar | V_up | 6 | 5 | 83% | 284,214 | -32,472 |
| daytype_sofar | range | 11 | 6 | 55% | 131,203 | -112,026 |
| daytype_sofar | trend_down | 15 | 7 | 47% | 57,610 | -217,780 |
| daytype_sofar | trend_up | 6 | 6 | 100% | 321,229 | 0 |
| daytype_full | V_down | 4 | 4 | 100% | 279,882 | 0 |
| daytype_full | V_up | 10 | 7 | 70% | 282,310 | -43,534 |
| daytype_full | range | 3 | 1 | 33% | -21,068 | -22,519 |
| daytype_full | trend_down | 21 | 11 | 52% | 288,428 | -272,927 |
| daytype_full | trend_up | 4 | 3 | 75% | 29,102 | -50,041 |
| side | CE | 19 | 11 | 58% | 226,840 | -204,386 |
| side | PE | 23 | 15 | 65% | 631,814 | -184,635 |
| und | NIFTY | 24 | 17 | 71% | 840,836 | -134,800 |
| und | SENSEX | 18 | 9 | 50% | 17,818 | -254,221 |
| tod | 10:30-12:00 | 19 | 11 | 58% | 204,152 | -175,827 |
| tod | 12:00-13:30 | 13 | 7 | 54% | 276,694 | -171,020 |
| tod | 13:30-15:30 | 10 | 8 | 80% | 377,808 | -42,174 |
| month | Oct | 11 | 6 | 55% | 257,622 | -129,967 |
| month | Sep | 31 | 20 | 65% | 601,032 | -259,054 |

#### Categorical context, etr

| feature | value | n | wins | win% | net ₹ | losses ₹ |
|---|---|---|---|---|---|---|
| vs_vwap | with | 14 | 6 | 43% | 2,204,420 | -512,749 |
| vs_OR | with | 14 | 6 | 43% | 2,204,420 | -512,749 |
| vs_PD | against | 1 | 0 | 0% | -198,193 | -198,193 |
| vs_PD | inside | 4 | 3 | 75% | 1,194,497 | -54,323 |
| vs_PD | with | 9 | 3 | 33% | 1,208,116 | -260,233 |
| vs_PDC | against | 1 | 0 | 0% | -198,193 | -198,193 |
| vs_PDC | with | 13 | 6 | 46% | 2,402,613 | -314,556 |
| daytype_sofar | V_down | 2 | 1 | 50% | 645,221 | -198,193 |
| daytype_sofar | trend_down | 7 | 3 | 43% | 831,892 | -152,163 |
| daytype_sofar | trend_up | 5 | 2 | 40% | 727,307 | -162,393 |
| daytype_full | V_down | 1 | 0 | 0% | -39,577 | -39,577 |
| daytype_full | range | 1 | 1 | 100% | 843,414 | 0 |
| daytype_full | trend_down | 8 | 3 | 38% | 633,699 | -350,356 |
| daytype_full | trend_up | 4 | 2 | 50% | 766,884 | -122,816 |
| side | CE | 5 | 2 | 40% | 727,307 | -162,393 |
| side | PE | 9 | 4 | 44% | 1,477,113 | -350,356 |
| und | NIFTY | 8 | 3 | 38% | 1,005,874 | -242,946 |
| und | SENSEX | 6 | 3 | 50% | 1,198,546 | -269,803 |
| tod | 10:30-12:00 | 5 | 2 | 40% | 300,318 | -324,617 |
| tod | 12:00-13:30 | 5 | 3 | 60% | 1,341,047 | -90,292 |
| tod | 13:30-15:30 | 4 | 1 | 25% | 563,055 | -97,840 |
| month | Oct | 9 | 5 | 56% | 1,984,227 | -203,032 |
| month | Sep | 5 | 1 | 20% | 220,193 | -309,717 |

#### Categorical context, odb

| feature | value | n | wins | win% | net ₹ | losses ₹ |
|---|---|---|---|---|---|---|
| vs_vwap | against | 4 | 3 | 75% | 130,365 | -10,551 |
| vs_vwap | with | 8 | 1 | 12% | -88,791 | -276,896 |
| vs_OR | forming | 12 | 4 | 33% | 41,574 | -287,447 |
| vs_PD | inside | 6 | 1 | 17% | 10,365 | -177,740 |
| vs_PD | with | 6 | 3 | 50% | 31,209 | -109,707 |
| vs_PDC | against | 1 | 0 | 0% | -57,796 | -57,796 |
| vs_PDC | with | 11 | 4 | 36% | 99,370 | -229,651 |
| daytype_sofar | V_up | 3 | 0 | 0% | -156,952 | -156,952 |
| daytype_sofar | trend_down | 4 | 3 | 75% | 130,365 | -10,551 |
| daytype_sofar | trend_up | 5 | 1 | 20% | 68,161 | -119,944 |
| daytype_full | V_down | 2 | 0 | 0% | -95,178 | -95,178 |
| daytype_full | range | 2 | 0 | 0% | -149,155 | -149,155 |
| daytype_full | trend_down | 5 | 3 | 60% | 122,568 | -18,348 |
| daytype_full | trend_up | 3 | 1 | 33% | 163,339 | -24,766 |
| side | CE | 8 | 1 | 12% | -88,791 | -276,896 |
| side | PE | 4 | 3 | 75% | 130,365 | -10,551 |
| und | NIFTY | 6 | 3 | 50% | -18,790 | -159,706 |
| und | SENSEX | 6 | 1 | 17% | 60,364 | -127,741 |
| tod | 09:15-09:45 | 12 | 4 | 33% | 41,574 | -287,447 |
| month | Oct | 8 | 2 | 25% | 17,514 | -192,269 |
| month | Sep | 4 | 2 | 50% | 24,060 | -95,178 |

#### Categorical context, odm

| feature | value | n | wins | win% | net ₹ | losses ₹ |
|---|---|---|---|---|---|---|
| vs_vwap | with | 10 | 8 | 80% | 630,130 | -199,496 |
| vs_OR | forming | 9 | 7 | 78% | 513,987 | -199,496 |
| vs_OR | inside | 1 | 1 | 100% | 116,143 | 0 |
| vs_PD | inside | 8 | 6 | 75% | 457,834 | -199,496 |
| vs_PD | with | 2 | 2 | 100% | 172,296 | 0 |
| vs_PDC | against | 1 | 1 | 100% | 296,547 | 0 |
| vs_PDC | with | 9 | 7 | 78% | 333,583 | -199,496 |
| daytype_sofar | range | 1 | 1 | 100% | 100,884 | 0 |
| daytype_sofar | trend_down | 7 | 5 | 71% | 375,250 | -199,496 |
| daytype_sofar | trend_up | 2 | 2 | 100% | 153,996 | 0 |
| daytype_full | V_down | 1 | 1 | 100% | 37,853 | 0 |
| daytype_full | V_up | 2 | 1 | 50% | -61,639 | -122,264 |
| daytype_full | range | 1 | 0 | 0% | -77,232 | -77,232 |
| daytype_full | trend_down | 4 | 4 | 100% | 514,121 | 0 |
| daytype_full | trend_up | 2 | 2 | 100% | 217,027 | 0 |
| side | CE | 3 | 3 | 100% | 254,880 | 0 |
| side | PE | 7 | 5 | 71% | 375,250 | -199,496 |
| und | NIFTY | 4 | 3 | 75% | 328,279 | -122,264 |
| und | SENSEX | 6 | 5 | 83% | 301,851 | -77,232 |
| tod | 09:15-09:45 | 10 | 8 | 80% | 630,130 | -199,496 |
| month | Oct | 7 | 5 | 71% | 419,981 | -199,496 |
| month | Sep | 3 | 3 | 100% | 210,149 | 0 |

VIX (2 Sep-6 Oct only):

| VIX feature (trades 2 Sep-6 Oct, ebs excl.) | nW | nL | median W | median L | AUC(W>L) |
|---|---|---|---|---|---|
| VIX level | 29 | 22 | 12.48 | 12.15 | 0.51 |
| VIX chg 30m % | 24 | 20 | 0.08 | -0.30 | 0.54 |
| VIX chg since open % | 29 | 22 | 1.05 | 0.92 | 0.54 |

## Appendix C. Candidate filters and scan (in-sample)

### Candidate filters (IN-SAMPLE, descriptive). Removing the trades that match; capital re-use not modelled.

| rule | removed n (W/L) | losses avoided ₹ | wins forgone ₹ | Δ net ₹ | Sep: n (W/L), Δ ₹ | Oct: n (W/L), Δ ₹ |
|---|---|---|---|---|---|---|
| M1 mac: spot on the wrong side of previous close (CE below PDC / PE above PDC) | 20 (9/11) | 293,264 | 412,643 | -119,379 | 15 (8/7), -208,973 | 5 (1/4), +89,594 |
| M2 mac: prior-60-min move in trade direction > 0.10 ADR (extended) | 8 (3/5) | 119,732 | 114,351 | +5,381 | 7 (3/4), +942 | 1 (0/1), +4,439 |
| M3 mac: last-30-min range > 1.0 x avg 15-min range (no compression) | 10 (5/5) | 105,176 | 197,025 | -91,849 | 6 (3/3), +3,085 | 4 (2/2), -94,934 |
| M4 mac: counter to day-type-so-far (CE on trend_down / PE on trend_up) | 12 (7/5) | 146,590 | 319,711 | -173,121 | 9 (6/3), -195,883 | 3 (1/2), +22,762 |
| M5 mac: SENSEX | 18 (9/9) | 254,221 | 272,039 | -17,818 | 14 (8/6), -93,095 | 4 (1/3), +75,277 |
| M6 mac: beyond opening range on the opposite side | 14 (8/6) | 196,631 | 473,608 | -276,977 | 9 (6/3), -230,007 | 5 (2/3), -46,970 |
| M7 mac: against VWAP-proxy | 16 (9/7) | 188,764 | 574,668 | -385,904 | 11 (6/5), -186,290 | 5 (3/2), -199,614 |
| M8 mac: index moved against trade side since open (from_open<0) | 22 (12/10) | 258,866 | 614,977 | -356,111 | 15 (9/6), -227,510 | 7 (3/4), -128,601 |
| M9 mac: entry 10:30-12:00 | 19 (11/8) | 175,827 | 379,979 | -204,152 | 17 (11/6), -258,632 | 2 (0/2), +54,480 |
| M10 mac: gap not in trade direction (gap_adr_signed<=0.1) | 19 (11/8) | 193,048 | 522,734 | -329,686 | 16 (10/6), -360,584 | 3 (1/2), +30,898 |
| B1 odb: CE side | 8 (1/7) | 276,896 | 188,105 | +88,791 | 2 (0/2), +95,178 | 6 (1/5), -6,387 |
| B2 odb: no gap in trade direction (gap_adr_signed<=0.1) | 7 (1/6) | 262,681 | 21,678 | +241,003 | 2 (0/2), +95,178 | 5 (1/4), +145,825 |
| B3 odb: spot within 0.10 ADR of / not beyond PDH-PDL | 10 (2/8) | 287,447 | 209,783 | +77,664 | 2 (0/2), +95,178 | 8 (2/6), -17,514 |
| B4 odb: non-expiry day | 8 (2/6) | 262,681 | 119,864 | +142,817 | 3 (1/2), -3,008 | 5 (1/4), +145,825 |
| B5 odb: re-entry (2nd+ entry same day/underlying) | 5 (2/3) | 102,924 | 209,783 | -106,859 | 1 (0/1), +27,359 | 4 (2/2), -134,218 |
| D1 odm: spot > 0.9 ADR inside prior-day range from the level in trade direction | 2 (0/2) | 199,496 | 0 | +199,496 | 0 (0/0), +0 | 2 (0/2), +199,496 |
| D2 odm: gap against trade side | 6 (4/2) | 199,496 | 440,303 | -240,807 | 1 (1/0), -37,853 | 5 (3/2), -202,954 |
| E1 etr: option premium rose < 40 % in the 15 min before entry | 6 (1/5) | 404,833 | 529,910 | -125,077 | 3 (1/2), -277,394 | 3 (0/3), +152,317 |
| E2 etr: entry at/after 13:30 | 4 (1/3) | 97,840 | 660,895 | -563,055 | 1 (0/1), +17,624 | 3 (1/2), -580,679 |
| E3 etr: ATM straddle fell < 8 % in prior 30 min | 6 (1/5) | 392,956 | 843,414 | -450,458 | 3 (0/3), +270,140 | 3 (1/2), -720,598 |
| A1 all directional: wrong side of previous close | 23 (10/13) | 549,253 | 709,190 | -159,937 | 16 (8/8), -10,780 | 7 (2/5), -149,157 |
| A2 all directional (mac+etr): prior-60-min move > 0.10 ADR | 21 (9/12) | 584,298 | 2,831,520 | -2,247,222 | 12 (4/8), -219,251 | 9 (5/4), -2,027,971 |
| A3 all directional: entry after 13:30 against VWAP-proxy | 7 (5/2) | 42,174 | 221,882 | -179,708 | 6 (4/2), -143,774 | 1 (1/0), -35,934 |

### Systematic single-threshold scan (mac only, 15 pre-entry features x 7 quantile thresholds x 2 directions; removal of <=60 % of trades)

tests run: 151

| rule (remove if) | n removed (W/L) | Δ net ₹ | Δ Sep ₹ | Δ Oct ₹ |
|---|---|---|---|---|
| mom60_adr > 0.051 | 21 (7/14) | +84,935 | +74,741 | +10,194 |
| from_open_adr < -0.6 | 8 (3/5) | +62,944 | +40,182 | +22,762 |
| mom60_adr > 0.059 | 17 (6/11) | +61,015 | +109,517 | -48,502 |
| gap_adr_signed < -0.26 | 8 (2/6) | +53,771 | +36,980 | +16,791 |
| straddle_chg15_pct > -0.1 | 9 (4/5) | +8,519 | +23,850 | -15,331 |
| dist_extreme_adr > 0.66 | 9 (4/5) | +7,802 | -14,960 | +22,762 |
| mom60_adr > 0.089 | 9 (4/5) | +310 | -4,129 | +4,439 |
| range_sofar_adr > 0.94 | 12 (5/7) | -23,751 | -113,345 | +89,594 |
| range_sofar_adr < 0.47 | 7 (3/4) | -25,710 | -25,710 | +0 |
| mom60_adr > 0.032 | 25 (10/15) | -34,135 | -44,329 | +10,194 |
| straddle_chg15_pct > -0.4 | 13 (7/6) | -47,600 | -21,182 | -26,418 |
| vwap_dist_adr > 0.07 | 9 (5/4) | -51,031 | -117,863 | +66,832 |

Permutation baseline (500 label shuffles of mac P&L across trades): best-in-scan Δnet median 65,356, 90th pct 124,989, 95th pct 145,714. Observed best: 84,935.
Share of shuffles whose best rule >= observed best: 0.32 (i.e. the observed best mac filter is what chance alone produces).

#### Early-failure exits (in-sample)

| strategy | rule | trades cut | of which final W/L | Δ net ₹ | Δ Sep | Δ Oct |
|---|---|---|---|---|---|---|
| mac | prem < -5% at +5 min → exit | 4 | 1/3 | -106,696 | +12,941 | -119,637 |
| mac | prem < -5% at +10 min → exit | 7 | 2/5 | -158,867 | -27,248 | -131,620 |
| mac | prem < -10% at +10 min → exit | 2 | 1/1 | -131,620 | +0 | -131,620 |
| mac | prem < -5% at +15 min → exit | 9 | 0/9 | +68,449 | +30,892 | +37,556 |
| mac | prem < -10% at +15 min → exit | 4 | 0/4 | +14,119 | -18,298 | +32,417 |
| etr | prem < -5% at +5 min → exit | 6 | 1/5 | +14,138 | +44,096 | -29,958 |
| etr | prem < -10% at +5 min → exit | 3 | 0/3 | +67,272 | +9,113 | +58,159 |
| etr | prem < -5% at +10 min → exit | 4 | 0/4 | +83,461 | +7,943 | +75,517 |
| etr | prem < -10% at +10 min → exit | 4 | 0/4 | +83,461 | +7,943 | +75,517 |
| etr | prem < -5% at +15 min → exit | 4 | 0/4 | +26,135 | +0 | +26,135 |
| etr | prem < -10% at +15 min → exit | 4 | 0/4 | +26,135 | +0 | +26,135 |
| odm | prem < -5% at +5 min → exit | 4 | 3/1 | -443,995 | -65,013 | -378,982 |
| odm | prem < -10% at +5 min → exit | 3 | 3/0 | -506,289 | -65,013 | -441,276 |
| odm | prem < -5% at +10 min → exit | 3 | 3/0 | -480,426 | +0 | -480,426 |
| odm | prem < -10% at +10 min → exit | 1 | 1/0 | -75,368 | +0 | -75,368 |
| odm | prem < -5% at +15 min → exit | 1 | 1/0 | -74,183 | +0 | -74,183 |
| odm | prem < -10% at +15 min → exit | 1 | 1/0 | -74,183 | +0 | -74,183 |

## Appendix D. Exit forensics

### Exit forensics

#### Winners: open profit given back (minute closes of the held contract)

| strategy | winners | sum net ₹ | sum peak open profit ₹ | given back ₹ | median give-back of peak move | winners giving back > 50 % |
|---|---|---|---|---|---|---|
| mac | 26 | 1,247,675 | 1,398,326 | 150,651 | 0.07 | 4 |
| etr | 6 | 2,717,169 | 4,228,443 | 1,511,274 | 0.35 | 1 |
| odb | 4 | 329,021 | 367,641 | 38,620 | 0.14 | 0 |
| odm | 8 | 829,626 | 1,117,646 | 288,020 | 0.20 | 1 |
| ebs | 1 | 334,426 | 334,426 | 0 | -0.19 | 0 |

#### Losers: did they ever go green (minute close above breakeven incl. costs)?

| strategy | losers | went green | max open profit ₹ (sum) | median minutes held | sum net ₹ |
|---|---|---|---|---|---|
| mac | 16 | 12 | 138,366 | 32 | -389,021 |
| etr | 8 | 7 | 252,013 | 34 | -512,749 |
| odb | 8 | 1 | 20,143 | 1 | -287,447 |
| odm | 2 | 2 | 78,058 | 50 | -199,496 |
| ebs | 1 | 1 | 13,374 | 135 | -182,674 |
| ecr | 1 | 0 | 0 | 4 | -19,921 |

#### By exit class: what happened next (60 min after exit)

| exit class | n | W/L | net ₹ | median hold min | median index fav. move next 60 min (ADR, trade dir.) | median index net move next 60 min (ADR) | share with premium higher 60 min later | median premium max next 60 min vs exit |
|---|---|---|---|---|---|---|---|---|
| index stop (swing / session extreme) | 12 | 0/12 | -495,203 | 22 | 0.06 | -0.05 | 5/12 | +4.9% |
| level re-cross stop | 9 | 0/9 | -307,368 | 1 | 0.37 | 0.28 | 5/9 | +38.6% |
| resting premium stop | 2 | 0/2 | -98,898 | 38 | 0.07 | -0.04 | 0/2 | +33.3% |
| swing trail (etr) | 9 | 4/5 | 1,144,546 | 46 | 0.14 | 0.10 | 6/9 | +39.3% |
| target / trail | 19 | 19/0 | 1,329,981 | 21 | 0.13 | 0.01 | 7/19 | +10.7% |
| time / flat | 28 | 21/7 | 2,141,799 | 60 | 0.09 | 0.00 | 12/28 | +10.1% |

#### Counterfactual: no index/level stop, no target - only the resting premium stop and the strategy's time exit

Simulated on the held contract's 1-min bars (LTP), premium stop filled at its level, otherwise exit at the close of the last bar before the time exit; average cost and quantity as actually filled (for etr runners the adds are treated as if bought at entry, so etr is approximate). Bid/ask not modelled; re-entries that the earlier stop made possible are kept as separate trades (optimistic for the counterfactual on odb).

| strategy | rule | n | actual net ₹ | counterfactual net ₹ | Δ ₹ | Δ Sep ₹ | Δ Oct ₹ |
|---|---|---|---|---|---|---|---|
| mac | prem stop 25 %, exit entry+60 | 42 | 858,654 | 754,584 | -104,070 | -189,858 | +85,787 |
| odb | prem stop 25 %, exit 10:00 | 12 | 41,574 | 243,904 | +202,330 | +86,718 | +115,613 |
| odm | prem stop 50 %, exit 12:00 | 10 | 630,130 | 428,256 | -201,874 | -118,171 | -83,703 |
| etr | prem stop 40 %, exit 15:15 | 14 | 2,204,420 | 1,862,640 | -341,780 | +416,477 | -758,257 |
| odb | first entry per day/index only, prem stop 25 %, exit 10:00 (no re-entries) | 7 | 41,574 (all 12 actual) | -46,076 | -87,650 | | |

#### Counterfactual detail per trade (stops/structure exits only)

| strat | date | und | side | entry | actual exit | actual ₹ | counterfactual ₹ | cf exit |
|---|---|---|---|---|---|---|---|---|
| mac | 2026-09-07 | NIFTY | CE | 12:15 | STOP | -24,268 | -42,621 | time |
| mac | 2026-09-09 | SENSEX | CE | 11:45 | STOP | -32,472 | -62,400 | stop@12:01 |
| mac | 2026-09-15 | SENSEX | CE | 12:06 | STOP | -41,520 | -38,031 | time |
| mac | 2026-09-18 | NIFTY | PE | 13:42 | STOP | -19,499 | -62,456 | stop@14:33 |
| mac | 2026-09-18 | SENSEX | PE | 13:45 | STOP | -22,675 | -30,063 | time |
| mac | 2026-09-28 | SENSEX | PE | 12:54 | STOP | -29,745 | -20,548 | time |
| mac | 2026-10-05 | SENSEX | PE | 12:57 | STOP | -16,791 | -62,614 | stop@13:40 |
| mac | 2026-10-12 | NIFTY | CE | 12:39 | STOP | -39,164 | -42,339 | time |
| mac | 2026-10-12 | SENSEX | CE | 12:39 | STOP | -19,532 | -20,321 | time |
| mac | 2026-10-13 | SENSEX | PE | 11:48 | STOP | -50,041 | -62,224 | stop@12:30 |
| odb | 2026-09-30 | SENSEX | CE | 09:21 | STRUCTURE_PRIOR_ORH | -67,819 | -11,876 | time |
| odb | 2026-09-30 | SENSEX | CE | 09:24 | STRUCTURE_PDH | -27,359 | 2,847 | time |
| odb | 2026-10-08 | NIFTY | PE | 09:16 | STRUCTURE_PDL | -10,551 | 17,198 | time |
| odb | 2026-10-14 | NIFTY | CE | 09:17 | STRUCTURE_PDH | -91,359 | -123,818 | stop@09:19 |
| odb | 2026-10-14 | NIFTY | CE | 09:24 | STRUCTURE_PDH | -57,796 | -57,906 | time |
| odb | 2026-10-15 | SENSEX | CE | 09:17 | STRUCTURE_PRIOR_ORH | -6,997 | -23,512 | stop@09:22 |
| odb | 2026-10-15 | SENSEX | CE | 09:20 | STRUCTURE_PRIOR_ORH | -17,769 | 139,160 | time |
| odb | 2026-10-19 | SENSEX | CE | 09:19 | STRUCTURE_PDH | -7,797 | -23,875 | stop@09:25 |
| odm | 2026-10-16 | SENSEX | PE | 09:21 | STOP | -77,232 | -104,638 | time |
| odm | 2026-10-16 | NIFTY | PE | 09:21 | STOP | -122,264 | -125,203 | stop@10:15 |
| etr | 2026-09-03 | SENSEX | PE | 11:06 | SWING_HIGH_RECLAIMED | -198,193 | -189,062 | stop@11:36 |
| etr | 2026-09-08 | NIFTY | PE | 13:52 | SWING_HIGH_RECLAIMED | -17,624 | -28,869 | stop@15:10 |
| etr | 2026-09-15 | NIFTY | PE | 11:20 | SWING_HIGH_RECLAIMED | -54,323 | 428,861 | time |
| etr | 2026-09-17 | SENSEX | CE | 12:13 | SWING_LOW_LOST | -39,577 | -100,214 | stop@13:17 |
| etr | 2026-10-01 | SENSEX | PE | 12:20 | SWING_HIGH_RECLAIMED | 843,414 | 138,908 | time |
| etr | 2026-10-06 | NIFTY | CE | 11:46 | SWING_LOW_LOST | -72,101 | -98,089 | stop@14:27 |
| etr | 2026-10-06 | NIFTY | CE | 12:45 | PREMIUM_STOP | -50,715 | -49,656 | stop@13:28 |
| etr | 2026-10-08 | SENSEX | PE | 11:23 | SWING_HIGH_RECLAIMED | 396,130 | 770,075 | time |
| etr | 2026-10-15 | SENSEX | CE | 11:40 | SWING_LOW_LOST | 228,805 | -165,443 | stop@14:52 |
| etr | 2026-10-19 | NIFTY | PE | 12:05 | SWING_HIGH_RECLAIMED | 58,015 | 55,275 | time |
| etr | 2026-10-19 | NIFTY | PE | 14:27 | PREMIUM_STOP | -48,183 | -49,618 | stop@14:43 |

## Appendix E. Sizing, correlation, pyramiding

#### Return on premium deployed (size-neutral view)

| strategy | n | premium deployed ₹ (sum) | net ₹ | net / premium | median trade return % | Sep net/prem | Oct net/prem |
|---|---|---|---|---|---|---|---|
| mac | 42 | 10,459,977 | 858,654 | +8.2% | +4.6% | +7.8% | +9.5% |
| etr | 14 | 4,100,197 | 2,204,420 | +53.8% | -12.4% | +14.9% | +75.7% |
| odb | 12 | 2,629,740 | 41,574 | +1.6% | -8.5% | +2.3% | +1.1% |
| odm | 10 | 2,255,009 | 630,130 | +27.9% | +32.6% | +28.7% | +27.6% |
| ebs | 2 | 496,422 | 151,752 | +30.6% | +35.3% | +30.6% |  |
| ecr | 1 | 246,831 | -19,921 | -8.1% | -8.1% | -8.1% |  |

odb expiry_day=True: n=4 net 184,391 premium 401,931 net/prem +45.9%

odb expiry_day=False: n=8 net -142,817 premium 2,227,809 net/prem -6.4%

#### Same signal on both indices (same strategy, same side, entries <= 5 min apart, different underlying)

| date | strat | side | entries | NIFTY ₹ | SENSEX ₹ | combined ₹ | same outcome |
|---|---|---|---|---|---|---|---|
| 2026-09-02 | mac | PE | 11:06/11:06 | 14,787 | 5,905 | 20,692 | yes |
| 2026-09-07 | mac | CE | 13:42/13:45 | 34,527 | 13,185 | 47,712 | yes |
| 2026-09-09 | mac | PE | 14:21/14:21 | 45,256 | 92,980 | 138,236 | yes |
| 2026-09-18 | mac | PE | 10:33/10:33 | 1,451 | -5,196 | -3,745 | no |
| 2026-09-18 | mac | CE | 11:51/11:54 | -3,020 | 11,132 | 8,112 | no |
| 2026-09-18 | mac | PE | 13:42/13:45 | -19,499 | -22,675 | -42,174 | yes |
| 2026-09-28 | mac | PE | 14:06/14:09 | 117,892 | 41,112 | 159,004 | yes |
| 2026-10-12 | odm | PE | 09:24/09:24 | 296,547 | 83,462 | 380,009 | yes |
| 2026-10-12 | mac | CE | 12:39/12:39 | -39,164 | -19,532 | -58,696 | yes |
| 2026-10-16 | odm | PE | 09:21/09:21 | -122,264 | -77,232 | -199,496 | yes |

10 pairs, same outcome in 8; combined P&L of paired trades 449,654; losing pairs combined -304,111

#### Pyramiding (etr adds): actual vs initial clip only

| date | und side | entry | initial qty | adds qty | actual ₹ | initial clip only ₹ | adds contributed ₹ |
|---|---|---|---|---|---|---|---|
| 2026-09-03 | SENSEX PE | 11:06 | 880 | 2160 | -198,193 | -45,476 | -152,717 |
| 2026-09-15 | NIFTY PE | 11:20 | 2990 | 2990 | -54,323 | -26,578 | -27,745 |
| 2026-09-15 | NIFTY PE | 12:59 | 2860 | 5720 | 529,910 | 200,495 | +329,415 |
| 2026-09-17 | SENSEX CE | 12:13 | 700 | 700 | -39,577 | -17,533 | -22,044 |
| 2026-10-01 | SENSEX PE | 12:20 | 680 | 1360 | 843,414 | 290,726 | +552,688 |
| 2026-10-06 | NIFTY CE | 11:46 | 2600 | 2600 | -72,101 | -37,687 | -34,414 |
| 2026-10-08 | SENSEX PE | 11:23 | 980 | 980 | 396,130 | 233,035 | +163,095 |
| 2026-10-08 | SENSEX PE | 14:01 | 1080 | 2080 | -32,033 | -11,915 | -20,118 |
| 2026-10-13 | NIFTY CE | 14:08 | 3510 | 5135 | 660,895 | 287,819 | +373,076 |
| 2026-10-15 | SENSEX CE | 11:40 | 760 | 1520 | 228,805 | 89,663 | +139,142 |
| 2026-10-19 | NIFTY PE | 12:05 | 1885 | 3770 | 58,015 | 32,246 | +25,769 |

Adds contributed +1,583,185 on winners and -257,038 on losers (net +1,326,147).

#### Day-level loss concentration

Losing days: 2026-10-16 -199,496, 2026-10-14 -149,155, 2026-09-03 -134,676, 2026-10-06 -122,816, 2026-09-30 -57,382, 2026-09-18 -37,807 | total -701,332
All losing trades total: -1,591,308 ; top-10 losers total: -974,476

#### ETR add timing

| date | und side | net ₹ | entry-fill minutes (qty @ avg px) | min from first fill to last add | last add px vs first fill |
|---|---|---|---|---|---|
| 2026-09-03 | SENSEX PE | -198,193 | 11:06 880@140.9 ; 11:08 880@147.3 ; 11:09 400@160.8 ; 11:10 880@172.2 | 4 | +22% |
| 2026-09-15 | NIFTY PE | -54,323 | 11:20 2990@41.1 ; 11:21 2990@41.5 | 1 | +1% |
| 2026-09-15 | NIFTY PE | 529,910 | 12:59 2860@43.2 ; 13:16 2860@49.5 ; 13:32 2860@62.0 | 33 | +43% |
| 2026-09-17 | SENSEX CE | -39,577 | 12:13 700@174.5 ; 12:14 700@181.0 | 1 | +4% |
| 2026-10-01 | SENSEX PE | 843,414 | 12:20 680@180.4 ; 12:23 680@195.6 ; 12:27 680@207.4 | 7 | +15% |
| 2026-10-06 | NIFTY CE | -72,101 | 11:46 2600@47.5 ; 11:50 2600@46.3 | 4 | -3% |
| 2026-10-08 | SENSEX PE | 396,130 | 11:23 980@121.2 ; 11:26 980@192.6 | 3 | +59% |
| 2026-10-08 | SENSEX PE | -32,033 | 14:01 1080@114.3 ; 14:02 1000@100.1 ; 14:05 1080@124.8 | 4 | +9% |
| 2026-10-13 | NIFTY CE | 660,895 | 14:08 3510@24.1 ; 14:20 5135@33.5 | 12 | +39% |
| 2026-10-15 | SENSEX CE | 228,805 | 11:40 760@162.3 ; 11:42 760@178.1 ; 12:35 760@199.4 | 55 | +23% |
| 2026-10-19 | NIFTY PE | 58,015 | 12:05 1885@64.0 ; 12:16 1885@73.0 ; 12:22 1885@75.4 | 17 | +18% |

## Appendix F. Trade paths and full trade table

#### Every losing trade: minute-by-minute path after entry

Premium % vs first fill at +1/+2/+3/+5/+10/+15/+30/+60 min (contract minute close); index move in trade direction in ADR units at the same offsets. Entry context columns: day-type so far, gap (ADR, + = in trade direction), move since open (ADR, trade dir.), dist. from day extreme (ADR), vs prev close.

| date | strat | und/side | entry→exit | exit | net ₹ | premium path % | index path (ADR) | day so far / full | gap | from open | from extreme | vs PDC | went green |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 09-03 | etr | SENSEX PE | 11:06→11:36 | SWING_HIGH_RECLAIMED | -198,193 | 0.2 / 4.5 / 13.7 / 19.4 / 27.7 / 1.1 / -33.7 / -1.3 | -0.0 / 0.01 / 0.04 / 0.09 / 0.11 / -0.04 / -0.19 / 0.05 | V_down / trend_down | -0.3 | 0.06 | 0.01 | against | True |
| 09-10 | ebs | SENSEX CE | 12:57→15:12 | TARGET_30 | -182,674 | 5.4 / -2.3 / -5.6 / -12.9 / -21.1 / -10.7 / -13.4 / -26.2 |  /  /  /  /  /  /  /  | range / trend_up |  |  |  |  | True |
| 10-16 | odm | NIFTY PE | 09:21→10:15 | STOP | -122,264 | 6.6 / 1.9 / -4.8 / -3.0 / 7.2 / 19.6 / 2.0 / -55.8 | 0.04 / 0.02 / -0.03 / -0.0 / 0.06 / 0.15 / 0.03 / -0.46 | trend_down / V_up | -0.17 | 0.23 | 0.07 | with | True |
| 10-14 | odb | NIFTY CE | 09:17→09:18 | STRUCTURE_PDH | -91,359 | -17.2 / -24.1 / -23.9 / -17.3 / -24.9 / -29.4 / -9.3 / -30.4 | -0.12 / -0.19 / -0.19 / -0.14 / -0.18 / -0.21 / 0.01 / -0.17 | V_up / range | -0.02 | 0.1 | 0.01 | with | False |
| 10-16 | odm | SENSEX PE | 09:21→10:06 | STOP | -77,232 | 4.1 / -0.1 / -4.4 / -6.0 / -1.5 / 6.5 / -7.5 / -45.2 | 0.02 / 0.01 / -0.03 / -0.04 / 0.02 / 0.1 / -0.02 / -0.51 | trend_down / range | -0.06 | 0.19 | 0.06 | with | True |
| 10-06 | etr | NIFTY CE | 11:46→12:12 | SWING_LOW_LOST | -72,101 | -2.2 / -4.1 / -0.3 / -15.5 / -21.6 / -20.0 / -30.7 / 13.4 | -0.01 / -0.01 / 0.0 / -0.04 / -0.07 / -0.06 / -0.08 / 0.08 | trend_up / trend_up | 0.18 | 0.35 | 0.01 | with | True |
| 09-30 | odb | SENSEX CE | 09:21→09:23 | STRUCTURE_PRIOR_ORH | -67,819 | -2.9 / -14.5 / -3.2 / -5.1 / -11.1 / -7.0 / -11.2 / 22.0 | 0.01 / -0.06 / 0.03 / 0.04 / 0.01 / 0.02 / 0.02 / 0.33 | trend_up / V_down | -0.13 | 0.32 | 0.02 | with | False |
| 10-14 | odb | NIFTY CE | 09:24→09:25 | STRUCTURE_PDH | -57,796 | -11.2 / -7.2 / -14.2 / -14.3 / -14.5 / -18.2 / -10.9 / -19.4 | -0.08 / -0.06 / -0.1 / -0.09 / -0.09 / -0.1 / -0.04 / -0.1 | V_up / range | -0.02 | 0.02 | 0.09 | against | False |
| 09-15 | etr | NIFTY PE | 11:20→12:06 | SWING_HIGH_RECLAIMED | -54,323 | 0.6 / -2.0 / -6.8 / -7.1 / 2.2 / -1.1 / -11.0 / -11.8 | 0.02 / 0.0 / -0.04 / -0.06 / 0.03 / -0.01 / -0.05 / -0.03 | trend_down / trend_down | -1.17 | 1.67 | 0.03 | with | True |
| 10-06 | etr | NIFTY CE | 12:45→13:45 | PREMIUM_STOP | -50,715 | -6.4 / 1.4 / -1.8 / -9.0 / -17.9 / -16.9 / -18.5 / -41.8 | -0.0 / 0.02 / 0.01 / -0.02 / -0.04 / -0.03 / -0.03 / -0.06 | trend_up / trend_up | 0.18 | 0.44 | 0.03 | with | True |
| 10-13 | mac | SENSEX PE | 11:48→12:19 | STOP | -50,041 | -1.3 / 0.3 / -2.9 / -9.9 / -11.4 / -10.4 / -16.4 / -24.2 | -0.01 / 0.01 / -0.01 / -0.08 / -0.08 / -0.09 / -0.1 / -0.16 | range / trend_up | -0.03 | -0.4 | 0.66 | against | True |
| 10-19 | etr | NIFTY PE | 14:27→14:43 | PREMIUM_STOP | -48,183 | 1.0 / -0.8 / -8.1 / -21.4 / -18.1 / -24.6 / -60.8 / -98.9 | 0.01 / 0.0 / -0.02 / -0.04 / -0.02 / -0.03 / -0.1 / -0.07 | trend_down / trend_down | 0.01 | 0.89 | 0.02 | with | True |
| 09-15 | mac | SENSEX CE | 12:06→12:59 | STOP | -41,520 | -3.6 / -3.6 / -1.5 / -2.8 / -5.6 / -7.6 / -8.0 / -15.3 | -0.07 / -0.07 / -0.03 / -0.08 / -0.14 / -0.17 / -0.15 / -0.25 | trend_down / trend_down | 1.17 | -1.28 | 1.41 | against | False |
| 09-17 | etr | SENSEX CE | 12:13→12:51 | SWING_LOW_LOST | -39,577 | 3.2 / 2.0 / -4.1 / 5.9 / 13.6 / 0.5 / 7.1 / -27.2 | 0.0 / 0.0 / -0.01 / 0.03 / 0.06 / 0.01 / 0.07 / -0.07 | trend_up / V_down | -0.22 | 0.6 | 0.0 | with | True |
| 10-12 | mac | NIFTY CE | 12:39→12:56 | STOP | -39,164 | -0.2 / 3.9 / 3.9 / 5.5 / -2.1 / -12.3 / -1.5 / -17.0 | 0.0 / 0.01 / 0.02 / 0.03 / -0.02 / -0.07 / -0.01 / -0.09 | trend_down / trend_down | 0.26 | -0.74 | 0.98 | against | True |
| 09-09 | mac | SENSEX PE | 10:42→11:42 | TIME_60 | -36,249 | 2.5 / 2.5 / -1.4 / -3.0 / -1.1 / -9.3 / -11.5 / -14.0 | 0.02 / -0.01 / -0.01 / -0.07 / -0.06 / -0.13 / -0.15 / -0.12 | trend_down / trend_down | 0.71 | 0.39 | 0.19 | with | True |
| 09-09 | mac | SENSEX CE | 11:45→11:52 | STOP | -32,472 | -1.3 / -4.2 / -3.2 / -8.1 / -15.1 / -22.8 / -22.4 / 20.1 | 0.0 / -0.03 / -0.04 / -0.09 / -0.17 / -0.25 / -0.31 / 0.2 | V_up / trend_down | -0.71 | -0.22 | 0.23 | against | False |
| 10-08 | etr | SENSEX PE | 14:01→15:15 | FLAT_BY | -32,033 | -12.7 / -7.4 / -10.5 / -3.9 / 16.2 / -22.3 / -14.8 / -13.3 | -0.02 / -0.03 / -0.02 / 0.01 / 0.06 / -0.08 / -0.01 / 0.04 | trend_down / trend_down | -0.07 | 1.46 | 0.0 | with | True |
| 09-28 | mac | SENSEX PE | 12:54→13:27 | STOP | -29,745 | -0.5 / -1.0 / 0.8 / 0.2 / 0.3 / 4.6 / -4.9 / -5.3 | -0.02 / -0.02 / -0.03 / -0.01 / -0.03 / 0.07 / -0.08 / -0.05 | trend_down / trend_down | 0.29 | 1.47 | 0.14 | with | True |
| 09-30 | odb | SENSEX CE | 09:24→09:31 | STRUCTURE_PDH | -27,359 | 4.1 / -2.0 / 6.6 / 3.6 / -9.2 / -6.2 / -7.2 / 21.8 | 0.06 / 0.0 / 0.11 / 0.08 / -0.11 / -0.05 / 0.0 / 0.31 | trend_up / V_down | -0.13 | 0.35 | 0.02 | with | True |
| 09-07 | mac | NIFTY CE | 12:15→12:22 | STOP | -24,268 | -1.0 / -3.8 / -4.5 / -9.1 / -8.2 / -12.7 / -12.2 / -16.9 | -0.02 / -0.05 / -0.07 / -0.12 / -0.13 / -0.17 / -0.19 / -0.25 | trend_down / trend_down | -0.1 | -0.68 | 0.73 | against | False |
| 09-18 | mac | SENSEX PE | 13:45→14:05 | STOP | -22,675 | -0.8 / -1.5 / -2.7 / -1.7 / -7.1 / -5.6 / -11.1 / -12.0 | 0.02 / -0.01 / -0.03 / -0.02 / -0.1 / -0.08 / -0.18 / -0.2 | range / trend_down | -0.37 | 0.13 | 0.15 | against | False |
| 09-10 | mac | NIFTY CE | 10:42→11:42 | TIME_60 | -22,304 | 0.1 / 0.4 / 6.9 / -0.7 / -6.3 / -12.7 / -4.6 / -8.7 | 0.01 / -0.02 / 0.08 / -0.02 / -0.13 / -0.24 / -0.13 / -0.18 | V_down / V_up | 0.11 | -0.06 | 0.41 | with | True |
| 09-28 | mac | NIFTY CE | 11:45→12:45 | TIME_60 | -22,106 | 0.6 / 0.9 / -1.4 / -4.1 / -7.6 / -12.1 / -9.5 / -8.4 | 0.01 / 0.02 / 0.01 / -0.02 / -0.06 / -0.09 / -0.05 / -0.02 | trend_down / trend_down | -0.44 | -1.31 | 1.4 | against | True |
| 09-10 | ecr | SENSEX PE | 10:59→11:03 | INVALIDATED | -19,921 | -1.6 / -7.6 / -8.4 / -6.7 / -10.2 / -23.2 / -17.9 / -25.8 | 0.01 / -0.04 / -0.03 / -0.05 / -0.07 / -0.14 / -0.13 / -0.15 | V_down / trend_up | 0.04 | 0.08 | 0.06 | with | False |
| 10-12 | mac | SENSEX CE | 12:39→12:56 | STOP | -19,532 | 0.3 / 2.3 / 2.4 / 3.9 / -0.3 / -5.6 / 0.7 / -8.0 | 0.0 / 0.0 / 0.02 / 0.03 / -0.03 / -0.06 / -0.02 / -0.08 | trend_down / trend_down | 0.35 | -0.94 | 1.06 | against | True |
| 09-18 | mac | NIFTY PE | 13:42→14:05 | STOP | -19,499 | 2.4 / 3.2 / 6.3 / 3.6 / -4.7 / -5.2 / -15.0 / -19.6 | 0.01 / 0.02 / 0.04 / 0.02 / -0.04 / -0.06 / -0.17 / -0.2 | range / range | -0.28 | 0.07 | 0.14 | against | True |
| 10-15 | odb | SENSEX CE | 09:20→09:21 | STRUCTURE_PRIOR_ORH | -17,769 | -18.2 / -12.8 / -18.6 / -1.1 / 30.7 / 38.4 / 65.7 / 187.0 | -0.08 / -0.07 / -0.11 / 0.01 / 0.11 / 0.13 / 0.23 / 0.52 | trend_up / trend_up | 0.17 | 0.17 | 0.05 | with | False |
| 09-08 | etr | NIFTY PE | 13:52→14:06 | SWING_HIGH_RECLAIMED | -17,624 | -0.3 / -8.7 / -5.5 / -11.5 / -13.1 / -18.6 / -22.1 / -16.4 | -0.02 / -0.05 / -0.05 / -0.07 / -0.1 / -0.17 / -0.19 / -0.13 | trend_down / trend_down | 0.25 | 0.81 | 0.01 | with | False |
| 10-05 | mac | SENSEX PE | 12:57→13:06 | STOP | -16,791 | -3.5 / -1.0 / -1.6 / -0.5 / -8.7 / -6.7 / -4.8 / -35.5 | -0.05 / -0.03 / -0.02 / -0.02 / -0.08 / -0.09 / -0.06 / -0.44 | range / V_up | -0.52 | 0.44 | 0.17 | against | True |
| 10-08 | odb | NIFTY PE | 09:16→09:17 | STRUCTURE_PDL | -10,551 | -10.5 / -5.3 / -6.4 / -5.5 / 11.6 / 5.8 / 29.6 / 10.3 | -0.15 / -0.08 / -0.08 / -0.07 / 0.11 / 0.05 / 0.3 / 0.16 | trend_down / trend_down | 0.05 | 0.26 | 0.02 | with | False |
| 10-19 | odb | SENSEX CE | 09:19→09:20 | STRUCTURE_PDH | -7,797 | -4.0 / -17.1 / -19.9 / -18.3 / -51.8 / -26.4 / -8.8 / -35.4 | -0.04 / -0.14 / -0.16 / -0.15 / -0.57 / -0.24 / -0.1 / -0.32 | V_up / trend_down | 0.01 | 0.06 | 0.01 | with | False |
| 10-15 | odb | SENSEX CE | 09:17→09:19 | STRUCTURE_PRIOR_ORH | -6,997 | -7.7 / -9.5 / -4.2 / -17.9 / 11.9 / 29.3 / 47.6 / 217.0 | -0.03 / -0.07 / -0.02 / -0.09 / 0.06 / 0.09 / 0.16 / 0.54 | trend_up / trend_up | 0.17 | 0.19 | 0.02 | with | False |
| 09-18 | mac | SENSEX PE | 10:33→11:33 | TIME_60 | -5,196 | 0.4 / -1.5 / -0.4 / -2.4 / 0.1 / -0.3 / -3.4 / -1.5 | -0.0 / -0.01 / -0.02 / -0.02 / 0.0 / -0.01 / -0.04 / -0.02 | trend_down / trend_down | -0.37 | 0.22 | 0.06 | against | True |
| 10-01 | mac | NIFTY PE | 11:03→12:03 | TIME_60 | -4,439 | 3.3 / 3.3 / 2.3 / 2.8 / 3.1 / -2.8 / 6.7 / -1.0 | 0.02 / 0.02 / 0.02 / 0.03 / 0.04 / -0.03 / 0.07 / 0.01 | V_down / V_up | 0.35 | -0.04 | 0.2 | with | True |
| 09-18 | mac | NIFTY CE | 11:54→12:54 | TIME_60 | -3,020 | 0.0 / -0.6 / -0.3 / 0.8 / 1.3 / -3.8 / -3.7 / -1.0 | -0.0 / -0.02 / -0.02 / 0.01 / -0.0 / -0.05 / -0.04 / 0.02 | range / range | 0.28 | -0.06 | 0.17 | with | True |

#### Every trade (compact)

| date | strat | und | side | entry | exit | min held | exit reason | qty | avg cost | avg exit | prem ret % | net ₹ | ToD | expiry day |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-09-02 | mac | SENSEX | PE | 11:06:00 | 12:06:00 | 60.0 | TIME_60 | 1040 | 235.32 | 241.64 | 2.7 | 5,905 | 10:30-12:00 |  |
| 2026-09-02 | mac | NIFTY | PE | 11:06:00 | 12:06:00 | 60.0 | TIME_60 | 1950 | 125.39 | 133.29 | 6.3 | 14,787 | 10:30-12:00 |  |
| 2026-09-03 | mac | NIFTY | PE | 10:30:00 | 11:06:01 | 36.0 | TARGET | 2600 | 95.44 | 120.13 | 25.9 | 63,517 | 10:30-12:00 |  |
| 2026-09-03 | etr | SENSEX | PE | 11:06:00 | 11:36:06 | 30.1 | SWING_HIGH_RECLAIMED | 3040 | 154.43 | 89.65 | -41.9 | -198,193 | 10:30-12:00 | Y |
| 2026-09-07 | mac | NIFTY | CE | 12:15:00 | 12:22:01 | 7.0 | STOP | 2730 | 90.93 | 82.27 | -9.5 | -24,268 | 12:00-13:30 |  |
| 2026-09-07 | mac | NIFTY | CE | 13:42:00 | 14:03:01 | 21.0 | TARGET | 2275 | 109.41 | 124.87 | 14.1 | 34,527 | 13:30-15:30 |  |
| 2026-09-07 | mac | SENSEX | CE | 13:45:00 | 14:30:01 | 45.0 | TARGET | 540 | 463.51 | 489.05 | 5.5 | 13,185 | 13:30-15:30 |  |
| 2026-09-08 | odb | NIFTY | PE | 09:17:00 | 10:00:00 | 43.0 | TIME_10:00 | 2080 | 46.76 | 57.05 | 22.0 | 21,052 | 09:15-09:45 | Y |
| 2026-09-08 | mac | SENSEX | PE | 12:48:00 | 13:48:01 | 60.0 | TIME_60 | 1020 | 244.83 | 247.87 | 1.2 | 2,400 | 12:00-13:30 |  |
| 2026-09-08 | etr | NIFTY | PE | 13:52:00 | 14:06:00 | 14.0 | SWING_HIGH_RECLAIMED | 3900 | 18.30 | 13.86 | -24.2 | -17,624 | 13:30-15:30 | Y |
| 2026-09-09 | mac | SENSEX | PE | 10:42:00 | 11:42:02 | 60.0 | TIME_60 | 1260 | 197.15 | 168.95 | -14.3 | -36,249 | 10:30-12:00 |  |
| 2026-09-09 | mac | NIFTY | PE | 11:36:00 | 12:00:01 | 24.0 | TARGET | 2730 | 91.10 | 104.83 | 15.1 | 36,751 | 10:30-12:00 |  |
| 2026-09-09 | mac | SENSEX | CE | 11:45:00 | 11:52:02 | 7.0 | STOP | 840 | 294.06 | 256.17 | -12.9 | -32,472 | 10:30-12:00 |  |
| 2026-09-09 | mac | SENSEX | PE | 14:21:00 | 14:39:01 | 18.0 | TARGET | 1360 | 181.80 | 250.82 | 38.0 | 92,980 | 13:30-15:30 |  |
| 2026-09-09 | mac | NIFTY | PE | 14:21:00 | 14:39:01 | 18.0 | TARGET | 2470 | 100.26 | 118.85 | 18.5 | 45,256 | 13:30-15:30 |  |
| 2026-09-10 | mac | NIFTY | CE | 10:42:00 | 11:42:01 | 60.0 | TIME_60 | 1885 | 131.43 | 119.90 | -8.8 | -22,304 | 10:30-12:00 |  |
| 2026-09-10 | ecr | SENSEX | PE | 10:59:00 | 11:03:03 | 4.0 | INVALIDATED | 1220 | 202.32 | 186.60 | -7.8 | -19,921 | 10:30-12:00 | Y |
| 2026-09-10 | mac | NIFTY | CE | 11:51:00 | 12:51:01 | 60.0 | TIME_60 | 1885 | 130.50 | 133.51 | 2.3 | 5,071 | 10:30-12:00 |  |
| 2026-09-10 | ebs | SENSEX | CE | 12:57:00 | 15:12:15 | 135.2 | TARGET_30 | 1780 | 145.63 | 43.36 | -70.2 | -182,674 | 12:00-13:30 | Y |
| 2026-09-10 | ebs | SENSEX | PE | 12:57:00 | 15:12:18 | 135.3 | TARGET_30 | 1780 | 133.26 | 321.90 | 141.6 | 334,426 | 12:00-13:30 | Y |
| 2026-09-15 | etr | NIFTY | PE | 11:20:00 | 12:06:01 | 46.0 | SWING_HIGH_RECLAIMED | 5980 | 41.30 | 32.35 | -21.7 | -54,323 | 10:30-12:00 | Y |
| 2026-09-15 | mac | SENSEX | CE | 12:06:00 | 12:59:00 | 53.0 | STOP | 640 | 384.00 | 319.95 | -16.7 | -41,520 | 12:00-13:30 |  |
| 2026-09-15 | etr | NIFTY | PE | 12:59:00 | 15:15:01 | 136.0 | FLAT_BY | 8580 | 51.59 | 113.61 | 120.2 | 529,910 | 12:00-13:30 | Y |
| 2026-09-17 | odm | NIFTY | CE | 09:21:00 | 12:00:01 | 159.0 | FLAT_BY | 1625 | 148.97 | 172.66 | 15.9 | 37,853 | 09:15-09:45 |  |
| 2026-09-17 | mac | NIFTY | PE | 10:51:00 | 11:11:00 | 20.0 | TARGET | 2210 | 111.30 | 146.07 | 31.2 | 76,132 | 10:30-12:00 |  |
| 2026-09-17 | mac | NIFTY | CE | 11:51:00 | 12:51:00 | 60.0 | TIME_60 | 2080 | 118.47 | 153.56 | 29.6 | 72,295 | 10:30-12:00 |  |
| 2026-09-17 | etr | SENSEX | CE | 12:13:00 | 12:51:00 | 38.0 | SWING_LOW_LOST | 1400 | 177.75 | 149.96 | -15.6 | -39,577 | 12:00-13:30 | Y |
| 2026-09-17 | mac | NIFTY | PE | 13:18:00 | 13:36:00 | 18.0 | TARGET | 2405 | 101.95 | 141.19 | 38.5 | 93,659 | 12:00-13:30 |  |
| 2026-09-18 | mac | NIFTY | PE | 10:33:00 | 11:33:01 | 60.0 | TIME_60 | 2470 | 99.24 | 100.07 | 0.8 | 1,451 | 10:30-12:00 |  |
| 2026-09-18 | mac | SENSEX | PE | 10:33:00 | 11:33:02 | 60.0 | TIME_60 | 600 | 413.14 | 405.57 | -1.8 | -5,196 | 10:30-12:00 |  |
| 2026-09-18 | mac | SENSEX | CE | 11:51:00 | 12:51:00 | 60.0 | TIME_60 | 540 | 451.21 | 473.00 | 4.8 | 11,132 | 10:30-12:00 |  |
| 2026-09-18 | mac | NIFTY | CE | 11:54:00 | 12:54:00 | 60.0 | TIME_60 | 2080 | 118.16 | 116.99 | -1.0 | -3,020 | 10:30-12:00 |  |
| 2026-09-18 | mac | NIFTY | PE | 13:42:00 | 14:05:01 | 23.0 | STOP | 3055 | 80.93 | 74.76 | -7.6 | -19,499 | 13:30-15:30 |  |
| 2026-09-18 | mac | SENSEX | PE | 13:45:00 | 14:05:00 | 20.0 | STOP | 620 | 394.54 | 358.92 | -9.0 | -22,675 | 13:30-15:30 |  |
| 2026-09-28 | odb | NIFTY | PE | 09:16:00 | 10:00:01 | 44.0 | TIME_10:00 | 1755 | 83.92 | 140.14 | 67.0 | 98,186 | 09:15-09:45 |  |
| 2026-09-28 | odm | SENSEX | PE | 09:21:00 | 12:00:02 | 159.0 | FLAT_BY | 680 | 358.14 | 523.44 | 46.2 | 111,671 | 09:15-09:45 |  |
| 2026-09-28 | mac | NIFTY | CE | 11:45:00 | 12:45:01 | 60.0 | TIME_60 | 2275 | 108.95 | 99.48 | -8.7 | -22,106 | 10:30-12:00 |  |
| 2026-09-28 | mac | SENSEX | PE | 12:54:00 | 13:27:00 | 33.0 | STOP | 1020 | 362.06 | 333.68 | -7.8 | -29,745 | 12:00-13:30 |  |
| 2026-09-28 | mac | SENSEX | PE | 14:06:00 | 14:19:05 | 13.1 | TARGET | 600 | 353.33 | 422.88 | 19.7 | 41,112 | 13:30-15:30 |  |
| 2026-09-28 | mac | NIFTY | PE | 14:09:00 | 14:19:00 | 10.0 | TARGET | 3770 | 72.97 | 104.48 | 43.2 | 117,892 | 13:30-15:30 |  |
| 2026-09-29 | odm | SENSEX | PE | 09:21:00 | 09:42:01 | 21.0 | TRAIL_15 | 700 | 350.63 | 438.14 | 25.0 | 60,625 | 09:15-09:45 |  |
| 2026-09-29 | mac | SENSEX | CE | 10:57:00 | 11:13:00 | 16.0 | TARGET | 500 | 466.11 | 577.53 | 23.9 | 55,142 | 10:30-12:00 |  |
| 2026-09-29 | mac | SENSEX | CE | 14:15:00 | 15:15:02 | 60.0 | FLAT_BY | 520 | 464.57 | 540.90 | 16.4 | 39,096 | 13:30-15:30 |  |
| 2026-09-30 | odb | SENSEX | CE | 09:21:00 | 09:23:03 | 2.0 | STRUCTURE_PRIOR_ORH | 1440 | 344.60 | 298.30 | -13.4 | -67,819 | 09:15-09:45 |  |
| 2026-09-30 | odb | SENSEX | CE | 09:24:00 | 09:31:00 | 7.0 | STRUCTURE_PDH | 940 | 333.45 | 305.02 | -8.5 | -27,359 | 09:15-09:45 |  |
| 2026-09-30 | mac | NIFTY | CE | 11:51:00 | 12:24:01 | 33.0 | TARGET | 1430 | 172.50 | 199.34 | 15.6 | 37,796 | 10:30-12:00 |  |
| 2026-10-01 | mac | NIFTY | PE | 11:03:00 | 12:03:00 | 60.0 | TIME_60 | 2210 | 110.60 | 108.86 | -1.6 | -4,439 | 10:30-12:00 |  |
| 2026-10-01 | mac | NIFTY | PE | 12:06:00 | 12:24:00 | 18.0 | TARGET | 2145 | 113.72 | 154.18 | 35.6 | 86,070 | 12:00-13:30 |  |
| 2026-10-01 | etr | SENSEX | PE | 12:20:00 | 14:30:04 | 130.1 | SWING_HIGH_RECLAIMED | 2040 | 194.48 | 609.00 | 213.1 | 843,414 | 12:00-13:30 | Y |
| 2026-10-05 | mac | NIFTY | CE | 12:51:00 | 13:42:00 | 51.0 | TARGET | 2015 | 123.55 | 183.34 | 48.4 | 119,773 | 12:00-13:30 |  |
| 2026-10-05 | mac | SENSEX | PE | 12:57:00 | 13:06:02 | 9.0 | STOP | 540 | 459.62 | 429.57 | -6.5 | -16,791 | 12:00-13:30 |  |
| 2026-10-06 | etr | NIFTY | CE | 11:46:00 | 12:12:01 | 26.0 | SWING_LOW_LOST | 5200 | 46.92 | 33.15 | -29.3 | -72,101 | 10:30-12:00 | Y |
| 2026-10-06 | etr | NIFTY | CE | 12:45:00 | 13:45:07 | 60.1 | PREMIUM_STOP | 2145 | 57.61 | 34.07 | -40.9 | -50,715 | 12:00-13:30 | Y |
| 2026-10-07 | mac | NIFTY | PE | 12:21:00 | 13:09:01 | 48.0 | TARGET | 1885 | 132.69 | 187.48 | 41.3 | 102,603 | 12:00-13:30 |  |
| 2026-10-08 | odb | NIFTY | PE | 09:16:00 | 09:17:00 | 1.0 | STRUCTURE_PDL | 650 | 148.18 | 132.31 | -10.7 | -10,551 | 09:15-09:45 |  |
| 2026-10-08 | odb | NIFTY | PE | 09:18:00 | 10:00:00 | 42.0 | TIME_10:00 | 650 | 140.81 | 174.58 | 24.0 | 21,678 | 09:15-09:45 |  |
| 2026-10-08 | etr | SENSEX | PE | 11:23:00 | 13:36:03 | 133.1 | SWING_HIGH_RECLAIMED | 1960 | 156.91 | 359.71 | 129.2 | 396,130 | 10:30-12:00 | Y |
| 2026-10-08 | etr | SENSEX | PE | 14:01:00 | 15:15:03 | 74.0 | FLAT_BY | 3160 | 113.37 | 103.55 | -8.7 | -32,033 | 13:30-15:30 | Y |
| 2026-10-09 | odm | NIFTY | CE | 09:30:00 | 11:43:00 | 133.0 | TRAIL_15 | 2210 | 129.93 | 182.85 | 40.7 | 116,143 | 09:15-09:45 |  |
| 2026-10-09 | mac | SENSEX | CE | 12:42:00 | 13:42:01 | 60.0 | TIME_60 | 420 | 569.94 | 597.65 | 4.9 | 11,087 | 12:00-13:30 |  |
| 2026-10-09 | mac | NIFTY | CE | 12:48:00 | 13:48:00 | 60.0 | TIME_60 | 2015 | 124.19 | 140.42 | 13.1 | 32,122 | 12:00-13:30 |  |
| 2026-10-12 | odm | NIFTY | PE | 09:24:00 | 12:00:00 | 156.0 | FLAT_BY | 3055 | 78.14 | 175.51 | 124.6 | 296,547 | 09:15-09:45 |  |
| 2026-10-12 | odm | SENSEX | PE | 09:24:00 | 12:00:01 | 156.0 | FLAT_BY | 260 | 427.93 | 750.43 | 75.4 | 83,462 | 09:15-09:45 |  |
| 2026-10-12 | mac | NIFTY | CE | 12:39:00 | 12:56:00 | 17.0 | STOP | 2795 | 87.93 | 74.09 | -15.7 | -39,164 | 12:00-13:30 |  |
| 2026-10-12 | mac | SENSEX | CE | 12:39:00 | 12:56:01 | 17.0 | STOP | 520 | 472.66 | 436.17 | -7.7 | -19,532 | 12:00-13:30 |  |
| 2026-10-13 | odm | SENSEX | CE | 09:21:00 | 10:33:03 | 72.0 | TRAIL_15 | 540 | 458.09 | 646.25 | 41.1 | 100,884 | 09:15-09:45 |  |
| 2026-10-13 | mac | SENSEX | PE | 11:48:00 | 12:19:00 | 31.0 | STOP | 780 | 316.63 | 253.09 | -20.1 | -50,041 | 10:30-12:00 |  |
| 2026-10-13 | etr | NIFTY | CE | 14:08:00 | 15:15:03 | 67.0 | FLAT_BY | 8645 | 29.70 | 106.34 | 258.0 | 660,895 | 13:30-15:30 | Y |
| 2026-10-14 | odb | NIFTY | CE | 09:17:00 | 09:18:05 | 1.1 | STRUCTURE_PDH | 2925 | 168.06 | 137.14 | -18.4 | -91,359 | 09:15-09:45 |  |
| 2026-10-14 | odb | NIFTY | CE | 09:24:00 | 09:25:00 | 1.0 | STRUCTURE_PDH | 2795 | 177.83 | 157.49 | -11.4 | -57,796 | 09:15-09:45 |  |
| 2026-10-15 | odb | SENSEX | CE | 09:17:00 | 09:19:01 | 2.0 | STRUCTURE_PRIOR_ORH | 480 | 193.73 | 179.70 | -7.2 | -6,997 | 09:15-09:45 | Y |
| 2026-10-15 | odb | SENSEX | CE | 09:20:00 | 09:21:00 | 1.0 | STRUCTURE_PRIOR_ORH | 420 | 232.65 | 190.96 | -17.9 | -17,769 | 09:15-09:45 | Y |
| 2026-10-15 | odb | SENSEX | CE | 09:25:00 | 10:00:01 | 35.0 | TIME_10:00 | 620 | 183.81 | 488.19 | 165.6 | 188,105 | 09:15-09:45 | Y |
| 2026-10-15 | etr | SENSEX | CE | 11:40:00 | 14:06:02 | 146.0 | SWING_LOW_LOST | 2280 | 179.90 | 280.86 | 56.1 | 228,805 | 10:30-12:00 | Y |
| 2026-10-15 | mac | NIFTY | PE | 14:27:00 | 15:11:01 | 44.0 | TARGET | 2015 | 121.95 | 140.07 | 14.9 | 35,934 | 13:30-15:30 |  |
| 2026-10-16 | odm | SENSEX | PE | 09:21:00 | 10:06:01 | 45.0 | STOP | 400 | 599.08 | 407.16 | -32.0 | -77,232 | 09:15-09:45 |  |
| 2026-10-16 | odm | NIFTY | PE | 09:21:00 | 10:15:00 | 54.0 | STOP | 1690 | 147.74 | 75.61 | -48.8 | -122,264 | 09:15-09:45 |  |
| 2026-10-19 | odb | SENSEX | CE | 09:19:00 | 09:20:03 | 1.1 | STRUCTURE_PDH | 240 | 393.48 | 362.10 | -8.0 | -7,797 | 09:15-09:45 |  |
| 2026-10-19 | odm | SENSEX | PE | 09:27:00 | 12:00:01 | 153.0 | FLAT_BY | 300 | 500.53 | 576.58 | 15.2 | 22,441 | 09:15-09:45 |  |
| 2026-10-19 | etr | NIFTY | PE | 12:05:00 | 14:03:01 | 118.0 | SWING_HIGH_RECLAIMED | 5655 | 70.80 | 81.23 | 14.7 | 58,015 | 12:00-13:30 | Y |
| 2026-10-19 | etr | NIFTY | PE | 14:27:00 | 14:43:07 | 16.1 | PREMIUM_STOP | 3250 | 37.99 | 23.23 | -38.8 | -48,183 | 13:30-15:30 | Y |
