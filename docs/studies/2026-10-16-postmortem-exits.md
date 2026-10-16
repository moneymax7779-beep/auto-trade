# 16 Oct post-mortem: two correlated opening-drive puts, and which exits actually help (ledger A-115)

User, 16 Oct ~16:20 IST: review today's trades and signals; "why losses though direction was correct ... we have to
seriously think on exit at right time"; sync today's data, research or fine-tune; act responsibly and realistically.

## The day (live session 914, PAPER-1, equity ₹12,95,511; SENSEX expiry 22 Oct, NIFTY 19 Oct)

| Time | NIFTY | Event |
|---|---|---|
| 09:15 | 23,065 | gap up +0.3 % to the previous high (PDH 23,056) |
| 09:21 | 22,978 | odm-v4 buys NIFTY 23000 PE (4,355 qty at ₹148.18) and SENSEX 74200 PE (1,060 qty at ₹594.41): breadth −64 / −78, futures FRESH_SHORT |
| 09:40 | 22,914 | the day's low: the puts' best point |
| 09:45 | 22,974 | NIFTY back above the session VWAP (22,970); SENSEX too (74,216 vs 74,199) |
| 10:06 / 10:15 | 23,094 | both puts stopped at the session extreme: −₹1,99,956 (−32 %) and −₹3,17,028 (−49 %) |
| 10:20 | 23,140 | short covering, futures RVOL 8×, breadth +90; the day closes 23,121 near its high |

- **Day:** −₹5,16,984, about 40 % of equity in one hour.
- **Kill switch:** engaged at 13:00 (limit ₹2,85,012). It was released from the UI at 13:01 and re-engaged at 14:03,
  as risk v17 does. It refused three ma-cross puts.
- **Shadows:** all lost or stayed flat.

## What the data says about exits (`.local/exits/`, 26 tick days, control run eb7b086613d2)

- **Method.** Premium-based exit overlays were tested on the real minute bids (zt option-chain snapshots) of all 88
  directional trades. An overlay can only exit earlier than the strategy did; the exit is at the bid − 1 %.
- **Selection rule, fixed before results:** the largest total improvement on non-ETR trades.
- **Result: no premium overlay improves the system.** None of these helps:
  - a stop at −15 % or −20 %;
  - break-even after +10–20 %;
  - trailing give-backs.

  Each either never fires or costs ₹0.6–9.6L on non-ETR trades. On ETR every overlay costs between ₹0.2L and ₹33L.
- **Why.** The winners dip first: odm 12 Oct NIFTY −22.9 % then +133 %; 17 Sep −22.4 % then +22.5 %; 12 Oct SENSEX
  −16 % then +80 %.
- **Paired entries.** Same direction on both indices within 5 minutes: 10 pairs, +₹5.75L, PF 4.21, against 3.88 for
  single entries. Paired trades are not worse on average; 16 Oct is the bad tail of that.

**Conclusion.** The "exit at the right time" problem is not solved by premium rules. Today's damage came from a
structural stop far away (the session extreme, about 100 NIFTY points) applied at about 50 % of equity per index. The
candidate is a structure exit: the index back through the session VWAP.

## The two puts, minute by minute (best bid from GCP's own capture)

| Time | NIFTY 23000 PE (cost ₹148.18) | SENSEX 74200 PE (cost ₹594.41) | Combined open P&L |
|---|---|---|---|
| 09:36, the best point | ₹181.15 (+22.2 %) | ₹650.05 (+9.4 %) | **+₹2.03L** |
| 09:45, index back above VWAP | ₹157.15 (+6.1 %) | ₹574.35 (−3.4 %) | +₹0.18L |
| 09:57 | ₹116.15 (−21.6 %) | ₹462.45 (−22.2 %) | −₹2.79L |
| exits 10:06 / 10:15 | ₹75.59 realised (−49 %) | ₹406.92 realised (−32 %) | −₹5.17L |

Why the protections never acted:
- odm-v4's peak trail arms only at +50 %; the best was +22 % and +9 %.
- Its stop is the session extreme, about 100 NIFTY points from entry.

## How much of a winner the strategies keep (26 tick days, real bids)

| Strategy | Trades | Avg peak | Avg exit | Winners keep (share of peak) | Losers that were +10 % first | Rose ≥ 20 % more after the exit |
|---|---|---|---|---|---|---|
| ma-cross | 53 | +14 % | +7 % | 91 % | 2 of 22 | 16 of 53 |
| expiry-trend-rider | 15 | +89 % | +43 % | 67 % | 4 of 8 | 8 of 15 |
| opening-drive | 9 | +19 % | +8 % | 71 % | 0 of 5 | 8 of 9 |
| opening-drive-momentum | 8 | +60 % | +44 % | 77 % | 0 of 1 | 7 of 8 |

- **Capture is already good.** The money left on the table is mostly after the opening strategies' time exits.
- **Holding unconditionally failed** on the bar days (A-110, A-112): it pays only on trend days. A hold conditioned on
  futures participation (odb-v9, A-114) is in shadow from 16 Oct.
- **ETR's losers were often in profit first,** but locking ETR's profits costs more on its big winners. No change.

## Size and compounding (live equity ₹7,78,527 after 8 sessions; the ₹10 Cr curve needs ₹10.42L)

- **Live returns:** +12.0, +78.0, +6.5, −5.2, +6.2, −28.9, +70.3, −39.9 %.

  | Sizing | End of the 8 days (from ₹5L) | Worst drawdown |
  |---|---|---|
  | as now | ₹7.79L | 40 % |
  | 0.75× | ₹7.64L | 30 % |
  | 0.5× | ₹7.07L | 20 % |
  | 1.25× | ₹7.45L | 50 % |

- **Compounded growth per session:**

  | Sizing | 169 bar days | 26 tick days |
  |---|---|---|
  | 1.25× | +1.50 % | +31.1 % |
  | as now | +1.48 % | +26.2 % |
  | 0.75× | +1.33 % | +20.8 % |
  | 0.5× | +1.03 % | +14.7 % |

- **Reading:** today's size is close to growth-optimal on history. 0.75× gives up little growth and cuts the worst
  drawdowns by about a quarter. The choice is the user's.

## A-115: odm-v7, opening drive momentum with a VWAP exit (registered 16 Oct ~16:55 IST, before results)

**The change.** odm-v7 = odm-v4, except it also exits on a 3-minute close back through the session VWAP
(`exits.vwap_trail_3m: true`, the same rule orc uses): a put exits with the index above VWAP, a call with it below.
- Everything else is odm-v4: entry, breadth filter, session-extreme stop, 50 % emergency stop, peak trail 50/15, 12:00
  flat.
- Config only; the live image b67b808 runs it.

**Test.**
- The live set (ebs-v2, ecr-v11, odb-v3, etr-v1, mac-v1, odm) with risk v17 and features v12, on all 26 tick days
  (2 Sep–15 Oct, zt capture) and all 169 bar days (coarse).
- Control: the same set with odm-v4 (runs eb7b086613d2 for tick days and ad74e92df4f3 for bar days, both on the live
  configuration).
- 16 Oct is the design day: reported at live equity from own capture, not counted.

**PASS** needs all of:
1. net ≥ control on the tick days;
2. net ≥ control on the bar days;
3. odm's own trades no worse on each data set;
4. no day worse than control by more than ₹50,000.

A PASS is a decision for the user; a FAIL is recorded and not deployed.

## A-115 result (16 Oct 16:51–18:02 IST; Mac, image b67b808 = GCP's live code; `.local/a115/`)

| | Control (odm-v4) | odm-v7 (VWAP exit) | Change |
|---|---|---|---|
| 26 tick days, set | +₹43,94,890 | +₹44,06,358 | +₹11,468 (2 days changed) |
| 26 tick days, odm's trades | 8, +₹7.57L | 8, +₹7.30L | −₹26,446 |
| 169 bar days, set | +₹25,23,401 | +₹24,09,755 | −₹1,13,646 (6 days changed) |
| 169 bar days, odm's trades | 8, 5 wins, +₹56,651 | 8, 1 win, −₹53,094 | −₹1,09,745 |
| 16 Oct at live equity (design day) | −₹5,16,984 (= live) | −₹22,813 | +₹4,94,171 |

- The worst changed day was −₹49,891 (12 Aug), just inside the limit.
- The control replay of 16 Oct reproduces the live day to the rupee.

**Verdict: FAIL** (criteria 2 and 3).
- The VWAP exit would have turned 16 Oct from −₹5.17L into −₹23K.
- On the bar days it exits four of odm's five winners at an earlier cross of VWAP, before their run.
- With 8 trades per data set the evidence is thin both ways. The honest path for this idea is a forward shadow test,
  not a live change.

## Improvement plan

1. **Exits.** Premium-based exits (stops, break-even, trails) are ruled out by the data; the strategies' own exits keep
   67–91 % of their peaks.
   - The remaining exit opportunity is holding the opening strategies past their time exits, only when futures
     participation confirms. odb-v9 (A-114) is in shadow on GCP from 16 Oct.
   - odm-v7 could join the shadow list, so the VWAP exit is judged on new days. That would be a shadow-list deploy, the
     user's decision.
2. **Size, the main lever for drawdowns.** Today's size is close to growth-optimal on history.
   - 0.75× (risk `premium_budget_pct` 75) costs about 0.15 % per session on bar days and turns the 40 % drawdown into
     about 30 %. The trade-off is the user's.
   - A risk-based per-trade cap (loss at the strategy's own stop as a % of equity) would need `SetupStrategy` to pass
     its stop distance. It would have to be tested with ETR, the profit engine, in mind.
3. **Paired same-direction entries** are profitable on average (PF 4.21 on 10 tick-day pairs). They are not to be cut
   without more evidence.
4. **Goal reality.** Equity is ₹7.79L against the ₹10.42L curve after 8 sessions. Two sessions in the last three lost
   30–40 % in the first hour; at this size the path to ₹10 Cr runs through such days.
