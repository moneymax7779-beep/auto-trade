# Review of the branches merged into main on 16 Nov 2026

Scope: everything merged into `main` today: reconciliation release (b424ce3), multi-broker validation and production
gaps (fe78b0a, 7c1f4a3, 97909a2, 66fdfc9, 99bb94c, ca16791 → d8f37b7), chart orders incl. UI (d3da68c → 18e3a69),
Telegram controls T0+T1 (5418b6d → 8f3e98a) and the Kotak/Kite listener race fix (8763c75 → acc1545).
Five independent read-only reviewers, one per area; every finding below was checked against the code before it was
acted on. Every fix has a test that fails on the old code and passes on the new (checked by reverting each fix).

Status: fixes are on branch `claude/intelligent-ishizaka-12b5ac`, **not committed, not merged, not deployed**.

## Fixed

| # | Area | Sev | Finding | Fix | Test |
|---|------|-----|---------|-----|------|
| F1 | Telegram | HIGH | `/flatten` and `/exit` sold on a LIVE account: the plan's LIVE gate ("nothing runs against a LIVE account until the user asks by name") was not in the code | `refusal()` refuses FLATTEN/EXIT on LIVE unless `autotrade.execution.telegram.live-exits` (env `AUTOTRADE_TELEGRAM_LIVE_EXITS`, default **false**); `/kill` still works on LIVE | `TelegramCommandsIT.nothingIsSoldOnALiveAccountUnlessLiveExitsIsOnButAKillStillWorks` |
| F2 | Canary | HIGH | A canary whose tag did not round-trip (the defect a canary exists to find) left its real LIMIT BUY working, with no cancel, no kill switch, no alert | `secure()` cancels by the order id the place returned and always reports a possible leftover (kill switch + CANARY_LEFTOVER alert) | `CanaryTest.aTagThatDoesNotRoundTripIsCancelledByItsOrderIdAndReportedAsALeftover` (all 4 brokers) |
| F3 | Chart orders | HIGH | One failed DB write on OPEN→EXITING switched the chart stop/target off for the rest of the day (marked "acted" before running, never cleared) | On a failed STOP/TARGET the mark is cleared and it fires again after `EXIT_RETRY` (5 s); a failed entry is not retried (safe side) | `ChartOrdersIT.aStopWhoseWriteFailedFiresAgainAfterAPause` |
| F4 | Reconciliation | MED | A released difference was accepted in both directions by matching text; "broker holds less than ours" (our stop/exit could oversell) stayed accepted all day | Only "broker holds more than ours, ours ≥ 0" (a hand buy, the 16 Nov case) can be accepted; Release dialog text updated | `BrokerVenueIT.aReleaseNeverAcceptsTheBrokerHoldingLessThanOurs` |
| F5 | Multi-broker | MED | Only the egress refusal engaged the kill switch and alerted; any other LIVE refusal (broker taken out of `live-brokers`, LIVE switch off, …) left a LIVE account with positions silently stranded | Every LIVE refusal with open positions engages the account switch and audits `LIVE_ROUTE_REFUSED` (sent on Telegram) | `MultiBrokerLiveIT.aLiveAccountWithAPositionRefusedItsRouteIsKilledAndAlerted` |
| F6 | Multi-broker | MED | Kite's product comes from the rules file and `requireProducts` compared it with itself: `product: NRML` would have sent every LIVE order NRML (no broker auto square-off) | Rules load refuses any product other than each broker's intraday one (MIS / I / MIS / MIS) | `BrokerRulesTest.otherMistakesAreRefusedAtLoad` |
| F7 | Chart orders | MED | The tick that triggers a breakout could already be at the target (target allowed 2 pts from entry, no-chase 8): bought then sold at once, spread and charges twice | Such an entry tick voids the order as a chase (live engine and replay) | `ChartLinesTest.aBreakoutWhoseEntryTickIsAlreadyAtTheTargetIsNotBought` |
| F8 | Gateways | MED (latent) | All four gateways' `poll()` did put-then-restore on `lastSeen` unlocked: overlapping polls could report the same FILLED twice with its fill quantity | `poll()` serialised on a private lock | covered by MultiBrokerLiveIT (20/20) |
| F9 | Kotak/Kite | — | (earlier today) listener registered twice → every report twice | merged as acc1545; tests hardened (blocked on the session's own monitor, always released, daemon threads) and a Kite case added | `MultiBrokerLiveIT.k*ListenerAdded*` |
| F10 | Telegram | LOW | A flatten while the worker had not started engaged no kill switch although the card promises one | The account switch is engaged first (idempotent) | existing flatten tests |
| F11 | Telegram | MED | Flatten card said "every open position sold"; positions opened by hand in Kite are not touched | Card and runbook say so | — |
| F12 | Canary | LOW | The modify price could equal the best bid (joins the bid, can fill) | Both prices must be strictly below the bid | `CanaryTest.refusesBeforeSendingAnything` |
| U1 | UI chart | MED | A draft and the draw tool carried over from NIFTY to SENSEX (and replay↔live); arming it took SENSEX's one working slot | `LiveDay` keyed by index and mode | tsc |
| U2 | UI chart | MED | `pointercancel` committed a line drag (touch scroll moved a stop); 2-unit threshold let a sideways pan drift a line; a pinch left a ghost line | Cancel aborts; 5-unit mostly-vertical threshold; pinch clears the drag; release price read from the release point | tsc |
| U3 | UI chart | MED | "Exit now" discarded the server's result ("not exited: …", "queued …"): a failed exit looked done | The result is shown, red when nothing was sold | tsc |
| U4 | UI manual | MED (real money) | The LIVE confirm token followed the latest 5 s poll, so a card opened on PAPER could send a LIVE order after the account switched; the price shown never aged | The card keeps the mode and quote time it opened with; a changed mode or a quote over 30 s needs a fresh BUY click | tsc |

## Not fixed: need your decision or a design change

| # | Sev | Finding | Why not fixed here | Suggested fix |
|---|-----|---------|--------------------|---------------|
| D1 | **HIGH (real money, ZERODHA-1)** | LiveGuard's "across all products" figure comes from **today's order book only**. An NRML lot carried overnight and sold by hand today nets out our MIS buy of the same contract → our exit and stop are refused (`PENDING_BROKER_HOLDS_LESS`), no alert; unmanaged until Kite's 15:26 square-off | Changes the LIVE guard's semantics on the real account; needs an all-products positions read (Kite `positions` net) and a careful test plan | Take the all-products figure from the positions endpoint (incl. overnight), keep the order book for "our product" and working sells |
| D2 | **HIGH (real money, ZERODHA-1)** | A hand NRML sell of our MIS long: positions match on MIS, net across products is 0, so no reconciliation difference and no NET_SHORT. The resting MIS stop (or Kite's MIS square-off) then leaves an NRML short overnight, unalerted | Same as D1; the right action (cancel our stop? alert only?) is your call: Kite will square off the MIS leg regardless | Alert at once (SPLIT_PRODUCT) when all-products holding < our product's holding, and cut our resting sells |
| D3 | HIGH (before any Kotak/Upstox/Groww canary) | The canary isn't fenced from the account's own trading: any position change in its contract during the run is treated as its fill and sold back (a strategy's or your hand trade) | Needs a fence between the canary and the trading loop | Count only the canary order's own fills; refuse entries on the account while a canary runs |
| D4 | MED | `follower-risk.v8.yaml` (from the manual-trade work) is older and weaker than v11 (no open-loss ₹10K stop, fewer strategies), yet the "allow manual LIVE" messages point to it | A risk file change is a new version and a LIVE-policy choice | `follower-risk.v13` = v11 + `manual` in `live.strategies`, messages point to it |
| D5 | MED | Refused routes are cached until restart: an egress address that appears seconds after start leaves the account without exits all day | Route lifecycle change | Don't cache refused routes; retry on a timer |
| D6 | MED | Chart orders arm even when no engine is running (engine off, session restarting) | Needs an engine heartbeat | Heartbeat row; refuse arm/move when stale; show it |
| D7 | MED | The ticket's breakout/pullback text uses a quote up to 10 s old; the server may arm the other kind | API change | Send the kind shown; server refuses a mismatch |
| D8 | MED (suspected) | Telegram offset: after a week of silence Telegram may restart update ids below ours, dropping commands silently | Not reproducible here | Poll once without offset when the last update is > 6 days old or the bot id changed |
| D9 | LOW | Square-off skips LIVE accounts that hold positions but have no lease (refused route); order budget is in-memory (resets on restart); probe answer files not owner-only; Kotak swap sets the session after swapping the client; Telegram poller: commands on with no token raises no alarm, an `Error` kills the thread; chart engine can leak a second instance if `start()` throws after it; Exit now on a fill that lands later is not retried; chart exits sell the oldest manual position of the index, not the order's own | Small, independent | Listed for a follow-up pass |
| D10 | INFO | As deployed, B1/B2 egress cannot pass (the execution container is on a bridge network and `AUTOTRADE_EGRESS_ACCOUNTS` is not passed): it fails closed, so safe, but needs host networking before E0/E2 | Infra | — |

## Checked and fine

PAPER-only enforcement of chart orders (arm, move, trigger); entry idempotency (unique keys, compare-and-set on version);
Telegram sender checks, nonce, single-use confirm, 60 s expiry, restart handling, token never logged, off by default at
three levels; egress fails closed; read/simulator configs can never place; canary authority (admin + typed phrase +
per-account lock); order budgets never block exits; V38/V39 migrations don't conflict; per-broker square-off timing.
