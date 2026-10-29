# OI-burst trades: where the losses come from and when to exit (A-259, 28 Oct 2026)

Data: oba-v1 (A-258, 80 trades) and obc-v8 (A-255, 32 trades) on the 29 tick days (2 Sep–27 Oct 2026). For every trade:
the entry context the engine stored (`trade.candidate`, 50 fields), the index's minute closes and the held option's
minute price from entry to 15:15 (own capture: last bid of the minute, from 28 Sep; before that, 1-minute bar closes).
Scripts in `.local/a259/` (`extract.py`, `common.py`, `exits.py`, `losses.py`, `grid*.py`, `robust.py`, `noiseband.py`).

Method: every exit rule is applied to the same trades on their real price paths. Each trade's real STOP / PREMIUM_STOP
exit is kept as it happened; the rest (ATR trail, counter-burst, the rule tested, a 2 ATR stop after the real exit) is
simulated minute by minute and valued at the option's price. The simulator reproduces the current rules within ~Rs 1 L
per half (rank correlation with the engine's net 0.99, ~4 % optimistic: the engine slices exits into the book). Rules
are judged on H1 (2 Sep–5 Oct) and H2 (6–27 Oct) separately, and on both strategies (same entry signal). In parallel,
a web review of the evidence (sources below).

## 1. The losses

| | oba-v1 |
|---|---|
| Losers | 47 of 80, −Rs 24.75 L (winners 33, +Rs 64.46 L) |
| Losers' best move in favour (median) | +0.61 ATR, reached 3 minutes after entry |
| Winners' best move (median) | +6.49 ATR, reached 143 minutes after entry |
| Losers never above +0.5 ATR | 22 (−Rs 12.2 L) |
| Below the entry 10 minutes in | 32 of 47 losers, 8 of 33 winners |
| Winners' worst adverse close (median) | 0.30 ATR; only 2 of 33 beyond 1.5 ATR |
| Exits by reason | STOP 24 trades −Rs 15.4 L (all losers, −24 % of premium on average) |

**No entry feature separates losers from winners.** Of the 50 stored fields, the ones that correlate with return in
both halves (close location of the last bar, VWAP distance, breadth, call OI change) are direction proxies: puts did
better than calls on these days (PE +Rs 30.6 L, CE +Rs 9.1 L). Taken in the trade's direction, none holds in both halves.

**The 09:30–09:44 window** (oba-v1: 12 trades, 1 win, −Rs 5.8 L, both halves) is not a rule: obc-v8's entries in the
same window made +Rs 2.69 L on 9 trades. oba-v1's losers there come from its lower all-day threshold (0.0446 vs the
morning 95th percentile 0.0627), which lets weaker morning bursts in.

**Losing days do not predict losing trades:** the win rate after a losing trade the same day is 42 % (31 trades),
the first trade of the day 41 % (41 trades). A tighter daily loss limit would only cut profit (the account kill switch
already exists).

## 2. Exits tested (change vs the current rules, Rs L, H1 / H2)

| Rule | Result | Verdict |
|---|---|---|
| Partial: sell half at +30 / +50 / +100 / +150 % | −4.8/−12.6, −5.7/−9.3, −3.2/−4.5, −3.2/−1.3 | Rejected: cuts the big winners |
| Premium trail (arm +20–100 %, give back 15–50 %) | every cell ≤ 0 | Rejected |
| Breakeven after +1 / +1.5 / +2 ATR | +0.7/−4.6, −0.1/−4.9, −0.7/+0.4 | Rejected |
| ATR trail (arm 1–4, distance 1.5–5) | current 2 / 3 sits on a plateau; distance 1.5–2 costs 5–10 L in H2 | Keep 2 / 3 |
| Tighter protective stop 1.0–1.75 ATR | oba: mixed; obc-v8: worse in H2 at every k | Keep 2 ATR |
| Early failure (N min, X ATR against) | oba: +1.0/+1.7 at (3, 0.5); obc-v8: −0.8/−6.2 | Rejected (not robust across the two) |
| No progress (N min, best < X ATR) | ≤ 0 in H2 everywhere | Rejected |
| Counter-burst only before the trade is +K ATR | oba −3.7/+8.2 at K = 1.5 | Rejected (flips between halves) |
| Expiry day: exit at 13:30–14:30 unless premium > entry × (1 + g) | 0 / −8.1 | Rejected |
| **Later flat time** | see below | **Candidate** |

**When winners peak:** 19 of oba-v1's 33 winning options made their high between 14:00 and 15:00; the real exits kept
a median 49 % of the peak gain (quartiles 20 / 70 %). Seven winners kept rising after a counter-burst exit, but
limiting that exit flips between halves.

**Holding obc-v8's morning trades into the afternoon** (flat time instead of 12:30; day bootstrap, 5,000 draws):

| Flat | Change | H1 / H2 | 90 % interval | P(> 0) | Without top 1 / 2 / 3 winners |
|---|---|---|---|---|---|
| 13:30 | +7.07 L | +2.12 / +4.95 | +0.51 to +14.79 | 0.97 | +4.39 / +1.96 / +0.89 |
| 14:30 | +7.26 L | +5.43 / +1.83 | −2.23 to +18.66 | 0.88 | +6.38 / +0.79 / +0.18 |
| 15:00 | +9.58 L | +7.83 / +1.75 | −0.83 to +22.83 | 0.93 | +2.90 / +2.26 / +1.29 |
| 15:15 | +9.07 L | +7.24 / +1.83 | −0.59 to +21.28 | 0.93 | +2.92 / +2.28 / +1.49 |

Better in both halves at every later time, still positive without the top three winners, but 6 days gain and 6 lose:
the gain is a few trend days carried into the afternoon. This matches the strongest outside evidence (intraday
momentum: the morning's direction predicts the last half hour; Gao et al. 2018, Baltussen et al. 2021).

## 3. Entry filter from the research, tested: the noise band (Zarattini, Aziz & Barbon 2024)

Keep a trade only if, at entry, the index is beyond the open ± k × its average move by that minute over 14 days
(gap-adjusted). Every k (0.5–1.5) is worse in both halves for both strategies (k = 1: obc-v8 −2.7 / −12.2, oba-v1
−6.0 / −19.5). The OI burst comes **before** the price leaves its noise range; waiting for price confirmation gives up
the move (it removes 15 Sep, 12 Oct, 15 Oct SENSEX). Rejected for this signal.

## 4. Conclusions

1. The losses are the price of the edge: a momentum entry that is right ~40 % of the time with winners ~3× the
   losers in size and ~45× in duration. Every early-exit and entry-filter rule tested removes winners with the losers.
2. Do not take profit early (partials, premium trails, breakeven, tighter trails all lose). Keep the 2 ATR stop and
   the 2 / 3 ATR trail.
3. The best time to exit is late: ~15:00–15:15, not 12:30. The one change worth a registered replay is
   **obc-v8 with flat 15:15** (A-260).
4. For the all-day variant, a threshold per part of the day (the morning 95th percentile before 11:00, the afternoon
   one after) removes the weak morning entries without a fitted rule (A-261, oba-v2).
5. All of this is in sample on 29 days and ~100 trades; the outside evidence (Harvey & Liu 2015; Bailey & López de
   Prado 2014) says to discount it for the number of variants tried here (~150 cells). Forward PAPER is the test.

## Sources (web review, 28 Oct 2026)

- Gao, Han, Li & Zhou, Market intraday momentum, JFE 2018 — https://www.sciencedirect.com/science/article/abs/pii/S0304405X18301351
- Baltussen, Da, Lammers & Martens, Hedging demand and market intraday momentum, JFE 2021 — https://www3.nd.edu/~zda/intramom.pdf
- Kaminski & Lo, When do stop-loss rules stop losses?, J. Financial Markets 2014 — https://dspace.mit.edu/bitstream/handle/1721.1/114876/Lo_When%20Do%20Stop-Loss.pdf
- Zarattini, Aziz & Barbon, Beat the market (SFI 24-97) — https://www.sfi.ch/de/publications/n-24-97-beat-the-market-an-effective-intraday-momentum-strategy-for-s-p500-etf-spy
- Muravyev & Ni, Why do option returns change sign from day to night?, JFE 2020 — https://ideas.repec.org/a/eee/jfinec/v136y2020i1p219-238.html
- Bhat, Pandey & Rao, Nifty options intraday / overnight returns, J. Futures Markets 2024 — https://ideas.repec.org/a/wly/jfutmk/v44y2024i8p1320-1337.html
- SEBI F&O study (93 % of individuals lost, FY22–24) — https://www.sebi.gov.in/media-and-notifications/press-releases/sep-2024/updated-sebi-study-reveals-93-of-individual-traders-incurred-losses-in-equity-fando-between-fy22-and-fy24-aggregate-losses-exceed-1-8-lakh-crores-over-three-years_86906.html
- Pan & Poteshman, The information in option volume, RFS 2006 — https://www.nber.org/papers/w10925
- Harvey & Liu, Backtesting, JPM 2015 — https://people.duke.edu/~charvey/Research/Published_Papers/P120_Backtesting.PDF
- Bailey & López de Prado, The deflated Sharpe ratio, JPM 2014 — https://papers.ssrn.com/abstract=2460551
