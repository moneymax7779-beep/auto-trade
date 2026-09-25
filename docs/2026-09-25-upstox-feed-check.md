# Upstox feed check: India VIX and closing-auction (CAS) data

Date: 2026-09-25. Phase 0 item. Sources: Upstox Market Data Feed V3 documentation, the Upstox
instrument master (`NSE.json.gz`, 24 Sep 2026 build), and the protobuf classes in
`upstox-java-sdk` 1.27 (used by zt-tiger-v2) and 1.29 (latest, published 7 Sep 2026).

## Result

| Need | Available from Upstox? | How |
| --- | --- | --- |
| India VIX, live | **Yes** | Instrument key `NSE_INDEX|India VIX` (exchange token 26017), index feed: LTPC and OHLC |
| India VIX, 252-day history for percentiles | Expected yes | Historical candle API with the same key; confirm once the Upstox adapter has a token (Phase 3) |
| Per-stock CAS auction data | **Yes, since SDK 1.29** | `MarketFullFeed` fields below, in `full` / `full_d30` modes for CAS-eligible stocks |
| CAS phase per segment | **Yes, since SDK 1.29** | `FeedResponse.marketInfo.casMarketStatus` (segment → status, updated time) |

zt-tiger-v2 uses SDK 1.27, whose protobuf has none of the CAS fields, and subscribes constituents
for price/volume only. That is why its database has the CAS indicative *index* value but nothing
per stock. auto-trade's Upstox adapter must use SDK ≥ 1.29 (or its own protobuf from the 1.29
schema) and subscribe index constituents in `full` mode.

## CAS fields in `MarketFullFeed` (SDK 1.29 protobuf)

| Field | Java type | Meaning (Upstox docs) |
| --- | --- | --- |
| `iep` | double | Indicative equilibrium price: the price where the most quantity matches. Also in LTPC during pre-open and CAS |
| `rp` | double | Reference price used for the CAS price band and circuit filters |
| `ieq` | long | Indicative equilibrium quantity executable at `iep` |
| `iiqTotal` | long | Total indicative imbalance quantity unmatched at `iep`; **positive = buy excess, negative = sell excess** |
| `iiqM` | long | Imbalance from unpriced market orders; same sign convention |
| `casEligible` | boolean | Whether the instrument takes part in the call auction |

`casMarketStatus` values: `CTS_CLOSE`, `CAS_LM_START`, `CAS_M_STOP`, `CAS_STOP`.
`preOpenSessionStatus` values: `PRE_OPEN_START`, `PRE_OPEN_M_END`, `PRE_OPEN_END`.

## What this unlocks in the threshold file

All CAS score components become computable from live data, per constituent and then weighted by
index weight:

- `iep_direction`, `iep_velocity` from `(iep − rp) / rp` over time.
- `weighted_buy_imbalance` from `iiqTotal / (ieq + |iiqTotal|)` (normalisation to be fixed in
  Phase 1 once real values are seen; the docs give no cumulative buy/sell quantities separately).
- `cas_breadth`, `low_concentration` from the sign and size of each stock's weighted IEP return.
- Liquidity confidence from `ieq` against its own rolling percentile.

`cas.requires_constituent_auction_feed` in `early-confirm-runner.v1.yaml` stays as it is until
these fields are captured; there is no history yet, so the CAS module cannot be replayed or
calibrated before auto-trade's own capture runs through several CAS sessions.

## Subscription limits (normal Upstox user)

| Mode | Per-mode limit | Combined limit |
| --- | --- | --- |
| LTPC | 5,000 keys | 2,000 keys |
| Option Greeks | 3,000 keys | 2,000 keys |
| Full | 2,000 keys | 1,500 keys |

Two WebSocket connections per user (five for Upstox Plus, which also gets `full_d30`: 50 keys).
zt-tiger-v2 already holds feed connections on the same user; running auto-trade's own feed
alongside it may exhaust the two-connection limit. Decide in Phase 3 whether auto-trade takes
over capture or uses a second Upstox user.

## Follow-ups

1. Phase 3: Upstox adapter on SDK ≥ 1.29; capture VIX (`NSE_INDEX|India VIX`) and the CAS fields
   for all NIFTY 50 and SENSEX 30 constituents in `full` mode (80 keys, well inside the limit).
2. Phase 3: backfill 252 daily India VIX candles; confirm the historical API accepts the key.
3. First CAS sessions captured: check `iiqTotal` sign against the index move, then fix the
   imbalance normalisation in a new threshold-file version.
