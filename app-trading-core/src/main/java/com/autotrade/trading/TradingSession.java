package com.autotrade.trading;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.SideView;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/**
 * One PAPER trading session: feed → features → strategy → risk → OMS → paper broker, all on one
 * thread under one lock. In replay mode the clock follows event time; live, it is the wall clock
 * (so the paper broker also charges the feed's own delay as latency).
 */
public final class TradingSession {

    public enum Mode { PAPER_LIVE, PAPER_REPLAY }

    /**
     * {@code strategies}: every strategy traded in this session (each keeps its own positions).
     * {@code underlyings} are subscribed, recorded and get features; only {@code tradeUnderlyings}
     * (a subset; all of them when empty) are given strategies.
     */
    public record Settings(Mode mode, String account, LocalDate session, List<String> underlyings,
                           List<String> tradeUnderlyings, FeatureConfig features, SessionHistory history, List<StrategyFactory> strategies,
                           RiskLimits risk, CostModel costs, FillModel fills,
                           InstrumentMaster instruments, Map<String, String> configHashes, String codeVersion) {

        public Settings {
            if (strategies.isEmpty()) {
                throw new IllegalArgumentException("a trading session needs at least one strategy");
            }
            // Positions, OMS keys and decision rows are keyed by strategy id: two versions of one
            // strategy in the same session would share a position.
            Set<String> ids = new HashSet<>();
            for (StrategyFactory factory : strategies) {
                if (!ids.add(factory.id())) {
                    throw new IllegalArgumentException("strategy " + factory.id()
                            + " is configured twice; run one version per session");
                }
            }
            strategies = List.copyOf(strategies);
            tradeUnderlyings = tradeUnderlyings == null || tradeUnderlyings.isEmpty() ? List.copyOf(underlyings)
                    : List.copyOf(tradeUnderlyings);
            if (!underlyings.containsAll(tradeUnderlyings)) {
                throw new IllegalArgumentException("trade underlyings " + tradeUnderlyings + " must be among the "
                        + "recorded underlyings " + underlyings);
            }
        }
    }

    /** One strategy instance for one underlying, with the factory it came from. */
    private record Slot(StrategyFactory factory, Strategy strategy) {
    }

    private static final Logger log = LoggerFactory.getLogger(TradingSession.class);
    private static final Duration STALE_ALERT = Duration.ofSeconds(10);
    private static final tools.jackson.databind.json.JsonMapper SNAPSHOT_JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();
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
    private final Map<String, List<Slot>> strategies = new HashMap<>();
    /** Per strategy and underlying: the budget scale fixed at the position's entry (its adds use it too). */
    private final Map<String, BudgetSizing> sizings = new HashMap<>();
    private static final BudgetSizing NO_BUDGET = new BudgetSizing(Double.NaN);
    private final Map<String, Decision> lastDecisions = new LinkedHashMap<>();
    private final Map<String, Double> lastSpot = new HashMap<>();
    private final List<String> recentRejections = new ArrayList<>();
    private final AtomicLong events = new AtomicLong();
    private long sessionId;
    private Instant lastReconcile;
    private Instant lastStaleAlert;
    private boolean squaredOff;
    private volatile String status = "STARTING";
    private com.autotrade.md.store.SnapshotWriter snapshotWriter;
    private final Map<String, FeatureSnapshot> lastSnapshots = new LinkedHashMap<>();

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
            List<Slot> slots = new ArrayList<>();
            if (settings.tradeUnderlyings().contains(underlying)) {
                for (StrategyFactory factory : settings.strategies()) {
                    slots.add(new Slot(factory, factory.create(underlying, settings.session())));
                }
            }
            strategies.put(underlying, slots);  // empty: recorded with features, never traded
            if (slots.isEmpty()) {
                log.info("{}: recorded with features, not traded (not in trade-underlyings)", underlying);
            }
            engines.put(underlying, new FeatureEngine(underlying, settings.session(), settings.features(),
                    settings.history(), this::onSnapshot));
        }
    }

    /** Runs the session until the feed ends (replay) or {@link #stop()} (live). */
    public Map<String, Object> run() {
        String ids = String.join("+", settings.strategies().stream().map(StrategyFactory::id).toList());
        String hashes = String.join("+", settings.strategies().stream().map(StrategyFactory::configHash).toList());
        sessionId = store.startSession(settings.account(), settings.mode().name(), settings.session(), feed.name(),
                ids, hashes, settings.configHashes(), settings.codeVersion());
        // Order ids carry the session id so they never repeat across runs of the same day.
        broker = new PaperBroker(settings.account(), settings.fills().latency(), settings.fills().adverseBps(), clock);
        StrategyFactory primary = settings.strategies().getFirst();
        oms = new OrderManager(settings.account(), settings.account() + "-S" + sessionId, primary.id(),
                settings.session(), broker, risk, settings.costs(), primary.premiumStopPct(), clock,
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

    /** Saves every feature snapshot (the live decision evidence, and later IV history) through {@code writer}. */
    public void recordSnapshots(com.autotrade.md.store.SnapshotWriter writer) {
        this.snapshotWriter = writer;
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
        lastSpot.put(underlying, snapshot.spot());
        lastSnapshots.put(underlying, snapshot);
        if (snapshotWriter != null) {
            try {
                snapshotWriter.add(snapshot.session(), underlying, snapshot.time(), snapshot.phase(), snapshot.spot(),
                        SNAPSHOT_JSON.writeValueAsString(com.autotrade.features.snapshot.SnapshotFlattener.jsonSafe(
                                com.autotrade.features.snapshot.SnapshotFlattener.flatten(snapshot))));
            } catch (RuntimeException e) {
                log.warn("feature snapshot not saved: {}", e.getMessage());
            }
        }
        boolean squareOff = risk.squareOffDue(time);
        MarketContext market = new MarketContext(time, snapshot.secondsSinceSpot(), snapshot.secondsSinceOption());
        // Live: never open or add from a stale snapshot (e.g. while the feed catches up on the morning).
        boolean stale = settings.mode() == Mode.PAPER_LIVE
                && Duration.between(snapshot.time(), Instant.now()).compareTo(MAX_DECISION_AGE) > 0;
        for (Slot slot : strategies.get(underlying)) {
            String strategyId = slot.factory().id();
            Decision decision = slot.strategy().decide(snapshot, oms.view(strategyId, underlying));
            lastDecisions.put(decisionKey(underlying, strategyId), decision);
            store.decision(sessionId, strategyId, decision, snapshot.spot());
            if (squareOff) {
                continue;
            }
            for (OrderIntent intent : decision.orders()) {
                act(slot, underlying, snapshot, decision, intent, market, stale, time);
            }
        }
        if (squareOff && !oms.livePositions().isEmpty()) {
            oms.exitAll("SQUARE_OFF");
        }
    }

    /** One strategy's intent through risk and the OMS, in that strategy's name. */
    private void act(Slot slot, String underlying, FeatureSnapshot snapshot, Decision decision, OrderIntent intent,
                     MarketContext market, boolean stale, LocalTime time) {
        String strategyId = slot.factory().id();
        if (stale && intent.action() != OrderIntent.Action.EXIT) {
            String reason = "snapshot " + Duration.between(snapshot.time(), Instant.now()).toSeconds() + " s old (catching up)";
            rejected(strategyId, underlying, intent.action() + (intent.side() == null ? "" : " " + intent.side()), reason);
            candidate(strategyId, underlying, snapshot, decision, intent, false, reason);
            return;
        }
        switch (intent.action()) {
            case ENTER -> {
                var contract = contracts.find(underlying,
                        intent.strike(snapshot.options().atmStrike(), snapshot.options().strikeStep()), intent.side());
                if (contract.isEmpty()) {
                    rejected(strategyId, underlying, "ENTER " + intent.side(), "no quotes for the chosen strike yet");
                    candidate(strategyId, underlying, snapshot, decision, intent, false, "no quotes for the chosen strike yet");
                    return;
                }
                // with a premium budget the strategy's lots are units of its plan, scaled at this entry
                double stopPct = intent.stopPctOr(slot.factory().premiumStopPct());
                // risk v5: no more premium than the per-trade loss cap allows at this position's stop
                double ask = oms.ask(contract.get());
                double budget = Math.min(budget(slot.factory()), risk.limits().premiumCapForStop(stopPct));
                // risk v6: the premium whose first stop (structure via delta, or premium) loses the risk per trade
                budget = Math.min(budget, risk.limits().premiumCapForRisk(stopPct, ask, oms.delta(contract.get()),
                        intent.stopPoints()));
                if (budget < contract.get().lotSize() * ask) {        // an expiry-day budget already used
                    String reason = String.format("expiry-day budget: %.0f left, one lot costs %.0f", budget,
                            contract.get().lotSize() * ask);
                    rejected(strategyId, underlying, "ENTER " + intent.side(), reason);
                    candidate(strategyId, underlying, snapshot, decision, intent, false, reason);
                    return;
                }
                BudgetSizing sizing = BudgetSizing.at(budget, slot.factory().intendedLots(),
                        contract.get().lotSize(), ask);
                sizings.put(strategyId + "|" + underlying, sizing);
                int lots = sizing.lots(intent.lots());
                RiskDecision result = oms.enter(strategyId, underlying, intent.side(), contract.get(), lots,
                        intent.stage().name(), market, stopPct);
                logDecision(strategyId, underlying, intent, lots, result, contract.get());
                candidate(strategyId, underlying, snapshot, decision, intent, result.approved(), result.reason());
            }
            case ENTER_STRADDLE -> {
                double atm = snapshot.options().atmStrike();
                double step = snapshot.options().strikeStep();
                var call = contracts.find(underlying, intent.legStrike(OptionSide.CE, atm, step), OptionSide.CE);
                var put = contracts.find(underlying, intent.legStrike(OptionSide.PE, atm, step), OptionSide.PE);
                if (call.isEmpty() || put.isEmpty()) {
                    rejected(strategyId, underlying, "ENTER STRADDLE", "no quotes for the chosen strikes yet");
                    candidate(strategyId, underlying, snapshot, decision, intent, false, "no quotes for the chosen strikes yet");
                    return;
                }
                double straddleStop = intent.stopPctOr(slot.factory().premiumStopPct());
                // risk v5: the combined premium is capped so the combined stop loses at most the per-trade cap
                double straddleBudget = Math.min(intent.premiumBudget(), risk.limits().premiumCapForStop(straddleStop));
                RiskDecision result = oms.enterStraddle(strategyId, underlying, call.get(), put.get(),
                        straddleBudget, intent.stage().name(), market, intent.targetPct(), straddleStop);
                log.info("{} {} {} ENTER STRADDLE {} + {} budget {} {}", time, underlying, strategyId,
                        call.get().symbol(), put.get().symbol(), Math.round(straddleBudget),
                        result.approved() ? "APPROVED" : "REJECTED: " + result.reason());
                candidate(strategyId, underlying, snapshot, decision, intent, result.approved(), result.reason());
            }
            case ADD -> {
                int lots = sizings.getOrDefault(strategyId + "|" + underlying, NO_BUDGET).lots(intent.lots());
                if (slot.factory().pyramidRiskCap()) {
                    oms.capRiskAtFirstEntry(strategyId, underlying);   // the adds never raise the loss at the stop
                }
                RiskDecision result = oms.add(strategyId, underlying, lots, intent.stage().name(), market);
                logDecision(strategyId, underlying, intent, lots, result, null);
                candidate(strategyId, underlying, snapshot, decision, intent, result.approved(), result.reason());
            }
            case EXIT -> {
                oms.exit(strategyId, underlying, intent.reason());
                log.info("{} {} {} EXIT {} ({})", time, underlying, strategyId, intent.side(), intent.reason());
            }
        }
    }

    /**
     * A strategy's premium budget for this entry. When any traded underlying expires today (e.g. NIFTY on
     * a Tuesday also counts for a SENSEX entry, since the capital is shared) a strategy with an expiry-day
     * budget gets what is left of it across all underlyings, so the rest of the capital stays free.
     */
    private double budget(StrategyFactory factory) {
        double expiryBudget = factory.expiryDayPremiumBudget();
        boolean expiryDay = settings.tradeUnderlyings().stream().anyMatch(contracts::expiresToday);
        if (!expiryDay || Double.isNaN(expiryBudget)) {
            return factory.premiumBudget();
        }
        return expiryBudget - oms.premiumInUse(factory.id());
    }

    /** Status key: the underlying alone with one strategy, "underlying · strategy" with several. */
    private String decisionKey(String underlying, String strategyId) {
        return settings.strategies().size() == 1 ? underlying : underlying + " · " + strategyId;
    }

    private void logDecision(String strategyId, String underlying, OrderIntent intent, int lots, RiskDecision result,
                             Contract contract) {
        log.info("{} {} {} {} {} {} lots {}{}", clock.get().atZone(MarketTime.IST).toLocalTime().withNano(0), underlying,
                strategyId, intent.action(), intent.side(), lots, result.approved() ? "APPROVED" : "REJECTED: " + result.reason(),
                contract == null ? "" : " " + contract.symbol());
    }

    private void reconcileIfDue() {
        Instant now = clock.get();
        if (lastReconcile == null || Duration.between(lastReconcile, now).toSeconds() >= settings.risk().reconcileEverySec()) {
            lastReconcile = now;
            oms.reconcile();
        }
    }

    /** Records an entry or add request with its market context (never fails the decision). */
    private void candidate(String strategyId, String underlying, FeatureSnapshot snapshot, Decision decision,
                           OrderIntent intent, boolean approved, String refusal) {
        try {
            SideView side = intent.side() == null ? null : intent.side() == OptionSide.CE ? decision.ce() : decision.pe();
            store.candidate(sessionId, strategyId, underlying, snapshot.time(), intent.action().name(),
                    intent.side() == null ? null : intent.side().name(), intent.reason(), approved,
                    approved ? null : refusal, snapshot.spot(), CandidateContext.of(snapshot, side));
        } catch (RuntimeException e) {
            log.warn("candidate not recorded ({} {} {}): {}", strategyId, underlying, intent.action(), e.getMessage());
        }
    }

    private void rejected(String strategyId, String underlying, String intent, String reason) {
        store.rejection(sessionId, strategyId, underlying, intent, reason, clock.get());
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
            lastDecisions.forEach((key, decision) -> {
                String underlying = decision.underlying();
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("time", decision.time().atZone(MarketTime.IST).toLocalTime().toString());
                view.put("spot", lastSpot.getOrDefault(underlying, Double.NaN));
                view.put("CE", sideSummary(decision.ce()));
                view.put("PE", sideSummary(decision.pe()));
                view.put("state", decision.state());
                FeatureSnapshot snapshot = lastSnapshots.get(underlying);
                if (snapshot != null) {
                    view.put("volatility", volatilityPanel(snapshot));
                    view.put("cas", casPanel(snapshot));
                }
                stages.put(key, view);
            });
            status.put("strategy", stages);
            synchronized (recentRejections) {
                status.put("recentRejections", List.copyOf(recentRejections));
            }
            return status;
        }
    }

    /** The design's VOL REGIME panel: its inputs as the snapshot measured them (finite values only). */
    private static Map<String, Object> volatilityPanel(FeatureSnapshot s) {
        Map<String, Object> panel = new LinkedHashMap<>();
        put(panel, "dte", s.regime().dteTradingDays());
        put(panel, "minutesToExpiry", s.regime().minutesToExpiryClose());
        put(panel, "atmIvPct", s.options().atmIv() * 100);
        put(panel, "ivPercentile", s.volatility().atmIvPercentile());
        put(panel, "ivTrend", s.volatility().ivTrend());
        put(panel, "vix", s.volatility().vix());
        put(panel, "vixPercentile252d", s.volatility().vixPercentile252d());
        put(panel, "vixChange15m", s.volatility().vixChange15m());
        put(panel, "realizedVolPct", s.volatility().realizedVol() * 100);
        put(panel, "realizedVolPercentile", s.volatility().realizedVolPercentile());
        put(panel, "atrPercentile", s.volatility().atrPercentile());
        put(panel, "futuresRvol", s.futures().rvolTod());
        put(panel, "expectedMoveRemaining", s.regime().expectedMoveRemaining());
        put(panel, "event", s.regime().marketEvent());
        return panel;
    }

    /** The design's CAS panel (from 15:15). */
    private static Map<String, Object> casPanel(FeatureSnapshot s) {
        Map<String, Object> panel = new LinkedHashMap<>();
        put(panel, "phase", s.cas().feedPhase());
        put(panel, "indicative", s.cas().indicativeIndex());
        put(panel, "reference", s.cas().referenceIndex());
        put(panel, "iepPressurePct", s.cas().weightedIepReturnPct());
        put(panel, "imbalance", s.cas().weightedImbalance());
        put(panel, "breadthPct", s.cas().casBreadthPct());
        put(panel, "top3", s.cas().topConcentration());
        put(panel, "futuresVsIndicative", s.cas().futuresVsIndicative());
        put(panel, "liquidityPercentile", s.cas().auctionLiquidityPercentile());
        put(panel, "coveragePct", s.cas().constituentCoveragePct());
        return panel;
    }

    private static void put(Map<String, Object> panel, String key, Object value) {
        if (value instanceof Double d && !Double.isFinite(d)) {
            return;
        }
        if (value != null) {
            panel.put(key, value instanceof Double d ? Math.round(d * 100) / 100.0 : value);
        }
    }

    private static String sideSummary(com.autotrade.strategy.SideView view) {
        return view.stage() + " early " + Math.round(view.earlyScore()) + " confirm " + Math.round(view.confirmScore())
                + " runner " + Math.round(view.runnerScore())
                + (Double.isNaN(view.casScore()) ? "" : " cas " + Math.round(view.casScore()));
    }

    private Map<String, Object> position(ManagedPosition p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("underlying", p.underlying());
        m.put("strategy", p.strategy());
        m.put("leg", p.leg());
        m.put("side", p.side());
        m.put("symbol", p.contract().symbol());
        m.put("state", p.state());
        m.put("quantity", p.quantity());
        m.put("boughtQuantity", p.boughtQuantity());
        m.put("lotSize", p.contract().lotSize());
        m.put("averageCost", Math.round(p.averageCost() * 100) / 100.0);
        m.put("premiumPaid", Math.round(p.averageCost() * p.boughtQuantity()));
        double bid = p.lastBid();
        m.put("bid", Double.isFinite(bid) ? bid : null);
        m.put("value", p.quantity() > 0 && Double.isFinite(bid) ? Math.round(bid * p.quantity()) : null);
        m.put("unrealised", Math.round(p.unrealised()));
        m.put("realised", Math.round(p.realised()));
        m.put("costs", Math.round(p.costs()));
        // closed: the average exit from the realised P&L over everything bought
        m.put("exitPrice", p.quantity() == 0 && p.boughtQuantity() > 0
                ? Math.round((p.averageCost() + p.realised() / p.boughtQuantity()) * 100) / 100.0 : null);
        double[] bracket = p.leg().isEmpty() ? null : oms.bracket(p.strategy(), p.underlying());
        m.put("targetPct", bracket == null ? null : bracket[0]);
        m.put("stopPct", bracket == null ? null : bracket[1]);
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
        public void rejected(String strategyId, String underlying, String intent, String reason) {
            TradingSession.this.rejected(strategyId, underlying, intent, reason);
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
