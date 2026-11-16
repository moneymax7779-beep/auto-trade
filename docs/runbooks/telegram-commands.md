# Telegram commands (T0 + T1) — runbook

Plan: `docs/plans/2026-11-16-telegram-controls-plan.md`. Built 16 Nov 2026 on `feature/telegram-controls`.
**Off by default.** PAPER first; using it on a LIVE account needs the user's explicit go (plan section 5 and the LIVE
gate).

## What it does
| Command | Who | Effect |
|---|---|---|
| `/status` | every member | each follower account: mode, broker session, open positions; engaged kill switches; worker heartbeat |
| `/positions` | every member | open positions in your workspaces (`#id ACCOUNT MODE SYMBOL qty @avg`) |
| `/whoami` | every member | your login and roles |
| `/kill` · `/kill ACCOUNT` | OWNER / ADMIN / TRADER / SUPPORT (web rules) | workspace (TENANT) or account kill switch. **Nothing is sold** |
| `/flatten` · `/flatten ACCOUNT` | same | kill switch on, broker stops cancelled, every open position sold (the web's Flatten) |
| `/exit` · `/exit ID` | same | one position: broker stop cancelled, sold now; the account keeps trading (the web's Exit) |
| `/killall` | platform admin | GLOBAL_LIVE switch |

Every write shows a card (account, workspace, **mode**, what will be sold) with Confirm and Cancel on separate rows. It
runs only on a Confirm tap by the same person within 60 s, with the rights checked again at the tap. It is refused when
the account's mode changed after the card was shown (a PAPER card never flattens a LIVE account). An account name held in
two workspaces gives a list to pick from, never a guess. The card is then edited to the result with a reference `T<id>`:
DONE, **INCOMPLETE** (it ran, but something is not known sold: a broker refusal, a partial fill, no answer from the
account within 120 s; check `/positions` before sending it again), REFUSED or FAILED. The audit log has the same
reference (`TELEGRAM_COMMAND`, actor `telegram:<login>`).

**Not available on Telegram, by design:** releasing a switch, LIVE/PAPER switches, entries, stops, capital, limits.

## Rules a user must know
- **No answer within 10 s means it was not received.** Use the web (Accounts → Flatten / Exit) or the broker app.
- A command message older than 60 s is never run (after an outage you get "Not run … send it again"). Older than
  10 minutes it gets no answer at all.
- A forwarded message is never run: type the command.
- Commands are obeyed only in your **private chat** with the bot, from the Telegram account linked in Settings. Group
  chats are ignored.

## Turning it on (GCP only)
1. **Create a new bot for GCP in BotFather.** Do not reuse the Mac's bot or any bot zt-tiger-v2 uses: Telegram lets one
   program read a bot, and a second reader causes 409s on both. Put its token and your chat id in `.local/telegram/bot.env`.
   Members link again (Settings → Telegram), because a new bot is a new chat.
2. Optional, recommended: a **second bot** for the alarm, in `.local/telegram/alarm.env`
   (`TELEGRAM_BOT_TOKEN=…`, `TELEGRAM_CHAT_ID=…`), and `AUTOTRADE_TELEGRAM_ALARM_FILE=/app/telegram/alarm.env` in the
   root-only `.env`.
3. Set `AUTOTRADE_TELEGRAM_COMMANDS=true` in the root-only `.env` and redeploy. Trading-core must run first: the worker
   now needs schema V39.
4. Check: `curl -s 127.0.0.1:8096/api/execution/status | jq .telegram` shows `"polling": true` and a recent `lastOk`.
   The log shows `Telegram commands on: reading the bot from update N`.

Turning it off: set `AUTOTRADE_TELEGRAM_COMMANDS=false` and redeploy. The `/start` linking then goes back to its
old read, which takes nothing from the bot.

## Alarms (`TELEGRAM_POLL_ALARM` in the audit log; alarm bot or admin chat; at most every 10 min per kind)
| Kind | Means | Do |
|---|---|---|
| CONFLICT | HTTP 409: another program reads this bot, or a webhook is set | If it isn't a second deployment of ours: **revoke the token in BotFather**, put the new one in bot.env |
| WEBHOOK | someone set a webhook: every command goes to them | revoke the token now |
| TOKEN | 401/404: token invalid | fix bot.env |
| STALE | no successful read for 60 s, 09:00–15:35 IST on a trading day | network or Telegram outage; use the web |

Someone holding the bot token can also send you fake "done" cards. Check the `T<id>` on the Accounts → audit page when
it matters.

## Restarts
- Updates stored but not handled when the worker stopped are marked `SKIPPED_RESTART`, never run late.
- Cards still waiting become EXPIRED.
- A command CONFIRMED but not finished becomes FAILED and is **not run again**: an exit may already have gone out. The
  person gets a message telling them to check `/positions`.

A chat that is not linked hears "not linked" once every 10 minutes at most (a flood cannot hold the reader up).
`/flatten` without an account lists the enabled accounts and any turned off that still hold a position, those with
positions first, at most 12 buttons (the web has all).

## Tables
`exec.telegram_update` (every update, keyed by Telegram's update_id), `exec.telegram_command` (cards and outcomes),
`exec.telegram_poller` (last good read, last error).

## Drill before any LIVE use (T1 acceptance, PAPER-1 during a session)
1. `/positions` matches the Accounts page.
2. `/kill PAPER-1`, Confirm → switch engaged by `telegram:<you>`; release it on the web.
3. `/exit`, pick → sold; the time from tap to fill is in exec.order_execution against telegram_command.confirmed_at.
4. `/flatten PAPER-1` → flat, switch engaged.
5. Stop the worker between Confirm and the result once → FAILED message after the restart, nothing sold twice.
6. Run a second reader (`curl …/getUpdates`) for a minute → CONFLICT alarm arrives.
