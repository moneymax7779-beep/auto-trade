package com.autotrade.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ToolArgsTest {

    @Test
    void parsesSpaceAndEqualsFormsAndFlags() {
        ToolArgs args = ToolArgs.parse(
                new String[] {"clone", "--session", "2026-09-02,2026-09-03", "--underlying=nifty", "--force"},
                Set.of("force"));

        assertThat(args.command()).isEqualTo("clone");
        assertThat(args.dates("session")).containsExactly(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3));
        assertThat(args.upperList("underlying", "")).containsExactly("NIFTY");
        assertThat(args.flag("force")).isTrue();
    }

    @Test
    void rejectsUnknownOptionsAndMissingValues() {
        ToolArgs args = ToolArgs.parse(new String[] {"replay", "--sesion", "2026-09-02"}, Set.of());
        assertThatThrownBy(() -> args.allowOnly(Set.of("session"))).hasMessageContaining("--sesion");
        assertThatThrownBy(() -> ToolArgs.parse(new String[] {"replay", "--session"}, Set.of()))
                .hasMessageContaining("needs a value");
    }

    @Test
    void readOnlyOptionIsAppendedToTheSourceUrl() {
        assertThat(SourceDatabase.withReadOnlyOption("jdbc:postgresql://h:1/db"))
                .isEqualTo("jdbc:postgresql://h:1/db?options=-c%20default_transaction_read_only%3Don");
        assertThat(SourceDatabase.withReadOnlyOption("jdbc:postgresql://h:1/db?ssl=false"))
                .endsWith("?ssl=false&options=-c%20default_transaction_read_only%3Don");
    }
}
