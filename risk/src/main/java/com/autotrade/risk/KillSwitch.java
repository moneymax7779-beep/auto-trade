package com.autotrade.risk;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Engaged switches block new entries and adds for their scope (GLOBAL, ACCOUNT:&lt;id&gt; or
 * STRATEGY:&lt;id&gt;). Exits are never blocked. Thread-safe; state changes are reported to a listener
 * so they can be persisted and alerted.
 */
public final class KillSwitch {

    public record Engagement(String scope, String reason, Instant at, String by) {
    }

    public interface Listener {
        void changed(String scope, Engagement engagement);
    }

    private final Map<String, Engagement> engaged = new LinkedHashMap<>();
    private final Listener listener;

    public KillSwitch(Listener listener) {
        this.listener = listener;
    }

    public synchronized void engage(String scope, String reason, Instant at, String by) {
        if (!engaged.containsKey(scope)) {
            Engagement engagement = new Engagement(scope, reason, at, by);
            engaged.put(scope, engagement);
            listener.changed(scope, engagement);
        }
    }

    public synchronized void release(String scope) {
        if (engaged.remove(scope) != null) {
            listener.changed(scope, null);
        }
    }

    /** The first engaged switch covering this account and strategy, if any. */
    public synchronized Optional<Engagement> blocking(String account, String strategy) {
        for (String scope : new String[] {"GLOBAL", "ACCOUNT:" + account, "STRATEGY:" + strategy}) {
            Engagement engagement = engaged.get(scope);
            if (engagement != null) {
                return Optional.of(engagement);
            }
        }
        return Optional.empty();
    }

    public synchronized Map<String, Engagement> engaged() {
        return Map.copyOf(engaged);
    }
}
