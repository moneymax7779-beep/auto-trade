package com.autotrade.exec.app;

import java.util.List;
import java.util.Map;
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
 * The user is the {@code X-Autotrade-User} header (the Keycloak subject). Until Keycloak is in front (L3) this is not
 * authentication: the service binds 127.0.0.1 only, and nothing here is reachable from outside the host.
 */
@Component
class Access {

    static final String HEADER = "X-Autotrade-User";

    record User(String subject, Map<Long, String> roles) {
        boolean sees(long tenantId) {
            return roles.containsKey(tenantId);
        }

        boolean mayAct(long tenantId) {
            String role = roles.get(tenantId);
            return "OWNER".equals(role) || "ADMIN".equals(role) || "TRADER".equals(role);
        }

        /** OWNER or ADMIN: may create accounts and change their capital, limits and strategies (TRADER may not). */
        boolean manages(long tenantId) {
            String role = roles.get(tenantId);
            return "OWNER".equals(role) || "ADMIN".equals(role);
        }

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
        Map<Long, String> roles = jdbc.queryForList("select m.tenant_id, m.role from acct.tenant_membership m "
                        + "join acct.app_user u on u.id = m.app_user_id where u.external_subject = ? and u.status = 'ACTIVE'",
                subject).stream().collect(Collectors.toMap(r -> ((Number) r.get("tenant_id")).longValue(),
                r -> (String) r.get("role")));
        if (roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no tenant for " + subject);
        }
        return new User(subject, roles);
    }

    static void require(boolean allowed, String what) {
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, what);
        }
    }
}
