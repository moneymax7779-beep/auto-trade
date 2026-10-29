# Stocks for option buying, and stocks that confirm index moves (research note, 8 Oct 2026; not a hypothesis test)

User, 8 Oct evening: "understand the most happening stocks that suits for options buying" and "which stocks help us
confirm the trend or trade continuation on indices". Data analysis only; nothing registered or deployed.

## 1. Option-buying screen (213 NSE F&O stocks)
Upstox public daily bars, 60 sessions to 8 Oct for each stock; the October-monthly (27 Oct) ATM call and put of each
stock, daily bars since 1 Sep. Measures: average day range and |close-to-close| move, share of days moving ≥ 2 %,
the ATM options' median intraday swing (high/low − 1), and ATM call+put premium traded per day (₹ crore and lots).
Only 4 stocks trade ≥ ₹5 Cr/day and ≥ 2,000 lots/day in their ATM call+put (POLICYBZR, BSE, BAJAJ-AUTO, HDFCBANK);
23 at ≥ ₹2 Cr and ≥ 1,000 lots. Ranked by range, option swing, big-move days and option liquidity: POLICYBZR, BSE,
MCX, ADANIGREEN, PREMIERENE, MAXHEALTH, SOLARINDS, DIXON, BAJAJ-AUTO, FORTIS, ... Limits: one strike of a monthly
series; no bid/ask spreads (needs live quotes); only monthly expiries (27 Oct, 23 Nov, 29 Dec).

## 2. Stocks that confirm index swings (80 NIFTY/SENSEX constituents, minute bars 8 Sep–6 Oct)
Index swing starts of 0.15 % (141 NIFTY, 147 SENSEX); a start "continued" when it reached 0.35 % from its pivot before
reversing 0.15 % (32 % and 29 % did). For each constituent: did it move ≥ 0.10 % the same way over the same minutes,
and how often did the swing continue when it did vs did not ("lift"). Highest lift in both indices: AXISBANK,
BAJFINANCE, BAJAJFINSV, TITAN, TECHM, ETERNAL (+8 to +21 points). The heavyweights (HDFCBANK, RELIANCE, BHARTIARTL,
LT) add little or nothing (−7 to +6): they are a large part of the index move itself.
A majority of the six-stock basket confirming: NIFTY 8–22 Sep 52 % vs 14 %, 23 Sep–6 Oct 32 % vs 22 %; SENSEX 41 % vs
7 %, 34 % vs 26 %. Chosen from the same 19 days, so in-sample; a strategy using it must be registered and tested on
new days.

## 3. Q2 results season (NSE event calendar, read 8 Oct evening)
232 companies have announced Q2 result dates (9 Oct–13 Nov); 72 are F&O stocks, 9 of them with ATM options trading
≥ ₹2 Cr and 1,000 lots a day: JIOFIN 15 Oct, FEDERALBNK 16 Oct, HDFCBANK 17 Oct (Sat), ICICIBANK 17 Oct (Sat), INFY
23 Oct, DRREDDY 23 Oct, BAJAJ-AUTO 27 Oct, MARUTI 27 Oct, BAJAJFINSV 3 Nov. Index movers with thinner options:
AXISBANK 17 Oct, BAJFINANCE 19 Oct, KOTAKBANK 24 Oct, HINDUNILVR 28 Oct, M&M 5 Nov. Not yet announced on 8 Oct: TCS,
RELIANCE, SBIN, LT, BHARTIARTL, ITC. Last quarter's results-day move (larger of the day and the next day) against
the stock's normal day: HDFCBANK 5.1 % (5.0×), BAJAJ-AUTO 5.7 % (4.7×), BAJAJFINSV 6.3 % (6.1×), FEDERALBNK 6.9 %
(5.9×), AXISBANK 5.5 % (5.3×), BAJFINANCE 8.3 % (6.2×), HINDUNILVR 7.0 % (6.6×); ICICIBANK 1.1 %, INFY 0.6 %,
MARUTI 0.6 % (small). One quarter only. HDFCBANK, ICICIBANK and AXISBANK (about 22.6 % of NIFTY) report on Saturday
17 Oct, so Monday 19 Oct, a NIFTY weekly expiry, opens on their results.
