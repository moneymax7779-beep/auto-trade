# Allocation step 1 — capital efficiency of the five LIVE strategies (10 Nov 2026)

Descriptive, from existing replays only (no new runs); ledger A-371. Scripts: `.local/alloc1/step1.py`, `.local/alloc1/slots.py`.

## Reading

1. **PAPER (Rs 5 L, rupee budgets): the set loses about a third of what the strategies make alone, and almost all of it on
   SENSEX expiry days.** Set +68.72 L vs alone +105.94 L on 28 days. etr -12.43 L and wbr -16.08 L, of which -23.05 L on
   SENSEX expiry days; mac -5.89 L on non-expiry days; obc and brtr lose little.
2. **Capital efficiency (net per lakh of premium held per hour, in the set):** wbr +130 K, etr +41 K, obc +15 K, mac
   +13 K, brtr +7 K. obc ties up 55 % of the premium-hours (3-hour holds) for 47 % of the net; brtr 25 % for 9 %.
3. **Refusals:** 57 capital refusals worth +24.66 L in the alone runs, +19.53 L of it in five refusals on four SENSEX
   expiry days (1, 8, 15, 29 Oct). obc held capital in 51 refusals, but those obc trades made +33.57 L; brtr held in 30
   (its trades +8.87 L). etr's entries that did get in got 46 % of their alone premium (+4.67 L vs +9.34 L).
4. **ecr on PAPER-1 cannot crowd real money.** Followers copy SIGNAL-1 (signal-source v2: Rs 100 Cr virtual capital,
   never refuses), not PAPER-1, and check the LIVE list at every entry. ecr only changes PAPER-1's own numbers.
5. **Real money is not sized by these budgets.** follower-risk v9 (the LIVE pilot): 1 lot per order, at most 2 open
   positions. There the allocation is the slot limit (step 1b below).

SET = five-strategy set, risk v19, image wbr22-a362, 29 days; ALONE = each by itself, image main-f82d279, 28 days. Common days: 28 (2026-09-28 .. 2026-11-06); halves to 2026-10-15 / after.
Premium-hours = premium paid (final quantity x average cost) x hours held; adds make it an overstatement for etr.

### In the set (28 common days)

| strategy | trades | wins | net | PF | avg premium / trade | avg hold (h) | premium-hours (L·h) | net per L·h | share of premium-hours | share of net |
|---|---|---|---|---|---|---|---|---|---|---|
| etr | 7 | 4 | +6.52 L | 7.16 | 1.52 L | 1.36 | 15.8 | +41.4 K | 4 % | 9 % |
| mac | 27 | 14 | +6.47 L | 2.76 | 2.55 L | 0.71 | 49.0 | +13.2 K | 13 % | 9 % |
| obc | 24 | 16 | +32.09 L | 8.34 | 2.84 L | 3.06 | 210.5 | +15.2 K | 55 % | 47 % |
| wbr | 9 | 7 | +17.28 L | 11.20 | 3.02 L | 0.53 | 13.3 | +129.9 K | 3 % | 25 % |
| brtr | 70 | 17 | +6.36 L | 2.32 | 1.47 L | 1.01 | 94.5 | +6.7 K | 25 % | 9 % |
| **all** | 137 | | **+68.72 L** | | | | 383.1 | +17.9 K | | |

### Alone, each with the whole Rs 5 L (28 days)

| strategy | trades | wins | net | PF | avg premium / trade | avg hold (h) | premium-hours (L·h) | net per L·h | share of premium-hours | share of net |
|---|---|---|---|---|---|---|---|---|---|---|
| etr | 13 | 6 | +18.96 L | 4.30 | 3.09 L | 1.24 | 54.2 | +35.0 K | 10 % | 18 % |
| mac | 55 | 28 | +12.36 L | 2.67 | 2.56 L | 0.62 | 88.1 | +14.0 K | 17 % | 12 % |
| obc | 24 | 16 | +34.33 L | 8.85 | 2.97 L | 3.06 | 225.3 | +15.2 K | 43 % | 32 % |
| wbr | 15 | 14 | +33.36 L | 51.23 | 3.06 L | 0.79 | 39.2 | +85.2 K | 7 % | 31 % |
| brtr | 82 | 21 | +6.94 L | 2.25 | 1.49 L | 1.07 | 122.9 | +5.6 K | 23 % | 7 % |
| **all** | 189 | | **+105.94 L** | | | | 529.6 | +20.0 K | | |

### Set vs alone per strategy (28 days)

| strategy | set | alone | set - alone | H1 | H2 | NIFTY exp | SENSEX exp | non-exp |
|---|---|---|---|---|---|---|---|---|
| etr | +6.52 L | +18.96 L | -12.43 L | -13.83 L | +1.40 L | -1.74 L | -10.70 L | +0.00 L |
| mac | +6.47 L | +12.36 L | -5.89 L | -4.93 L | -0.96 L | -0.11 L | +0.50 L | -6.28 L |
| obc | +32.09 L | +34.33 L | -2.24 L | +0.00 L | -2.24 L | +0.00 L | +0.00 L | -2.24 L |
| wbr | +17.28 L | +33.36 L | -16.08 L | -9.73 L | -6.35 L | -3.73 L | -12.35 L | +0.00 L |
| brtr | +6.36 L | +6.94 L | -0.58 L | -0.81 L | +0.23 L | +0.32 L | -0.38 L | -0.52 L |

### Capital refusals in the set and who held the capital (28 days)

57 refused episodes (first capital refusal per day / strategy / index / intent).

| refused strategy | episodes | value of its alone trades at those minutes |
|---|---|---|
| etr | 9 | +14.50 L |
| mac | 28 | +1.91 L |
| obc | 7 | +0.00 L |
| wbr | 6 | +5.44 L |
| brtr | 7 | +0.81 L |

| capital holder | refusal episodes it was holding in | refused alone value, by its premium share | those holding trades' own net (distinct trades) |
|---|---|---|---|
| etr | 6 | +1.32 L | +7.47 L over 5 trades |
| mac | 11 | +5.84 L | +2.87 L over 8 trades |
| obc | 51 | +9.65 L | +33.57 L over 19 trades |
| wbr | 5 | +4.60 L | +17.62 L over 3 trades |
| brtr | 30 | +1.25 L | +8.87 L over 15 trades |

Entry refusals worth more than Rs 50 K alone:

| day | type | refused | index | intent | time | alone value | holders (premium) |
|---|---|---|---|---|---|---|---|
| 2026-10-01 | SENSEX exp | etr | SENSEX | ENTER PE | 12:20 | +8.48 L | mac N 2.44 L, wbr S 2.49 L |
| 2026-10-08 | SENSEX exp | etr | SENSEX | ENTER PE | 11:23 | +4.07 L | obc N 3.68 L, brtr N 1.22 L |
| 2026-10-29 | SENSEX exp | wbr | SENSEX | ENTER CE | 13:37 | +2.73 L | etr S 2.33 L, mac N 2.48 L |
| 2026-10-15 | SENSEX exp | etr | SENSEX | ENTER CE | 11:40 | +2.29 L | obc S 2.32 L, obc N 2.53 L |
| 2026-10-15 | SENSEX exp | wbr | SENSEX | ENTER CE | 11:52 | +1.96 L | obc S 2.32 L, obc N 2.53 L |
| 2026-10-23 | non-exp | mac | NIFTY | ENTER CE | 13:36 | +1.24 L | obc N 2.52 L, brtr N 1.18 L, brtr S 1.22 L |
| 2026-10-05 | non-exp | mac | NIFTY | ENTER CE | 12:51 | +1.20 L | obc S 3.68 L |
| 2026-10-08 | SENSEX exp | wbr | SENSEX | ENTER PE | 11:25 | +1.11 L | obc N 3.68 L, brtr N 1.22 L |
| 2026-10-30 | non-exp | mac | SENSEX | ENTER CE | 10:57 | +0.66 L | mac N 2.48 L, brtr N 1.19 L |
| 2026-10-01 | SENSEX exp | brtr | NIFTY | ENTER PE | 12:23 | +0.65 L | mac N 2.44 L, wbr S 2.49 L |

### Set trades that got less premium than the same trade alone (capital in use)

| strategy | matched trades | of them < 90 % of the alone premium | set premium / alone premium (those) | set net (those) | alone net (those) |
|---|---|---|---|---|---|
| etr | 6 | 4 | 46 % | +4.67 L | +9.34 L |
| mac | 26 | 1 | 65 % | -0.14 L | -0.22 L |
| obc | 24 | 3 | 72 % | +4.59 L | +6.83 L |
| wbr | 6 | 2 | 68 % | +6.61 L | +9.91 L |
| brtr | 70 | 3 | 68 % | -0.19 L | -0.28 L |

### Other refusals in the set (count of rejection rows)

- mac: kill switch ACCOUNT x 6
- wbr: kill switch ACCOUNT x 5
- etr: kill switch ACCOUNT x 5
- brtr: kill switch ACCOUNT x 3
- brtr: daily loss limit x 2
- mac: daily loss limit x 1

9 Nov (set only, no alone run): 2 trades, brtr -0.05 L, obc -0.91 L

## Step 1b — the LIVE pilot: 1 lot, 2 slots (alone trades per lot, first come first served)

Per-lot net = position net / lots - Rs 40 (fixed brokerage of a 1-lot round trip, approximate).

| strategy | trades | per-lot net, all | H1 | H2 | per-lot net per trade |
|---|---|---|---|---|---|
| etr | 13 | +13,898 | +17,076 | -3,177 | +1,069 |
| mac | 55 | +34,977 | +22,360 | +12,617 | +636 |
| obc | 24 | +72,726 | +67,316 | +5,411 | +3,030 |
| wbr | 15 | +27,619 | +19,812 | +7,807 | +1,841 |
| brtr | 82 | +55,405 | +9,160 | +46,245 | +676 |

etr+mac+obc+wbr+brtr, 2 slots: taken 146 trades +159,327 (H1 +110,093 / H2 +49,233); refused 43 worth +45,299
etr+mac+obc+wbr+brtr, 3 slots: taken 170 trades +196,281 (H1 +125,979 / H2 +70,302); refused 19 worth +8,345
etr+mac+obc+wbr+brtr, 4 slots: taken 184 trades +202,208 (H1 +134,750 / H2 +67,458); refused 5 worth +2,418

five strategies, 2 slots: refused per strategy: mac 20 (+5,011), brtr 8 (+4,403), etr 6 (+11,076), wbr 7 (+7,955), obc 2 (+16,855)
slot holders when a trade was refused: obc 36 (+11,801 of refused value), mac 12 (+7,965 of refused value), wbr 2 (+5,523 of refused value), brtr 36 (+20,011 of refused value)
taken per strategy: etr 7 (+2,822), mac 35 (+29,966), obc 22 (+55,871), wbr 8 (+19,664), brtr 74 (+51,002)
biggest refused: 2026-11-06 obc NIFTY +16,026 (held by brtr+brtr); 2026-10-01 etr SENSEX +8,269 (held by mac+wbr); 2026-10-08 etr SENSEX +4,117 (held by obc+brtr); 2026-10-23 mac NIFTY +4,097 (held by brtr+brtr); 2026-10-30 mac NIFTY +3,390 (held by brtr+mac); 2026-10-09 brtr SENSEX +3,274 (held by obc+obc)
