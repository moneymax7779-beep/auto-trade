package com.autotrade.exec.app;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.execution.ExecutionLoop;
import com.autotrade.execution.StoredQuotes;
import com.autotrade.sim.CostModel;

/**
 * Runs the {@link ExecutionLoop} every poll interval. Refuses to start unless the schema is at least V23 (this service
 * never migrates: trading-core owns the schema). Retry quotes come from today's own capture (md.option_tick).
 */
@Service
class ExecutionService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int REQUIRED_SCHEMA = 24;

    private final DataSource dataSource;
    private final ExecutionProperties properties;
    private volatile ExecutionLoop loop;
    private volatile java.time.Instant lastTick;

    ExecutionService(DataSource dataSource, ExecutionProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        Integer version = new JdbcTemplate(dataSource).queryForObject(
                "select max(version::int) from flyway_schema_history where success", Integer.class);
        if (version == null || version < REQUIRED_SCHEMA) {
            throw new IllegalStateException("schema version " + version + " < " + REQUIRED_SCHEMA
                    + ": start trading-core with this code first (it owns the migrations)");
        }
        CostModel costs = CostModel.from(ThresholdConfig.load(Path.of(properties.costsFile())));
        loop = new ExecutionLoop(dataSource, properties.tenant(), properties.copyGroup(), properties.workerId(), costs,
                new StoredQuotes(dataSource, null), properties.brokerFunds());
        log.info("execution worker {} serving copy group {} (tenant {}), every {} ms", properties.workerId(),
                properties.copyGroup(), properties.tenant(), properties.pollMs());
        if (properties.day() != null) {
            log.warn("DRILL: serving session date {} instead of today", properties.day());
        }
    }

    @Scheduled(fixedDelayString = "${autotrade.execution.poll-ms:1000}", initialDelay = 5000)
    void tick() {
        ExecutionLoop current = loop;
        if (current != null) {
            current.tick(day(), properties.pollMs() * 5);
            lastTick = java.time.Instant.now();
        }
    }

    /** Today, or the drill day (a replayed day streamed through the worker). */
    LocalDate day() {
        return properties.day() == null ? LocalDate.now(IST) : LocalDate.parse(properties.day());
    }

    /**
     * 15:20 IST, weekdays (the live set is flat by 15:15; Zerodha squares MIS off from about 15:20): any follower position
     * still open is flattened without a kill switch (code review 24 Oct 2026: a stopped signal source sends no EXIT).
     */
    @Scheduled(cron = "${autotrade.execution.square-off-cron:0 20 15 * * MON-FRI}", zone = "Asia/Kolkata")
    void squareOff() {
        ExecutionLoop current = loop;
        if (current != null) {
            current.squareOff(day()).forEach(line -> log.warn("end-of-day square-off: {}", line));
        }
    }

    /** After the close (15:45 IST, weekdays): every broker-routed account this worker holds is reconciled. */
    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
    void endOfDay() {
        ExecutionLoop current = loop;
        if (current != null) {
            current.endOfDay(day()).forEach(d -> log.info("end of day account {}: difference {}, {} contracts "
                    + "differ", d.ledger().brokerAccountId(), d.ledger().difference(), d.differences().size()));
        }
    }

    List<String> flatten(long accountId, String actor, String reason, LocalDate day) throws Exception {
        ExecutionLoop current = loop;
        if (current == null) {
            return List.of("the worker has not started");
        }
        return current.flatten(accountId, actor, reason, day);
    }

    /** When the loop last finished a tick (null before the first): the watchdog's heartbeat. */
    java.time.Instant lastTick() {
        return lastTick;
    }

    List<ExecutionLoop.AccountStatus> status() {
        ExecutionLoop current = loop;
        return current == null ? List.of() : current.status();
    }

    @Override
    public void destroy() {
        if (loop != null) {
            loop.close();                       // releases every account lease
        }
    }
}
