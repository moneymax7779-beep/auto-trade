package com.autotrade.trading;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.autotrade.core.time.MarketTime;

/**
 * Sends futures-volume surges with agreeing OI flow to Telegram during the live session (--mode=auto only), for
 * manual calls, and a follow-up 15 minutes later with what the index did. Reads the same surge report as the Live
 * page; places no orders. After a restart the surges already on the board are marked seen, not re-sent.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class SurgeAlerter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SurgeAlerter.class);

    private final SurgeController surges;
    private final LevelsService levelsService;
    private final SessionRunner runner;
    private final AlertService alerts;
    private final AlertProperties properties;
    private final TradingProperties trading;
    private final JdbcTemplate jdbc;

    private LocalDate day;
    private SurgeAlertRules rules;
    private final Set<String> seen = new HashSet<>();
    private final Map<String, String> pending = new LinkedHashMap<>();   // underlying|t -> underlying
    private final Map<String, SurgeAlertRules.Plan> plans = new LinkedHashMap<>();   // the plan each alert sent
    private boolean primed;

    SurgeAlerter(SurgeController surges, LevelsService levelsService, SessionRunner runner, AlertService alerts,
                 AlertProperties properties, TradingProperties trading, DataSource target) {
        this.surges = surges;
        this.levelsService = levelsService;
        this.runner = runner;
        this.alerts = alerts;
        this.properties = properties;
        this.trading = trading;
        this.jdbc = new JdbcTemplate(target);
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> mode = args.getOptionValues("mode");
        if (mode != null && mode.contains("surge-preview")) {
            List<String> session = args.getOptionValues("session");
            preview(LocalDate.parse(session == null ? LocalDate.now(MarketTime.IST).toString() : session.getFirst()));
            return;
        }
        if (!properties.surgeAlerts() || mode == null || !mode.contains("auto")) {
            return;
        }
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
                runnable -> Thread.ofPlatform().name("surge-alerts").daemon(true).unstarted(runnable));
        timer.scheduleWithFixedDelay(this::tick, 45, 30, TimeUnit.SECONDS);
        log.info("surge alerts on: >= {}x, bullish/bearish flow, min volume {}, cooldown {} min", properties.surgeMinX(),
                properties.surgeMinVolume(), properties.surgeCooldownMin());
    }

    @SuppressWarnings("unchecked")
    private synchronized void tick() {
        try {
            ZonedDateTime now = ZonedDateTime.now(MarketTime.IST);
            LocalDate today = now.toLocalDate();
            LocalTime time = now.toLocalTime();
            if (!runner.tradingDay(today) || time.isBefore(LocalTime.of(9, 17)) || time.isAfter(LocalTime.of(15, 50))) {
                return;
            }
            TradingSession live = runner.current();
            Object status = live == null ? null : live.status().get("status");
            if (!"RUNNING".equals(status) && pending.isEmpty()) {
                return;
            }
            if (!today.equals(day)) {
                day = today;
                rules = new SurgeAlertRules(new SurgeAlertRules.Settings(properties.surgeMinX(), properties.surgeMinVolume(),
                        properties.surgeCooldownMin(), LocalTime.of(9, 20), LocalTime.of(15, 15)));
                seen.clear();
                pending.clear();
                plans.clear();
                primed = false;
            }
            for (String underlying : trading.trading().tradeUnderlyingList()) {
                Map<String, Object> report = surges.cached(today, underlying);
                List<Object> list = (List<Object>) report.getOrDefault("surges", List.of());
                for (Object o : list) {
                    Map<String, Object> s = (Map<String, Object>) o;
                    String key = underlying + "|" + s.get("t");
                    if (!seen.add(key) || !primed) {
                        continue;                 // already handled, or on the board before this process started
                    }
                    if (rules.accept(underlying, s)) {
                        SurgeAlertRules.Levels known = levels(today, underlying, report, time);
                        SurgeAlertRules.Plan plan = SurgeAlertRules.plan(s, known, (String) report.get("optionsExpiry"), today);
                        alerts.signal(SurgeAlertRules.message(underlying, s, known,
                                SurgeAlertRules.hitRate(list, (String) s.get("flow"), time), plan));
                        if (properties.surgeFollowUp()) {
                            pending.put(key, underlying);
                            plans.put(key, plan);
                        }
                    }
                }
                for (Object o : list) {
                    Map<String, Object> s = (Map<String, Object>) o;
                    String key = underlying + "|" + s.get("t");
                    if (pending.containsKey(key) && s.get("move15") != null) {
                        alerts.signal(SurgeAlertRules.followUp(underlying, s, plans.remove(key)));
                        pending.remove(key);
                    }
                }
            }
            primed = true;
        } catch (RuntimeException e) {
            log.warn("surge alert check failed: {}", e.toString());
        }
    }

    /** --mode=surge-preview: the messages these rules would have sent on {@code day}, logged, nothing sent. */
    @SuppressWarnings("unchecked")
    private void preview(LocalDate day) {
        SurgeAlertRules dry = new SurgeAlertRules(new SurgeAlertRules.Settings(properties.surgeMinX(),
                properties.surgeMinVolume(), properties.surgeCooldownMin(), LocalTime.of(9, 20), LocalTime.of(15, 15)));
        int count = 0;
        for (String underlying : trading.trading().tradeUnderlyingList()) {
            Map<String, Object> report = surges.cached(day, underlying);
            List<Object> list = (List<Object>) report.getOrDefault("surges", List.of());
            for (Object o : list) {
                Map<String, Object> s = (Map<String, Object>) o;
                if (!dry.accept(underlying, s)) {
                    continue;
                }
                LocalTime sentAt = LocalTime.parse((String) s.getOrDefault("from", s.get("t"))).plusMinutes(1);
                count++;
                SurgeAlertRules.Levels known = levels(day, underlying, report, sentAt);
                SurgeAlertRules.Plan plan = SurgeAlertRules.plan(s, known, (String) report.get("optionsExpiry"), day);
                log.info("PREVIEW alert {} at ~{}:\n{}\n{}", count, sentAt,
                        SurgeAlertRules.message(underlying, s, known, SurgeAlertRules.hitRate(list, (String) s.get("flow"), sentAt), plan),
                        SurgeAlertRules.followUp(underlying, s, plan));
            }
        }
        log.info("PREVIEW {}: {} alerts", day, count);
    }

    /** The levels known at {@code asOf} (LevelsService), VWAP at that minute and the day's range so far. */
    @SuppressWarnings("unchecked")
    private SurgeAlertRules.Levels levels(LocalDate today, String underlying, Map<String, Object> report, LocalTime asOf) {
        Double dayHigh = null, dayLow = null;
        for (Object o : (List<Object>) report.getOrDefault("index", List.of())) {
            if (LocalTime.parse((String) ((List<Object>) o).get(0)).isAfter(asOf)) {
                continue;                          // only what was known by then
            }
            double p = ((Number) ((List<Object>) o).get(1)).doubleValue();
            dayHigh = dayHigh == null ? p : Math.max(dayHigh, p);
            dayLow = dayLow == null ? p : Math.min(dayLow, p);
        }
        Map<String, Object> data = levelsService.levels(today, underlying);
        List<SurgeAlertRules.Mark> marks = new java.util.ArrayList<>();
        for (LevelsService.Level l : (List<LevelsService.Level>) data.get("levels")) {
            if (!LocalTime.parse(l.from()).isAfter(asOf)) {
                marks.add(new SurgeAlertRules.Mark(l.name(), l.price(), l.price()));
            }
        }
        for (LevelsService.Zone z : (List<LevelsService.Zone>) data.get("zones")) {
            boolean active = !LocalTime.parse(z.from()).isAfter(asOf) && (z.until() == null || LocalTime.parse(z.until()).isAfter(asOf));
            if (active) {
                marks.add(new SurgeAlertRules.Mark(z.kind() + " zone (" + z.touches() + "×)", z.lo(), z.hi()));
            }
        }
        Double vwap = null;
        for (Object o : (List<Object>) ((Map<String, Object>) data.get("lines")).get("vwap")) {
            List<Object> row = (List<Object>) o;
            if (!LocalTime.parse((String) row.get(0)).isAfter(asOf)) {
                vwap = ((Number) row.get(1)).doubleValue();
            }
        }
        return new SurgeAlertRules.Levels(marks, vwap, dayHigh, dayLow);
    }
}
