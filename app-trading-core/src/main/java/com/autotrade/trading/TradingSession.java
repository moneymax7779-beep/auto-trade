package com.autotrade.trading;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.broker.paper.PaperBroker;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.history.ReferenceData;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.FeatureEngine;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.instruments.InstrumentMaster;
import com.autotrade.md.live.LiveFeed;
import com.autotrade.oms.Contract;
import com.autotrade.oms.ManagedPosition;
import com.autotrade.oms.MarketContext;
import com.autotrade.oms.OmsListener;
import com.autotrade.oms.OrderManager;
import com.autotrade.risk.KillSwitch;
import com.autotrade.risk.RiskDecision;
import com.autotrade.risk.RiskEngine;
import com.autotrade.risk.RiskLimits;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.FillModel;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/**
 * One PAPER trading session: feed → features → strategy → risk → OMS → paper broker, all on one
 * thread under one lock. In replay mode the clock follows event time; live, it is the wall clock
 * (so the paper broker also charges the feed's own delay as latency).
 */
public final class TradingSession {

    public enum Mode { PAPER_LIVE, PAPER_REPLAY }

    public record Settings(Mode mode, String account, LocalDate session, List<String> underlyings,
                           FeatureConfig features, SessionHistory history, StrategyFactory strategies,
                           double premiumStopPct, RiskLimits risk, CostModel costs, FillModel fills,
                           InstrumentMaster instruments, Map<String, String> configHashes, String codeVersion) {
    }

    private static final Logger log = LoggerFactory.getLogger(TradingSession.class);
    private static final Duration STALE_ALERT = Duration.ofSeconds(10);
    private static final Duration MAX_DECISION_AGE = Duration.ofSeconds(60);

    private final Settings settings;
    private final LiveFeed feed;
    private final TradeStore store;
    private final Object lock = new Object();
    private final AtomicReference<Instant> eventTime = new AtomicReference<>();
    private final Supplier<Instant> clock;
    private final KillSwitch killSwitch;
    private final RiskEngine risk;
    private PaperBroker broker;
    private OrderManager oms;
    private final ContractResolver contracts;
    private final Map<String, FeatureEngine> engines = new LinkedHashMap<>();
    private final Map<String, Strategy> strategies = new HashMap<>();
    private final Map<String, Decision> lastDecisions = new LinkedHashMap<>();
    private final Map<String, Double> lastSpot = new HashMap<>();
    private final List<String> recentRejections = new ArrayList<>();
    private final AtomicLong events = new AtomicLong();
    private long sessionId;
    private Instant lastReconcile;
    private Instant lastStaleAlert;
    private boolean squaredOff;
    private volatile String status = "STARTING";

    public TradingSession(Settings settings, LiveFeed feed, TradeStore store) {
        this.settings = settings;
        this.feed = feed;
        this.store = store;
        this.clock = settings.mode() == Mode.PAPER_REPLAY
                ? () -> eventTime.get() == null ? MarketTime.sessionStart(settings.session()) : eventTime.get()
                : Instant::now;
        this.killSwitch = new KillSwitch((scope, engagement) -> {
            if (sessionId > 0) {
                store.killSwitch(sessionId, scope, engagement != null,
                        engagement == null ? "released" : engagement.reason(), clock.get(),
                        engagement == null ? "operator" : engagement.by());
            }
            log.warn("kill switch {} {}", scope, engagement == null ? "RELEASED" : "ENGAGED: " + engagement.reason());
        });
        this.risk = new RiskEngine(settings.risk(), killSwitch);
        this.contracts = new ContractResolver(settings.session(), settings.instruments());
        for (String underlying : settings.underlyings()) {
            strategies.put(underlying, settings.strategies().create(underlying, settings.session()));
            engines.put(underlying, new FeatureEngine(underlying, settings.session(), settings.features(),
                    settings.history(), this::onSnapshot));
        }
    }

    /** Runs the session until the feed ends (replay) or {@link #stop()} (live). */
    public Map<String, Object> run() {
        sessionId = store.startSession(settings.account(), settings.mode().name(), settings.session(), feed.name(),
                settings.strategies().id(), settings.strategies().configHash(), settings.configHashes(),
                settings.codeVersion());
        // Order ids carry the session id so they never repeat across runs of the same day.
        broker = new PaperBroker(settings.account(), settings.fills().latency(), settings.fills().adverseBps(), clock);
        oms = new OrderManager(settings.account(), settings.account() + "-S" + sessionId, settings.strategies().id(),
                settings.session(), broker, risk, settings.costs(), settings.premiumStopPct(), clock,
                new StoreListener());
        status = "RUNNING";
        log.info("trading session {} ({} {}, {}) started on {}", sessionId, settings.mode(), settings.session(),
                settings.account(), feed.name());
        String error = null;
        try {
            feed.run(this::onEvent);
            synchronized (lock) {
                engines.values().forEach(FeatureEngine::finish);
                if (!oms.livePositions().isEmpty()) {
                    oms.exitAll("SESSION_END");
                }
            }
            status = "DONE";
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
            status = "FAILED";
            log.error("trading session {} failed", sessionId, e);
        }
        Map<String, Object> summary = summary();
        store.endSession(sessionId, status, summary, error);
        log.info("trading session {} {}: {}", sessionId, status, summary);
        return summary;
    }

    public void stop() {
        feed.stop();
    }

    private void onEvent(MarketEvent event) {
        synchronized (lock) {
            events.incrementAndGet();
            if (settings.mode() == Mode.PAPER_REPLAY) {
                eventTime.set(event.receivedAt());
            }
            if (event instanceof OptionTick tick) {
                contracts.accept(tick);
                oms.observe(tick);
            }
            if (ReferenceData.INDIA_VIX.equals(event.underlying())) {
                // Market-wide context: every underlying's engine takes India VIX.
                engines.values().forEach(engine -> engine.accept(event));
            } else {
                FeatureEngine engine = engines.get(event.underlying());
                if (engine != null) {
                    engine.accept(event);
                }
            }
            broker.accept(event);
            reconcileIfDue();
        }
    }

    /** Wall-clock duties when live: OMS timers, the square-off even if the feed stops, stale-feed alerts. */
    public void heartbeat() {
        if (settings.mode() != Mode.PAPER_LIVE || oms == null) {
            return;
        }
        synchronized (lock) {
            Instant now = clock.get();
            oms.onTimer(now);
            LocalTime time = now.atZone(MarketTime.IST).toLocalTime();
            if (risk.squareOffDue(time) && !squaredOff && !oms.livePositions().isEmpty()) {
                oms.exitAll("SQUARE_OFF");
                squaredOff = true;
            }
            Instant last = feed.lastEventTime();
            boolean marketHours = !time.isBefore(settings.features().marketOpen())
                    && time.isBefore(settings.features().derivativesClose());
            if (marketHours && last != null && Duration.between(last, now).compareTo(STALE_ALERT) > 0
                    && (lastStaleAlert == null || Duration.between(lastStaleAlert, now).toMinutes() >= 1)) {
                lastStaleAlert = now;
                log.warn("feed stale: last event {} s ago", Duration.between(last, now).toSeconds());
            }
            reconcileIfDue();
        }
    }

    private void onSnapshot(FeatureSnapshot snapshot) {
        String underlying = snapshot.underlying();
        LocalTime time = snapshot.time().atZone(MarketTime.IST).toLocalTime();
        Decision decision = strategies.get(underlying).decide(snapshot, oms.view(underlying));
        lastDecisions.put(underlying, decision);
        lastSpot.put(underlying, snapshot.spot());
        store.decision(sessionId, decision, snapshot.spot());
        if (risk.squareOffDue(time)) {
            if (!oms.livePositions().isEmpty()) {
                oms.exitAll("SQUARE_OFF");
            }
            return;
        }
        MarketContext market = new MarketContext(time, snapshot.secondsSinceSpot(), snapshot.secondsSinceOption());
        // Live: never open or add from a stale snapshot (e.g. while the feed catches up on the morning).
        boolean stale = settings.mode() == Mode.PAPER_LIVE
                && Duration.between(snapshot.time(), Instant.now()).compareTo(MAX_DECISION_AGE) > 0;
        for (OrderIntent intent : decision.orders()) {
            if (stale && intent.action() != OrderIntent.Action.EXIT) {
                rejected(underlying, intent.action() + " " + intent.side(), "snapshot "
                        + Duration.between(snapshot.time(), Instant.now()).toSeconds() + " s old (catching up)");
                continue;
            }
            switch (intent.action()) {
                case ENTER -> {
                    var contract = contracts.find(underlying, snapshot.options().atmStrike(), intent.side());
                    if (contract.isEmpty()) {
                        rejected(underlying, "ENTER " + intent.side(), "no quotes for the ATM contract yet");
                        continue;
                    }
                    RiskDecision result = oms.enter(underlying, intent.side(), contract.get(), intent.lots(),
                            intent.stage().name(), market, intent.premiumStopPct());
                    logDecision(underlying, intent, result, contract.get());
                }
                case ADD -> logDecision(underlying, intent,
                        oms.add(underlying, intent.lots(), intent.stage().name(), market), null);
                case EXIT -> {
                    oms.exit(underlying, intent.reason());
                    log.info("{} {} EXIT {} ({})", time, underlying, intent.side(), intent.reason());
                }
            }
        }
    }

    private void logDecision(String underlying, OrderIntent intent, RiskDecision result, Contract contract) {
        log.info("{} {} {} {} {} lots {}{}", clock.get().atZone(MarketTime.IST).toLocalTime().withNano(0), underlying,
                intent.action(), intent.side(), intent.lots(), result.approved() ? "APPROVED" : "REJECTED: " + result.reason(),
                contract == null ? "" : " " + contract.symbol());
    }

    private void reconcileIfDue() {
        Instant now = clock.get();
        if (lastReconcile == null || Duration.between(lastReconcile, now).toSeconds() >= settings.risk().reconcileEverySec()) {
            lastReconcile = now;
            oms.reconcile();
        }
    }

    private void rejected(String underlying, String intent, String reason) {
        store.rejection(sessionId, underlying, intent, reason, clock.get());
        synchronized (recentRejections) {
            recentRejections.add(clock.get().atZone(MarketTime.IST).toLocalTime().withNano(0) + " " + underlying + " "
                    + intent + ": " + reason);
            if (recentRejections.size() > 20) {
                recentRejections.removeFirst();
            }
        }
    }

    // ---------------------------------------------------------------- operator actions

    public void engageKillSwitch(String scope, String reason) {
        killSwitch.engage(scope, reason, clock.get(), "operator");
    }

    public void releaseKillSwitch(String scope) {
        killSwitch.release(scope);
    }

    public void exitAll(String reason) {
        synchronized (lock) {
            if (oms != null) {
                oms.exitAll(reason);
            }
        }
    }

    public long sessionId() {
        return sessionId;
    }

    public List<Map<String, Object>> orders() {
        return store.orders(sessionId);
    }

    public Map<String, Object> status() {
        synchronized (lock) {
            Map<String, Object> status = new LinkedHashMap<>();
            if (oms == null) {
                status.put("status", this.status);
                return status;
            }
            status.put("session", sessionId);
            status.put("status", this.status);
            status.put("mode", settings.mode());
            status.put("date", settings.session().toString());
            status.put("account", settings.account());
            status.put("feed", feed.name());
            status.put("events", events.get());
            Instant last = feed.lastEventTime();
            status.put("lastEvent", last == null ? null : last.atZone(MarketTime.IST).toLocalTime().toString());
            status.put("feedLagSeconds", last == null || settings.mode() == Mode.PAPER_REPLAY ? null
                    : Duration.between(last, Instant.now()).toMillis() / 1000.0);
            status.put("dayPnl", Math.round(oms.dayPnl()));
            status.put("killSwitches", killSwitch.engaged().keySet());
            List<Map<String, Object>> positions = new ArrayList<>();
            for (ManagedPosition position : oms.livePositions()) {
                positions.add(position(position));
            }
            status.put("openPositions", positions);
            List<Map<String, Object>> closed = new ArrayList<>();
            for (ManagedPosition position : oms.closedPositions()) {
                closed.add(position(position));
            }
            status.put("closedPositions", closed);
            Map<String, Object> stages = new LinkedHashMap<>();
            lastDecisions.forEach((underlying, decision) -> stages.put(underlying, Map.of(
                    "time", decision.time().atZone(MarketTime.IST).toLocalTime().toString(),
                    "spot", lastSpot.getOrDefault(underlying, Double.NaN),
                    "CE", sideSummary(decision.ce()),
                    "PE", sideSummary(decision.pe()),
                    "state", decision.state())));
            status.put("strategy", stages);
            synchronized (recentRejections) {
                status.put("recentRejections", List.copyOf(recentRejections));
            }
            return status;
        }
    }

    private static String sideSummary(com.autotrade.strategy.SideView view) {
        return view.stage() + " early " + Math.round(view.earlyScore()) + " confirm " + Math.round(view.confirmScore())
                + " runner " + Math.round(view.runnerScore())
                + (Double.isNaN(view.casScore()) ? "" : " cas " + Math.round(view.casScore()));
    }

    private static Map<String, Object> position(ManagedPosition p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("underlying", p.underlying());
        m.put("side", p.side());
        m.put("symbol", p.contract().symbol());
        m.put("state", p.state());
        m.put("quantity", p.quantity());
        m.put("averageCost", Math.round(p.averageCost() * 100) / 100.0);
        m.put("stages", p.stages());
        m.put("net", Math.round(p.net()));
        m.put("exitReason", p.exitReason());
        m.put("opened", p.openedAt() == null ? null : p.openedAt().atZone(MarketTime.IST).toLocalTime().withNano(0).toString());
        m.put("closed", p.closedAt() == null ? null : p.closedAt().atZone(MarketTime.IST).toLocalTime().withNano(0).toString());
        return m;
    }

    private Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<ManagedPosition> closed = oms.closedPositions();
        summary.put("events", events.get());
        summary.put("positions", closed.size());
        summary.put("wins", closed.stream().filter(p -> p.net() > 0).count());
        summary.put("net", Math.round(oms.dayPnl()));
        summary.put("costs", Math.round(closed.stream().mapToDouble(ManagedPosition::costs).sum()));
        summary.put("stillOpen", oms.livePositions().size());
        summary.put("killSwitches", killSwitch.engaged().keySet());
        summary.put("reconciled", oms.reconcile());
        return summary;
    }

    /** Persists OMS activity and keeps the operator-facing rejection list. */
    private final class StoreListener implements OmsListener {
        @Override
        public void orderSent(ManagedPosition position, String role, OrderRequest request) {
            store.order(sessionId, position, role, request, clock.get());
        }

        @Override
        public void orderUpdated(ManagedPosition position, String role, OrderUpdate update) {
            store.orderEvent(update);
            if (update.isFill()) {
                log.info("{} {} {} {} filled {} @ {} ({})", update.time().atZone(MarketTime.IST).toLocalTime().withNano(0),
                        position.underlying(), role, position.contract().symbol(), update.lastFillQuantity(),
                        update.lastFillPrice(), update.status());
            }
        }

        @Override
        public void rejected(String underlying, String intent, String reason) {
            TradingSession.this.rejected(underlying, intent, reason);
        }

        @Override
        public void closed(ManagedPosition position) {
            store.position(sessionId, position);
            log.info("{} {} {} closed: {} net ₹{}", position.closedAt().atZone(MarketTime.IST).toLocalTime().withNano(0),
                    position.underlying(), position.contract().symbol(), position.exitReason(), Math.round(position.net()));
        }

        @Override
        public void alert(String message) {
            log.error("ALERT {}", message);
        }
    }
}
