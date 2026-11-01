package com.autotrade.exec.app;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Whether a date has a session: a weekday that is not in the exchange file's {@code holidays} (the same file trading-core
 * runs on). An unreadable file means no holidays are known: every weekday counts (a message too many, never one missed).
 */
@Component
class TradingDays {

    private static final Logger log = LoggerFactory.getLogger(TradingDays.class);

    private final Set<LocalDate> holidays;

    TradingDays(@Value("${autotrade.execution.exchange-file:config/exchange/nse-bse-sessions.v3.yaml}") String file) {
        this.holidays = load(Path.of(file));
    }

    boolean isTradingDay(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY
                && !holidays.contains(day);
    }

    static Set<LocalDate> load(Path file) {
        try (Reader in = Files.newBufferedReader(file)) {
            Object root = new Yaml().load(in);
            Object list = root instanceof Map<?, ?> m ? m.get("holidays") : null;
            if (!(list instanceof List<?> days)) {
                log.warn("no holidays in {}: every weekday is a trading day", file);
                return Set.of();
            }
            return days.stream().map(d -> LocalDate.parse(d.toString())).collect(Collectors.toUnmodifiableSet());
        } catch (IOException | RuntimeException e) {
            log.warn("exchange file {} not readable ({}): every weekday is a trading day", file, e.getMessage());
            return Set.of();
        }
    }
}
