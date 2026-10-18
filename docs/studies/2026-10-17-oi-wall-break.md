# OI wall break (ledger A-119): registered before any replay

User, 17 Oct 2026: "register and test that OI rule on tick days — explain the rule that we want to implement and what
are the other parameters will be included in the rule, any level break out or something like that and what is the
plan for exit parameters". From the 16 Oct NIFTY review (`.local/oct16-flow`, fixed strikes 22,650–23,300).

## What 16 Oct showed (design day)

- 09:36 low 22,917: futures volume 17× a normal minute, put holders closing 15.7 lakh units in that minute.
- 09:39–09:55 base 22,940–22,990: writers on both sides. Puts written below (22,900 +30.6 lakh, 22,800 +13.8),
  calls written above (23,000 +26.5, 23,050 +18.2). PCR flat 0.86–0.91.
- 09:57 close 23,011 above the 23,000 wall on 21,320 futures (6× normal); from 09:56 call OI fell (−26.3 lakh at
  10:00): call writers covering. NIFTY broke the ORH (23,076) at 10:15 on 20× volume and reached ~23,150.
- No live strategy could act: odm had used its one trade (09:21 put), odb trades 09:16–09:25, the expiry strategies
  were off (no expiry), and the cross's EMA20 only existed from ~10:15 (first cross 13:00).

## Rule, owb-v1 (`config/strategy/oi-wall-break.v1.yaml`), fixed now

Decided on 3-minute closes, 09:30–14:30, on the index that does not expire today. Call side (puts are the mirror):

1. **A base after a low.** The session low is at least 15 minutes old.
2. **Writers on both sides.** At the previous 3-minute close, the strongest call barrier above the index (strike W,
   from the features' OI share + fresh OI + volume) had more OI than 10 minutes earlier, and so had the put support
   below. (The wall is remembered from the previous close: the barrier feature moves to the next strike once crossed.)
3. **The level break.** This 3-minute close is above W. W is the OI level; no chart level (ORH, PDH, VWAP) is needed
   for the trigger, but the index must be above the session VWAP.
4. **Writers covering.** Near-ATM call OI fell over the last 3 minutes.
5. **Participation.** Futures volume ≥ 1.5× normal for the time of day.

Then buy the ATM call, ₹2.5L of premium scaled with equity like every strategy; one trade per side per index per day.

Puts: session high ≥ 15 minutes old; the same two writer conditions; a 3-minute close below the put support strike;
near-ATM put OI falling over 3 minutes; futures volume; below VWAP.

## Exits

- **Failed break:** index stop 0.5 ATR(3m) back inside the broken strike (W − 0.5 ATR for a call).
- **Trail:** a 3-minute close back through the session VWAP ends the trade.
- **Emergency:** 30 % premium stop. **Time:** flat by 15:15. No target, no time stop.

## Not in v1 (stated so they can be tested separately)

ORH/PDH confirmation, IV, VIX, breadth and PCR are not conditions: PCR gave no signal on 16 Oct; IV and breadth are
already under test as filters (A-087, A-088). The wall-break rider (A-057/A-058, FAIL) entered when a wall's OI fell
before any break; this rule waits for the break and the cover together.

## Test

Mac, one image (research/a119-oi-wall-break from main 36b001b). Days: the 24 zt tick days 2 Sep–13 Oct and own
capture 14, 15, 16 Oct; every day at the starting capital. Held-out 22–25 Sep, read once for this hypothesis; design
day 16 Oct reported apart; test = the other 22 days. Runs: C the live set (ebs-v2, ecr-v11, odb-v3, etr-v1, mac-v1,
odm-v4; risk v17; features v12); S = C + owb-v1 listed last; A = owb-v1 alone.

**PASS** if owb-v1 alone on the test days has ≥ 8 trades (else INCONCLUSIVE), net > 0, profit factor ≥ 1.3, no day
below −₹1,10,000, and held-out net ≥ 0. In the set: if the other strategies lose more than ₹50,000 to it on the test
days, it is shadow-only. A PASS is the user's decision.

## Result

Stopped by the user at 07:54 ("stop it and test with new rule only") after the alone run and 17 of 27 set days.
owb-v1 alone: test 15 filled trades, 3 wins, −₹90,516, PF 0.80, worst day 5 Oct −₹1,51,521; held-out 4 trades,
0 wins, −₹1,11,180; design day 16 Oct +₹1,23,269. FAIL on the alone criteria; set comparison not completed.

# A-120: OI flow break (ofb-v1), registered before any replay

User, 17 Oct (after seeing owb-v1's first trades): relax futures participation to 1×; huge short-window OI changes
(long build-up / unwinding); at least one level break; "catching the momentum consistently and rapidly enter"; "this
can be continuation trend up or down should trade, one side OI buildup and other side OI unwinding".

## Rule, ofb-v1 (`config/strategy/oi-flow-break.v1.yaml`), fixed now

Every 1-minute close 09:30–14:30, either direction, on the index that does not expire today. Up (puts mirror it):

1. **Fresh level break:** the close is above at least one level that the previous close was at or below — ORH, PDH,
   the prior day's ORH, PWH, the session VWAP, the call barrier strike, the last swing high.
2. **One side builds, the other unwinds:** near-ATM put OI up and near-ATM call OI down over 1 minute (over 3 minutes
   when the 1-minute change is zero, as on BSE whose option OI updates every 2–3 minutes). 30 seconds is not
   measurable: option OI arrives about once a minute on NSE.
3. **Futures agree:** the futures OI state is fresh long or short covering (puts: fresh short or long unwinding).
4. **Participation:** futures volume ≥ 1× normal for the time of day (owb-v1: 1.5×).
5. **Trend side:** above the VWAP (puts: below).

ATM option, ₹2.5L scaled; one trade per side per index per day. Exits: index stop 0.5 ATR back inside the highest
level broken (lowest for puts); a 3-minute close back through the VWAP; 30 % premium stop; 15:15.

## Test

Same days, split and image family as A-119 (24 zt tick days + own 14–16 Oct; held-out 22–25 Sep read once; design
day 16 Oct apart; test = 22 days). Runs on image a120: S = live set + ofb-v1, A = ofb-v1 alone; the control C runs on
the same image (A-119 was stopped by the user before its control ran).
PASS criteria as A-119.

## Result

Pending.

# A-121: ofb-v2, the flow rule without levels (registered before any replay)

User, ~08:06: "ignore the levels in the rule and start replay". ofb-v2 = ofb-v1 with no level-break condition; the
index stop is 1 ATR(3m) from the entry since there is no broken level to stop behind. Everything else as ofb-v1.
Alone only, on the same 27 days; criteria as A-119.

## Result (Mac, image a121, 17 Oct 08:06–08:24, `.local/a121/summary.txt`)

| Part | Trades | Wins | Net | PF |
|---|---|---|---|---|
| Test, 22 days | 27 | 6 | +₹9,40,494 | 2.40 |
| Held-out, 22–25 Sep | 8 | 3 | −₹1,13,395 | 0.22 |
| Design day 16 Oct | 1 | 1 | +₹79,078 | – |

FAIL as registered: worst day 29 Sep −₹1,22,984 (below −₹1.1L) and held-out below zero; trade count, net and PF pass.
Six winners (15 Sep, 28 Sep, 1 Oct, 9 Oct, 12 Oct, 15 Oct; +₹1.7L to +₹3.6L each, held to 15:15 or the VWAP trail)
pay for 21 small losers (stopped within 2–20 minutes). Max drawdown −₹2.09L (29–30 Sep); longest run of losing days 4. Not deployed.

# A-122: ofb-v3, the user's exit (registered before any replay)

The 1-ATR stop in ofb-v2 was my choice, made when the level break was dropped, and not agreed with the user. The user's
exit for v3: no VWAP exit; leave when a level or the structure breaks against the trade AND the OI flow has reversed.
Exit at a 1-minute close: (the index back through the nearest level that was on the far side at entry — puts: ORH,
PDH, prior ORH, PWH, call barrier above the entry — OR through the latest swing — puts: above the last swing high) AND
(put OI building with call OI unwinding for a put; the mirror for a call). No ATR stop, no VWAP trail; the 30 % premium
stop and 15:15 remain. Entry as ofb-v2. Alone only, same 27 days; criteria as A-119.

## Result

Pending.

# A-123: ofb-v4, one trade per index per day (registered before any replay)

User: "please come up with the best solution for this, I am exhausted". The losses in ofb-v2 came from where the
trades were taken, not from the exit: the first trade on an index each day (25) won 10 and made +₹12.27L; every later
trade on the same index that day (11, mostly the opposite side after a stop) lost, −₹3.21L, all on two-way days. The
user's level+OI reversal exit (v3) cut the six trend-day winners (15 Sep +₹3.43L → +₹32K), because the latest swing
sits a few points from the price in a trend and 1-minute option OI flips sign most minutes. So v4 keeps v2's exits
(they held every trend-day winner to 15:15) and takes only the first flow signal per index per day.

The one-per-day choice is in-sample on the tick days and the held-out week is spent, so the decision rests on the
169 bar days (Jan–Sep, option and futures OI per minute), never used for this family: v4 and v2 both, alone.
Criteria in the ledger row.

## Result (Mac, image a123; ticks 11:30–11:48, bars 12:34–13:10)

| Data | Version | Trades | Wins | Net | PF |
|---|---|---|---|---|---|
| 22 tick test days | v4 | 19 | 6 | +₹12,06,941 | 3.99 |
| 169 bar days, Jan–May | v4 | 125 | 28 | −₹88,017 | 0.95 |
| 169 bar days, Jun–Sep | v4 | 133 | 25 | −₹1,65,418 | 0.88 |
| 169 bar days, all | v4 | 258 | 53 | −₹2,53,435 | 0.92 |
| 169 bar days, all | v2 | 430 | 89 | +₹6,94,373 | 1.13 |

**FAIL.** The one-trade-per-day rule came from the tick days and does not hold out of sample: on the bar days it lost
in both halves, and the later same-index trades it removes made money (v2 − v4 = +₹9.48L). The OI-flow entry itself
(v2) is weak out of sample: PF 1.13, January–May negative, the gain concentrated in June–July. Not deployed.

# A-124: ofb-v5, a 5 % premium profit lock (registered before any replay)

User: "try the target at 5% initially and let it go further if it reverts exit at 5%". ofb-v5 = ofb-v2 + a lock armed
when the option's bid is 5 % above the average premium paid; the trade then keeps running under v2's exits, and exits
if the bid falls back to +5 %. Judged on the 169 bar days against v2 (+₹6,94,373, PF 1.13); ticks reported.

## Result (Mac, image a124, 13:18–13:54)

| Data | Version | Trades | Wins | Net | PF | Worst day |
|---|---|---|---|---|---|---|
| 169 bar days | v5 (5 % lock) | 435 | 227 | +₹5,88,787 | 1.21 | −₹65,583 |
| 169 bar days | v2 | 430 | 89 | +₹6,94,373 | 1.13 | −₹1,41,666 |
| 22 tick test days | v5 | 28 | 13 | +₹3,34,049 | 1.69 | −₹94,194 |
| 22 tick test days | v2 | 27 | 6 | +₹9,40,494 | 2.40 | −₹1,22,984 |

**FAIL**: on the bar days v5 nets less than v2 and January–May is still negative (−₹99,005). The lock turned many
trades into small wins (211 lock exits, 199 positive, avg +₹7,241) and halved the worst day, but it also closed the
trend-day winners on their first dip back to +5 % (on the ticks only 3 trades reached 15:15, against 6 for v2). Not
deployed.
