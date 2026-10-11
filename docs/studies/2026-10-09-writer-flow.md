# Writer flow: does near-the-money option OI change predict the index? (A-068)

Registered 2026-10-09 22:35 IST, before the extraction finished. Trigger: the 9 Oct NIFTY review. The index gained
300 points on a day when, between 09:20 and 10:30, put OI at 22300–22500 rose by 37 million while call OI at
22300–22400 fell by 7 million and futures OI fell 2.9 % against a rising price (short covering). None of the live
strategies read any of that: the only OI feature is the futures OI gate in the expiry trend rider.

## Question

For each minute of a session, writer flow = Δ over the last 15 and 30 minutes of (Σ put OI − Σ call OI) at the
ATM strike ± 2 steps of the nearest expiry, in lots, normalised by the same sum's level at 09:30. Alongside it the
futures OI change over the same windows and the index's move. Does writer flow predict the index's next 30 and
60 minutes? Does the reading at 10:15 predict the move from 10:15 to the close?

## Data

Own capture, 28 Sep – 9 Oct 2026 (9 sessions, NIFTY and SENSEX = 18 index-days), per-minute last OI per contract
from md.option_tick, front-month futures OI from md.future_tick, spot from md.index_tick. 9 Oct is the design day
and is reported apart. The option expiry day of an index is kept but flagged (OI behaves differently).

## Pass criterion (set before the result)

Worth a strategy (A-069) only if the Spearman IC of the 30-minute writer-flow change against the next 60-minute
index return has the same sign on at least 6 of the 8 non-design days and |IC| ≥ 0.08 pooled. Otherwise NO SIGNAL
and nothing is built.

## Result (23:05 IST, 18 index-days, 5,148 minutes 09:45–14:30; scratchpad wf/analyse.py, wf_minutes.csv)

Per-minute Spearman IC, non-design days (16 index-days):

| Predictor → target | Pooled IC | Index-days positive / negative |
|---|---|---|
| writer flow 30 min → next 60 min | −0.027 | 8 / 8 |
| writer flow 15 min → next 30 min | +0.032 | 9 / 7 |
| futures OI change 30 min → next 60 min | −0.025 | 8 / 8 |
| past 30-min index move → next 60 min (momentum, reference) | +0.193 | 8 / 8 |

Per day with both indices pooled, writer flow 30 → 60: +0.16, +0.01, −0.16, −0.01, −0.17, +0.48, −0.20, +0.04. Two
days of eight above +0.08, three below −0.08. Quintiles of the 30-minute writer flow show no ordering in the next
60-minute return (means −0.05 %, −0.04 %, −0.07 %, −0.01 %, −0.11 % from most call-heavy to most put-heavy).

The 10:15 reading against the 10:15–15:15 move, non-design, non-expiry index-days with |writer flow| ≥ 10 % of the
09:30 level: right on 29 Sep SENSEX (+23 % → +347), 30 Sep SENSEX (−16 % → −405), 6 Oct SENSEX (+11 % → +243),
8 Oct NIFTY (−12 % → −271); wrong or flat on 1 Oct NIFTY (+13 % → −125), 5 Oct both (+27 %, +39 % → flat),
7 Oct SENSEX (−18 % → −8). Four of eight. The 9 Oct design day was right on both indices, which is why it looked
like a signal.

Note on the momentum row: a pooled IC of +0.19 with the sign split 8/8 means trend days and chop days cancel; it
is the known regime problem, not a usable predictor on its own.

**Verdict: NO SIGNAL as registered.** The put build-up against call unwinding that was so clear on 9 Oct does not
predict the next hour on the other eight days, at the 30-minute horizon, with this definition. A-069 (opening-range
continuation gated by writer flow) is not built. What remains open and untested: the same flow read only in the
first 45 minutes of a gap day, which is a different and smaller question (about 4 such days in this sample, too few).

## Follow-up (23:20 IST): the user's momentum rule, pre-checked on the index path (scratchpad wf/momentum2.py)

User: "why can't we do it as long as OI change is valid and future volume is gradually increasing/decreasing and a
fixed percentage target ... enter in momentum ... whichever works". Pre-check on index points, not a replay: entry
when the index makes a new 60-minute high (low) with a 30-minute move of at least one stop unit, 09:45–14:30, one
trade at a time; stop 25 NIFTY / 80 SENSEX points, target 2× or 3× the stop or a 15-minute trail; a round-trip cost
of 10 / 30 index points (option spread + charges at delta 0.5). Filters: futures OI change over 30 min ≥ 0.2 % in
either direction ("valid"), rising ("build-up") or falling ("covering"); futures 15-minute volume above the previous
15 minutes. 16 non-design index-days; 9 Oct apart. 21 cells in all; the full grid is in the script output.

| Variant (2:1 target) | Trades | Win | Avg net pts | Days + / − | 9 Oct |
|---|---|---|---|---|---|
| momentum only | 78 | 50 % | +7 | 5 / 3 | −265 |
| + OI valid | 47 | 51 % | +8 | 4 / 4 | −215 |
| + OI covering (what 9 Oct was) | 12 | 42 % | −17 | 3 / 4 | −255 |
| + OI build-up | 35 | 54 % | +16 | 7 / 1 | +40 |
| + volume rising | 54 | 56 % | +18 | 7 / 1 | −235 |
| + OI valid + volume rising | 31 | 65 % | +32 | 7 / 1 | −220 |
| + OI build-up + volume rising | 24 | 71 % | +49 | 8 / 0 | 0 trades |

Reading: (1) the rule as a whole loses on 9 Oct itself, because a new-60-minute-high entry after 09:45 buys into
the three-hour range; the 9 Oct money was the 09:36 range break, before this rule can fire. (2) The best cell is the
best of 21 on 16 index-days and never fired on the design day; it is a selection result until a replay says
otherwise. (3) Under the OI build-up filter NIFTY is flat (13 trades, −5 pts) and SENSEX carries everything (22
trades, +579 pts); 12 of the 35 trades are on 30 Sep, a SENSEX expiry-eve chop day, and lost. (4) Futures volume
rising is the one filter that helps in every exit variant. (5) Costs are 40 % of the stop on NIFTY: a fixed-point
scalp has to clear them every time.

Not registered as a strategy yet: the variant has to be chosen before the replay, by the user, from this table, not
after it.

## Measurement fix and A-069 pre-check (23:50 IST; scratchpad wf/analyse2.py, levelbreak2.py)

A flaw found while reading the 9 Oct minutes: the ATM ± 2 basket changes strikes as spot moves, so its OI sum jumps by
a whole strike's OI (10–13 million) whenever the ATM shifts, which it did eight times in the first half hour on
9 Oct. Flow is now measured on fixed strikes: Σ OI(t) − Σ OI(t − k) over the strikes that are ATM ± 2 at t. The
volume-burst baseline also could not exist before 09:46 (30-minute median); it now needs 5 minutes.

**A-068 re-measured (16 non-design index-days):** writer flow 30 min → next 60 min pooled IC +0.19 (was −0.03), but
positive on 8 of 16 index-days and at or above +0.08 on 3 of 8 days (2 at or below −0.08). Quintiles: the most
call-heavy fifth −0.15 %, the other four between −0.05 % and +0.01 %. Still NO SIGNAL by the registered criterion:
like momentum, it is a trend-day effect that chop days cancel. The first measurement's near-zero IC was partly the
basket artefact; the verdict does not change.

**A-069 pre-check, the user's rule as registered:**

| Variant | Trades | Win | Avg net pts | Days + / − | 9 Oct |
|---|---|---|---|---|---|
| level cross, no filter | 31 | 29 % | −32 | 1 / 7 | 8 trades, −₹1.81L |
| + writer confirmation | 9 | 33 % | −25 | 3 / 3 | 4 trades, −₹2.48L |
| + volume burst | 28 | 32 % | −24 | 3 / 5 | 5 trades, −₹1.60L |
| + writer + burst (the rule) | 9 | 33 % | −25 | 3 / 3 | 4 trades, −₹2.48L |

Per level, unfiltered: today's ORH 1 win in 8, ORL 2 in 8, prior-day ORH 0 in 3; previous close and prior-day ORL
were the only levels above water. The first cross of an opening-range level is usually a false break on these days.
On 9 Oct the NIFTY ORH break at 09:33 (+65 points unfiltered, target at 09:55) passed neither filter: put OI had not
built over the prior 15 minutes on fixed strikes, and the futures burst came at 09:38, five minutes later. The rule's
four 9 Oct trades were the 12:07 and 14:18 crosses, both stopped.

**Verdict: pre-check FAIL (9 trades, negative, no better than nothing). A-069 is not built.** Not because the idea is
silly, but because on these nine days the confirmations do not pick the breaks that run, and the day it was drawn
from is a losing day for it.

### Amendment at the user's request (23:58 IST, "3 minutes 1 minutes"): writer confirmation over 3 and 1 minutes

Same rule, same levels, exits and costs; only the OI lookback changes (fixed strikes). Non-design = 16 index-days.

| Writer lookback | Filter | Trades | Win | Avg net pts | Days + / − | 9 Oct |
|---|---|---|---|---|---|---|
| 15 min (registered) | writer + burst | 9 | 33 % | −25 | 3 / 3 | 4 trades, −₹2.48L |
| 3 min | writer only | 14 | 36 % | −19 | 3 / 3 | 3 trades, +₹2.18L |
| 3 min | writer + burst | 14 | 36 % | −19 | 3 / 3 | 2 trades, +₹0.89L |
| 1 min | writer only | 12 | 25 % | −39 | 2 / 3 | 1 trade, ₹0 |
| 1 min | writer + burst | 10 | 30 % | −30 | 2 / 3 | 1 trade, ₹0 |

With the 3-minute lookback the 9 Oct NIFTY ORH break at 09:33 passes the writer test (+65 points, +₹1.30L) and the
SENSEX ORH break at 09:31 passes too (+₹0.88L); the volume-burst requirement then removes the NIFTY one, because the
burst came at 09:38. On the other eight days the same 3-minute confirmation picked 14 crosses and won 5: 29 Sep
both indices stopped twice each, 5 Oct one win and four stops, 7 Oct SENSEX stopped, 8 Oct NIFTY and 6 Oct SENSEX
won. Net −₹1.76L before 9 Oct, +₹0.42L with it.

Verdict unchanged: the confirmation is right on the design day and no better than a coin flip on the rest, at
every lookback tried. Not built.
