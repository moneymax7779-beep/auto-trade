package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Secret references: env:NAME (the execution container's environment, from the VM's .env) or file:name; nothing else. */
class BrokerSecretsTest {

    @TempDir
    Path dir;

    @Test
    void envAndFileReferencesResolveAndAnythingElseIsRefused() throws Exception {
        Files.writeString(dir.resolve("kite-api-key"), "from-file\n");
        BrokerSecrets secrets = new BrokerSecrets(dir, dir.resolve("sessions"),
                Map.of("KITE_API_KEY", " from-env ", "EMPTY", " ")::get);
        assertThat(secrets.read("env:KITE_API_KEY")).isEqualTo("from-env");
        assertThat(secrets.read("file:kite-api-key")).isEqualTo("from-file");
        assertThatThrownBy(() -> secrets.read("env:KITE_API_SECRET")).hasMessageContaining("not set");
        assertThatThrownBy(() -> secrets.read("env:EMPTY")).hasMessageContaining("not set");
        assertThatThrownBy(() -> secrets.read("env:lower_case")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secrets.read("file:../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secrets.read("the-actual-secret")).isInstanceOf(IllegalArgumentException.class);
    }
}
