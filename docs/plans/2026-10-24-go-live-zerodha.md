# Go-live plan: Zerodha first, signals fanned out from PAPER (24 Oct 2026)

User, 24 Oct: start live trading from Monday; several broker accounts (Upstox, Zerodha, Kotak Neo) with separate users
and ledgers; Zerodha first; the current capture + signals stay primary and keep trading PAPER, and fan the signals out
to dedicated trading accounts that size by the capital chosen for each account, not the broker's whole balance.

Builds on `docs/LIVE-TRADING-PLAN.md` (branch `feature/live-execution`, now merged with main 515b83e and pushed).

## 1. Short answer

- **What you describe is the architecture already built on the branch** (L0–L2, L6 first slice): PAPER-1 is the
  signal source; a copy group fans each approved entry/exit out to follower accounts; every follower has its own
  user, broker account, allocation (FIXED or COMPOUNDING), daily loss limit and ledger; a follower is sized from **its
  allocation**, and the broker's funds are only a ceiling check (unknown funds refuse, a refusal never shrinks an
  order). PAPER-1 is unchanged by any of it.
- **Real money on Monday 26 Oct is not safe**, for concrete reasons, not caution for its own sake:
  1. The Kite Connect app, its redirect URL and the static-IP registration do not exist yet (yours to create).
  2. The daily Kite login and the access-token store are not built (blocked 19 Oct by the session's safety classifier
     until you approved it).
  3. The execution service and its tables (Flyway V21–V24) are not on GCP; GCP runs trading-core only, schema 20.
  4. The Zerodha path has run only against a local simulator. It has never read a real Kite account (funds,
     quotes, order book), and no DRY_RUN day has run against a real broker.
- **What Monday can be:** the live set keeps trading PAPER as today, and your Zerodha account follows it in
  **DRY_RUN against the real Kite API**: real login, real funds, real quotes, every order computed, sized to your
  allocation and logged, **none sent**. First real orders, **1 lot**, after 3 clean DRY_RUN sessions: **Thursday
  29 Oct at the earliest**, more likely Monday 2 Nov.

## 2. What the evidence says about the strategies (read before choosing money)

- Live set on 2025–2026 at ₹5 L a day (A-199): +₹41.5 L, but **+₹39.0 L of it on 27 Sep–Oct tick days** (where the set
  was chosen, in-sample) and **+₹2.45 L on 416 bar days (PF 1.02)**. 2025 alone −₹4.25 L.
- etr (+₹24.3 L on 20 trades) and odm (+₹6.9 L on 19) only trade from Aug 2026 (data); mac is ~break-even over
  1,095 trades (PF 1.05); ecr is small but positive in both years (PF 1.87).
- Live fills will trail paper (depth, slippage, partial fills on 0DTE strikes).
- So the pilot is a **systems test with real money**, sized so a full loss is acceptable to you, not a step towards
  the ₹10 Cr curve. Sizing decisions are yours; I am not a licensed adviser.

## 3. Work, in order

### Saturday 24 – Sunday 25 Oct (Claude, on your go-ahead for each item)

| # | Item | Notes |
|---|---|---|
| C1 | **Kite login flow + token store** | `GET /api/brokers/kite/login` → Kite login page; Kite redirects to `/api/brokers/kite/callback` with the request token; the server exchanges it (API key + secret) for the day's access token and stores it **encrypted on the VM** (`/srv/auto-trade/secrets`, root-only, or GCP Secret Manager). Never a password or TOTP. A Telegram message at 08:30 IST carries the login link. Needs your explicit go-ahead (this is the code that was blocked on 19 Oct). |
| C2 | **Deploy the execution stack to GCP, signals only** | Flyway V21–V24 on GCP (additive; existing tables unchanged; the deploy check becomes `flyway: 24`); trading-core with `signal-source.enabled=true` in **tee** mode (PAPER-1 decides exactly as today, A-L1 gate met on 26/26 tick days); the `app-execution` container with one worker; behind the same nginx basic auth. |
| C3 | **Your user and Zerodha account rows** | One user (you), one broker account `ZERODHA-1` in **DRY_RUN**, copy group = the live set, the allocation and limits you choose (section 4), follower-risk **v3** (v4's cross-account limits only matter with several accounts). |
| C4 | **Watchdog + alerts** | `bin/execution-watchdog` every minute 09:00–15:45 → Telegram on DOWN / STALE / account error / unreconciled position. |
| C5 | **Flatten-all** | Already in the UI and API (Accounts page); test it on the DRY_RUN account. Telegram flatten only if you decide it (decision 5). |
| C6 | **Rehearsal** | Sunday: a replay day through the full path (signal → intent → worker → Kite adapter in DRY_RUN) on GCP, then the divergence report. |

### By Monday 08:30 IST (you)

| # | Item |
|---|---|
| U1 | Create a **Kite Connect developer account and app** (paid subscription; check Zerodha's current price). App type: Connect. Redirect URL: `https://autotrade.136-82-45-194.sslip.io/api/brokers/kite/callback` (C1 confirms the exact path). |
| U2 | Register the **static IP `34.14.137.170`** (GCP Cloud NAT `zt-egress-ip`; zt-autotrade's only way out) as the app's primary IP. Zerodha allows a change about once a week, so register it once and leave it. |
| U3 | Put the **API key and secret into GCP Secret Manager yourself** (I give the exact commands; never paste them in chat). |
| U4 | Make sure the Zerodha account has **F&O enabled** and funds ≥ your allocation + a buffer for charges and margin on stops. |
| U5 | Decide section 4. |

### Monday 26 – Wednesday 28 Oct: DRY_RUN against real Kite (no orders)

- 08:30 Telegram link → you log in to Kite once (≈ 1 minute). The token is valid for the day.
- The follower computes every entry, add, stop and exit from PAPER-1's signals, sized to **your allocation**, checks
  real funds and real quotes, and logs the orders it **would** send.
- 15:45: divergence report (follower vs PAPER-1), ledger, reconciliation.
- **Gate to go LIVE:** 3 sessions with no login, token or funds failure, every PAPER-1 decision followed or its refusal
  explained, no unreconciled position, the kill switch and flatten tested once.

### Thursday 29 Oct at the earliest: LIVE pilot (L4)

- **You switch `ZERODHA-1` to LIVE** in the Accounts page (I do not place or enable real orders).
- 1 lot per entry, only the pilot strategies you choose, MIS, limit orders with a buffer, a resting broker SL for every
  position, daily loss limit = your number, kill switch armed, max 2 open positions.
- 10 sessions at 1 lot, then the ladder (10 % → 25 % → 50 % → 100 % of the allocation, 5 sessions a step), each step
  only if live P&L per trade sits within the measured slippage band of PAPER-1.

## 4. Decisions needed from you

| # | Decision | Notes |
|---|---|---|
| D1 | **Allocation for ZERODHA-1** (₹) and FIXED or COMPOUNDING | The follower never uses more than this, whatever the broker balance. |
| D2 | **Daily loss limit** for the account (₹) | Stops new entries and flattens when hit. |
| D3 | **Pilot strategies** (1–2) | Evidence in section 2: ecr is the only one positive on both bar years; etr/odm are strong but have only Aug–Oct 2026 history; mac is ~break-even on bars with many trades (most order load). |
| D4 | Go-ahead for **C1** (login + token store) and **C2** (deploying V21–V24 + app-execution to GCP) | Both touch production; C2 is additive and leaves PAPER-1's decisions unchanged. |
| D5 | Telegram flatten command | Optional for the pilot. |

## 5. Users, ledgers and the other brokers

- **Separate users:** the `acct` schema already has users (tenants), broker accounts per user, allocations, capital
  movements and a daily ledger per account. For the pilot: one user. Each extra identity (yours or immediate
  family's) is a user with its own accounts; real per-user logins need **Keycloak** (L3), before a second person uses
  the UI.
- **Upstox:** the primary Upstox account stays market data + PAPER (zt-tiger-v2's app; never used for orders). A live
  Upstox follower needs **another Upstox account** with its own API app and the same static IP.
- **Kotak Neo:** no adapter yet. It needs its own adapter (orders, order book, quotes, instrument mapping, charges)
  behind the same `BrokerGateway`, plus its static-IP and login rules. Roughly the L2 work again (about a week),
  then its own DRY_RUN days. Not before the Zerodha pilot is clean.

## 6. Compliance (confirm with Zerodha; not legal advice)

- Own accounts (and immediate family under Zerodha's IP rules) only; never other people's money.
- Orders only from the registered static IP; under 10 orders a second per account (our limiter), so no exchange algo
  registration; the broker tags orders.
- No plain market orders: limit orders with a buffer; market-protected orders only on the emergency path.
