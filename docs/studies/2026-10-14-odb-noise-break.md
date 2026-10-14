# 14 Oct: opening-drive bought a 3-point "break" with the whole account (ledger A-097, A-098)

Registered 14 Oct 2026 ~19:50 IST, before any variant is replayed. User, ~19:30: review today's signals and trades,
"do whatever is needed", "run as many replays as you want".

## What happened on 14 Oct (live session 894, PAPER-1, equity ₹10,69,295; no index expiring; SENSEX expires 15 Oct)

NIFTY opened 22,715.75, **3.25 points (0.014 %) below PDH 22,719.0**. SENSEX opened 73,047.7, 2.8 points above its PDH.

| Time | odb-v3 | Context at entry | Result |
|---|---|---|---|
| 09:17 | NIFTY 22750 CE, 6,240 qty at ₹165.83 (₹10.35L premium, about the whole equity) on spot 22,747 > PDH | breadth −0.9 (09:17) after −32 at 09:16; futures FRESH_SHORT from 09:18 | spot back below PDH at 09:18, STRUCTURE_PDH exit −₹1,83,366 (−17.7 %) |
| 09:24 | NIFTY 22700 CE, 5,980 qty at ₹178.13 on spot 22,722 > PDH (fresh break) | breadth about −45 | back below PDH at 09:25, −₹1,25,417 |

The day's loss of −₹3,08,783 was more than the 22 % daily limit (₹2,35,245). The account kill switch engaged at the
next entry attempt (10:42) and refused every ma-cross entry for the rest of the day.

**Replays (Mac, own capture, live equity; `.local/pm1014`).** The as-deployed replay reproduces live to the rupee
(−₹3,08,783). The same set without odb-v3 makes **+₹3,60,522**:

| Time | ma-cross trades | Result |
|---|---|---|
| 10:42 | calls | −₹2,16,447 |
| 12:54 | puts, the 13:00–13:30 fall | +₹6,83,121 (target) |
| 14:12 | puts | −₹1,06,153 |

Earlier odb record (tick-day replays and live, `.local/alldays`):
- Wins came when price never returned to the level (+22 %, +67 %, +24 %).
- Every loss came within 1–2 minutes of entry (−14 %, −9 %, −11 %; live 30 Sep −14 %; today −17.7 % and −11.6 %).

## Hypotheses (one change each, values fixed now, round numbers)

**A-097, odb-v5: open-distance gate.** odb-v3, except a level counts only if the day opened at least **0.1 % of the
level** away from it on its untouched side (`scope.min_open_distance_pct: 0.1`).
- 0.1 % is about 23 NIFTY or 73 SENSEX points.
- An open within a few points of the level makes the first-minute noise a "break". Today's NIFTY open was 0.014 % away.

**A-098, odb-v6: breadth agreement.** odb-v3, except an entry needs mover breadth ≥ +50 for a call or ≤ −50 for a put at
the entry snapshot (`filters.breadth_min_agree: 50`).
- These are A-088's values; an unknown breadth fails, as in A-088.
- There is no breadth before July on bar days, so v6 cannot trade there. Its bar-day comparison is therefore limited to
  July–September.

## Test

Mac, one image; features v12, risk v12, costs v2, ₹5L per day.

- **Days:** all 24 tick days (zt archive) and all 169 bar days (coarse, A-045).
- **Runs:** the live set as deployed on 14 Oct, with odb-v5 or odb-v6 in place of odb-v3.
- **Control:** the f75550 all-days run (13 Oct, reproduced to the rupee on 14 Oct by A-094).
- **Design day:** 14 Oct is reported separately at live equity and is not counted.

**PASS** (for each variant separately) needs all of:
- the set's net ≥ control on the tick days;
- the set's net ≥ control on the bar days (for v6, on bar days from 1 Jul);
- the variant's own odb trades have PF ≥ 1.5 over tick and bar days together;
- no day worse than control by more than ₹50,000.

**FAIL** otherwise. A PASS is a decision for the user. Note that SENSEX expires on 15 Oct and odb-v3 is live at 09:16.

Already known: odb-v4 (A-072, a 15-minute cool-down after a loss) passed on 2 cases and is not deployed. It would have
blocked today's second trade (−₹1,25,417).

## Results (14 Oct 19:56–20:48 IST; Mac, image 9d960a5-a97b; `.local/a97/`, `evaluate.sh`)

The first attempt, image 9d960a5-a97, ran an app jar that had not been repackaged, so it still contained v3's code. It
was stopped, and its sessions are not used (`.local/a97/*-badjar`). The jar was rebuilt and checked to contain the new
classes before the re-run.

| | Tick days vs control | Bar days vs control | Its odb trades | Worst day | 14 Oct at live equity |
|---|---|---|---|---|---|
| control (odb-v3) | +₹41,94,356 | +₹24,08,629 | 75, PF 2.23 | – | −₹3,08,783 |
| **A-097 odb-v5** | −₹21,056 (only 8 Sep) | −₹1,45,227 (20 days changed) | 52, PF 2.09 | −₹39,672 | **+₹3,60,522** |
| **A-098 odb-v6** | −₹34,355 | −₹3,13,510 from 1 Jul (−₹4,88,446 over all) | 8, PF 3.83 | −₹1,03,660 | −₹3,41,863 |

**Both FAIL.**

- **v5.** The open-distance gate removes the noise breaks of days like 14 Oct, but it also removes good breaks from
  near-level opens. Over 193 days it costs −₹1.66L, set against +₹6.69L on the design day at double the size.
- **v6.** At these values the breadth filter blocks most of odb's winners. On 14 Oct it did not block the 09:24 poke,
  where breadth had reached +50.
- **Conclusion.** odb's 1-minute noise stops are part of its normal distribution, not a separable defect. 14 Oct was a
  tail day, made large by sizing: the premium was about 100 % of equity, so each noise stop cost 12–18 % of equity.
