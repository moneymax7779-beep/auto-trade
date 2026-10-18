# Runbook: live execution workers (app-execution)

For the plan behind it see `docs/LIVE-TRADING-PLAN.md`, sections 3–4 and 13. This build trades **PAPER and DRY_RUN
only**: a ZERODHA DRY_RUN account's orders go to a local Kite simulator inside the container, and LIVE is refused. The
API binds 127.0.0.1 only. Callers name themselves with the `X-Autotrade-User` header (the Keycloak subject to be); it
is **not** authentication until Keycloak is in front (L3).

## Start, stop, status

```bash
# from the repository root, on the branch's image; * = every enabled copy group of every tenant
AUTOTRADE_CODE_VERSION=<tag> AUTOTRADE_EXECUTION_COPY_GROUP='*' \
  docker compose -p autotrade --profile execution up -d --no-deps --no-build --force-recreate execution
docker stop autotrade-execution                              # the off switch: nothing trades; leases are released
curl -s http://127.0.0.1:8096/api/execution/status          # worker heartbeat (lastTick, stale) and every account's turn
bin/execution-watchdog                                       # exit 0 = alive and ticking; 1 = DOWN / STALE / an account in error
```

An account's `route` says how it trades: "simulated fills", "Kite simulator http://127.0.0.1:port", or why it is
refused ("LIVE refused ...", "no order adapter for UPSTOX yet").

## Kill switches (entries and adds stop; exits never)

```bash
H='-H X-Autotrade-User:<subject> -H Content-Type:application/json'
curl -s $H http://127.0.0.1:8096/api/execution/kill-switches                                    # engaged ones
curl -s $H -X POST -d '{"scope":"ACCOUNT","accountId":94,"reason":"..."}' http://127.0.0.1:8096/api/execution/kill-switches
curl -s $H -X POST http://127.0.0.1:8096/api/execution/kill-switches/<id>/release
```

The scopes are:

- **ACCOUNT**: one account.
- **STRATEGY**: a strategy on all of your tenant's accounts.
- **INSTRUMENT**: one contract on your tenant's accounts.
- **GLOBAL_LIVE**: every LIVE account; ADMIN only.

The system engages an ACCOUNT switch itself on any of these:

- a refused broker stop, after which the position is exited at once;
- an exit that cannot fill: its broker stop is re-armed for what is left;
- an order whose outcome stays unknown;
- a reconciliation difference;
- a flatten.

Every engage and release is in the audit log (`GET /api/execution/audit`).

## Flatten

```bash
curl -s $H -X POST -d '{"confirm":"FLATTEN","reason":"..."}' http://127.0.0.1:8096/api/execution/accounts/<id>/flatten
curl -s $H -X POST -d '{"confirm":"FLATTEN ALL","reason":"..."}' http://127.0.0.1:8096/api/execution/flatten-all
```

A flatten engages the account's kill switch, then, for each open position:

1. takes its broker stop off;
2. books what the stop had already sold;
3. sells the rest.

What does not sell keeps a broker stop and is listed in the reply. Flatten it in the broker's own app if it stays open.

## Broker session lost (token expired, IP not allowed)

The account goes `TOKEN_EXPIRED`. Its entries are refused (`BROKER_SESSION`) and its exits wait. An entry that already
had an order at the broker also waits; it resumes by tag after the login. Open positions keep their broker stops, which
are verified again after the login.

After the day's login, mark the session (the Kite request-token exchange itself is L3 and not in this build):

```bash
curl -s $H -X POST -d '{"status":"ACTIVE"}' http://127.0.0.1:8096/api/execution/accounts/<id>/session
bin/autotrade account-session --label <name> --status ACTIVE      # the same from the CLI
```

## Reconciliation difference

At every lease takeover and every 30 s, the broker's net quantity per contract is compared with our open positions. On
any difference, a manual trade included:

- the account's entries stop (kill switch), and the audit log has both sides;
- exits for our positions continue;
- nothing is flattened automatically.

To resolve it, decide in the broker's app (close or keep the manual position), then release the switch. Before the
next live start, the end of day (15:45 IST) writes `acct.ledger_day` with the difference against the broker's trades.

## Worker crash or redeploy mid-position

This needs nothing. The broker stops protect the positions. On start, the worker:

1. takes the leases again (epoch + 1);
2. resumes an order in flight by its tag;
3. verifies every open position's stop, re-arming a missing one and closing a stale row;
4. reconciles.

A DRY_RUN simulator is new after a restart and is given the account's open positions. This was drilled on 19 Oct (plan
section 13, "Chaos drill").

## Drills (DRY_RUN only)

- **A replayed day through the worker:** set `AUTOTRADE_EXECUTION_DAY=<date>` and a drill copy group, and run
  `.local/l2/chaos/replay.sh <date> <equity>` (trading-core replay with the signal source teed into CHAOS).
- **Faults:** the simulator's port is in the worker log ("DRY_RUN orders go to a local Kite simulator at ..."). Inside
  the container:

  ```bash
  docker exec autotrade-execution wget -qO- "http://127.0.0.1:<port>/sim/fault?op=place&fault=TIMEOUT_PROCESSED"
  ```

  The endpoints are `/sim/expire-token`, `/sim/login`, `/sim/partial?fraction=0.5`, `/sim/stop-fills-on-cancel?on=true`
  and `/sim/state`. The faults are `NONE`, `HTTP_429`, `HTTP_500`, `HTTP_500_PROCESSED`, `TIMEOUT`,
  `TIMEOUT_PROCESSED`, `TOKEN_EXPIRED`, `INPUT_ERROR` and `REJECT_FUNDS`.
- **Afterwards:** recreate the worker without `AUTOTRADE_EXECUTION_DAY`.
