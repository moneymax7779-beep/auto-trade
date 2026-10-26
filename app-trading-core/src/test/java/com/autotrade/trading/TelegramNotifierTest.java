package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Telegram refuses messages over 4,096 characters (the 26 Oct after-close shadow summary failed with HTTP 400). */
class TelegramNotifierTest {

    @Test
    void shortTextIsOneUnchangedMessage() {
        assertThat(TelegramNotifier.parts("Shadow net ₹-35302", TelegramNotifier.MAX_CHARS)).containsExactly("Shadow net ₹-35302");
    }

    @Test
    void sixteenShadowSummariesSplitBetweenSummariesInOrder() {
        List<String> summaries = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            summaries.add("Shadow 2026-10-26 (strategy-" + i + ".yaml), session " + (978 + i) + ":\n"
                    + "• strategy NIFTY 22650 CE 27 OCT 26 09:59–10:27 33 lots 171.84 → 170.18 TRAIL_15 ₹-4,345\n".repeat(3)
                    + "Shadow net ₹-" + i + " · live net ₹-138101");
        }
        String text = String.join("\n\n", summaries);
        assertThat(text.length()).isGreaterThan(4096);

        List<String> parts = TelegramNotifier.parts(text, TelegramNotifier.MAX_CHARS);
        assertThat(parts).hasSizeGreaterThan(1).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(4000));
        assertThat(parts.getFirst()).startsWith("(1/" + parts.size() + ")\nShadow 2026-10-26 (strategy-1.yaml)");
        List<String> rejoined = new ArrayList<>();
        for (String p : parts) {
            rejoined.add(p.substring(p.indexOf('\n') + 1));       // without the marker
        }
        assertThat(String.join("\n\n", rejoined)).isEqualTo(text);  // nothing lost, nothing reordered
    }

    @Test
    void oneVeryLongLineIsCutAtTheLimit() {
        List<String> parts = TelegramNotifier.parts("x".repeat(9000), TelegramNotifier.MAX_CHARS);
        assertThat(parts).hasSize(3).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(4000));
        assertThat(String.join("", parts.stream().map(p -> p.substring(p.indexOf('\n') + 1)).toList())).hasSize(9000);
    }
}
