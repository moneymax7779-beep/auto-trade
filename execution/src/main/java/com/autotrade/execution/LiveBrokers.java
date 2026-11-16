package com.autotrade.execution;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import com.autotrade.account.BrokerType;

/**
 * The brokers an account may be switched LIVE on, read by the LIVE switch, the workspace's day switch, the admin's LIVE
 * eligibility and the broker pages (8 Nov 2026: one hard-coded set). A broker joins only after its adapter's gates in
 * docs/plans/2026-11-08-live-adapters-plan.md (simulator fault suite, READ_LIVE sessions, canary) and the user's
 * go-ahead.
 * <p>
 * 16 Nov 2026 (multi-broker gap A2): the set comes from configuration ({@code autotrade.execution.live-brokers}, default
 * ZERODHA), and a configured broker is LIVE only when the worker has an adapter for it that offers a LIVE connection
 * ({@link BrokerAdapter#offersLive}); a configured broker without one stays DRY_RUN (and is logged at start). PAPER is
 * never LIVE: configuring it fails. It also knows which brokers have an adapter at all ({@link #hasAdapter}), so an
 * account of any of them may be created DRY_RUN.
 */
public final class LiveBrokers {

    /** The configuration's default: the Kite pilot only. */
    public static final String DEFAULT = "ZERODHA";

    private final Set<BrokerType> configured;
    private final Set<BrokerType> live;
    private final Set<BrokerType> adapted;

    /**
     * @param configured the brokers the configuration allows LIVE
     * @param adapters   the worker's broker adapters
     */
    public LiveBrokers(Collection<BrokerType> configured, Collection<? extends BrokerAdapter> adapters) {
        Set<BrokerType> allowed = EnumSet.noneOf(BrokerType.class);
        allowed.addAll(configured);
        if (allowed.contains(BrokerType.PAPER)) {
            throw new IllegalArgumentException("live brokers: PAPER is never LIVE");
        }
        Set<BrokerType> adapted = EnumSet.noneOf(BrokerType.class);
        Set<BrokerType> live = EnumSet.noneOf(BrokerType.class);
        for (BrokerAdapter a : adapters) {
            adapted.add(a.broker());
            if (a.offersLive() && allowed.contains(a.broker())) {
                live.add(a.broker());
            }
        }
        this.configured = Set.copyOf(allowed);
        this.live = Set.copyOf(live);
        this.adapted = Set.copyOf(adapted);
    }

    /** From the configuration's comma-separated broker names (blank: none LIVE). */
    public static LiveBrokers of(String names, Collection<? extends BrokerAdapter> adapters) {
        return new LiveBrokers(parse(names), adapters);
    }

    static Set<BrokerType> parse(String names) {
        Set<BrokerType> set = EnumSet.noneOf(BrokerType.class);
        if (names == null) {
            return set;
        }
        Arrays.stream(names.split(",")).map(String::trim).filter(n -> !n.isEmpty()).forEach(n -> {
            try {
                set.add(BrokerType.valueOf(n.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("live brokers: unknown broker " + n);
            }
        });
        return set;
    }

    /** Whether an account of this broker may be switched LIVE (configured, and its adapter offers LIVE). */
    public boolean live(BrokerType broker) {
        return live.contains(broker);
    }

    /** The LIVE brokers. */
    public Set<BrokerType> live() {
        return live;
    }

    /** Configured LIVE but without an adapter that offers LIVE, so not LIVE (the start log names them). */
    public Set<BrokerType> configuredWithoutLiveAdapter() {
        Set<BrokerType> set = EnumSet.noneOf(BrokerType.class);
        set.addAll(configured);
        set.removeAll(live);
        return set;
    }

    /** Whether the worker has an order adapter for this broker (its accounts may trade DRY_RUN). */
    public boolean hasAdapter(BrokerType broker) {
        return adapted.contains(broker);
    }
}
