# Break-retest with a noise-proof stop (brt-v3, ledger A-074)

Registered 2026-10-11 before any replay. User: "register and test break-retest v2 with the wider stop". (The file is
v3 because `break-retest.v2.yaml` already holds A-065's runner-split half.)

## Why

On the 22 tick days brt-v1 made 60 trades, 17 wins, −₹76,836, profit factor 0.81; on the 7 days no strategy was
designed on, 2 wins in 20, and 13 of those 20 stops hit within 5 minutes of entry. Its stop sits at the retest's extreme
close, often 20–40 index points from the entry: inside one ordinary minute's noise. On 15 Sep SENSEX the 09:42 put
was stopped at 09:44 by a 28-point tick, after which SENSEX fell about 400 points; on 30 Sep SENSEX four of five
entries were stopped within 1–4 minutes. 15 and 30 Sep are the design days.

## Rule

brt-v1 unchanged except: (1) the stop is 1 ATR (3-minute ATR) on the far side of the broken level; (2) the premium
budget is ₹1,25,000 instead of ₹2,50,000. v1's stop was about 0.3–1.3 ATR from the entry, v3's about 1–2 ATR, so half
the size keeps the rupee loss at the stop roughly where v1's was. Target (next level ≥ 0.5 ATR away, else 1.5 ATR),
30-minute time stop, window, 25 % emergency premium stop and the never-the-expiring-index rule are as v1.

## Test

The 22 full tick days on the Mac. Held-out 22, 23, 24, 25 Sep, read once; design days 15 and 30 Sep reported apart;
test = the other 16. Three runs on one new image: control (the live set, brt-v1); the live set with brt-v3 in brt-v1's
place; the live set without break-retest (descriptive, to inform the removal decision; not a hypothesis). The control
must reproduce the earlier sessions day by day.

## Pass criteria (fixed now)

PASS if on the 16 test days: brt-v3 net > 0 and > brt-v1 net; brt-v3 profit factor ≥ 1.2 with at least 20 trades
(fewer = INCONCLUSIVE); the set's net with brt-v3 ≥ control − ₹50,000. Held-out: brt-v3 net ≥ 0. A PASS means brt-v3
may replace brt-v1 later; the decision is the user's.

## Result (11 Oct 2026; image c61f805-a74; `.local/a74/summary.txt`)

The control reproduced the earlier sessions on all 22 days to the rupee.

| Part | Set: control | Set: brt-v3 | Set: no break-retest | brt-v1 | brt-v3 |
|---|---|---|---|---|---|
| Test (16 days) | +20,02,371 | +13,44,343 | +20,06,587 | 44 trades, 14 wins, −4,217, PF 0.99 | 36, 16, −64,539, PF 0.75 |
| Held-out (4) | +4,31,141 | +4,36,241 | +4,76,320 | 8, 2, −45,180, PF 0.13 | 8, 5, −40,079, PF 0.19 |
| Design (15, 30 Sep) | +3,46,531 | +4,06,893 | +3,73,971 | 8, 1, −27,440 | 6, 3, +32,922 |

**A-074 brt-v3: FAIL.** On the test days v3 lost 64,539 (worse than v1's −4,217), profit factor 0.75 (bar 1.2), and the
set made 6,58,028 less than the control (bar −50,000); held-out −40,079 (bar ≥ 0). It won more often (44 % against
32 %) but lost more per losing trade, and it held positions longer: on 1 Oct its longer-held trade tied up capital when
the trend rider entered, so the trend rider's +₹8.46L trade was smaller (set +3,65,797 against +9,22,602 that day). The
wider stop helped only on the two days it was designed from (+32,922 against −27,440).

**Removing break-retest (descriptive):** the set without it made +28,56,878 over the 22 days against +27,80,043, i.e.
+76,835, exactly brt-v1's own net. Every other strategy's trades and net were identical in both runs, so removing it
has no side effects here: no strategy gained or lost capital or trades. On the test days alone break-retest was about
break-even (−4,217); its losses are on the held-out days (−45,180) and the design days (−27,440).
