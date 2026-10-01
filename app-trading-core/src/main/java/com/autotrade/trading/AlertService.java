package com.autotrade.trading;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.autotrade.core.time.MarketTime;

/**
 * Active alerts and their delivery. An alert is sent when it is raised, again every 30 minutes while it stays
 * active (CRITICAL / WARN), and once more when it clears; INFO messages are sent once. Everything also goes
 * to the log and to /api/alerts for the Live page.
 */
@Component
class AlertService {

    enum Level { CRITICAL, WARN, INFO, RESOLVED }

    record Alert(String key, Level level, String message, Instant since, Instant lastSent) {
    }

    record Sent(Instant at, Level level, String message) {
    }

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final Duration REPEAT = Duration.ofMinutes(30);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm");

    private final TelegramNotifier telegram;
    private final Map<String, Alert> active = new LinkedHashMap<>();
    private final Deque<Sent> recent = new ArrayDeque<>();

    AlertService(AlertProperties properties) {
        this.telegram = new TelegramNotifier(Path.of(properties.telegramFile()));
    }

    synchronized void raise(String key, Level level, String message) {
        Instant now = Instant.now();
        Alert current = active.get(key);
        boolean sameLevel = current != null && current.level() == level;
        if (sameLevel && Duration.between(current.lastSent(), now).compareTo(REPEAT) < 0) {
            // same alert, same level: keep the latest text (e.g. the free GB) without sending it again yet
            active.put(key, new Alert(key, level, message, current.since(), current.lastSent()));
            return;
        }
        Instant since = current != null ? current.since() : now;
        active.put(key, new Alert(key, level, message, since, now));
        deliver(level, sameLevel ? message + " (still active since " + at(since) + ")" : message);
    }

    synchronized void clear(String key, String resolved) {
        Alert current = active.remove(key);
        if (current != null) {
            deliver(Level.RESOLVED, resolved);
        }
    }

    synchronized void info(String message) {
        deliver(Level.INFO, message);
    }

    synchronized List<Alert> active() {
        return List.copyOf(active.values());
    }

    synchronized List<Sent> recent() {
        return new ArrayList<>(recent);
    }

    boolean telegramConfigured() {
        return telegram.configured();
    }

    private void deliver(Level level, String message) {
        String text = "auto-trade PAPER · " + level + "\n" + message;
        switch (level) {
            case CRITICAL -> log.error("ALERT {}", message);
            case WARN -> log.warn("ALERT {}", message);
            default -> log.info("ALERT {} {}", level, message);
        }
        recent.addFirst(new Sent(Instant.now(), level, message));
        while (recent.size() > 50) {
            recent.removeLast();
        }
        telegram.send(text);
    }

    private static String at(Instant instant) {
        return ZonedDateTime.ofInstant(instant, MarketTime.IST).format(TIME);
    }
}
