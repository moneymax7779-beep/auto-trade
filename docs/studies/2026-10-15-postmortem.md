# 15 Oct 2026 post-mortem: a trend day, exits that left most of it, and a filter that blocked the NIFTY drive
# (ledger A-110, A-111)

Registered 15 Oct ~18:05 IST, before any variant is replayed. User, ~17:45: thorough review of today's trades and
signals. "Some of the trades could make best profits if exited / continued at right time", "morning SENSEX same
direction entries multiple times", "do responsibly and realistically".

**Data.** The daily sync (16:45) loaded our own capture of today. The Mac replay at live equity (₹7,60,512, deployed
code ad74e92) gives +₹5,35,029 against live +₹5,34,999: the same 5 trades, ₹30 apart on fills.

## The day

SENSEX expired today. Both indices trended up from about 09:20 to 13:38, pulled back from 14:00 to 15:11, and recovered
into the close.

| | Open | High (13:38) | Close |
|---|---|---|---|
| NIFTY | 22,695 | 23,051 (+1.6 %) | 22,997 |
| SENSEX | 73,048 | 74,326 (+1.75 %) | 74,316 |

## Live trades (session 904): +₹5,34,999, no kill switch

| Time | Strategy | Trade | Exit | Net | The option afterwards |
|---|---|---|---|---|---|
| 09:17–09:19 | odb-v3 | SENSEX 73200 CE (0DTE) @ ₹195.49 × 740 | back under prior ORH | −₹11,742 | closed at ₹1,114 |
| 09:20–09:21 | odb-v3 | SENSEX 73100 CE @ ₹231.62 × 640 | back under prior ORH | −₹26,386 | closed at ₹1,217 |
| 09:25–10:00 | odb-v3 | SENSEX 73200 CE @ ₹183.81 × 620 → ₹488.19 | **10:00 time exit** | +₹1,88,105 | **peak ₹1,135 at 13:38**; held to the close ≈ +₹5.8L |
| 11:40–14:06 | etr-v1 | SENSEX 74000 CE @ ₹180.84 × 3,320 → ₹281.02 | swing low lost | +₹3,30,594 | peak ₹359 (13:38) while held; ₹315 at the close |
| 14:27–15:11 | mac-v1 | NIFTY 23000 PE @ ₹121.96 × 3,055 → ₹140.06 | 2R target | +₹54,428 | peak ₹151 after the exit |

**Shadow runs** (each alone at ₹5L):
- odm-v1 and odm-v2: NIFTY 22700 CE, 09:24–12:00, **+₹3,34,550** each.
- odm-v3 (the live odm-v4's breadth filter, A-088), odm-v5, orb-v1/v2/v3 and orc-v1/v2: no trade.

## What worked, what did not, and why

1. **The SENSEX morning re-entries** (the user's point).
   - odb-v3 has no trade limit; it re-enters on a fresh break of a level.
   - The first two calls were stopped within 1–2 minutes when the index dipped back under the prior ORH (−₹38,128 in
     all). The third held and made +₹1,88,105, so the three together netted +₹1,49,977.
   - The same rule lost on 14 Oct, when NIFTY opened on PDH. A-102 (no re-entry for 15 minutes after a loss) failed on
     193 days: re-entries make money on balance.
   - The early stops are noise around the level. Both stopped calls ended the day at 5–6 times their entry price.
2. **The 10:00 time exit sold a 0DTE call at ₹488 that went to ₹1,135.**
   - odb's window was designed as an opening drive that ends by 10:00.
   - On an expiry-day trend the convexity of a 0DTE option is the opportunity, and the fixed exit throws it away.
3. **etr caught the main SENSEX leg** (entry 11:40, +₹3.31L).
   - It exited on a lost swing low at 14:06, after a ₹359 → ₹281 giveback.
   - Holding to the close would have added about ₹1.1L. That is not clearly better: SENSEX fell to 73,941 by 15:11
     before the closing jump.
4. **The NIFTY morning drive was missed live.**
   - odm-v4's breadth filter (A-088, chosen from 9 in-sample trades) blocked it.
   - The unfiltered odm-v1/v2 made +₹3.35L each.
5. **ma-cross** took the afternoon pullback on NIFTY to its 2R target (+₹54K).

## Hypotheses (one change each, fixed now)

**A-110, odb-v7: no 10:00 time exit.**
- odb-v7 = odb-v3 with `time_exit: "15:15"`.
- Exits are then the structure stop (back through the broken level), the premium stop (25 %), the existing trail
  (arms at +20 %, exits on a 20 % giveback from the best bid) and 15:15.
- Everything else, including the trigger window 09:16–09:26 and the re-entries, is v3.

**A-111, odm-v6: no breadth filter.**
- odm-v6 = odm-v4 (drive momentum with the A-091 peak trail) without `filters.breadth_min_agree`, i.e. odm-v1 plus the
  trail.

## Test

- **Image:** Mac, ad74e92 (the deployed code).
- **Strategies:** the live set as deployed today: ebs-v2, ecr-v11, odb-v3, etr-v1, mac-v1, odm-v4.
- **Configs:** risk v17, features v12, costs v2, ₹5L per day.
- **Runs:** each variant in its strategy's place, on all 24 tick days (zt archive, 2 Sep–13 Oct) and 169 bar days.
- **Control:** the live set re-run on the same image.
- **Design days:** 14 Oct and 15 Oct (own capture, live equity), reported apart and not counted.

**PASS** (for each variant separately) needs all of:
- set net ≥ control on the tick days;
- set net ≥ control on the bar days;
- the worst day at most 25 % worse than the control's worst day on each data set.

A PASS is a decision for the user.

**A-112, odb-v8: a wider trail** (registered ~19:00, before any result).
- odb-v8 = odb-v7 with `trail_activate_pct: 50` and `trail_giveback_pct: 30` (v3/v7: 20 and 20).
- The values are round, fixed before looking at any option path, and the activation matches odm's A-091 trail.
- On 15 Oct odb-v7's 20 % trail sold the SENSEX 0DTE call at 11:20, before its ₹1,135 peak at 13:38.
- It applies on every day. Same test, control and criteria as A-110, reported beside odb-v7.

## Results so far (15 Oct ~19:45 IST; `.local/a110/`)

The control reproduces A-106's bar-day total to the rupee (+₹25,23,401).

| Row | Variant | Tick days | Bar days | Worst day | 15 Oct (design) | Verdict |
|---|---|---|---|---|---|---|
| A-110 | odb-v7, no 10:00 exit | +₹77,900 | **−₹2,14,612** | 11 % worse | +₹58,246 | FAIL (criterion 2) |
| A-111 | odm-v6, no breadth filter | −₹74,420 | −₹5,21,617 | 54 % worse | +₹3,30,528 | FAIL |

**Why holding odb longer fails: day type** (`.local/a110/daytype/split.py`). The labels are the same as A-094's: a day is
a trend day if either index trended.

| | Trend | Two-way | Narrow |
|---|---|---|---|
| Tick days | 67 % | 33 % | – |
| Bar days, 23 Jan–30 Sep | 44 % | 47 % | 9 % |

| odb-v7's effect | |
|---|---|
| trend non-expiry bar days | +₹1,64,592 |
| two-way non-expiry bar days | −₹3,16,019 |
| tick days | +₹77,900, all on trend days |

Holding past 10:00 pays on trend days and costs on two-way days. The day type is known only at the close; A-094 showed
it cannot be read from these inputs during the day. The user's remark on 15 Oct ("tick days might be trend days
mostly") is confirmed.

| Row | Variant | Tick days | Bar days | Worst day | 15 Oct (design) | Verdict |
|---|---|---|---|---|---|---|
| A-112 | odb-v8, no 10:00 exit, trail 50/30 | +₹62,115 | **−₹2,89,465** | 20 % worse | **+₹1,86,200** | FAIL (criterion 2) |

**odb-v8 by day type:**

| Days | Effect |
|---|---|
| trend non-expiry bar days | +₹2,73,497 |
| two-way non-expiry bar days | −₹4,48,363 |
| trend expiry bar days | −₹61,107 |

A wider trail amplifies A-110's pattern: larger gains on trend days, larger losses on two-way days. "Expiry trend days
only" does not rescue it on the bar data.

## Conclusions and plan (what can be done realistically)

1. **Nothing tested today is deployable.**
   - The three exit and filter changes win on today's kind of day and lose over 193 days.
   - The live setup (no egb, risk v17) stays as it is.
2. **The money today came from a trend day plus the existing rules.** etr (+₹3.31L) and odb's third entry (+₹1.88L).
   The losses were the two morning noise stops (−₹38K), and odb's re-entries netted +₹1.50L.
3. **The left-on-the-table problem is real but day-type bound.**
   - Today the exits left about ₹5L (odb) and ₹1L (etr) on a trend day.
   - Holding longer costs more on two-way days, and the day type cannot be read in time (A-094).
4. **What could change this:**
   - **A measurable trend-day signal at 10:00, from inputs we have not tried.** These are now being collected: GIFT
     Nifty overnight move, FII positioning, BANKNIFTY's agreement. It is the next candidate once enough days exist;
     that is weeks of forward data, not tonight.
   - **More tick history** (the vendor request). Tick days and bar days disagree on most rules: 67 % of tick days were
     trend days against 44 % of bar days. Rules judged on 24 trend-heavy tick days look better than they are.
   - **Capital.** etr's 12:41 add was refused for capital (₹7.6L equity). Equity rose to about ₹12.95L today, so the
     limit loosens by itself.
5. **The goal.**
   - Equity was about ₹12.95L after today, against ₹9.49L on the ₹10 Cr curve (5L × 1.096⁷), so it is ahead again.
   - About 8.4 % a session is still needed for about 54 sessions. The 15 Oct bootstrap assessment (a few % chance at
     the present size) stands; one trend day does not change it.
