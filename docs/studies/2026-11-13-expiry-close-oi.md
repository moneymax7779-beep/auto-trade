# A-387 Expiry-day OI changes 15:20–15:40 (descriptive)

Script `docs/studies/2026-11-13-expiry-close-oi.py`, output `.local/a387/result.txt`. Own capture, expiring series only,
the strikes we capture (NIFTY ~100 contracts, SENSEX ~65), so totals are "our window", not the whole chain.
15 expiry index-days: NIFTY 29 Sep, 6/13/19/27 Oct, 3/9 Nov; SENSEX 1/8/15/22/29 Oct, 5/12 Nov; BANKNIFTY 27 Oct.
A-194 freeze kept: on 3/5/9/12 Nov no option price or index close after 15:20 was read (OI and volume only).

## Session structure (why the window is 15:20–15:40, not 15:20–15:30)

- Cash market: continuous trading ends 15:15, closing auction (CAS) 15:15 to a random stop at 15:28:05–15:29:52
  (`md.session_phase_event` UPSTOX_CAS_STOP). The index close, which is the expiry settlement price, is fixed then.
- Index options keep trading until 15:40 (NSE market timings page). After the CAS stop the expiring options trade at
  intrinsic value (e.g. 19 Oct 22900 CE at 0.05 from 15:29:40 with the close 22,888.95).
- Announced (IANS 7 Nov 2026, not yet checked against an NSE circular): from 7 Dec 2026 derivatives trade to 15:45,
  the CAS moves to 15:30–15:40, and one secondary source says expiring contracts go back to a 30-minute VWAP settlement.

## Q1 The OI clock does not stop

- NIFTY keeps its ~60 s print clock and SENSEX its exact 162 s clock through 15:40 (SENSEX prints e.g. 15:28:51,
  15:31:33, 15:34:15, 15:36:57, 15:39:39). The last print in our capture is 15:39:xx. Volume also keeps rising to 15:40.
- So **the OI on screen at 15:30 is not the end of the day**: a further 12–23 % of the 15:20 OI disappears after the CAS stop.

## Q2 Size: the expiring series is being closed, fast

| | NIFTY (7 days) | SENSEX (7 days) |
|---|---|---|
| OI 15:20 → CAS stop | −10 % to −19 % | −4 % to −16 % |
| OI CAS stop → 15:40 | −12 % to −16 % | −14 % to −23 % |
| OI 15:20 → 15:40 | −26 % to −32 % | −26 % to −34 % |
| net OI change per minute, 15:20–15:30 vs 14:22–15:20 | 2.7–5.0× | 2.5–7.6× |
| gross OI change per minute (sum of abs changes) | 1.3–3.7× | 2.1–3.2× |
| volume per minute | 1.1–2.6× | 2.0–2.4× |
| share of 15:20–15:40 volume traded after the CAS stop | 10–19 % | 12–21 % |

Every day is net closing; there is no day with fresh net OI in the window.

## Q3 Where (pooled, OI change as % of the strike group's 15:20 OI; final moneyness = against the close, 10 unfrozen days)

| Group | NIFTY 15:20→stop | NIFTY stop→15:40 | SENSEX 15:20→stop | SENSEX stop→15:40 |
|---|---|---|---|---|
| CE ITM at the close | −18 % | −19 % | −15 % | −25 % |
| CE OTM ≤ 1 strike | −10 % | −9 % | +7 % | +8 % |
| CE OTM > 1 strike | −16 % | −13 % | −14 % | −20 % |
| PE ITM at the close | +6 % | −20 % | +11 % | −21 % |
| PE OTM ≤ 1 strike | +10 % | −20 % | +28 % | +6 % |
| PE OTM > 1 strike | −18 % | −12 % | −13 % | −22 % |

- Far-OTM options (the bulk of the OI) are closed steadily at 0.05–a few rupees, before and after the settlement is known.
- Near-the-money puts gain OI into the CAS stop on most days, then lose it after; the day-level numbers swing from −41 % to +125 %
  on small bases, so this is not a stable pattern.
- ITM options are closed hardest after the settlement is known (a riskless sale at intrinsic value). The likely motive is the exercise
  STT on ITM options, which is not verified here.

## Q4 Does the OI flow say anything about the last minutes' price? (10 unfrozen days, counts only)

- Near-ATM (±3 strikes) put-minus-call OI flow in 15:20–15:25 had the same sign as the 15:25–15:30 synthetic-spot move on
  **4 of 10** days, which is a coin flip.
- It had the same sign as its own window's move on 8/10 (15:20–25) and **10/10** (15:25–30). OI follows price, as in A-154, A-383 and A-232.
- Pin: the close was nearer the 15:20 max-OI strike on 2 days and nearer the 15:20 ATM strike on 2; on 6 days they are the same strike.

## Reading (descriptive, no verdict)

1. The last 20 minutes of an expiry are a **liquidation**: about 30 % of the expiring OI is closed, half of it after the closing price is known.
2. Late OI changes are an **echo of price, not a lead**. Nothing here becomes a buy signal for 15:20–15:30.
3. After the CAS stop (≈15:28–15:30) expiring options carry no risk and no edge. Any OI move then is housekeeping, not positioning.
4. A-194 (frozen late-expiry forward test, read at ≥ 20 expiries ≈ mid-December): if the 7 Dec change is confirmed,
   expiries from 7 Dec settle under a different rule. Cutting A-194's sample at 4 Dec (before reading anything) needs the user's decision.

Sources for the session hours: NSE market timings (https://www.nseindia.com/static/market-data/market-timings),
NSE CAS page (https://www.nseindia.com/static/products-services/closing-auction-session),
IANS 7 Nov 2026 (https://ianslive.in/nse-to-overhaul-closing-auction-session-extend-derivatives-trading-hours-from-dec-7--20261107180834).
