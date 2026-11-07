package com.autotrade.exec.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code autotrade.execution.keycloak.*}: the members' Keycloak logins ({@link KeycloakAdmin}). Blank url or secret = not
 * configured (members' logins stay a console job).
 *
 * @param url          the Keycloak base on the internal network, with its path (http://keycloak:8180/auth)
 * @param realm        the realm trading-core signs users in with
 * @param clientId     the service-account client (bin/keycloak/bootstrap.sh --admin-secret-file)
 * @param clientSecret its secret, from the VM's root-only .env (AUTOTRADE_EXECUTION_KEYCLOAK_CLIENT_SECRET); never logged
 */
@ConfigurationProperties(prefix = "autotrade.execution.keycloak")
public record KeycloakAdminProperties(String url, String realm, String clientId, String clientSecret) {

    public KeycloakAdminProperties {
        url = url == null || url.isBlank() ? null : url.replaceAll("/+$", "");
        realm = realm == null || realm.isBlank() ? "autotrade" : realm;
        clientId = clientId == null || clientId.isBlank() ? "autotrade-admin" : clientId;
        clientSecret = clientSecret == null || clientSecret.isBlank() ? null : clientSecret.strip();
    }
}
