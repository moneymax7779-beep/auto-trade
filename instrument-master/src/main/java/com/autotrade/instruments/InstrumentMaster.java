package com.autotrade.instruments;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/** Lookups over one contract-master snapshot. */
public final class InstrumentMaster {

    private final Map<String, TreeSet<LocalDate>> optionExpiries = new HashMap<>();
    private final Map<String, List<Instrument>> byUnderlying = new HashMap<>();
    private final Map<String, Instrument> byKey = new HashMap<>();

    public InstrumentMaster(List<Instrument> instruments) {
        for (Instrument instrument : instruments) {
            byUnderlying.computeIfAbsent(instrument.underlying(), u -> new ArrayList<>()).add(instrument);
            byKey.put(instrument.instrumentKey(), instrument);
            if (instrument.isOption() && instrument.expiry() != null) {
                optionExpiries.computeIfAbsent(instrument.underlying(), u -> new TreeSet<>()).add(instrument.expiry());
            }
        }
    }

    public Optional<Instrument> byKey(String instrumentKey) {
        return Optional.ofNullable(byKey.get(instrumentKey));
    }

    /** Option expiries on or after {@code from}, earliest first. */
    public List<LocalDate> optionExpiries(String underlying, LocalDate from) {
        TreeSet<LocalDate> expiries = optionExpiries.get(underlying);
        return expiries == null ? List.of() : List.copyOf(expiries.tailSet(from, true));
    }

    public Optional<LocalDate> nearestOptionExpiry(String underlying, LocalDate from) {
        return optionExpiries(underlying, from).stream().findFirst();
    }

    public Optional<Instrument> option(String underlying, LocalDate expiry, double strike, String type) {
        return instruments(underlying).stream()
                .filter(i -> i.isOption() && type.equals(i.type()) && expiry.equals(i.expiry()) && i.strike() == strike)
                .findFirst();
    }

    /** The nearest-expiry future on or after {@code from}. */
    public Optional<Instrument> nearestFuture(String underlying, LocalDate from) {
        return instruments(underlying).stream()
                .filter(i -> i.isFuture() && i.expiry() != null && !i.expiry().isBefore(from))
                .min(Comparator.comparing(Instrument::expiry));
    }

    /** Strike spacing for an expiry (smallest gap between listed strikes). */
    public double strikeStep(String underlying, LocalDate expiry) {
        TreeSet<Double> strikes = new TreeSet<>();
        for (Instrument instrument : instruments(underlying)) {
            if (instrument.isOption() && expiry.equals(instrument.expiry())) {
                strikes.add(instrument.strike());
            }
        }
        double step = Double.NaN;
        Double previous = null;
        for (double strike : strikes) {
            if (previous != null && (Double.isNaN(step) || strike - previous < step)) {
                step = strike - previous;
            }
            previous = strike;
        }
        return step;
    }

    public List<Instrument> instruments(String underlying) {
        return byUnderlying.getOrDefault(underlying, List.of());
    }

    public int size() {
        return byKey.size();
    }
}
