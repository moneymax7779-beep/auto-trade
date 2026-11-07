package com.autotrade.exec.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code autotrade.execution.brokers.*}: the members' broker apps and daily logins other than Kite's (trader workspaces,
 * 7 Nov 2026).
 *
 * @param storeDir  writable folder of the broker app keys members type on the Brokers page ({@code store:<name>}, one
 *                  file per value, owner-only; on the VM .local/broker-secrets)
 * @param upstoxApi Upstox's API (api.upstox.com; a stand-in in tests)
 * @param kotakApi  Kotak Neo's login API (mis.kotaksecurities.com; a stand-in in tests)
 * @param growwApi  Groww's API (api.groww.in; a stand-in in tests)
 * @param egressIps the static IP(s) a member registers with their broker app (comma-separated; shown on the Brokers page)
 */
@ConfigurationProperties(prefix = "autotrade.execution.brokers")
public record BrokerLoginProperties(String storeDir, String upstoxApi, String kotakApi, String growwApi, String egressIps) {

    public BrokerLoginProperties {
        storeDir = blank(storeDir) ? "/app/broker-secrets" : storeDir;
        upstoxApi = blank(upstoxApi) ? "https://api.upstox.com" : upstoxApi.replaceAll("/+$", "");
        kotakApi = blank(kotakApi) ? "https://mis.kotaksecurities.com" : kotakApi.replaceAll("/+$", "");
        growwApi = blank(growwApi) ? "https://api.groww.in" : growwApi.replaceAll("/+$", "");
        egressIps = blank(egressIps) ? "" : egressIps.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
