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
| `autotrade-tools` | Operator CLI: `sessions`, `clone`, `manifests`, `verify`, `replay`, `config-hash` |

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

## Threshold files

```bash
bin/autotrade config-hash config/strategy/early-confirm-runner.v1.yaml
```

`early-confirm-runner.v1.yaml` holds the ChatGPT-suggested starting values, `status: UNCALIBRATED`.
Change a value by adding a new version file; never edit a version that a run has used.
