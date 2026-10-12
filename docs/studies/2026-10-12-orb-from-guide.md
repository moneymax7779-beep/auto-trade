# 15-minute opening-range breakout from the user's pasted guide (ledger A-082): registered before any replay

User, 12 Oct 2026 ~16:15 IST, pasted a guide ("The most effective and accurate approach to intraday options trading
... is the Opening Range Breakout (ORB) with Volume and Open Interest (OI) Confirmation") and asked: "review this and
see if we can have this if you think we can do implement and run in local docker and to replay".

## Review

The guide's core rule is close to two strategies already tested here and one already live:

| Guide | Already tested | Result |
|---|---|---|
| ORB, first close beyond the 09:15-09:30 range, volume filter | orc-v1 (A-076): 3-minute close beyond ORH/ORL, VWAP + EMA + futures volume, 1-ATR stop, VWAP trail | FAIL, 0 of 8 |
| same, entries before 10:15 too | orc-v2 (A-078) | FAIL, 2 of 14 (one estimated) |
| VWAP pullback in a trend | trend-day rider (A-064) | FAIL |
| straddle breakout | expiry-breakout-straddle ebs-v2 | live |
| RSI > 60 momentum | not tested; no RSI feature (EMA/VWAP/ATR used instead) | – |

What is new in the guide and not tested: (1) 15-minute candles, not 3-minute closes; (2) OI confirmation on the other
side (put OI building on an upside break, call OI building on a downside break); (3) a tight stop at the breakout
candle with a fixed 2R target instead of a wide stop and a trail; (4) the 10:15-14:30 window. The rule is worth one
registered test because those four differences are where A-076/A-078 lost (late chases, wide stops riding reversals).
The guide's general rules (1-2 % risk a trade, 2-3 trades a day, square off before 15:20, a daily loss limit) are
already enforced by the risk file or the frame, except per-trade risk sizing (here: the usual premium budget).

## Rule, orb-v1 (`opening-range-breakout.v1.yaml`), fixed now

Index that does not expire today. Range = 09:15-09:30 high/low. At a 15-minute candle close from 10:15 to 14:30
beyond the range, with futures volume >= 1.5x normal for the time of day and the other side's OI building (features'
peOiFlow / ceOiFlow ending in BUILD), buy the ATM option that way. Stop at the breakout candle's lowest (highest)
minute close; target 2R; 30 % emergency premium stop; flat 15:15; one trade per direction per index per day; ₹2.5L
premium budget scaled with equity like the rest.

## Test

Mac, new image; the 22 tick days (zt archive) plus 12 Oct (synced from GCP the same evening; no rule came from it).
Held-out 22-25 Sep read once; test = the other 18 days; 12 Oct reported apart. Runs: control (live set), live set +
orb-v1 (listed last), orb-v1 alone (its own edge without capital sharing, descriptive).

**PASS** if on the test days (in the set): orb-v1 >= 8 trades (else INCONCLUSIVE), net > 0, profit factor >= 1.3, no
day below −₹1,10,000, the other strategies lose <= ₹50,000 to it; held-out orb-v1 net >= 0. A PASS is the user's
decision (shadow or live PAPER); a FAIL is recorded and not deployed.

Data note: the contract masters of 5-9 Oct were synced from GCP before this test (the earlier replays of those days
ran without them), so this control can differ from A-079's control on 5-9 Oct; each test compares with its own control.

## Result (Mac, image 11b73a8-a82, 12 Oct 18:06–18:52; `.local/a82/summary.txt`)

Control: equal to A-079's control on every day except 5, 6, 7 and 9 Oct, which moved by −₹30, +₹281, +₹45 and −₹46
once the contract masters of those days were loaded from GCP (tick sizes and freeze limits; immaterial).

| | Trades (wins) | Net | PF | Worst day |
|---|---|---|---|---|
| In the set, test (18 days) | 17 (9) + 1 unfilled | +₹1,44,081 | 1.43 | 7 Oct −₹2,95,010 |
| In the set, others vs control (test) | – | −₹3,31,176 | – | – |
| In the set, held-out (4 days) | 6 (2) | −₹41,561 | 0.28 | – |
| Alone, test | 17 (10) | +₹4,23,089 | 3.17 | 7 Oct −₹1,53,089 |
| Alone, 12 Oct (out of sample) | 2 (2) | +₹1,54,358 | – | – |

**Verdict: FAIL as registered** — the worst day, the cost to the other strategies and the held-out days fail; the
trade count, net and profit factor pass. Not deployed.

- **The rule itself is the best standalone result of the new rules tested so far** (PF 3.17 on the test days, two
  targets on 12 Oct). Its targets come from the tight candle stop with a 2R target: many exits within minutes.
- **The cost is capital, not signals.** 15 Sep: its 13:00 SENSEX put held the capital the NIFTY trend rider needed on
  NIFTY's expiry day (−₹3,28,520). orc-v2 failed the same way (A-078). Any strategy on the non-expiring index competes
  with the expiry strategies for one pool of capital.
- **7 Oct** lost on both indices twice (10:30 calls, 13:15 puts): a two-way day, the rule's known weakness.
- **Held-out** is small (6 trades) and negative.

Next steps that would need their own registration: a capital reserve for the expiry strategies on expiry days (risk
file, new version), after which orb-v1 could be re-tested in the set; or orb-v1 in shadow (its own session, no shared
capital) as a forward test. Both are the user's decision.
