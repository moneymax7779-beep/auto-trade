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
                Map.of("KITE_API_KEY", " from-env ", "KITE_EMPTY", " ", "AUTOTRADE_DB_PASSWORD", "x")::get);
        assertThat(secrets.read("env:KITE_API_KEY")).isEqualTo("from-env");
        assertThat(secrets.read("file:kite-api-key")).isEqualTo("from-file");
        secrets.writeToken(7, "{}");
        try (var files = Files.list(dir.resolve("sessions"))) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly("kite-7.token");   // no .tmp left
        }
        assertThatThrownBy(() -> secrets.read("env:KITE_API_SECRET")).hasMessageContaining("not set");
        assertThatThrownBy(() -> secrets.read("env:KITE_EMPTY")).hasMessageContaining("not set");
        assertThatThrownBy(() -> secrets.read("env:lower_case")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secrets.read("env:AUTOTRADE_DB_PASSWORD")).as("only KITE_ variables")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secrets.read("file:../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secrets.read("the-actual-secret")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Trader workspaces (7 Nov 2026): what a member types on the Brokers page, write-only into the store folder. */
    @Test
    void storeReferencesAreWrittenOwnerOnlyAndNothingEscapesTheFolder() throws Exception {
        BrokerSecrets secrets = new BrokerSecrets(dir, dir.resolve("sessions"), dir.resolve("store"), Map.<String, String>of()::get);
        String ref = BrokerSecrets.storeRef(7, 12, "secret");
        assertThat(ref).isEqualTo("store:t7-p12-secret");
        assertThat(secrets.stored(ref)).isFalse();
        assertThatThrownBy(() -> secrets.read(ref)).hasMessageContaining("not set");
        secrets.write(ref, "s3cret-value");
        assertThat(secrets.stored(ref)).isTrue();
        assertThat(secrets.read(ref)).isEqualTo("s3cret-value");
        assertThat(java.nio.file.Files.getPosixFilePermissions(dir.resolve("store").resolve("t7-p12-secret")))
                .containsExactlyInAnyOrder(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        assertThat(java.nio.file.Files.getPosixFilePermissions(dir.resolve("store"))).as("the folder owner-only")
                .containsExactlyInAnyOrder(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        try (var files = Files.list(dir.resolve("store"))) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly("t7-p12-secret");   // no .tmp left
        }
        assertThatThrownBy(() -> secrets.write("store:../escape", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secrets.write("store:..", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secrets.write("file:t7", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secrets.read("file:..")).isInstanceOf(IllegalArgumentException.class);
        BrokerSecrets noStore = new BrokerSecrets(dir, dir.resolve("sessions"));
        assertThatThrownBy(() -> noStore.read(ref)).hasMessageContaining("no secret store");
        // a token per broker; a logout deletes whichever exists
        secrets.writeToken("upstox", 9, "{}");
        assertThat(secrets.readToken("upstox", 9)).isEqualTo("{}");
        secrets.deleteToken(9);
        assertThat(secrets.readToken("upstox", 9)).isNull();
    }
}
