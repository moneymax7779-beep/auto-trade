# auto-trade — implementation plan

Status: agreed 2026-09-25. Phase 0 done (736a423), Phase 1 done (8067b4c), Phase 2 in progress; see sections 10–11.

**Database decision (2026-09-25, for testing and implementation):** market data is read directly
from zt-tiger-v2's database, read-only, with no copying (`bin/autotrade replay --from zt`, the
default). auto-trade's own state (session split, runs, later signals and orders) lives in
`autotrade-postgres`. A dedicated market-data database comes later; the session cloner
(section 4) is kept for that.

auto-trade is an autonomous index-options trading platform for NIFTY, SENSEX and (later)
BANKNIFTY, designed from the start for multiple users, multiple brokers and a later extension to
stocks and stock F&O. Upstox is the primary broker for market data and the first execution
adapter.

## 1. Principles

1. **The platform is the product; a strategy is a hypothesis.** Every strategy plugs in behind a
   flag and earns capital only by passing: replay on tuning sessions → one run on held-out
   sessions → forward PAPER → LIVE on one lot of the owner's account.
2. **Live and replay run identical code.** All inputs pass through one `Clock` and one event
   journal; a backtest is the live engine fed from the journal.
3. **Point-in-time features.** Every feature is keyed by the time it became *available*
   (receipt time), never by an exchange timestamp the feed does not provide (option OI has none).
4. **Own state, borrowed market data.** auto-trade keeps its own state in its own database. Until
   the dedicated market-data database exists, it reads zt-tiger-v2's captured ticks read-only;
   the clone tool copies sessions in once that database is ready.
5. **Everything tunable is versioned config.** Threshold files carry a content hash that is
   stamped on every signal and run.
6. **PAPER by default.** LIVE requires an explicit per-account switch, a passed validation gate
   and the compliance items in section 8.

## 2. Architecture

```
           Upstox WS V3 (protobuf)            zt-tiger-v2 PG (read-only, clone tool only)
                    │                                     │
            ┌───────▼────────┐                   ┌────────▼────────┐
            │ MarketData GW  │                   │  session-cloner  │
            │ (Broker SPI)   │                   └────────┬────────┘
            └───────┬────────┘                            ▼
                    │                      autotrade-postgres (TimescaleDB): md.*, ref.*
                    ▼                                     │
          Event journal (Chronicle Queue) ◄── same event model ── Replay feed
                    │
     ┌──────────────▼───────────────── TRADING CORE (1 active + standby) ──┐
     │ Disruptor ring → Feature engine (incremental, point-in-time)        │
     │   → Regime engine (DTE, session phase incl. CAS, vol regime)        │
     │   → Strategy plugins (lifecycle state machine, scores)              │
     │   → TradeIntent → Pre-trade risk (global + per account)             │
     │   → OMS (order state machine, idempotency, reconciliation)          │
     └──────────────┬──────────────────────────────────────────────────────┘
                    │ Postgres transactional outbox
     ┌──────────────▼──────────────┐      ┌──────────────────────────┐
     │ PLATFORM API (stateless, N) │◄────►│ React UI (WS + REST)     │
     └──────────────┬──────────────┘      └──────────────────────────┘
     Execution adapters per account: Upstox (v1) · Zerodha · Dhan … (BrokerGateway SPI)
```

### Stack

| Layer | Choice |
| --- | --- |
| Runtime | Java 21 LTS (installed JDK; move to 25 LTS when installed), virtual threads |
| Framework | Spring Boot 4.1, Spring Modulith boundaries, ArchUnit tests |
| Hot path | LMAX Disruptor (one thread per underlying), Chronicle Queue journal |
| Upstox | Market Data Feed V3 (protobuf), Orders v3, portfolio-update WebSocket |
| Database | PostgreSQL 17 + TimescaleDB (own container), Flyway, JDBC/jOOQ |
| Research | Parquet + DuckDB for large scans |
| Cache / rate limits | Valkey |
| Auth | Keycloak (OIDC); roles ADMIN / TRADER / VIEWER |
| Secrets | Vault or KMS envelope encryption for broker tokens |
| Frontend | React 19 + TypeScript + Vite, TanStack Query, Zustand, TradingView Lightweight Charts, AG Grid, Tailwind + shadcn/ui |
| Observability | Micrometer → Prometheus/Grafana, OpenTelemetry, Loki |
| Hosting | Always-on VM in Mumbai with a static IP (required for API orders) |

### Kafka

Not in v1. Tick → feature → signal → order stays in one process. Durable events use a Postgres
outbox (`FOR UPDATE SKIP LOCKED`) behind an `EventBus` port. Adopt Redpanda/Kafka when there are
multiple hosts, more than ~50 accounts, or several independent consumers of market data. Topics
then: `md.ticks` (key instrument), `signals`, `trade-intents` (key strategy), `account-orders`
(key accountId), `order-events`, `positions`.

### Modules (Maven multi-module; added as each phase needs them)

```
platform-core        domain: InstrumentId, AssetClass, Underlying, Money, Qty, Clock, market events
strategy-config      versioned threshold YAML loader + canonical content hash
marketdata-store     Flyway migrations, md.* writers (COPY) and ordered replay reader
autotrade-tools      CLI: sessions, clone, verify, replay, config-hash
instrument-master    daily contract master → expiries, DTE, lot size, freeze qty, holidays   (Phase 1)
features, regime     point-in-time feature and regime engines                                (Phase 1–2)
strategy-api, strategies/*                                                                   (Phase 2)
risk, oms, broker-api, broker-upstox, broker-paper, portfolio                                (Phase 3)
app-trading-core, app-platform-api, ui/                                                      (Phase 3–4)
accounts                                                                                     (Phase 5)
```

Key abstractions: `Strategy` emits intents, never orders; one signal fans out into one
`AccountOrder` per subscribed account, each sized and risk-checked for that account and executed
in isolation; `BrokerGateway` exposes place/modify/cancel, order updates, positions, funds and a
capability matrix; a canonical `InstrumentId` maps to each broker's tokens. `Underlying` carries
`isIndex / hasFutures / hasOptions / constituents` so stocks slot in later (with ban-period and
physical-settlement guards).

## 3. Own database

Container `autotrade-postgres` (TimescaleDB, PostgreSQL 17), `127.0.0.1:5500`, own volume.

| Schema | Contents |
| --- | --- |
| `ref` | index weights, exchange session config (CAS timings, band), later instruments / calendar |
| `md` | `load_manifest`; hypertables `index_tick`, `future_tick`, `option_tick`, `constituent_tick`, `session_phase_event`, `candle`; `oi_profile` |
| `research` | `session_split` (tuning / held-out), later experiments and runs |
| `feat`, `acct`, `trade`, `audit`, `ops` | created empty; filled by later phases |

## 4. Session cloning from zt-tiger-v2

`bin/autotrade clone --session 2026-09-22 --underlying NIFTY,SENSEX`

- Connects to zt-tiger-v2 (`127.0.0.1:5490`) with `default_transaction_read_only=on`; never
  changes that instance.
- Refuses to run 09:00–15:50 IST on weekdays unless `--force`, so live capture is not slowed.
- Streams `market_tick_records` by `(underlying_key, session_date, sequence_number)`, checks each
  payload against its SHA-256 `payload_hash`, parses it into typed columns and bulk-loads with
  `COPY`, all inside one transaction per session and underlying.
- Verifies before commit: row counts per tick type, first/last sequence, and a SHA-256 digest
  over `(sequence, payload_hash)` in sequence order, computed on the source stream and again on
  the destination rows. Any mismatch rolls back.
- Records an `md.load_manifest` row (source, counts, digests, tool version, status). Readers use
  only ACTIVE manifests, so a reload supersedes the previous one atomically.
- Also clones that session's candles, CAS phase events and OI profiles.

Source facts (checked 2026-09-25): 17 sessions 2–25 Sep (14 Sep absent; 4, 11, 16, 21 Sep
partial). Since zt-tiger-v2's retention run on 25 Sep evening, its database keeps the newest 10
sessions (11–25 Sep); 2–10 Sep are archived, verified, on the external drive
(`/Volumes/Expansion/zt-tiger-v2-archive/market_tick_records/*.copy.gz`, restorable with
zt-tiger-v2's `restore-session`). Direct replay therefore covers 11–25 Sep: tuning 11, 15, 16, 17,
18, 21 Sep and held-out 22–25 Sep. About 3.1–3.8M tick rows per session for NIFTY + SENSEX: index, futures, an ATM band of
options with 5-level depth and IV/Greeks, and 50 + 30 constituents with official weights. Gaps:
BANKNIFTY (one day only), India VIX, per-constituent CAS auction data. Tick receipt times are
stored as IST local time without zone.

Sessions split, declared before any study:

| Set | Sessions | Expiries |
| --- | --- | --- |
| Tuning | 2–21 Sep (13 sessions) | NIFTY 8, 15 Sep; SENSEX 3, 10, 17 Sep |
| Held-out | 22–25 Sep (4 sessions) | NIFTY 22 Sep; SENSEX 24 Sep |

## 5. Threshold defaults

`config/strategy/early-confirm-runner.v1.yaml` holds the ChatGPT-suggested values as the
starting defaults, `status: UNCALIBRATED`. Values ChatGPT did not give are marked `placeholder`.
Any change is a new file version; the UI shows UNCALIBRATED until a version passes held-out
replay. CAS components that need per-constituent auction data stay disabled until a feed
provides it; the CAS score renormalises over the available components.

## 6. Phases

| Phase | Deliverables | Done when |
| --- | --- | --- |
| **0 (1 wk)** | Maven skeleton; Compose (autotrade-postgres, Valkey, Keycloak profile); Flyway schemas; `session-cloner`; clone the 17 sessions; threshold YAML loader with hash; check Upstox V3 for CAS and VIX fields | Every clone manifest verified; `replay --session 2026-09-02` streams events in order |
| **1 (2–3 wks)** | Instrument master; point-in-time feature engine (level ladder, VWAP/EMA9/20, swings; futures momentum/acceleration/basis; time-of-day RVOL and slope; near-ATM OI velocity and wall weakening; IV/Greeks with own Black–Scholes fallback; premium response; weighted breadth; CAS-available features); option-path fill simulator; dated cost model | Feature unit tests; spot checks against zt-tiger-v2 panels |
| **2 (2–3 wks)** | Strategy SPI; lifecycle WATCH→ARMED→EARLY→CONFIRMED→RUNNER→EXIT; regime engine; EARLY/CONFIRM/RUNNER scores plus Direction/Participation/Structure/Continuation; `early-confirm-runner` on defaults; research ledger | Tuning replay report, then one held-out run; frames and episodes separate; base and stressed fills |
| **3 (2–3 wks)** | Upstox live feed into `md.*`; OMS; paper broker; risk and kill switches; broker-side stop; 15:20 square-off; reconciliation | 10 clean PAPER sessions |
| **4 (2 wks)** | React UI: score board, lifecycle timeline, positions/P&L, replay scrubber, config history, audit | — |
| **5 (2–3 wks)** | Multi-user: Keycloak, Upstox OAuth onboarding, per-account risk/allocation, fan-out, Vault | Account-isolation tests |
| **6** | LIVE one lot, owner's account only, Mumbai static-IP host | 20 sessions matching PAPER |
| **7** | Second broker; BANKNIFTY; stocks and stock F&O; Kafka if scale requires | — |

Start capturing BANKNIFTY and India VIX as soon as the own feed exists (Phase 3); backfill daily
VIX for 252 days from Upstox historical candles for percentiles.

## 7. Production must-haves

- Resting stop-limit order at the broker on entry; no raw market orders on options (marketable
  limits with a buffer).
- Deterministic client order id (Upstox `tag`); order-state machine fed by the portfolio stream
  and reconciled against REST.
- Freeze-quantity slicing and lot sizes from the contract master, never hard-coded.
- Daily Upstox token re-authorisation through OAuth / token approval. Never store user passwords
  or TOTP secrets.
- Square-off by 15:20; CAS mode from 15:15.
- Kill switches (global, account, strategy). A stale feed blocks entries only.
- chrony time sync; receipt-time features.
- Full decision evidence in the audit log, replayable.
- Check current Upstox WebSocket connection/instrument limits and order rate limits; auto-trade's
  own feed and zt-tiger-v2 on the same Upstox user may compete for connections.

## 8. Compliance (before any user other than the owner)

SEBI's retail algo framework applies: static-IP API orders, algo-id tagging through the broker,
and registration above the order-per-second threshold. Running strategies for other users makes
the operator an algo provider, which generally needs exchange empanelment through a broker and
possibly SEBI RA/IA registration. Other users stay PAPER until this is confirmed with Upstox and
a compliance adviser.

## 9. Assessment of the source design

Kept: staged lifecycle with a separate runner decision; one strategy with regime-dependent
parameters; DTE from the contract master; premium response / IV-crush detection; CAS as its own
mode excluded from normal statistics; exchange rules in config.

Cautions: the design has ~40 features and 60+ thresholds against 17 captured sessions, so the
defaults are a starting point, not a calibration. zt-tiger-v2's ledger found most tested families
negative on the option path after costs (only the expiry gamma pair with a +30% target was
positive, 4 of 4 expiries). Scaling 25/40/35 needs at least four lots. Some assumed data is not
in the capture (per-stock CAS, VIX, BANKNIFTY).

## 10. Phase 1 status (2026-09-25)

Built (modules `features`, `execution-sim`, `instrument-master`; CLI `features`, `simulate`,
`instruments`):

- **Feature engine**: one snapshot per minute per underlying, 129 columns, strictly point in time
  (a snapshot at T never sees an event received at or after T; a test pins this).
  - Structure: day open, ORH/ORL (15 min), previous close, PDH/PDL (continuous trading only),
    session mean, VWAP proxy, EMA9/20 and slope on 3-minute bars, ATR 1m/3m, confirmed swings,
    higher lows / lower highs, level ladder with nearest level above/below in ATR, level
    acceptance (closes beyond, retest held), breakout-bar shape, spot change 30s/1m/3m.
  - Futures: momentum 30s/1m/3m, ATR-normalised momentum, acceleration, basis and its change,
    OI change 3m and day, price/OI state, time-of-day RVOL (3-minute slot vs median of up to
    20 prior sessions) and its slope.
  - Options (nearest expiry): weighted near-ATM OI change 1/3/5/10 min, OI flow
    (accelerating/fading build or unwind), call barrier and put support scores, wall weakening,
    straddle and its change, ATM IV and change, skew, Greeks (feed, or own Black–Scholes),
    premium response ratio, spreads.
  - Breadth: weighted momentum breadth and day breadth (−100..+100), coverage, top-3 concentration.
  - Regime: DTE (trading and calendar days), minutes to expiry, expected daily and remaining move.
  - CAS: feed phase, indicative index, its gap to the last continuous value and to futures.
  - Checked against the 25 Sep 14:52 screenshot from the design conversation: ORH, ORL, EMA20,
    session mean, previous close, PDL and open agree within about a point.
- **Cost model**: dated rate sets (`config/costs/india-index-options-costs.v1.yaml`), same rates
  as zt-tiger-v2.
- **Fill simulator**: latency, first quote after it, walks five-level depth, adverse slippage;
  base (250 ms, 0 bp) and stressed (1 s, 25 bp) models; stop/target/time exits on the bid.
- **Instrument master**: Upstox contract file → `ref.instrument` snapshots; expiries, lot size,
  freeze quantity (max lots per order), tick size, strike step.

Known limitations (items resolved on 25 Sep in features v2 / exchange v2 are marked):

1. *Resolved (features v2):* SENSEX futures trade ~20 contracts a minute with 45% empty minutes,
   so its RVOL uses a 10-minute slot and a minimum historical slot volume. RVOL also runs about 2×
   all through a futures expiry week (rollover); `futures.daysToExpiry` and a session-relative
   `futures.rvolSession` are now given alongside so strategies can tell the two apart.
2. *Resolved (features v2):* the expected move is now taken from the ATM straddle's time value
   (× √(π/2)), spread over the trading minutes to expiry. v1 applied a calendar-time IV per trading
   minute and overstated the move (2.4× on SENSEX expiry day).
3. Spot VWAP is a proxy (futures session VWAP minus current basis); the index has no volume.
4. OI is in raw quantity (not lakhs, not lots).
5. *Resolved:* `bin/autotrade features --save` writes snapshots to `feat.snapshot` under a
   `research.run` recording code version and config hashes.
6. *Resolved (exchange v2):* 2026 NSE/BSE holidays loaded, cross-checked against the capture
   (14 Sep) and the contract file (19 Oct NIFTY expiry before the 20 Oct holiday).
7. Contract files are loaded by hand (`bin/autotrade instruments --file ...`); a daily download job
   comes with the Upstox adapter in Phase 3. Loaded 25 Sep: NSE (NIFTY, BANKNIFTY) and BSE (SENSEX).

## 11. Phase 2 status (2026-09-25)

Built (modules `strategy-api`, `strategy-ecr`, `research`; CLI `lifecycle`):

- **Strategy SPI**: strategies see only a feature snapshot and their own position and return
  stages, scores, named conditions and order intents (never broker orders).
- **early-confirm-runner** (`config/strategy/early-confirm-runner.v2.yaml`): WATCH → ARMED →
  EARLY_ENTRY → CONFIRMED → RUNNER → EXITED on the opening range (CE at ORH, PE at ORL), the
  design's early-entry AND list, confirmation (3-minute close through the level, TOD RVOL, breakout
  bar, regime-weighted confirm score vs the time-window minimum), runner score, 30/40/30 tranches
  of 4 lots, exits (resting 25% premium stop, probe timeout/failure, invalidation, EMA9 trail,
  futures reversal, expiry stall, flat by 15:15). Regime from DTE (NORMAL / NEAR / EXPIRY) selects
  the weights. The four market states (direction, participation, structure, continuation) are
  reported every minute.
- **Replay harness**: features → strategy → multi-tranche option positions on the replayed quotes,
  one lane per fill model (base, stressed). Frames and episodes are stored separately
  (`research.lifecycle_frame`, `research.episode`) with a markdown report per run.
- **Held-out discipline**: held-out sessions need `--held-out --save` and are consumed per
  strategy family (`research.held_out_use`); the experiment ledger is `docs/EXPERIMENT-LEDGER.md`.
