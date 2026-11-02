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

## The Accounts page (UI)

A branch-built UI runs next to the main one, at **http://127.0.0.1:8097/accounts**. It is trading-core in serve mode
(display only: no session, alerts or cues), with no tokens and no zt mount, and it forwards `/api/execution/**` to the
worker. The main UI on 8095 (the GCP code) is unchanged.

The page shows:

- the worker's heartbeat;
- each follower account: broker, mode, session, allocation, route, last turn and result;
- Kill, "Logged in" (marks the session ACTIVE) and Flatten (type FLATTEN) per account;
- kill switches: list, release, engage by account, strategy, contract, or every LIVE account (admin);
- Flatten all (type FLATTEN ALL);
- the audit log, filterable by account.

```bash
AUTOTRADE_CODE_VERSION=<tag> docker compose -p autotrade --profile preview up -d --no-deps ui-preview   # start
docker stop autotrade-ui-preview                                                                       # stop
```

The page acts as `autotrade.ui.execution-user` (default `local-owner`). This is not a login: both ports are loopback
only until Keycloak.

## End-of-day square-off and contract checks (code review, 24 Oct 2026)

- **15:20 IST, weekdays:** the worker flattens any follower position still open (`autotrade.execution.square-off-cron`).
  Every live-set strategy is flat by 15:15, so this only acts when the signal source stopped sending (its failure also
  raises a CRITICAL Telegram alert from trading-core). No kill switch is engaged; the audit log shows `EOD_SQUARE_OFF`.
- **An EXIT for another contract** than the one the follower holds (a restarted signal source on a new strike) is
  skipped as `CONTRACT_MISMATCH`; nothing is sold. The held position keeps its broker stop until the square-off or a
  flatten.
- **Daily Kite login:** `/api/execution/accounts/<id>/kite/login` (the 08:30 Telegram link). The account must have its
  Zerodha client id pinned (`bin/autotrade kite-profile ... --account <name> --client-id <id>`); a login from another
  client id is refused. Before the login (connection TOKEN_EXPIRED) entries are refused with `BROKER_SESSION`.
- **Cross-site guard:** state-changing `/api` calls from another site are refused (`SameOriginFilter`); the UI and
  scripts on the host are unaffected.

## Managing accounts (Accounts page, 24 Oct 2026)

- **Add account:** name, broker (Zerodha in DRY_RUN, or Paper), Zerodha client id and Kite app, allocation, fixed or
  compounding equity, a daily loss limit in rupees (blank = the risk file's %), strategies, enabled. New accounts use
  follower-risk v3; no account is ever created LIVE.
- **Manage** (per account): change the allocation, equity mode, daily loss limit, mode (PAPER / DRY_RUN), client id,
  enabled and strategies; the account's Kite session and a "Log in to Kite" button; positions of any day; daily P&L for
  30 days. Changes apply from the account's next turn (about a second) and are audited (`ACCOUNT_CREATED`,
  `ACCOUNT_SETTINGS` with the values before).
- Only an OWNER or ADMIN of the tenant may create or change accounts; a TRADER may kill and flatten; a VIEWER only reads.
- The daily loss limit in rupees is `acct.broker_account.daily_loss_limit` (V25); when set it replaces the risk file's
  `daily_loss_pct` for that account.


## Login board and off-site watchdog (multi-broker plan K1, 1 Nov 2026)

**Login board.** Every enabled DRY_RUN / LIVE broker account, whether today's broker session is ACTIVE, until when, and
how many strategies it follows: `GET /api/execution/login-board` (through the UI: your tenants only; on the host
without the user header: all). At **09:10 IST on trading days** the board goes to Telegram after the 08:30 login
links: "N of M broker accounts ready", then **OUT FOR TODAY** for every account without a session (its entries are
refused `BROKER_SESSION` until a login). The 08:30 links and the 09:10 board skip the exchange file's holidays.

**Off-site watchdog.** `bin/offsite-watchdog` runs on the Mac (outside GCP) every minute from 08:55 to 15:50 IST on
trading days. One IAP SSH call reads the worker's status and login board, trading-core's health, whether the live PAPER
session runs, and the open follower positions, on the VM's loopback only. Telegram:
- **WATCHDOG ALERT**: the VM cannot be reached (stopped, GCP billing or network, IAP), the worker is down or stuck, an
  account is in error, trading-core is unhealthy, or no live session runs in market hours; with the open follower
  positions (LIVE ones are protected only by their broker stops while it lasts);
- **WATCHDOG STILL** every 15 minutes while it lasts; **WATCHDOG CLEARED** when it ends;
- **Watchdog running** once a day at the first healthy check (no such line by about 09:00 = the watchdog itself is not
  running: the Mac asleep, launchd, gcloud).

Install (copies the script to `~/.autotrade-watchdog`, writes the launchd agent, starts it):

```bash
bin/install-offsite-watchdog --account <gcloud account for IAP SSH> --telegram-file <path to bot.env>
```

Check by hand: `bin/offsite-watchdog --force --dry-run` (prints instead of sending). Remove:
`bin/install-offsite-watchdog --uninstall`. launchd does not run while the Mac sleeps.

**When it alerts with LIVE positions open:** open the broker's own app and look at the positions and the resting stops;
if the host stays unreachable, flatten there (section "Flatten" above lists what a flatten does; by hand in the broker
app it is: sell each open position, after cancelling its stop order).

Days you stop the VMs on purpose: `bin/offsite-watchdog --state-dir ~/.autotrade-watchdog --pause-until YYYY-MM-DD`
(no checks through that day), `--resume` to end it early. An account without today's broker login is reported once
(you may have chosen to sit the day out), not every 15 minutes.

## Kite LIVE pilot (user, 2 Nov 2026; first LIVE day Wed 4 Nov)

What a LIVE account is: a Zerodha follower whose orders go to the real Kite (product MIS) from GCP's static IP
34.14.137.170 (registered by the user in the Kite developer console, 2 Nov). Nothing else is LIVE.

**The pilot's limits, three layers:**
- the account: `size_step_pct` 0 (one lot an entry, no adds), its own daily loss limit (ZERODHA-1: Rs 20,000);
- the risk file `follower-risk.v7`: 2 contracts at once, 2 lots in an underlying, and `live:` (1 lot a buy order,
  2 contracts, strategies early-confirm-runner, ma-cross, oi-burst-cross); an intent of any other strategy is refused
  `LIVE_STRATEGY`;
- `LiveGuard`, in front of the Kite connection: no buy over 1 lot, no buy in a third contract, never a sell beyond what
  the day's order book says is held (no short option). There is no market order type at all.

**Switching (the account's owner only; Claude never switches an account LIVE):**
1. Before: the account is DRY_RUN on follower-risk v7 with size step 0, today's Kite login is done, and it holds no
   position.
2. Accounts page → the account → LIVE trading → type the account's name → Switch to LIVE. The server checks all of the
   above again and audits `LIVE_ON`. The worker rebuilds the route on its next turn (log: "LIVE orders go to Kite").
3. Only decisions taken after the switch are sent; intents made while DRY_RUN are never sent LIVE.
4. Back to DRY_RUN: the same panel, only with no position open (`LIVE_OFF`). To stop new entries with positions open,
   engage the account's kill switch (or `GLOBAL_LIVE` for every LIVE account); exits always run.

**If something goes wrong:** flatten from the Kite app (positions → exit) is always available and is the fallback for
everything. The resting SL at Kite protects each position if the worker or GCP is down. A wrong-IP or session error
refuses entries (`BROKER_SESSION`); log in again from the Accounts page.
