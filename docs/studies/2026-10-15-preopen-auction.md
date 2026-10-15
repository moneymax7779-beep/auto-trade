# The pre-open auction as opening context (features v14; ledger A-113)

Registered 15 Oct 2026 ~22:00 IST, before any pre-open value is compared with a trade. User: "build the pre-open auction
feature and register the test", after the 14 Oct review. That day odb-v3 bought a 1-minute poke of PDH at 09:17.
- **Before the trade:** the pre-open auction had indicated a flat open, with 26 of 50 NIFTY stocks below their previous
  close and SENSEX 20 of 30 with sell imbalance. FII positioning was 9.8 % long.
- **At 09:17:** most in-session features did not exist yet.

## The feature (features v14 = v13 + `preopen.enabled`)

| Field | Meaning |
|---|---|
| `preOpenReturnPct` | index-weighted mean of the indicative equilibrium price (IEP) against the previous close, in % |
| `preOpenBreadth` | weighted share of stocks indicated up minus down, −100 to +100 |
| `preOpenImbalance` | weighted share with buyers in excess minus sellers in excess, −100 to +100 |
| `preOpenCoveragePct` | index weight with an auction quote and a previous close |

- **Point-in-time:** the feature uses only auction ticks received before 09:15 (receipt time, so a replay keeps exactly
  what the live engine had). The quotes are final then, but a stock's weight and previous close arrive with its first
  constituent tick, so the value is recomputed until 09:20 and kept unchanged from the 09:20 snapshot on. An entry
  before 09:20 records the value known at that moment.
- **Weights and previous closes** come from the constituent feed.
- **Where it is stored:** in every candidate's context.
- **Used by:** no strategy.
- **Data:** our own capture holds the raw auction ticks from 28 Sep 2026; zt-tiger-v2 has no pre-open prices. Replays
  compute the feature for any captured day, so it does not need to be live to be collected.

## Hypothesis (A-113, values fixed now)

Opening-drive entries that go **against** the pre-open read do worse than those that do not.

- **Pre-open score** = the mean of `preOpenBreadth` and `preOpenImbalance` of the traded index.
- **Against:** a call with a score ≤ −20, or a put with a score ≥ +20.
- **Agreeing:** a call with a score ≥ +20, or a put with a score ≤ −20.
- **Neutral:** everything in between.

**Entries:**
- every odb-v3 and odm-v4 entry (the live versions);
- odm-v1 (the unfiltered shadow), for more samples, in a separate replay of odm-v1 alone
  (`--autotrade.trading.strategy-files=config/strategy/opening-drive-momentum.v1.yaml`), so it does not take capital
  from the live set.
- All replayed from own capture with features v14 at ₹5L.

**Sample:**
- 28 Sep–15 Oct is the design sample. It is reported only and does not count.
- The verdict is on sessions from 16 Oct on, once at least **20** such entries exist with pre-open coverage ≥ 70 %.

**PASS** needs both:
- the "against" entries have a negative net, and their profit factor is at most two-thirds of that of the agreeing and
  neutral entries together;
- at least 6 "against" entries.

**On PASS:** a gating rule (no odb/odm entry against the pre-open read) is registered and replay-tested before any live
change. **On FAIL or too few:** nothing changes.
