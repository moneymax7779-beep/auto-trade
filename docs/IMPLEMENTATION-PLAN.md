# auto-trade — implementation plan

Status: agreed 2026-09-25. Phase 0 done (736a423), Phase 1 done (8067b4c), Phase 2 done (616a018, d340a30), Phase 3 in progress, Phase 4 in progress; see sections 10–13.

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

**Tuning result and decision (2026-09-25).** A-001 (ecr-v2) closed at G1: 6 episodes on the
tuning sessions, stressed average −0.05 R; the early probe never fired (breadth ≥ +50 rarely holds)
and no PE trade occurred. By user decision the ChatGPT thresholds are kept without tuning; ecr-v3
has identical thresholds and adds `evaluation.sessions_from: 2026-09-21`, so strategies are now
evaluated on this week's market data onward (new sessions as they are captured), not older data.

## 12. Phase 3 status (2026-09-25)

Decisions (user, 2026-09-25): the first live PAPER feed tails zt-tiger-v2's capture (read-only, no
new broker connection); auto-trade's own Upstox connection follows, on a separate Upstox developer
app under the same Upstox user (it shares that user's two WebSocket connections with zt-tiger-v2).

Built (modules `broker-api`, `broker-paper`, `risk`, `oms`, `marketdata-live`, `app-trading-core`):

- **Broker SPI** and a **paper broker**: latency on orders, cancels and modifies; limits fill on
  visible depth at the limit or better (partial fills); stop-limits trigger on the last traded
  price; adverse slippage never crosses the limit; idempotent client order ids.
- **Risk** (`config/risk/paper-risk.v2.yaml`): global/account/strategy kill switches, daily loss
  limit (engages the account switch), max positions and lots, order rate, entry cutoff 14:45,
  square-off 15:20, stale-feed and spread guards. Exits are never blocked.
- **OMS**: marketable limits (ask + 4 ticks), freeze-quantity slicing, a resting stop-limit that
  always covers the held quantity (resized in place after adds), exits that cancel the stop and
  working entries and sell only after the cancels are confirmed (no oversell), exit re-pricing
  every 2 s, entry timeout, reconciliation with the broker (a mismatch engages the global switch).
  Client order ids carry the trading-session id and a role letter (B, S, X).
- **Live feed**: `ZtTailFeed` follows zt-tiger-v2's capture by row id (catch-up from the open, then
  new rows, with a trailing recheck window for late commits); `ReplayAsLiveFeed` runs a recorded
  session through the same path.
- **trading-core** (`bin/trading-core`): one session per day; persists sessions, orders, order
  events, positions, per-minute decisions, rejections and kill-switch events (`trade.*`,
  `ops.kill_switch_event`); operator API on 127.0.0.1:8095 (`/api/status`, `/api/orders`,
  `/api/kill`, `/api/kill/release`, `/api/exit-all`, `/api/stop`). Live, it never opens or adds
  from a snapshot older than 60 s.
- **Checked**: replay-as-live of 24 Sep matches the research replay to the rupee (−₹1,356);
  25 Sep −₹1,623 vs −₹1,519 (the extra cancel-then-sell step on exit).

Part 2 (2026-09-26): **Upstox adapter** (`broker-upstox`): browser OAuth with a local callback
(`bin/autotrade upstox-login`; the account holder signs in, the token is stored owner-only until
03:30 IST), Market Data Feed V3 on SDK 1.29 in full mode: index, India VIX, nearest future, all
NIFTY 50 / SENSEX 30 constituents (weights from zt-tiger-v2's last session) and a re-centring band
of ATM ± 12 nearest-expiry options; per-stock closing-auction data arrives as `AuctionTick`. Select
it with `--autotrade.trading.feed=upstox`. No order API is wired: PAPER only.

Still to do in Phase 3: capture the Upstox feed into the own `md.*` tables (so PAPER sessions on
the Upstox feed can be replayed without zt-tiger-v2); first connection test once the app's key is
in `.env`; measuring feed lag live; ten clean PAPER sessions (the exit criterion) from 28 Sep.

## 13. Phase 4 status (2026-09-26)

Built: `ui/` (React 19, TypeScript, Vite, Tailwind 4, TanStack Query, TradingView Lightweight
Charts), served by trading-core from `ui/dist` on 127.0.0.1:8095; `--mode=serve` runs the UI and a
read-only history API without a trading session (`/api/sessions/**`, `/api/runs/**`,
`/api/snapshots/**`, `/api/configs/**`).

- **Live**: session header (status, day P&L net of costs, feed, last event, lag, kill switches),
  per-index cards with the four market states and CE/PE stage plus early/confirm/runner scores,
  open and closed positions, recent refusals; kill switch, release, exit all and stop, each behind
  a second confirming click. Polls every 2 s.
- **Sessions**: list; per session a price timeline with buy/add/exit markers, confirm and runner
  scores through the day, stage changes, positions and every order's last state.
- **Research**: lifecycle runs; per run lane totals (base/stressed), episodes, and per-session
  minute frames with the four states.
- **Replay**: scrub a saved feature run minute by minute (price with ORH/ORL, VWAP, EMA20) and
  read every feature at that minute.
- **Config**: every versioned file with version, status and hash; view the file.

Verified in the browser against real data: session 5 (24 Sep, −₹1,356), run 4, feature runs 1–2,
and a replay in progress (kill switch engaged and released from the UI, both recorded in
`ops.kill_switch_event`).

Deviations from the stack table: no AG Grid, shadcn/ui or Zustand yet (plain Tailwind tables and
React Query state were enough); WebSocket push is replaced by 2-second polling on loopback.

Still to do: an audit view (kill-switch events, rejections across sessions); a config diff between
versions. Authentication: **deferred by decision (2026-09-26)** — no UI login until Phase 5; the
UI and operator API stay bound to 127.0.0.1. auto-trade does not use zt-tiger-v2's users or broker
accounts (only its market data); the Upstox feed uses auto-trade's own Upstox app. Phase 5 is parked.

**Automatic daily sessions (2026-09-26).** Scheduling is part of the application, not the OS:
trading-core `--mode=auto` stays up, serves the UI, starts the live PAPER session at 09:00 IST on
trading days (holiday file), stops it at 15:45, retries a failed start up to three times a day, and
restarts at once after a service restart in market hours. It runs as the `trading-core` service in
`docker-compose.yml` (`restart: unless-stopped`, built by `bin/deploy`), reaching zt-tiger-v2 via
`host.docker.internal:5490` (read-only, password file mounted read-only). A launchd job was tried
and removed: macOS privacy controls block background jobs from the Desktop folder, and it tied the
app to one OS. Known gap: a mid-day restart loses the paper broker's in-memory positions (the new
session starts flat); position recovery from `trade.*` comes with the Upstox order path.

## 14. Strategy and data completion (2026-09-26)

The review found design elements that were not built (order-book imbalance, IV/VIX percentile,
level acceptance extras, volatility regime, the CAS mode). They are now implemented without
changing any ChatGPT value; `docs/DESIGN-COVERAGE.md` maps every design element to its code and
says which data each needs.

- **Features v3** (`features.v3.yaml`; every v2 definition unchanged, v2 snapshots reproduce):
  level acceptance (minutes beyond, travel, time-of-day volume after the break), the level ladder,
  breakout-bar volume, room to the next level vs the expected move; order-book imbalance with
  10/20 s persistence (ATM options from total quantities; futures from Upstox book totals);
  premium momentum, new highs, option volume expansion, straddle compression ending, 1 ITM/OTM IV;
  IV percentile (same minute of earlier sessions from zt `option_tick_snapshots`, expiry days apart),
  realised vol, RV/IV, ATR and realised-vol percentiles; India VIX level, 5/15-minute and day change,
  60/252-day percentiles; weekend/holiday gap; CAS reference, indicative velocity/acceleration,
  futures-vs-CAS basis and follow ratio, CAS ORH/ORL cross, and per-stock CAS pressure, imbalance
  and its velocity, CAS breadth, top-3 concentration and auction turnover.
- **Strategy ecr-v4**: volatility regime engine (VIX leads NIFTY, IV leads SENSEX/BANKNIFTY,
  realised-vol escalation) that changes size, confirmation threshold, premium stop and early probes;
  imbalance as ±3 confirm points; breakout volume; runner gates (premium response ≥ 0.6, room);
  expiry runner gates and exits; futures state score +2…−2; CAS mode (CAS_RUNNER from 15:15, CAS
  entries by band in 15:20–15:30 with per-stock data and futures agreement, CAS_EXPIRY_MODE, exit by
  15:35). **Risk v3**: square-off 15:36, CAS entry window.
- **Data**: migration V10 (`md.future_tick` book totals, `ref.index_candle`); `bin/autotrade
  vix-backfill` loads India VIX from Upstox's public candle API (272 daily bars, 44 sessions of
  one-minute bars on 2026-09-26); live sessions poll today's VIX minutes and save them; Upstox VIX
  ticks reach every engine. `OrderIntent` carries a premium stop; the OMS and the research simulator
  honour it per position. Live decisions now store the named conditions that held.
- **Research**: `lifecycle --descriptive` replays held-out sessions a family has already consumed,
  records the run as `LIFECYCLE_DESCRIPTIVE` and does not claim anything; fresh held-out sessions
  cannot be read that way.

Verified: all tests pass (new: features v3 engine and units, strategy v4 regime/CAS/sizing, OMS
per-position stop, risk v3 window); the v3 strategy on features v2 reproduces run 4 exactly (6
episodes, base +₹1,145, stressed +₹791), so nothing old changed.

Limits: per-stock auction data and the futures book exist only on the live Upstox feed, so CAS
entries cannot be replayed from zt-tiger-v2; CAS liquidity percentiles need captured auction history.

**Placeholders calibrated, v5 live (2026-09-26).** On the user's instruction to choose the best
values, placeholders were set by rules registered before computing (`docs/CALIBRATION-v5.md`):
feature distributions of 11–18 Sep only, never trade results. Result: strategy ecr-v5 and features
v4 (travel 0.8 ATR, opposite wall 70, IV trend 0.0014, HIGH stop 40%, EXTREME stop 35%; CAS
index-level components only while the indicative index moves, since the recorded one is frozen
15:15–~15:29). trading-core runs ecr-v5 + features v4 + risk v3 from Monday 28 Sep (ledger A-004).

**Shared Upstox login (2026-09-26, user decision).** "Use the same Upstox and user data as
zt-tiger-v2 for now": live sessions read the Upstox token of zt-tiger-v2's primary Upstox account
(user BM6462) from its `trading_accounts`, read-only and in memory only, and use the Upstox feed
(one of the two connections Upstox allows per user; zt-tiger-v2 holds the other). Fallback to the
zt-tiger-v2 tail when there is no token or the feed fails or stays silent for 60 s. Contract files
are downloaded daily. Verified 2026-09-26: token accepted for BM6462; a 20-second feed check
subscribed 185 instruments and received index, VIX, futures, 80 constituents and 100 options.
auto-trade stores no zt-tiger-v2 user or credential and never re-authenticates with its app.
