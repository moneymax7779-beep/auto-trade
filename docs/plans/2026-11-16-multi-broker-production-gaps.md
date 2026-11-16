# Multi-broker LIVE: what is missing for production (16 Nov 2026)

This builds on the validation of 16 Nov (`docs/studies/2026-11-16-multi-broker-adapter-validation.md` in the research
checkout) and the review fixes on `fix/broker-validation-16nov`. The 8 Nov plan's gates still apply: no broker goes LIVE
without its READ_LIVE sessions, its canary and the user's switch.

## A. Built in code now (no outside dependency)

| # | Gap | Why it matters | Work |
|---|---|---|---|
| A1 | Per-broker trading-day rules (S7): product, last LIVE entry, our square-off time | Each broker squares MIS off at its own time; one global 15:10/15:20 and a hard-coded "MIS" are wrong for Upstox (product `I`) and any broker that cuts off earlier | versioned `config/brokers/broker-rules.v1.yaml`; the venue, guard and square-off read it; square-off runs per broker at its own time |
| A2 | LIVE brokers by configuration (S4) | Adding a broker needs a code change in several places; `AccountAdminController` still hard-codes ZERODHA | one `LiveBrokers` from configuration (default ZERODHA), still requiring the adapter's `live()`; admin uses the adapter list |
| A3 | Per-broker costs (S8) | One costs file for every broker; brokerage differs per broker | per-broker costs file in the broker rules; P&L charged by the account's broker |
| A4 | Broker-neutral simulator access (S3) | `BrokerConnections.simulator()` casts to `KiteSimulator` | broker-neutral handle |
| A5 | Parallel square-off integration test | Untested since the 16 Nov change | two-account IT with one slow lane |
| B1 | Per-account egress address in every broker HTTP client (E1 code) | Since 1 Apr 2026 orders must come from the IP registered for that client; Kotak also needs the login from that IP | `localAddress` per account on every HTTP client (orders, reads and the broker's login), from configuration; fails closed when the address is not on this host |
| B2 | Static-IP LIVE gate | A broker that needs a registered IP must never send from the shared NAT IP | LIVE refused for such a broker without the account's egress |
| C1 | LIVE connections for Kotak, Upstox and Groww | `live()` returns null | `live()` behind LiveGuard, token read per call, state kept across a session swap, the lot size from the checked instrument list, an explicit LIVE configuration (read configs still can never send) |
| C2 | Per-user order budgets for Upstox and Kotak | Upstox counts per user; ours is per gateway and resets on rebuild | shared per broker user, as Kite's per API key |
| D1 | Canary command | The plan's step E is manual | a command that, for one account, buys 1 lot far below the market, reads it back by tag, modifies, cancels and records each step; run only by the user's explicit request |
| D2 | READ_LIVE response recorder | Contract tests need real, non-empty responses | the READ_LIVE probe saves redacted raw responses per broker and day for contract tests |

## B. Needs the user or outside parties (not code)

| # | Gap | Owner |
|---|---|---|
| E0/E2 | Reserve one static IP per LIVE account, the per-account NAT and routes on GCP; narrow `zt-nat` to its primary range (D10, shared infra, after hours) | user's go-ahead; `bin/egress` run by the user or by Claude on request |
| R1 | 5 READ_LIVE sessions per broker on a real logged-in account (tag round-trip, product code in the book, status words, empty-book shape, Kotak expiry offset, Groww page size) | the member logs in each morning |
| R2 | Groww API subscription (D13) | user |
| R3 | An Upstox LIVE user who is not BM6462 (D14) | user / family member |
| R4 | Morning canary per broker, then a 1-lot pilot, one broker at a time | user's switch |

## Status (16 Nov 2026, branch fix/broker-validation-16nov at the commit adding this section; not merged into main, not deployed)

All of A1–A5, B1–B2, C1–C2 and D1–D2 are built. Build of broker-api, marketdata-store and every dependent module:
686 tests, 0 failures, 0 skipped, including a Spring context start (ExecutionContextIT).

| Item | Where | Notes |
|---|---|---|
| A1 per-broker rules | `config/brokers/broker-rules.v1.yaml`, `BrokerRules`, minute square-off tick | Zerodha unchanged (MIS, 15:10, 15:20). Kotak 15:05/15:15 against a cut-off of 15:20 that is UNCONFIRMED. Groww 15:05/15:15. Upstox 15:10/15:20. Kotak- and Groww-typed accounts square off at 15:15 even in PAPER. |
| A2 LIVE brokers | `autotrade.execution.live-brokers` (default ZERODHA), one `LiveBrokers` for routes and pages | A LIVE route needs both the setting and the adapter's `offersLive()`; only Zerodha's is true |
| A3 costs | per-broker `costs_file` | all four still on costs v2 (not checked against each broker's pricing) |
| A4, A5 | broker-neutral `simulator()`; parallel square-off IT | |
| B1 egress | `autotrade.execution.egress.accounts` (`id=IP`), every client and login of the account | fails closed; with open positions → kill switch + EGRESS_REFUSED alert |
| B2 static-IP gate | `required-brokers` = KOTAK_NEO, UPSTOX, GROWW (the setting can only add) | Zerodha not required (shared NAT pilot) |
| C1 LIVE connections | Kotak/Upstox/Groww `live()` behind LiveGuard; explicit LIVE config kinds | not tradable: `offersLive()` false; usable only by the canary; BM6462 refused |
| C2 budgets | per user: Kite 350/min, 4,500/day; Upstox 450/min, 1,800/30 min; Kotak 200/min (assumed); Groww 250/min | only buys are ever refused |
| D1 canary | `POST /api/execution/accounts/{id}/canary` (202 + run id), `GET …/canary/{runId}` | platform admin who manages the tenant, confirm phrase, 09:20–15:00 trading days, no open position or working order; a leftover engages the kill switch and alerts (CANARY_LEFTOVER, also for PAPER-mode accounts) |
| D2 recorder | `autotrade.execution.read-live-record.accounts` | redacted, capped, bounded queue; Zerodha's READ_LIVE route now probes too |

To make Kotak, Upstox or Groww tradable after section B's gates:
1. Set `offersLive()` to true in that adapter (a code change, reviewed).
2. Add the broker to `AUTOTRADE_LIVE_BROKERS`.
3. Give the account its egress address.
4. Switch the account LIVE.
