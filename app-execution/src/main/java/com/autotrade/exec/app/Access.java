package com.autotrade.exec.app;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who may see and do what (live plan section 2a, "Tenancy" and "Roles"): a user's memberships (acct.tenant_membership)
 * decide which tenants' accounts they see; OWNER, ADMIN and TRADER may act (kill, release, flatten) on their tenants'
 * accounts, VIEWER and AUDITOR only read; ADMIN alone may engage GLOBAL_LIVE.
 * <p>
 * <b>SUPPORT</b> (trader workspaces, 7 Nov 2026, V29): the platform admin in a family member's workspace. Without a grant
 * they see it and may stop trouble at once (engage a kill switch, flatten, exit a position: {@link User#emergency}); with
 * an active grant from the member ({@code acct.support_grant}), or in a workspace that has no active OWNER (a viewer-only
 * workspace nobody else could run), they act and manage like an OWNER.
 * <p>
 * The user is the {@code X-Autotrade-User} header (the Keycloak subject). Until Keycloak is in front (L3) this is not
 * authentication: the service binds 127.0.0.1 only, and nothing here is reachable from outside the host.
 */
@Component
class Access {

    static final String HEADER = "X-Autotrade-User";

    /**
     * @param id        acct.app_user id
     * @param granted   tenants where this user is SUPPORT with an active grant
     * @param ownerless tenants where this user is SUPPORT and no active OWNER exists
     */
    record User(long id, String subject, Map<Long, String> roles, Set<Long> granted, Set<Long> ownerless) {

        boolean sees(long tenantId) {
            return roles.containsKey(tenantId);
        }

        /** SUPPORT in this tenant (the platform admin in a member's workspace). */
        boolean support(long tenantId) {
            return "SUPPORT".equals(roles.get(tenantId));
        }

        /** SUPPORT working with the member's grant, or in a workspace without an active OWNER. */
        boolean helping(long tenantId) {
            return support(tenantId) && (granted.contains(tenantId) || ownerless.contains(tenantId));
        }

        boolean mayAct(long tenantId) {
            String role = roles.get(tenantId);
            return "OWNER".equals(role) || "ADMIN".equals(role) || "TRADER".equals(role) || helping(tenantId);
        }

        /** Stopping trouble: engage a kill switch, flatten, exit a position. Anyone who may act, and SUPPORT always. */
        boolean emergency(long tenantId) {
            return mayAct(tenantId) || support(tenantId);
        }

        /** OWNER or ADMIN (or SUPPORT helping): may create accounts and change their capital, limits and strategies. */
        boolean manages(long tenantId) {
            String role = roles.get(tenantId);
            return "OWNER".equals(role) || "ADMIN".equals(role) || helping(tenantId);
        }

        /** The platform admin: ADMIN of a tenant (their own). */
        boolean admin() {
            return roles.containsValue("ADMIN");
        }

        List<Long> tenants() {
            return List.copyOf(roles.keySet());
        }
    }

    private final JdbcTemplate jdbc;

    Access(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    User user(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no " + HEADER);
        }
        List<Long> ids = jdbc.queryForList("select id from acct.app_user where external_subject = ? and status = 'ACTIVE'",
                Long.class, subject);
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no tenant for " + subject);
        }
        long id = ids.getFirst();
        Map<Long, String> roles = jdbc.queryForList("select tenant_id, role from acct.tenant_membership where app_user_id = ?",
                id).stream().collect(Collectors.toMap(r -> ((Number) r.get("tenant_id")).longValue(),
                r -> (String) r.get("role")));
        if (roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no tenant for " + subject);
        }
        Set<Long> granted = Set.of(), ownerless = Set.of();
        if (roles.containsValue("SUPPORT")) {
            granted = Set.copyOf(jdbc.queryForList("select distinct g.tenant_id from acct.support_grant g "
                    + "where g.granted_to = ? and g.revoked_at is null and g.expires_at > now()", Long.class, id));
            ownerless = Set.copyOf(jdbc.queryForList("select m.tenant_id from acct.tenant_membership m where m.app_user_id = ? "
                    + "and m.role = 'SUPPORT' and not exists (select 1 from acct.tenant_membership o join acct.app_user u "
                    + "on u.id = o.app_user_id where o.tenant_id = m.tenant_id and o.role = 'OWNER' and u.status = 'ACTIVE')",
                    Long.class, id));
        }
        // the first sign-in accepts a pending invite; last seen at most every 5 minutes (a write per page, not per call)
        if (jdbc.update("update acct.app_user set last_seen_at = now() where id = ? and (last_seen_at is null "
                + "or last_seen_at < now() - interval '5 minutes')", id) > 0) {
            jdbc.update("update acct.invite set status = 'ACCEPTED', accepted_at = now() where username = ? "
                    + "and status = 'PENDING'", subject);
        }
        return new User(id, subject, roles, granted, ownerless);
    }

    static void require(boolean allowed, String what) {
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, what);
        }
    }
}
