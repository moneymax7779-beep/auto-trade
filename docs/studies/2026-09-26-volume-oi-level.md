# Study: futures volume + near-ATM OI change + price at a key level (registered before computing)

User's idea (2026-09-26): enter when futures volume is high, OI is changing strongly in ATM ± 2
strikes, and spot is near ORH / ORL / support-resistance / PDH / PDL, in either direction.

This is an **exploratory event study**, not a strategy test: all sessions used have been looked at
before, and ten sessions cannot validate anything. Its job is to say whether the idea shows any sign
of an edge over random entries, and how large the costs are against the moves.

## Data
Features v5 per minute (engine snapshots) for 11, 15, 16, 17, 18, 21 (partial), 22, 23, 24, 25 Sep
2026, NIFTY and SENSEX. Option outcomes from zt-tiger-v2 `option_tick_snapshots` (bid/ask about once
a minute per strike, nearest expiry).

## Signal (all must hold at snapshot minute t, 09:30–14:45)
1. Futures volume high: time-of-day RVOL ≥ 1.5 (the design's "strong" band).
2. OI changing strongly near ATM: max(|call ΔOI 3 min|, |put ΔOI 3 min|) over ATM ± 3 strikes
   (ATM ± 2 weighted fully — the engine's definition) at or above the 80th percentile of that value
   for the index over all minutes of these sessions. (The percentile uses the whole sample: a mild
   look-ahead in the threshold, accepted for an exploratory study and stated here.)
3. Near a level: |spot − level| ≤ 0.25 ATR3m for any of ORH, ORL, PDH, PDL, last swing high, last
   swing low.
After a signal, the index is ignored for 15 minutes (so overlapping signals are not counted twice).

## Trades
Enter the ATM option at the ask of the first option snapshot at or after t; exit at the bid of the
first snapshot at or after t + H, for H = 5, 10, 15, 30 minutes. One lot (NIFTY 65, SENSEX 20).
Costs from `india-index-options-costs.v1.yaml` (brokerage both legs, STT on the sell, exchange, SEBI,
GST, stamp).

Direction rules (the idea does not say which side), all reported:
- MOMENTUM: CE if futures 3-minute momentum > 0, else PE.
- FADE: the opposite of MOMENTUM.
- OI_WRITERS: CE when put OI is building more than call OI (writers defending below), else PE.
- LEVEL_SIDE: CE if spot is above the nearest level, else PE.
- STRADDLE: buy ATM CE and PE together (direction-free volatility bet).

## Comparison
For each rule and horizon, 2,000 random samples with the same number of entries per index and
session, at random minutes in 09:30–14:45, with the side chosen by the same rule at that minute. The
signal is only interesting if its mean net P&L sits well above the random distribution (reported as
a percentile), not merely above zero. Sensitivity: RVOL ≥ 1.25 and level distance ≤ 0.5 ATR.

## Results (computed 2026-09-26, unchanged output)

```

=== Registered definition: RVOL >= 1.5, near level <= 0.25 ATR, OI >= p80 (NIFTY 1,742,052, SENSEX 315,690) -> 33 signals in 11 index-sessions
rule          H   n  win%   mean ₹   median     t  rand p50 pctile
MOMENTUM      5  33    58      118       63  1.17       -47     97
MOMENTUM     10  33    55       72        3  0.49       -32     79
MOMENTUM     15  33    39       -3     -136 -0.02       -14     53
MOMENTUM     30  33    61       16      195  0.08        33     47
FADE          5  33    27     -212     -226 -3.10       -82      3
FADE         10  33    36     -144     -167 -1.62       -87     28
FADE         15  33    45      -87     -113 -0.72       -94     53
FADE         30  33    33      -53     -436 -0.23      -111     62
OI_WRITERS    5  33    48       65       -3  0.62       -62     93
OI_WRITERS   10  33    52       49        0  0.34       -22     74
OI_WRITERS   15  33    33       45     -152  0.26        -1     62
OI_WRITERS   30  33    48      -51      -38 -0.26        29     36
LEVEL_SIDE    5  33    42       24      -73  0.24       -81     93
LEVEL_SIDE   10  33    58      145       16  1.02       -66     97
LEVEL_SIDE   15  33    55      186       54  1.25       -82     97
LEVEL_SIDE   30  33    48      -65      -88 -0.36       -37     45
STRADDLE      5  33    24      -95     -166 -2.07      -126     82
STRADDLE     10  33    21      -72     -164 -0.88      -118     79
STRADDLE     15  33    21      -90     -203 -1.01      -110     60
STRADDLE     30  33    24      -37     -240 -0.32       -73     62
   2026-09-11 NIFTY  10:22:00 rvol 3.10 near +0.07 ATR mom 9.1
   2026-09-15 NIFTY  10:31:00 rvol 2.03 near -0.22 ATR mom -5.1
   2026-09-15 NIFTY  10:49:00 rvol 2.35 near -0.19 ATR mom 6.3
   2026-09-15 NIFTY  11:33:00 rvol 1.84 near +0.08 ATR mom 12.2
   2026-09-15 NIFTY  13:01:00 rvol 3.51 near -0.12 ATR mom -15.0
   2026-09-15 NIFTY  13:41:00 rvol 3.39 near +0.10 ATR mom -5.9
   2026-09-15 NIFTY  14:01:00 rvol 2.14 near -0.12 ATR mom 6.3
   2026-09-17 NIFTY  10:08:00 rvol 2.26 near -0.10 ATR mom 15.0
   2026-09-17 NIFTY  13:29:00 rvol 2.81 near +0.09 ATR mom -31.0
   2026-09-17 SENSEX 10:42:00 rvol 2.09 near +0.24 ATR mom -39.2
   2026-09-17 SENSEX 12:48:00 rvol 3.56 near +0.21 ATR mom -3.2
   2026-09-17 SENSEX 14:15:00 rvol 10.86 near -0.18 ATR mom 9.0
   2026-09-17 SENSEX 14:30:00 rvol 2.83 near +0.05 ATR mom -60.0
   2026-09-18 NIFTY  14:10:00 rvol 4.01 near -0.23 ATR mom 10.0
   2026-09-22 NIFTY  10:31:00 rvol 3.71 near +0.23 ATR mom -11.8
   2026-09-22 NIFTY  13:51:00 rvol 3.07 near -0.18 ATR mom -10.2
   2026-09-22 NIFTY  14:11:00 rvol 2.01 near +0.03 ATR mom 14.9
   2026-09-22 NIFTY  14:35:00 rvol 3.44 near +0.24 ATR mom -21.4
   2026-09-22 SENSEX 13:56:00 rvol 2.56 near -0.00 ATR mom -84.5
   2026-09-23 SENSEX 13:36:00 rvol 1.73 near +0.19 ATR mom -9.95
   2026-09-23 SENSEX 14:32:00 rvol 2.08 near +0.14 ATR mom -9.3
   2026-09-24 NIFTY  10:18:00 rvol 4.79 near +0.02 ATR mom -19.0
   2026-09-24 SENSEX 09:57:00 rvol 2.23 near +0.01 ATR mom -2.3
   2026-09-24 SENSEX 10:13:00 rvol 1.90 near -0.02 ATR mom -26.85
   2026-09-24 SENSEX 10:28:00 rvol 2.50 near -0.24 ATR mom 10.0
   2026-09-24 SENSEX 10:51:00 rvol 2.36 near -0.03 ATR mom 70.85
   2026-09-24 SENSEX 11:19:00 rvol 2.80 near -0.03 ATR mom 27.25
   2026-09-24 SENSEX 11:34:00 rvol 19.86 near -0.06 ATR mom -3.05
   2026-09-24 SENSEX 11:53:00 rvol 7.09 near -0.05 ATR mom -11.9
   2026-09-24 SENSEX 12:22:00 rvol 5.60 near +0.21 ATR mom 4.25
   2026-09-24 SENSEX 13:56:00 rvol 12.11 near -0.16 ATR mom -20.65
   2026-09-24 SENSEX 14:25:00 rvol 3.58 near +0.10 ATR mom -78.05
   2026-09-25 NIFTY  12:52:00 rvol 2.81 near -0.03 ATR mom -4.0

=== Sensitivity (looser): RVOL >= 1.25, near level <= 0.5 ATR, OI >= p80 (NIFTY 1,742,052, SENSEX 315,690) -> 62 signals in 13 index-sessions
rule          H   n  win%   mean ₹   median     t  rand p50 pctile
MOMENTUM      5  60    38       -8      -88 -0.11       -49     75
MOMENTUM     10  60    40      -66     -150 -0.70       -46     39
MOMENTUM     15  60    35      -90     -168 -0.83       -32     28
MOMENTUM     30  60    45      -18     -141 -0.12         1     45
FADE          5  60    42      -94      -92 -1.92       -79     39
FADE         10  60    45      -12      -39 -0.19       -75     81
FADE         15  60    43       -7      -69 -0.09       -85     81
FADE         30  60    43      -49     -276 -0.36       -98     64
OI_WRITERS    5  61    49       45       -3  0.64       -72     99
OI_WRITERS   10  61    43        6      -61  0.06       -33     69
OI_WRITERS   15  61    39       35      -81  0.31        -7     68
OI_WRITERS   30  61    46      -19     -113 -0.13        -9     48
LEVEL_SIDE    5  61    41      -45      -73 -0.69       -81     75
LEVEL_SIDE   10  61    44        6      -59  0.07       -65     82
LEVEL_SIDE   15  61    44       44      -76  0.46       -76     91
LEVEL_SIDE   30  61    48       62     -119  0.42       -62     81
STRADDLE      5  61    18     -102     -141 -2.92      -130     87
STRADDLE     10  61    18      -80     -184 -1.50      -125     86
STRADDLE     15  61    20      -97     -212 -1.72      -117     66
STRADDLE     30  61    25      -69     -178 -1.00       -99     65
```

## Follow-up (user request): NIFTY only, non-expiry days (11, 16, 17, 18, 21, 23, 24, 25 Sep)

Chosen after seeing the first results, so it is further data-mining, not a cleaner test.

```

=== Registered definition: RVOL >= 1.5, near level <= 0.25 ATR, OI >= p80 (NIFTY 1,128,380) -> 19 signals in 7 index-sessions
rule          H   n  win%   mean ₹   median     t  rand p50 pctile
MOMENTUM      5  19    37       13     -194  0.09       -48     68
MOMENTUM     10  18    39       17     -190  0.08       -41     62
MOMENTUM     15  18    28       45     -333  0.13       -27     64
MOMENTUM     30  19    47      137      -23  0.43       -30     71
FADE          5  19    47     -114      -16 -1.16      -103     45
FADE         10  18    44     -113      -67 -0.76      -126     53
FADE         15  18    56      -89       18 -0.46      -124     58
FADE         30  19    37     -279     -315 -1.23      -138     30
OI_WRITERS    5  19    37       -2     -141 -0.01      -111     84
OI_WRITERS   10  18    33      -35     -199 -0.16      -128     73
OI_WRITERS   15  18    22      -29     -333 -0.08      -149     73
OI_WRITERS   30  19    32      -56     -315 -0.17      -199     72
LEVEL_SIDE    5  19    53       -2       56 -0.01       -95     81
LEVEL_SIDE   10  18    56       -1        8 -0.00       -65     65
LEVEL_SIDE   15  18    61       24       92  0.11       -56     66
LEVEL_SIDE   30  19    47     -124     -258 -0.50       -34     39
STRADDLE      5  19    21     -101     -202 -1.22      -163     83
STRADDLE     10  18    17      -96     -225 -0.96      -158     74
STRADDLE     15  18    11      -44     -286 -0.23      -154     81
STRADDLE     30  19    21     -142     -296 -1.01      -150     52
   2026-09-11 NIFTY  10:22:00 rvol 3.10 near +0.07 ATR mom 9.1
   2026-09-11 NIFTY  11:13:00 rvol 9.66 near +0.14 ATR mom 12.2
   2026-09-16 NIFTY  13:32:00 rvol 1.84 near -0.20 ATR mom 8.0
   2026-09-17 NIFTY  10:08:00 rvol 2.26 near -0.10 ATR mom 15.0
   2026-09-17 NIFTY  11:54:00 rvol 1.87 near -0.12 ATR mom 4.9
   2026-09-17 NIFTY  13:29:00 rvol 2.81 near +0.09 ATR mom -31.0
   2026-09-18 NIFTY  13:05:00 rvol 2.27 near -0.19 ATR mom 8.0
   2026-09-18 NIFTY  14:10:00 rvol 4.01 near -0.23 ATR mom 10.0
   2026-09-23 NIFTY  14:32:00 rvol 2.48 near +0.11 ATR mom -0.8
   2026-09-24 NIFTY  09:58:00 rvol 2.01 near +0.22 ATR mom -8.9
   2026-09-24 NIFTY  10:17:00 rvol 1.82 near +0.03 ATR mom -21.5
   2026-09-24 NIFTY  11:15:00 rvol 2.74 near +0.06 ATR mom -18.0
   2026-09-25 NIFTY  10:22:00 rvol 2.36 near -0.14 ATR mom 3.0
   2026-09-25 NIFTY  10:49:00 rvol 3.88 near +0.22 ATR mom -7.5
   2026-09-25 NIFTY  11:28:00 rvol 1.52 near -0.23 ATR mom -11.8
   2026-09-25 NIFTY  12:52:00 rvol 2.81 near -0.03 ATR mom -4.0
   2026-09-25 NIFTY  13:08:00 rvol 5.43 near -0.13 ATR mom -4.0
   2026-09-25 NIFTY  13:52:00 rvol 2.54 near -0.06 ATR mom 0.5
   2026-09-25 NIFTY  14:11:00 rvol 5.01 near +0.24 ATR mom -21.2

=== Sensitivity (looser): RVOL >= 1.25, near level <= 0.5 ATR, OI >= p80 (NIFTY 1,128,380) -> 25 signals in 7 index-sessions
rule          H   n  win%   mean ₹   median     t  rand p50 pctile
MOMENTUM      5  25    32      206     -172  0.77       -63     97
MOMENTUM     10  25    48      107     -142  0.56       -47     83
MOMENTUM     15  25    40       69     -210  0.33       -49     75
MOMENTUM     30  25    44       96      -23  0.37       -44     72
FADE          5  25    52     -159       35 -1.34      -102     23
FADE         10  25    36     -141      -75 -1.32      -103     38
FADE         15  25    48     -136      -59 -0.99      -110     43
FADE         30  25    32     -167     -294 -0.69      -117     40
OI_WRITERS    5  25    40     -108     -141 -0.72      -112     52
OI_WRITERS   10  25    44     -111      -65 -0.79      -118     52
OI_WRITERS   15  25    36     -197     -312 -1.11      -137     35
OI_WRITERS   30  25    28     -367     -296 -1.67      -191     20
LEVEL_SIDE    5  25    56      -10      103 -0.07       -88     81
LEVEL_SIDE   10  25    52       16        0  0.12       -57     71
LEVEL_SIDE   15  25    48      -42     -136 -0.25       -54     54
LEVEL_SIDE   30  25    36     -262     -278 -1.21       -35     15
STRADDLE      5  25    20       47     -159  0.29      -161     99
STRADDLE     10  25    20      -34     -213 -0.31      -162     91
STRADDLE     15  25    20      -67     -244 -0.61      -157     82
STRADDLE     30  25    24      -71     -281 -0.51      -157     73
```

## Follow-up 2 (user request, registered before computing): 1-minute OI burst relative to 3 minutes

NIFTY, non-expiry days. The OI condition is replaced by an OI burst: for the call or the put side,
|ΔOI 1 min| is at or above the 80th percentile of |ΔOI 1 min| on these days, and the same side's
1-minute change runs at ≥ 1.5× its 3-minute average rate with the same sign
(ΔOI1m ÷ (ΔOI3m ÷ 3) ≥ 1.5; the factor is a placeholder). OI_WRITERS uses the 1-minute changes.
Run A keeps the volume and level conditions; run B uses the OI burst alone (15-minute cooldown).

### Results (unchanged output)

```

=== Run A: OI burst + volume + level: RVOL >= 1.5, near level <= 0.25 ATR, OI burst |dOI1m| >= p80 and >= 1.5x 3m pace (NIFTY 492,031) -> 17 signals in 6 index-sessions
rule          H   n  win%   mean ₹   median     t  rand p50 pctile
MOMENTUM      5  16    31     -147     -177 -0.92       -58     21
MOMENTUM     10  17    12     -273     -269 -1.89       -56      7
MOMENTUM     15  17    12     -378     -327 -1.92       -50      4
MOMENTUM     30  16    31     -442     -498 -1.76       -61      6
FADE          5  16    56       32       18  0.39       -95     91
FADE         10  17    65       93       82  1.23      -108     93
FADE         15  17    65      164       54  1.30      -100     92
FADE         30  16    56      130      219  0.76       -90     80
OI_WRITERS    5  16    12     -161     -214 -1.02       -96     26
OI_WRITERS   10  17    18     -189     -226 -1.28      -103     28
OI_WRITERS   15  17    29     -319     -243 -1.64      -114     13
OI_WRITERS   30  16    38     -427     -393 -1.70      -131     10
LEVEL_SIDE    5  16    38      -20     -110 -0.13       -86     75
LEVEL_SIDE   10  17    41      -63      -66 -0.42       -56     48
LEVEL_SIDE   15  17    53      -43       37 -0.21       -57     54
LEVEL_SIDE   30  16    38     -186     -202 -0.69       -43     30
STRADDLE      5  16    12     -116     -191 -1.22      -155     77
STRADDLE     10  17     6     -180     -238 -2.18      -152     36
STRADDLE     15  17    12     -214     -270 -2.20      -152     24
STRADDLE     30  16    12     -312     -267 -2.62      -148     11
   2026-09-11 NIFTY  10:22:00 rvol 3.10 near +0.07 ATR mom 9.1 ce1m -1,633,580 pe1m +541,158
   2026-09-11 NIFTY  11:13:00 rvol 9.66 near +0.14 ATR mom 12.2 ce1m -1,051,928 pe1m -21,450
   2026-09-11 NIFTY  11:45:00 rvol 3.40 near +0.18 ATR mom 8.0 ce1m -528,352 pe1m +426,498
   2026-09-11 NIFTY  12:44:00 rvol 1.83 near +0.17 ATR mom -0.8 ce1m -685,880 pe1m -587,112
   2026-09-16 NIFTY  11:34:00 rvol 2.99 near -0.04 ATR mom 12.6 ce1m +395,200 pe1m -589,940
   2026-09-16 NIFTY  12:18:00 rvol 2.64 near -0.04 ATR mom 12.8 ce1m +68,022 pe1m -524,062
   2026-09-18 NIFTY  13:05:00 rvol 2.27 near -0.19 ATR mom 8.0 ce1m -697,970 pe1m +305,370
   2026-09-18 NIFTY  14:11:00 rvol 4.90 near -0.06 ATR mom 7.5 ce1m -3,359,915 pe1m +232,992
   2026-09-18 NIFTY  14:29:00 rvol 1.82 near -0.04 ATR mom 16.0 ce1m -198,965 pe1m -619,905
   2026-09-23 NIFTY  11:33:00 rvol 2.72 near -0.05 ATR mom 15.5 ce1m -704,210 pe1m -119,080
   2026-09-24 NIFTY  10:17:00 rvol 1.82 near +0.03 ATR mom -21.5 ce1m +719,258 pe1m +5,330
   2026-09-24 NIFTY  11:14:00 rvol 1.87 near +0.16 ATR mom -13.3 ce1m +346,482 pe1m -863,362
   2026-09-24 NIFTY  14:24:00 rvol 4.53 near -0.03 ATR mom -26.3 ce1m -142,480 pe1m -522,698
   2026-09-25 NIFTY  10:49:00 rvol 3.88 near +0.22 ATR mom -7.5 ce1m +104,130 pe1m -1,158,398
   2026-09-25 NIFTY  12:01:00 rvol 2.15 near +0.16 ATR mom -6.9 ce1m +39,942 pe1m +507,455
   2026-09-25 NIFTY  12:51:00 rvol 2.24 near -0.17 ATR mom -5.0 ce1m +325,228 pe1m -592,735
   2026-09-25 NIFTY  13:12:00 rvol 5.11 near +0.20 ATR mom 8.1 ce1m +103,415 pe1m -587,048

=== Run B: OI burst alone: RVOL >= None, near level <= None ATR, OI burst |dOI1m| >= p80 and >= 1.5x 3m pace (NIFTY 492,031) -> 90 signals in 8 index-sessions
rule          H   n  win%   mean ₹   median     t  rand p50 pctile
MOMENTUM      5  83    36      -98     -106 -1.48       -63     23
MOMENTUM     10  83    42      -46      -95 -0.63       -58     56
MOMENTUM     15  85    42      -58     -156 -0.66       -59     51
MOMENTUM     30  83    41      -91     -152 -0.76       -48     35
FADE          5  83    46      -38      -53 -0.79       -95     92
FADE         10  83    37      -81     -133 -1.08      -100     62
FADE         15  85    38      -79     -104 -0.92      -104     62
FADE         30  83    39      -46     -166 -0.42      -121     75
OI_WRITERS    5  84    32      -86     -160 -1.28       -97     59
OI_WRITERS   10  84    36      -25     -138 -0.30      -105     87
OI_WRITERS   15  86    38      -21     -175 -0.21       -97     81
OI_WRITERS   30  84    40     -101     -153 -0.82      -130     60
LEVEL_SIDE    5  71    38     -113     -103 -1.88       -93     31
LEVEL_SIDE   10  71    42      -47     -111 -0.65       -65     61
LEVEL_SIDE   15  73    40      -83     -149 -0.97       -79     48
LEVEL_SIDE   30  71    38     -172     -164 -1.33       -81     23
STRADDLE      5  84    11     -139     -152 -4.53      -158     78
STRADDLE     10  84    12     -128     -184 -3.74      -159     79
STRADDLE     15  86    16     -136     -186 -3.32      -161     72
STRADDLE     30  84    20     -143     -257 -2.56      -171     67
```

## Audit and repricing from raw ticks (2026-09-26)

Audit of 18 Sep 14:11: every condition checks by hand; call OI change recomputed from raw ticks (weighted ATM±3) = -3,359,915 (1 min) and -5,347,030 (3 min), identical to the engine. The snapshot table used for prices updates about once a minute, so entries/exits were up to 60 s late (that trade: +82 with snapshots, -19 at exact tick prices). All trades below are repriced from raw ticks: entry at the ask of the first tick 250 ms after the signal, exit at the bid at exactly t + H, skipped if no quote within 10 s.

```
##### 3-minute OI version, tick pricing

=== Registered definition: RVOL >= 1.5, near level <= 0.25 ATR, OI >= p80 (NIFTY 1,128,380) -> 19 signals in 7 index-sessions
rule          H   n  win%   mean ₹   median     t  rand p50 pctile
MOMENTUM      5  19    42       60     -153  0.35       -52     81
MOMENTUM     10  18    33       39     -140  0.19       -43     67
MOMENTUM     15  18    22       52     -321  0.15       -27     65
MOMENTUM     30  19    32      170     -128  0.51       -20     74
FADE          5  19    47     -162      -26 -1.54      -102     27
FADE         10  18    44     -150      -77 -1.07      -120     42
FADE         15  18    56      -79       27 -0.41      -128     60
FADE         30  19    37     -305     -175 -1.33      -141     26
OI_WRITERS    5  19    53       54       28  0.31      -111     93
OI_WRITERS   10  18    28       -8     -140 -0.04      -132     79
OI_WRITERS   15  18    22       28     -321  0.08      -158     81
OI_WRITERS   30  19    21        2     -206  0.01      -212     78
LEVEL_SIDE    5  19    42       10      -76  0.06      -103     86
LEVEL_SIDE   10  18    44      -73      -93 -0.41       -70     49
LEVEL_SIDE   15  18    61       50       58  0.24       -49     69
LEVEL_SIDE   30  19    42     -178      -92 -0.71       -47     33
STRADDLE      5  19    16     -102     -218 -1.06      -162     82
STRADDLE     10  18    17     -111     -251 -1.15      -161     69
STRADDLE     15  18    17      -26     -263 -0.14      -156     84
STRADDLE     30  19    21     -135     -312 -0.94      -150     53

=== Sensitivity (looser): RVOL >= 1.25, near level <= 0.5 ATR, OI >= p80 (NIFTY 1,128,380) -> 25 signals in 7 index-sessions
rule          H   n  win%   mean ₹   median     t  rand p50 pctile
MOMENTUM      5  25    44      216     -153  0.83       -64     98
MOMENTUM     10  25    32      135     -149  0.66       -48     86
MOMENTUM     15  25    32       33     -242  0.16       -51     68
MOMENTUM     30  25    36      116     -115  0.43       -39     74
FADE          5  25    44     -188      -26 -1.53      -100     14
FADE         10  25    36     -161     -100 -1.49      -100     30
FADE         15  25    48     -113      -11 -0.84      -108     49
FADE         30  25    32     -180     -150 -0.73      -128     39
OI_WRITERS    5  25    48     -102     -139 -0.62      -115     56
OI_WRITERS   10  25    32     -124     -163 -0.87      -126     50
OI_WRITERS   15  25    28     -195     -252 -1.14      -144     37
OI_WRITERS   30  25    24     -345     -206 -1.50      -197     23
LEVEL_SIDE    5  25    52        2        8  0.01       -97     85
LEVEL_SIDE   10  25    44      -18      -86 -0.13       -61     63
LEVEL_SIDE   15  25    48      -26      -74 -0.16       -51     56
LEVEL_SIDE   30  25    36     -248     -108 -1.13       -40     19
STRADDLE      5  25    20       28     -145  0.19      -160     99
STRADDLE     10  25    24      -26     -239 -0.22      -161     93
STRADDLE     15  25    20      -80     -233 -0.75      -157     79
STRADDLE     30  25    24      -65     -298 -0.46      -155     75
##### 1-minute OI burst version, tick pricing

=== Run A: OI burst + volume + level: RVOL >= 1.5, near level <= 0.25 ATR, OI burst |dOI1m| >= p80 and >= 1.5x 3m pace (NIFTY 492,031) -> 17 signals in 6 index-sessions
rule          H   n  win%   mean ₹   median     t  rand p50 pctile
MOMENTUM      5  17    41      -15     -153 -0.09       -58     65
MOMENTUM     10  16    19     -216     -335 -1.43       -62     15
MOMENTUM     15  17    12     -304     -291 -1.66       -54      9
MOMENTUM     30  16    38     -402     -451 -1.58       -57      9
FADE          5  17    47      -86      -26 -1.24       -93     53
FADE         10  16    62       37       44  0.50      -105     84
FADE         15  17    47       93      -11  0.85       -97     85
FADE         30  16    56       96      118  0.54       -93     77
OI_WRITERS    5  17    24      -86     -233 -0.53       -98     55
OI_WRITERS   10  16    19     -152     -252 -0.99      -105     37
OI_WRITERS   15  17    18     -228     -100 -1.23      -119     28
OI_WRITERS   30  16    44     -375     -255 -1.48      -145     16
LEVEL_SIDE    5  17    35      -28     -151 -0.17       -96     74
LEVEL_SIDE   10  16    44      -43      -38 -0.28       -57     54
LEVEL_SIDE   15  17    35      -46      -54 -0.24       -56     52
LEVEL_SIDE   30  16    44     -196     -278 -0.74       -60     31
STRADDLE      5  17     6     -101     -201 -0.96      -155     81
STRADDLE     10  16     6     -179     -253 -1.99      -153     37
STRADDLE     15  17     6     -210     -253 -2.17      -154     27
STRADDLE     30  16    12     -305     -313 -2.60      -146     12

=== Run B: OI burst alone: RVOL >= None, near level <= None ATR, OI burst |dOI1m| >= p80 and >= 1.5x 3m pace (NIFTY 492,031) -> 90 signals in 8 index-sessions
rule          H   n  win%   mean ₹   median     t  rand p50 pctile
MOMENTUM      5  83    35      -86     -114 -1.24       -63     32
MOMENTUM     10  83    42      -35      -97 -0.48       -62     64
MOMENTUM     15  85    40      -58      -87 -0.63       -62     52
MOMENTUM     30  83    45      -65     -127 -0.56       -44     42
FADE          5  83    45      -53      -77 -1.09       -93     85
FADE         10  83    40     -106      -75 -1.54       -97     45
FADE         15  85    38      -69     -100 -0.79      -102     67
FADE         30  83    40      -80     -188 -0.76      -124     65
OI_WRITERS    5  84    27     -101     -168 -1.47       -92     44
OI_WRITERS   10  84    40      -41     -128 -0.51      -104     83
OI_WRITERS   15  86    40        4      -81  0.04      -101     89
OI_WRITERS   30  84    42     -109     -155 -0.91      -138     59
LEVEL_SIDE    5  71    28     -125     -127 -1.97       -98     26
LEVEL_SIDE   10  71    46      -19      -46 -0.28       -69     77
LEVEL_SIDE   15  73    40      -74      -89 -0.84       -74     50
LEVEL_SIDE   30  71    41     -133     -127 -1.07       -90     36
STRADDLE      5  84    11     -141     -173 -4.21      -158     76
STRADDLE     10  84    11     -138     -176 -4.34      -159     71
STRADDLE     15  86    20     -124     -187 -2.93      -163     80
STRADDLE     30  84    21     -149     -259 -2.69      -172     64
```
