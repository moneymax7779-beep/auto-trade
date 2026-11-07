#!/bin/bash
# Per-user logins (L3, user 24 Oct 2026: "start per-user logins with Keycloak"): creates or updates the Keycloak realm
# "autotrade" that trading-core signs users in with (autotrade.auth.mode=oidc). Idempotent; run on the host that runs the
# Keycloak container. It runs kcadm inside the container with the container's own bootstrap admin, so no admin password
# is passed or stored here.
#
#   bin/keycloak/bootstrap.sh --app-url http://localhost:8098 [--app-url https://host] --secret-file .local/keycloak/client-secret [--no-totp]
#                             [--admin-secret-file .local/keycloak/admin-client-secret]
#
# What it sets:
#   realm autotrade: no self-registration, usernames fixed, brute-force lockout, password policy (12+, not the username),
#                    an authenticator app (TOTP) required for every new user (unless --no-totp)
#   role  operator:  may use the PAPER system controls (kill, release, stop, exit-all); give it with
#                    kcadm add-roles -r autotrade --uusername <user> --rolename operator
#   client autotrade-web: confidential, authorization code only, redirect <app-url>/login/oauth2/code/keycloak; the
#                    realm roles go into a "roles" claim
#   client autotrade-admin (with --admin-secret-file; trader workspaces, 7 Nov 2026): confidential, service account
#                    only (no browser sign-in), realm-management roles manage-users, view-users, query-users and
#                    view-realm, so the execution service creates family members' logins (one-time password, authenticator
#                    at first sign-in), resets an authenticator and enables/disables a login, instead of the admin console.
#                    Its secret goes to --admin-secret-file (mode 600); the execution service reads it from
#                    AUTOTRADE_EXECUTION_KEYCLOAK_CLIENT_SECRET.
# The client secret is written to --secret-file (mode 600), never printed; trading-core reads it from
# AUTOTRADE_AUTH_CLIENT_SECRET. Without autotrade-admin, users are created by the owner in the Keycloak admin console
# (Users > Add user), and each one needs an acct.app_user row whose external_subject is the Keycloak username, plus a
# tenant membership (the Members panel's "existing login").
set -euo pipefail
CONTAINER=${KEYCLOAK_CONTAINER:-autotrade-keycloak}
REALM=autotrade
CLIENT=autotrade-web
APP_URLS=(); SECRET_FILE=""; TOTP=true; ADMIN_SECRET_FILE=""
ADMIN_CLIENT=autotrade-admin
while [ $# -gt 0 ]; do
  case "$1" in
    --app-url) APP_URLS+=("${2%/}"); shift 2 ;;
    --secret-file) SECRET_FILE="$2"; shift 2 ;;
    --no-totp) TOTP=false; shift ;;
    --admin-secret-file) ADMIN_SECRET_FILE="$2"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ ${#APP_URLS[@]} -gt 0 ] || { echo "--app-url is required" >&2; exit 2; }
[ -n "$SECRET_FILE" ] || { echo "--secret-file is required" >&2; exit 2; }

redirects=""; logouts=""; origins=""
for u in "${APP_URLS[@]}"; do
  redirects+="\"$u/login/oauth2/code/keycloak\","; logouts+="$u/##"; origins+="\"$u\","
done
redirects="[${redirects%,}]"; origins="[${origins%,}]"; logouts="${logouts%##}"

# kcadm inside the container; the admin password stays in the container's environment.
docker exec "$CONTAINER" bash -c '/opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm.config \
  --server "http://localhost:${KC_HTTP_PORT:-8180}${KC_HTTP_RELATIVE_PATH:-/auth}" --realm master \
  --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' >/dev/null
kc() { docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config < /dev/null; }
kcin() { docker exec -i "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config; }   # JSON on stdin

REALM_JSON='{"realm":"autotrade","enabled":true,"displayName":"auto-trade","registrationAllowed":false,
 "editUsernameAllowed":false,"resetPasswordAllowed":false,"loginWithEmailAllowed":false,"duplicateEmailsAllowed":false,
 "rememberMe":false,"sslRequired":"external","bruteForceProtected":true,"permanentLockout":false,"failureFactor":5,
 "maxFailureWaitSeconds":900,"waitIncrementSeconds":60,"passwordPolicy":"length(12) and notUsername",
 "ssoSessionIdleTimeout":28800,"ssoSessionMaxLifespan":43200,"otpPolicyType":"totp","otpPolicyAlgorithm":"HmacSHA1",
 "otpPolicyDigits":6,"otpPolicyPeriod":30}'
if kc get "realms/$REALM" --fields realm >/dev/null 2>&1; then
  echo "$REALM_JSON" | kcin update "realms/$REALM" -f - && echo "realm $REALM updated"
else
  echo "$REALM_JSON" | kcin create realms -f - && echo "realm $REALM created"
fi

# Email is not needed to sign in here (usernames are the identity): optional in the user profile, so a new user is not
# stopped at "Update Account Information".
kc get "realms/$REALM/users/profile" | python3 -c '
import json, sys
p = json.load(sys.stdin)
for a in p.get("attributes", []):
    if a.get("name") == "email":
        a.pop("required", None)
print(json.dumps(p))' | kcin update "realms/$REALM/users/profile" -f - >/dev/null && echo "email optional"

kc get "roles/operator" -r "$REALM" >/dev/null 2>&1 \
  || { kc create roles -r "$REALM" -s name=operator -s 'description=PAPER system controls: kill, release, stop, exit-all' && echo "role operator created"; }

kc update authentication/required-actions/CONFIGURE_TOTP -r "$REALM" -s enabled=true -s defaultAction=$TOTP
echo "authenticator app required for new users: $TOTP"

CLIENT_JSON="{\"clientId\":\"$CLIENT\",\"name\":\"auto-trade\",\"enabled\":true,\"protocol\":\"openid-connect\",
 \"publicClient\":false,\"clientAuthenticatorType\":\"client-secret\",\"standardFlowEnabled\":true,
 \"implicitFlowEnabled\":false,\"directAccessGrantsEnabled\":false,\"serviceAccountsEnabled\":false,
 \"redirectUris\":$redirects,\"webOrigins\":$origins,\"frontchannelLogout\":true,
 \"attributes\":{\"post.logout.redirect.uris\":\"$logouts\",\"pkce.code.challenge.method\":\"S256\"}}"
CID=$(kc get clients -r "$REALM" -q "clientId=$CLIENT" --fields id --format csv --noquotes | head -1)
if [ -n "$CID" ]; then
  echo "$CLIENT_JSON" | kcin update "clients/$CID" -r "$REALM" -f - && echo "client $CLIENT updated"
else
  echo "$CLIENT_JSON" | kcin create clients -r "$REALM" -f - >/dev/null && echo "client $CLIENT created"
  CID=$(kc get clients -r "$REALM" -q "clientId=$CLIENT" --fields id --format csv --noquotes | head -1)
fi

if ! kc get "clients/$CID/protocol-mappers/models" -r "$REALM" --fields name --format csv --noquotes | grep -qx "realm roles"; then
  kcin create "clients/$CID/protocol-mappers/models" -r "$REALM" -f - >/dev/null <<'JSON'
{"name":"realm roles","protocol":"openid-connect","protocolMapper":"oidc-usermodel-realm-role-mapper",
 "config":{"claim.name":"roles","multivalued":"true","jsonType.label":"String","id.token.claim":"true",
 "access.token.claim":"true","userinfo.token.claim":"true"}}
JSON
  echo "roles claim mapper created"
fi

umask 077
mkdir -p "$(dirname "$SECRET_FILE")"
kc get "clients/$CID/client-secret" -r "$REALM" --fields value --format csv --noquotes > "$SECRET_FILE.tmp"
[ -s "$SECRET_FILE.tmp" ] || { rm -f "$SECRET_FILE.tmp"; echo "could not read the client secret" >&2; exit 1; }
mv "$SECRET_FILE.tmp" "$SECRET_FILE"; chmod 600 "$SECRET_FILE"
echo "client secret written to $SECRET_FILE (mode 600)"
echo "redirects: $redirects"

# The execution service's own client (trader workspaces): service account only, user management, nothing else.
if [ -n "$ADMIN_SECRET_FILE" ]; then
  ADMIN_JSON="{\"clientId\":\"$ADMIN_CLIENT\",\"name\":\"auto-trade execution (members' logins)\",\"enabled\":true,
   \"protocol\":\"openid-connect\",\"publicClient\":false,\"clientAuthenticatorType\":\"client-secret\",
   \"standardFlowEnabled\":false,\"implicitFlowEnabled\":false,\"directAccessGrantsEnabled\":false,
   \"serviceAccountsEnabled\":true}"
  ACID=$(kc get clients -r "$REALM" -q "clientId=$ADMIN_CLIENT" --fields id --format csv --noquotes | head -1)
  if [ -n "$ACID" ]; then
    echo "$ADMIN_JSON" | kcin update "clients/$ACID" -r "$REALM" -f - && echo "client $ADMIN_CLIENT updated"
  else
    echo "$ADMIN_JSON" | kcin create clients -r "$REALM" -f - >/dev/null && echo "client $ADMIN_CLIENT created"
    ACID=$(kc get clients -r "$REALM" -q "clientId=$ADMIN_CLIENT" --fields id --format csv --noquotes | head -1)
  fi
  kc add-roles -r "$REALM" --uusername "service-account-$ADMIN_CLIENT" --cclientid realm-management \
    --rolename manage-users --rolename view-users --rolename query-users --rolename view-realm
  echo "service account roles: manage-users, view-users, query-users, view-realm"
  umask 077
  mkdir -p "$(dirname "$ADMIN_SECRET_FILE")"
  kc get "clients/$ACID/client-secret" -r "$REALM" --fields value --format csv --noquotes > "$ADMIN_SECRET_FILE.tmp"
  [ -s "$ADMIN_SECRET_FILE.tmp" ] || { rm -f "$ADMIN_SECRET_FILE.tmp"; echo "could not read the admin client secret" >&2; exit 1; }
  mv "$ADMIN_SECRET_FILE.tmp" "$ADMIN_SECRET_FILE"; chmod 600 "$ADMIN_SECRET_FILE"
  echo "admin client secret written to $ADMIN_SECRET_FILE (mode 600)"
fi
