package com.autotrade.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 14 Oct: the UI released GLOBAL while the daily loss limit had engaged ACCOUNT:PAPER-1, so nothing was released. */
class KillSwitchTest {

    private final List<String> changes = new ArrayList<>();
    private final KillSwitch killSwitch = new KillSwitch((scope, e) -> changes.add(scope + (e == null ? " released" : " engaged")));

    @Test
    void releasingAScopeThatIsNotEngagedChangesNothingAndSaysSo() {
        killSwitch.engage("ACCOUNT:PAPER-1", "daily loss limit 235244.9 reached", Instant.EPOCH, "risk");
        assertThat(killSwitch.release("GLOBAL")).isFalse();
        assertThat(killSwitch.blocking("PAPER-1", "ma-cross")).isPresent();
    }

    @Test
    void releaseAllReleasesTheDailyLossSwitch() {
        killSwitch.engage("ACCOUNT:PAPER-1", "daily loss limit 235244.9 reached", Instant.EPOCH, "risk");
        killSwitch.engage("STRATEGY:opening-drive", "operator", Instant.EPOCH, "operator");
        assertThat(killSwitch.releaseAll()).containsExactly("ACCOUNT:PAPER-1", "STRATEGY:opening-drive");
        assertThat(killSwitch.blocking("PAPER-1", "opening-drive")).isEmpty();
        assertThat(changes).containsExactly("ACCOUNT:PAPER-1 engaged", "STRATEGY:opening-drive engaged",
                "ACCOUNT:PAPER-1 released", "STRATEGY:opening-drive released");
    }
}
