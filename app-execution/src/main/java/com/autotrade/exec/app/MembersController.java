package com.autotrade.exec.app;

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
 * Who is signed in, and family members (user, 29 Oct 2026: "family only ... start steps 1 and 2"). Each family member is
 * their own tenant, so their capital, limits, kill switches, positions and audit stay apart; the ADMIN who adds them is
 * also ADMIN of that tenant (to help and, in an emergency, flatten), and shares their own signal groups with it so the
 * member's accounts can follow the live set. The person's login (password, authenticator app) is made in Keycloak by the
 * admin; nothing here handles a password.
 * <ul>
 *   <li>{@code GET /api/execution/me}: the caller, whether they are an ADMIN, and their tenants with their role and the
 *       signal groups they follow;</li>
 *   <li>{@code GET /api/execution/admin/members}: the tenants the caller administers, their members and shared groups;</li>
 *   <li>{@code POST /api/execution/admin/members}: a new member in a new tenant of their own (ADMIN);</li>
 *   <li>{@code POST /api/execution/admin/members/{username}/status}: ACTIVE or DISABLED (signed-in pages stop working
 *       for them at once: every call checks the status).</li>
 * </ul>
 */
@RestController
class MembersController {

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,39}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{1,39}");
    private static final Set<String> MEMBER_ROLES = Set.of("OWNER", "TRADER", "VIEWER");

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KillSwitches audit;

    MembersController(Access access, DataSource dataSource, TransactionTemplate tx) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = tx;
        this.audit = new KillSwitches(dataSource);
    }

    @GetMapping("/api/execution/me")
    Map<String, Object> me(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        List<Map<String, Object>> tenants = new ArrayList<>();
        for (long id : user.tenants()) {
            String role = user.roles().get(id);
            List<String> follows = jdbc.queryForList("select g.name from acct.copy_group g where g.id = any(?) order by g.id",
                    String.class, (Object) accounts.followableCopyGroups(id).toArray(new Long[0]));
            tenants.add(Map.of("id", id, "name", jdbc.queryForObject("select name from acct.tenant where id = ?", String.class,
                    id), "role", role, "mayAct", user.mayAct(id), "manages", user.manages(id), "follows", follows));
        }
        return Map.of("user", user.subject(), "admin", user.admin(), "tenants", tenants);
    }

    @GetMapping("/api/execution/admin/members")
    List<Map<String, Object>> members(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "needs ADMIN");
        List<Map<String, Object>> out = new ArrayList<>();
        for (long tenant : administered(user)) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tenantId", tenant);
            t.put("tenant", jdbc.queryForObject("select name from acct.tenant where id = ?", String.class, tenant));
            t.put("members", jdbc.queryForList("select u.external_subject username, u.display_name, u.status, m.role "
                    + "from acct.tenant_membership m join acct.app_user u on u.id = m.app_user_id where m.tenant_id = ? "
                    + "order by m.role, u.external_subject", tenant));
            t.put("accounts", jdbc.queryForObject("select count(*) from acct.broker_account where tenant_id = ? "
                    + "and account_role = 'FOLLOWER_TRADE'", Integer.class, tenant));
            t.put("follows", jdbc.queryForList("select g.id, g.name, t.name owner from acct.copy_group g join acct.tenant t "
                    + "on t.id = g.tenant_id where g.id in (select copy_group_id from acct.copy_group_share where tenant_id = ?) "
                    + "or g.tenant_id = ? order by g.id", tenant, tenant));
            out.add(t);
        }
        return out;
    }

    /** {@code role}: the member's role in their own tenant (OWNER: also manages capital and strategies; TRADER; VIEWER). */
    record NewMember(String username, String displayName, String role, String tenantName) {
    }

    @PostMapping("/api/execution/admin/members")
    Map<String, Object> add(@RequestHeader(value = Access.HEADER, required = false) String subject,
                            @RequestBody NewMember body) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "needs ADMIN");
        String username = body.username() == null ? "" : body.username().trim().toLowerCase(Locale.ROOT);
        require(USERNAME.matcher(username).matches(), "username: 3-40 of a-z, 0-9, . _ - (their Keycloak username)");
        String display = body.displayName() == null || body.displayName().isBlank() ? username : body.displayName().trim();
        require(NAME.matcher(display).matches(), "display name: 2-40 letters, digits, space, . _ -");
        String role = body.role() == null ? "TRADER" : body.role().trim().toUpperCase(Locale.ROOT);
        require(MEMBER_ROLES.contains(role), "role: OWNER, TRADER or VIEWER");
        String tenantName = body.tenantName() == null || body.tenantName().isBlank() ? "family-" + username
                : body.tenantName().trim();
        require(NAME.matcher(tenantName).matches(), "tenant name: 2-40 letters, digits, space, . _ -");
        // the admin's live signal group (LIVE-SET), not drills (TEE-TEST, CHAOS); every enabled group only when there is none
        Long[] home = home(user).toArray(new Long[0]);
        List<Long> groups = jdbc.queryForList("select id from acct.copy_group where enabled and tenant_id = any(?) "
                + "and name = 'LIVE-SET' order by id", Long.class, (Object) home);
        if (groups.isEmpty()) {
            groups = jdbc.queryForList("select id from acct.copy_group where enabled and tenant_id = any(?) order by id",
                    Long.class, (Object) home);
        }
        List<Long> follows = groups;
        Map<String, Object> created = tx.execute(t -> {
            Integer taken = jdbc.queryForObject("select count(*) from acct.app_user where external_subject = ?", Integer.class,
                    username);
            require(taken != null && taken == 0, username + " already exists");
            Integer tenantTaken = jdbc.queryForObject("select count(*) from acct.tenant where name = ?", Integer.class,
                    tenantName);
            require(tenantTaken != null && tenantTaken == 0, "a tenant named " + tenantName + " already exists");
            long tenant = accounts.ensureTenant(tenantName);
            long member = accounts.ensureUser(username, display);
            long admin = jdbc.queryForObject("select id from acct.app_user where external_subject = ?", Long.class,
                    user.subject());
            accounts.ensureMembership(tenant, member, role);
            accounts.ensureMembership(tenant, admin, "ADMIN");
            for (long group : follows) {
                accounts.shareCopyGroup(group, tenant, admin);
            }
            return Map.of("tenantId", tenant, "username", username, "role", role, "follows", follows);
        });
        audit.audit((Long) created.get("tenantId"), null, user.subject(), "MEMBER_ADDED", created);
        return created;
    }

    record Status(String status) {
    }

    @PostMapping("/api/execution/admin/members/{username}/status")
    Map<String, Object> status(@RequestHeader(value = Access.HEADER, required = false) String subject,
                               @PathVariable String username, @RequestBody Status body) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "needs ADMIN");
        String status = body.status() == null ? "" : body.status().trim().toUpperCase(Locale.ROOT);
        require(status.equals("ACTIVE") || status.equals("DISABLED"), "status: ACTIVE or DISABLED");
        require(!username.equals(user.subject()), "you cannot change your own status");
        Integer signals = jdbc.queryForObject("select count(*) from acct.broker_account a join acct.app_user u on u.id = "
                + "a.app_user_id where u.external_subject = ? and a.account_role = 'SIGNAL_SOURCE'", Integer.class, username);
        require(signals == null || signals == 0, username + " owns the signal source (and is the no-login mode's user): "
                + "it is never disabled here");
        List<Long> theirs = jdbc.queryForList("select m.tenant_id from acct.tenant_membership m join acct.app_user u "
                + "on u.id = m.app_user_id where u.external_subject = ?", Long.class, username);
        List<Long> mine = administered(user);
        if (theirs.isEmpty() || !mine.containsAll(theirs)) {               // only someone wholly within your tenants
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no member " + username);
        }
        jdbc.update("update acct.app_user set status = ? where external_subject = ?", status, username);
        for (long tenant : theirs) {
            audit.audit(tenant, null, user.subject(), "MEMBER_" + status, Map.of("username", username));
        }
        return Map.of("username", username, "status", status);
    }

    /** Tenants the caller is ADMIN of. */
    private static List<Long> administered(Access.User user) {
        return user.tenants().stream().filter(t -> "ADMIN".equals(user.roles().get(t))).toList();
    }

    /** The caller's own tenants (OWNER or ADMIN) that are not a family member's: whose signal groups a new member follows. */
    private List<Long> home(Access.User user) {
        return user.tenants().stream().filter(user::manages).filter(t -> {
            Integer shared = jdbc.queryForObject("select count(*) from acct.copy_group_share where tenant_id = ?", Integer.class, t);
            return shared == null || shared == 0;
        }).toList();
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
