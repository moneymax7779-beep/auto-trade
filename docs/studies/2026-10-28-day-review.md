# 28 Oct 2026 (Wed): day review and replay

Source: Mac replay of the day from own capture (`--replay-source=own`, Rs 5 L per session), main d8c69d2 image for the
live set and the 18 shadows, research image a260-local for oba-v1. Scripts and logs: `.local/review-1028/`.
GCP could not be read: the project's billing account is closed (`open: false`, billing disabled on zt-tiger-v2-prod,
seen 21:00 IST), so the live PAPER-1 / ZERODHA-1 records of the day are not in this review; the replay stands in for
them. The Mac sync loaded the day's md.* capture (option ticks 6.3 M rows) before the bucket became unreachable; the
instrument snapshot, VIX candles and zt's capture of the day are missing (the replay used the 27 Oct snapshot).

## The day

| | Prev close | Open | Low | High | Close |
|---|---|---|---|---|---|
| NIFTY | 22,749.8 | 22,613.5 (−0.60 %) | 22,498.1 at 10:22 | 22,777.7 at 13:00 | 22,698.9 |
| SENSEX | 73,598.7 | 73,053.0 (−0.74 %) | 72,604.2 at 10:22 | 73,525.0 at 13:00 | 73,246.0 |

Gap down, a choppy first half hour, a break of the previous day's low (NIFTY PDL 22,637) into 10:22, then a V reversal
(+1.24 %) that filled the gap by 13:00, and a flat-to-lower afternoon. Not an expiry day (SENSEX expires 29 Oct, NIFTY
3 Nov).

## Live set (replay; PAPER-1 equivalent at Rs 5 L)

| Strategy | Trade | In | Out | Qty | Entry | Exit | Reason | Net |
|---|---|---|---|---|---|---|---|---|
| mac-v1 | NIFTY 22600 CE | 11:00 | 12:00 | 1,300 | 183.9 | 199.3 | TIME_60 | +19,473 |
| mac-v1 | SENSEX 73000 CE | 11:03 | 12:03 | 680 | 354.9 | 388.8 | TIME_60 | +22,509 |
| mac-v1 | NIFTY 22700 PE | 14:06 | 15:06 | 1,430 | 169.2 | 173.4 | TIME_60 | +5,490 |
| mac-v1 | SENSEX 73300 PE | 14:06 | 15:06 | 780 | 317.9 | 301.9 | TIME_60 | −13,017 |
| **Total** | 4 trades, 3 wins | | | | | | | **+34,455** |

Live PAPER-1 (GCP daily summary, read after billing was restored at ~21:30): +Rs 15,520, the same four trades at
the same prices, at the account's equity-based sizes (NIFTY 585 / SENSEX 300–340 qty). The replay matches live.

ecr-v11 and etr-v1 trade expiry days only (none today). odm-v4 found no opening drive (WATCH all day); its older
versions v1/v2 took the 09:24 NIFTY put and lost Rs 91K, so v4's filters kept the live set out of the morning's losses.

mac's 60-minute time exit sold the two 11:00 calls at 12:00, before the 12:30–13:00 thrust: the SENSEX 73000 CE was bid
693 at 13:00 and 508 at 15:15 (exit 389); the NIFTY 22600 CE 307 at 13:00 and 244 at 15:15 (exit 199). One day; holding
mac longer was tested before (A-110 to A-112: pays only on trend days) and is not re-opened on this evidence.

## Shadows

| Strategy | Trades | Net | What happened |
|---|---|---|---|
| zbf-v10 | 3 (1 win) | +59,330 | 09:48 CE −21.8K, 10:17 PE −75.7K (LEVEL_BACK), 12:00 CE +1,56,807 (rode the reversal to 13:03) |
| aob-v2 / v4 / v5 | 1 each | +34,418 each | SENSEX 73300 CE 12:47–13:05, trail exit near the high |
| odm-v3 / v5 / v9 | 0 | 0 | no drive |
| obc-v8 / obc-v11 | 1 | −47,030 each | NIFTY 22600 PE 09:21, ATR trail 11:08 (v11 identical: out before 12:30) |
| orb-v1 / v2 / v3 | 1 | −66,656 each | NIFTY 22750 CE at 13:00, the day's high; stop 13:23 |
| odm-v1 / v2 | 1 | −90,966 each | NIFTY 22550 PE 09:24, held to 12:00 |
| odb-v3 / v9 | 1 | −1,01,809 each | NIFTY 22600 PE 09:16, PDL structure exit 09:48 |
| orc-v1 | 2 | −1,11,038 | 10:18 PE stop −94.2K, 12:45 CE −16.8K |
| orc-v2 | 2 | −1,20,282 | 10:06 SENSEX PE stop −1,03,481, 12:45 CE −16.8K |
| oba-v1 (research, A-258) | 2 | −1,10,835 | 09:21 PE −55.7K (counter-burst), 10:05 PE −55.2K (stop); account kill switch, no afternoon trade |

## Reading

1. Every put entered between 09:16 and 10:18 lost (8 distinct entries from odb, odm, obc, oba ×2, orc-v1, orc-v2 and
   zbf: −Rs 6.24 L, each version counted once). They
   were sold into a gap-down already 0.6–0.8 % below the close and near the previous day's low: the downside left
   was 0.4 % to 10:22, the reversal 1.2 %. The live set took none of them.
2. Every call entered on the reversal from 11:00 to 12:47 won (mac ×2, zbf, aob). The one call bought at 13:00 (ORB,
   after a 280-point run) bought the high.
3. The open-continuation family (odb, odm-v1/v2, orc, obc, oba) is wrong on a gap that reverses; this is the same
   failure as 26 Oct (open flush then V reversal). Two such days in three sessions. Not a rule yet: A-246's stand-aside
   and odm early-put block both failed on the tick days. A gap-size / distance-to-PDL condition for opening puts would
   be a new registered hypothesis, tested on bar days with a held-out split, not adopted from two days.
4. obc-v11 = obc-v8 today (the trade ended at 11:08). oba-v1 hit the account kill switch after two morning losses
   (−Rs 1.11 L = 22 % of Rs 5 L).
