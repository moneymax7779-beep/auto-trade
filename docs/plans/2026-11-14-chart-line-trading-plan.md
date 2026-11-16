# Chart-line trading: draw the entry, stop and target on the chart, the platform executes (14 Nov 2026)

Status: **P0 built on branch `feature/chart-orders` (14 Nov 2026), not merged or deployed**; P1–P4 not started. See
"P0 as built" below for where it differs from this plan. User, 14 Nov: "i want to manually enter and exit in such a way that I will draw a
line in the chart for entry and exit and will also draw stop loss, so give me the best implementation plan."

How I read it:
- you trade by hand, as an index-options **buyer**, reading the index chart, futures volume and OI;
- you draw an **entry line**, a **stop line** and a **target line** on the chart and arm them;
- the platform watches the price, buys the option when the entry line is crossed, and sells it when the stop or the
  target line is crossed, with no clicking at the moment of the move;
- you can drag the lines while the trade is waiting or open (move the stop up, pull the target in), or exit at once.

Checked against main 95606b7: the chart (`TimelineChart.tsx`, lightweight-charts 5.2), the live push
(`LiveTicks`, `/api/live/ticks`), the decision path (`SignalDecision` → `FanOut` → `ExecutionLoop` → `OrderManager` /
Kite adapter), the order rules (`OrderRules`: marketable limits, exit chase, resting stop-limit), `SignalWakeup` and the
hand-exit endpoints in `AccountsController`. Latest migration on main: V37.

## 0. The honest summary

1. **The lines must live on the server, not in the browser.** If the browser watched the price, a closed laptop, a
   sleeping tab or a dropped Wi-Fi would leave you in a trade with no stop. The browser only draws and edits; the
   trading core holds every armed line in the database and checks it on every tick.
2. **You draw on the index, you trade the option.** An index stop ("NIFTY below 24,180") cannot be sent to Kite as an
   order: Kite's SL and GTT trigger on the option's own price only. So the index stop and target are watched by our
   server, and **every filled position also gets a resting stop-limit at the broker on the premium**, set a little
   beyond where the premium should be when the index reaches your stop. That is the safety net if our server, the feed
   or the network dies. The OMS already does this for every strategy; the chart order only sets its price.
3. **Almost everything below the chart already exists.** Marketable-limit entries, exit chasing, the resting stop,
   freeze slicing, the liquidity cap, kill switches, reconciliation, the ledger, Telegram and the wake-on-signal path
   (decision to Kite place in well under a second since 4 Nov) are built and running LIVE. A chart order becomes one
   more signal source, `manual`, addressed to one account. The new work is the line engine, the drawing layer, one
   table and the ticket.
4. **This is an execution tool, not an edge.** Our tests of chart reading as rules (A-391, A-400 level trader: +9.17 L on
   31 tick days but the second half −1.15 L) did not hold up. Your own judgement may do better than any rule we wrote,
   but only your record will show it. So the plan includes a journal from the first day (section 8), and it starts on
   PAPER-1. LIVE on ZERODHA-1 only when you ask for it, at 1 lot, as with every strategy.
5. **Rules check.** An order fired by our server when a price condition is met is an API order under the broker's algo
   rules (static IP, algo tag), the same as today's strategies. At a handful of orders a day it stays well under the
   orders-per-second threshold that needs exchange registration. Confirm with Zerodha before the LIVE phase; nothing
   new is needed for PAPER.

## 1. What you do on screen

### 1.1 Drawing a trade

On the Live page chart (NIFTY or SENSEX, 1- or 3-minute candles, futures volume underneath, OI tape and strike ladder
below as today):

1. Pick **Buy CE** or **Buy PE** in a small toolbar above the chart (hotkeys `C` / `P`).
2. Click the chart at your entry level. Three lines appear at once:
   - **Entry** (blue) where you clicked;
   - **Stop** (red), by default one 3-minute ATR away on the losing side;
   - **Target** (green), by default 2 R on the winning side.
3. Drag any line to where you want it. Each line shows its price on the axis and, next to it, what it means in money:
   `Stop 24,180 · prem ≈ 92 · −₹4,350`.
4. The **ticket** on the right fills itself in (1.2). Check it and press **Arm**.

A line is **dashed** while it waits, **solid** once you are in the trade, **grey** when it is done or cancelled. A line
can also be a **trendline** (two clicks, phase 2): its price moves with time, for sloping breakouts and channels.

### 1.2 The ticket

| Field | Default | Choices |
|---|---|---|
| Account | PAPER-1 | any account of your workspace; LIVE ones only once switched LIVE (V35) |
| Index | the chart's | NIFTY, SENSEX |
| Option | Buy CE / Buy PE (from the toolbar) | |
| Strike | **ATM at the moment the entry fires** | ATM ± n, a fixed strike, nearest premium to ₹X |
| Expiry | nearest weekly | next weekly; 0-DTE shows a warning with theta per minute |
| Size | **₹ risk** (e.g. ₹5,000) → lots worked out | fixed lots |
| Entry fires on | price crosses the line by the buffer (2 pts NIFTY, 6 pts SENSEX) | 1- / 3- / 5-minute candle closes beyond; break and retest (phase 2) |
| Stop fires on | price crosses the stop by the buffer | candle close beyond (the broker stop still covers a fast move) |
| Target | one line, exit all | T1 (half) + T2 (rest), stop to entry after T1 |
| Confirm with (phase 2) | none | futures volume ≥ k × usual for the minute; ATM ±3 OI flow on your side over 15 min; VWAP side |
| No-chase | skip if the first tick is more than 8 pts past the line, or the ask is more than 10 % above the estimate | numbers editable |
| Valid until | 14:45 | a time; "today"; cancel if the stop line is touched first |

Under the ticket, live, before you arm:

- the option's **premium now**, and the **estimated premium at entry, stop and target**, from delta, gamma and theta
  (the strike ladder already has IV and gamma per strike);
- **₹ risk** (entry to stop, plus 2 % for slippage and costs), **₹ reward**, **R:R**;
- **lots**, after the account's capital, liquidity cap (risk v12) and loss limit;
- whether each confirm filter would pass right now (green / red).

**Arm** asks once for a LIVE account: "Buy NIFTY 24200 CE at 24,250 ↑, stop 24,180, target 24,390, 2 lots, risk
₹4,350. Arm?". PAPER arms without asking.

### 1.3 While the trade waits or runs

- Drag a line to move it: the change is saved on release and applies from the next tick. Every move is recorded.
- Once in the trade the **entry line** is fixed (it marks your fill); stop and target stay movable.
- Buttons on the position: **Stop to entry**, **Exit half**, **Exit now**, **Cancel** (a waiting order only).
- The stop cannot be dragged past the current price (that would be an instant exit): it asks "Exit now instead?".
- Telegram on arm, fill (strike, price, lots), stop or target hit, and on any refusal.

## 2. What the lines mean, exactly

This is where hand-line tools usually go wrong, so each rule is written out.

### 2.1 Which price the lines are checked against

- **Index spot** by default: you draw on the spot chart, so the lines are checked against spot ticks.
- **Futures** as an option: for levels drawn on the futures chart, and automatically for SENSEX and NIFTY on expiry
  day after 15:15, when the spot index stops moving during the closing period (the 15:15–15:28 freeze seen in the
  expiry-close study).
- **Option premium** (phase 2, "premium mode"): lines drawn on the option's own chart, checked against its bid. Then the
  stop can be a true broker stop order, exact to the rupee. Useful for expiry-afternoon scalps where the premium moves
  apart from the index (IV, gamma).

### 2.2 Entry

The direction comes from the side and where the line sits when you arm:

| You arm | Line above the price | Line below the price |
|---|---|---|
| Buy CE | **breakout**: fires when price rises through the line | **buy the dip**: fires when price falls to the line |
| Buy PE | **buy the rally**: fires when price rises to the line | **breakdown**: fires when price falls through the line |

- **Cross** (default): the first tick at least the buffer beyond the line. The buffer stops one-tick pokes.
- **Candle close**: the 1/3/5-minute candle closes beyond the line; the order goes at the next tick. Fewer fake breaks,
  later and dearer entries.
- **Break and retest** (phase 2): cross, come back to within n points of the line, then move away again.
- **Void, not late**:
  - if the stop line is reached before the entry, the setup is void and cancelled;
  - if the first tick past the line is beyond the no-chase limit (a gap, a news spike), it is not entered;
  - if the engine was down when the line was crossed, it does not enter late on restart.
- The strike is chosen when the entry fires (ATM then), unless you locked it at arm.
- The order itself is today's entry: a limit at the ask plus the buffer ticks, sliced to the freeze quantity, and what
  has not filled after the entry timeout is cancelled. A part fill is the position.

### 2.3 Stop

- **Cross** (default): the first tick at least the buffer beyond the stop line → exit. The exit is today's: cancel the
  resting broker stop, confirm the cancel, then a limit at the bid minus the buffer, re-priced at the touch until
  filled. A stop and an exit can never both sell (built and tested in the OMS).
- **Candle close**: only a close beyond the line exits. This rides out wicks but can lose more, so the broker stop
  below stays in place either way.
- **The broker safety stop**: when the entry fills, a resting stop-limit sell goes to the broker at the premium expected
  at your index stop **minus a further 15 %** (editable). It fires only if our own stop did not act first: server
  down, feed gap, or a move too fast to chase. It is re-priced whenever you drag the stop line.
- **Minimum stop distance**: the ticket warns below half a 3-minute ATR. Stops inside the noise were the biggest single
  loss source in our own live record (14 Oct post-mortem).

### 2.4 Target and exits

- Target crossed → sell (all, or T1's share). After T1 the stop moves to the entry line (can be switched off).
- **Time exit**: 15:15 on normal days, as every strategy; on expiry day the same square-off as today's live set.
- **Kill switch** engaged on the account: waiting orders are cancelled, open ones keep their stops (an exit is never
  blocked).
- **Exit now** and **Exit half** use the existing position exit endpoint.

### 2.5 Trendlines (phase 2)

Two points (time, price). The price at any moment is the straight line through them, measured in **trading minutes**
(09:15–15:30), so a line drawn yesterday continues today without a jump across the night. The line extends to the
right; an entry, stop or target can each be a trendline.

## 3. How it fits the platform

```
 Live page chart ── draw / drag / arm ──► POST/PATCH /api/chart-orders ──► exec.chart_order (+ event log)
        ▲                                                                          │ NOTIFY
        │ SSE: line states, fills                                                  ▼
        └──────────────────────────────────────── trading core: ChartOrderEngine (every index / futures tick)
                                                       │ ENTER / EXIT decision, strategy "manual",
                                                       │ addressed to one account, context: chart_order_id,
                                                       │ stop_pct (broker safety stop), strike rule
                                                       ▼
                                 exec.signal_decision ─ NOTIFY ─► app-execution (SignalWakeup)
                                                       ▼
                       FanOut (one intent, to the chosen account) → ExecutionLoop → OMS / Kite adapter
                       (risk check, kill switches, marketable limit, resting stop, exit chase, reconcile, ledger)
```

### 3.1 Data (Flyway V38, on main)

`exec.chart_order`: id, tenant, broker account, created by, underlying, side (CE/PE), reference price (SPOT / FUT /
OPTION), strike rule, expiry rule, size (₹ risk or lots), entry line (a price, or two time-price points), entry
trigger (CROSS / CLOSE_1M / CLOSE_3M / CLOSE_5M / RETEST), buffer, no-chase limits, confirm filters (json), stop line,
stop trigger, targets (json: line, share), safety-stop margin, valid until, status, version, resolved contract, entry
and exit decision ids, created and updated times.

Status: `ARMED → TRIGGERED → OPEN → EXITING → CLOSED`, or `CANCELLED`, `VOID` (stop first, no-chase, engine down at the
cross), `EXPIRED` (valid-until passed), `REFUSED` (risk or kill switch said no).

`exec.chart_order_event`: every create, line move, arm, trigger, fill, exit and refusal, with the index price at that
moment and who did it. This is the audit and the raw material for the journal.

Every edit carries the version the browser last saw; a stale edit is refused and the browser reloads (two tabs, or
the engine having just triggered).

### 3.2 The engine (`ChartOrderEngine`, app-trading-core)

- Runs inside the live session, which already receives every index, futures and option tick in memory (`LiveTicks`
  gets the same ticks). No database read on the tick path.
- Holds the day's ARMED and OPEN chart orders in memory; reloads one on a Postgres NOTIFY from the API and every 5 s
  as a backstop.
- On each tick of the reference price: check entry (and confirm filters), stop, targets, valid-until. When one fires,
  it writes the status change and the decision in **one transaction**, so a decision is never written twice
  (idempotency key `chart:<id>:<version>:<ENTER|EXIT|T1>`).
- **Stale feed guard**: no tick for 3 s on the reference → no new entries, a red banner on the chart. Stops still fire
  on the first tick back, and the broker safety stop covers the gap.
- **Restart**: reloads from the table. An OPEN order whose stop was crossed while the engine was down exits at once;
  an ARMED order whose entry was crossed while down is VOID (never a late entry).
- **Replay**: the engine is a plain class fed by the same tick events, so the replay runner drives it on any tick day.
  The same code runs live and in replay (principle 2 of the implementation plan).

### 3.3 The execution side (small changes)

- **Addressed decisions**: today a decision fans out to every subscribed follower. A chart order goes to the one
  account you chose: `signal_decision` gets a nullable `target_account_id`; `FanOut` makes exactly one intent for it
  (or none if that account is off, idle or killed) and ignores subscriptions.
- **`manual` strategy id**: positions are keyed by strategy, underlying and leg, so a manual position never mixes with a
  strategy's position in the same index. The owner LIVE list and the account switches (V35) apply as to any strategy,
  so `manual` must be switched LIVE before a LIVE account can take it.
- **Stop price from the decision**: the resting stop's level comes from the decision's `stop_pct`, already read today;
  the engine sets it from the index stop (2.3). Dragging the stop sends a `MODIFY_STOP` decision (a new action next to ENTER, ADD,
  EXIT); the OMS re-prices the resting order with the gateway's existing modify call.
- **Hand trades get our tag**: orders placed this way carry our order tag and are reconciled, unlike hand orders typed
  in the Kite app (46 of 53 untagged on 12 Nov). This alone removes most of the reconciliation trouble hand trading
  caused on ZERODHA-1.

### 3.4 The drawing layer (ui)

- `TimelineChart` already uses lightweight-charts 5.2. Horizontal lines: `createPriceLine` (axis label, colour, dash).
  Trendlines and drag handles: a series primitive (the v5 plugin interface) drawing on the chart canvas.
- Drag: pointer events on the chart element, `coordinateToPrice` / `coordinateToTime`, chart scrolling paused while a
  handle is held, a snap to the nearest level (PDH/PDL, opening range, round 50s) within 3 pixels, Esc to cancel.
- State arrives over the existing `/api/live/ticks` server-sent-event stream as a second event type, so a fill or an
  exit shows on the chart within half a second, with the 30 s refresh as backstop. The server is always the truth: the
  browser shows a line as moved only once the server has saved it.
- Works the same on the Replay page (section 6).

### 3.5 API (app-execution, through the existing proxy)

| Call | Does |
|---|---|
| `POST /api/chart-orders` | create and arm (validated: stop on the losing side of entry, target on the winning side, size within limits) |
| `PATCH /api/chart-orders/{id}` | move a line or change a field (with `version`) |
| `POST /api/chart-orders/{id}/cancel` | a waiting order |
| `POST /api/chart-orders/{id}/stop-to-entry`, `/exit`, `/exit-half` | an open one |
| `GET /api/chart-orders?day=` | the day's orders and events for the chart and the journal |
| `GET /api/chart-orders/preview` | the ticket's estimates (premium at the lines, ₹ risk, lots) |

Access is checked as today (`Access`): only the account's own trader, or support with a grant.

## 4. Risk controls

- **₹ risk per trade** (from the ticket) and a **cap per trade** in the risk file (e.g. ₹10K on ZERODHA-1).
- **Daily loss**: manual trades count toward the account's existing loss limit (₹22K, open positions counted). Add a
  manual-only sub-limit (e.g. ₹10K) so a bad hand day stops hand trading without stopping the strategies, and the
  other way round only if you want it (decision D5).
- **At most 3 armed and 2 open** chart orders per account; **no adding to a loser** (a second entry in the same index
  and side while one is losing is refused).
- **Optional cool-down**: after two stops in a row, no new arm for 15 minutes. A discipline aid, off by default.
- **Capital**: a manual trade uses the account's free capital like any strategy. It can be refused when the strategies
  hold it all (the capital crowding seen in A-359). Decision D5 chooses between a reserved manual slice and first come.
- Spread, liquidity cap, freeze slicing, kill switches, reconciliation: unchanged, all apply.

## 5. Expiry-day specifics

- 0-DTE ATM premiums move 2–4× their delta-implied amount in the last hour (gamma), so after 13:30 the ticket's
  estimates widen and show a range, not one number.
- The ticket shows theta per minute for the chosen strike; a waiting entry on a 0-DTE option that has lost more than a
  set share of its premium since arming is voided (default 25 %).
- After 15:15 the reference switches to futures (2.1). The time exit follows the live set's expiry square-off.
- The ladder's walls and the 1 σ-to-close band can be shown as snap levels when drawing (they are descriptions, not
  signals: A-382 found walls do not forecast move vs pin).

## 6. Practice on recorded days (phase 3)

The Replay page plays one of our 31 own-capture tick days at 1×–20× speed. You draw and arm lines exactly as live;
the same engine fires them and the paper simulator fills them against the recorded order book. Results go to the
journal marked PRACTICE. Two uses:

- practice the tool and your setups on real days without risk;
- test the engine itself: a set of scripted chart orders (breakout, dip buy, gap through, stop first, engine restart,
  trendline) runs on fixed tick days in CI, and their fills must match the paper simulator's.

## 7. Phases

| Phase | Builds | Done when | Size |
|---|---|---|---|
| **P0 core** | V38 table and events; `ChartOrderEngine` (horizontal lines, spot, cross trigger, buffer, void rules, restart); addressed decisions in `FanOut`; `manual` strategy id; drawing and dragging horizontal lines on the Live chart; ticket with fixed lots; one target; broker safety stop; Exit now; PAPER-1 only | unit tests for every trigger rule; Testcontainers IT from arm to fill to stop on PAPER; a scripted replay on 3 tick days | ~4–5 days |
| **P1 trader** | ₹-risk sizing and the money preview; candle-close triggers; no-chase; valid-until; T1/T2; stop to entry; drag stop on an open position (broker stop re-priced); Telegram; manual loss sub-limit | one week of your own PAPER-1 use, every arm/fill/exit visible in the event log | ~3–4 days |
| **P2 professional** | confirm filters (futures volume vs usual, ATM ±3 OI flow, VWAP side); trendlines; break and retest; futures reference and the expiry 15:15 switch; premium mode; snap levels | each filter shown live on the ticket; trendline tests across a day boundary | ~4–5 days |
| **P3 practice and journal** | replay practice mode; journal page (section 8); entry and exit screenshots | 31 tick days playable; journal totals match the ledger | ~3 days |
| **P4 LIVE** | `manual` in the owner LIVE list; ZERODHA-1 at 1 lot; Zerodha algo-rule check | only on your explicit request, after P1 has run on PAPER | config + 1 day of checks |

P0 and P1 together are the useful tool (about 1½ weeks). Each phase is its own branch and merge; deploys to GCP after
market hours, as always.

## 8. Journal: your own record

Every chart order is stored with what you saw and did, so after 20–30 trades you can see your edge honestly:

- per trade: setup (breakout, dip, retest), index, side, strike, entry/stop/target lines, lines moved and when, fill
  prices, R multiple, MFE and MAE in R, time in trade, the confirm filters' state at entry, PRACTICE / PAPER / LIVE;
- totals by setup, by hour, expiry vs normal day, filters passed vs not;
- next to the strategies' results on the same days, so you can see whether hand trades add to the live set or crowd it;
- a screenshot of the chart at entry and exit (the strategy loop's screenshot code reused).

Two things worth watching from day one: how often you **move the stop away** (widening) versus up, and how the trades
where you **moved the target in** compare with leaving it. Those are where hand trading usually leaks.

## 9. Alternatives considered

| Option | Why not |
|---|---|
| Watch the lines in the browser | Dies with the laptop, the tab or the network. Never for a stop. |
| Kite GTT / OCO orders | Trigger on the option's own price, not the index; no candle close, no OI or volume filters; ATM cannot be chosen at trigger time. Kept only as the idea behind the broker safety stop, which we already place ourselves. |
| TradingView alerts by webhook | Another service and login in the order path, seconds of delay, no knowledge of our positions, kill switches or capital. |
| A separate manual-trading app | Would duplicate the OMS, risk, reconciliation and ledger, which are already proven LIVE. |

## 10. P0 as built (14 Nov 2026)

Built on top of the unmerged `feature/manual-trade` branch (9 Nov: manual BUY CE / BUY PE / EXIT), with main merged in.
That branch already had what section 3.3 planned to add: a `manual` decision with one intent for one account, never
copied by followers. So:

- **No `target_account_id` column.** Entries use `ManualOrders.buy` (now with lots, broker-stop % and an idempotency
  key); exits use the new `ManualOrders.exit` (one EXIT intent for the account, sold by the worker as any strategy exit:
  broker stop released first, then the chased sell). No new decision action (`MODIFY_STOP` is P1).
- **The engine** (`ChartOrderEngine`) is a plain class in the `execution` module, fed every index tick in memory by
  the core's live session (`SessionRunner`, next to the chart push), so tests drive it with the same code.
  `autotrade.chart-orders.enabled=false` turns it off. It does not poll `md.index_tick` (the capture writes it only
  every 5 s).
- **The chart** is the index page's own SVG chart (`SurgePanel`), not lightweight-charts as 3.4 said: lines are drawn
  there, placed by a click with Draw Buy CE / PE, and dragged by a handle. The ticket and order list sit above it
  (`ChartOrdersPanel`), on live NIFTY and SENSEX days.
- **API** (POST only, the UI proxy forwards GET and POST): `GET/POST /api/execution/accounts/{id}/chart-orders`,
  `.../{orderId}/move`, `/cancel`, `/exit`.
- **P0 limits:** PAPER accounts only; one working order per account and index (a unique index); fixed lots 1–10;
  horizontal lines, index spot, tick-cross entries; one target; moving an open order's stop does not re-price the broker
  stop yet (PAPER has none); a PAPER exit fills at the latest captured bid (up to ~5 s old).
- **Tests:** `ChartLinesTest` (every trigger rule, both sides), `ChartOrdersIT` (Testcontainers: breakout CE to stop,
  pullback PE to target with a trailed stop, refusals, stop-first and no-chase voids, a moved line winning over the
  engine's older copy, kill switch, Exit now, expiry). The UI was checked by hand on a past day's chart with the API
  mocked. It has not run on a live market day yet.

**Code review 15 Nov 2026** (each fixed, with a test):

- **Exit now no longer changes the order's status.** It used to set EXITING after the trader's sell had run. If that
  sell did not happen (no broker route, a lane timeout, a refused sell), the order stayed EXITING for good, the engine
  stopped watching its stop, and the index stayed blocked for a new order. Now the order stays OPEN, with its stop
  watched, until the engine sees the position closed.
- **Exit now on a waiting order that fired a moment earlier** used to answer "cancelled" while the buy went ahead. The
  cancel is now checked: if the order had already fired, the real exit runs.
- **Exit now between the trigger and the buy:** the engine checks for it before buying. If the buy was written anyway,
  it sells the position as soon as it fills.
- **Exit now on a finished order** is refused. It used to sell any manual position the account held on that index.
- **A buy or sell written but not recorded on the order** (an error after its commit, or a crash) is now found by its
  idempotency key. Before, the order was marked REFUSED, or stuck EXITING, and the position had no lines watching it.
  Orders that are truly unwritten are repaired after 15 s of no change.
- **A PAPER exit with no bid in the last 30 s is not written.** Before, it wrote an intent that the worker rejected,
  every tick. A failed or rejected exit now fires again no sooner than 5 s later.
- **Dragging only the stop or target of a waiting order** is no longer refused when the index is near the entry, and
  the entry kind is kept. A moved entry is still checked as a new order.
- **A status change with no reason clears the old reason** (an EXITING order showed the earlier failure's text).
- **The ternary NPE in the exit's event** (Exit now's sell left no event) is fixed.
- **UI:** an account the caller may only Exit on is listed. Buttons and drags follow the account's arm and exit rights.
  A dragged line stays where it was dropped until the server answers. The panel is hidden when the caller has no PAPER
  account.

**Still true after the review** (limits, not bugs):
- A line moved in the UI reaches the engine within 1 s. The API and the engine run in different processes and the
  engine reloads once a second.
- Arming and moving are checked against `md.index_tick`, which can be up to ~5 s old. The no-chase limit bounds what
  that lag can cost.
- A stop crossed while the buy is still filling (TRIGGERED) acts on the first tick after the fill, if the index is
  still past the line.

**Chart orders in a replay (15 Nov 2026, user: "in the replay i should be able to draw and run the replay").** A first
cut of phase 3 (section 6):
- `bin/replay-desk YYYY-MM-DD [speed=60] [pause-at=09:20] [strategies=off] [port=8098]` runs a paced replay of the own
  capture, pauses at the given time, and keeps serving after the day ends. Open `http://127.0.0.1:<port>/index/NIFTY`.
  It never runs migrations, and on this Mac it reads earlier days' history from `zt_archive` as the replay batches do.
- The NIFTY and SENSEX pages of a running replay show the same chart-order panel. Draw, drag, Cancel and Exit now work
  as live; the replay's controls (pause, speed, next entry or exit, run until) work with them.
- The orders run inside the replay (`ReplayChartOrders`, API `/api/replay/chart-orders`): they are checked on every
  replayed index tick at the replay's clock, and they trade the replay's own paper account (its OMS, risk limits and fill
  model on the replayed option quotes) as strategy `chart`. So the live limits above do not apply: a moved line counts
  from the next replayed tick, the spot is the replay's latest, and a stop crossed while the buy fills sells what it
  bought.
- One working order per index at a time. The lines live in memory for the replay's run; the trades are stored with
  the PAPER_REPLAY session, the lines are not (no PRACTICE journal yet).
- `strategies=off` stops the configured strategies' orders (they still decide, so their states show); `on` lets them
  trade the same paper account, sharing its capital.
- Checked on 13 Nov 2026 at 60×: a CE breakout voided when the stop came first (09:20:16); a 2-lot CE breakout, its
  stop dragged on the chart, bought 09:34:26 at 162.90, sold at the target 09:47:26, net +₹4,648.

## 11. Decisions for you

| # | Question | My recommendation |
|---|---|---|
| D1 | Lines checked against spot or futures by default? | Spot (you draw on spot); futures automatically after 15:15 on expiry; futures volume for confirmation |
| D2 | Stop on a tick cross or a candle close by default? | Tick cross with a buffer; candle close as a choice, always with the broker safety stop |
| D3 | Strike chosen at arm or when the entry fires? | When it fires (ATM then); lock-at-arm as a choice |
| D4 | Size by ₹ risk or fixed lots? | ₹ risk, with a lot cap per trade |
| D5 | Capital and loss limit on ZERODHA-1: a reserved manual slice, or shared first come? | Shared capital, own ₹10K manual loss sub-limit inside the account's ₹22K |
| D6 | Which account first? | PAPER-1 for P0–P1; ZERODHA-1 LIVE only when you ask, 1 lot |
| D7 | Members' workspaces too? | Later; the table is per account already, so it is a UI switch, not a rebuild |
