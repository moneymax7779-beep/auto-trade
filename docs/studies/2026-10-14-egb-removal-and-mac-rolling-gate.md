# Removing egb-v4, and a rolling track-record gate for ma-cross (ledger A-095, A-096)

Registered 14 Oct 2026 ~07:25 IST, before any result. User: "register and test removing egb and the ma-cross rolling
rule", after the per-strategy review of the f75550 replays.

| Strategy | Tick days (24) | Bar days (169) |
|---|---|---|
| egb-v4 | 0 trades | 6 trades, 1 win, PF 0.17, −₹59,922 |
| mac-v1 | 53 trades, PF 2.42, +₹8.60L | 438 trades, PF 1.15, +₹6.70L |

mac-v1 on bar days by month: Feb–Mar +₹3.54L, Apr–Jun −₹8.35L, Jul–Sep +₹11.38L.

## A-095: the live set without egb-v4

**Change.** The live set minus `expiry-gamma-breakout.v4.yaml`. Every other file is as deployed (f75550e): ebs-v2,
ecr-v11, odb-v3, etr-v1, mac-v1 and odm-v4, with features v12, risk v12 and costs v2, at ₹5L per day.

**Test.** Mac, image f75550eb4877-a95 (the code of 9d960a5, whose trading is identical to f75550e). Replay all 24 tick
days (zt archive) and all 169 bar days (coarse, A-045).

**Control.** The f75550 all-days run of 13 Oct with the full live set. Its day nets were reproduced to the rupee on 14 Oct
(A-094), so it is reused, not run again.

**PASS** needs both:
- net ≥ the control's net on the tick days, and also on the bar days;
- no single day worse than the control by more than ₹50,000.

## A-096: ma-cross rolling track-record gate (evaluated on the trade sequence)

**Rule, fixed now.** At the start of each session, compute the profit factor (gross wins / gross losses, net of costs)
of the last 20 closed ungated mac-v1 trades, both indices and all days before this session.
- PF ≥ 1.0: ma-cross trades that day.
- PF below 1.0: ma-cross makes no entry that day.
- Fewer than 20 prior trades: it trades.

The record is the ungated strategy's own. When the gate is shut, mac-v1 keeps running unseen (in live: in shadow), so the
gate can reopen.

**Series.** mac-v1's trades from the control replays in date order: bar days 23 Jan–31 Aug, then the tick days 2 Sep–13
Oct. A tick replay is used wherever one exists; September bar days are not used.

**Measure.** The sum of mac-v1's trades on open days. The capital the gate frees for other strategies is not counted.
mac-v1 is listed last, so the others take capital first and this second-order effect is small. It is checked by a full
replay only if the test passes.

**PASS** needs all three:
- the gated net ≥ the ungated net + ₹1,00,000 over the whole series;
- the gated maximum drawdown of cumulative mac P&L ≤ 75 % of the ungated one;
- on the tick days, the gated net ≥ 90 % of the ungated net (the gate must not shut off the period that works).

**Sensitivity, reported only, never part of the verdict:** 10 or 30 trades instead of 20, and a threshold of 0.8 or 1.2.

**Caveat, stated now.** The April–June weakness was seen before this rule was written. A PASS here is in-sample evidence
only. A PASS leads to mac-v4 (v1 + gate, with v1 in shadow for the record), a full in-set replay, then a forward test.
Any live change is the user's decision.

## A-096 result (14 Oct ~07:40 IST; `.local/a96/gate.py`, `result.txt`)

The series is 429 mac-v1 trades, 23 Jan–13 Oct.

| | Net | Trades | Max drawdown |
|---|---|---|---|
| Ungated | +₹7,68,080 | 429 | ₹9,74,645 |
| Gated (registered rule) | +₹7,38,483 | 239 (61 days shut) | ₹5,31,650 |

- On the tick days the gate stayed open, so the result is the same as ungated (+₹8,59,752).
- The shut days avoided −₹5.51L in April–June but missed +₹5.80L in February, July and August. A track-record gate
  reopens only after the strategy has already recovered, so it misses the recovery.
- Sensitivity, reported only:

| Variant | Gated net |
|---|---|
| Last 30 trades | +₹9.94L |
| PF ≥ 1.2 | +₹8.36L |
| Last 10 trades | +₹5.92L |
| PF ≥ 0.8 | +₹4.39L |

  The result depends on the setting, which is the pattern of a fitted rule, so choosing the best variant after the
  fact would be overfitting.

**Verdict: FAIL.** Criterion 1 failed (−₹29,597 against the +₹1,00,000 required). The drawdown and tick-day criteria
were met. Not built.

## A-095 result (14 Oct 07:23–08:03 IST; Mac, image f75550eb4877-a95; `.local/a95/result.txt`)

| Days | Full live set (control) | Without egb-v4 | Difference |
|---|---|---|---|
| 24 tick days | +₹41,94,356 | +₹41,94,356 | ₹0 (egb never traded) |
| 169 bar days | +₹24,08,629 | +₹24,84,424 | +₹75,795 |

Only five days changed; every other day is identical to the control.

| Day | Change without egb |
|---|---|
| 3 Feb | +₹7,771 |
| 17 Feb | +₹28,578 |
| 5 Mar | +₹35,246 |
| 12 Mar | −₹12,017 (the worst day; the ₹50,000 limit holds) |
| 2 Apr | +₹16,217 |

The gain is egb's own −₹59,922 plus about ₹15.9K from capital the other strategies could then use.

**Verdict: PASS** (all three criteria). The effect is small, and it is measured on coarse bar data only: on the tick days
egb has never traded. Removing egb from the live set is the user's decision.
