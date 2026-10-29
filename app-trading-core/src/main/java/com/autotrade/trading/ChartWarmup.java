package com.autotrade.trading;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * After a start in {@code auto} or {@code serve} mode, computes the latest captured day's index charts in the background
 * (user, 29 Oct 2026: "why the loading the chart and other data taking time?": the first visit after a restart waited
 * 1-5 s per panel while each was computed from the day's ticks): surges, levels, the OI tape and PCR of NIFTY and SENSEX,
 * and the premium of the at-the-money strike the chart opens on. Finished days are then kept in md.chart_day_cache. On
 * the charts pool only, one request at a time; a failure is logged and the page simply computes it when visited.
 */
@Component
class ChartWarmup {

    private static final Logger log = LoggerFactory.getLogger(ChartWarmup.class);
    private static final List<String> INDICES = List.of("NIFTY", "SENSEX");

    private final ApplicationArguments args;
    private final SurgeController surges;
    private final LevelsController levels;
    private final OiTapeController tape;
    private final PcrController pcr;
    private final PremiumController premium;

    ChartWarmup(ApplicationArguments args, SurgeController surges, LevelsController levels, OiTapeController tape,
                PcrController pcr, PremiumController premium) {
        this.args = args;
        this.surges = surges;
        this.levels = levels;
        this.tape = tape;
        this.pcr = pcr;
        this.premium = premium;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        List<String> mode = args.getOptionValues("mode");
        if (mode == null || mode.isEmpty() || !(mode.contains("serve") || mode.contains("auto"))) {
            return;                                           // replays and live tails do not serve the charts
        }
        Thread.ofVirtual().name("chart-warmup").start(this::warm);
    }

    void warm() {
        long started = System.currentTimeMillis();
        LocalDate day;
        try {
            day = surges.latestDay();
        } catch (RuntimeException e) {
            log.warn("chart warm-up: no latest day: {}", e.toString());
            return;
        }
        if (day == null) {
            return;
        }
        String date = day.toString();
        for (String u : INDICES) {
            step(u + " surges", () -> {
                Map<String, Object> s = surges.cached(day, u);
                Object expiry = s.get("optionsExpiry");
                List<?> index = (List<?>) s.get("index");
                if (expiry != null && index != null && !index.isEmpty()) {
                    double spot = ((Number) ((List<?>) index.getLast()).get(1)).doubleValue();
                    double step = u.equals("NIFTY") ? 50 : 100;
                    premium.premium(date, u, expiry.toString(), Math.round(spot / step) * step);
                }
            });
            step(u + " levels", () -> levels.levels(date, u));
            step(u + " OI tape", () -> tape.tape(date, u, OiTapeController.BAND));
            step(u + " PCR", () -> pcr.pcr(date, u));
        }
        log.info("chart warm-up: {} ready in {} s", date, (System.currentTimeMillis() - started) / 1000);
    }

    private static void step(String what, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.warn("chart warm-up: {} failed: {}", what, e.toString());
        }
    }
}
