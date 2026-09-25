package com.autotrade.strategy;

import java.time.LocalDate;

/** Creates fresh, per-underlying, per-session strategy instances. */
public interface StrategyFactory {

    String id();

    String configHash();

    Strategy create(String underlying, LocalDate session);
}
