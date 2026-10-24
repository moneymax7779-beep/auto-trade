package com.autotrade.trading;

import java.security.Principal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/me}: who the UI is signed in as. With {@code autotrade.auth.mode=none} it is the single configured
 * execution user and the system controls are open (as before); with {@code oidc} it is the Keycloak user, and
 * {@code operator} says whether they hold the realm role for the PAPER system controls (kill, stop, exit-all).
 */
@RestController
class MeController {

    private final AuthProperties auth;
    private final String defaultUser;

    MeController(AuthProperties auth, @Value("${autotrade.ui.execution-user:local-owner}") String defaultUser) {
        this.auth = auth;
        this.defaultUser = defaultUser;
    }

    @GetMapping("/api/me")
    Map<String, Object> me(Principal principal) {
        if (!auth.oidc()) {
            return Map.of("mode", "none", "user", defaultUser, "operator", true, "logout", false);
        }
        String operator = auth.operatorAuthority();
        boolean isOperator = principal instanceof Authentication a
                && a.getAuthorities().stream().anyMatch(g -> operator.equals(g.getAuthority()));
        return Map.of("mode", "oidc", "user", principal == null ? "" : principal.getName(), "operator", isOperator,
                "logout", true);
    }
}
