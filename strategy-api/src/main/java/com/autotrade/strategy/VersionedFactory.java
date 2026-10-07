package com.autotrade.strategy;

import java.time.LocalDate;
import java.util.List;

/**
 * A strategy factory under another id: two versions of one strategy in the same session (e.g. etr-v1 and etr-v5)
 * must not share an id, because positions, decisions and trades are kept per strategy id. The second one runs as
 * {@code <id>@<version>}; everything else is the wrapped factory's.
 */
public final class VersionedFactory implements StrategyFactory {

    private final StrategyFactory delegate;
    private final String id;

    public VersionedFactory(StrategyFactory delegate) {
        this.delegate = delegate;
        this.id = delegate.id() + "@" + delegate.version();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String configHash() {
        return delegate.configHash();
    }

    @Override
    public Strategy create(String underlying, LocalDate session) {
        Strategy inner = delegate.create(underlying, session);
        return new Strategy() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String configHash() {
                return inner.configHash();
            }

            @Override
            public Decision decide(com.autotrade.features.snapshot.FeatureSnapshot snapshot, PositionView position) {
                return inner.decide(snapshot, position);
            }
        };
    }

    @Override
    public double premiumStopPct() {
        return delegate.premiumStopPct();
    }

    @Override
    public List<String> requiredFeatureSections() {
        return delegate.requiredFeatureSections();
    }

    @Override
    public double premiumBudget() {
        return delegate.premiumBudget();
    }

    @Override
    public double expiryDayPremiumBudget() {
        return delegate.expiryDayPremiumBudget();
    }

    @Override
    public int intendedLots() {
        return delegate.intendedLots();
    }

    @Override
    public boolean pyramidRiskCap() {
        return delegate.pyramidRiskCap();
    }

    @Override
    public String version() {
        return delegate.version();
    }

    /** {@code factories} with every repeated id renamed to {@code <id>@<version>} (the first keeps its id). */
    public static List<StrategyFactory> unique(List<StrategyFactory> factories) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<StrategyFactory> out = new java.util.ArrayList<>();
        for (StrategyFactory f : factories) {
            StrategyFactory g = seen.add(f.id()) ? f : new VersionedFactory(f);
            if (g != f && !seen.add(g.id())) {
                throw new IllegalStateException("strategy " + g.id() + " listed twice");
            }
            out.add(g);
        }
        return out;
    }
}
