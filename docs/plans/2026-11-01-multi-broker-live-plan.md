# Multi-broker, multi-user live trading: Kite, Upstox, Kotak Neo (1 Nov 2026)

Status: **plan only, nothing built.** User, 1 Nov: "work on the multi broker multi user functionality to support Kotak
neo, upstox and kite, first analyze and then think about real trades fire fighting all other negative positive
scenarios, give me the plan? be brutally honest and true."

This plan builds on [LIVE-TRADING-PLAN.md](../LIVE-TRADING-PLAN.md) (architecture, phases L0–L6) and
[2026-10-24-go-live-zerodha.md](2026-10-24-go-live-zerodha.md) (the Zerodha pilot). It does not repeat them. It records:
- what is real today and what is not;
- what each broker forces on us;
- what can go wrong with real money;
- the order in which to build.

PAPER only until you switch an account to LIVE yourself.

## 0. The honest summary

1. **No order has ever reached a real broker.**
   - ZERODHA-1's "DRY_RUN" (26–30 Oct) sends its orders to a **local Kite simulator**. That simulator has an
     unlimited order book (100,000 lots of depth) and uses our own stored bid/ask.
   - It does not read Kite's quotes, Kite's funds, Kite's order book or Kite's instrument file. The only real Kite
     call that has ever run is the daily login.
   - The go-live plan of 24 Oct promised "real funds and real quotes" in DRY_RUN. The code does not do that.
   - In any case, ZERODHA-1 followed only ecr, which did not trade that week, so even the simulated path has no real
     day behind it.
2. **Only one broker adapter exists (Kite).**
   - `BrokerType` already names UPSTOX and KOTAK_NEO, but `BrokerConnections` refuses both: "no order adapter … yet".
   - LIVE is refused for every broker, by design.
3. **Three brokers is three times the failure surface, not three times the profit.** The strategies make the money.
   The brokers are only places where things break. My advice is:
   - one broker live and clean first;
   - then the second;
   - then the third.

   Building all three at once is how a wrong strike gets bought in a family member's account.
4. **The edge is not proven well enough to scale.**
   - **The live set over two years of bars:** only ecr is positive in both years. odm-v4 is PF 0.99 (A-231). etr's
     profit comes from 3 days.
   - **Tick days from 14 Oct:** the live set made +₹12.9 L, and +₹12.8 L of that was one day (15 Oct).
   - **Live PAPER-1 in late October:** −1.38 L, +15 K, +1.50 L, +50 K.

   Copying this to more accounts multiplies the losing days as well as the winning ones.
5. **Paper fills are optimistic, and multi-account makes it worse.**
   - Paper buys ₹2–5 L of a 0DTE strike at the quote.
   - Two or three family accounts on three brokers will buy **the same strike in the same second from the same order
     book**.
   - Today's replays measured the spread cost (−0.7 %) but not the depth cost.
   - The size every account can really get is set by the book, not by capital. The ₹49 L replay figure cannot be scaled
     by adding accounts.
6. **The daily login is the weakest operational link.** Every broker forces a fresh login each trading day with a
   human second factor (section 2). Three brokers times N family members means 3N logins before 09:15, every day.
   A missed login is harmless: that account sits out the day. **A session lost mid-day with an open position leaves
   only the broker-side stop to protect it.**
7. **There is one host, in one region, on one billing account.** On 28 Oct GCP billing closed at 17:03 (after the
   market). In market hours, every open live position would have depended only on its resting broker stop. The plan
   below adds a second, independent path.
8. **Multi-user means family only.**
   - All three brokers allow family members to share a static IP. Kotak allows up to 10 members. Upstox has supported
     it since May 2026. Zerodha does it through one developer account with a key per member.
   - **We will not rely on that (user, 2 Nov): every broker account gets its own static IP** (section 3.9), so one
     account's IP, login or registration problem can never touch another, and no broker has to judge a "family".
   - Running the system for unrelated people makes you an algo provider (exchange empanelment through a broker).
     That is out of scope.

## 1. What exists today (verified in code, main 629965d)

| Area | State |
|---|---|
| Signal source → fan-out → follower workers (`execution`, `app-execution`) | Built. L1 gate met. On GCP since 24 Oct. |
| Tenants, users, roles, family members (V21–V27), Keycloak sign-in | Built. On GCP since 29 Oct. |
| `BrokerGateway` / `BrokerConnection` / `BrokerCapabilities`; error taxonomy (RETRYABLE / TERMINAL / SESSION / UNKNOWN) | Built, broker-neutral. |
| Kite adapter (`broker-zerodha`): HTTP, login checksum, LIMIT + SL, tags ≤ 20, tick rounding, idempotent place with lookup by tag, 8 orders a second | Built. **Tested only against `KiteSimulator`.** |
| Kite daily login + token store (`KiteLogin`, `BrokerSecrets`) | Built. Used daily since 26 Oct. |
| BrokerVenue: marketable-limit entry, freeze slicing, exit chase, resting SL, cancel-stop-before-exit, recovery by tag | Built. Tested in IT and the local chaos drill. |
| Kill switches (GLOBAL_LIVE / ACCOUNT / STRATEGY / INSTRUMENT), audit, flatten one or all | Built. |
| Reconciliation: intraday every 30 s and at 15:45 | Built. Never run against a real broker book. |
| EOD square-off of followers (`ExecutionLoop.squareOff`) | Built: a safety net after the strategies' own 15:15 exits. |
| Cross-account limits + liquidity allocator (follower-risk v4) | Built. **Attached to no account.** |
| Upstox order adapter | **Not built.** Upstox market data, login and token code exist, but they belong to the primary data account. |
| Kotak Neo adapter | **Not built.** |
| Real-broker read path in DRY_RUN (quotes, funds, order book, instruments) | **Not built.** |
| Per-broker charges and contract-note reconciliation | Charges per order exist for Kite. No contract-note import for any broker. |
| WebSocket / postback order updates | Not built. Polling only, about 1 s. |
| Independent watchdog off GCP | Not built. `bin/execution-watchdog` exists but nothing runs it. |

**Known inconsistency:** the go-live plan says the follower's daily loss limit "stops new entries and flattens". The
code only refuses new entries. Open positions keep their stops and exits. Decide which you want (decision D7).

## 2. What each broker forces on us

These are from the brokers' own pages where they exist. Items marked *verify* must be checked in the broker's live
API docs before that adapter's code is written.

| | Kite (Zerodha) | Upstox | Kotak Neo |
|---|---|---|---|
| Static IP for orders | Yes: primary + secondary, change about weekly | Yes: place / modify / cancel / multi / GTT; change about weekly; a change invalidates the token | Yes, since 1 Apr 2026: primary + secondary, change once in 7 days; **the session must be created from the same IP that sends orders** (error 1037) |
| Read APIs from any IP | Yes | Yes | Yes (login, reports, portfolio, data, WebSocket) |
| Apps | Several keys under one developer account | **One active app per user** (others deleted) | API application per user; family members are added under Trade API |
| Family sharing of one IP | Same developer account, one key per member | Since May 2026 | Up to 10 members; only the parent can change the IP |
| Daily login | Request-token redirect (you sign in on Kite, including Kite's own 2FA) | OAuth redirect, daily; token ends ~03:30 *verify* | Access token + **TOTP + MPIN** typed each day; the session is tied to the IP |
| Market orders | Must carry market protection; protection 0 refused | Market protection applied by Upstox | Not allowed; converted to a protected limit (options 1–10 % from LTP by price band); **rejected if the option has no LTP** |
| Order rate | < 10/s without registration (we use 8) | < 10/s | 10 orders/s |
| Algo id | Added by the broker | Header `X-Algo-Id` or `X-Algo-Name`: sources disagree, *verify* | Added by Kotak |
| Client tag (our idempotency key) | `tag`, 20 alphanumerics | `tag` field exists, length *verify* | **verify: if Kotak has no client tag searchable in the order book, recovering an order with an unknown outcome needs another design (section 4, E3)** |
| Order updates | Postback + WebSocket | Portfolio stream WebSocket | Order feed WebSocket *verify* |
| Instrument ids | instrument_token / tradingsymbol | instrument_key `NSE_FO\|token` | its own scrip master (pSymbol / trading symbol) *verify* |
| Broker stop | SL (stop-limit), GTT | SL, GTT | SL *verify*; bracket/cover orders reportedly withdrawn on the API, 1 Apr 2026 *verify* |

**What this means for us:**
- **One static IP per broker account (decision D9, user 2 Nov).** The rules would allow one IP across brokers, and
  zt-tiger-v2 uses exactly that (one Cloud NAT IP, `34.14.137.170`, for everything; checked 2 Nov in its
  `ops/gcp/10-network.sh` and the live NAT). We do not: each account sends all its broker calls, logins included,
  from its own IP (section 3.9). `34.14.137.170` stays for zt and market data and is registered by no trading account.
- **Each account's IP survives a host change.** The public IP belongs to the account's NAT gateway, not to a VM, so a
  standby VM that takes over the account's alias range sends from the same IP (3.9). A secondary IP is then only
  for a NAT or broker-side problem (D4).
- **Kotak needs special care with its IP-bound session.** The login must run on the order host, through that
  account's egress, never from your phone or the Mac.
- **Upstox live trading cannot use your primary Upstox account.** That account's single app is zt-tiger-v2's data app.
  Upstox live means another family member's Upstox account, with their own app.
- **No market orders anywhere.** We already send marketable limits. Kotak's rule that an option without an LTP is
  rejected matters for deep-OTM exits late on expiry day.

## 3. What to build (deltas only)

### 3.1 Broker adapter contract (all three)
Each adapter implements `BrokerConnection` and declares `BrokerCapabilities`, and adds:
- **`LoginFlow`:**
  - Kite: `DAILY_REQUEST_TOKEN`.
  - Upstox: `DAILY_OAUTH`.
  - Kotak: a new `DAILY_TOTP_MPIN`. The user types the TOTP and MPIN into our page; we pass them once to Kotak and
    keep only the session token. **The MPIN and TOTP secret are never stored or logged.** No headless login, the same
    rule as Kite.
- **Instrument master** loaded daily at 08:30. It maps our exchange key (segment + exchange token) to the broker's id,
  and checks expiry, strike, option type, lot size and tick against `ref.instrument`. **Any field that disagrees
  blocks that contract for that broker for the day.** A wrong mapping is the one bug that buys the wrong option.
- **Status and error mapping** onto `OrderStatus` and `BrokerException.Kind`, from recorded real responses:
  - Kotak 1037 / 100008 → SESSION;
  - Upstox token invalid → SESSION;
  - 429 → RETRYABLE;
  - band / freeze / margin → TERMINAL.
- **A simulator per broker** (Kite has one): a local server that speaks the broker's HTTP shapes, with the same fault
  menu (timeouts before and after processing, 429, 5xx, partial fills, stop filled on cancel, stale book, session
  expiry, IP mismatch).
- **Charges file per broker** (brokerage plan, exchange, STT, stamp, GST), dated and versioned like
  `config/costs/`.

### 3.2 DRY_RUN that touches the real broker (all three, before any LIVE)
A `READ_LIVE` route:
- real login;
- real instrument file;
- real `quote` (bid, ask **and depth**);
- real `availableFunds`;
- real order book, trade book and positions read every 30 s.

Only the order calls are simulated. The simulator's book comes from the real broker quote and depth at that second,
not from our stored ticks.

This is what the 24 Oct plan described. It is the minimum before money, because it exercises the IP, the session, the
instrument mapping and the quote shapes every day without sending an order.

### 3.3 Canary orders (LIVE accounts only, your switch)
A short check each morning, before the first entry is allowed:
1. a 1-lot **buy limit at ₹0.05** on a liquid next-week option, which cannot fill;
2. read it back by tag;
3. modify it;
4. cancel it;
5. check the book shows CANCELLED.

This proves the static IP, the session, the tag, the status mapping and modify/cancel on the real broker without a
fill. A failed canary keeps that account out of the day.

It is a real order on a real account, so it runs only on accounts you have switched to LIVE.

### 3.4 Product and day end
- **MIS for every live follower position.** The broker's own auto square-off near its cut-off is a free last line of
  defence if our host is down. Our square-off stays at 15:15.
  - Each broker's MIS cut-off differs (*verify*), so it goes in that broker's capabilities.
  - A strategy that holds past 15:15 (els-v26 holds to 15:29) is **not followable live**. The fan-out must refuse
    such a subscription for a LIVE account.
- **No expiry exercise.** A long ITM option left at 15:30 on its expiry day is exercised: STT on the intrinsic value,
  and cash settlement days later. MIS plus our 15:15 square-off prevent it. The 15:45 reconciliation alerts if
  anything is still held.

### 3.5 Broker-side stop that ratchets
Today the resting SL sits at −25 % from entry and stays there. If our host dies while a trade is up +80 %, the stop
lets it fall back to −25 %.

**Option (needs a registered test, as an exit rule, not a tweak):**
- move the resting SL to break-even once the position is up 50 %;
- then trail it to the strategy's own trail level, modified at most every 30 s.

Until tested, the stop stays as it is.

### 3.6 Multi-account and family at scale
- **Turn on follower-risk v4** (the liquidity allocator and combined limits) for any tenant with more than one live
  account. Add **staggered slices** across accounts: today they all hit the book in the same second.
- **Each family member is their own tenant**, with their own broker account, own app or API key, own login and own
  ledger and own static IP (3.9). They share only the signal group (V27 share).
- **Daily login board:** a page plus a Telegram list at 08:30 showing who has logged in and who has not. An account
  without a session by 09:10 is out for the day and says so.

### 3.7 Ledgers and reconciliation
- At 15:45, pull each broker's trade book and compare it fill by fill. Write `ledger_day` per account. Any difference
  blocks that account's next LIVE day until you acknowledge it.
- Weekly: import each broker's contract note (PDF or CSV, by hand at first) and compare charges with our model.
  Charges differ by broker plan. Today only Kite's per-order brokerage is modelled.
- Tax view per person and broker. F&O is business income, so each account's ledger must be complete and stand on its
  own.

### 3.8 Second path off GCP
- **A watchdog off GCP** (the Mac, under launchd, or a tiny VM in another project) that does all of these every 30 s:
  - checks `/api/execution/status`;
  - checks each broker's positions **read-only through that broker's API**;
  - Telegrams you if a live account holds a position while the worker is down or stale.
- **A runbook per broker:** how to flatten from that broker's own app, which screen, which product. Rehearse it once
  per broker.
- **A standby host** that can take over each account's alias range, and so its IP (3.9). A secondary IP per account
  only if D4 says so.

### 3.9 One static IP per broker account (D9, user 2 Nov)

**How it works on GCP.** A VM network card has one public IP, so per-account IPs come from Cloud NAT, which picks the
public IP by the packet's *source range*:

```
execution worker (one container, many accounts)
  account 7's HttpClient ──bind──▶ 172.31.7.2 (container IP on docker network egress-7)
        Docker SNAT (bridge option com.docker.network.host_ipv4) ──▶ 10.21.0.56 (alias IP on zt-autotrade, range acct-7)
        Cloud NAT gateway nat-acct-7 (serves only range acct-7) ──▶ 34.x.x.x = egress-acct-7  ──▶ Kite / Upstox / Kotak
  account 9's HttpClient ──bind──▶ 172.31.9.2 ──▶ 10.21.0.72 ──▶ nat-acct-9 ──▶ egress-acct-9
  market data, zt-v2, everything else ──▶ primary range 10.20.0.0/24 ──▶ zt-nat ──▶ 34.14.137.170 (unchanged)
```

- **Per account in GCP:** a reserved regional static IP `egress-acct-<id>`, a /29 secondary range `acct-<id>` on
  `zt-subnet` (from a new 10.21.0.0/16 pool), one /32 alias IP from it on the worker VM, and a NAT gateway
  `nat-acct-<id>` on `zt-router` that serves only that range with only that IP.
- **Per account in Docker:** a bridge network `egress-<id>` whose outgoing traffic is SNATed to the account's alias IP
  (`com.docker.network.host_ipv4`; the VM runs Docker 29.8.2). The execution container joins it (`docker network
  connect`, no restart needed).
- **Per account in Java:** each account's broker client is built with `HttpClient.Builder.localAddress(<its container
  IP on egress-<id>>)` (Java 19+; `KiteHttp` today builds one plain client). Every call for that account goes through
  it: orders, reads, the login or token exchange, order-update WebSockets. **No account ever uses the default
  client.**
- **Why not other ways:** Cloud NAT rules choose by *destination*, and broker APIs sit behind CDNs whose addresses
  move. A proxy VM per account works (the fallback, ~₹850 a month each) but adds a box that can die. One NIC per IP
  needs one VPC per account and a large VM.

**One-time change to shared infrastructure (D10).** Today `zt-nat` serves `ALL_SUBNETWORKS_ALL_IP_RANGES`, which
would also claim every new secondary range. It must be narrowed to `zt-subnet`'s primary range. Both VMs live in that
range, so zt-v2 and auto-trade keep `34.14.137.170`. But it is a change on the path zt-tiger-v2's traffic uses, so it
is done after hours, on your go-ahead, with zt-v2's egress checked before and after. zt-tiger-v2's own files are not
edited.

**What happens when you add a broker account:**

| Step | Who | What | Account state |
|---|---|---|---|
| 1 | you (UI) | Add the account: broker, owner, PAPER / DRY_RUN | `EGRESS_NONE`: can run PAPER; DRY_RUN reads use the shared IP; **no LIVE** |
| 2 | you, or Claude at your request, after hours | `bin/egress provision --account <id>`: prints the plan; `--apply` reserves the IP, adds the range, the alias, the NAT gateway and the Docker network, connects the worker, writes `acct.broker_egress`. Idempotent: a re-run finishes a half-done one | `EGRESS_PROVISIONED` |
| 3 | automatic | The worker fetches its public IP through that account's client (two independent echo services) and compares it with the reserved IP | `EGRESS_VERIFIED`, or `EGRESS_FAILED` + Telegram |
| 4 | the account owner | The account page shows **"Register 34.x.x.x as the primary static IP"** with a link to the broker's page. The owner registers it at the broker and ticks "registered" | `EGRESS_REGISTERED` |
| 5 | the broker | First proof that the broker accepts the IP: Kotak's login (1037 if not), Kite's and Upstox's first canary order (K2). Until then the registration is believed, not proven | — |
| 6 | you | Switch the account to LIVE (refused unless state is `EGRESS_REGISTERED` and today's check passed) | LIVE |

**Every trading day, 09:00–09:05:** each account's public IP is checked again through its own client. A mismatch, a
timeout or a missing Docker network takes **that account** out for the day (reject code `EGRESS`), shown on the login
board and sent by Telegram. The other accounts keep trading.

**Removing an account:** `bin/egress retire --account <id>` stops the account first, removes the gateway, alias and
network, and **keeps the reserved IP for 7 days** (the brokers' change limit) unless the owner confirms they removed
it at the broker; then releases it. A released IP can go to a stranger, so it must not stay registered.

**Code changes (in `../auto-trade-broker`):**
- Migration V28 `acct.broker_egress`: account id, alias IP, container IP, Docker network, reserved IP name and
  address, optional secondary IP, state, `verified_at`, `registered_at` (owner), last check time and result; changes
  audited in `exec.audit_log`.
- `BrokerEgress` (execution): builds each account's `HttpClient` from that row; refuses to build one without a row
  for a LIVE or BROKER-market DRY_RUN account; the daily check; reject code `EGRESS` in `ExecutionLoop`.
- Every gateway (Kite now; Upstox and Kotak when built) takes its `HttpClient` from `BrokerEgress`. `KiteHttp`
  stops building its own. The quote limiter stays per app key.
- Account page and login board: the IP to register, the state, today's check.
- `bin/egress` (provision / verify / retire / list), run from the Mac with gcloud, dry-run by default. **The
  application gets no GCP network rights**: a compromised app must not be able to rewire the project's network.
- Tests: a Linux integration test with two accounts bound to `127.0.0.2` and `127.0.0.3` against a local server that
  records the source address (every order, read and login of account A from A's address, never B's); a missing or
  failed egress refuses that account only; a script test of `bin/egress` in dry-run.

**Limits and costs (checked 2 Nov):**
- Region quota: 8 static external IPs, 1 used (`zt-egress-ip`), so **7 accounts** without a quota request (fewer if
  each takes a secondary IP). A quota increase is a console request.
- About ₹250–300 a month per in-use IP, ₹600 per reserved but unused IP, plus Cloud NAT's small per-gateway and per-GB
  charges. Check the pricing calculator.
- To prove in E0: that a second NAT gateway on the same router can serve a secondary range of `zt-subnet` while
  `zt-nat` serves its primary range, that GCP's guest agent makes the alias IP usable as a source, and that Docker's
  `host_ipv4` SNAT holds. If any fails, the account uses the proxy fallback.

### 3.10 The daily login, as close to automatic as the rules allow (user 3 Nov)

Every broker needs a fresh session each trading day. Fully automatic login is not on the table:
- **Kite:** Zerodha's staff state the exchange requires a manual login at least once a day and advise against
  automating it ([forum](https://kite.trade/forum/discussion/comment/40680)); TOTP is mandatory. Scripts that log in
  headlessly need the account's password and TOTP secret stored on the server: against the broker's stated rule, and
  anyone who gets into the VM could then log in to the account. **Not built, on purpose (D12).**
- **Upstox:** an official approve-on-the-phone flow exists ([access token request](https://upstox.com/developer/api-documentation/access-token-request)):
  the server asks, the owner taps Approve in the Upstox app or on WhatsApp, Upstox posts the day's token to our webhook
  (token valid till 03:30 next morning). No password is typed and none is stored.
- **Kotak Neo:** TOTP + MPIN on our login page (N1); no approve flow known.

What gets built:
1. **Kite reminders (built 3 Nov, branch `feature/kite-login-reminders`, deploy after the first LIVE day):** 08:30
   the login link, 08:50 a reminder, 09:05 the last reminder before the open, each only to an account still without a
   session; 09:16 an ALERT for a LIVE account with no session at the open (entries refused, exits wait for the login;
   the broker-side stop still rests at Kite). The 09:10 login board stays.
2. **Upstox approve flow (U1):** at 08:30 on trading days `POST /v3/login/auth/token/request/{client_id}` for every
   enabled Upstox follower account without a session; a notifier webhook on our public URL receives the token, checks
   the client id against the account's pinned user id, stores it like a Kite token (session file, owner-only, never in
   the database) and marks the session ACTIVE; audited `SESSION_LOGIN` with `via=token-request`. Reminders as for Kite
   (resend the request at 08:50 and 09:05 while no token arrived). Fallback: the ordinary OAuth link. Risks to prove in
   U1: webhook delivery (developers report needing Upstox to whitelist the notifier URL), the token's client matching
   the account, and a request the owner rejects or ignores (= no session, the account is out, as P1). Applies to
   auto-trade's own Upstox apps only; zt-tiger-v2's login is not touched.

## 4. Fire-fighting: what happens with real trades

**A** = automatic action, **Y** = your action. Rows marked *new* need building; the rest exist and are tested
against the simulator only.

### Before the open
| # | Scenario | Detection | A | Y |
|---|---|---|---|---|
| P1 | Login not done by 09:10 | session status | account out for the day; link at 08:30, reminders 08:50 and 09:05, board 09:10, LIVE alert 09:16 (3.10; reminders on a branch) | log in or accept the day off |
| P2 | Kotak session created from the wrong IP (phone, Mac) | 1037 on the canary | account out; message says "log in through the app page" (*new*) | log in again through our page |
| P3 | IP changed or de-registered at the broker | 100008 / IP errors on the canary | account out (*new*: canary) | fix the IP registration (a weekly limit applies) |
| P4 | Instrument master disagrees (lot size changed, new expiry pattern, strike missing) | 08:30 mapping check | that contract blocked for that broker (*new*) | none, or check the exchange circular |
| P5 | Funds below allocation + buffer | funds API | entries refused for funds (built: `CapitalPolicy`) | add funds or lower the allocation |
| P6 | Broker or exchange outage at the open (it happens to every broker) | canary / 5xx | account out until a canary passes (*new*) | none |
| P7 | Yesterday's position still open (a missed square-off, an NRML leftover) | reconciliation at takeover | account kill (entries); alert with the broker view (built) | flatten in the broker app or adopt it |
| P8 | An account's public IP is not its reserved IP (NAT gateway, alias or Docker network missing or changed) | 09:00 egress check through that account's client | that account out for the day, `EGRESS`; others trade (*new*: 3.9) | `bin/egress verify`, then re-provision after hours |

### Entry
| # | Scenario | Detection | A | Y |
|---|---|---|---|---|
| E1 | Partial fill | order updates | the rest cancelled after 10 s; the position is what filled; stop for that quantity (built) | none |
| E2 | No fill (the move ran away) | timeout | cancelled; the intent is ENTRY_NOT_FILLED; **no chasing an entry** (built) | none. This is a good outcome: missed, not lost. |
| E3 | Place times out: did it reach the exchange? | UNKNOWN | look up by tag, adopt or re-place (built for Kite). **Kotak without a tag: blocked as LIVE until solved** | none |
| E4 | Rejected for band, freeze or margin | TERMINAL | refused, never shrunk silently (built) | read the reason |
| E5 | Wrong contract bought (mapping bug) | reconciliation: position not in OMS | account kill + alert (built). P4 is the real prevention | flatten by hand |
| E6 | Several family accounts on one strike, the book too thin | allocator | depth split pro rata; a share under 1 lot is refused (built in v4, *turn on*) | none |
| E7 | Entry fills but the stop cannot be placed | stop refused | emergency exit + account kill (built) | check why |
| E8 | 10 orders a second exceeded (slices + stops + several accounts) | limiter per account | queued at 8/s (built for Kite, *per broker*) | none |

### Holding
| # | Scenario | Detection | A | Y |
|---|---|---|---|---|
| H1 | Session dies mid-day (token revoked, Kotak IP flip) | SESSION error | entries refused; exits wait for login; **the resting SL still protects** (built) | log in again at once (Telegram) |
| H2 | Our host or GCP dies with positions open | off-GCP watchdog (*new*) | broker SL protects; MIS square-off at the broker's cut-off | flatten in the broker app if needed (runbook) |
| H3 | Signal source dies (trading-core crash) but the worker lives | stale bus | no new entries; positions keep their stops; 15:15 square-off still runs in the worker (built) | none |
| H4 | You trade by hand in the same account | untagged order or position | account kill for entries; that instrument frozen; **never auto-flattened** (built) | don't trade by hand in an algo account |
| H5 | You close our position in the broker app while our exit is working | position 0 but our sell still working | ***new*: cancel our working sells when the broker net goes to 0; otherwise the sell fills and leaves a short option** | — |
| H6 | Big open profit, then a crash | — | today the stop is at −25 % from entry; see 3.5 | — |
| H7 | Exchange halt / circuit | errors, phase | entries blocked (built) | wait |
| H8 | Order-update stream silent | the 1 s poll keeps going | the order book is the truth (built) | none |

### Exit
| # | Scenario | Detection | A | Y |
|---|---|---|---|---|
| X1 | Exit does not fill (0DTE strike after a collapse, bid at 0.05) | chase exhausted | stop re-armed for the rest; account kill; alert (built) | sell by hand, or let it expire worthless if the bid is 0.05 (cost: nothing more) |
| X2 | Our exit and the broker SL both sell | cancel-stop-before-exit | stop cancelled first; if it already filled, no exit is sent (built, tested) | none |
| X3 | Kotak: exit with no LTP | rejected | limit at the bid, never a market order (*new*: per-broker exit rule) | — |
| X4 | Exit refused: session lost | SESSION | waits for login; the stop protects (built) | log in |
| X5 | Exit fills better than the limit | fill price | booked as filled (positive) | none |

### End of day and after
| # | Scenario | Detection | A | Y |
|---|---|---|---|---|
| D1 | Square-off fails at 15:15 | reconciliation at 15:20 | retry chase, then alert; MIS cut-off at the broker (*new*: alert) | flatten by hand |
| D2 | Our fills ≠ the broker's trade book | 15:45 reconciliation | ledger difference; next LIVE day blocked until acknowledged (built) | look at both and acknowledge |
| D3 | Charges ≠ the contract note | weekly import (*new*) | report | update the charges file |
| D4 | Daily loss limit hit | ledger | entries refused (built); flatten or not = D7 | — |

### Positive scenarios that still need handling
- **A trend day where the book cannot take our size.** The allocator and the depth cap leave money on the table.
  That is correct: buying it would move the price against us.
- **One account fills and another is refused** (funds, login, share). The accounts diverge, and that is correct: each
  account's P&L is its own, and the divergence report explains each difference.
- **An exit decision for a position that an account's broker stop already sold.** Skipped, recorded (built).
- **A winning streak raising COMPOUNDING equity.** Size grows only as fast as the ladder allows (section 5). Equity
  growth must not skip a ladder step.

## 5. Order of work and gates (calendar estimates, honest)

| Step | Work | Size | Gate before the next step |
|---|---|---|---|
| **K1** | Kite READ_LIVE DRY_RUN: real quotes and depth, funds, order and trade books, instrument file; the 08:30 mapping check; the login board; off-GCP watchdog; follower-risk v4 on for multi-account tenants | 3–4 days build + 5 sessions | 5 sessions: no mapping difference, no session failure, every decision followed or explained, the divergence report clean, the watchdog proven by stopping the worker once |
| **E0** | Egress spike, after hours, on your go-ahead (D10): narrow `zt-nat` to the primary range; one test range, alias, NAT gateway and Docker network; a throwaway container proves its calls leave from the test IP and everything else from `34.14.137.170`; then remove the test pieces | 1 evening | the test IP seen by two echo services; zt-v2 and trading-core still on `34.14.137.170` |
| **E1** | `acct.broker_egress` (V28), `BrokerEgress`, per-account `HttpClient` in the Kite gateway, the 09:00 check, reject `EGRESS`, the two-address IT | 2–3 days | full build green; the live set replays identically |
| **E2** | `bin/egress` provision / verify / retire, account page + login board fields, runbook | 2 days | ZERODHA-1 provisioned for real: its own IP verified for 5 sessions in DRY_RUN |
| **K2** | Kite LIVE pilot (**your switch**; ZERODHA-1's own IP registered at Kite): canary each morning, 1 lot, MIS, 1–2 strategies, max 2 positions | 10 sessions | no unreconciled fill; every order tagged; live slippage per trade measured against PAPER |
| **K3** | Kite ladder 10 → 25 → 50 → 100 % of allocation | 5 sessions a step | live P&L per trade within the measured slippage band |
| **U1** | Upstox order adapter + simulator + recorded-response tests; the approve-on-the-phone login (3.10); READ_LIVE on a **family member's** Upstox account with its own app | 1–1.5 weeks + 5 sessions | the same as K1 |
| **U2** | Upstox LIVE pilot (that member's own switch, or yours as ADMIN with their consent) | 10 sessions | the same as K2 |
| **N1** | Kotak Neo: the TOTP + MPIN login page on the order host; tag / idempotency answered; adapter + simulator; READ_LIVE | 1.5–2 weeks + 5 sessions | the same as K1, plus a deliberate wrong-IP login proven to fail cleanly |
| **N2** | Kotak LIVE pilot | 10 sessions | the same as K2 |
| **M1** | Staggered multi-account slicing, contract-note import, tax ledger per person, the secondary IP and standby host drill | about 1 week | one full drill: GCP stopped mid-session in DRY_RUN, positions protected, the watchdog alerts, the standby takes over |

**Calendar:** Kite live at 1 lot in about 4 weeks (E0–E2 add about a week). Three brokers live at 1 lot in **8–10 weeks**, if nothing goes badly
wrong. Steps overlap only where safe: the Upstox and Kotak adapter code can be written during K2/K3, but their LIVE steps
wait for Kite's ladder to be clean.

**What would make me say stop:**
- any wrong-contract fill;
- any short option position created by accident;
- any unreconciled difference not explained within a day;
- live slippage that eats more than half of paper's per-trade edge.

## 6. Decisions needed from you

| # | Decision | My default |
|---|---|---|
| D1 | Which strategies go live first | ecr only (the only one positive in both bar years); mac second; not odm (A-297 pending), not etr until its lottery profile is accepted knowingly |
| D2 | Allocation per account, FIXED or COMPOUNDING | FIXED for the pilot |
| D3 | Whose Upstox and Kotak accounts (your primary Upstox cannot trade: one app, zt's) | name the family member and the broker account for each |
| D4 | Secondary static IP per account, and a standby host | standby host yes, before K2 (it keeps each account's IP by taking over its alias range); a secondary IP per account only if the 7-account quota is raised |
| D5 | MIS for all live positions; strategies holding past 15:15 not followable live | yes |
| D6 | Ratcheting broker stop (3.5) as a registered test | test it on tick days, then decide |
| D7 | Daily loss limit: entries only (today) or entries + flatten (the 24 Oct plan) | entries only + alert; flattening locks in the worst print |
| D8 | Who logs in each morning for each family account | each member themselves; you as ADMIN can only see the board |
| D9 | One static IP per broker account, or one shared IP | **decided 2 Nov: one per account** (3.9) |
| D10 | Narrow `zt-nat` to `zt-subnet`'s primary range (shared infrastructure, zt's IP unchanged) | yes, in E0, after hours, zt-v2 egress checked before and after |
| D11 | Who runs `bin/egress` | you, or Claude when you ask, after hours only; never the application itself |
| D12 | Fully automatic broker login (password + TOTP secret stored on the server) | **no** (3.10): against Zerodha's stated rule and a single point of account takeover; Kite gets reminders, Upstox the official approve-on-the-phone flow |

## 7. What I will and won't do
- I build, test, simulate and deploy (after hours, on your go-ahead). **I never switch an account to LIVE, never
  place a canary or a real order on my own, never enter a password, TOTP, MPIN or API secret, and never handle money
  transfers.** Logins and the LIVE switch are yours.
- zt-tiger-v2 stays untouched: its Upstox app, token and containers.
- Every adapter merges only after the long-path regression (the live set and shadows replay identically) and the
  broker's simulator fault suite pass.

Sources (checked 1 Nov 2026):
- [Kotak Neo static IP rules](https://www.kotakneo.com/platform/kotak-neo-trade-api/static-ip-details/)
- [Upstox: SEBI/exchange mandates from 1 Apr 2026](https://community.upstox.com/t/important-new-sebi-exchange-mandates-for-api-trading-effective-1st-april-2026/14822)
- [Zerodha: SEBI algo changes April 2026](https://inthemoneybyzerodha.substack.com/p/sebi-algo-trading-changes-april-2026)
- [Upstox Python SDK (v3 place, algo id)](https://pypi.org/project/upstox-python-sdk/)
- [QuotaGuard on Upstox X-Algo-Name](https://www.quotaguard.com/blog/configure-upstox-algo-trading-my-apps)
- [Kotak Neo SDK (login steps)](https://pypi.org/project/kotakneoapi/)
