package com.autotrade.exec.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.execution.KillSwitches;

/**
 * A member's broker apps on the Brokers page (trader workspaces, 7 Nov 2026, plan 2.4): what used to be a CLI command and a
 * secret file on the VM, done by the member in their own workspace.
 * <ul>
 *   <li>{@code GET /api/execution/workspaces/{tenant}/broker-apps}: the workspace's apps (never a secret: when the keys
 *       were set and by whom, the key's last 4 characters, the last check) and, per broker, what to register at the
 *       broker (the redirect URL, the static IP);</li>
 *   <li>{@code POST .../broker-apps} {@code {"broker": "UPSTOX", "name": ...}}: a new app;</li>
 *   <li>{@code POST /api/execution/broker-apps/{id}/keys} {@code {"apiKey", "apiSecret", "mobileNumber"}}: the keys,
 *       write-only (to the secret store, {@code store:}); Kotak Neo needs its consumer key and the registered mobile
 *       number, no secret;</li>
 *   <li>{@code POST /api/execution/broker-apps/{id}/status} {@code {"active": false}}: disable (or enable) an app;</li>
 *   <li>{@code POST /api/execution/accounts/{id}/broker-app} {@code {"profileId": n}}: which app an account logs in with
 *       (not while it has an ACTIVE session).</li>
 * </ul>
 * OWNER (or SUPPORT with the member's grant) only; every change audited without a value.
 */
@RestController
class BrokerAppController {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{1,39}");
    /** A key or secret as typed: printable, no spaces, 6-4096 characters (Groww's are long JWT-like strings). */
    private static final Pattern VALUE = Pattern.compile("[\\x21-\\x7E]{6,4096}");
    private static final Pattern MOBILE = Pattern.compile("\\+91[6-9][0-9]{9}");
    private static final List<BrokerType> BROKERS = List.of(BrokerType.ZERODHA, BrokerType.UPSTOX, BrokerType.KOTAK_NEO,
            BrokerType.GROWW);

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KillSwitches audit;
    private final BrokerSecrets secrets;
    private final KiteLoginProperties kite;
    private final BrokerLoginProperties brokers;

    BrokerAppController(Access access, DataSource dataSource, TransactionTemplate tx, KiteLoginProperties kite,
                        BrokerLoginProperties brokers) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = tx;
        this.audit = new KillSwitches(dataSource);
        this.secrets = new BrokerSecrets(Path.of(kite.secretsDir()), Path.of(kite.sessionsDir()), Path.of(brokers.storeDir()));
        this.kite = kite;
        this.brokers = brokers;
    }

    @GetMapping("/api/execution/workspaces/{tenant}/broker-apps")
    Map<String, Object> apps(@RequestHeader(value = Access.HEADER, required = false) String subject,
                             @PathVariable long tenant) {
        Access.User user = access.user(subject);
        seen(user, tenant);
        List<Map<String, Object>> apps = new ArrayList<>();
        for (Map<String, Object> p : jdbc.queryForList("select p.id, p.name, p.broker_type broker, p.status, p.redirect_uri, "
                + "array_to_string(p.registered_ips, ',') ips, p.settings::text settings, p.key_hint, "
                + Iso.of("p.secret_set_at") + " secret_set_at, u.external_subject secret_set_by, " + Iso.of("p.verified_at")
                + " verified_at, "
                + "p.last_check_error, p.api_key_ref, (select coalesce(array_agg(a.display_name order by a.id), '{}') "
                + "from acct.broker_account a where a.credential_profile_id = p.id) accounts "
                + "from acct.broker_credential_profile p left join acct.app_user u on u.id = p.secret_set_by "
                + "where p.tenant_id = ? order by p.id", tenant)) {
            Map<String, Object> a = new LinkedHashMap<>(p);
            String ref = (String) a.remove("api_key_ref");
            // a key held elsewhere (the owner's env: / file: setup) is "set" too; a store: key only once typed
            a.put("keysSet", ref != null && (!ref.startsWith("store:") || secrets.stored(ref)));
            a.put("accounts", p.get("accounts") instanceof java.sql.Array arr ? arrayOf(arr) : List.of());
            Map<String, Object> settings = new LinkedHashMap<>();
            BrokerHttp.JSON.readTree((String) p.get("settings")).properties().forEach(e -> settings.put(e.getKey(),
                    mask(e.getKey(), e.getValue().asString(""))));
            a.put("settings", settings);
            apps.add(a);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apps", apps);
        Map<String, Object> register = new LinkedHashMap<>();
        for (BrokerType b : BROKERS) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("redirectUrl", redirect(b));
            r.put("staticIps", ips());
            r.put("liveCapable", WorkspaceController.LIVE_BROKERS.contains(b));
            register.put(b.name(), r);
        }
        out.put("register", register);
        out.put("canManage", user.manages(tenant));
        return out;
    }

    record NewApp(String broker, String name) {
    }

    @PostMapping("/api/execution/workspaces/{tenant}/broker-apps")
    Map<String, Object> create(@RequestHeader(value = Access.HEADER, required = false) String subject,
                               @PathVariable long tenant, @RequestBody NewApp body) {
        Access.User user = access.user(subject);
        seen(user, tenant);
        manage(user, tenant);
        BrokerType broker;
        try {
            broker = BrokerType.valueOf(String.valueOf(body.broker()).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("broker: ZERODHA, UPSTOX, KOTAK_NEO or GROWW");
        }
        require(BROKERS.contains(broker), "broker: ZERODHA, UPSTOX, KOTAK_NEO or GROWW");
        String name = body.name() == null ? "" : body.name().trim();
        require(NAME.matcher(name).matches(), "name: 2-40 letters, digits, space, _ or -");
        Integer taken = jdbc.queryForObject("select count(*) from acct.broker_credential_profile where tenant_id = ? and name = ?",
                Integer.class, tenant, name);
        require(taken != null && taken == 0, "a broker app named " + name + " already exists");
        long owner = jdbc.queryForList("select app_user_id from acct.tenant_membership where tenant_id = ? and role = 'OWNER' "
                + "order by app_user_id limit 1", Long.class, tenant).stream().findFirst().orElse(user.id());
        long id = tx.execute(t -> {
            long next = jdbc.queryForObject("select nextval(pg_get_serial_sequence('acct.broker_credential_profile', 'id'))",
                    Long.class);
            String key = BrokerSecrets.storeRef(tenant, next, "key");
            // Kotak Neo has no app secret: its consumer key is the only one (the reference is kept for the column)
            String secret = broker == BrokerType.KOTAK_NEO ? key : BrokerSecrets.storeRef(tenant, next, "secret");
            jdbc.update("insert into acct.broker_credential_profile (id, tenant_id, broker_type, owner_app_user_id, scope, "
                    + "name, api_key_ref, api_secret_ref, redirect_uri, registered_ips) values (?, ?, ?, ?, 'USER_PRIVATE', "
                    + "?, ?, ?, ?, ?)", next, tenant, broker.name(), owner, name, key, secret, redirect(broker),
                    ips().toArray(new String[0]));
            return next;
        });
        audit.audit(tenant, null, user.subject(), "BROKER_APP_CREATED", Map.of("app", id, "broker", broker.name(),
                "name", name));
        return Map.of("id", id, "name", name, "broker", broker.name());
    }

    record Keys(String apiKey, String apiSecret, String mobileNumber) {
    }

    @PostMapping("/api/execution/broker-apps/{id}/keys")
    Map<String, Object> keys(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                             @RequestBody Keys body) {
        Access.User user = access.user(subject);
        Map<String, Object> p = profile(user, id);
        long tenant = ((Number) p.get("tenant_id")).longValue();
        manage(user, tenant);
        BrokerType broker = BrokerType.valueOf((String) p.get("broker_type"));
        String keyRef = (String) p.get("api_key_ref"), secretRef = (String) p.get("api_secret_ref");
        notInUseLive(id, "its keys cannot change");
        require(keyRef.startsWith("store:"), "this app's keys are kept on the server (" + keyRef.replaceAll(":.*", ":")
                + "...): they are changed there, not here");
        String key = body.apiKey() == null ? "" : body.apiKey().strip();
        require(VALUE.matcher(key).matches(), broker == BrokerType.KOTAK_NEO
                ? "consumer key: the token from the NEO app (Trade API card), 6-4096 characters without spaces"
                : "API key: 6-4096 characters without spaces");
        String secret = body.apiSecret() == null ? "" : body.apiSecret().strip();
        String mobile = null;
        if (broker == BrokerType.KOTAK_NEO) {
            mobile = body.mobileNumber() == null ? "" : body.mobileNumber().replaceAll("[\\s-]", "");
            if (mobile.matches("[6-9][0-9]{9}")) {
                mobile = "+91" + mobile;
            }
            require(MOBILE.matcher(mobile).matches(), "mobile number: the 10-digit number registered with Kotak");
        } else {
            require(VALUE.matcher(secret).matches(), "API secret: 6-4096 characters without spaces");
            require(!secret.equals(key), "the API secret cannot be the API key");
        }
        secrets.write(keyRef, key);
        if (broker != BrokerType.KOTAK_NEO) {
            secrets.write(secretRef, secret);
        }
        String hint = "..." + key.substring(key.length() - 4);
        String settings = mobile == null ? null : BrokerHttp.JSON.writeValueAsString(Map.of("mobileNumber", mobile));
        jdbc.update("update acct.broker_credential_profile set secret_set_at = now(), secret_set_by = ?, key_hint = ?, "
                + "verified_at = null, last_check_error = null, settings = coalesce(?::jsonb, settings), updated_at = now() "
                + "where id = ?", user.id(), hint, settings, id);
        audit.audit(tenant, null, user.subject(), "BROKER_APP_KEYS_SET", Map.of("app", id, "keyHint", hint));
        return Map.of("id", id, "keyHint", hint, "verified", false);
    }

    record Status(Boolean active) {
    }

    @PostMapping("/api/execution/broker-apps/{id}/status")
    Map<String, Object> status(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                               @RequestBody Status body) {
        Access.User user = access.user(subject);
        Map<String, Object> p = profile(user, id);
        long tenant = ((Number) p.get("tenant_id")).longValue();
        manage(user, tenant);
        require(body.active() != null, "active: true or false");
        if (!body.active()) {
            notInUseLive(id, "it cannot be disabled");
        }
        jdbc.update("update acct.broker_credential_profile set status = ?, updated_at = now() where id = ?",
                body.active() ? "ACTIVE" : "DISABLED", id);
        audit.audit(tenant, null, user.subject(), body.active() ? "BROKER_APP_ENABLED" : "BROKER_APP_DISABLED",
                Map.of("app", id));
        return Map.of("id", id, "status", body.active() ? "ACTIVE" : "DISABLED");
    }

    record Link(Long profileId) {
    }

    @PostMapping("/api/execution/accounts/{id}/broker-app")
    Map<String, Object> link(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                             @RequestBody Link body) {
        Access.User user = access.user(subject);
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
        manage(user, a.tenantId());
        require(a.brokerType() != BrokerType.PAPER, a.displayName() + " is a paper account: it has no broker app");
        require(body.profileId() != null, "profileId is required");
        Map<String, Object> p = profile(user, body.profileId());
        require(((Number) p.get("tenant_id")).longValue() == a.tenantId(), "that app belongs to another workspace");
        require(a.brokerType().name().equals(p.get("broker_type")), "that is a " + p.get("broker_type") + " app, not "
                + a.brokerType());
        require("ACTIVE".equals(p.get("status")), "that app is disabled");
        Integer sessions = jdbc.queryForObject("select count(*) from acct.broker_session where broker_account_id = ? "
                + "and status = 'ACTIVE' and expires_at > now()", Integer.class, id);
        require(sessions == null || sessions == 0, a.displayName() + " is logged in: log out first");
        Integer open = jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ? and status <> "
                + "'CLOSED'", Integer.class, id);
        require(!a.liveTradingEnabled() || open == null || open == 0, a.displayName() + " is LIVE-eligible and holds a "
                + "position: flatten it before changing its broker app");
        Long current = jdbc.queryForObject("select credential_profile_id from acct.broker_account where id = ?", Long.class, id);
        boolean revoke = a.liveTradingEnabled() && !body.profileId().equals(current);
        // another app means the admin's LIVE check no longer holds: PAPER-only again (second review, 7 Nov 2026)
        jdbc.update("update acct.broker_account set credential_profile_id = ?, trade_mode = case when ? then 'PAPER' "
                + "else trade_mode end, live_trading_enabled = live_trading_enabled and not ?, updated_at = now() where id = ?",
                body.profileId(), revoke, revoke, id);
        if (revoke) {
            new com.autotrade.execution.TradingDay(jdbc.getDataSource()).sessionLost(a, user.subject());
            audit.audit(a, user.subject(), "LIVE_ELIGIBLE_OFF", Map.of("reason", "broker app changed"));
        }
        audit.audit(a, user.subject(), "BROKER_APP_LINKED", Map.of("app", body.profileId(), "liveEligibilityRemoved", revoke));
        return Map.of("account", a.displayName(), "app", body.profileId());
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /**
     * Refused while an account using the app holds a live position or has an order working at the broker: its exits and
     * its stop need the app's key and session (code review 7 Nov 2026). Flatten first.
     */
    private void notInUseLive(long profileId, String what) {
        List<String> busy = jdbc.queryForList("select a.display_name from acct.broker_account a where a.credential_profile_id = ? "
                + "and (exists (select 1 from exec.position p where p.broker_account_id = a.id and p.status <> 'CLOSED') "
                + "or exists (select 1 from exec.order_execution e where e.broker_account_id = a.id and e.status in "
                + "('CREATED', 'SUBMITTED', 'ACKNOWLEDGED', 'OPEN', 'PARTIALLY_FILLED', 'CANCEL_PENDING', 'UNKNOWN', "
                + "'RECONCILIATION_REQUIRED')))", String.class, profileId);
        if (!busy.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(", ", busy) + " holds a position or a "
                    + "working order through this app: " + what + " until it is flat");
        }
    }

    /** The broker's redirect URL to register on the app (Kite, Upstox); null for brokers without a redirect. */
    private String redirect(BrokerType broker) {
        if (kite.publicBaseUrl() == null) {
            return null;
        }
        return switch (broker) {
            case ZERODHA -> kite.publicBaseUrl() + "/api/execution/kite/callback";
            case UPSTOX -> kite.publicBaseUrl() + "/api/execution/upstox/callback";
            default -> null;
        };
    }

    private List<String> ips() {
        return brokers.egressIps().isEmpty() ? List.of() : Arrays.stream(brokers.egressIps().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private Map<String, Object> profile(Access.User user, long id) {
        return jdbc.queryForList("select id, tenant_id, broker_type, api_key_ref, api_secret_ref, status from "
                + "acct.broker_credential_profile where id = ?", id).stream()
                .filter(p -> user.sees(((Number) p.get("tenant_id")).longValue())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no broker app " + id));
    }

    private static List<String> arrayOf(java.sql.Array a) {
        try {
            return List.of((String[]) a.getArray());
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }

    /** Settings are not secrets, but a mobile number is shown masked. */
    private static String mask(String key, String value) {
        return key.equals("mobileNumber") && value.length() > 4 ? "******" + value.substring(value.length() - 4) : value;
    }

    private static void seen(Access.User user, long tenant) {
        if (!user.sees(tenant)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + tenant);
        }
    }

    private static void manage(Access.User user, long tenant) {
        Access.require(user.manages(tenant), user.support(tenant) ? "SUPPORT needs the member's grant for broker apps"
                : "only the workspace's OWNER manages its broker apps");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw bad(message);
        }
    }
}
