# Runbook: trader workspaces (family members' own logins, brokers and daily PAPER/LIVE switch)

What the feature is: `docs/plans/2026-11-07-trader-workspaces-plan.md` (sections 2 and 10).
What the broker logins rely on: `docs/research/2026-11-07-broker-login-apis.md`.

## 1. Deploy (after hours, the user's go-ahead; never 08:50–15:50 IST)

The order matters: trading-core owns the migrations, and the execution worker refuses to start below V29.

1. **Back up** `acct` and `exec` on the VM:
   `sudo docker exec autotrade-postgres pg_dump -U autotrade -n acct -n exec autotrade > /srv/auto-trade/backup-acct-exec-$(date +%F).sql`
2. **Create the key store folder** (owned by the containers' user 10001, mode 700). Without it, the Brokers page refuses
   to store keys, with a clear message.
   `sudo install -d -o 10001 -g 10001 -m 700 /srv/auto-trade/.local/broker-secrets`
3. **Keycloak service account** (optional; without it, members' logins stay a console job):
   - `bin/keycloak/bootstrap.sh --app-url <the same URLs as before> --secret-file .local/keycloak/client-secret --admin-secret-file .local/keycloak/admin-client-secret`
   - Then add `AUTOTRADE_EXECUTION_KEYCLOAK_CLIENT_SECRET=<that file's content>` to the root-only `.env` (by hand; never
     printed).
   - The bootstrap also re-applies the realm and `autotrade-web` client: pass every `--app-url` the site uses.
4. **Optional:** `AUTOTRADE_BROKER_EGRESS_IPS=<the static IP members register>` in `.env` (shown on the Brokers page). Per
   decision D9, each account eventually gets its own IP (plan 3.9); until then, this is the one registered IP.
5. **Deploy the code**
   - trading-core first: the V29 migration runs at its start (Flyway 29).
   - Then the execution worker: `REQUIRED_SCHEMA` 29.
6. **Verify**
   - `select max(version::int) from flyway_schema_history` returns 29.
   - `curl -s 127.0.0.1:8096/actuator/health` returns UP.
   - The Family page lists DEMO's workspace: DAILY_SWITCH, the admin as SUPPORT, DEMO-1 as its default broker.

**What V29 changes in existing data**
- Family workspaces (any tenant a copy group is shared with) switch daily.
- Their TRADER becomes OWNER, and the admin's ADMIN membership there becomes SUPPORT.
- A sole follower account becomes the default broker.
- Real broker accounts there get a paper twin.
- The owner's own tenant (SIGNAL-1, PAPER-1, ZERODHA-1) is untouched (ACCOUNT_MODE).

## 2. Add a family member (Family page)

1. **Add member:** username, display name, relationship (spouse, child, parent, sibling: family only), role OWNER.
   - With Keycloak administration, a **one-time password** is shown once. Pass it on in person or by phone, then press
     "hide".
   - With an email server in the realm, Keycloak emails the link instead.
   - Without Keycloak administration, create the Keycloak user in the console first and tick "login already exists".
2. **First sign-in:** the member sets their own password and authenticator app (Keycloak forces both).
3. **Telegram:** on their Settings page they press "Link Telegram" and send the code to the bot. If the bot cannot be
   read (a webhook on it), the admin sets the chat id by hand on the Family page.
4. **Brokers page** (the member, or the admin with their grant):
   - add the broker account (name, broker, client id, capital), then its strategies;
   - create the broker app at the broker under the member's own developer login, registering the redirect URL and
     static IP the page shows;
   - paste the keys (write-only);
   - link the app to the account;
   - log in once.
5. **LIVE eligibility (platform admin, Zerodha only today):** after a login worked, type the account's name to mark it
   LIVE-eligible.
   - Refused unless: client id and app are set, a login has worked, the risk file has live limits, the size step is 0,
     and nothing is open.

## 3. The member's day (Today page)

- **Morning:** every day starts PAPER on the broker chosen last; without a login, the day paper-trades.
- **Logging in:** any time of the day.
  - Zerodha and Upstox: the browser login.
  - Kotak Neo: TOTP and MPIN typed on the Brokers page; passed through, never stored.
  - Groww: approve the API key on Groww's page first, then "Get today's token".
- **Switch LIVE:** type LIVE. Only signals decided after the switch go to the broker.
  - The switch is refused when: not eligible, no session, the member's kill switch is engaged, the loss limit is
    reached, or another broker still holds a live position.
- **Back to PAPER:** any time. Open LIVE positions keep their broker stop and exit at the broker.
- **Changing broker:** any time; the new one starts PAPER.
- **Session lost while LIVE:** the day goes back to PAPER by itself (Telegram to the member and the admin). The member
  logs in again and switches LIVE again if they want.

## 4. Emergencies (the admin, without the member's grant)

- **Family page:** "To PAPER" (new signals paper-trade) or "Kill" (TENANT switch: no new entries on any of the member's
  accounts; exits continue).
- **Brokers or Accounts page:** flatten an account, or exit one position.
- **Anything else** (keys, capital, strategies, LIVE): the member's grant (their Settings page), or **break-glass**
  (Settings page, one hour, with a reason; the member is told on Telegram at once).

## 5. Rollback (only if needed; after hours)

The V29 schema is additive and stays. **Before** starting old code again, take the members' real accounts out of LIVE.
Old code has no daily switch: it would send every entry to every enabled, subscribed account, a LIVE-eligible member
account included.

```sql
update acct.broker_account a set trade_mode = 'PAPER', live_trading_enabled = false
from acct.tenant t where t.id = a.tenant_id and t.trading_control = 'DAILY_SWITCH' and a.broker_type <> 'PAPER';
-- twins would then paper-trade next to their real accounts in old code: disable them (they hold nothing overnight)
update acct.broker_account set enabled = false where paper_of_account_id is not null;
-- the admin's rights in the family workspaces as old code knows them
update acct.tenant_membership m set role = 'ADMIN' where role = 'SUPPORT';
```

Then deploy the previous images (execution first, then trading-core). The keys typed on the Brokers page stay in
`.local/broker-secrets`. Old code reads only `env:` and `file:` references, so those apps cannot log in under old code.

## 6. Checks and logs

- **Audit** (Accounts page, or `exec.audit_log` of the member's tenant):
  - `TODAYS_BROKER`, `MODE_LIVE`, `MODE_PAPER`, `MODE_PAPER_SESSION_LOST`;
  - `SUPPORT_GRANTED`, `SUPPORT_REVOKED`, `SUPPORT_BREAK_GLASS`;
  - `BROKER_APP_*`, `SESSION_LOGIN`, `SESSION_LOGIN_FAILED`, `SESSION_LOGOUT`;
  - `LIVE_ELIGIBLE_ON` / `_OFF`, `TENANT_LOSS_LIMIT`;
  - `MEMBER_*`, `TELEGRAM_*`.
- **The day's switches:** `select * from acct.trading_day_event where tenant_id = ? order by id`.
- **Where an entry would go now:** `select acct.entry_target(<tenant>, now())`.
- **Worker status** (`/api/execution/status`): a member's real broker account shows `IDLE` on days it is not trading
  LIVE (no broker connection, no reconciliation).
