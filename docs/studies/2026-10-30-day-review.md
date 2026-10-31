# 30 Oct 2026 (Fri, no expiry): day review and replay

Sources: GCP's live sessions (SIGNAL-1 1055, PAPER-1 1056), its 21 shadow sessions (1057–1077) and the follower
positions (exec.position); the Mac replay of the day from own capture (`--replay-source=own`, Rs 5 L per session, image
24a8011-local = main, the code GCP ran; LIVE = the live set of five, LIVE4 = the four without obc-v13;
`.local/review-1030/`). Every shadow's Mac replay equals its GCP session to the rupee (orc −44,888, orb −47,597,
zbf −26,294, aob +1,03,248, oba −8,084, the rest 0), so the replay is faithful.

## The day

| | Prev close | Open | Low | High | Close (15:30) |
|---|---|---|---|---|---|
| NIFTY | 22,936.8 | 22,965.4 (+0.12 %) | 22,905.1 at 10:16 | 23,118.0 at 12:57 | 23,053.1 (+0.51 %) |
| SENSEX | 74,151.0 | 74,202.2 (+0.07 %) | 74,082.3 at 10:16 | 74,741.8 at 12:57 | 74,459.5 (+0.42 %) |

Flat to 10:00, a dip to the day's low at 10:16, a rally from 11:00 to 12:57 (NIFTY +0.93 % off the low), a fade from 13:00
to 14:00 (back to 23,007) and a chop to the close. India VIX 15.12 → 15.27. No expiry today (NIFTY 3 Nov, SENSEX 5 Nov),
so ecr-v11 and etr-v1 (expiry-day strategies) could not trade; no opening gap, so obc-v13 (gap rule) and gfd-v4b stayed
out; odm-v4 saw no opening drive (WATCH all day). **Only mac-v1 traded in the live set.**

## Live set

| | PAPER-1 (live) | SIGNAL-1 (live, signal account) | Replay at Rs 5 L | Replay without obc-v13 |
|---|---|---|---|---|
| Net | **+Rs 50,043** | +Rs 2,70,504 | +Rs 2,60,112 | +Rs 2,60,112 |
| Trades | 4 (2 won) | 4 (4 won) | 3 (3 won) | 3 (3 won) |

PAPER-1's trades (all mac-v1):

| Contract | In | Out | Qty | Entry | Exit | Reason | Net |
|---|---|---|---|---|---|---|---|
| NIFTY 23000 CE | 10:57 | 11:03 | 1,690 | 112.30 | 107.12 | OPERATOR (Exit All) | −9,176 |
| SENSEX 74300 CE | 10:57 | 11:03 | 320 | 583.83 | 558.38 | OPERATOR (Exit All) | −8,576 |
| SENSEX 74600 PE | 13:27 | 13:59 | 320 | 597.80 | 690.41 | TARGET | +29,143 |
| NIFTY 23100 PE | 13:27 | 13:59 | 1,235 | 149.86 | 181.54 | TARGET | +38,653 |

- **mac-v1: 4 of 4 targets on SIGNAL-1** (the 10:57 calls rode the 11:00–12:57 rally to TARGET at 11:50/11:51, the 13:27
  puts the 13:00–14:00 fade to TARGET at 13:59).
- **The Exit All test cost PAPER-1 about Rs 1.6 L.** The two morning calls were closed by the operator at 11:03 (−17.8 K);
  held, they would have reached the same targets as SIGNAL-1 (165.40 / 743.75), about +1.40 L at PAPER-1's size
  (estimate from SIGNAL-1's exits). Without the test PAPER-1 would have made about +2.1 L.
- **Replay at Rs 5 L (+2.60 L) vs SIGNAL-1 (+2.71 L):** the replay put 2,470 into the NIFTY 23100 PE (the RVOL 1.5x rule of
  risk v17) and then refused the SENSEX put for capital (premium 2.40 L + 3.70 L in use over 5 L); SIGNAL-1 split the two.
- LIVE = LIVE4: obc-v13 made no trade, so no crowding today.

## Shadows (each its own Rs 5 L)

| Strategy | Trades | Net | What happened |
|---|---|---|---|
| aob-v2 / v4 / v5 | 2 (2 won) | **+1,03,248** | NIFTY 23000 CE 11:10 → 13:06 TRAIL_15 +96.4 K; NIFTY 23050 PE 13:33 → 15:15 +6.8 K |
| oba-v1 / oba-v4 | 3 (1 won) | −8,084 | SENSEX 74200 CE 09:22 −17.3 K (trail), NIFTY 22950 PE 10:20 −44.7 K (stop, at the low), NIFTY 23000 CE 11:09 +53.9 K (counter-burst 13:33) |
| zbf-v10 | 1 | −26,294 | NIFTY 23000 PE 14:00 → 14:05 LEVEL_BACK |
| orc-v1 / v2 | 2 (1 won) | −44,888 | NIFTY 22900 PE 10:18 stopped 10:38 (−47.4 K, sold the low), SENSEX 74300 CE 11:03 → 14:03 +2.6 K |
| orb-v1 / v2 | 2 (1 won) | −47,597 | SENSEX 74500 CE 11:30 TARGET +63.1 K; NIFTY 23100 CE 12:00 PREMIUM_STOP 13:29 −1.11 L (bought near the top) |
| orb-v3, odm-v1/v2/v3/v5/v9, odb-v3/v9, obc-v8/v11, gfd-v4b | 0 | 0 | no setup (no drive, no gap, no burst cross) |

The 10:16 low sold by the put entries (oba, orc) and the 12:57 high bought by orb's call were the day's losers; the
trend-following entries that waited for the 11:00 turn (mac, aob, oba's third trade) made the money.

## Followers and accounts

- **DEMO-1** (family member demo, PAPER Rs 5 L, follows ecr/mac/obc/odm; first day): the four mac trades, all TARGET,
  gross +2,76,560, charges 2,454, **net about +Rs 2,74,106**.
- **ZERODHA-1** (DRY_RUN, ecr only): nothing to copy (no expiry, ecr made no trade).

## Nightly trackers (sync log)

- A-209 mac/VIX: +4 mac trades, previous VIX 15.12; sessions 5/30.
- A-243 S1 forward: 2 trades from 27 Oct, mean −1.3 %; read at 40.
- A-197 strangle: 0 closed cycles.
