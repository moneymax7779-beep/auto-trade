package com.autotrade.trading;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.autotrade.core.time.MarketTime;
import com.autotrade.md.store.ReferenceStore;

/**
 * Collects the pre-open and positioning cues in the background (feature/missing-data), only when
 * {@code autotrade.cues.enabled} and only in {@code --mode=auto} or {@code serve} (never in a replay or a tool command):
 * <ul>
 * <li>GIFT Nifty quotes every {@code autotrade.cues.gift-poll-minutes} minutes, except 03:00-06:00 IST when NSE IX's
 *     index futures do not trade (a re-fetched unchanged quote is not stored twice);</li>
 * <li>NSE's participant-wise OI once each weekday evening from 19:00 IST, retried every 30 minutes until 23:30 while
 *     the day's file is not there yet.</li>
 * </ul>
 * Failures are logged (at most every 30 minutes each) and never stop anything else.
 */
@Component
class CueScheduler implements org.springframework.beans.factory.DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CueScheduler.class);
    static final LocalTime QUIET_FROM = LocalTime.of(3, 0), QUIET_UNTIL = LocalTime.of(6, 0);
    static final LocalTime PARTICIPANT_FROM = LocalTime.of(19, 0), PARTICIPANT_UNTIL = LocalTime.of(23, 30);

    private final boolean enabled;
    private final int giftPollMinutes;
    private final ApplicationArguments args;
    private final ReferenceStore store;
    private final CueFetcher fetcher;
    private final Map<String, Object> status = new LinkedHashMap<>();
    private ScheduledExecutorService timer;
    private Instant lastGiftPoll, lastParticipantTry, lastGiftWarn, lastParticipantWarn;

    CueScheduler(DataSource target, ApplicationArguments args, @Value("${autotrade.cues.enabled:false}") boolean enabled,
                 @Value("${autotrade.cues.gift-poll-minutes:5}") int giftPollMinutes) {
        this.enabled = enabled;
        this.giftPollMinutes = Math.max(1, giftPollMinutes);
        this.args = args;
        this.store = new ReferenceStore(target);
        this.fetcher = new CueFetcher(store);
        status.put("enabled", enabled);
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        String mode = args.containsOption("mode") && !args.getOptionValues("mode").isEmpty()
                ? args.getOptionValues("mode").getFirst() : "live";
        if (!enabled || !("auto".equals(mode) || "serve".equals(mode))) {
            status.put("running", false);
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("cues").daemon(true).factory());
        timer.scheduleAtFixedRate(this::tick, 5, 60, TimeUnit.SECONDS);
        status.put("running", true);
        log.info("cues: GIFT Nifty every {} min, participant OI each weekday from {} IST", giftPollMinutes, PARTICIPANT_FROM);
    }

    @Override
    public void destroy() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    synchronized Map<String, Object> status() {
        return new LinkedHashMap<>(status);
    }

    void tick() {
        ZonedDateTime now = ZonedDateTime.now(MarketTime.IST);
        if (dueForGift(now, lastGiftPoll, giftPollMinutes)) {
            lastGiftPoll = now.toInstant();
            try {
                int added = fetcher.fetchGiftNifty();
                record("giftLastFetch", now.toString(), "giftLastNew", added, "giftError", null);
            } catch (Exception e) {
                record("giftError", e.getMessage());
                if (lastGiftWarn == null || lastGiftWarn.isBefore(Instant.now().minusSeconds(1800))) {
                    lastGiftWarn = Instant.now();
                    log.warn("cues: GIFT Nifty fetch failed: {}", e.getMessage());
                }
            }
        }
        LocalDate today = now.toLocalDate();
        if (dueForParticipant(now, lastParticipantTry) && !participantStored(today)) {
            lastParticipantTry = Instant.now();
            try {
                var rows = fetcher.fetchParticipantOi(today);
                record("participantLastTry", now.toString(), "participantRows", rows.orElse(0), "participantError", null);
                rows.ifPresent(n -> log.info("cues: participant OI {} stored ({} rows)", today, n));
            } catch (Exception e) {
                record("participantError", e.getMessage());
                if (lastParticipantWarn == null || lastParticipantWarn.isBefore(Instant.now().minusSeconds(1800))) {
                    lastParticipantWarn = Instant.now();
                    log.warn("cues: participant OI fetch failed: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * A GIFT poll is due when {@code everyMinutes} have passed since the last (5 s slack for the timer), outside NSE IX's
     * 03:00-06:00 IST pause.
     */
    static boolean dueForGift(ZonedDateTime now, Instant lastPoll, int everyMinutes) {
        LocalTime t = now.toLocalTime();
        boolean quiet = !t.isBefore(QUIET_FROM) && t.isBefore(QUIET_UNTIL);
        return !quiet && (lastPoll == null || !lastPoll.plusSeconds(everyMinutes * 60L - 5).isAfter(now.toInstant()));
    }

    /** Weekdays 19:00-23:30 IST, at most every 30 minutes. */
    static boolean dueForParticipant(ZonedDateTime now, Instant lastTry) {
        LocalTime t = now.toLocalTime();
        return now.getDayOfWeek().getValue() <= 5 && !t.isBefore(PARTICIPANT_FROM) && t.isBefore(PARTICIPANT_UNTIL)
                && (lastTry == null || lastTry.isBefore(now.toInstant().minusSeconds(1800)));
    }

    private boolean participantStored(LocalDate day) {
        var latest = store.participantOi(day.plusDays(1), 1);
        return !latest.isEmpty() && latest.getFirst().session().equals(day);
    }

    private synchronized void record(Object... keyValues) {
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            status.put((String) keyValues[i], keyValues[i + 1]);
        }
    }
}
