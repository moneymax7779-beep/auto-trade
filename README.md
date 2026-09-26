# auto-trade

Autonomous index-options trading platform (NIFTY, SENSEX; BANKNIFTY and stocks later).
Multi-user, multi-broker, Upstox first. PAPER only until the gates in
[docs/IMPLEMENTATION-PLAN.md](docs/IMPLEMENTATION-PLAN.md) are passed.

## Layout

| Module | What it holds |
| --- | --- |
| `platform-core` | Domain types: `Underlying`, market events (`IndexTick`, `FutureTick`, `OptionTick`, `ConstituentTick`, `SessionPhaseEvent`), `MarketTime` |
| `strategy-config` | Versioned threshold files (`config/strategy/*.yaml`) with a canonical content hash and sanity checks |
| `marketdata-store` | Flyway schema for the own database, COPY encoder/loader, load manifests, ordered replay (`ReplayReader`, `EventStreams`, `ReorderingCursor`) |
| `marketdata-source-zt` | Read-only access to zt-tiger-v2 captures: payload parser and `ZtSessionSource` (direct replay) |
| `features` | Point-in-time feature engine: structure, futures, options chain, breadth, regime, CAS |
| `execution-sim` | Dated cost model and option-path fill simulator (base and stressed) |
| `instrument-master` | Upstox contract master → `ref.instrument`: expiries, lots, freeze quantity, ticks |
| `strategy-api` | Strategy SPI: stages, scores, conditions, order intents |
| `strategy-ecr` | The early-confirm-runner strategy |
| `research` | Lifecycle replay (features → strategy → simulated fills), episodes, reports, held-out guard |
| `broker-api`, `broker-paper` | Broker SPI; paper broker filling on live/replayed quotes |
| `risk`, `oms` | Kill switches and pre-trade limits; order management with broker-side stop and reconciliation |
| `marketdata-live` | Live feed tailing zt-tiger-v2 (read-only); replay-as-live |
| `broker-upstox` | Upstox browser login and token store; Market Data Feed V3 (SDK 1.29: depth, Greeks, VIX, per-stock CAS). No orders |
| `app-trading-core` | PAPER trading service (`bin/trading-core`), operator API on 127.0.0.1:8095 |
| `ui/` | React operator UI (served by trading-core on 127.0.0.1:8095) |
| `autotrade-tools` | Operator CLI: `sessions`, `zt-sessions`, `replay`, `features`, `simulate`, `instruments`, `clone`, `verify`, `manifests`, `config-hash` |

## Run locally

```bash
cp .env.example .env            # optional; the zt-tiger-v2 password is read from its runtime.env
docker compose up -d            # autotrade-postgres (TimescaleDB) on 127.0.0.1:5500
mvn -q install                  # builds and runs all tests (integration tests need Docker)
bin/autotrade sessions          # applies migrations, lists declared sessions and load state
```

## Replaying sessions (reads zt-tiger-v2 directly)

For testing and implementation, market data is read straight from zt-tiger-v2's database over a
server-enforced read-only connection (`default_transaction_read_only=on`). Its password is read
from `../zt-tiger-v2/.local/v2/runtime.env` (`V2_DB_PASSWORD`); `ZT_SOURCE_PASSWORD_FILE` or
`ZT_SOURCE_PASSWORD` override that. zt-tiger-v2 keeps its newest 10 sessions in the database.

```bash
bin/autotrade zt-sessions                                    # what zt-tiger-v2 currently holds
bin/autotrade replay --session 2026-09-24                    # stream NIFTY+SENSEX in replay order
bin/autotrade replay --session 2026-09-24 --underlying NIFTY --verify-hashes
```

Both commands refuse to run 09:00–15:50 IST on weekdays (live capture); `--force` overrides.

## Cloning sessions into the own database (for the later dedicated market-data database)

```bash
bin/autotrade clone --session 2026-09-02                     # NIFTY and SENSEX, all datasets
bin/autotrade clone --session 2026-09-22 --underlying NIFTY --what ticks,cas
bin/autotrade clone --all                                    # every session in research.session_split
bin/autotrade verify --session 2026-09-02 --source           # re-digest stored rows and the source
bin/autotrade replay --session 2026-09-02 --from own         # replay a cloned session
bin/autotrade manifests
```

- Refuses to run 09:00–15:50 IST on weekdays (live capture is running); `--force` overrides.
- Every payload is checked against its SHA-256; counts and a sequence digest are compared between
  source and stored rows before the load commits. A reload supersedes the previous one atomically.
- Chunks of a cloned session are compressed right after loading.

## Features, simulation, instruments

```bash
bin/autotrade features --session 2026-09-25 --at 10:00,14:52      # CSV per index in .local/features
bin/autotrade simulate --session 2026-09-25 --underlying NIFTY --strike 23100 --type CE --at 14:52 \
    --stop-pct 15 --target-pct 30 --exit-by 15:20                   # base and stressed fills, after costs
bin/autotrade instruments --file .local/instruments/NSE-2026-09-25.json.gz --date 2026-09-25
bin/autotrade features --session 2026-09-25 --save                 # also into feat.snapshot
bin/autotrade lifecycle --save                                     # sessions from the strategy's evaluation window
bin/autotrade lifecycle --session 2026-09-28 --save                # one session
# sessions marked HELD_OUT additionally need --held-out (consumed once per strategy family)
```

Register a hypothesis in `docs/EXPERIMENT-LEDGER.md` before running it.

After changing any module, rebuild the CLI jar: `mvn -q -DskipTests -pl autotrade-tools -am clean install`
(`bin/autotrade` warns when it is stale).

## PAPER trading (Phase 3)

```bash
bin/trading-core --mode=replay --session=2026-09-25     # a recorded day through the live path, then exit
bin/trading-core --mode=live                            # today: tails zt-tiger-v2's capture until 15:45
curl -s http://127.0.0.1:8095/api/status                 # positions, P&L, stages, feed lag, kill switches
curl -s -X POST 'http://127.0.0.1:8095/api/kill?scope=GLOBAL&reason=manual'   # block new entries
curl -s -X POST http://127.0.0.1:8095/api/exit-all       # close everything
curl -s -X POST http://127.0.0.1:8095/api/stop           # close everything and end the session
```

Everything is PAPER: no code path sends an order to a real broker. Records are in `trade.*`.

### Upstox feed (instead of tailing zt-tiger-v2)

1. Put `UPSTOX_API_KEY` and `UPSTOX_API_SECRET` of the auto-trade Upstox app in `.env`; the app's
   redirect URL must be `http://127.0.0.1:5055/upstox/callback`.
2. Each morning: `bin/autotrade upstox-login`, open the printed link and sign in to Upstox yourself.
   The day's token goes to `.local/upstox/token.json` (owner-only) and expires at 03:30 IST.
3. `bin/autotrade upstox-status` checks it; then
   `bin/trading-core --mode=live --autotrade.trading.feed=upstox`.

Upstox allows two feed connections per user; auto-trade uses one, and zt-tiger-v2 runs on the same user.

## Operator UI (Phase 4)

```bash
cd ui && npm install && npm run build && cd ..   # once, and after UI changes
bin/trading-core --mode=serve                     # UI + history only; or run --mode=live / --mode=replay
open http://127.0.0.1:8095
```

Pages: **Live** (stages, scores, the four market states, positions, P&L, feed lag, kill switch /
exit all / stop with a confirm click), **Sessions** (minute timeline with entries and exits, stage
changes, orders, positions), **Research** (lifecycle runs, episodes, frame charts), **Replay**
(every saved feature at any minute), **Config** (versioned files and hashes). For UI development,
`cd ui && npm run dev` serves on 127.0.0.1:5173 and proxies `/api` to trading-core.

## Config files

```bash
bin/autotrade config-hash config/*/*.yaml
```

| File | Holds |
| --- | --- |
| `config/strategy/early-confirm-runner.v3.yaml` | ChatGPT thresholds (untuned) plus lifecycle/exit placeholders; evaluated from 21 Sep 2026 onward (v2: same thresholds; v1: thresholds only) |
| `config/features/features.v2.yaml` | Feature definitions (v2: SENSEX RVOL slot, straddle expected move) |
| `config/exchange/nse-bse-sessions.v2.yaml` | Session and CAS timings, 2026 holidays |
| `config/costs/india-index-options-costs.v1.yaml` | Dated cost rates and fill models |
| `config/risk/paper-risk.v2.yaml` | PAPER risk limits and order-handling parameters |

Every file carries a content hash that is stamped on outputs. Change a value by adding a new
version file; never edit a version that a run has used.
