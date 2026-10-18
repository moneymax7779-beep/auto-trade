# Live trading plan: multiple accounts and brokers, with fan-out

Proposal, 16 Oct 2026 (user: "now I want to do live trading with the same account for now ... multi user multi broker
support for my own multi brokers account ... give me the best implementation plan"). Nothing here runs against a live
account until the gates in section 9 pass and the user says so. It replaces phases 5–7 of `IMPLEMENTATION-PLAN.md`
for the owner's own accounts. Accounts belonging to other people stay out of scope (section 7).

**Revised 16 Oct ~01:40 (user: "go with option A, Zerodha first ... start on a new branch document the revised plan and
start working"):** option A; the first live broker is Zerodha (Kite Connect); work on branch `feature/live-execution`.
Section 11 records the decisions, section 12 the Zerodha specifics, and the L0 row in section 9 its revised scope.

## 0. The fact that decides the first step

Upstox rules since 1 April 2026 (from Upstox's community notice; confirm on the developer page "Algo Registration &
Static IP Requirement"):

- **One active API app per user.** Extra apps are deleted.
- **Order APIs only from the app's registered static IP.** Read APIs (market data, positions, funds) are exempt.
  Changing the IP is allowed about once a week and invalidates the token.
- **No plain market orders.** Market orders become market-protected orders.

The only app on the primary Upstox account is zt-tiger-v2's. auto-trade reads zt's token read-only and never logs in
with zt's app. So "live on the same Upstox account" can only mean one of two things:

| Option | What it means | Verdict |
|---|---|---|
| **A. Live on a separate account** (recommended) | The primary Upstox account keeps doing market data plus PAPER, unchanged. The first live follower account trades one of your other accounts: another broker with a retail options API, or an Upstox account in your own name with its own app and static IP. | Clean isolation. One token failure cannot take down both data and trading. It is also exactly the fan-out you described. |
| **B. Same Upstox account** | The single app must serve data and orders, and must be whitelisted for the static IP of the VM that places the orders. That means changing zt-tiger-v2's app setup (whose app, which IP), or auto-trade taking over the app with zt reading auto-trade's token. | It touches zt-tiger-v2, which needs your explicit decision. It couples data and orders on one token. A weekly IP change locks both out. |

The plan below works for both options. Only the first broker connection differs.

## 1. Target architecture

```
                        PRIMARY (unchanged, GCP zt-autotrade)
  Upstox feed (primary, read-only) ─► capture md.* ─► FeatureEngine ─┬─► PAPER-1 (as today, untouched)
                                                                     │
                                                                     ▼
                                     SIGNAL SOURCE per copy group: strategies (same versioned files) on a virtual,
                                     never-refused account → broker-neutral SignalDecision (ENTER / EXIT, strategy,
                                     contract, reason)
                                                                     │  fan-out (durable, in Postgres)
                                                                     ▼
                                     TradeBatch (one per decision) → TradeIntent (one per follower account)
                                                                     │
                     ┌───────────────────────────────────────────────┼──────────────────────────────┐
                     ▼                                               ▼                              ▼
          app-execution worker: account LIVE-A            account LIVE-B (broker 2)       PAPER follower(s)
          intent → account risk + capital → executable quote from its own broker → OMS → broker adapter →
          OrderExecution / Position (bound to that account) → reconciliation → account ledger
```

**Signal source and followers (aligned with zt-tiger-v2's blueprint).**
- **Signal source.** The strategies run once per copy group, on a virtual account that is never refused for capital
  and fills at the quote. Its decisions are broker-neutral `SignalDecision`s.
  - Because the virtual account always "holds" what it entered, every exit decision reaches every follower that
    holds the position. No follower is left with a position its strategy has forgotten.
  - (The first draft ran a full strategy copy per account. Then a capital refusal in one account would have changed
    that copy's state, and its later exits would no longer match the market's.)
- **Followers.** Each follower account turns intents into orders under its own rules:
  - its capital, risk limits, kill switches and broker session;
  - strategy priority within a batch (straddle first, odm last), applied by the account's capital check;
  - an ENTER intent for a position the account already holds is skipped;
  - an EXIT intent for a position it never got is skipped, with the reason recorded.
- **Account-local protection** comes from the execution runtime, not from the signal: the broker-side stop, the daily
  loss limit and reconciliation exits.
- **PAPER-1 stays exactly as it is**, with its own strategies and capital, for continuity of every ledger and test.
  A PAPER follower of the signal source runs beside it, and their difference is measured (gate L1).

**Separate process for execution.** One modular codebase, as zt-tiger-v2's blueprint recommends, but two
deployables. trading-core keeps the market data, PAPER-1 and the signal source. A new `app-execution` container runs
the intent workers for every follower account. The market-data capture and PAPER-1 cannot be slowed or crashed by a broker timeout, a stuck order or a
bad deploy of execution code. This is the isolation you asked for: "primary account for capturing market data and in
PAPER mode won't be disturbed."

**Durable pipeline.**
- **Intents.** Batches and intents are committed to Postgres before any order is sent (outbox).
- **Workers.** Each intent is claimed by one worker with a row lock. A stale claim is re-claimed only after
  reconciling with the broker by tag, and attempts are capped.
- **Concurrency.** Accounts are processed in parallel, one worker lane per account. zt-tiger-v2 loops over accounts
  one after another, so a slow broker delays the next account.
- **Idempotency keys:**
  - `batch = copyGroup:signalDecisionId:mode`;
  - `intent = brokerAccount:batch:side:contract`;
  - broker tag = intent id plus attempt number, cut to the broker's tag length.
- **Executable quote.** Before a live order, the worker reads the contract's bid and ask through the account's own
  broker (a read API). A missing bid or ask rejects that account's intent only. Bid and ask are never synthesised for
  live orders.
- **Divergence report.** Each follower trade is compared with the signal source's trade, and the PAPER follower is
  compared with PAPER-1.

**The market bus.**
- trading-core publishes, once:
  - each feature snapshot;
  - top-of-book plus 5-level depth for the subscribed option band;
  - session-phase events;
  - the day's contracts.
- Every message carries a sequence number and the receive time.
- When the signal source or an execution worker sees a gap, or no message for N seconds, it treats the feed as **stale**: entries are blocked and exits
  use the broker's REST quote (read APIs need no static IP and no second feed connection).
- Execution workers never open their own market-data WebSocket, so the primary account's feed connections are not touched.
- Valkey is already in compose. Kafka only if the load ever needs it.

**One writer per account.**
- An execution worker takes a Postgres advisory lock (a lease) for its account at start, and every order carries the lease's
  fencing number.
- A second process for the same account cannot trade.
- This extends today's "this process is the only live one for the account" check.

## 2. Accounts, capital and ledger (new Flyway migrations, never edit V1–V20)

| Table | Holds |
|---|---|
| `acct.tenant`, `acct.app_user`, `acct.tenant_membership` | zt-tiger-v2's identity model: one INDIVIDUAL tenant (you) first; users by Keycloak subject; roles OWNER / ADMIN / TRADER / VIEWER / AUDITOR |
| `acct.broker_credential_profile` | broker app credentials (api key/secret references in Secret Manager, redirect URI, registered static IP); may be shared by reference |
| `acct.broker_account` | **the execution boundary** (zt's `trading_accounts.id` = brokerAccountId): tenant, app user, broker type, external broker user id (display and reconciliation only, never authority), credential profile, `account_role` SIGNAL_SOURCE / FOLLOWER_TRADE / FOLLOWER_VIEW_ONLY / DISABLED, `connection_status`, trade mode PAPER / DRY_RUN / LIVE, `live_trading_enabled` (default false), **allocated capital**, equity mode FIXED / COMPOUNDING, risk file, size step (see 9) |
| `acct.broker_session` | per broker account: token reference, acquired / expires / last validated, last failure; never shared between accounts |
| `acct.copy_group`, `acct.strategy_subscription` | which signal source an account follows, and which versioned strategy files, size multiplier and mode it takes from it |
| `exec.trade_batch`, `exec.trade_intent`, `exec.order_execution` | the durable pipeline, with zt's unique keys (section 1) and states, plus UNKNOWN and RECONCILIATION_REQUIRED |
| `acct.capital_movement` | dated additions or withdrawals of allocated capital, by whom, why (audited) |
| `acct.ledger_day` | per account and day: opening equity, realised gross, brokerage, STT, exchange and GST charges, net, closing equity, broker-reported figures, difference |
| existing `trade.*` | **unchanged** (PAPER-1, shadows, replays keep writing there as today). Follower orders, fills and positions live in `exec.*`, each with its `broker_account_id`; exits route to the entry account (account-strict) |

**Capital you define, even when the broker account holds more.**
- **Allocated capital is the hard cap.**
  - The equity an account sizes from = allocation (FIXED), or allocation plus the account's own realised net (COMPOUNDING).
  - It never comes from the broker balance or from PAPER-1's equity.
- **Before every entry, both of these must hold:**
  - premium ≤ the account's free capital;
  - premium ≤ the broker's available funds minus a buffer (funds API).
  If either fails, the entry is refused, never shrunk silently, as risk v12 does today.
- **Daily loss limit, maximum premium per trade and maximum open premium** are percentages of the account's equity, from
  the account's own versioned risk file.
- **End of day:**
  - Pull the broker's trade book and charges.
  - Write `ledger_day` with the difference against our own fills.
  - A non-zero difference is an alert, and blocks the next day's live start until you acknowledge it.

P&L is then per user, per broker, per account and per strategy. Each account has its own ledger, so tax and broker
statements stay separate.

## 2a. Multiple users and multiple brokers

Users here means your own identities (you, plus later another login of yours or a family member's own account under
their own login). Running the system for other people is excluded until section 7 is resolved.

**Users**
- **Tenancy.** Every row in `acct.*` and `trade.*` belongs to one user. The API filters on the logged-in user's
  Keycloak subject. One user never sees or controls another user's accounts.
- **Roles:**
  - OWNER: everything for their own accounts;
  - VIEWER: read-only, e.g. a second device;
  - ADMIN: you, across users, for the global kill switch and deploys.
- **Per-user settings:**
  - strategy subscriptions (which versioned strategy files each account runs);
  - risk profile, allocation and ledger;
  - Telegram chat;
  - daily broker login link.
- **Onboarding a broker connection:** the user's own broker OAuth, then a token in Secret Manager under that
  connection, then a static-IP check, then the account starts in DRY_RUN.

**Brokers**
- **Instrument mapping.**
  - Signals and the contract master are keyed by exchange instrument (exchange, segment, underlying, expiry, strike,
    type).
  - Each broker adapter maps that to its own id (Upstox `instrument_key`, Kite `instrument_token` / tradingsymbol,
    Dhan `security_id`, Fyers symbol).
  - Each broker's instrument file is loaded daily, and the mapping is checked before the open. An unmapped contract
    blocks that account's entries in it.
- **Capabilities per adapter** (`BrokerCapabilities` already exists), extended with:
  - order types (stop-limit, market-protected), tag length, order-rate limit, modify support;
  - order-update channel (WebSocket, postback, polling);
  - funds/margin API;
  - login flow (daily OAuth, request token);
  - static-IP slots.

  The OMS adapts to these instead of special-casing brokers.
- **Charges per broker** (brokerage plan, exchange, STT, GST, stamp duty, dated) feed each account's ledger, so the
  net P&L matches that broker's contract note.
- **Market data stays single-sourced** from the primary (section 1). A second broker's feed can be added later as a
  standby data source behind the same bus, never as a second primary.

**Fan-out at scale**
- **Ordering.** One signal decision becomes one intent per follower. Accounts act in parallel, each in its own worker lane and broker connection,
  so a slow broker delays only its own account.
- **Shared liquidity.** Several of your accounts buying the same 0DTE strike at the same second compete with each other
  for one order book. A **liquidity allocator** in the fan-out splits each strike's usable depth (risk v12's
  depth cap) across accounts, pro rata to allocation, and staggers their slices.
- **Combined exposure.** A cross-account limit caps total open premium and total lots per strike across all your
  accounts, on top of each account's own limits.

## 3. Broker adapter (one per broker, behind the existing `BrokerGateway`)

The OMS already prices entries as marketable limits, slices to the freeze quantity from the contract master, chases
exits and reconciles. Each live adapter adds the following.

**Orders**
- **Limit or market-protected orders only.** Prices must stay inside the exchange's dynamic price band; a band
  rejection is re-priced inside the band.
- **Slicing by the OMS from the contract master,** not the broker's slicing flag. The OMS then knows every child order.
  Under the order-rate limit below.

**Order state**
- **`tag` = the OMS's deterministic client order id** (cut to the broker's tag length; `BrokerCapabilities.tagMaxLength`
  exists).
- **State from the broker's order stream** (Upstox: portfolio stream with order and position updates enabled), plus
  REST polling every few seconds as a fallback.
- **The state machine includes UNKNOWN.** A timed-out place call is never retried blindly. The order is looked up by tag
  first, then re-placed only if it does not exist.

**Limits and errors**
- **Order-rate limiter per account, kept below 10 orders a second.** That is SEBI's threshold for algo registration.
  Sized for slices plus stop updates.
- **Error taxonomy:** retryable (timeouts, 5xx, rate limit), terminal (funds, band, freeze, instrument), session (token
  expired or invalid, IP not whitelisted). A session error disables the account's entries and alerts at once.

**Broker-side stop at the catastrophic level**
- After each entry fill, a resting stop-limit sell is placed at the broker, at the premium stop (−25 %).
- If auto-trade or the VM dies, this order protects the position.
- The app still manages the trail, structure and time exits.
- **Exit sequencing:**
  1. cancel the broker stop;
  2. on confirmation, send the exit;
  3. if the cancel fails because the stop already filled, the position is closed, so record that and send no exit.

  This prevents a double sell, which would leave a short option position.

**Recovery on start:**
- read the broker's positions and open orders;
- adopt the ones whose tag is ours;
- rebuild `ManagedPosition`s and re-arm exits;
- anything untagged is not ours: alert and leave it alone.

## 4. Failure and fire-fighting matrix

| Scenario | Detection | Automatic action | Your action |
|---|---|---|---|
| Market bus stale or gap (primary down, network) | sequence gap / no message N s | block entries; exits on broker REST quotes; held positions keep the broker stop | none unless it lasts; Telegram alert |
| Primary trading-core crash | bus silent, health check | as above; PAPER-1 restarts on its own | check after restart |
| app-execution crash or redeploy mid-position | lease lost / process restart | broker stop protects; on start, recovery from broker (section 3), lease re-taken | none |
| VM down | external watchdog (Mac or a GCP uptime check) misses heartbeats | broker stops protect | runbook: flatten in the broker app; Telegram tells you which positions |
| Token expired or invalid, IP rejected | session-class error | account entries blocked, exits retried through the REST path; alert | re-login (daily login flow) |
| No login by 09:10 | connection status | account stays disabled for the day; PAPER-1 unaffected | log in or skip the day |
| Order timeout / unknown state | no acknowledgement in T | lookup by tag; no blind retry | — |
| Partial fill on entry | order stream | rest cancelled after the entry timeout; position sized to what filled; broker stop for the filled quantity | — |
| Exit not filling (thin 0DTE strike) | chase limit reached | widen through the ask in steps (exists), then market-protected; then alert and account kill | flatten manually if still open |
| Rejection: funds, band, freeze | terminal error | entry refused; band → re-priced once inside the band | check funds |
| Reconciliation mismatch | periodic broker positions vs OMS | account kill (entries); alert with both sides; exits for our tagged positions continue | decide; never auto-flatten untagged positions |
| Manual trade in the same account | untagged order or position | that instrument frozen for the account; alert | avoid manual trades in instruments the account holds |
| Runaway orders (bug) | more than X orders per minute per account or instrument | account kill + cancel all open orders of the account | investigate |
| Daily loss limit | ledger | account kill switch (entries), as today | the A-100 override stays a separate decision |
| Exchange halt / freeze / circuit | order errors, phase events | entries blocked | — |
| Expiry day | calendar | 0DTE positions flat by 15:15–15:20; no exercise reliance (STT on exercised ITM) | — |
| Two processes on one account | advisory lock | the second never trades | — |

**Kill switches:** GLOBAL_LIVE, ACCOUNT, STRATEGY and INSTRUMENT.
- **Flatten all live:** cancel every open order of every live account, then exit every tagged position with
  marketable-limit chasing.
- **Where:** the UI, with a confirmation, and an authenticated Telegram command.
- **Logging:** every manual action is in the audit log.

## 5. Not disturbing what runs today

**What must keep running, unchanged:**
- the GCP live PAPER session: capture, features, PAPER-1 and its sizes, ledger and kill switch;
- shadow sessions, replays and research numbers;
- the daily sync, the local Mac serve container;
- zt-tiger-v2.

**How each layer is kept apart:**

| Layer | How it stays undisturbed |
|---|---|
| **Code** | All live work on a branch (`feature/live-execution`), merged into main one gated phase at a time. New modules only: `acct`, `exec`, a broker adapter module and `app-execution`. trading-core gets one addition, the signal-source publisher, behind `autotrade.signal-source.enabled` (default **false**). With the flag off, trading-core does exactly what it does today. |
| **Proof, every merge** | The full build with every test, plus the 26 genuine tick days replayed with the flag off and on. PAPER-1's trades must be identical in both: the publisher only observes. This is the same identity check as A-113. |
| **Timing** | trading-core publishes signals with a non-blocking insert from its own small pool. If the insert is slow or fails, the signal is logged as lost; the live PAPER session never waits for or fails because of the live side. |
| **Database** | New schemas `acct` and `exec` only, through new Flyway migrations. Existing `md.*`, `trade.*` and `ref.*` tables are **not altered**: PAPER-1 is registered as an account row that points at its existing label, without touching its code path. `app-execution` connects as its own DB role: write on `acct`/`exec`, read-only on the rest, with its own small connection pool. |
| **Process and host** | `app-execution` runs on its **own small VM** with its own reserved static IP (needed for the broker anyway). CPU, memory, restarts and deploys of the live side cannot touch the zt-autotrade VM. It reaches Postgres over the private network. |
| **Market data** | No new feed connection. The Upstox primary token is used exactly as today, by trading-core only. Follower accounts check bid and ask through **their own** broker's read API (for example Zerodha), so the primary account's rate limits are not touched either. |
| **zt-tiger-v2** | Not touched: no writes, no config, no change to its app or token. Its code was only read, once, for this plan. |
| **Deploys** | trading-core keeps its after-hours deploy scripts and checks. `app-execution` deploys and rolls back separately, after hours until L5. |
| **Off switch** | Stop the `app-execution` VM or container and set the flag to false. The system is then exactly as it is today. Followers' open live positions keep their broker-side stops; the runbook says how to flatten them. |
| **Development** | L0–L2 run on the Mac first. Local replays with the signal source on write intents to the local DB, and DRY_RUN workers consume them. Nothing on GCP changes until L3. |

**Proof that nothing changed** comes before and after every phase that touches trading-core: the 26-day identity
replay, the full test suite, and the existing deploy script's checks (live list, risk, features file, Flyway version,
health).

## 6. Security

- **UI authentication.** Keycloak login in front of the UI and API before any live account exists. The UI already has kill
  and exit controls; with live money behind them they must not be reachable without login. (The earlier public quick
  tunnel must not come back.)
- **Tokens and secrets.**
  - Broker tokens live in GCP Secret Manager, one per broker connection.
  - Passwords and TOTP secrets are never stored (unchanged rule).
  - Login is the broker's daily OAuth, through a link sent to you on Telegram each morning.
- **Least privilege.** Read-only DB role for the UI's read paths. app-execution has write access only to its own
  accounts' rows.

## 7. Compliance (confirm with each broker; not legal advice)

- **Static IP.** Reserve a static external IP for the order-sending VM (Cloud NAT in asia-south1) and register it on
  the broker app. One IP per order-sending host; plan around the weekly change limit.
- **Order rate.** Stay below 10 orders a second per account, so no algo registration is needed for own-use API trading.
  The broker attaches the exchange algo id.
- **Own accounts only.** Running the system for other people's accounts makes you an algo provider (empanelment through
  a broker, possibly SEBI RA/IA). Not in this plan.

## 8. Testing before money

1. **Fault-injection broker.**
   - A `BrokerGateway` that can delay, time out, reject, partially fill, reorder updates, drop the stream, fill the
     broker stop during an exit, and return duplicate updates.
   - Every scenario in section 4 becomes an automated test against the real OMS.
2. **Contract tests** against recorded responses from each broker (Upstox V3 has a sandbox for order APIs).
3. **DRY_RUN accounts.**
   - The full live path runs: signal source, batch, intent, worker, risk, OMS and adapter, up to the HTTP call.
   - Orders are logged and simulated against real quotes.
   - At least 5 sessions where DRY_RUN decisions equal PAPER-1's decisions.
4. **Chaos day in the sandbox:** kill the process mid-position, cut the bus, expire the token, and check that recovery
   is clean.

## 9. Phases and gates

| Phase | Builds | Done when (gate) |
|---|---|---|
| **L0** (1–2 wk) | `acct.*` / `exec.*` schemas (Flyway V21) with tenancy, roles, broker accounts, sessions, copy groups, subscriptions, capital and ledger, batches, intents and executions; an `account` module with the capital rules (allocation, FIXED / COMPOUNDING equity, free capital, broker-funds check, daily loss) and ledger arithmetic; PAPER-1 registered as an account row by a tool command (its code path untouched) | migration tested on a fresh Postgres (Testcontainers); full build green; trading-core not changed; nothing deployed to GCP |
| **L1** (1–2 wk) | signal-source publisher in trading-core behind a flag (default off); signal source on a virtual account; batches and intents; `app-execution` workers; DRY_RUN and PAPER followers; lease; divergence report | 5 sessions where the PAPER follower matches PAPER-1 within the expected capital-refusal differences, and DRY_RUN intents equal the PAPER follower's; bus gap handling tested |
| **L2** (2 wk) | broker adapter #1 (section 3), fault-injection suite, recovery, broker-side stops, EOD broker reconciliation | every section-4 scenario green in tests; sandbox chaos day clean |
| **L3** (1 wk) | Keycloak, Secret Manager, static IP, daily login flow, flatten-all (UI and Telegram), watchdog, runbooks | a full rehearsal from login to EOD ledger in sandbox or DRY_RUN |
| **L4** pilot | LIVE-A: **1 lot**, 1–2 strategies, small allocation (your number), 10 sessions | zero unreconciled differences; every order tagged; slippage vs PAPER-1 measured; no unhandled incident |
| **L5** ladder | all live-set strategies; size step 10 % → 25 % → 50 % → 100 % of allocation, at least 5 sessions per step | same gates per step, plus live P&L per trade within the measured slippage band of paper |
| **L6** | more users (your own identities) and brokers: adapter, instrument mapping and charges per broker; liquidity allocator and cross-account limits; warm standby host with its own registered IP | account and user isolation tests (one account's failure, or one user's login, never reaches another); mapping checked for every traded contract |

**Expected gap between live and paper.** Live results will trail PAPER-1's. Paper entries of ₹5–6 lakh on one 0DTE
strike assume fills that the real book may not give. Risk v12's depth cap helps, but measured live slippage, not
paper, decides each size step. The ₹10 Cr curve should be tracked on the live ledger separately from PAPER-1.

## 10. Alignment with zt-tiger-v2

Read 16 Oct ~01:00, read-only: zt's `MULTI_USER_MULTI_BROKER_BLUEPRINT.md` (7 Jun 2026), `OPTIONS_LIVE_READINESS_RUNBOOK.md`
and its code.

**Taken from zt-tiger-v2 as is** (names, so the two systems can be bridged later):
- **Identity:** tenant, app user and membership. App login is never broker login.
- **Execution boundary:** `broker_account_id` is what an order belongs to. The external broker id never decides
  ownership or merges accounts.
- **Credentials and sessions:** a credential profile may be shared, a broker session never.
- **Signal source and followers:** account roles, copy groups and strategy subscriptions.
- **Pipeline:** TradeBatch, then TradeIntent, then OrderExecution, with zt's idempotency keys and DB constraints.
- **Positions:** bound to the entry account; account-strict routing first, broker failover only before any fill.
- **Broker interfaces:**
  - split interfaces: auth, orders, quotes, market data;
  - a `BrokerAccountRef` on every call, with no non-account overloads;
  - a declared capability matrix.
- **Order states:** normalised. An unknown status means reconcile, never success.
- **Long-option safety:** an entry that fills while its protective stop fails gets an emergency exit. If that exit is
  uncertain, the account freezes and alerts.
- **Risk in layers:** tenant, user, account, copy group, strategy, order.
- **Tests and gates:** zt's isolation, fan-out, execution, security and recovery tests, and its go/no-go list,
  become gates L0–L3.
- **Readiness matrix UI:** per account, showing session, token expiry, mode, live flag, risk state and the last
  rejection.

**Built differently from zt-tiger-v2**, where its own report or code shows a gap:

| zt-tiger-v2 today | auto-trade |
|---|---|
| tokens and API secrets in plain table columns | Secret Manager references only; tokens never in a table or log |
| headless Kite login with stored password and TOTP | daily OAuth by you; no password or TOTP stored (standing rule) |
| account `max_daily_loss` / `max_open_positions` shown but not enforced; account caps skipped in live | enforced for every account and mode; live is stricter than paper, never looser |
| no capital per account (config `dry-run-capital`) | allocated capital per account, FIXED or COMPOUNDING, plus a broker-funds check |
| global kill switch and global execution mode | kill switches per tenant, user, account, strategy and instrument; mode per account |
| fan-out loops over accounts in sequence | one worker lane per account, in parallel |
| broker interface returns Kite SDK models | neutral models; each adapter maps its own |
| broker-side stop only on Kite (GTT) | live only on a broker whose adapter has a protective stop (GTT or resting SL); no live without it |
| no order-rate limiter; algo header not sent | limiter per account under 10/s; the broker's algo header where required |
| no end-of-day broker reconciliation | daily trade-book and charges reconciliation into `ledger_day` |
| schema from Hibernate `ddl-auto` | Flyway migrations only (as today) |
| CSRF off, many public GET endpoints | Keycloak login on every endpoint; private data scoped by tenant and account |

**Shared constraints between the two systems:**
- Both read market data from the same Upstox primary through zt's app.
- zt's own principle, "do not let the primary signal source lend its access token to followers", is the same reason
  this plan recommends option A (section 0).
- auto-trade never writes to zt-tiger-v2 and keeps its own database. Bridging the two (for example zt reading
  auto-trade's intents) would be a later, separate decision.
- **Candidates for the first live broker:** zt-tiger-v2 already has Kite (with GTT protective stops), Upstox and Kotak
  Neo adapters, which suggests you hold accounts there. A Zerodha account through Kite Connect, with GTT as the
  broker-side stop and order postbacks, is the strongest option-A candidate. Check Zerodha's current app and
  static-IP rules first.

## 11. Decisions

| # | Decision | Status |
|---|---|---|
| 1 | Option A: live on a separate account; the Upstox primary stays market data + PAPER | **Decided 16 Oct** |
| 2 | First live broker: Zerodha (Kite Connect) | **Decided 16 Oct** |
| 3 | Pilot allocated capital, FIXED or COMPOUNDING | open (needed by L4) |
| 4 | First 1–2 pilot strategies | open (needed by L4) |
| 5 | Telegram may carry the authenticated flatten-all command | open (needed by L3) |
| 6 | Egress IP for orders: the new execution VM's own reserved IP, or the project's existing Cloud NAT IP (if zt-tiger-v2 already registered it with Zerodha, the two systems share one registration) | open (needed by L3) |

## 12. Zerodha (Kite Connect) specifics

From Zerodha's support pages and Kite Connect forum staff replies; verify against the live developer docs when L2
starts.

- **Static IP for orders only.**
  - Orders come only from the registered IP: one primary plus one optional secondary. The IP list belongs to the
    Kite Connect developer account, not to each app, and can be changed once a calendar week.
  - Market data, order book and positions can be read from any IP.
- **Apps.** Several apps can sit under one developer account (each family member's client id added to an app). The
  IPs must be used only by you or your immediate family.
- **Our app.** auto-trade gets its **own Kite Connect app**: its own API key and its own daily access token. It never
  uses an app or token of zt-tiger-v2's.
- **Rate.** At most 10 orders a second; more gets HTTP 429. Above 10 a second the strategy needs exchange
  registration. Our limiter stays well below: slices are capped at 10 per order, so freeze-quantity slicing never
  exceeds it.
- **Market orders need market protection;** protection 0 is rejected. We send limit orders (marketable limit with a
  buffer, as the OMS does now). Market-protected orders only on the emergency path.
- **Tag:** up to 20 alphanumeric characters. The intent id plus attempt number is encoded to fit.
- **Order updates.**
  - Postbacks (per app) and WebSocket order updates are the trigger. The order book is the truth: pull it on each
    update and on a timer.
  - A 200 on place means accepted, not filled.
  - Postbacks report trades, not positions, so positions are pulled.
- **Protective stop at the broker:**
  - **Primary:** a resting SL-limit order at the premium stop.
  - **Alternative:** a GTT.
  - Forum threads report missed GTT postbacks, so either way the order book is polled.
  - Under the long-option rule, a failed stop placement means an emergency exit.
- **Slicing.** The OMS slices to the freeze quantity from the contract master. Kite's autoslice is not used: one
  parent with child errors is harder to reconcile than our own children.
- **Login.** Kite's daily request-token flow, through a link that comes to you by Telegram. Nothing is automated with
  a password or TOTP (zt-tiger-v2's headless login is not copied).
- **Market data.** Never taken from Kite. The Kite app needs no market-data subscription. Executable bid and ask come
  from the Kite quote API (a read call), only before an order.

Sources: Zerodha support "Static IP" and "Kite Connect API FAQs"; kite.trade forum threads on SEBI's retail algo rules,
market protection, order tag length, postbacks and autoslice.

## 13. Status

**L0, 16 Oct ~02:30 (branch `feature/live-execution`): built, not deployed anywhere.**
- **Flyway `V21__accounts_and_execution.sql`.** Additive only: new tables in `acct` and in a new `exec` schema; no
  existing table changed. The table itself enforces two guards: live trading needs LIVE mode, a real broker and
  credentials; a PAPER account can never be LIVE.
- **Module `account`:**
  - `CapitalPolicy`: equity FIXED / COMPOUNDING; entry checks for daily loss, per-trade cap, free capital and, in
    DRY_RUN/LIVE, broker funds with a buffer. Unknown funds refuse; a refusal never shrinks the order.
  - `Sizing`: the follower budget from its equity and multiplier; the one-lot pilot step and ladder steps.
  - `LedgerDay`: the broker difference that blocks the next live start.
  - `AccountStore`: tenant-scoped reads; PAPER-1 registration; capital movements; ledger.
- **CLI:** `bin/autotrade account-register-paper --subject S --name N` registers PAPER-1 (defaults from risk v17:
  ₹5,00,000 from 7 Oct) as a PAPER, DISABLED account. Not yet run on any database.
- **Tests:** 12 new (`AccountStoreIT` runs V21 on a fresh TimescaleDB). The full build with every test is green.
- **Unchanged:** trading-core, the GCP deploy and the Mac's database (V21 is applied nowhere yet). When the branch
  merges, the deploy check `flyway: 20` becomes `21`.

**Next, L1:**
- the signal-source publisher in trading-core behind `autotrade.signal-source.enabled` (default off), with the 26-day
  identity replay off and on;
- the virtual signal account;
- batches and intents with workers in `app-execution`;
- DRY_RUN and PAPER followers on the Mac.

**L1, first slice, 16 Oct 03:00–08:15 (branch, Mac only, nothing on GCP).**

**Built:**
- `SignalRecorder`: an optional hook on TradingSession; only the signal-source session has one.
- `autotrade.signal-source.*`: a replay option, run as account SIGNAL-1 under `config/risk/signal-source.v2.yaml`
  (never refuses for capital or loss; risk v17's liquidity rules).
- The `execution` module:
  - `SignalDecisionStore`;
  - `FanOut` (one batch per decision, one intent per subscribed follower, idempotent);
  - `FollowerSimulator` (PAPER and DRY_RUN followers: equity sizing, the size step, the signal's lots as the liquidity
    ceiling, `CapitalPolicy` refusals, skips for positions not held and for straddles);
  - `config/risk/follower-risk.v1.yaml`.
- `OrderManager.participationCapQuantity`: read only.
- CLI: `follower-add`, `follow`.
- Tests: `ExecutionPipelineIT`; the full build is green.
- V21 is now applied to the Mac's database (additive; the main-branch serve container ignores it as a future
  migration). It is still applied nowhere on GCP.

**Checks on the 26 genuine tick days** (`.local/l1`, A-114's days and set):

| Check | Result |
|---|---|
| PAPER-1 unchanged: control replays on the branch image vs A-114's control | **identical on 26 of 26 days**, run twice (after each code change) |
| DRY_RUN follower = PAPER follower (same decisions, rejections, net) | **26 of 26 days** |
| PAPER follower (₹5L FIXED, all six strategies) vs PAPER-1 | **+₹39.14L vs +₹44.47L (−₹5.33L, −12 %): not yet a match** |

The gap by strategy:

| Strategy | Gap | Cause |
|---|---|---|
| ebs | −₹3.05L | straddles not followed in L1 |
| etr | −₹5.60L | sizing rules differ (PAPER-1's risk engine, including the RVOL boost, vs `CapitalPolicy`); PAPER-1's 14/15 Oct replays used its chained equity (₹10.7L / ₹7.6L), the follower a fixed ₹5L |
| ma-cross | −₹85K | as above |
| odb | +₹4.01L | smaller size on 14 Oct and different refusals |

**Two bugs found in this check, fixed:**
- **Single-leg exits stored as straddle legs.** `ManagedPosition.leg()` is "" for a single-leg position. They are now
  null, and the 118 recorded rows were corrected.
- **Signal source v1 had no liquidity rule.** Followers copied sizes no order book had (13 Oct: 24,830 vs 3,510 units).
  Fixed by `signal-source.v2` and by using the signal's lots as the ceiling.

**To finish L1:**
- follow straddles (both legs, one group);
- follower sizing on the same rules as the account's risk file (RVOL boost, budget handling) and a run at equal equity,
  to close the PAPER-1 gap or explain each remaining trade;
- the live feed tee for the signal source (a bounded queue, never blocking PAPER-1);
- `app-execution` intent workers with row-lock claiming and the lease;
- the daily divergence report.

**L1, straddles and sizing parity, 16 Oct 16:10–17:10.**

**Changes:**
- **Straddles followed.** Both legs are sized as one pair: floor(min(budget, free capital) / ((call + put ask) x lot)),
  as the OMS sizes a straddle. Each leg exits on its own `STRADDLE:CE/PE` decision.
- **Same sizing and checks as PAPER-1's risk engine, in its order:**
  - plan units (BudgetSizing: lots = floor(strategy lots x budget / (intended lots x lot x price)), scale fixed at the
    entry and used by every add);
  - the RVOL boost on spare capital;
  - max 4 open positions, 400 lots per underlying;
  - the daily loss latched for the day;
  - capital (premium in use + entry <= equity).

  `config/risk/follower-risk.v2.yaml`; Flyway **V22** adds the sizing context and `exec.position.sizing_scale`.
- **Replay depth ceiling.** A follower's position never exceeds what the signal account's own position got filled
  through the same book (its `trade.position` quantity). This is replay only; a live follower's book decides.

**Results, 26 tick days, PAPER-1 at the follower's ₹5L on every day:**

| Check | Result |
|---|---|
| PAPER-1 control vs A-114 | identical 26 of 26 (third time) |
| DRY_RUN = PAPER follower | 26 of 26 |
| Days within ±₹10K of PAPER-1 | 19 of 26 (first slice: 9) |
| PAPER follower total vs PAPER-1 | +₹42.17L vs +₹45.00L (−₹2.84L, −6.3 %); 1 Oct alone −₹2.83L, the other 25 days together −₹0.7K |

Trade by trade:

| | Trades | Net difference |
|---|---|---|
| same trade, same quantity | 80 | +₹12K (fill prices) |
| same trade, other quantity | 12 | −₹3.38L |
| PAPER-1 only | 5 | −₹54K |
| follower only | 4 | +₹96K |

**The remaining difference is capital timing.**
- The signal account never refuses, so it publishes an add at the moment it wants it.
- A capital-limited account is refused then. PAPER-1's strategy re-asks every snapshot and buys a few minutes later,
  once capital frees (1 Oct: etr's two adds after ma-cross closed, 2,040 vs 680 units).
- A follower gets each decision once.

**Fix (next):** a follower keeps a capital-refused ENTER or ADD pending while the signal holds that position, and
retries when its capital frees, priced at the account's own quote. Live and DRY_RUN workers use the broker's quote;
the replay simulator uses stored option ticks.

**L1, retry for capital-refused adds, 16 Oct 17:00–17:20.**

**Change:**
- An add refused for capital stays pending while its position is open.
- Whenever an exit frees capital, the add is retried. It is re-sized at the position's scale (and the replay depth
  ceiling), priced at that moment's ask, and checked again. This mirrors PAPER-1's strategies, which re-ask every
  snapshot.
- Prices come from `QuoteSource`. In replay that is `StoredQuotes`: own capture `md.option_tick`, else the local
  `zt_archive`. Live and DRY_RUN workers will ask the broker.
- Entries are not retried: their re-attempt rules differ per strategy.

**Results, 26 tick days at equal ₹5L equity:**

| Check | Result |
|---|---|
| Follower vs PAPER-1, total | +₹44,97,728 vs +₹45,00,314 (**−₹2,586, −0.06 %**) |
| 1 Oct | −₹1,872 (before the retry: −₹2,82,805; one retry over all 26 days) |
| Days within ±₹10K | 20 of 26 |
| DRY_RUN = PAPER follower | 26 of 26 |

Trade by trade:

| | Trades | Net difference |
|---|---|---|
| same trade, same quantity | 84 | +₹10K (fill prices) |
| other quantity | 8 | −₹61K |
| PAPER-1 only | 5 | −₹54K |
| follower only | 3 | +₹1.02L |

**Remaining differences, offsetting:**
- entries refused for capital (not retried);
- the daily loss counting open positions in PAPER-1 (realised only in the follower);
- depth at slightly different moments.

**L1 gate, part 1 (PAPER follower ≈ PAPER-1 within the expected capital-refusal differences): met.** Every remaining
trade difference is one of the kinds listed above.

**Left in L1:**
- the live feed tee for the signal source;
- `app-execution` workers (row-lock claiming, lease);
- the daily divergence report.

**L1, live feed tee and execution workers, 16 Oct 17:20–18:00.**

**Built:**
- **`TeeFeed` + `QueueFeed`** (trading-core):
  - The session's feed is handed to the session first, then offered to a bounded queue drained on the signal session's
    thread.
  - Live, a full queue fails only the signal source; replay blocks, so runs stay deterministic.
  - With `autotrade.signal-source.enabled` (default off) the signal source always runs **alongside** live, and in replay
    with `tee=true`. It has its own thread and heartbeat, no capture and no snapshots, and ends with the feed.
- **`AccountLease`:**
  - a Postgres advisory lock per account on a held connection;
  - `exec.account_lease` (Flyway **V23**), whose epoch grows at each takeover and is recorded on every order
    (`order_execution.lease_epoch`, fencing);
  - a heartbeat detects a lost lease.
- **`ExecutionLoop`:**
  - each tick fans out once (a transaction advisory lock), then runs every follower on its own single-thread lane
    (busy lanes are skipped);
  - intents are claimed (`for update skip locked`);
  - the follower state (daily-loss latch, pending retries) survives restarts, rebuilt from the database;
  - broker tags are deterministic (intent + role + attempt);
  - LIVE is refused in L1.
- **`app-execution`:**
  - a Spring Boot service on 127.0.0.1:8096 with `/api/execution/status`;
  - never migrates, and refuses to start below V23;
  - no market-data connection;
  - Dockerfile, and compose service `execution` under profile `execution` (opt-in; `docker compose up` does not start
    it).

**Checks (Mac, offline):**

| Check | Result |
|---|---|
| PAPER-1 with the tee vs control (1, 13, 15 Oct) | identical: +9,27,883 / +7,18,070 / +5,35,029 |
| Teed signal decisions vs the separate signal replays | identical (time, side, contract, lots, price, sizing context); exits now also carry `bought_quantity` |
| Worker IT (Testcontainers) | incremental ticks; a second worker refused while the lease is held; takeover with epoch 2; orders carry the epoch |
| Streaming run: worker container + a 16 Oct own-capture tee replay | DRY-TEE (worker, live as decisions arrived) = PAPER-TEE (offline): same 2 trades, prices and net −₹2,01,624, same 3 daily-loss refusals; decision to order: average 0.63 s, worst 0.92 s (1 s poll) |

**L1, daily divergence report, 19 Oct 00:40–01:10 (branch, Mac only).**

- `DivergenceReport` (execution module, reads only) and CLI `divergence-report --session D[,D] [--copy-group G]
  [--paper-session ID] [--out DIR]` (Markdown per day):
  1. **signal source vs each follower:** every ENTER decision, the follower's outcome (executed, or the intent's skip /
     reject code), its lots against the signal's, the entry and exit fills against the signal's reference prices
     (slippage %), its net;
  2. **PAPER follower vs PAPER-1:** positions matched by strategy, underlying, side, contract and entry time (90 s);
     quantity includes adds on both sides; a one-sided trade is *explained* by the follower's refusal or skip, or by
     PAPER-1's risk refusal (trade.rejection), else *unexplained*. Gate for the day: the follower ran and nothing is
     unexplained. A follower that processed none of the day's intents is "not run this day" and not counted.
- PAPER-1's session: the same mode and code version as the signal session (the tee runs both), else the day's latest.
- Tests: `DivergenceReportIT` (3: matched / explained / unexplained / not run / no decisions).
- **On the Mac's stored runs: LIVE-SET (PAPER-F2) within the gate on 26 of 26 tick days**; daily follower − PAPER-1
  from −₹50,120 to +₹33,954, all from matched trades (sizing and fill timing) or explained ones; TEE-TEST (PAPER-TEE,
  streaming worker) within on 16 Oct, its only run day. Reports in `.local/l1/divergence/`.

**L1 is complete; its gate is met.** L2 (the Zerodha adapter: auth, orders, the broker's quotes and
funds, the order stream, a broker-side stop, recovery) comes next.
