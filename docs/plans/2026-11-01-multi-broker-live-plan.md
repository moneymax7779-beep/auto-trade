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
- **One egress IP serves all three brokers.** The rules allow one static IP across brokers. Today that is Cloud NAT
  `34.14.137.170`, which also carries zt-autotrade's traffic.
- **Register a secondary IP on day one.** It is the only way to fail over to a standby host without waiting a week.
- **Kotak needs special care with its IP-bound session.** The login must run on the order host, through the same NAT,
  never from your phone or the Mac.
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
  ledger. They share only the static IP and the signal group (V27 share).
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
- **A secondary static IP registered** on all three brokers for a standby host, so failover does not wait a week.

## 4. Fire-fighting: what happens with real trades

**A** = automatic action, **Y** = your action. Rows marked *new* need building; the rest exist and are tested
against the simulator only.

### Before the open
| # | Scenario | Detection | A | Y |
|---|---|---|---|---|
| P1 | Login not done by 09:10 | session status | account out for the day; listed at 08:30 and 09:10 (*new*: board) | log in or accept the day off |
| P2 | Kotak session created from the wrong IP (phone, Mac) | 1037 on the canary | account out; message says "log in through the app page" (*new*) | log in again through our page |
| P3 | IP changed or de-registered at the broker | 100008 / IP errors on the canary | account out (*new*: canary) | fix the IP registration (a weekly limit applies) |
| P4 | Instrument master disagrees (lot size changed, new expiry pattern, strike missing) | 08:30 mapping check | that contract blocked for that broker (*new*) | none, or check the exchange circular |
| P5 | Funds below allocation + buffer | funds API | entries refused for funds (built: `CapitalPolicy`) | add funds or lower the allocation |
| P6 | Broker or exchange outage at the open (it happens to every broker) | canary / 5xx | account out until a canary passes (*new*) | none |
| P7 | Yesterday's position still open (a missed square-off, an NRML leftover) | reconciliation at takeover | account kill (entries); alert with the broker view (built) | flatten in the broker app or adopt it |

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
| **K2** | Kite LIVE pilot (**your switch**): canary each morning, 1 lot, MIS, 1–2 strategies, max 2 positions | 10 sessions | no unreconciled fill; every order tagged; live slippage per trade measured against PAPER |
| **K3** | Kite ladder 10 → 25 → 50 → 100 % of allocation | 5 sessions a step | live P&L per trade within the measured slippage band |
| **U1** | Upstox order adapter + simulator + recorded-response tests; READ_LIVE on a **family member's** Upstox account with its own app | 1–1.5 weeks + 5 sessions | the same as K1 |
| **U2** | Upstox LIVE pilot (that member's own switch, or yours as ADMIN with their consent) | 10 sessions | the same as K2 |
| **N1** | Kotak Neo: the TOTP + MPIN login page on the order host; tag / idempotency answered; adapter + simulator; READ_LIVE | 1.5–2 weeks + 5 sessions | the same as K1, plus a deliberate wrong-IP login proven to fail cleanly |
| **N2** | Kotak LIVE pilot | 10 sessions | the same as K2 |
| **M1** | Staggered multi-account slicing, contract-note import, tax ledger per person, the secondary IP and standby host drill | about 1 week | one full drill: GCP stopped mid-session in DRY_RUN, positions protected, the watchdog alerts, the standby takes over |

**Calendar:** Kite live at 1 lot in about 3 weeks. Three brokers live at 1 lot in **8–10 weeks**, if nothing goes badly
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
| D4 | Secondary static IP and a standby host | yes, before K2 |
| D5 | MIS for all live positions; strategies holding past 15:15 not followable live | yes |
| D6 | Ratcheting broker stop (3.5) as a registered test | test it on tick days, then decide |
| D7 | Daily loss limit: entries only (today) or entries + flatten (the 24 Oct plan) | entries only + alert; flattening locks in the worst print |
| D8 | Who logs in each morning for each family account | each member themselves; you as ADMIN can only see the board |

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
