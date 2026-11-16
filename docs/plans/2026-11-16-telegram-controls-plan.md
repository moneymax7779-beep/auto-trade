# Telegram controls: login, kill switch, exits, spot / % exits — design and plan (16 Nov 2026)

Status: **plan only, nothing built.** Written against `main` b424ce3 and the uncommitted `feature/chart-orders` worktree
(`../auto-trade-chart`). PAPER first; nothing here runs against a LIVE account until the user asks for that by name.

---

## 0. The honest summary

1. **Telegram today is send-only.** Two `TelegramNotifier`s (trading-core, app-execution) and `bin/offsite-watchdog` all
   read the same `bot.env` and only call `sendMessage`. The single inbound path is the `/start <code>` chat link
   (`acct.telegram_link`, V29), read with `getUpdates` **without an offset** so that nothing gets used up. A bot that
   takes commands has to use offsets, and doing that breaks the linking reader unless the two are merged (section 3.1).
2. **Most of the actions already exist. Only the Telegram entry point is missing.** Kill switches (`KillSwitches`: TENANT /
   ACCOUNT / STRATEGY / INSTRUMENT / GLOBAL_LIVE, with audit), `flatten` (kill, then exit everything, broker stops
   cancelled first), per-position `exitPosition` (no kill), and the role checks (`Access.User.mayAct` / `emergency`)
   are on `main` and covered by tests. Telegram must call **these** methods. It must not get its own copy of them.
3. **Exiting at a spot level cannot be done at the broker.** Kite SL and GTT orders trigger only on the option's own
   price. An index-level exit is watched by **our server and our feed**, and the feed is the shared zt Upstox token, one
   of two connections. If either goes down, the spot exit does not fire. The broker stop on the premium stays as the
   backstop. The engine that watches spot levels is already written: `ChartOrderEngine` on `feature/chart-orders`. It
   is **uncommitted** and has not run on a single live day. Spot exits ride on it. A second engine is not built.
4. **"Exit open order" means two different things** and the plan keeps them apart:
   - an **open position** → sell it (`exitPosition`);
   - a **working order** → cancel it. In this system entries are limit chases that settle in seconds. The orders left
     working are the **broker STOP** of each position and, once chart orders exist, **armed entry lines**. Cancelling a
     STOP from a phone would leave the position with no protection, so it is **not offered**. "Cancel" covers armed
     lines only.
5. **Telegram cannot log anyone in to Kite.** Kite needs its own web login with password and TOTP, and storing those
   to automate the login is ruled out. "Login" over Telegram therefore means: who you are is your linked chat (exists),
   and the bot hands you the daily broker login link on demand (the 08:30 push exists, the on-demand version does not).
   Keycloak stays the app login.
6. **Telegram is a convenience channel, not the safety system.** It has no delivery guarantee, bot chats are not
   end-to-end encrypted, and anyone holding the bot token can **steal or redirect your commands**. Two ways: call
   `getUpdates` themselves, or set a webhook, which makes our poller get HTTP 409. In both cases your `/kill` simply
   never reaches us. The real emergency paths stay the web Flatten and the Kite app. Every command is answered with an
   explicit ack. **No ack within 10 s means it was not received.**

---

## 1. Scope

| # | Capability | Who (existing `Access` rules) | Risk direction |
|---|---|---|---|
| C1 | Link and identity: `/start <code>` (exists), `/whoami`, `/unlink` | anyone with a membership | n/a |
| C2 | Read: `/status` (session, mode, switches, feed age), `/positions`, `/orders`, `/pnl` | every role (VIEWER and AUDITOR read-only) | none |
| C3 | Login: `/login` lists the accounts that need today's broker login, each with its link | `mayAct` | none |
| C4 | Kill switch: `/kill` (my workspace), `/kill <account>`, `/kill <strategy>`, `/killall` (GLOBAL_LIVE) | `emergency` (`/killall`: `admin()` only) | reduces risk |
| C5 | Flatten: `/flatten <account>` (kill + exit all), `/flattenall` | `emergency` / `admin()` | reduces risk, costs spread |
| C6 | Exit one position now: `/exit` → pick a position | `emergency` | reduces risk, costs spread |
| C7 | Cancel an armed chart line (after chart orders ship) | `mayAct` | reduces risk |
| C8 | Conditional exit by **spot level**: `/exitat` → position → level → stop-below or target-above | `mayAct` | reduces risk *if correct*; a wrong level is an unwanted exit |
| C9 | Conditional exit by **premium %** from entry (stop % and/or target %) | `mayAct` | same |
| C10 | Clear or change a C8/C9 exit | `mayAct` | **can increase risk**, so it needs a step-up (section 4.3) |

**Not over Telegram, by design:** releasing a kill switch, switching an account or strategy to LIVE, new entries,
cancelling or loosening a broker STOP, changing capital, limits or risk files. Each of these turns risk back on. A
phone with an unlocked Telegram, or a SIM swap, must never be able to do them. They stay on the web behind Keycloak
(this matches the existing alert text "release the switch there once it is understood").

`%` is defined once, so it can never be read two ways:
- **premium %** = the option's price against the position's average entry (`+40` target, `−25` stop);
- **spot %** is converted to an absolute index level **at the moment you arm it**, and the bot reads the level back to
  you ("NIFTY 24,151.35"). Only absolute levels are stored.
- A P&L % of capital is already handled by the daily loss stop (risk v11). It is not repeated here.

---

## 2. Architecture

```
 Telegram  ⇄  TelegramGateway (app-execution, GCP only, ONE instance)
                │ long-poll getUpdates(offset, timeout=25)
                ▼
          exec.telegram_update      (raw update, update_id unique → exactly-once)
                │
          CommandRouter: identity → role → parse → confirm card → exec.telegram_command (PENDING)
                │ confirm tap (callback, nonce, ≤60 s, same user)
                ▼
          exec.telegram_command  CONFIRMED ──NOTIFY──▶ ExecutionLoop lane of the lease holder
                │                                     (same LISTEN/NOTIFY wake as SignalWakeup)
                ├─ C4 KillSwitches.engage(...)         actor = "telegram:<subject>"
                ├─ C5 ExecutionLoop.flatten(...)
                ├─ C6 ExecutionLoop.exitPosition(...)
                └─ C8/C9 ChartOrders.attachExit(...) → ChartOrderEngine watches ticks
                ▼
          result → outbox (priority 0) → ack edits the confirm card: DONE / REFUSED reason / FAILED
```

Why it is shaped this way:

- **One poller only.** Telegram allows one `getUpdates` consumer per bot, and a second one gets 409. Today the Mac
  (8095/8096) and GCP containers can both see a `bot.env`. **Use a separate bot (and token) per environment.** The
  gateway polls only when `autotrade.telegram.commands=on`, which is set only on GCP. The Mac bot sends alerts and
  never takes commands.
- **Commands go through the database, not a direct call.** Accounts are leased by execution workers
  (`AccountLease`), and `flatten` / `exitPosition` run on the account's own lane. A command row that the lease holder
  picks up works whichever worker holds the account. It survives a restart in the middle of a command, and it gives
  exactly-once through `update_id`, then `command.id` used as the idempotency key.
- **Long polling, not a webhook.** It needs no public HTTPS endpoint and opens no new inbound port on the VM.
- **The acks have their own outbox and jump the queue.** `ExecutionAlerts` sends every 10 s and can flood. Telegram
  allows about 1 message/s per chat and about 30/s per bot. Command acks go out first, and alerts are batched behind
  them.

### 2.1 Data (one new migration; number after chart orders' V38, so V39 if chart orders merge first)

- `exec.telegram_update(update_id bigint pk, received_at, from_user_id bigint, chat_id bigint, chat_type, kind, text,
  callback_data, processed_at)`. The `/start` linker reads `/start` rows from this table instead of calling
  `getUpdates` itself.
- `exec.telegram_command(id, update_id fk unique, app_user_id, tenant_id, verb, args jsonb, target_account_id,
  target_position_id, mode_at_confirm, nonce, status PENDING/CONFIRMED/EXPIRED/DONE/REFUSED/FAILED,
  created_at, confirmed_at, done_at, result jsonb)`.
- `acct.app_user`: add `telegram_user_id bigint unique`, the Telegram `from.id`, stored at link time next to the
  existing `telegram_chat_id`.
- C8/C9: chart orders get `kind ENTRY|EXIT_ONLY`, `position_id`, `reference SPOT|FUT|PREMIUM`. Today the engine
  starts every order at an entry line. An exit-only order attaches to a position that already exists, whoever opened
  it (a strategy, Exit, a chart line).
- Everything is also written to `exec.audit_log` with actor `telegram:<keycloak subject>`, so the existing audit page
  and `ExecutionAlerts` show it without any change.

---

## 3. Component detail

### 3.1 TelegramGateway (T0)
- A long-poll loop on its own thread: `getUpdates?offset=last+1&timeout=25&allowed_updates=["message","callback_query"]`.
  Each update is stored first and the offset moved after. A crash in between means the next poll returns the same
  `update_id` and the insert ignores it.
- **Health, the part people skip:**
  - a heartbeat row every poll;
  - HTTP 409, or `getWebhookInfo` showing a URL we did not set → **HIJACK/CONFLICT alarm**;
  - no successful poll for 60 s during 09:00–15:35 IST → alarm.
  - The alarm cannot go over this same bot if the bot is the problem. It goes through `offsite-watchdog`'s path with a
    **second bot token** in a separate file, plus a red banner in the app.
- The poller uses its own `HttpClient` with a 35 s read timeout. It shares nothing with the senders.

### 3.2 Identity and authorisation (T0)
A command is accepted only when **all** of these hold:
1. `chat.type == private`. Group chats, including `TELEGRAM_GROUP_ID`, never take commands.
2. `from.id` matches a linked `acct.app_user.telegram_user_id`. Checking the chat id alone is not enough.
3. That user is `ACTIVE` in Keycloak and in `acct.app_user`, and still holds a membership in the target tenant.
   Removing a member unlinks them in the same transaction.
4. The verb is allowed by `Access.User` for that tenant, using the same methods the REST controllers use. If the
   `Access` code needs refactoring so both entry points can call it, refactor it rather than copying it.
5. Anything that is not a read needs a **confirm tap** (section 4.2).

### 3.3 Commands → existing methods (T1)
| Verb | Calls | Ack shows |
|---|---|---|
| `/kill` | `KillSwitches.engage(TENANT…)` | switch id, scope, **open positions still open + their ₹ P&L**, and an [Exit all] button. A kill does not exit anything, which is the most likely misunderstanding. |
| `/flatten A` | `ExecutionLoop.flatten` | each position: qty, fill price or "chasing", the broker stop cancelled |
| `/exit` | list → pick → `exitPosition` | fill price, ₹ realised, or why it was refused (e.g. the session was lost: "exit waits for login") |
| `/positions` | read | account, mode (**LIVE in capitals**), symbol, qty, avg, LTP, ₹, broker stop, any armed spot/% exit |
| `/login` | `LoginBoard` data | one link per account that needs a login; the link still asks for the Keycloak session |

### 3.4 Conditional exits (T2, after chart orders P0 is merged)
- **Spot level:** `ChartOrderEngine` already exits OPEN positions when the stop or target line is crossed, in memory
  on every index tick, with a stale-feed guard. Telegram only creates an `EXIT_ONLY` order bound to `position_id`.
  - **Direction is never guessed.** The card says, for example, "NIFTY 24 150 **below** = stop" or "**above** =
    target", and the user taps one. Arming is refused if the level is already crossed, which would mean an immediate
    exit; the user does that with `/exit`.
  - **Expiry close:** the index freezes 15:15–15:28 (see the expiry close regime study). A spot line armed on an
    expiry-day underlying after 15:10 is refused, or switched to the futures reference with a warning.
- **Premium %:**
  - The **stop** side **re-prices the broker STOP** (`BrokerGateway.modify`, already used in `BrokerVenue` /
    `LiveGuard`), only ever **tighter**, never looser. A % stop then survives our server being down.
  - The **target** side is watched by our engine on option ticks. A resting broker limit-sell is not used: together
    with the STOP for the same quantity it can **double-sell into a short option**. That is the same hazard the
    `ManualExit` refusal already guards against.
- **One owner of the exit.** Whichever fires first (strategy EXIT, broker stop, spot/% exit, `/exit`, EOD square-off)
  closes the position, and the others find it CLOSED (the engine's `sync`). The exit reason is recorded as
  `TG_SPOT_STOP` / `TG_SPOT_TARGET` / `TG_PCT_TARGET`, so research and reconciliation can separate hand-imposed exits
  from strategy exits. **This matters for the forward tests:** a Telegram exit on a follower makes it diverge from its
  signal source, and that trade must be excluded or flagged in A-378-style comparisons.

### 3.5 Login (T3)
- `/login`: the accounts where `connection_status` is not ACTIVE and today is a trading day, each with its broker
  login URL. The existing callback and Keycloak gate are unchanged.
- After a successful broker login, a "ZERODHA-1 session ACTIVE, waiting exits resumed" message goes to the person who
  logged in.
- Optional: a Keycloak login from a new device → a Telegram notice ("was this you?"). Cheap and useful. It is not a
  control.

---

## 4. Safety design

### 4.1 Threat model, stated plainly
| Threat | Effect | Mitigation | What remains |
|---|---|---|---|
| Bot token leaked (it sits in a plain `bot.env` on the VM, the Mac and the watchdog) | attacker **eats or redirects** your commands, sends fake "done" messages; **cannot** send commands as you | separate tokens per environment and per purpose; file mode 600; hijack alarm (3.1); the ack carries a short code that the web audit page shows for the same command; rotate on any doubt (BotFather `/revoke`) | fake acks are possible until the token is rotated |
| Your Telegram account taken over (SIM swap, open session, unlocked phone) | attacker can kill, flatten, exit, arm exits | nothing that turns risk back on is offered (section 1); the worst case is unwanted exits, paid in spread; step-up for C10; `/sessions` notice on a new link | unwanted exits and their costs |
| Fat finger | wrong account or position | a confirm card naming account + **mode** + symbol + qty + estimated ₹; buttons, not typed ids; the card expires after 60 s | low |
| Telegram outage or delay | command late or lost | an explicit ack; no ack in 10 s = use the web or the Kite app (runbook) | real; Telegram has no SLA |
| Feed down (shared zt Upstox token) | spot/% target exits don't fire | stale-feed guard; broker STOP backstop; the arm ack says "**watched by our server only; backstop broker stop ₹X**" | a gap or crash between the level and the backstop |
| Restart while a command runs | double exit or lost exit | command row + idempotency key; lane lease; the position is re-read before selling | low |

### 4.2 The confirmation protocol
- Each card has `callback_data` = `cmd:<id>:<nonce>`. The server checks: same `from.id`, status PENDING, age ≤ 60 s,
  and the nonce matches. On a match it sets CONFIRMED in one compare-and-set update. A second tap does nothing.
- Each card shows: `LIVE ZERODHA-1 · EXIT NIFTY 24200 CE · 75 qty · est. ₹ +3,420 at bid 61.4`.
- For LIVE accounts the confirm button is on a separate row from Cancel, so a single accidental tap is less likely.

### 4.3 Step-up (C10 only, and anything added later that turns risk back on)
- A 6-digit TOTP from an authenticator app that is linked on the web (Keycloak OTP if it is enabled, otherwise our own
  secret stored encrypted). A Telegram message alone never re-enables risk.

---

## 5. Compliance check (do not assume)
- Every Telegram-triggered exit is an **API order** placed by our app. The 2025 SEBI framework for retail algo and API
  trading (static IP, order tagging, broker-side registration and limits) applies to these orders as much as to
  strategy orders. **Static IP is not built yet** (memory: adapter validation, 16 Nov). Before any LIVE use, confirm
  with Zerodha what is required for manually triggered API exits on this app. This is a hard gate, not a footnote.
- `LiveGuard` currently counts hand NRML orders (adapter validation finding, high). The fix branch
  `fix/broker-validation-16nov` is uncommitted. Telegram exits must be tagged so that `LiveGuard` and reconciliation
  count them as ours.

---

## 6. Phases

| Phase | Builds | Depends on | Done when | Est. |
|---|---|---|---|---|
| **T0 foundation** | separate bots per environment; `TelegramGateway` long poll; V39 update/command tables; `/start` linking moved to stored updates; `telegram_user_id`; identity and role checks through `Access`; priority outbox; heartbeat + hijack alarm through the second bot; read verbs C1–C2 | — | IT (Testcontainers + stub Telegram API, as in `TelegramStartLinkTest`): duplicate `update_id`, group chat refused, unlinked `from.id` refused, VIEWER refused for a write, 409 raises the alarm | 3 d |
| **T1 emergency** | C4–C6 through the command table and lane wake; confirm cards; acks with codes; runbook ("no ack in 10 s → …") | T0 | PAPER-1 drill on a live session: `/kill`, `/exit`, `/flatten`, with tap→order and tap→ack times measured; a worker restart in the middle of a command gives exactly one exit | 2–3 d |
| **T2 conditional exits** | C7–C9: `EXIT_ONLY` chart orders, premium-% stop via a tighter broker STOP, % target in the engine, exit reasons, research exclusions | **chart orders P0 committed and merged** (today uncommitted in `../auto-trade-chart`) | engine unit tests per rule; replay on 3 tick days (spot stop, spot target, % target, gap through the level); PAPER week | 3–4 d |
| **T3 login + polish** | `/login`, session-restored notice, new-device notice, `/help` | T0 | broker login completed from the Telegram link on the Mac test bot | 1 d |
| **T4 step-up** | TOTP for C10 | T2 | wrong code refused and audited; 3 wrong codes lock it for 15 min | 1 d |
| **LIVE gate** | — | compliance check (section 5), `LiveGuard` fix merged, 5 clean PAPER days of T1/T2, **the user's explicit go** | — | — |

Total ≈ 10–12 working days, plus a week of PAPER use before any LIVE switch.

---

## 7. Decisions needed from the user
1. Should **TRADER** members be able to `/flatten` and `/exit` from Telegram, or OWNER only? Today `Access.emergency`
   lets TRADER and SUPPORT do it on the web. The default proposal mirrors the web rules.
2. Releasing a kill switch from Telegram: the proposal is **never**. Override only with step-up, if you want it.
3. Separate bot per environment (GCP commands bot, Mac alerts bot): yes or no. It is needed for a single poller.
4. Build order: T0+T1 now (independent), with T2 waiting for chart orders to be committed and merged. Or merge chart
   orders first.

---

## 8. As built: T0 + T1 (16 Nov 2026, `feature/telegram-controls`, uncommitted)

Built: V38 (`exec.telegram_update`, `exec.telegram_command`, `exec.telegram_poller`), `TelegramBot`, `TelegramGateway`,
`TelegramCommands`, `/start` linking read from the table while commands are on, `/status`'s `telegram` block,
`TelegramCommandsIT` (14 cases against a stub Bot API), runbook `docs/runbooks/telegram-commands.md`. Off by default
(`AUTOTRADE_TELEGRAM_COMMANDS`).

Where it differs from the design above, and why:
- **No `telegram_user_id` column.** In a private chat Telegram's chat id is the user's id. The check is: private chat,
  sender == chat, chat linked to exactly one ACTIVE login. One consequence: an admin's hand-set chat id
  (`/admin/members/{u}/telegram`) also gets command rights for that login.
- **Commands run in the worker's own process**, through `ExecutionService.flatten` / `exitPosition` as the REST
  controllers do, on one `telegram-actions` thread. There is no LISTEN/NOTIFY hand-off. GCP runs a single execution
  worker and the poller only runs there. The command table still gives exactly-once, the restart rules and the audit
  reference. If a second worker is ever added, an account leased by the other worker is handled the way the web's
  Flatten handles it today. That case has not been checked.
- **No separate priority outbox.** The acks are sent directly, and a 429 is retried once after `retry_after` (≤5 s).
- `/flattenall` and `/login` are not in T1. `/login` is T3.
- **Migration number:** V38 is also used by the uncommitted chart orders. Whichever merges second renumbers its file.

Not verified yet:
- No real Telegram traffic.
- No real flatten or exit driven from Telegram. The IT uses fake actions for those two; the kill switch is real.
- The T1 drill on PAPER-1 (runbook) is still to be done during a session.

### Code review, 16 Nov 2026 (same day, before any commit)
Fixed:
- The reader thread died on any database error (nothing caught it), and the offset had already moved past the update,
  so Telegram was told it was read: the update was lost and every later command unheard. Now the loop catches, backs
  off 5 s, and the offset moves only after the update is stored.
- The card's mode was stored but never compared: an account switched PAPER → LIVE between the card and the tap was
  flattened LIVE on a PAPER confirmation. Now REFUSED.
- `/flatten NAME` and `/kill NAME` took the first account of that name across workspaces (names are unique per workspace
  only). Now two of them give a pick list naming the workspace; every card names the workspace.
- `/flatten` without a name listed enabled accounts only: an account turned off mid-day with a position open could not
  be picked. Now listed, positions first.
- One action thread: a `/kill` waited behind a flatten stuck up to 120 s on a broker. Now 4 threads (per-account lanes
  still serialise each account).
- A flatten that left something unsold ("NOT flattened", a partial fill, "the worker has not started") showed DONE; a
  120 s lane timeout showed FAILED although the sale may still go through. Both are INCOMPLETE now, with a check-first
  message.
- An exception after the action (storing or sending the result) was swallowed by the executor: the person saw
  "running…" for ever. Now they are told it may have run.
- Texts over 4,096 characters (a long `/status` for the admin) were refused by Telegram: nothing arrived. Now cut.
- On first enable (a day of messages waiting) or after a long outage every old message was answered, including every
  `/start`. Now no answer past 10 minutes; a stranger's flood gets one "not linked" per 10 minutes.
- A forwarded `/flatten` (an old command passed on; its date is the forward's) ran. Now refused.
- V38 (not applied anywhere yet: Mac DB at 37) edited in place: INCOMPLETE status; `on delete set null` on the account and
  position keys, so a cancelled card no longer blocks removing an account.
Not changed, known: the action threads are daemons, so a deploy mid-flatten cuts it off (the restart then says FAILED,
not run again, check positions); an admin's hand-set chat id gets command rights; `telegram_update` keeps every update
(small; no retention job).

