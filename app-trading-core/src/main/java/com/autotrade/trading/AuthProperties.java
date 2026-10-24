package com.autotrade.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code autotrade.auth.*}: per-user logins (live plan L3, user 24 Oct 2026: "start per-user logins with Keycloak").
 *
 * @param mode          {@code none} (default: no login in the app, the single configured execution user, as before) or
 *                      {@code oidc} (Keycloak sign-in on every page and API; the signed-in user is who acts)
 * @param publicBase    Keycloak as the browser reaches it, e.g. https://host/auth (the login page, the token issuer)
 * @param internalBase  Keycloak as this service reaches it, e.g. http://keycloak:8180/auth (tokens, keys, user info)
 * @param realm         the Keycloak realm (autotrade)
 * @param clientId      this app's client in the realm (autotrade-web)
 * @param clientSecret  its secret (from the environment, never logged)
 * @param operatorRole  the realm role allowed to use the PAPER system controls (kill, stop, exit-all)
 */
@ConfigurationProperties(prefix = "autotrade.auth")
public record AuthProperties(String mode, String publicBase, String internalBase, String realm, String clientId,
                             String clientSecret, String operatorRole) {

    public AuthProperties {
        mode = mode == null || mode.isBlank() ? "none" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        realm = blank(realm) ? "autotrade" : realm;
        clientId = blank(clientId) ? "autotrade-web" : clientId;
        operatorRole = blank(operatorRole) ? "operator" : operatorRole;
        publicBase = blank(publicBase) ? null : publicBase.replaceAll("/+$", "");
        internalBase = blank(internalBase) ? publicBase : internalBase.replaceAll("/+$", "");
        if (!mode.equals("none") && !mode.equals("oidc")) {
            throw new IllegalArgumentException("autotrade.auth.mode must be none or oidc, not " + mode);
        }
    }

    public boolean oidc() {
        return "oidc".equals(mode);
    }

    String publicRealm() {
        return publicBase + "/realms/" + realm;
    }

    String internalRealm() {
        return internalBase + "/realms/" + realm;
    }

    @Override
    public String toString() {
        return "AuthProperties[mode=" + mode + ", publicBase=" + publicBase + ", internalBase=" + internalBase + ", realm="
                + realm + ", clientId=" + clientId + ", clientSecret=***, operatorRole=" + operatorRole + "]";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
