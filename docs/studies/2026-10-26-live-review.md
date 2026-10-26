# Live review — Mon 26 Oct 2026 (first DRY_RUN day)

Sources: GCP session 978 (PAPER-1) and 977 (SIGNAL-1), tonight's shadow replays 979–991, the GCP → Mac sync
(re-run at 17:20 after Docker restarted mid-sync; all md / ref tables complete), own tick capture (index, futures,
options).

## The day in one line

Neither index expired, so etr and ecr had nothing to do. The open flushed down to 09:25 and then **reversed**: a V
bottom, a breakout at 09:50–10:00 and an up-trend to the close at the day's high. ma-cross bought **puts** three times
into that up-trend and lost all three.

| | Open | Low | 10:15 | Close |
|---|---|---|---|---|
| NIFTY | 22,524 | 22,388 (09:25) | 22,670 | **22,753** (high, 15:28; +366 off the low) |
| SENSEX | 72,979 | 72,515 (09:26) | 73,373 | **73,607** (high, 15:28; +1,092 off the low) |

## Results

| Account | Trades | Net |
|---|---|---|
| PAPER-1 (live PAPER) | 3 ma-cross puts, 0 wins | **−₹1,38,101** |
| SIGNAL-1 (signal source) | 4 ma-cross puts (one PAPER-1 refused for capital) | −₹1,43,643 |
| ZERODHA-1 (DRY_RUN, ecr only) | none — ecr had no signal (not an expiry day); ma-cross switched off for it | ₹0 |

PAPER-1's trades:

| Time | Trade | Exit | Net |
|---|---|---|---|
| 11:24 → 12:24 | NIFTY 22600 PE, 3,770 @ ₹97.77 → ₹91.78 | TIME_60 | −₹23,379 |
| 12:51 → 13:20 | SENSEX 73100 PE, 480 @ ₹508.63 → ₹397.24 | STOP | −₹53,975 |
| 12:54 → 13:19 | NIFTY 22600 PE, 2,470 @ ₹98.18 → ₹73.77 | PREMIUM_STOP | −₹60,747 |

## Why no opening-drive (odm) trade

The opening drive that existed today was **down**, and it was the wrong side.
- **NIFTY:** the 09:21 drive down qualified, but breadth vetoed it. By odm's known quirk, that one veto used up
  NIFTY's trade for the day (A-216/A-217).
- **SENSEX:** odm sent a buy for the 72600 PE at 09:21:00.202 (25 lots, limit ₹492.6). It didn't fill within 10
  seconds. The ask was ₹491.65–491.80 with only 20–40 shown, and ran to ₹522 by 09:21:11 as SENSEX kept falling.
- **What it would have done:** tonight's shadows that took both 09:21 puts lost. NIFTY −₹93,701 and SENSEX −₹55,350,
  both stopped at 09:54–09:55 when the reversal broke out. The veto and the missed fill saved about ₹1.49 L.

**The "great move" was the reversal up (09:30–10:15) and the afternoon up-trend.** odm only trades a drive away from
the open between 09:21 and 09:30, and at that point the drive was down. Nothing in the live set trades a reversal off
the opening low.

## What the order flow said (own capture, NIFTY near ATM ±3 strikes, weekly expiry 27 Oct)

| 5 min | Futures volume | Call OI change | Put OI change | Reading |
|---|---|---|---|---|
| 09:20 | 101,855 | +9.30 M | +1.00 M | writers selling calls into the fall: bearish |
| 09:25 | 157,495 | +4.72 M | +2.99 M | the low |
| **09:30** | 100,035 | −1.91 M | **+9.11 M** | **the flip: put writing, call covering** |
| 09:45 | 135,590 | −1.05 M | +4.72 M | put writing continues |
| **09:55** | **246,805** (day's highest) | **−10.48 M** | +5.77 M | call short-covering + breakout |
| 10:00 | 175,175 | −2.87 M | +4.75 M | follow-through |

SENSEX showed the same flip, on much thinner futures volume.

The OI flipped at 09:30, about 20–25 minutes before price broke out. This is the "writers switch sides at the opening
low" pattern you have pointed at before. **One day is not evidence.** OI-flow entry rules have failed out of sample
before:
- A-068: writer flow doesn't predict the next hour.
- A-121/A-123: OI flow break.
- A-152: OI pulse.
- A-157/A-162: an OI burst that worked in October but not over 166 bar days.

The shadows that use OI flow did catch it:
- **zbf-v10:** NIFTY CE 09:55 → 10:25, **+₹2,10,340**; +₹2.1 L on the day over 8 trades.
- **orc-v2:** NIFTY CE 09:54, +₹20,787.
- **aob-v2:** CE 09:58, +₹2,511.

The ORB shadow bought the 10:15 top and lost ₹1.06 L.

## What worked / what didn't

**Worked:**
- **ZERODHA-1's ecr-only decision (A-226).** It sat out ma-cross's −₹1.38 L.
- **Capital control.** PAPER-1 refused ma-cross's 11:27 SENSEX put for capital; on SIGNAL-1 that trade lost ₹11.6 K.
- **odm's breadth veto (by luck of the quirk) and the missed fill.** Together they avoided −₹1.49 L.
- **Operations.** The Upstox and Kite logins, the feed (no gaps flagged), and the 15:20 square-off all ran clean.

**Didn't work:**
- **ma-cross on a trend day.** Its three puts were crosses down inside short pullbacks of an up-trend day. Two of them
  (12:51 SENSEX, 12:54 NIFTY) went in within 3 minutes of each other on correlated indices, which doubled the same bet;
  the 13:20 resumption up stopped both.
- **VIX regime.** Yesterday's VIX close was 16.81. The A-209 forward tracker's first high-VIX day: 3 trades, −₹1.38 L,
  in line with A-208's finding (mac loses when the previous VIX is ≥ 15). It was screened out of sample in A-208 and
  failed there, so it stays a forward watch, not a rule.
- **No strategy for a non-expiry reversal or trend day.** The A-173 candidates all failed over two years.

## Data and ops issues found

1. **Live vs replay fills differ for the same order.** Live didn't fill the SENSEX 09:21 put at all. The replay of the
   same tick data filled 380 at ₹491.97. Replays of opening entries in fast markets look more fillable than live is,
   which matters for every replay result on odm. It's worth a registered check of the paper broker's live order
   timing against replay.
2. **The Mac sync was interrupted by a Docker Desktop restart at ~17:13** (watchdog). The re-run at 17:20 completed;
   no data was lost. The A-197 tracker was re-run too.
3. **odm's veto quirk mattered live today** (NIFTY). It is documented; changing it (odm-v16) failed A-217.

## Suggested next steps (none applied)

1. **Keep the live set unchanged.** One day doesn't justify a rule change. ma-cross's forward read is at 25 sessions
   (A-225).
2. **DRY_RUN days 2–3** (Tue, Wed). Tuesday is the NIFTY expiry, so ecr can actually signal. That is the first real
   test of ZERODHA-1's following and sizing.
3. If you want the OI flip studied properly, register it as a new rule. Example: after an opening flush of ≥ 0.4 %
   from the open, put OI added near ATM exceeds call OI added over 5 minutes, then a 3-minute close back above the
   flush midpoint → ATM call. Test it on all own-capture days and judge it on fresh days only. The prior evidence
   above says expect a FAIL.
