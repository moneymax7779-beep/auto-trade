package com.autotrade.exec.app;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import com.autotrade.execution.KillSwitches;

/**
 * Who is signed in, and family members (user, 29 Oct 2026: "family only ... start steps 1 and 2"; trader workspaces, 7 Nov
 * 2026). Each family member is their own tenant (workspace), so their brokers, capital, limits, kill switches, positions
 * and audit stay apart; it switches daily (DAILY_SWITCH, V29); the member OWNs it, the platform admin who adds them is
 * SUPPORT there ({@link Access}), and the admin's live signal group is shared with it.
 * <ul>
 *   <li>{@code GET /api/execution/me}: the caller, whether they are the platform admin, their workspaces with their role
 *       and rights there, and their own Telegram link;</li>
 *   <li>{@code GET /api/execution/admin/members}: the family workspaces the caller supports, their members (relationship,
 *       invite, last seen, Telegram) and shared groups;</li>
 *   <li>{@code POST /api/execution/admin/members}: a new member in a new workspace of their own; with Keycloak
 *       administration configured ({@link KeycloakAdmin}) their login too, delivered by Keycloak's email or as a
 *       one-time password shown once in this response (they set their own password and authenticator at first
 *       sign-in); otherwise (or {@code "login": "EXISTING"}) the login is made in the Keycloak console first;</li>
 *   <li>{@code POST .../members/{username}/status} (ACTIVE / DISABLED, the Keycloak login too),
 *       {@code .../one-time-password} (a new one, shown once), {@code .../reset-authenticator} (enrol again),
 *       {@code .../telegram} (set a chat id by hand when the bot cannot be read);</li>
 *   <li>{@code POST /api/execution/me/telegram/link}, {@code .../check}, {@code .../unlink}: the caller links their own
 *       Telegram chat by sending a one-time code to the bot.</li>
 * </ul>
 * Only members wholly within the caller's supported workspaces are changed, never someone who is ADMIN anywhere (the
 * platform admin's own login stays a console job). Passwords are never stored, logged or audited.
 */
@RestController
class MembersController {

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,39}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{1,39}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s]{2,20}");
    private static final Pattern CHAT_ID = Pattern.compile("-?[0-9]{3,20}");
    private static final Set<String> MEMBER_ROLES = Set.of("OWNER", "TRADER", "VIEWER");
    /** Family only (a member is trading on the family's own signals, not a client's): SEBI's family and siblings. */
    private static final Set<String> RELATIONSHIPS = Set.of("SPOUSE", "CHILD", "PARENT", "SIBLING");
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KillSwitches audit;
    private final KeycloakAdmin keycloak;
    private final TelegramNotifier telegram;
    private final SecureRandom random = new SecureRandom();

    MembersController(Access access, DataSource dataSource, TransactionTemplate tx, KeycloakAdmin keycloak,
                      TelegramNotifier telegram) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = tx;
        this.audit = new KillSwitches(dataSource);
        this.keycloak = keycloak;
        this.telegram = telegram;
    }

    @GetMapping("/api/execution/me")
    Map<String, Object> me(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        List<Map<String, Object>> tenants = new ArrayList<>();
        for (long id : user.tenants()) {
            Map<String, Object> t = jdbc.queryForMap("select name, trading_control from acct.tenant where id = ?", id);
            List<String> follows = jdbc.queryForList("select g.name from acct.copy_group g where g.id = any(?) order by g.id",
                    String.class, (Object) accounts.followableCopyGroups(id).toArray(new Long[0]));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", t.get("name"));
            m.put("role", user.roles().get(id));
            m.put("control", t.get("trading_control"));
            m.put("mayAct", user.mayAct(id));
            m.put("manages", user.manages(id));
            m.put("canStop", user.emergency(id));
            m.put("support", user.support(id));
            m.put("helping", user.helping(id));
            m.put("grantUntil", user.support(id) ? jdbc.queryForList("select max(expires_at)::text from acct.support_grant "
                    + "where tenant_id = ? and granted_to = ? and revoked_at is null and expires_at > now()", String.class,
                    id, user.id()).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null) : null);
            m.put("follows", follows);
            tenants.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user.subject());
        out.put("admin", user.admin());
        out.put("tenants", tenants);
        out.put("telegramLinked", jdbc.queryForObject("select telegram_chat_id is not null from acct.app_user where id = ?",
                Boolean.class, user.id()));
        out.put("keycloakAdmin", keycloak.configured());
        return out;
    }

    @GetMapping("/api/execution/admin/members")
    List<Map<String, Object>> members(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "needs the platform admin");
        List<Map<String, Object>> out = new ArrayList<>();
        for (long tenant : supported(user)) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tenantId", tenant);
            t.put("tenant", jdbc.queryForObject("select name from acct.tenant where id = ?", String.class, tenant));
            t.put("members", jdbc.queryForList("select u.external_subject username, u.display_name, u.status, m.role, "
                    + "u.relationship, u.last_seen_at::text last_seen, u.telegram_chat_id is not null telegram, "
                    + "(select i.status from acct.invite i where i.username = u.external_subject order by i.id desc limit 1) "
                    + "invite from acct.tenant_membership m join acct.app_user u on u.id = m.app_user_id where m.tenant_id = ? "
                    + "order by case m.role when 'OWNER' then 0 when 'TRADER' then 1 when 'VIEWER' then 2 else 3 end, "
                    + "u.external_subject", tenant));
            t.put("accounts", jdbc.queryForObject("select count(*) from acct.broker_account where tenant_id = ? "
                    + "and account_role = 'FOLLOWER_TRADE' and paper_of_account_id is null", Integer.class, tenant));
            t.put("follows", jdbc.queryForList("select g.id, g.name, t.name owner from acct.copy_group g join acct.tenant t "
                    + "on t.id = g.tenant_id where g.id in (select copy_group_id from acct.copy_group_share where tenant_id = ?) "
                    + "or g.tenant_id = ? order by g.id", tenant, tenant));
            out.add(t);
        }
        return out;
    }

    /**
     * {@code role}: the member's role in their own workspace (OWNER, default: runs it; TRADER; VIEWER). {@code relationship}:
     * SPOUSE, CHILD, PARENT or SIBLING (family only). {@code login}: CREATE (default when Keycloak administration is
     * configured) or EXISTING (the Keycloak user was made in the console).
     */
    record NewMember(String username, String displayName, String role, String tenantName, String relationship,
                     String email, String login) {
    }

    @PostMapping("/api/execution/admin/members")
    Map<String, Object> add(@RequestHeader(value = Access.HEADER, required = false) String subject,
                            @RequestBody NewMember body) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "needs the platform admin");
        String username = body.username() == null ? "" : body.username().trim().toLowerCase(Locale.ROOT);
        require(USERNAME.matcher(username).matches(), "username: 3-40 of a-z, 0-9, . _ - (their Keycloak username)");
        String display = body.displayName() == null || body.displayName().isBlank() ? username : body.displayName().trim();
        require(NAME.matcher(display).matches(), "display name: 2-40 letters, digits, space, . _ -");
        String role = body.role() == null || body.role().isBlank() ? "OWNER" : body.role().trim().toUpperCase(Locale.ROOT);
        require(MEMBER_ROLES.contains(role), "role: OWNER, TRADER or VIEWER");
        String relationship = body.relationship() == null ? "" : body.relationship().trim().toUpperCase(Locale.ROOT);
        require(RELATIONSHIPS.contains(relationship), "relationship: SPOUSE, CHILD, PARENT or SIBLING (family only)");
        String email = body.email() == null || body.email().isBlank() ? null : body.email().trim();
        require(email == null || EMAIL.matcher(email).matches(), "email: not an email address");
        String tenantName = body.tenantName() == null || body.tenantName().isBlank() ? "family-" + username
                : body.tenantName().trim();
        require(NAME.matcher(tenantName).matches(), "workspace name: 2-40 letters, digits, space, . _ -");
        String login = body.login() == null || body.login().isBlank() ? (keycloak.configured() ? "CREATE" : "EXISTING")
                : body.login().trim().toUpperCase(Locale.ROOT);
        require(login.equals("CREATE") || login.equals("EXISTING"), "login: CREATE or EXISTING");
        require(!login.equals("CREATE") || keycloak.configured(), "Keycloak administration is not configured on the "
                + "execution service: make the login in the Keycloak console and add the member with login EXISTING");
        Integer taken = jdbc.queryForObject("select count(*) from acct.app_user where external_subject = ?", Integer.class,
                username);
        require(taken != null && taken == 0, username + " already exists");
        Integer tenantTaken = jdbc.queryForObject("select count(*) from acct.tenant where name = ?", Integer.class, tenantName);
        require(tenantTaken != null && tenantTaken == 0, "a workspace named " + tenantName + " already exists");
        // the admin's live signal group (LIVE-SET), not drills (TEE-TEST, CHAOS); every enabled group only when there is none
        Long[] home = home(user).toArray(new Long[0]);
        List<Long> groups = jdbc.queryForList("select id from acct.copy_group where enabled and tenant_id = any(?) "
                + "and name = 'LIVE-SET' order by id", Long.class, (Object) home);
        if (groups.isEmpty()) {
            groups = jdbc.queryForList("select id from acct.copy_group where enabled and tenant_id = any(?) order by id",
                    Long.class, (Object) home);
        }
        List<Long> follows = groups;

        // the login first: when the database step fails after it, the admin adds the member again with login EXISTING
        KeycloakAdmin.Delivery delivery;
        try {
            delivery = login.equals("CREATE") ? keycloak.create(username, display, email)
                    : new KeycloakAdmin.Delivery(null, "EXISTING_LOGIN", null);
        } catch (KeycloakAdmin.Failure e) {
            throw new ResponseStatusException(e.status == 409 ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY,
                    e.getMessage() + (e.status == 409 ? " (add them with login EXISTING to use that login)" : ""));
        }
        Map<String, Object> created = tx.execute(t -> {
            long tenant = accounts.ensureTenant(tenantName);
            jdbc.update("update acct.tenant set trading_control = 'DAILY_SWITCH' where id = ?", tenant);
            long member = accounts.ensureUser(username, display);
            jdbc.update("update acct.app_user set relationship = ? where id = ?", relationship, member);
            accounts.ensureMembership(tenant, member, role);
            accounts.ensureMembership(tenant, user.id(), "SUPPORT");
            for (long group : follows) {
                accounts.shareCopyGroup(group, tenant, user.id());
            }
            jdbc.update("insert into acct.invite (username, display_name, relationship, email, tenant_id, keycloak_user_id, "
                    + "delivery, created_by) values (?, ?, ?, ?, ?, ?, ?, ?)", username, display, relationship, email, tenant,
                    delivery.keycloakUserId(), delivery.delivery(), user.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenantId", tenant);
            m.put("username", username);
            m.put("role", role);
            m.put("relationship", relationship);
            m.put("follows", follows);
            m.put("delivery", delivery.delivery());
            return m;
        });
        audit.audit((Long) created.get("tenantId"), null, user.subject(), "MEMBER_ADDED", created);
        Map<String, Object> out = new LinkedHashMap<>(created);
        if (delivery.oneTimePassword() != null) {
            out.put("oneTimePassword", delivery.oneTimePassword());     // shown once; never stored or audited
        }
        return out;
    }

    record Status(String status) {
    }

    @PostMapping("/api/execution/admin/members/{username}/status")
    Map<String, Object> status(@RequestHeader(value = Access.HEADER, required = false) String subject,
                               @PathVariable String username, @RequestBody Status body) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "needs the platform admin");
        String status = body.status() == null ? "" : body.status().trim().toUpperCase(Locale.ROOT);
        require(status.equals("ACTIVE") || status.equals("DISABLED"), "status: ACTIVE or DISABLED");
        require(!username.equals(user.subject()), "you cannot change your own status");
        Integer signals = jdbc.queryForObject("select count(*) from acct.broker_account a join acct.app_user u on u.id = "
                + "a.app_user_id where u.external_subject = ? and a.account_role = 'SIGNAL_SOURCE'", Integer.class, username);
        require(signals == null || signals == 0, username + " owns the signal source (and is the no-login mode's user): "
                + "it is never disabled here");
        List<Long> theirs = memberOf(user, username);
        jdbc.update("update acct.app_user set status = ? where external_subject = ?", status, username);
        String login = "not changed (no Keycloak administration)";
        if (keycloak.configured()) {
            try {
                String id = keycloak.userId(username);
                if (id != null) {
                    keycloak.enabled(id, status.equals("ACTIVE"));
                    login = status.equals("ACTIVE") ? "enabled" : "disabled and signed out";
                } else {
                    login = "no Keycloak user " + username;
                }
            } catch (KeycloakAdmin.Failure e) {
                login = "not changed: " + e.getMessage();              // the app's own refusal already applies
            }
        }
        for (long tenant : theirs) {
            audit.audit(tenant, null, user.subject(), "MEMBER_" + status, Map.of("username", username, "login", login));
        }
        return Map.of("username", username, "status", status, "login", login);
    }

    /** A new one-time password for a member (the old password stops working), shown once. */
    @PostMapping("/api/execution/admin/members/{username}/one-time-password")
    Map<String, Object> oneTimePassword(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                        @PathVariable String username) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "needs the platform admin");
        List<Long> theirs = memberOf(user, username);
        KeycloakAdmin.Delivery d = keycloakCall(() -> keycloak.resend(keycloakId(username)));
        for (long tenant : theirs) {
            audit.audit(tenant, null, user.subject(), "MEMBER_ONE_TIME_PASSWORD", Map.of("username", username));
        }
        jdbc.update("insert into acct.invite (username, display_name, relationship, tenant_id, keycloak_user_id, delivery, "
                + "created_by) select u.external_subject, u.display_name, coalesce(u.relationship, 'UNKNOWN'), ?, ?, "
                + "'TEMP_PASSWORD', ? from acct.app_user u where u.external_subject = ?", theirs.getFirst(), d.keycloakUserId(),
                user.id(), username);
        return Map.of("username", username, "oneTimePassword", d.oneTimePassword());
    }

    /** Removes a member's authenticator app (a lost phone): they enrol a new one at the next sign-in. */
    @PostMapping("/api/execution/admin/members/{username}/reset-authenticator")
    Map<String, Object> resetAuthenticator(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                           @PathVariable String username) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "needs the platform admin");
        List<Long> theirs = memberOf(user, username);
        int removed = keycloakCall(() -> keycloak.resetAuthenticator(keycloakId(username)));
        for (long tenant : theirs) {
            audit.audit(tenant, null, user.subject(), "MEMBER_AUTHENTICATOR_RESET", Map.of("username", username,
                    "removed", removed));
        }
        return Map.of("username", username, "authenticatorsRemoved", removed);
    }

    record ChatId(String chatId) {
    }

    /** Sets (or with a blank id clears) a member's Telegram chat by hand: when the bot's messages cannot be read. */
    @PostMapping("/api/execution/admin/members/{username}/telegram")
    Map<String, Object> memberTelegram(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                       @PathVariable String username, @RequestBody ChatId body) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "needs the platform admin");
        List<Long> theirs = memberOf(user, username);
        String chat = body.chatId() == null || body.chatId().isBlank() ? null : body.chatId().trim();
        require(chat == null || CHAT_ID.matcher(chat).matches(), "chat id: digits (a private chat's id)");
        jdbc.update("update acct.app_user set telegram_chat_id = ? where external_subject = ?", chat, username);
        for (long tenant : theirs) {
            audit.audit(tenant, null, user.subject(), chat == null ? "TELEGRAM_UNLINKED" : "TELEGRAM_LINKED",
                    Map.of("username", username, "by", "admin"));
        }
        return Map.of("username", username, "linked", chat != null);
    }

    // ------------------------------------------------------------------------------------------------- own Telegram

    @PostMapping("/api/execution/me/telegram/link")
    Map<String, Object> linkTelegram(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        require(telegram.configured(), "the Telegram bot is not set up on this server");
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        jdbc.update("delete from acct.telegram_link where app_user_id = ? and used_at is null", user.id());
        jdbc.update("insert into acct.telegram_link (code, app_user_id, expires_at) values (?, ?, now() + interval "
                + "'30 minutes')", code.toString(), user.id());
        String bot = telegram.botUsername();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code.toString());
        out.put("bot", bot);
        out.put("link", bot == null ? null : "https://t.me/" + bot + "?start=" + code);
        out.put("send", "/start " + code);
        out.put("expiresInMinutes", 30);
        return out;
    }

    @PostMapping("/api/execution/me/telegram/check")
    Map<String, Object> checkTelegram(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        List<String> codes = jdbc.queryForList("select code from acct.telegram_link where app_user_id = ? and used_at is null "
                + "and expires_at > now()", String.class, user.id());
        require(!codes.isEmpty(), "no link code waiting: start the link again");
        for (TelegramNotifier.StartMessage m : telegram.startMessages()) {
            if (codes.contains(m.code())) {
                tx.executeWithoutResult(t -> {
                    jdbc.update("update acct.telegram_link set used_at = now() where code = ?", m.code());
                    jdbc.update("update acct.app_user set telegram_chat_id = ? where id = ?", m.chatId(), user.id());
                });
                for (long tenant : user.tenants()) {
                    audit.audit(tenant, null, user.subject(), "TELEGRAM_LINKED", Map.of("username", user.subject()));
                }
                telegram.sendToUser(user.id(), "auto-trade: this chat now gets " + user.subject() + "'s login links and "
                        + "alerts.");
                return Map.of("linked", true);
            }
        }
        return Map.of("linked", false, "hint", "send the code to the bot first (it can take a few seconds to arrive)");
    }

    @PostMapping("/api/execution/me/telegram/unlink")
    Map<String, Object> unlinkTelegram(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        jdbc.update("update acct.app_user set telegram_chat_id = null where id = ?", user.id());
        for (long tenant : user.tenants()) {
            audit.audit(tenant, null, user.subject(), "TELEGRAM_UNLINKED", Map.of("username", user.subject()));
        }
        return Map.of("linked", false);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /**
     * The tenants of {@code username}, who must be wholly within the caller's supported workspaces and ADMIN nowhere (404
     * otherwise: not revealed).
     */
    private List<Long> memberOf(Access.User user, String username) {
        List<Map<String, Object>> rows = jdbc.queryForList("select m.tenant_id, m.role from acct.tenant_membership m "
                + "join acct.app_user u on u.id = m.app_user_id where u.external_subject = ?", username);
        List<Long> theirs = rows.stream().map(r -> ((Number) r.get("tenant_id")).longValue()).toList();
        boolean admin = rows.stream().anyMatch(r -> "ADMIN".equals(r.get("role")) || "SUPPORT".equals(r.get("role")));
        if (theirs.isEmpty() || admin || !supported(user).containsAll(theirs)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no member " + username);
        }
        return theirs;
    }

    private String keycloakId(String username) {
        String id = keycloak.userId(username);
        if (id == null) {
            throw new KeycloakAdmin.Failure(404, "no Keycloak user " + username);
        }
        return id;
    }

    private static <T> T keycloakCall(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (KeycloakAdmin.Failure e) {
            throw new ResponseStatusException(e.status == 404 ? HttpStatus.NOT_FOUND : e.status == 503
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /** Family workspaces the caller supports: SUPPORT there, or (before V29) ADMIN of a tenant a group is shared with. */
    private List<Long> supported(Access.User user) {
        return user.tenants().stream().filter(t -> user.support(t) || "ADMIN".equals(user.roles().get(t)) && shared(t))
                .toList();
    }

    /** The caller's own tenants (OWNER or ADMIN) that are not a family member's: whose signal groups a new member follows. */
    private List<Long> home(Access.User user) {
        return user.tenants().stream().filter(user::manages).filter(t -> !user.support(t) && !shared(t)).toList();
    }

    private boolean shared(long tenant) {
        Integer n = jdbc.queryForObject("select count(*) from acct.copy_group_share where tenant_id = ?", Integer.class, tenant);
        return n != null && n > 0;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
