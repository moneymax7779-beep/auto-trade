package com.autotrade.exec.app;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.autotrade.execution.SignalDecisionStore;

/**
 * Runs the execution tick as soon as a signal decision is stored (Postgres LISTEN on
 * {@link SignalDecisionStore#CHANNEL}), instead of at the next poll. 4 Nov 2026, the first LIVE day: the 1 s poll was
 * 0.7-1.0 s of every ZERODHA-1 order, from the signal's decision to the Kite place. The poll stays as the fallback, so a
 * lost notification or a dropped listener connection only costs the old delay. One connection of the pool is held while
 * listening; on any error it is given back and taken again after {@link #RETRY}.
 * <p>
 * {@code autotrade.execution.wake-on-signal: false} turns it off (poll only).
 */
@Component
class SignalWakeup implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SignalWakeup.class);
    static final Duration RETRY = Duration.ofSeconds(5);
    private static final int WAIT_MS = 1000;                   // getNotifications blocks this long, then checks for a stop

    private final DataSource dataSource;
    private final Runnable onSignal;
    private final boolean enabled;
    private volatile boolean stopped;
    private volatile boolean listening;
    private Thread thread;

    @org.springframework.beans.factory.annotation.Autowired
    SignalWakeup(DataSource dataSource, ExecutionService service,
                 @Value("${autotrade.execution.wake-on-signal:true}") boolean enabled) {
        this(dataSource, service::tick, enabled);
    }

    SignalWakeup(DataSource dataSource, Runnable onSignal, boolean enabled) {
        this.dataSource = dataSource;
        this.onSignal = onSignal;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void start() {
        if (!enabled) {
            log.info("wake on signal off: the execution tick runs on its poll only");
            return;
        }
        if (thread == null) {
            thread = Thread.ofPlatform().name("exec-signal-wakeup").daemon(true).start(this::run);
        }
    }

    /** True while a LISTEN connection is open (tests and the status page). */
    boolean listening() {
        return listening;
    }

    private void run() {
        while (!stopped) {
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                c.setAutoCommit(true);
                s.execute("listen " + SignalDecisionStore.CHANNEL);
                PGConnection pg = c.unwrap(PGConnection.class);
                listening = true;
                log.info("listening for signal decisions on {}", SignalDecisionStore.CHANNEL);
                while (!stopped) {
                    PGNotification[] got = pg.getNotifications(WAIT_MS);
                    if (got != null && got.length > 0 && !stopped) {
                        try {
                            onSignal.run();                    // one tick for however many arrived together
                        } catch (RuntimeException e) {
                            log.error("tick after a signal decision failed", e);
                        }
                    }
                }
            } catch (SQLException | RuntimeException e) {
                if (!stopped) {
                    log.warn("signal listener lost ({}); polling only, listening again in {} s", e.getMessage(),
                            RETRY.toSeconds());
                }
            } finally {
                listening = false;
            }
            if (!stopped) {
                try {
                    Thread.sleep(RETRY.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @Override
    public synchronized void destroy() {
        stopped = true;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
