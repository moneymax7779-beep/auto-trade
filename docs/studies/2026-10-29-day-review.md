# 29 Oct 2026 (Thu, SENSEX expiry): day review and replay

Sources: GCP's live sessions (PAPER-1 1034, SIGNAL-1 1033) and its 20 shadow sessions (1035–1054); the Mac replay of
the day from own capture (`--replay-source=own`, Rs 5 L per session, image 6582de7-local = the code GCP ran; LIVE = the
live set of five, LIVE4 = the four without obc-v13; `.local/review-1029/`). Every shadow's Mac replay equals its GCP
session to the rupee (orb +88,856, odb −11,224, zbf −33,694, aob −69,883, obc-v8 +3,77,339, obc-v11 +1,65,729,
oba +4,30,123), so the replay is faithful.

## The day

| | Prev close | Open | Low | High | Close (15:15) |
|---|---|---|---|---|---|
| NIFTY | 22,698.8 | 22,690.7 (−0.04 %) | 22,656.8 at 09:15 | 22,961.6 at 15:12 | 22,940.8 (+1.07 %) |
| SENSEX | 73,246.0 | 73,268.5 (+0.03 %) | 73,188.2 at 09:15 | 74,165.8 at 14:56 | 74,092.5 (+1.16 %) |

A flat open at the day's low, a first leg up to 10:30 (NIFTY +0.75 %), a range from 10:30 to 13:45 with a dip at 12:45,
and a second leg from 13:45 to 15:00. India VIX 16.2 → 15.1 (−7 %). SENSEX expired today; NIFTY expires 3 Nov. No
opening gap, so A-266's gap rule (oba-v4, obc-v13) changed nothing today.

## Live set

| | PAPER-1 (live, equity Rs 2.35 L) | SIGNAL-1 (live, signal account) | Replay at Rs 5 L | Replay without obc-v13 |
|---|---|---|---|---|
| Net | **+Rs 1,50,474** | +Rs 4,45,362 | +Rs 3,20,153 | +Rs 1,98,364 |
| Trades | 4 (3 won) | 5 (3 won) | 4 (3 won) | 3 (1 won) |

PAPER-1's trades (equity Rs 2,34,717, so budgets × 0.47):

| Strategy | Contract | In | Out | Qty | Entry | Exit | Reason | Net |
|---|---|---|---|---|---|---|---|---|
| obc-v13 | SENSEX 73400 CE | 09:43 | 12:49 | 740 | 232.27 | 340.40 | TRAIL_ATR | +79,456 |
| etr-v1 | SENSEX 73800 CE | 11:51 | 12:45 | 300 | 189.81 | 137.80 | SWING_LOW_LOST | −15,755 |
| mac-v1 | NIFTY 22850 CE | 13:27 | 14:25 | 715 | 152.35 | 181.64 | TARGET | +20,640 |
| etr-v1 | SENSEX 73900 CE | 13:36 | 15:15 | 760 | 142.19 | 229.77 | FLAT_BY | +66,132 |

Refused for capital: obc-v13's NIFTY 22800 CE at 10:10 (SIGNAL-1: +1,30,666), mac's NIFTY put at 12:42 (SIGNAL-1:
−38,523) and etr's two adds at 13:54/13:55 (SIGNAL-1 held 3,280 of the 73900 CE: +2,70,470).

- **obc-v13 (first live day): +Rs 79K live, the day's best strategy.** The 09:43 SENSEX call rode the first leg; its ATR
  trail sold at 12:49 into the 12:45 dip (the CE was ~470 at 12:30).
- **Crowding (A-267) on the day, at Rs 5 L:** with obc-v13 +3.20 L, without +1.98 L (**+1.22 L**). Its own +1.66 L, but its
  capital in use refused etr's two adds (etr +1.41 L vs +2.70 L without obc) and mac's 12:42 put (a −38.5 K loser, which
  freed mac to take the 13:27 call, +46.9 K). One day of the trade-off A-267 measured over 29 (set +, live four −).
- **etr-v1:** the 11:51 new-day-high call was stopped at 12:45 in the dip (−15.8 K); the 13:36 re-entry held to 15:15
  (+66 K, half size).
- **ecr-v11 (expiry days):** no trade. SENSEX ARMED from 10:16 to the close, but its confirmation (an ORH break on a strong
  breakout bar with retest and score at the same minute) never came together on a grinding trend: `broke_level` held in
  9 minutes and `breakout_bar` in 27, never with the rest.
- **odm-v4:** WATCH all day: no opening drive (the day's low was the open).

## Shadows (each its own Rs 5 L)

| Strategy | Trades | Net | What happened |
|---|---|---|---|
| oba-v1 / oba-v4 | 3 (2 won) | **+4,30,123** | 09:30 SENSEX CE −25 K (counter-burst), 09:41 73500 CE +2.59 L (counter-burst exit 10:40), 13:58 73900 CE +1.96 L to 15:15 |
| obc-v8 | 1 | **+3,77,339** | SENSEX 73400 CE 09:43 → 12:30 flat at 470.5, the morning's high |
| obc-v11 | 1 | +1,65,729 | the same entry, ATR trail at 12:49 (336.4) |
| orb-v1 / v2 / v3 | 1 | +88,856 | NIFTY 22850 CE 10:30 → 15:15 |
| odb-v3 / v9 | 2 | −11,224 | SENSEX 73300 CE 09:16 stopped 09:23, re-entry trailed 09:48 |
| zbf-v10 | 3 | −33,694 | NIFTY CE 10:10 +64 K, puts/calls 12:49 and 13:36 −98 K (LEVEL_BACK) |
| aob-v2 / v4 / v5 | 3 | −69,883 | NIFTY CE 10:10 +42 K, 12:51 put −1.11 L (premium stop), 14:27 CE flat |
| orc-v1 / v2, odm-v1 / v2 / v3 / v5 / v9 | 0 | 0 | no setup |

On a trend day the OI-burst family made the money; obc-v8's 12:30 flat happened to sell at the high (A-260 found 15:15
better over 29 days; one day does not change that). The 12:45 dip cost every strategy that reacted to it (etr's stop,
zbf and aob's puts).

## Followers and accounts

- **ZERODHA-1** (DRY_RUN, ecr only): nothing to copy (ecr made no trade).
- **`demo`** (GCP account 7, created 10:42, ecr + obc): +Rs 1,62,564 is **not real**: it booked the 09:43 SENSEX entry at
  the signal's price an hour after it was decided (the late-join bug, fixed in b590936 by `enabled_at`, V27). Disabled
  21:12.
- **DEMO-1** (family member demo, PAPER Rs 5 L, ecr/mac/obc/odm) was created at 21:03 and follows from tomorrow.

## Changes deployed tonight (main b590936 + compose, pushed)

Family members (own tenants following LIVE-SET), per-user sign-in with Keycloak on GCP, the late-join fix, per-account
ledger, member view of the index charts, finished chart days kept in the database and warmed at start. Trading logic,
the live set and the shadows are unchanged.
