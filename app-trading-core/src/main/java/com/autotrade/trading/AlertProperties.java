package com.autotrade.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational alerts of the always-on service (--mode=auto only). {@code telegramFile} holds
 * TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID (KEY=value lines; never logged); without it alerts still reach the
 * log and the Live page. {@code diskPath} is any bind-mounted host folder: its free space is the Mac's.
 */
@ConfigurationProperties("autotrade.alerts")
public record AlertProperties(String telegramFile, String diskPath, double diskWarnGb, double diskCriticalGb,
                              boolean trades) {
}
