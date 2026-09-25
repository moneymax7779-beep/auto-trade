package com.autotrade.research;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import com.autotrade.core.event.OptionTick;
import com.autotrade.strategy.OptionSide;

/** Contracts seen so far in the replay, nearest expiry only: (strike, side) → token, symbol, lot size. */
final class OptionBook {

    record Contract(long token, String symbol, int lotSize, LocalDate expiry, double strike) {
    }

    private final LocalDate session;
    private final Map<String, Contract> contracts = new HashMap<>();
    private LocalDate expiry;

    OptionBook(LocalDate session) {
        this.session = session;
    }

    void accept(OptionTick tick) {
        if (tick.expiry() == null || tick.expiry().isBefore(session) || tick.lotSize() == null) {
            return;
        }
        if (expiry == null || tick.expiry().isBefore(expiry)) {
            expiry = tick.expiry();
            contracts.clear();
        } else if (tick.expiry().isAfter(expiry)) {
            return;
        }
        contracts.putIfAbsent(key(tick.strike(), tick.optionType() == OptionTick.OptionType.CE ? OptionSide.CE
                : OptionSide.PE), new Contract(tick.instrumentToken(), tick.symbol(), tick.lotSize(), tick.expiry(),
                tick.strike()));
    }

    Contract find(double strike, OptionSide side) {
        return contracts.get(key(strike, side));
    }

    private static String key(double strike, OptionSide side) {
        return side + ":" + strike;
    }
}
