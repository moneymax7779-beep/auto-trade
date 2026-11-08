# LIVE order adapters for Upstox, Kotak Neo and Groww (8 Nov 2026)

A delta on [the multi-broker plan of 1 Nov](2026-11-01-multi-broker-live-plan.md). That plan's order of work, gates,
failure matrix and decisions D1–D11 stand. This file records what the 8 Nov research answered, adds Groww (not in the
1 Nov plan), lists the shared code that is still Zerodha-only, and sets the build order for the three adapters.

## 0. The honest summary

1. **Kite is the only adapter, and it is the only one that has ever sent a real order** (ZERODHA-1, LIVE since 4 Nov,
   1 lot, from the shared NAT IP 34.14.137.170). Everything below for the other three brokers is research and
   simulator work until a real account proves it; no web page or SDK replaces reading our own orders back from the
   broker.
2. **Every broker now has a client order tag we can search by**, which was the blocker for Kotak (1 Nov plan, E3):
   - Upstox `tag` (≤ 40 characters), returned in the order book and accepted by order history (`?tag=`).
   - Kotak `ig`, echoed back as `GuiOrdId` in the order book, history and trades. **Source: OpenAlgo's production
     plugin and the SDK's optional `ig`; Kotak's own docs do not describe it.** It must be proven on a real account
     (READ_LIVE + canary) before Kotak can go LIVE.
   - Groww `order_reference_id` (8–20 alphanumerics, ≤ 2 hyphens, duplicates refused with GA007), with a
     status-by-reference endpoint. **Our tags can be shorter than 8 characters** (`F12E1`): Groww needs a padded form.
3. **Static IP per account (E0–E2) is still not built, and it is now a hard gate for all three new brokers**:
   - Upstox: one IP per *user* (all apps), a change logs every session out, **no sharing between customers**
     (Upstox staff, Apr 2026). The Upstox user zt-tiger-v2 logs in with must never be a LIVE account here, or
     registering an IP logs zt out.
   - Kotak: the session must be *created* from the IP that sends orders (error 1037); login and orders must leave
     through the same account IP.
   - Groww: orders only from a registered static IP; one IP per client (family may share).
4. **Market orders are gone or converted at all three** (Upstox: protection required; Kotak: converted to a protected
   limit, rejected without an LTP; Groww: MARKET exists but protection is undocumented). Our venue already sends
   limit orders only, so this is a constraint we already meet, not new work.
5. **Groww's API is paid** (₹499/month + GST, per Groww's blog) and its order-update feed is NATS over WebSocket,
   documented only in SDK source. Groww goes last.
6. **Three adapters is three times the failure surface.** The 1 Nov advice stands: code can be written in parallel,
   LIVE goes one broker at a time, each after its own READ_LIVE sessions and canary.

## 1. Shared code that is still Zerodha-only (from the 8 Nov code map)

A new adapter is one `BrokerAdapter` (`dryRun()` + `live()`) returning a `BrokerConnection`; `BrokerVenue`,
`LiveGuard`, stops, recovery, reconciliation and kill switches are broker-neutral. These places are not:

| # | Where | Today | Change |
|---|---|---|---|
| S1 | `ExecutionLoop` / `ExecutionService` | one `ZerodhaDryRunAdapter`, one `kiteApiBase` | a list of adapters, one per registered broker, each with its own base URL |
| S2 | `BrokerSessions.Access(apiKey, accessToken)`, `KiteSessionSource` | Kite-shaped; empty for other brokers | a session per broker: Kotak needs token + sid + baseUrl; Upstox and Groww a bearer token |
| S3 | `BrokerConnections.simulator()` | casts to `KiteSimulator` | broker-neutral simulator handle |
| S4 | `WorkspaceController.LIVE_BROKERS`, `LiveSwitchController:81`, `AccountAdminController:258-273` | `ZERODHA` written in several places | one `LiveBrokers` set in the execution module, read everywhere; a broker joins it only after its gates |
| S5 | `RateLimiter` (package-private in broker-zerodha), `KiteHttp` error mapping | Kite only | a shared limiter and a per-broker `BrokerException.Kind` mapping |
| S6 | `KiteMappingCheck` | Kite instruments only | the same check per broker against `ref.instrument` (expiry, strike, type, lot, tick) |
| S7 | Product MIS, 15:10 last entry, 15:20 square-off | Kite's MIS cut-off | per-broker cut-off (*verify* each broker's MIS auto square-off time) |
| S8 | Costs `india-index-options-costs.v2` | one model for every broker | a charges file per broker |
| S9 | Messages ("cancel them in Kite", "no Kite session") | Kite wording | broker name from the account |
| S10 | Egress | one NAT IP for everything | E0–E2 of the 1 Nov plan |

## 2. Each adapter (what the research says, to be proven in READ_LIVE)

| | Upstox | Kotak Neo | Groww |
|---|---|---|---|
| Place / modify / cancel | `POST/PUT/DELETE api-hft.upstox.com/v3/order/{place,modify,cancel}` (v2 deprecated); place returns `order_ids[]` | `POST {baseUrl}/quick/order/rule/ms/place`, `/quick/order/vr/modify`, `/quick/order/cancel`; body `jData=<JSON>` form-encoded, all values strings | `POST api.groww.in/v1/order/{create,modify,cancel}` JSON, `X-API-VERSION: 1.0` |
| Tag | `tag` ≤ 40 | `ig` → `GuiOrdId` (*prove*) | `order_reference_id` 8–20 (pad ours) |
| Book / trades / positions | `/v2/order/retrieve-all`, `/v2/order/trades/get-trades-for-day`, `/v2/portfolio/short-term-positions` | `/quick/user/orders`, `/quick/user/trades` (`tok` empty: map by symbol), `/quick/user/positions` (strings) | `/v1/order/list?segment=FNO` (paged ≤ 100), `/v1/order/trades/{id}`, `/v1/positions/user?segment=FNO` |
| Stop | `SL` with trigger | `SL` (SL-M refused for algo sessions) | `SL` with trigger |
| Instrument key | already ours (`NSE_FO\|token`) | scrip master `pSymbol` (= exchange token, *prove*), `pTrdSymbol` read, never built; nse_fo expiry epoch + 315511200 (sources differ by 30 min) | CSV `exchange_token` + `trading_symbol` |
| Session error | UDAPI100050 / 401 | HTTP 401/403, stCode 1003; 1037 = wrong IP | HTTP 401 |
| IP error | UDAPI1154 / 403 | 100008 | undocumented |
| Order rate | 10/s | 10/s (scope unclear) | 10/s, 250/min |
| Updates | portfolio stream WebSocket / webhook | `wss://<baseUrl>/realtime`, no trade messages | NATS WebSocket (SDK only) |

Polling the order book stays the source of truth for every adapter (as for Kite); a push feed is an optimisation for
later.

## 3. Build order

| Step | Work | Gate |
|---|---|---|
| A | Shared refactor S1–S5, S9 with no behaviour change: the Kite route, its tests and the live set replay identically | full build green; `LiveIT`, `BrokerVenueIT`, `ReadLiveIT` unchanged |
| B1 | **Kotak** gateway + `KotakSimulator` (fault menu as Kite's plus 1037 wrong IP) + gateway tests + DRY_RUN adapter; `live()` returns null | simulator fault suite; BrokerVenueIT's failure matrix run against the Kotak simulator |
| B2 | **Upstox** gateway + simulator + tests + DRY_RUN adapter; `live()` null | the same |
| B3 | **Groww** gateway + simulator + tests + DRY_RUN adapter; `live()` null | the same |
| C | READ_LIVE (DRY_RUN with `dry_run_market: BROKER`) per broker on a real logged-in account: real book, trades, positions, funds, instrument file, mapping check; orders go to the simulator | 5 sessions each; recorded real responses become contract tests; tag/`GuiOrdId` and status strings confirmed |
| D | E0–E2 (static IP per account) | 1 Nov plan gates |
| E | Per broker, on your switch: morning canary (1-lot buy far from the market, read by tag, modify, cancel), then a 1-lot LIVE pilot | 1 Nov plan K2 gates; one broker at a time |

Steps A–B can be built now. C needs each account logged in during market hours (reads only). D needs your go-ahead
after hours (D10, D11). E needs your explicit switch per broker and never happens on my own.

## 4. Decisions needed

| # | Decision | Default |
|---|---|---|
| D12 | Order of the three adapters | Kotak (account ready, tag to prove), Upstox, Groww (paid, feed undocumented) |
| D13 | Groww subscription (₹499/month + GST) for READ_LIVE | only when B3 is done |
| D14 | Which Upstox user goes LIVE (never zt's) | **not BM6462**: BM6462 is the primary Upstox user zt-tiger-v2 logs in with (and satyatrader's Upstox account is a second app on that same user); Upstox's static IP is per user and any IP change logs out every session, so registering it would log zt out. A family member's own Upstox user, after D. BM6462 is deny-listed by default (`AUTOTRADE_UPSTOX_DENIED_USER_IDS`); zt's app key goes in `AUTOTRADE_UPSTOX_DENIED_API_KEYS` on the host |
