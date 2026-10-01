package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Alerts are sent once when raised, not again while unchanged, and once more when they clear. */
class AlertServiceTest {

    @TempDir
    Path dir;

    private AlertService service(Path telegramFile) {
        return new AlertService(new AlertProperties(telegramFile.toString(), ".", 8, 4, true));
    }

    @Test
    void raisesOnceRepeatsNothingWhileUnchangedAndReportsTheClear() {
        AlertService alerts = service(dir.resolve("missing.env"));
        alerts.raise("disk", AlertService.Level.WARN, "Mac disk low: 7.5 GB free.");
        alerts.raise("disk", AlertService.Level.WARN, "Mac disk low: 7.4 GB free.");             // new text, same alert
        assertThat(alerts.active()).singleElement().extracting(AlertService.Alert::message)
                .isEqualTo("Mac disk low: 7.4 GB free.");
        assertThat(alerts.active()).singleElement().extracting(AlertService.Alert::key).isEqualTo("disk");
        assertThat(alerts.recent()).hasSize(1);

        alerts.raise("disk", AlertService.Level.CRITICAL, "Mac disk almost full: 3.0 GB free.");   // escalation is sent
        assertThat(alerts.recent()).hasSize(2);

        alerts.clear("disk", "Mac disk OK again: 12.0 GB free.");
        alerts.clear("disk", "Mac disk OK again: 12.0 GB free.");                                  // nothing active: silent
        assertThat(alerts.active()).isEmpty();
        assertThat(alerts.recent()).hasSize(3).first().extracting(AlertService.Sent::level)
                .isEqualTo(AlertService.Level.RESOLVED);
    }

    @Test
    void telegramIsConfiguredOnlyWithATokenAndAChat() throws Exception {
        Path file = dir.resolve("bot.env");
        assertThat(service(file).telegramConfigured()).isFalse();
        Files.writeString(file, "# test\nTELEGRAM_BOT_TOKEN=abc\n");
        assertThat(service(file).telegramConfigured()).isFalse();
        Files.writeString(file, "TELEGRAM_BOT_TOKEN=abc\nTELEGRAM_CHAT_ID=123\n");
        assertThat(service(file).telegramConfigured()).isTrue();
    }
}
