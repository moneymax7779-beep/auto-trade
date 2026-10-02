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
    private final SessionRunner runner;
    private final AlertService alerts;
    private final AlertProperties properties;
    private final TradingProperties trading;
    private final JdbcTemplate jdbc;

    private LocalDate day;
    private SurgeAlertRules rules;
    private final Set<String> seen = new HashSet<>();
    private final Map<String, String> pending = new LinkedHashMap<>();   // underlying|t -> underlying
    private boolean primed;

    SurgeAlerter(SurgeController surges, SessionRunner runner, AlertService alerts, AlertProperties properties,
                 TradingProperties trading, DataSource target) {
        this.surges = surges;
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
                        alerts.signal(SurgeAlertRules.message(underlying, s, levels(today, underlying, report, time),
                                SurgeAlertRules.hitRate(list, (String) s.get("flow"), time)));
                        if (properties.surgeFollowUp()) {
                            pending.put(key, underlying);
                        }
                    }
                }
                for (Object o : list) {
                    Map<String, Object> s = (Map<String, Object>) o;
                    String key = underlying + "|" + s.get("t");
                    if (pending.containsKey(key) && s.get("move15") != null) {
                        alerts.signal(SurgeAlertRules.followUp(underlying, s));
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
                log.info("PREVIEW alert {} at ~{}:\n{}\n{}", count, sentAt,
                        SurgeAlertRules.message(underlying, s, levels(day, underlying, report, sentAt),
                                SurgeAlertRules.hitRate(list, (String) s.get("flow"), sentAt)),
                        SurgeAlertRules.followUp(underlying, s));
            }
        }
        log.info("PREVIEW {}: {} alerts", day, count);
    }

    /** Opening range and previous-day levels from the live features; the day's range from the index series. */
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
        List<SurgeAlertRules.Levels> rows = jdbc.query("select (features->>'structure.orHigh')::float8, "
                        + "(features->>'structure.orLow')::float8, (features->>'structure.pdh')::float8, "
                        + "(features->>'structure.pdl')::float8 from feat.snapshot where session_date = ? and underlying = ? "
                        + "order by snap_time desc limit 1",
                (rs, i) -> new SurgeAlertRules.Levels((Double) rs.getObject(1), (Double) rs.getObject(2),
                        (Double) rs.getObject(3), (Double) rs.getObject(4), null, null), today, underlying);
        SurgeAlertRules.Levels l = rows.isEmpty() ? new SurgeAlertRules.Levels(null, null, null, null, null, null) : rows.getFirst();
        return new SurgeAlertRules.Levels(l.orHigh(), l.orLow(), l.pdh(), l.pdl(), dayHigh, dayLow);
    }
}
