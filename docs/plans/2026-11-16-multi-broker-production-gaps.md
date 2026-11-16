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
