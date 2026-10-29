# 27 Oct 2026 — what happened, minute by minute (NIFTY expiry, NIFTY monthly futures expiry)

User, 27 Oct evening: "what exactly happened through out the day from 9.15AM? what is the level, what is the OI changes,
1min, 3min, 5min, 15min, 30min, future volume, is OI building continuously time wise ... I would like to understand how
a real trader trades, I want the data, what is the VIX".

Source: our own capture on GCP, synced to the Mac at 18:00 (md.index_tick, md.option_tick, md.future_tick,
ref.index_candle for India VIX). Script `.local/day-2026-10-27/day.py`; every minute with all windows in
`.local/day-2026-10-27/minutes-NIFTY.csv` and `minutes-SENSEX.csv`; printed tables in `summary.txt`.

## How to read the numbers

| Item | Definition |
|---|---|
| ATM ± 5 OI change over N minutes | At each minute: ATM = spot rounded to the strike step (50 NIFTY, 100 SENSEX); the 11 strikes ATM−5 … ATM+5 of the nearest expiry (NIFTY 27 Oct = expiring today, SENSEX 29 Oct); sum over those strikes of OI(now) − OI(N minutes ago), **in lots** (NIFTY 65, SENSEX 20 units). Strikes are fixed at that minute, so the band moving with price does not create fake changes |
| + / − | + = new positions opened (writing or buying, OI cannot tell which); − = positions closed (covering or unwinding) |
| Futures volume × normal | Futures volume of the minute (or block) ÷ the median of the same minute over the previous 19 sessions |
| Futures OI | Nearest future. **NIFTY's October future expired today**, so its OI falls all day from rollover/closing — not a market signal. SENSEX futures OI prints jump in steps of ~85,000 and back: unreliable, not used |
| SENSEX OI | BSE updates option OI only every ~2.5–3 minutes (A-152), so SENSEX 1- and 3-minute OI changes are mostly 0; use 5 minutes and more |
| Index 15:15–15:30 | The index print freezes on expiry afternoons (expiry-close regime); 22,749.8 is the frozen value |

## Levels

| Level | NIFTY | SENSEX |
|---|---|---|
| Previous close (official, set after the session) | 22,753.2 | 73,606.75 |
| Previous day high (last trade before 15:15) | **22,721.05** | 73,510.71 |
| Previous day low | 22,387.45 | 72,514.57 |
| Last week high / low (19–23 Oct) | 23,174.5 / 22,372.25 | 74,813.12 / 72,452.64 |
| Today's open (09:15) | 22,705.6 (below the previous close; the 22,841 seen earlier was a pre-open quote) | 73,395.24 |
| Opening range 09:15–09:30 high / low | 22,745.95 / 22,653.5 | 73,500.92 / 73,213.08 |
| Day high | **22,829.7 at 12:43** | 73,762.98 at 12:43 |
| Day low | **22,637.1 at 14:17** | 73,179.41 at 14:16 |
| Close (last 15:29) | 22,749.75 | 73,598.67 |

Range after the opening range: 193 points NIFTY (0.85 %), both directions, ending where it started.

## India VIX: falling all day

17.13 previous close → 17.45 at 09:16 (the day's high) → 17.06 at 10:15 → 16.71 at 12:15 → 16.65 at 12:45 → 16.68 at
14:15 → **16.27 at 14:54 (low)** → 16.36 close. No rise at either the 12:43 high or the 14:17 low: neither move came
with fear or demand for options. A falling VIX on an expiry day is the option sellers' day.

## NIFTY by 15 minutes

Calls / puts = ATM ± 5 OI change over the block, lots.

| Block | O | H | L | C | Move | Fut vol × | Calls | Puts | What it was |
|---|---|---|---|---|---|---|---|---|---|
| 09:15–09:30 | 22,677 | 22,750 | 22,654 | 22,734 | +57 | 1.0 | +628,266 | +444,464 | Both sides written heavily from the first minute |
| 09:30–09:45 | 22,736 | 22,767 | 22,705 | 22,752 | +16 | 1.1 | +202,701 | +170,896 | Still both sides |
| 09:45–10:00 | 22,752 | 22,782 | 22,728 | 22,781 | +28 | 1.3 | +140,401 | +168,063 | |
| 10:00–10:15 | 22,779 | 22,804 | 22,762 | 22,767 | −12 | 2.2 | −100,473 | +180,778 | First push to 22,804 failed |
| 10:15–10:30 | 22,769 | 22,770 | 22,685 | 22,728 | −41 | 1.8 | +104,607 | −217,145 | Dip; put positions closed |
| 10:30–10:45 | 22,729 | 22,773 | 22,722 | 22,761 | +32 | 1.8 | −60,856 | +131,922 | Back to 22,750 |
| 10:45–11:00 | 22,764 | 22,784 | 22,758 | 22,765 | +1 | 2.2 | −26,991 | +184,478 | |
| 11:00–11:15 | 22,765 | 22,770 | 22,736 | 22,738 | −27 | 1.5 | −11,277 | +152,303 | |
| 11:15–11:30 | 22,739 | 22,753 | 22,719 | 22,752 | +13 | 3.3 | +199,974 | −175,563 | 11:28 futures 14.8× normal, no price move |
| 11:30–11:45 | 22,753 | 22,768 | 22,749 | 22,750 | −3 | 1.9 | +62,190 | +70,616 | |
| 11:45–12:00 | 22,750 | 22,783 | 22,744 | 22,779 | +29 | 2.2 | −17,370 | +52,491 | |
| 12:00–12:15 | 22,780 | 22,780 | 22,752 | 22,757 | −23 | 1.4 | −5,749 | +122,633 | |
| 12:15–12:30 | 22,759 | 22,762 | 22,740 | 22,760 | +2 | 1.8 | +58,379 | −13,956 | |
| **12:30–12:45** | 22,761 | **22,830** | 22,760 | 22,814 | **+53** | **3.0** | **−277,698** | +55,959 | **Call short covering → day high 12:43** |
| 12:45–13:00 | 22,815 | 22,817 | 22,769 | 22,769 | −46 | 1.4 | +119,143 | −15,385 | Calls written back into the high |
| 13:00–13:15 | 22,771 | 22,772 | 22,701 | 22,714 | −57 | 2.0 | +155,120 | **−341,733** | Put writers left; fall |
| 13:15–13:30 | 22,714 | 22,718 | 22,678 | 22,692 | −22 | 1.6 | +161,319 | −57,697 | |
| 13:30–13:45 | 22,693 | 22,717 | 22,690 | 22,700 | +7 | 1.2 | +149,742 | +62,351 | |
| 13:45–14:00 | 22,702 | 22,715 | 22,691 | 22,692 | −10 | 0.8 | +2,996 | +63,425 | |
| **14:00–14:15** | 22,693 | 22,693 | **22,639** | 22,647 | **−46** | **2.5** | +47,762 | **−193,617** | **Put covering → day low 14:16–14:17** |
| 14:15–14:30 | 22,646 | 22,679 | 22,637 | 22,674 | +28 | 2.3 | +155,463 | +81,918 | |
| **14:30–14:45** | 22,675 | 22,754 | 22,667 | 22,720 | **+45** | 1.5 | **−488,227** | −86,042 | **Call short covering** back to 22,750 |
| 14:45–15:00 | 22,720 | 22,777 | 22,712 | 22,762 | +42 | 1.4 | −294,731 | +62,259 | |
| 15:00–15:15 | 22,764 | 22,769 | 22,741 | 22,761 | −4 | 1.3 | −25,365 | −154,264 | |
| 15:15–15:30 | 22,761 | 22,761 | 22,750 | 22,750 | −11 | 1.4 | −336,938 | −330,511 | Index frozen; positions closed into expiry |

## Was OI building continuously?

NIFTY: yes in the sense that ATM ± 5 OI changed **every single minute** (60-second exchange updates), but not one way:

| Window | Calls (net, lots; minutes up / down) | Puts (net; up / down) |
|---|---|---|
| 09:15–09:45 | **+722,222** (22 / 8) | **+650,410** (26 / 4) |
| 09:45–10:15 | +36,429 (21 / 9) | +373,173 (22 / 8) |
| 10:15–11:15 | +32,087 | +261,720 |
| 11:15–12:15 | +243,642 | +74,459 |
| 12:15–12:45 | **−221,770** (18 / 12) | +23,375 |
| 12:45–13:45 | **+563,330** | **−327,165** |
| 13:45–14:15 | +46,762 | −133,364 |
| 14:15–15:15 | **−658,428** (29 / 31) | −100,836 |
| 15:15–15:30 | −336,938 | −330,511 |

The first 30 minutes were the only stretch of steady one-way building (both sides). After that, OI came and went in
bursts that matched each swing.

**The largest single moves of the day (NIFTY, lots):**

| Window | Biggest add | Biggest cut |
|---|---|---|
| Calls 1 min | +135,447 at 09:16 | **−263,500 at 12:43 (the day high, 22,821)** |
| Puts 1 min | +125,032 at 09:16 | −210,440 at 10:21 |
| Calls 3 min | +256,463 at 09:18 | −342,184 at 14:40 |
| Puts 3 min | +279,716 at 09:18 | −256,605 at 10:21 |
| Calls 5 min | +361,859 at 09:20 | **−506,443 at 14:40** |
| Puts 5 min | +299,146 at 09:20 | −309,104 at 10:21 |
| Calls 15 min | +628,266 at 09:30 | −586,373 at 14:41 |
| Puts 15 min | +444,464 at 09:30 | −342,068 at 15:29 |
| Calls 30 min | +830,967 at 09:45 | −789,831 at 15:00 |
| Puts 30 min | +615,360 at 09:45 | −533,939 at 15:29 |

Futures volume ≥ 5× normal (NIFTY): 10:02, 10:49, 10:51, 11:23 (10.5×), 11:28 (14.8×), 11:29, 11:45, 12:38, 12:40 (7.7×),
12:42, 14:11, 14:15, 14:23. The two biggest (11:23, 11:28) moved price by almost nothing.

## Where positions ended (NIFTY 27 Oct expiry, 09:15 → 15:29, lots)

| Strike | Calls added | Calls at end | Puts added | Puts at end |
|---|---|---|---|---|
| 22,650 | −9,428 | 44,697 | +20,656 | 146,650 |
| 22,700 | −5,525 | 166,520 | **+151,384** | **352,250** |
| **22,750** | **+116,063** | 207,166 | **+280,179** | **356,853** |
| 22,800 | **+171,772** | **353,752** | +35,605 | 119,985 |
| 22,850 | +49,699 | 125,895 | +11,579 | 26,810 |
| 23,000 | −45,309 | 254,382 | −7,985 | 31,440 |

New positions all day went into **22,700–22,800**, centred on 22,750: puts below, calls above, both at 22,750. Strikes
away from the day's range (22,400–22,600 and 23,000) lost OI. NIFTY closed at 22,749.75.

## The two etr trades in this data

| | 12:43 NIFTY 22800 CE (−₹75,237) | 14:16 NIFTY 22650 PE (−₹71,273) |
|---|---|---|
| Index | 22,821, 1 minute before the day high | 22,640, 1 minute before the day low |
| Calls 1 / 3 / 5 / 15 / 30 min | **−263,500** / −249,984 / −277,532 / −286,523 / −199,517 | +38,832 / −8,228 / +3,717 / +101,232 / +103,627 |
| Puts 1 / 3 / 5 / 15 / 30 min | −6,168 / +2,671 / +13,347 / +32,332 / +18,744 | +194 / **−174,674** / −195,632 / −215,781 / −155,647 |
| Futures volume × normal | 4.0 (7.7 at 12:40) | 4.7 |
| VIX | 16.60, falling | 16.68, falling |
| What followed | Calls written back +84 K–96 K lots within 5–7 minutes; index −44 in 15 minutes | Calls added +107 K–155 K over 15 minutes; index +140 in 30 minutes |

Both entries came **in the minute of the largest covering burst on their side**: the 12:43 call buy as call writers
bought back 2.6 L lots in one minute, the 14:16 put buy as put writers bought back 1.75 L lots in three. On this day
the covering burst was the **end** of the move (the trapped side finished buying back), not its start, and VIX did not
confirm either move.

## SENSEX in brief

Same shape: high 73,763 at 12:43, low 73,179 at 14:16, close 73,599. OI (expiry 29 Oct, two days away) built far less:
calls +61,884 / puts +26,537 lots in the first 30 minutes, then small. The same two covering bursts: calls −35,065 lots
in 12:30–12:45 (to the high), puts −34,747 in 13:00–13:15 and −22,354 in 14:00–14:15 (to the low), calls −35,153 in
14:30–14:45. New calls went to 73,500–74,000 (+15,078 at 74,000), puts to 73,000–73,300. Futures volume was below
normal most of the day (0.1–0.7×) except around the swings (14:16 12.5×, 14:55 22×, 15:04 17×).

## How a professional would read this day — what was knowable when

| Time | What the data showed then | A reasonable reading then |
|---|---|---|
| 09:15–09:45 | Open below the previous close; both call and put OI built every minute (+7.2 L / +6.5 L lots), mostly at 22,700–22,800; VIX falling from 17.45 | Writers selling both sides around 22,750 on expiry day: expect a range unless something breaks it with VIX rising. Breakout buying needs extra proof |
| 10:00–12:30 | Pushes to 22,804 and dips to 22,685 both reversed; puts kept building; 11:28 futures 14.8× normal with no price move | Range confirmed; heavy volume without price progress is absorption |
| 12:30–12:43 | +70 points in 13 minutes, futures 7.7×, then a −2.6 L lot call cut in one minute; VIX still falling | Short covering in progress. Buying **after** the cover is buying the climax; the trade that existed was before it, or not at all |
| 12:45–13:15 | Calls rebuilt +1.2–1.6 L lots, puts cut −3.4 L | The squeeze failed; writers back |
| 14:00–14:17 | Below the opening-range low on 2.5× volume, put covering −1.75 L lots in 3 minutes, VIX flat-to-falling | The mirror image: put covering at the low, without fear (VIX) behind it |
| 14:17–15:00 | Call covering −3.4 L then −2.9 L lots, back to 22,750 | Return to the strike where most positions sat |

What this day does **not** prove: that pins can be predicted (A-154 found floors and ceilings hold as often as chance on
78 expiry days), or that "covering bursts mark extremes" in general. One day is a hypothesis. If wanted, it can be
registered and tested on the tick days and bar days: *on expiry days with VIX falling, an ATM ± 5 covering burst of at
least X lots in 1–3 minutes near the day's extreme is followed by a reversal more often than chance*. A-161 (OI climax
exit for aob) is the closest earlier test and is in shadow.

## Data caveats

- NIFTY futures expired today: their OI fall (−30 K to −6 L per block) is expiry, and our capture has no next-month
  NIFTY future to show the roll.
- SENSEX futures OI jumps in ~85,000 steps and back: a feed artefact, not used.
- Futures volume normal = median of the same minute over 19 sessions (28 Sep onwards; earlier days are not in the own
  capture).
- OI cannot tell writers from buyers; "writing" / "covering" here is the usual reading of OI up / down near the money on
  an expiry day, not a fact about who traded.
