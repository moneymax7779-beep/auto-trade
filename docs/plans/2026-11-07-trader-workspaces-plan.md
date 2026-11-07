# Trader workspaces: a dedicated login per family member, with their own broker management (7 Nov 2026)

Status: **plan only, nothing built.** User, 7 Nov: "managing the family accounts from the same primary account is
difficult, so would like to think about having dedicated users and broker management in the trader account only so
that it is easy to manage and maintain, give me the best implementation plan."

How I read it:
- every family member gets their own login and their own **trader workspace**;
- connecting a broker, the daily broker login, capital, strategies, limits and their own kill switch live in that
  workspace, run by the member;
- your primary login stops being the place where everyone's accounts are set up and run. It keeps the platform jobs
  (strategies, signal source, research, deploys, the global kill) and gets a one-screen **household view** plus audited
  help access to a member's workspace when they ask for it.

This sits on top of the multi-broker plan (`../auto-trade-broker/docs/plans/2026-11-01-multi-broker-live-plan.md`,
3.6 and D8: "each family member is their own tenant, own app or API key, own login, own ledger, own static IP";
"each member logs in themselves"). That plan covers the broker adapters and IPs; this one covers who manages what and
where.

## 0. The honest summary

1. **Most of the data model is already right.** Since V21/V27 each family member is their own tenant, with their own
   accounts, capital, limits, kill switches, positions and audit, following your LIVE-SET through a share. The trouble
   is everything around it: how people are added, who holds the broker keys, where alerts go, and one Accounts page
   that mixes everyone.
2. **The biggest win is not new code. It is moving four jobs off you:** creating logins, adding broker app keys,
   receiving everyone's login links, and making account changes on members' behalf.
3. **Self-service adds one new risk: the app starts accepting broker API secrets from a web form.** Today only you put
   them on the VM by hand. The form must be write-only (a secret can never be read back, not even by you), stored
   outside the database, and audited. That part gets its own security review before it ships.
4. **More logins means more forgotten logins.** Each member's broker needs a fresh login every trading day (Kite:
   manual by rule). A member who doesn't log in simply paper-trades that day (2.5). That is safe, but they must get the reminder
   themselves, not you.
5. **Family only.** Running signals into unrelated people's accounts makes you an algo provider (exchange empanelment
   through a broker). Every workspace is a family member, and the invite asks for the relationship.

## 1. Why it is hard today (verified in code, main 5853817)

| Pain | Where | Effect on you |
|---|---|---|
| A member's login is made in the Keycloak admin console, which is reachable only through an SSH tunnel | `MembersController` ("the person's login ... is made in Keycloak by the admin"); GCP admin console via `-L 8188:...` | Every new member, password reset or lost authenticator is an SSH session for you |
| Broker app keys (Kite API key and secret) only by CLI plus a secret in `.env` or a file on the VM | `ToolCommands.kiteProfile`, `BrokerSecrets` (`env:KITE_*` / `file:`) | Every member's Kite app is a VM job for you, after hours |
| You are ADMIN of every member's tenant, and an account you create there is quietly assigned to "the first non-admin member" | `AccountAdminController.owner()` | Easy to create or change the wrong person's account |
| One Accounts page lists every tenant's accounts, kill switches and members together | `AccountsPage.tsx` (tenant column, `MembersPanel`, one `KillSwitchPanel`) | Mixed tables: the more members, the more chance of acting on the wrong row |
| All Telegram messages, including every account's 08:30 Kite login link, go to one chat: yours | `TelegramNotifier` (one `TELEGRAM_CHAT_ID`) | You receive members' login links and must forward them |
| The site's basic-auth gate sits in front of Keycloak | nginx on zt-autotrade (see keycloak-logins memory) | Members need two passwords: the shared gate and their own |
| Members get a narrower UI by lacking the `operator` role, but there is no trader workspace as such | `App.tsx` `MEMBER_NAV`, `SecurityConfig.MEMBER_APIS` | A member with OWNER can add accounts but cannot add a broker app, so the job falls back to you |

## 2. Target design

### 2.1 Three kinds of login

| | Platform admin (you) | Trader (each family member, and you as a trader) | Viewer (optional, e.g. a spouse watching) |
|---|---|---|---|
| Keycloak realm role | `operator` | `trader` | none |
| Sign-in | password + TOTP | password + TOTP (required as soon as the workspace has any non-PAPER account) | password + TOTP |
| Strategies, versions, signal source, research, replay, config, deploys | yes | no | no |
| GLOBAL_LIVE kill, household limits (follower-risk v4 allocator), static IP provisioning | yes | no | no |
| Own workspace: brokers, broker apps, daily login, accounts, capital, strategy choices from the shared set, loss limit, kill and flatten | their own only, as a trader | yes | read only |
| Another member's workspace | household view (read); acting needs the member's grant (2.3); flatten and kill always allowed | never | never |

**Recommendation: split your own identity in two.** `satyacp99` stays the platform admin. Your own trading accounts
(PAPER-1, ZERODHA-1 ...) move to a trader workspace you reach with the switcher (2.2). It is the same person and the
same login, but your own trading sits in a workspace like everyone else's instead of being "the admin's view". A
second Keycloak user is not needed (decision F1).

### 2.2 One workspace per trader, one workspace on screen at a time

- A **workspace switcher** in the header lists the workspaces the signed-in person belongs to: "Satya (you)",
  "Household" (admin only), and a member's workspace only while a support grant is active (2.3).
- Every trader page shows **only the selected workspace**: its accounts, positions, ledger, kill switches and audit.
  There are no mixed tables any more. The API takes the workspace (tenant) explicitly, and the server checks it on
  every call, as `Access` does today.
- Trader navigation:
  - **Today:** today's broker (2.5), its login status, open positions, P&L, alerts.
  - **Brokers:** broker apps, connections, daily login (2.4).
  - **Accounts:** capital, mode, loss limit, strategies.
  - **Ledger.**
  - **Settings:** profile, authenticator, Telegram, support access.
  - The index charts and Overview, read only, as members see them today.
- Admin navigation adds **Household** and **Members**. The platform pages are unchanged.

### 2.3 Support access: how you help a member without running their account

- In a member's tenant you hold a new role, **SUPPORT** (replacing today's ADMIN there).
  - SUPPORT sees the household summary.
  - It may always **engage the member's kill switch and flatten**: in an emergency nobody needs to ask.
  - It may **not** change capital, mode, strategies, limits or broker apps.
- When a member wants hands-on help, they grant it in **Settings → Support access**:
  - "Let Satya manage my workspace for 2 hours / until tonight / 7 days".
  - The grant is time-limited, can be revoked, and is audited.
- While a grant is active you can open their workspace from the switcher.
  - A banner says "Acting in Ramesh's workspace".
  - Every change is audited with you as the actor `on_behalf_of` the member.
  - The member sees those changes in their own audit list ("Satya changed allocation ₹5 L → ₹3 L at 10:02").
- **Break-glass:** if a member cannot be reached, you can open a 1-hour grant yourself. It needs a typed reason, is
  sent to the member's Telegram at once, and is flagged in the audit.

### 2.4 Broker management inside the trader workspace

A **Connect a broker** wizard. Zerodha comes first; Upstox and Kotak are added the same way once their adapters exist
(multi-broker plan U1/N1).

1. **Client id:** the member types their Zerodha client id. It is pinned to the account, as today, and a login from
   another client id is refused, as today.
2. **Broker app:** the page shows exactly what to register on the broker's developer site:
   - the redirect URL;
   - the account's own static IP, from the per-account IP plan (3.9; until E1 the single registered IP);
   - the app name to use.

   The member creates the app under their own developer login, or under yours if Zerodha's family arrangement is used
   (decision F3).
3. **Keys:** the member pastes the API key and secret into a **write-only** form.
   - They go straight to the secret store (2.6) and never into the database or a log.
   - The page afterwards shows only "secret set on 7 Nov 21:14 by ramesh, ends …x9Q2".
   - Replacing a key writes a new version; the old one is disabled after the next successful login.
4. **Test:** one read call (profile or margins) after the first daily login proves the key, the client id and the IP
   together. Only then does the account's connection turn ACTIVE.
5. **Account:** the member creates the broker account. It always starts and trades PAPER (2.5); it is never created
   LIVE.
6. **LIVE eligibility, once per broker account (owner):** before the member's LIVE switch (2.5) works for an account,
   you mark it *LIVE-eligible* on the Household page after the checks:
   - client id pinned;
   - the test call passed;
   - IP registered;
   - loss limit set.

   This is today's `live_trading_enabled` flag. It is a one-time set-up step, not a daily approval (decision F5).

The daily login and its reminders go to **the member's own Telegram**, not to yours. You get one morning summary,
e.g. "Ramesh: Zerodha, PAPER (not logged in yet)".

### 2.5 Today's broker and PAPER/LIVE: the member's daily switch (user, 7 Nov)

User, 7 Nov:
- "each member will choose which broker to use for trading that day that has to be in his dashboard";
- "if member not logged in to broker account, it should be PAPER trading ... member can login any time of the day not
  exactly at 9.15AM, and change the flag to LIVE and then only after any signal should be traded, unless user/primary
  owner move to LIVE it should be in PAPER ... not allowed for splitting for now."

**The card on Today:**

```
Trading today, Mon 9 Nov     Broker: (•) Zerodha ZR1234   ( ) Upstox 7AB12C   ( ) Off
                             Zerodha: logged in 10:42   |   Mode: [ PAPER | LIVE ]   LIVE since 10:44
                             ₹3,00,000  |  ecr, mac, obc  |  today: 2 paper trades +₹4,100, 1 live trade open
```

**The rules:**
1. **PAPER is the default, every day.** Each morning every member's mode is PAPER. Signals follow the chosen broker
   account as paper trades: the same strikes, sizes and exits, filled by the simulator on our quotes, with no order
   sent to the broker. A member who never logs in simply paper-trades all day. Their ledger shows it as PAPER.
2. **Log in any time.** The broker login works at any time of the day, not only before 09:15. Logging in does **not**
   make anything LIVE by itself.
3. **LIVE only by an explicit switch.** The member flips Mode to LIVE (or you do, as the primary owner). The switch
   needs three things:
   - the chosen broker is logged in (an ACTIVE session);
   - the account is LIVE-eligible (2.4, step 6);
   - no kill switch is engaged and the member's loss limit is not hit.

   From that moment, **only signals decided after the switch** go to the broker. Nothing earlier is chased: an entry
   signalled at 09:40 is not bought at 10:44.
4. **What happens to positions already open at the switch.**
   - Paper positions opened before the switch stay paper and exit on paper.
   - A live position is never turned into paper, or the other way round.
   - Each position keeps the mode and the broker it was opened with until it exits.
5. **Back to PAPER.** The member or you can flip back to PAPER at any time. New signals then paper-trade. Live
   positions still open keep their broker stop and exit live at the broker. Flipping to PAPER is never a way of
   leaving a live position behind.
6. **Lost session while LIVE.** If the broker session expires or is rejected mid-day:
   - new entries fall back to PAPER automatically, by rule 1;
   - open live positions keep their resting broker stop;
   - their exits wait for the re-login, with an urgent Telegram to the member and to you;
   - after the re-login the mode is still PAPER, and the member flips LIVE again if they want.
7. **One broker at a time, no splitting.** The member picks one broker; a signal goes to that one broker account
   only.
8. **Changing broker** is allowed at any time of the day. New signals go to the new broker, in PAPER until the member
   flips LIVE there. A change that would give **two brokers live exposure at once** is refused: while a live position
   is open on broker A, the member can switch to B only in PAPER, and B can go LIVE once A has no live position open.
   Paper positions never block a change. Every change of broker or mode is audited with the time and who did it.
9. **The mode resets to PAPER every morning (decision F12).** Yesterday's LIVE does not carry over. Otherwise a login
   at 10:00 tomorrow would start live trading without a fresh decision. The broker pick does carry over.
10. **Loss limit across brokers, PAPER and LIVE kept apart.** The member's daily loss limit counts the LIVE P&L of all
    their brokers that day, so switching brokers never resets it. Paper P&L is reported but does not use up the
    limit. A member-level kill switch covers every broker of theirs.
11. **Per-broker settings.** Capital, strategies and size stay per broker account, because the money sits at each
    broker separately. The card shows the chosen account's settings.
12. **Who uses this.** A tenant setting, `DAILY_SWITCH` (family default) or `ACCOUNT_MODE`, decides it. Your own tenant
    keeps `ACCOUNT_MODE`: SIGNAL-1, PAPER-1 and ZERODHA-1 work exactly as today.
13. **Later, optional:** "/live" and "/paper" commands to the Telegram bot. Not in the first build.

**Where it is enforced:**
- **In `FanOut`.** For a `DAILY_SWITCH` tenant:
  - an ENTER or ADD is made only for the day's chosen account;
  - its `execution_mode` is LIVE only if the day's mode was LIVE **before** the decision (`live_since <= decided_at`)
    and the account is LIVE-eligible; otherwise it is PAPER.
  - EXIT intents follow the position's own account and mode, as now.
- **In the worker, just before the broker call.** It re-checks the day's state:
  - The session must still be ACTIVE, the mode still LIVE, and the broker still the chosen one.
  - Otherwise a LIVE **entry** is downgraded to PAPER (reason `SESSION_NOT_ACTIVE` / `MODE_PAPER`) or refused
    (`NOT_TODAYS_BROKER`).
  - A LIVE **exit** is never downgraded. It waits and alerts.
- **In the database.** The existing `exec.trade_intent.execution_mode`, and `exec.position` bound to its account, carry
  the mode and broker of every position, so mixed PAPER and LIVE positions in one day stay apart.

### 2.6 Where broker secrets live

| Option | For | Against |
|---|---|---|
| **A. GCP Secret Manager** (recommended): one secret per broker app, `autotrade-t<tenant>-<profile>-{key,secret}`; the execution service account can add versions and read them only under the `autotrade-` prefix (IAM condition) | Never on disk or in the DB; versioned; access-logged by Google; no key of ours to lose | Needs IAM set-up once (yours, after hours); a GCP outage at 08:30 delays logins (tokens are cached on the VM for the day) |
| B. Encrypted in the DB (AES-GCM, master key in the VM's root-only `.env`) | No new GCP service | A DB dump plus the `.env` reveals every key; key rotation is ours to build |
| C. Today's `file:` / `env:` on the VM | Exists | Only someone on the VM can add a key, which is exactly the job to move off you |

`BrokerSecrets` gains an `sm:` reference next to `env:` and `file:`. The access tokens stay as today: a session file
per account, owner-only, deleted on expiry. Passwords, TOTP secrets and MPINs are still never stored (multi-broker
D12).

### 2.7 People, invites and Telegram

- **Invite instead of the Keycloak console.** You fill in the invite on the Members page: name, username, relationship
  and email or phone. The app then:
  - creates the Keycloak user through a narrow service account (realm `autotrade`, manage-users only, may assign only
    the `trader` role);
  - creates the member's tenant with them as OWNER and you as SUPPORT;
  - shares LIVE-SET with the tenant, as today.
- **First sign-in:** the member sets their own password and authenticator through Keycloak's required actions.
  - With SMTP configured (a dedicated mailbox), Keycloak emails a one-time link (`execute-actions-email`, valid 24 h).
  - Without SMTP, the app shows you a one-time temporary password once, to pass on by phone, and Keycloak forces a
    change at first sign-in (decision F2).
- **Members page actions:** disable, re-enable, "reset authenticator" (removes the OTP credential so they enrol
  again), "resend invite". No SSH tunnel for everyday user work. The Keycloak admin console stays tunnel-only for
  realm changes.
- **Telegram per person:** in Settings the member presses "Link Telegram". They get a one-time code to send to the
  bot, and the bot stores their chat id against their user. Alerts for an account go to its owner's chat; platform
  alerts go to yours.

### 2.8 Household view (admin)

One card per member, so you watch everyone without operating anyone:

- broker login today (yes/no and time);
- today's broker, PAPER or LIVE (since when), and whether it is logged in;
- today's P&L and open positions;
- loss-limit headroom;
- kill switches;
- unreconciled ledger days;
- pending requests (LIVE requests, broker apps awaiting a test).

The only buttons are **Kill**, **Flatten**, **Switch PAPER/LIVE** (the same rules as the member's), **Mark LIVE-eligible**, **Open workspace** (with a grant) and **Break-glass**.

### 2.9 What stays platform-level, on purpose

- strategies and their versioned files;
- the signal source and the LIVE-SET share;
- shadows, research and replays;
- the GLOBAL_LIVE kill;
- the **household liquidity allocator and combined limits** (follower-risk v4): several family accounts buy the same
  strike from the same order book, so this cannot be per member;
- static IP provisioning (`bin/egress`, multi-broker D11);
- deploys.

A member chooses *which* of the shared strategies their account follows and at what size. They cannot add strategies
or change their thresholds.

## 3. Data model (one new Flyway migration; number fixed at build time, main is at V28)

- `acct.tenant_membership.role`: add `SUPPORT`. The migration turns today's ADMIN rows in family tenants (tenants with
  a `copy_group_share`) into SUPPORT, and makes each such tenant's member OWNER where they are TRADER and you agree
  (decision F4).
- `acct.support_grant` (id, tenant_id, granted_to, level `MANAGE`, reason, granted_by, granted_at, expires_at,
  revoked_at, break_glass boolean).
- `acct.invite` (id, username, display_name, relationship, contact, tenant_id, keycloak_user_id, status
  `PENDING|ACCEPTED|EXPIRED|REVOKED`, created_by, created_at, expires_at).
- `acct.app_user`: add `telegram_chat_id`, `relationship`. `acct.telegram_link` (code, app_user_id, expires_at) for
  the linking step.
- `acct.broker_credential_profile`:
  - use the existing `owner_app_user_id` and `scope = 'USER_PRIVATE'`;
  - `api_key_ref` / `api_secret_ref` may be `sm:<name>`;
  - add `secret_set_at`, `secret_set_by`, `secret_hint` (last 4 characters), `verified_at`, `last_check_error`.
- `acct.tenant`: add `trading_control` (`DAILY_SWITCH` | `ACCOUNT_MODE`, family tenants DAILY_SWITCH), `default_broker_account_id`,
  `daily_loss_limit` (member level, across all their brokers).
- `acct.trading_day` (tenant_id, session_date, broker_account_id null = Off, mode `PAPER|LIVE` default PAPER, live_since,
  changed_by, changed_at; primary key tenant_id + session_date), and every change of broker or mode in `exec.audit_log`
  (`TODAYS_BROKER`, `MODE_LIVE`, `MODE_PAPER`, `MODE_PAPER_SESSION_LOST`).
- `exec.kill_switch.scope`: add `TENANT` (all of a member's brokers).
- LIVE eligibility stays `acct.broker_account.live_trading_enabled`, set by the owner (audited with the checks it passed).
- `exec.audit_log`: add `on_behalf_of` (app_user id, nullable). The member's audit list is
  `where tenant_id = their tenant`.

No existing table loses a column. Applied migrations are not edited.

## 4. Code changes

| Area | Change |
|---|---|
| `Access` | Workspace-scoped user: roles per tenant plus active grants; `mayAct` / `manages` honour SUPPORT and grants; a `on_behalf_of` actor for audits |
| `AccountAdminController` | Every call names the tenant; remove the `owner()` guess (the account belongs to the workspace's OWNER); LIVE request / approve endpoints |
| New `BrokerAppController` | Credential profiles per workspace: create, set or replace secret (write-only), test, disable |
| `BrokerSecrets` | `sm:` reference via the Secret Manager client; cache per login; never logged |
| `MembersController` | Invite, resend, disable, reset authenticator, through a `KeycloakAdmin` client (service-account credentials in the secret store) |
| New `SupportGrantController` | Grant, revoke, break-glass (Telegram to the member) |
| `FanOut` | For a `DAILY_SWITCH` tenant, ENTER/ADD only to the day's chosen account, LIVE only when `live_since <= decided_at`, else PAPER; EXIT follows the position |
| Execution worker / `FollowerRisk` | Re-check broker, mode and session before the broker call (downgrade a LIVE entry to PAPER, or `NOT_TODAYS_BROKER`; never downgrade an exit); on session loss set the day to PAPER; member loss limit summed over all the tenant's accounts; TENANT kill |
| New `TradingDayController` | Get / set today's broker and mode for the member or the owner (rules 3, 7, 8 of 2.5); 08:00 reset to PAPER |
| `TelegramNotifier` (execution) | Recipient per account owner; every PAPER/LIVE flip to the member; the morning summary to the platform admin |
| `KiteLogin` | Login links and reminders to the account owner's chat; a login at any time of day; the session no longer decides the mode (2.5) |
| `SecurityConfig` | A `trader` realm role reaches the trader APIs; `operator` the platform ones |
| UI | Workspace switcher; trader pages (Today, Brokers, Accounts, Ledger, Settings); admin Household and Members; `AccountsPage` split so no table mixes tenants |
| Keycloak (`bin/keycloak/bootstrap.sh`) | Realm role `trader`; client `autotrade-admin` (service account, manage-users, limited role mapping); optional SMTP; brute-force protection on |

Tests:
- ITs per controller, with two tenants and a SUPPORT user, for:
  - cross-tenant refusals;
  - grant expiry in the middle of a request;
  - break-glass audit;
  - the write-only secret (no endpoint returns it);
  - a LIVE request that one person cannot both make and approve.
- A Keycloak Testcontainer for the invite path.
- The existing 400+ tests stay green.

## 5. Moving today's data (GCP, after hours, your go-ahead)

1. Back up `acct.*` and `exec.*` (pg_dump of those schemas).
2. Apply the migration.
   - In family-demo, `demo` stays VIEWER and you become SUPPORT.
   - In your own tenant 1 you stay ADMIN and OWNER-equivalent. `local-owner` stays OWNER, because it is the signal
     source's owner subject.
3. Move ZERODHA-1's Kite app from `env:KITE_*` into Secret Manager (`sm:`) **on a weekend**, then do one test login
   on Monday at 08:30 before the open. Keep the old `.env` entries until that login succeeds.
4. Turn off nginx basic-auth for the app and `/auth/` once every route requires Keycloak. This is your change: it was
   refused to me as security-weakening on 29 Oct. It also goes into the VM `startup-script` metadata, which rewrites
   nginx on every boot.
5. Link your Telegram chat to `satyacp99`; members link their own at first sign-in.

## 6. Phases and gates

| Phase | Content | Gate to the next | Estimate |
|---|---|---|---|
| W0 | Your decisions F1–F12; this plan reviewed | decisions written down | you |
| W1 | Workspace switcher, workspace-scoped API and pages, Household view (read only); no schema change except `on_behalf_of` | no page mixes tenants; ITs for cross-tenant refusal | 1–2 days |
| W2 | SUPPORT role, support grants, break-glass, member-visible audit | IT: SUPPORT cannot change capital without a grant, can always kill/flatten | 1–2 days |
| W3 | Invites through the Keycloak admin API, Members page actions, Telegram per person | a new test member signs in end to end on the Mac with Keycloak (you type the passwords) | 2 days |
| W4 | Brokers page: write-only broker app keys in Secret Manager, connection test, member's own daily login and reminders | security review of the secret path; a test member links a Kite app and logs in on the Mac against the simulator; your ZERODHA-1 key migrated on GCP and logged in once | 2–3 days |
| W5 | Today's broker + daily PAPER/LIVE switch (2.5): Today card, `FanOut` + worker enforcement, morning reset to PAPER, session-loss fallback, member loss limit and TENANT kill across brokers | ITs: no login → PAPER; a signal before the LIVE switch stays PAPER, after it goes LIVE; an entry never reaches a non-chosen broker; switching to B while A holds a live position stays PAPER; a LIVE exit is never downgraded; a live loss on A blocks live entries on B; then a Mac day with a test member logging in late and flipping LIVE against the Kite simulator | 2–3 days |
| W5b | LIVE-eligible marking with its checks, morning summary per member, household roll-up | IT: the switch refuses LIVE on a not-eligible account | 1 day |
| W6 | GCP migration (section 5), basic-auth gate off (you), runbook "add a family member" | first real family member onboarded and paper-trading ≥ 5 sessions before their first LIVE switch | after hours, your deploy |

Order with the other live work: W1–W3 do not touch execution and can go first. W4 changes how ZERODHA-1's key is read,
so it ships on a non-trading evening with a Monday morning test login. Upstox and Kotak use the same Brokers page
when their adapters land (multi-broker U1/N1). Per-account static IPs (E0–E2) appear on the page as read-only
information.

## 7. Risks and how they are handled

| Risk | Handling |
|---|---|
| A member's laptop or phone is compromised and their workspace is opened | TOTP required; only their own accounts are reachable; secrets cannot be read back; LIVE needs your approval; you can always kill and flatten |
| Your login is compromised | It reaches every member's kill/flatten, but not their capital or keys without a grant; break-glass alerts the member at once |
| Wrong key pasted (another person's Kite app) | The client-id pin plus the test call refuse it; the account stays not-connected |
| Secret Manager unavailable at login | The day's token is already on disk after login; for the login itself, retry with backoff and alert; meanwhile the member paper-trades (a missed login is harmless) |
| A member forgets they are LIVE, or thinks they are | The card shows PAPER/LIVE in large print; the mode resets to PAPER every morning; every flip sends a Telegram to the member |
| Members choose strategies or sizes that overlap and crowd the order book | The household allocator stays platform-level (2.9); per-member size caps in `strategy_subscription.size_multiplier` |
| A member switches brokers to dodge a loss limit or a kill | The loss limit and the TENANT kill count all their brokers; a switch is refused while anything is open |
| The chosen broker's session drops mid-day while LIVE | New entries fall back to PAPER; open live positions keep the broker stop and exit on the same broker after re-login; urgent Telegram to the member and you |
| A late LIVE switch buys an old signal at a worse price | Only signals decided after the switch go live (`live_since <= decided_at`) |
| Scope creep to non-family | The invite records the relationship; the plan and the app say family only |

## 8. Decisions for you

| # | Decision | My default |
|---|---|---|
| F1 | Your own trading in a trader workspace under the same login, or a second Keycloak user for trading | same login, switcher |
| F2 | Invites by email (configure SMTP, dedicated mailbox) or a one-time temporary password you pass on | email if you can set up a mailbox; else temporary password |
| F3 | Each member's Kite Connect app under their own developer login, or keys under your developer account (Zerodha's family arrangement) | their own login: no dependency on your account; *verify Zerodha's current cost and rules for each before W4* |
| F4 | Family members as OWNER of their workspace (self-managed) or TRADER (you manage capital and strategies) | OWNER; VIEWER for anyone who only watches |
| F5 | LIVE: you mark a broker account LIVE-eligible once; after that the member (or you) flips the daily switch | yes (your rule: member or owner moves to LIVE) |
| F6 | Secret store: Secret Manager (A) or encrypted DB (B) | A |
| F7 | Your powers in a member's workspace without a grant | view + kill + flatten only |
| F8 | Who does a member's first broker connection: the member alone, or you together on a call with a grant | together the first time, alone after that |
| F9 | Splitting one signal across brokers | not now (your rule) |
| F10 | Change of broker during the day | any time; the new broker starts PAPER; LIVE there only once no live position is open on the old one |
| F11 | Loss limit per member across brokers, or per broker account | per member across brokers, LIVE P&L only (plus the existing per-account one) |
| F12 | Does LIVE carry over to the next day | no: every morning starts PAPER, the member flips LIVE after logging in |

## 9. What I will and won't do

- I build, test and simulate, and prepare the deploy for your go-ahead after hours.
- I never type anyone's password, TOTP, MPIN or broker API secret; members and you enter those yourselves.
- I never switch an account to LIVE.
- I never change GCP IAM, nginx or Keycloak on production without your go-ahead in chat.
- zt-tiger-v2 is untouched.

## 10. Implementation design (as built on `feature/trader-workspaces`, 7 Nov 2026)

User, 7 Nov: "commit the existing changes, create a new branch document the plan and go with the implementation ... production
ready ... consider all the negative use cases ... keep zerodha kite, upstox, kotak neo, grow brokers". The defaults of
section 8 are taken (F1–F12), with the changes below where the code showed a better way.

### 10.1 PAPER and LIVE on one broker: a paper twin per broker account

The execution worker runs each account through **one** route: a PAPER account fills in place, a LIVE account goes to its
broker behind `LiveGuard`. A LIVE route runs only LIVE intents, and nothing else, by design (`FollowerSimulator.pending`).
The reconciler kills an account whose positions differ from the broker's. Mixing PAPER and LIVE positions inside one
account would break both, so it is not done.

Instead, every real broker account in a `DAILY_SWITCH` workspace gets a **paper twin**:
- the twin is an account on the PAPER broker (`acct.broker_account.paper_of_account_id` points to the real one);
- it has the same capital, equity mode, loss limit and strategies, which are kept in step on every change;
- the daily switch decides where an **entry** goes:
  - PAPER, or LIVE without a valid session or without LIVE eligibility → the twin (simulated fills);
  - LIVE → the real account (its broker, through the existing LIVE route, `LiveGuard` and reconciliation, unchanged).
- Every position therefore stays in the account, and the mode, it was opened in. Its stop and exit stay there; the
  schema already binds a position to its account.
- The paper ledger and the live ledger are separate by construction. The member's loss limit counts the LIVE accounts
  only.

A workspace on the PAPER broker only (no broker connected, e.g. DEMO-1) trades that account itself, in PAPER.

### 10.2 The day's state: an append-only event log

- `acct.trading_day_event` (tenant, broker account or null = Off, mode, who, why, when) is append-only.
- `acct.trading_state(tenant, at)` returns the state at any moment:
  - the latest event at or before `at`;
  - its mode counts only on the same IST day, so every new day starts PAPER (F12);
  - its broker carries over (rule 9).
- With no event at all, the workspace's `default_broker_account_id` applies, in PAPER.
- Fan-out resolves an entry's target **as of the decision's own time** (`decided_at`). A signal before the LIVE switch is
  therefore PAPER, however late it is fanned out, and replays are deterministic.
- At most one ENTER intent is created per batch in a `DAILY_SWITCH` workspace. A switch landing between two fan-out
  ticks can never send the same entry to both the twin and the broker.
- Just before an entry runs, the worker checks again that the account is still the workspace's target now
  (`TARGET_CHANGED`). A flip between fan-out and execution then refuses the entry rather than sending it to the wrong
  place.

### 10.3 Exits and adds

- EXIT and ADD go to every subscribed account of the workspace, as today, and an account that holds nothing skips them.
- A real broker account in a `DAILY_SWITCH` workspace is **idle** when it:
  - holds no position;
  - has no order working;
  - has no entry pending.

  An idle account gets no turn: no broker connection, no lease, no reconciliation, so no session errors and no kill
  from a member's own hand trades on PAPER days. Its EXIT and ADD intents are closed as `NOT_HELD` straight away, so an
  old ADD can never attach to a later position.

### 10.4 Safety rules in code

- `TENANT` kill switch scope: all of a member's accounts.
- Member daily loss limit (`acct.tenant.daily_loss_limit`): LIVE realised net across all their broker accounts today.
  Once reached, LIVE entries are refused (`TENANT_DAILY_LOSS`) and the LIVE switch is refused for the rest of the day.
- Session lost while LIVE: the worker appends a PAPER event (reason `SESSION_LOST`), so later entries go to the twin.
  Open LIVE positions keep their broker stop; their exits wait for the re-login (existing behaviour).
- LIVE switch checks:
  - the chosen account is LIVE-eligible (`trade_mode = LIVE`, `live_trading_enabled`, set once by the platform admin);
  - it has an ACTIVE session;
  - no kill switch is engaged;
  - the member's loss limit is not reached;
  - no other broker of the member holds a live position or a working order.
- Changing broker: any time, the new broker starts PAPER.
- Off: no entries at all.

### 10.5 Roles

- `SUPPORT` replaces the platform admin's `ADMIN` membership in family workspaces.
  - Without a grant: see, engage a kill switch, flatten, exit a position.
  - With an active grant from the member (`acct.support_grant`, time-limited, revocable; break-glass 1 h with a reason,
    the member is told on Telegram): everything an OWNER does.
- The member is OWNER of their workspace.
- No new Keycloak realm role is needed: what a person may do is decided by their memberships, as today. `operator`
  stays the platform admin's role.

### 10.6 Brokers

- `GROWW` is added as a broker type next to `ZERODHA`, `UPSTOX` and `KOTAK_NEO`.
- Every broker can be connected (broker app, keys, client id) and paper-traded.
- The daily login is built per broker (Kite redirect, Upstox OAuth, Kotak Neo TOTP + MPIN passed through, never
  stored, and Groww as its API allows).
- LIVE stays possible only where an order adapter exists, which today is Zerodha. Upstox, Kotak Neo and Groww stay
  PAPER until their adapters are built and tested (multi-broker plan U/N phases); the LIVE switch says so.

### 10.7 Secrets

- Broker app keys and secrets typed into the Brokers page go to a **write-only file store**:
  - one file per value, owner-only (0600);
  - in a writable folder of the execution container (`/app/broker-secrets`, on the VM `.local/broker-secrets`, root
    side 700);
  - referenced as `store:<name>`.
- The API never returns a value, only when it was set, by whom, and its last 4 characters.
- The existing `env:` and `file:` references keep working, so ZERODHA-1 needs no change.
- GCP Secret Manager (F6 option A) is left as a later step: it needs IAM work on the VM that only you can do. The file
  store gives today's protection (a root-only folder on the VM) without it.

### 10.8 What is not changed

- `ACCOUNT_MODE` workspaces (your own tenant: SIGNAL-1, PAPER-1, ZERODHA-1) run exactly as before.
- Every new rule is gated on `trading_control = 'DAILY_SWITCH'`.

### 10.9 Changes after the code review and the end-to-end run (7 Nov 2026)

**LIVE switch**
- **Who:** the workspace's OWNER, or SUPPORT with the member's grant. A TRADER chooses the broker and can stop trouble,
  but does not switch real money LIVE.
- **Session-bound:** a LIVE switch counts only during the broker session it was made in (`acct.entry_target`). After a
  lost session and a re-login, the day is PAPER until switched LIVE again.

**Session-lost fallback**
- Runs under the workspace's lock, re-reads the state, and never undoes a broker change made meanwhile.
- Covers every way a session ends: the worker's reconciliation or order call, the 06:00 expiry sweep, a logout here, a
  session marked DISCONNECTED by hand, and the member being disabled.

**Idle accounts:** a pending add does not wake an idle LIVE-eligible account (adds are fanned out in that account's
LIVE mode). A leftover from an earlier day does not either.

**Never strand an open LIVE position:** logout, disabling a broker app, and replacing its keys are all refused while an
account using that app holds a position or a working order.

**Disabled OWNER:** still owns the workspace. SUPPORT gains no rights by disabling a member, and that member's LIVE day
goes back to PAPER.

**V29 data step:** a family workspace's real broker accounts start PAPER-only. LIVE eligibility is the admin's new
check, not inherited.

**Not built as first planned**
- No `trader` realm role: memberships decide everything.
- No `on_behalf_of` column: SUPPORT's actions are audited in the member's workspace under SUPPORT's own name.
- Secrets go to the owner-only file store, not GCP Secret Manager (10.7).

**End-to-end run on the Mac** (scratch database, the local Keycloak, broker APIs pointed at a dead address)
- **Admin:** added a member, whose login was created in Keycloak with the first-sign-in actions.
- **Member:** added a Zerodha account (its twin was created), created a Kite app, stored keys write-only (0600 files),
  and linked them.
- **Simulated login:** written to the scratch database only.
- **Admin:** marked the account LIVE-eligible.
- **Member:** switched LIVE.
- **Worker:** found no usable session and sent the day back to PAPER within the second.
- **Signals:** before and after went to the paper twin. An exit closed the twin's position, and the idle real account's
  copy was closed NOT_HELD with no broker call.
- **Groww and Upstox:** LIVE refused ("no LIVE order adapter yet").
- **Unreachable broker:** a clear message, recorded as the app's last check.
