package com.autotrade.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceDatabaseTest {

    @TempDir
    Path dir;

    @Test
    void passwordFileWinsWhenItHasTheKey() throws IOException {
        Path file = dir.resolve("runtime.env");
        Files.writeString(file, "OTHER=x\nV2_DB_PASSWORD=abc123\n");

        assertThat(SourceDatabase.resolvePassword(source("explicit", file.toString()))).isEqualTo("abc123");
    }

    @Test
    void fallsBackToExplicitPasswordWhenFileIsAbsent() {
        assertThat(SourceDatabase.resolvePassword(source("explicit", dir.resolve("missing").toString())))
                .isEqualTo("explicit");
        assertThat(SourceDatabase.resolvePassword(source("explicit", ""))).isEqualTo("explicit");
    }

    @Test
    void failsWhenFileLacksTheKey() throws IOException {
        Path file = dir.resolve("runtime.env");
        Files.writeString(file, "OTHER=x\n");

        assertThatThrownBy(() -> SourceDatabase.resolvePassword(source("explicit", file.toString())))
                .hasMessageContaining("has no V2_DB_PASSWORD");
    }

    private static ToolProperties.Source source(String password, String file) {
        return new ToolProperties.Source("zt", "jdbc:postgresql://h/db", "u", password, file, "V2_DB_PASSWORD");
    }
}
