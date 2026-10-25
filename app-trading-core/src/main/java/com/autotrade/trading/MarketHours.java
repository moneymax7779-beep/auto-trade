package com.autotrade.trading;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.time.SessionClock;

/**
 * Whether the market is busy at a moment, for the display endpoints that defer heavy reads of earlier days to after the
 * close (PCR, OI tape): a trading day in the exchange holiday file between 09:00 and 15:50 IST. Without the files it
 * falls back to weekdays, so a misconfigured server defers more, never less.
 */
@Component
class MarketHours {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarketHours.class);
    static final LocalTime FROM = LocalTime.of(9, 0), UNTIL = LocalTime.of(15, 50);

    private final SessionClock calendar;
    private final Set<Integer> calendarYears;      // years the holiday list covers

    @org.springframework.beans.factory.annotation.Autowired
    MarketHours(TradingProperties properties) {
        FeatureConfig config = null;
        try {
            TradingProperties.Trading t = properties.trading();
            config = FeatureConfig.from(ThresholdConfig.load(Path.of(t.featuresFile())),
                    ThresholdConfig.load(Path.of(t.exchangeFile())));
        } catch (RuntimeException e) {
            log.warn("market hours: no exchange calendar ({}); weekdays count as trading days", e.getMessage());
        }
        this.calendar = config == null ? null : new SessionClock(config);
        this.calendarYears = config == null ? Set.of() : years(config.holidays());
    }

    /** For tests: a known calendar, or null for the weekday fallback. */
    MarketHours(SessionClock calendar) {
        this(calendar, Set.of());
    }

    /** For tests: a calendar and the holidays it lists, which say which years it covers. */
    MarketHours(SessionClock calendar, Set<LocalDate> holidays) {
        this.calendar = calendar;
        this.calendarYears = years(holidays);
    }

    private static Set<Integer> years(Set<LocalDate> holidays) {
        return holidays.stream().map(LocalDate::getYear).collect(Collectors.toUnmodifiableSet());
    }

    boolean tradingDay(LocalDate day) {
        return calendar != null ? calendar.isTradingDay(day) : day.getDayOfWeek().getValue() <= 5;
    }

    /**
     * A trading day by the exchange calendar in a year its holiday list covers. False for years it does not list
     * (2024-25 bar days), where an unlisted holiday would look like a trading day: unknown, not assumed.
     */
    boolean knownTradingDay(LocalDate day) {
        return calendar != null && calendarYears.contains(day.getYear()) && calendar.isTradingDay(day);
    }

    /** A trading day between 09:00 and 15:50 IST. */
    boolean busy(ZonedDateTime now) {
        ZonedDateTime ist = now.withZoneSameInstant(MarketTime.IST);
        return tradingDay(ist.toLocalDate()) && !ist.toLocalTime().isBefore(FROM) && ist.toLocalTime().isBefore(UNTIL);
    }
}
