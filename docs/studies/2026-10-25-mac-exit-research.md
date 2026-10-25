# ma-cross: is the 60-minute exit right, and what should replace it? (A-220, A-220b, A-220c)

You challenged ma-cross's fixed 60-minute exit on 25 Oct: *"this should be based on market data, levels and other
factors"*. This note is the research behind the answer. Every rule was registered in the ledger before it was run.

## Short answer

- **You're right that 60 minutes has no derived basis.** It was fixed when ma-cross was registered (A-066, 9 Oct) and
  never tuned. Two years of bars show the best holding time **changes with the market regime**: 2024–25 rewarded holding
  much longer, 2026 rewarded cutting at about an hour.
- **Swapping the clock for a market-data exit does not help.** Structure trail, EMA20, VWAP, chandelier, momentum fade,
  stall, volatility-scaled time, level targets and a premium trail were all worse out of sample. Most were worse
  everywhere.
- **What does help is making the 60-minute check conditional on market data (V12).** At 60 minutes, keep the trade only
  if it is **working (≥ +0.5R)** and its **trend is intact** (3-minute close on the trade's side of VWAP and EMA20).
  Ride the kept trades with a swing trail; exit the rest. This beat the fixed 60 minutes in all three periods
  (+₹4.6 L, +₹2.8 L, +₹1.0 L). In the 2026 validation the gain is within the noise (95 % CI −₹4.5 L to +₹7.0 L).
- **ma-cross's real problem is not the exit.** Over two years its swing stops cost about ₹1.88 Cr, against
  +₹1.44 Cr from targets and +₹31 L from time exits (lab rupees, ₹2.5 L a trade). The entries and stops (chop
  whipsaws) are where the money goes.

## Why a time stop exists at all (market structure)

- **The option holder pays for time.** ma-cross buys an ATM weekly option. With 2–5 days to expiry, an ATM NIFTY option
  loses a material part of its premium each hour the index goes nowhere. The decay speeds up nearer expiry and after
  lunch, when IV usually settles. An index stall *is* a loss for a premium buyer, even with the stop untouched.
- **Signal decay.** An EMA9/EMA20 cross on 3-minute bars says "momentum turned in the last few bars". That information
  has a half-life. When the target has not been reached after about 20 bars, the original impulse is gone.
- **So a time element is legitimate.** The question is whether one fixed number fits all regimes. The data says no.

## What was tested

- **Method:** ma-cross was rebuilt in a research model (`docs/studies/2026-10-25-mac-exit-lab.py`). Its entries match
  **96 %** of the engine's 1,045 ma-cross trades on the A-199 bar days.
- **Data:** about 1,350 trades on NIFTY and SENSEX, Oct 2024–Sep 2026, 1-minute bars.
- **Pricing:** the ATM weekly option, ±0.75 % a side (also ±0.25 % and zero cost).
- **Samples:** design Oct 2024–Jun 2025 and Jul–Dec 2025; validation Jan–Sep 2026.
- **Fixed parts:** every variant keeps the entry, the initial swing stop, the 25 % premium stop and 15:15. Only the exit
  changes.

| Exit | Market data it uses | Oct 24–Jun 25 | Jul–Dec 25 | Jan–Sep 26 |
|---|---|---|---|---|
| **V0 mac-v1: 2R + 60 min** | time | −₹5.9 L | −₹9.8 L | −₹12.6 L |
| V1 2R, no time stop | — | +₹5.8 L | −₹1.1 L | −₹16.0 L |
| V2 structure trail | 3-min swings | −₹12.8 L | −₹17.1 L | −₹20.7 L |
| V3 close across EMA20 | trend MA | −₹19.4 L | −₹16.5 L | −₹30.9 L |
| V4 close across VWAP | VWAP | −₹4.6 L | −₹2.0 L | −₹25.4 L |
| V5 chandelier 2 ATR | volatility | −₹12.2 L | −₹2.4 L | −₹14.3 L |
| V6 EMA9 momentum fade | momentum | −₹20.8 L | −₹14.2 L | −₹17.6 L |
| V7 stall 30 min + EMA9 | momentum + time | −₹10.1 L | −₹1.9 L | −₹15.9 L |
| V8 volatility-scaled time + trail | ATR, target distance | −₹25.5 L | −₹6.2 L | −₹17.3 L |
| V9 level target (PDH/PDL, ORH/ORL, PWH/PWL, PMH/PML) | levels | −₹1.9 L | −₹13.4 L | −₹17.4 L |
| V10 premium trail 20/15 | option premium | +₹5.0 L | −₹3.2 L | −₹18.9 L |
| V11 trail + level target + premium trail | combined | −₹14.6 L | −₹22.6 L | −₹26.1 L |
| **V12 conditional time stop** | profit, VWAP, EMA20 at 60 min | **−₹1.3 L** | **−₹7.0 L** | **−₹11.6 L** |

The totals are negative because the lab charges ±0.75 % a side on every fill. At ±0.25 % they are V0 +₹5.9 L / −₹1.1 L
/ +₹0.6 L and V12 +₹10.4 L / +₹1.6 L / +₹1.5 L. **Compare the rows, not the signs.**

**The time-stop sweep (A-220b)** shows why no single number works:
- Picked on 2024–25, the best choice would be "no time stop" (+₹4.8 L against 60 minutes' −₹15.6 L).
- On 2026, 60 minutes is the best of all the times tried (−₹12.6 L, against −₹16.0 L for no time stop).
- The trades the 60-minute stop cut would have gained +₹5 L in each 2024–25 half if held, and **lost ₹5.3 L in 2026**
  (62 went on to the stop, 40 to the target).

## Why V12 is the sensible "dynamic" exit

- At 60 minutes it asks the market three questions:
  1. Is the trade in profit by at least half its risk?
  2. Is price still above (below) VWAP, the session's fair value?
  3. Is price still on the right side of the 3-minute EMA20, the trend?
- **All yes:** the move is alive. The trade is kept, the stop trails each new swing, and the 2R target stays.
  - 165 trades were kept past an hour.
  - They ended TARGET 49, STOP 104 (64 of them trailed stops at a profit; the 104 net +₹10.3 L), 15:15 12.
  - Net +₹60.6 L.
- **Any no:** the 331 trades that weren't working, or whose trend had broken, were cut at 60 minutes (−₹29.0 L). Holding
  them would most likely have bled more.
- **Caveats:**
  - 13 exits were tried, so one consistent winner can still be luck.
  - The 2026 improvement (+₹1.0 L) is within the noise.
  - This is a 1-minute bar model: tick replays (the engine) must confirm it.

## Recommendation

1. **Keep mac-v1 live for now.** No exit has cleared the registered bar for an engine test.
2. **If you want to pursue V12**, the next step is an engine version, **mac-v7** = mac-v1 + the conditional time stop.
   It would be replayed against mac-v1 on the bar days and the 27 tick days, inside the live set (capital effects), then
   run as a GCP shadow. I have not built it, because the registered rule doesn't grant the engine test. Say if you want
   it anyway.
3. **The bigger lever is the entry and stop.** 568 swing stops cost about ₹1.88 Cr in the lab. Worth a separate study:
   - stop placement (swing vs ATR-based);
   - not taking crosses inside a tight range (chop);
   - the 10:15 warm-up blind spot (21 Oct).

   Earlier entry filters failed: VWAP side (A-079), VIX (A-208), seeded EMAs (A-156).
