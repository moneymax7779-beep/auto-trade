package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Every route of the single-page UI (ui/src/App.tsx) must load index.html from the server too, or a reload, a bookmark
 * or a broker's login callback to that page answers 404 (mobile phase 3 review, 17 Nov 2026: /home, /positions, /ledger
 * and /brokers/add were missing). React Router's ":param" is Spring's "{param}"; "*" is the client's own fallback.
 */
class UiRoutesTest {

    @Test
    void everyClientRouteIsForwardedToIndexHtml() throws Exception {
        String app = Files.readString(Path.of("..", "ui", "src", "App.tsx"));
        Set<String> client = new TreeSet<>();
        Matcher m = Pattern.compile("<Route path=\"([^\"]+)\"").matcher(app);
        while (m.find()) {
            if (!m.group(1).equals("*")) {
                client.add(m.group(1).replaceAll(":(\\w+)", "{$1}"));
            }
        }
        Set<String> server = new TreeSet<>(Arrays.asList(
                UiController.class.getDeclaredMethod("index").getAnnotation(GetMapping.class).value()));
        assertThat(client).isNotEmpty();
        assertThat(server).containsAll(client);
    }
}
