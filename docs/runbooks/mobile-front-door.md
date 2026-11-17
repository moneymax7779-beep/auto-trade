# Mobile front door: a real domain and the password gate (mobile phase 0)

Plan: `docs/plans/2026-11-17-mobile-app-plan.html` (phase 0). Nothing here runs without the user's go-ahead; every
step that touches DNS, the load balancer, nginx or `.env` on the VM is the user's decision and is done outside
08:50–15:50 IST. Claude never sees a password or secret.

## Why

- Passkeys (WebAuthn) and web push are bound to the site's domain. `autotrade.136-82-45-194.sslip.io` is derived from
  the load balancer's IP: if that IP ever changes, every phone's passkey and push subscription stop working.
- The nginx basic-auth gate asks for a second, shared password before Keycloak. On a phone that is two sign-ins, and an
  app added to the iPhone home screen cannot fetch its manifest or service worker through a basic-auth prompt.

## Decision 1: the domain

1. The user buys a domain (any registrar) and creates an `A` record, e.g. `app.<domain>` → the HTTPS load balancer's
   IP (the IP in today's sslip.io name).
2. GCP: add a Google-managed certificate for `app.<domain>` to the load balancer's HTTPS proxy, next to the current
   one, and wait until it is ACTIVE (can take up to an hour). Keep the sslip.io certificate during the switch.
3. VM `.env` (root 600): `AUTOTRADE_PUBLIC_HOST=app.<domain>` and
   `AUTOTRADE_AUTH_PUBLIC_BASE=https://app.<domain>/auth`. `docker-compose.gcp.yml` reads `AUTOTRADE_PUBLIC_HOST` for
   the execution service's public base URL (broker callbacks) and Keycloak's `KC_HOSTNAME`; without it both stay on
   the sslip.io name.
4. Keycloak (admin console through the SSH tunnel): client `autotrade-web` → add
   `https://app.<domain>/login/oauth2/code/keycloak` to Valid redirect URIs and `https://app.<domain>` to Web origins and
   Valid post logout redirect URIs. Keep the sslip.io entries until step 7.
5. Broker apps: the Brokers page shows the new redirect URL once the execution service runs with the new base URL.
   Kite: developers.kite.trade → the app → Redirect URL. Upstox: developer console → the app → Redirect URL. Kite allows
   one redirect URL per app, so do this right after step 6 and before the next morning's login.
6. Recreate keycloak, trading-core and execution with the new `.env` (deploy script or
   `docker compose ... up -d --no-build keycloak trading-core execution`), then sign in at `https://app.<domain>`.
7. After a full market day on the new name: remove the sslip.io redirect URIs from Keycloak; keep its certificate on
   the load balancer for a month so old bookmarks still load (they redirect to Keycloak on the new name).
8. Update `bin/keycloak/bootstrap.sh` defaults, `docs/runbooks/family-members.md` and the SameOriginFilter test host
   when the switch is final.

Rollback: remove `AUTOTRADE_PUBLIC_HOST` / restore `AUTOTRADE_AUTH_PUBLIC_BASE` in `.env` and recreate the three
services; the sslip.io name keeps working throughout because its certificate and redirect URIs stay until step 7.

## Decision 2: the basic-auth gate

Keycloak already requires a 12+ character password, an authenticator code (TOTP), and locks out brute force; every
`/api/` route needs its session (`SecurityConfig`). The gate adds a shared password in front of that.

1. Back up the site file: `sudo cp /etc/nginx/sites-available/zt-autotrade{,.bak-<date>}`.
2. Remove `auth_basic` / `auth_basic_user_file` from `location /` and `location /auth/`. Keep the Keycloak admin
   console unreachable from the internet as today (it is served only on 127.0.0.1:8180 through the SSH tunnel; add
   `location /auth/admin/ { return 404; }` as a second guard).
3. The VM's startup script rewrites the nginx site on every boot (30 Oct incident): make the same change in the GCE
   metadata `startup-script`, or the gate returns at the next boot.
4. `sudo nginx -t && sudo systemctl reload nginx`; check signed out → Keycloak page, no basic-auth prompt.

Rollback: restore the `.bak` file and the startup-script metadata, reload nginx.

## Phone notifications (web push, mobile phase 4)

Off by default: nothing is sent and no key exists until `autotrade.execution.push.enabled` is on.

1. Needs the real domain first (decision 1): a phone's push subscription is bound to the site's address.
2. VM `.env`: `AUTOTRADE_EXECUTION_PUSH_ENABLED=true` (and optionally `AUTOTRADE_EXECUTION_PUSH_SUBJECT=mailto:<you>`;
   default: the site's public address), passed to the execution service in `docker-compose.gcp.yml`, then recreate it.
3. The first use makes the server's VAPID key pair (`acct.push_vapid`, V47). Each person turns notifications on under
   More → Notifications; on an iPhone only after Add to Home Screen (Safari → Share → Add to Home Screen).
4. Check: More → Notifications → Send a test reaches the phone; the 08:30 Kite login reminder arrives the next morning.

Rollback: the flag off (nothing is sent); `delete from acct.push_subscription` forgets every phone.

## Passkeys (Face ID / fingerprint sign-in, mobile phase 4)

Keycloak configuration, done by the user in the admin console (SSH tunnel, http://localhost:8188/auth/admin/, realm
`autotrade`); nothing in the app changes. Needs the real domain first: a passkey is bound to the domain it was made on.

1. Authentication → Policies → Webauthn Passwordless Policy: Relying Party name `auto-trade`, User verification
   requirement `required`, Resident key `Yes` (a passkey), and the Relying Party ID = the domain (e.g. `app.<domain>`).
2. Authentication → Required actions: enable "Webauthn Register Passwordless" (not default).
3. Authentication → Flows: duplicate "browser" as "browser-passkeys"; in its forms subflow add "WebAuthn Passwordless
   Authenticator" as ALTERNATIVE next to the password form (keep "OTP Form" CONDITIONAL after the password), then
   Bind flow → Browser flow.
4. Each person: Account console (`/auth/realms/autotrade/account`) → Signing in → Passkey → Set up, on their phone.
5. Check: sign out, sign in on the phone with the passkey; the password + authenticator code still works as fallback.

Rollback: bind the original "browser" flow again.
