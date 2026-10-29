# Family members: turning on logins and member accounts on GCP

Branch `feature/multi-user` (29 Oct 2026). Nothing here runs without the user's go-ahead; deploys only outside
08:50–15:50 IST. The user does every step that involves a password; Claude never sees one.

## Once: Keycloak on the VM

1. A database for Keycloak in autotrade-postgres (own database): `create database keycloak; create user keycloak ...`
   with a password the user puts in `/srv/auto-trade/.env` as `KC_DB_PASSWORD` (never echoed).
2. Keycloak in `start` mode (not dev): `KC_DB=postgres`, `KC_DB_URL=jdbc:postgresql://postgres:5432/keycloak`,
   `KC_HTTP_RELATIVE_PATH=/auth`, `KC_HOSTNAME=https://autotrade.136-82-45-194.sslip.io/auth`,
   `KC_PROXY_HEADERS=xforwarded`, `KC_HTTP_ENABLED=true` on 127.0.0.1:8180 only, the bootstrap admin password from
   `.env` (the user's).
3. nginx: `location /auth/ { proxy_pass http://127.0.0.1:8180/auth/; proxy_set_header Host $host;
   proxy_set_header X-Forwarded-Proto https; proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; }`.
4. Realm: `bin/keycloak/bootstrap.sh --app-url https://autotrade.136-82-45-194.sslip.io --secret-file
   .local/keycloak/client-secret` (mode 600; the secret goes to `.env` as `AUTOTRADE_AUTH_CLIENT_SECRET` by reference).
5. The user creates their own Keycloak user with the realm role `operator` and signs in once to set up the authenticator.

## Once: link the owner

The signal source creates its owner as `local-owner` unless told otherwise. With the owner's Keycloak username `U`:

```sql
update acct.app_user set external_subject = 'U' where external_subject = 'local-owner';
update acct.tenant_membership set role = 'ADMIN' where tenant_id = 1
  and app_user_id = (select id from acct.app_user where external_subject = 'U');
```

and set `AUTOTRADE_SIGNALSOURCE_OWNERSUBJECT=U` in `.env`, so the next signal-source session does not create
`local-owner` again.

## The switch

- Merge `feature/multi-user` into main; `.local/deploy-exec.sh` with `SCHEMA=27`; trading-core with
  `AUTOTRADE_AUTH_MODE=oidc`, `AUTOTRADE_AUTH_PUBLIC_BASE=https://autotrade.136-82-45-194.sslip.io/auth`.
- Check: signed out → the Keycloak page; the owner signs in (code from the app) and sees every page with the OP badge;
  the Accounts page shows the Family members panel.
- Keep the nginx password gate until a member has signed in successfully, then remove it for `/` (keep it on
  `/auth/admin/` if wanted).
- Rollback: `AUTOTRADE_AUTH_MODE=none` and recreate trading-core (the gate is still there).

## Adding a family member

1. In Keycloak (the user): a new user with their username, a temporary password, no `operator` role. They sign in once
   to set their password and their authenticator app.
2. In the UI, Accounts → Family members: add the same username, role TRADER (logs in to the broker, kill, flatten) or
   OWNER (also changes capital and strategies) or VIEWER (reads only). They get their own tenant that follows LIVE-SET.
3. Add their account (Add account → Whose account: theirs). PAPER until step 3 (their own broker connection) exists.
4. They sign in and see only "My accounts". Disable them in the same panel; every call is refused at once.
