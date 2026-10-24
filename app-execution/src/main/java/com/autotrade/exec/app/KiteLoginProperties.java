package com.autotrade.exec.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code autotrade.execution.kite.*}: the daily Kite login (live plan L3, user go-ahead 24 Oct 2026, "C1").
 *
 * @param secretsDir    read-only folder of the Kite app's API key and secret (one file each, named by the credential
 *                      profile's {@code api_key_ref} / {@code api_secret_ref} as {@code file:<name>})
 * @param sessionsDir   writable folder for the day's access tokens (one file per broker account, owner-only)
 * @param publicBaseUrl the address you open in a browser (behind the password gate), for the morning login link
 * @param apiBase       Kite's API (api.kite.trade; a local simulator in tests)
 * @param loginBase     Kite's login page host (kite.zerodha.com)
 * @param telegramFile  TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID (the same file trading-core uses); absent = no message
 */
@ConfigurationProperties(prefix = "autotrade.execution.kite")
public record KiteLoginProperties(String secretsDir, String sessionsDir, String publicBaseUrl, String apiBase,
                                  String loginBase, String telegramFile) {

    public KiteLoginProperties {
        secretsDir = blank(secretsDir) ? "/app/secrets" : secretsDir;
        sessionsDir = blank(sessionsDir) ? "/app/broker-sessions" : sessionsDir;
        publicBaseUrl = blank(publicBaseUrl) ? null : publicBaseUrl.replaceAll("/+$", "");
        apiBase = blank(apiBase) ? "https://api.kite.trade" : apiBase;
        loginBase = blank(loginBase) ? "https://kite.zerodha.com" : loginBase;
        telegramFile = blank(telegramFile) ? "/app/telegram/bot.env" : telegramFile;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
