package com.autotrade.trading;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

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

    @org.springframework.beans.factory.annotation.Autowired
    MarketHours(TradingProperties properties) {
        SessionClock clock = null;
        try {
            TradingProperties.Trading t = properties.trading();
            clock = new SessionClock(FeatureConfig.from(ThresholdConfig.load(Path.of(t.featuresFile())),
                    ThresholdConfig.load(Path.of(t.exchangeFile()))));
        } catch (RuntimeException e) {
            log.warn("market hours: no exchange calendar ({}); weekdays count as trading days", e.getMessage());
        }
        this.calendar = clock;
    }

    /** For tests: a known calendar, or null for the weekday fallback. */
    MarketHours(SessionClock calendar) {
        this.calendar = calendar;
    }

    boolean tradingDay(LocalDate day) {
        return calendar != null ? calendar.isTradingDay(day) : day.getDayOfWeek().getValue() <= 5;
    }

    /** A trading day between 09:00 and 15:50 IST. */
    boolean busy(ZonedDateTime now) {
        ZonedDateTime ist = now.withZoneSameInstant(MarketTime.IST);
        return tradingDay(ist.toLocalDate()) && !ist.toLocalTime().isBefore(FROM) && ist.toLocalTime().isBefore(UNTIL);
    }
}
