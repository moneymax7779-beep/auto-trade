# Swing zones: what the 1-minute index data says (2 Jan – 16 Oct 2026)

User, 17 Oct 2026: "get all the days charts visually and try to understand the data at least on tick days, analyze
the data, don't assume anything, be serious, I want the data and any strategy can be derived". The user's charts
(25, 29, 30 Sep NIFTY) mark alternating highs and lows — zones where the index turned.

## Data and method

- 392 index-days: NIFTY and SENSEX, 196 sessions each, 2 Jan – 16 Oct 2026. Upstox 1-minute index bars for Jan–Sep
  (`hist.candle`), own capture (`md.index_tick`, aggregated to minutes) for 1–16 Oct.
- ATR3 = mean true range of the last 14 three-minute bars, carried across days. TWAP = running mean of the 1-minute
  typical price from 09:15. The index has no volume, so TWAP stands in for VWAP.
- Swings: zigzag on 1-minute highs and lows; a turn is confirmed when the price retraces 3 × ATR3 from its extreme.
- Every result is shown for H1 (Jan–May) and H2 (Jun–16 Oct). A pattern counts only if it has the same sign in both.
- Script `.local/zones-study/analysis.py`; tables `.local/zones-study/results.md`; charts of every day 2 Sep – 16 Oct
  with the swing points, ORH/ORL, PDH/PDL, open and TWAP: `.local/zones-study/charts.html`.
- The zigzag reproduces the user's marked zones (29 Sep NIFTY: the 09:15 top, the 09:40 low, the 12:05 top, the
  12:35 dip and the 13:45 higher low).

## Findings

**1. The swings are real and large.** A typical index-day has 7 swing points (middle half: 5–9) and the median swing
is 4.8 ATR (middle half: 3.8–6.9). Identical in both halves.

**2. Turns do not cluster at levels.** Share of swing turns within 0.5 ATR of a level, against the share of all
minutes within 0.5 ATR of it (ratio > 1 means turns are attracted to the level):

| Level | H1 | H2 |
|---|---|---|
| ORH | 0.99× | 1.14× |
| ORL | 0.79× | 0.94× |
| PDH | 0.59× | 0.92× |
| PDL | 0.88× | 1.17× |
| Previous close | 0.84× | 1.09× |
| Day open | 0.79× | 0.81× |
| TWAP | 0.54× | 0.51× |
| Round number (NIFTY 100, SENSEX 500) | 0.90× | 1.29× |
| An earlier swing of the same day | 0.93× | 1.37× |

No level attracts turns in both halves. Turns are *less* likely near TWAP (turns happen away from it).

**3. Stretch from VWAP/TWAP does not revert.** At 3-minute closes 09:45–14:45, the chance that the price first moves 1 ATR
back toward TWAP before 1 ATR further away is 42–54 % at every stretch level, in both halves; the mean 30-minute move is
within ±0.3 ATR. A 3-minute turn toward TWAP does not help (46 % and 47 % revert). The 9 Oct 13-day screen's "stretch
reverts over 30 minutes" was a small-sample result: on 392 index-days, more than 4 ATR below the ORL the price kept
going (extends first 55.8 % H1, 54.4 % H2; mean 30-minute move −0.48 / −0.18 ATR, i.e. further away).

**4. Trading every swing after it confirms loses or breaks even.** Entering at the 3-ATR retrace and exiting at the
next confirmation earns leg − 6 ATR per leg: −0.21 ATR (H1), +0.01 ATR (H2), 32–36 % winners, before option costs.

**5. First touches of levels are coin flips, with one modest exception.** First touch (within 0.25 ATR) of a level after
09:45, outcome: close 1 ATR back (reject) or 1 ATR through (break) within 30 minutes:

| Level, approach | H1 reject / break (n) | H2 reject / break (n) |
|---|---|---|
| ORL from above (support) | 54.8 % / 34.6 % (104) | 50.5 % / 32.7 % (101) |
| Previous close from above | 59.2 % / 36.7 % (49) | 52.1 % / 35.4 % (48) |
| ORH from below (resistance) | 42.3 % / 45.2 % (104) | 54.5 % / 32.3 % (99) |
| PDH from below | 20.0 % / 67.5 % (40) | 48.7 % / 38.5 % (39) |
| Day open from below | 53.2 % / 38.7 % (62) | 40.7 % / 35.6 % (59) |

Only ORL (and the previous close) tested from above held more often than they broke in both halves, about 61 % of
decided cases. At 1 ATR either way that is roughly +0.2 ATR per trade in index points, which an ATM option's spread,
costs and time decay would mostly absorb.

**6. The open and the close make the day's extremes.** Share of index-days whose high (low) formed in each window:

| Window | High H1 / H2 | Low H1 / H2 |
|---|---|---|
| 09:15–09:30 | 32 % / 35 % | 27 % / 28 % |
| 09:30–10:15 | 11 % / 6 % | 9 % / 8 % |
| 10:15–11:30 | 10 % / 10 % | 11 % / 10 % |
| 11:30–13:00 | 8 % / 16 % | 7 % / 9 % |
| 13:00–14:30 | 13 % / 15 % | 19 % / 14 % |
| 14:30–15:30 | 26 % / 19 % | 27 % / 30 % |

On about 60 % of days one end of the day is set in the first 15 minutes; midday rarely makes the extreme.

**7. A strong first hour keeps the close on its side, but the move after 10:15 is small.** When the index is ≥ 4 ATR
from the open at 10:15, it closes on that side 75–100 % of the time (H1/H2, both directions). The continuation from
10:15 is small and positive only for those strong first hours (+0.12 / +0.65 ATR to 12:00, +0.35 / +0.65 to 15:15);
weaker first hours retraced in H1 and continued in H2. Trend days (|close − open| ≥ 60 % of the range) are 39 % (H1)
and 43 % (H2) of index-days; the opening-range width does not predict them (37–46 % in every width bucket).

## What can and cannot be derived

- **Not supported:** fading or trading the zones as reversal points; level-based turns; VWAP/OR stretch reversion;
  trading every swing. The turns the eye picks out on a finished chart are where the swings happened to end; their
  position relative to levels or VWAP is no better than chance in this data.
- **Supported, and already traded:** the open sets one end of the day on ~60 % of days and a strong first hour holds
  its side — the opening-drive strategy (odm-v4, live) trades exactly this; expiry-day trends are the trend rider's.
- **Supported but too small to trade with options:** first tests of ORL / previous close from above hold ~61 % of
  decided cases (≈ +0.2 ATR gross per trade).
- **Consequence for A-130 (stretch reversion, sr-v1):** finding 3 predicts it will not pass; its option-fill replay
  is the check.
