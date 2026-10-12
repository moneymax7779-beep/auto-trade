# Historical Options Data Request

*11 October 2026*

We are looking for **historical tick data with best bid and ask** for NIFTY and SENSEX weekly index options, their
futures and the indices themselves, from **1 January to 30 September 2026**, for internal backtesting by a single
user. Before buying, we would like **two sample days** to check against our own recordings.

## 1. What we need

| # | Dataset | Instruments | Priority |
|---|---|---|---|
| 1 | Index options | NIFTY and SENSEX options, the nearest two expiries (weekly and monthly), calls and puts, strikes within ±15 strikes of the at-the-money strike through each day, **including expired contracts** | Essential |
| 2 | Index futures | NIFTY (NSE) and SENSEX (BSE) futures, near and next month | Essential |
| 3 | Indices | NIFTY 50, SENSEX, India VIX: every published value | Essential |
| 4 | Index constituents | The 50 NIFTY and 30 SENSEX stocks: ticks, or 1-minute bars if ticks are not available; plus the daily index weights | Useful |
| 5 | Pre-open and closing auction | Indicative prices and quantities for the indices' constituents in the pre-open (09:00–09:15) and closing session (15:30–15:40) | Optional |

Exchanges: NSE (NIFTY, NIFTY futures and options, India VIX, NIFTY constituents) and BSE (SENSEX, SENSEX futures and
options, SENSEX constituents). If you cover only one exchange, please say which.

## 2. Fields per record

**Options and futures, one row per update (trade or quote change):**

| Field | Notes |
|---|---|
| Exchange timestamp | IST, millisecond precision or better |
| Receive timestamp | If you record it as well, with how it is measured |
| Instrument identifiers | Exchange token, trading symbol, underlying, expiry date, strike, CE/PE, lot size |
| Last traded price and quantity | Per trade, with a flag that marks trade rows and quote-only rows |
| Cumulative volume | For the day |
| Open interest | As published, with its own timestamp if updated separately |
| Best bid price and quantity | Required |
| Best ask price and quantity | Required |
| Market depth, 5 levels each side | Preferred; please state whether every change is captured or snapshots at an interval |
| Total buy and total sell quantity | If available |

**Indices:** timestamp and value for every update. **Constituents:** as options, or OHLC and volume per minute.

## 3. Period, format and delivery

- **Period:** 1 January 2026 to 30 September 2026, every trading day, 09:00–15:40 IST. Please quote separately for an
  ongoing daily delivery from October 2026.
- **Format:** CSV or Parquet, one file per day per underlying (or per day per dataset), compressed. A data
  dictionary describing every column.
- **Delivery:** download link or cloud bucket. Total size estimate in the quote, please.

## 4. Quality requirements

- No gaps longer than 5 seconds for actively traded at-the-money contracts during 09:15–15:30, or a list of the gaps
  that exist, with dates and times.
- A list of known feed outages or exchange disruptions in the period.
- How the data was captured (direct exchange feed or a broker feed), and whether any rows were filled, interpolated
  or deduplicated.
- Corrections policy: whether the files are final or may be revised.

## 5. Sample request

Two full trading days, all datasets above, before any purchase:

- **Tuesday 29 September 2026** (a NIFTY weekly expiry)
- **Thursday 1 October 2026** (a SENSEX weekly expiry)

We recorded both days ourselves and will compare your files with our recordings, tick by tick, for the indices, the
futures and a set of at-the-money options. A close match is our condition for buying the full period.

## 6. Questions to answer in your quote

1. Coverage: dates, both exchanges or one, expired weekly options included, how many strikes per expiry.
2. Bid/ask: best level only or 5-level depth; every change or interval snapshots (and the interval).
3. Price for 1 January – 30 September 2026, and for an ongoing daily delivery.
4. Licence terms for internal research use by one user (no redistribution needed).
5. Delivery time after payment, and the sample delivery time.
6. Total data size.
