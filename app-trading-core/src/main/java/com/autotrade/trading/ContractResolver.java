package com.autotrade.trading;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.autotrade.core.event.OptionTick;
import com.autotrade.instruments.Instrument;
import com.autotrade.instruments.InstrumentMaster;
import com.autotrade.oms.Contract;
import com.autotrade.strategy.OptionSide;

/**
 * Maps (underlying, strike, side) at the nearest expiry to a tradable contract: the token and lot
 * size from the live quotes, and the broker instrument key, tick size and freeze limit from the
 * contract master. Without a master entry it falls back to the quote's facts and safe defaults.
 */
final class ContractResolver {

    private static final double DEFAULT_TICK = 0.05;
    private static final int DEFAULT_MAX_LOTS = 20;

    private record Seen(long token, String symbol, int lotSize, LocalDate expiry) {
    }

    private final LocalDate session;
    private final InstrumentMaster master;
    private final Map<String, LocalDate> nearestExpiry = new HashMap<>();
    private final Map<String, Seen> seen = new HashMap<>();

    ContractResolver(LocalDate session, InstrumentMaster master) {
        this.session = session;
        this.master = master;
    }

    void accept(OptionTick tick) {
        if (tick.expiry() == null || tick.expiry().isBefore(session) || tick.lotSize() == null) {
            return;
        }
        LocalDate current = nearestExpiry.get(tick.underlying());
        if (current == null || tick.expiry().isBefore(current)) {
            nearestExpiry.put(tick.underlying(), tick.expiry());
            current = tick.expiry();
        }
        if (tick.expiry().equals(current)) {
            seen.putIfAbsent(key(tick.underlying(), tick.strike(), tick.optionType().name()),
                    new Seen(tick.instrumentToken(), tick.symbol(), tick.lotSize(), tick.expiry()));
        }
    }

    /** True once option quotes show {@code underlying}'s nearest expiry is this session. */
    boolean expiresToday(String underlying) {
        return session.equals(nearestExpiry.get(underlying));
    }

    Optional<Contract> find(String underlying, double strike, OptionSide side) {
        Seen quote = seen.get(key(underlying, strike, side.name()));
        if (quote == null) {
            return Optional.empty();
        }
        String exchange = underlying.equals("SENSEX") ? "BSE" : "NSE";
        Optional<Instrument> instrument = master == null ? Optional.empty()
                : master.option(underlying, quote.expiry(), strike, side.name());
        return Optional.of(instrument
                .map(i -> new Contract(quote.token(), i.instrumentKey(), quote.symbol(), exchange, i.lotSize(),
                        i.tickSize(), i.maxLotsPerOrder()))
                .orElse(new Contract(quote.token(), "ZT|" + quote.token(), quote.symbol(), exchange, quote.lotSize(),
                        DEFAULT_TICK, DEFAULT_MAX_LOTS)));
    }

    private static String key(String underlying, double strike, String type) {
        return underlying + ":" + type + ":" + strike;
    }
}
