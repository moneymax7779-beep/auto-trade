package com.autotrade.exec.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code autotrade.execution.telegram.*}: the bot taking commands (Telegram controls T0 + T1, 16 Nov 2026).
 *
 * @param commands       on: this worker polls the bot and takes commands. Off (the default) everywhere except the one
 *                       deployment that owns the bot: Telegram lets one reader use a bot's updates, a second gets 409
 * @param alarmFile      a second bot's TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID, for the poller alarm: when the command
 *                       bot is hijacked (someone else reads it, or set a webhook) it cannot be trusted to say so. Absent:
 *                       the alarm goes to the command bot's admin chat, the log and the audit log
 * @param confirmSeconds how long a confirm card stays valid
 * @param maxAgeSeconds  a command message older than this (Telegram's send time) is refused, never run late: after an
 *                       outage the bot would otherwise flatten on a message sent minutes ago
 * @param liveExits      on: /flatten and /exit may sell on a LIVE account. Off (the default; review 16 Nov 2026): the
 *                       plan's LIVE gate (the user's explicit go) is this switch; /kill works on LIVE either way
 */
@ConfigurationProperties(prefix = "autotrade.execution.telegram")
public record TelegramCommandProperties(boolean commands, String alarmFile, int confirmSeconds, int maxAgeSeconds,
                                        boolean liveExits) {

    public TelegramCommandProperties {
        alarmFile = alarmFile == null || alarmFile.isBlank() ? null : alarmFile;
        confirmSeconds = confirmSeconds <= 0 ? 60 : confirmSeconds;
        maxAgeSeconds = maxAgeSeconds <= 0 ? 60 : maxAgeSeconds;
    }

    static TelegramCommandProperties off() {
        return new TelegramCommandProperties(false, null, 0, 0, false);
    }
}
