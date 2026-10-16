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
    private SignalRecorder signals;                       // live execution L1: only on the signal-source session

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
            switch (engines.get(underlying).oiBaselineStatus()) {           // features v12 (A-090)
                case MISSING -> log.warn("{}: no OI baseline loaded (ref.oi_baseline empty); OI z-scores stay empty",
                        underlying);
                case MISMATCH -> log.warn("{}: the OI baseline was measured with other strikes, weights or window than "
                        + "this features file (or mixes definitions); OI z-scores stay empty - rebuild it with "
                        + "bin/build-oi-baseline.sql", underlying);
                default -> { }
            }
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
        if (signals != null) {
            signals.start(sessionId, settings.session());
        }
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

    /**
     * Live execution L1: makes this session a signal source whose approved entries and adds, and every position close,
     * are recorded as exec.signal_decision rows. Only the signal-source session has one; PAPER-1 never does.
     */
    void publishSignals(SignalRecorder recorder) {
        this.signals = recorder;
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
        if (intent.action() == OrderIntent.Action.ENTER || intent.action() == OrderIntent.Action.ENTER_STRADDLE) {
            String streak = losingStreakRefusal(strategyId, underlying);     // risk v13 (A-071)
            if (streak != null) {
                String what = intent.action() == OrderIntent.Action.ENTER ? "ENTER " + intent.side() : "ENTER STRADDLE";
                rejected(strategyId, underlying, what, streak);
                candidate(strategyId, underlying, snapshot, decision, intent, false, streak);
                log.info("{} {} {} {} REJECTED: {}", time, underlying, strategyId, what, streak);
                return;
            }
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
                // risk v17 (A-106): a larger budget when futures volume confirms (time-of-day RVOL at entry), using only
                // spare capital: never above 97 % of the free capital (the check prices at the ask plus the entry
                // buffer) and never below the normal budget, so an entry that fills the capital is exactly v12's
                double base = budget(slot.factory());
                double boost = risk.limits().rvolBoost(snapshot.futures().rvolTod());
                if (boost > 1) {
                    double free = risk.limits().capital() - oms.premiumInUse(null);
                    base = Math.max(base, Math.min(base * boost, 0.97 * free));
                }
                double budget = Math.min(base, risk.limits().premiumCapForStop(stopPct));
                // risk v6: the premium whose first stop (structure via delta, or premium) loses the risk per trade
                budget = Math.min(budget, risk.limits().premiumCapForRisk(stopPct, ask, oms.delta(contract.get()),
                        intent.stopPoints()));
                // risk v14 (A-084): on an expiry day the non-expiring index keeps the expiry reserve free
                double reserveRoom = expiryReserveRoom(underlying);
                boolean reserveBinds = reserveRoom < budget;
                budget = Math.min(budget, reserveRoom);
                if (budget < contract.get().lotSize() * ask) {        // an expiry-day budget already used
                    String reason = reserveBinds
                            ? String.format("expiry reserve (risk v14): %.0f left for non-expiring indices, one lot costs %.0f",
                                    Math.max(0, budget), contract.get().lotSize() * ask)
                            : String.format("expiry-day budget: %.0f left, one lot costs %.0f", budget,
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
                // risk v16 (A-101): refused by the daily loss limit, an A-grade entry releases the switch (once a day)
                if (!result.approved() && result.reason() != null && result.reason().contains("daily loss limit")
                        && autoReleaseDailyLoss(snapshot, intent.side(), strategyId, underlying, time)) {
                    result = oms.enter(strategyId, underlying, intent.side(), contract.get(), lots,
                            intent.stage().name(), market, stopPct);
                }
                logDecision(strategyId, underlying, intent, lots, result, contract.get());
                candidate(strategyId, underlying, snapshot, decision, intent, result.approved(), result.reason());
                if (signals != null && result.approved()) {
                    // the lots published are those the order book allows (risk v12 participation), the follower's ceiling
                    long cap = oms.participationCapQuantity(contract.get()) / contract.get().lotSize();
                    Map<String, Double> signalSizing = new HashMap<>();
                    signalSizing.put("strategy_lots", (double) intent.lots());
                    signalSizing.put("intended_lots", (double) slot.factory().intendedLots());
                    signalSizing.put("liquidity_lots", cap >= Long.MAX_VALUE / contract.get().lotSize() ? Double.NaN : cap);
                    signalSizing.put("rvol_tod", snapshot.futures().rvolTod());
                    signalSizing.put("stop_pct", stopPct);
                    signals.entered(clock.get(), strategyId, underlying, intent.side(), contract.get(), ask, budget,
                            (int) Math.min(lots, cap), intent.stage().name(), intent.reason(), null, signalSizing);
                }
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
                double straddleBudget = Math.min(intent.premiumBudget() * settings.risk().budgetScale(),   // risk v8 scale
                        risk.limits().premiumCapForStop(straddleStop));
                RiskDecision result = oms.enterStraddle(strategyId, underlying, call.get(), put.get(),
                        straddleBudget, intent.stage().name(), market, intent.targetPct(), straddleStop);
                log.info("{} {} {} ENTER STRADDLE {} + {} budget {} {}", time, underlying, strategyId,
                        call.get().symbol(), put.get().symbol(), Math.round(straddleBudget),
                        result.approved() ? "APPROVED" : "REJECTED: " + result.reason());
                candidate(strategyId, underlying, snapshot, decision, intent, result.approved(), result.reason());
                if (signals != null && result.approved()) {
                    String group = strategyId + "@" + time;
                    Map<String, Double> legs = Map.of("call_ask", oms.ask(call.get()), "put_ask", oms.ask(put.get()),
                            "stop_pct", straddleStop);
                    signals.entered(clock.get(), strategyId, underlying, OptionSide.CE, call.get(), oms.ask(call.get()),
                            straddleBudget, 0, intent.stage().name(), intent.reason(), group, legs);
                    signals.entered(clock.get(), strategyId, underlying, OptionSide.PE, put.get(), oms.ask(put.get()),
                            straddleBudget, 0, intent.stage().name(), intent.reason(), group, legs);
                }
            }
            case ADD -> {
                int lots = sizings.getOrDefault(strategyId + "|" + underlying, NO_BUDGET).lots(intent.lots());
                if (slot.factory().pyramidRiskCap()) {
                    oms.capRiskAtFirstEntry(strategyId, underlying);   // the adds never raise the loss at the stop
                }
                RiskDecision result = oms.add(strategyId, underlying, lots, intent.stage().name(), market);
                logDecision(strategyId, underlying, intent, lots, result, null);
                candidate(strategyId, underlying, snapshot, decision, intent, result.approved(), result.reason());
                if (signals != null && result.approved()) {
                    oms.livePositions().stream()
                            .filter(p -> p.strategy().equals(strategyId) && p.underlying().equals(underlying))
                            .findFirst().ifPresent(p -> signals.added(clock.get(), strategyId, underlying, p.side(),
                                    p.contract(), oms.ask(p.contract()), lots, intent.stage().name(), intent.reason(),
                                    Map.of("strategy_lots", (double) intent.lots())));
                }
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
        double scale = settings.risk().budgetScale();               // risk v8: budgets follow the equity
        double expiryBudget = factory.expiryDayPremiumBudget() * scale;
        boolean expiryDay = settings.tradeUnderlyings().stream().anyMatch(contracts::expiresToday);
        if (!expiryDay || Double.isNaN(expiryBudget)) {
            return factory.premiumBudget() * scale;
        }
        return expiryBudget - oms.premiumInUse(factory.id());
    }

    /**
     * Risk v14 (A-084): how much premium an entry on {@code underlying} may still buy under the expiry reserve: on a day
     * when a traded underlying expires and this one does not, the non-expiring underlyings together may hold at most
     * {@link RiskLimits#nonExpiringPremiumCap()}; infinite otherwise (and with the reserve off).
     */
    private double expiryReserveRoom(String underlying) {
        double cap = risk.limits().nonExpiringPremiumCap();
        if (!Double.isFinite(cap) || contracts.expiresToday(underlying)
                || settings.tradeUnderlyings().stream().noneMatch(contracts::expiresToday)) {
            return Double.POSITIVE_INFINITY;
        }
        return cap - oms.premiumInUseOn(u -> !contracts.expiresToday(u));
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
                    approved ? null : refusal, snapshot.spot(), CandidateContext.of(snapshot, side, lastSnapshots));
        } catch (RuntimeException e) {
            log.warn("candidate not recorded ({} {} {}): {}", strategyId, underlying, intent.action(), e.getMessage());
        }
    }

    /**
     * Risk v13 (A-071): the refusal when this strategy's last {@code max_consecutive_losses} closed trades on this
     * underlying today all lost (a straddle's legs, opened together, count as one trade); null when entries may go on.
     */
    private String losingStreakRefusal(String strategyId, String underlying) {
        int max = risk.limits().maxConsecutiveLosses();
        if (max <= 0) {
            return null;
        }
        Map<Instant, Double> tradeNet = new java.util.TreeMap<>();
        for (ManagedPosition p : oms.closedPositions()) {
            if (strategyId.equals(p.strategy()) && underlying.equals(p.underlying()) && p.openedAt() != null) {
                tradeNet.merge(p.openedAt(), p.net(), Double::sum);
            }
        }
        int streak = 0;
        List<Double> nets = new ArrayList<>(tradeNet.values());
        for (int i = nets.size() - 1; i >= 0 && nets.get(i) < 0; i--) {
            streak++;
        }
        return streak >= max ? max + " losing trades in a row on " + underlying + " today (risk v13): no new entries" : null;
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

    /**
     * A-grade (A-100's definition, fixed from the 12 Oct evaluation of 118 trades): ATM IV percentile at least 50 or ATM
     * IV rising over 3 minutes, and mover breadth at least +50 for a call (at most -50 for a put). Unknown fails.
     */
    static boolean aGrade(FeatureSnapshot s, OptionSide side) {
        double pct = s.volatility().atmIvPercentile(), change = s.options().atmIvChange3m(), b = s.breadth().moverBreadth();
        boolean iv = pct >= 50 || change > 0;
        boolean breadth = side == OptionSide.CE ? b >= 50 : b <= -50;
        return iv && breadth;
    }

    /**
     * risk v16 (A-101): the application releases the daily-loss switch itself for an A-grade entry, once a day, raising
     * the day's limit by 10 % of equity; only when that raise leaves room for the day's loss so far.
     */
    private boolean autoReleaseDailyLoss(FeatureSnapshot snapshot, OptionSide side, String strategyId, String underlying,
                                         LocalTime time) {
        if (!settings.risk().dailyLossAutoRelease() || risk.dailyLossReleasesLeft() <= 0 || !aGrade(snapshot, side)
                || !(oms.dayPnl() > -risk.dailyLossLimitAfterRelease())) {
            return false;
        }
        String scope = "ACCOUNT:" + settings.account();
        if (!risk.releaseDailyLoss()) {
            return false;
        }
        killSwitch.release(scope);
        log.warn("{} {} {} {}: A-grade entry, daily-loss kill switch RELEASED by the application (day {}, limit now {}, "
                        + "releases left {})", time, underlying, strategyId, side, Math.round(oms.dayPnl()),
                Math.round(risk.dailyLossLimit()), risk.dailyLossReleasesLeft());
        return true;
    }

    /** What an operator release did: the scopes released, and why a scope was kept (null when none was). */
    public record Release(List<String> released, String refused) {
    }

    /**
     * Releases {@code scope}, or every engaged switch for "ALL". The daily-loss switch (ACCOUNT scope engaged by risk)
     * goes through the risk engine: with risk v16 one release a day, raising the day's loss limit by 10 % of equity;
     * once used, it stays engaged (A-101).
     */
    public Release releaseKillSwitch(String scope) {
        List<String> scopes = "ALL".equals(scope) ? List.copyOf(killSwitch.engaged().keySet()) : List.of(scope);
        List<String> released = new ArrayList<>();
        String refused = null;
        for (String s : scopes) {
            KillSwitch.Engagement e = killSwitch.engaged().get(s);
            if (e == null) {
                continue;
            }
            boolean dailyLoss = s.equals("ACCOUNT:" + settings.account()) && "risk".equals(e.by())
                    && e.reason() != null && e.reason().startsWith("daily loss limit");
            if (dailyLoss && !risk.releaseDailyLoss()) {
                refused = "the daily-loss kill switch was already released once today (limit now "
                        + Math.round(risk.dailyLossLimit()) + "); it stays engaged";
                continue;
            }
            if (killSwitch.release(s)) {
                released.add(s);
                if (dailyLoss) {
                    log.warn("daily-loss kill switch released by the operator: limit now {}, releases left {}",
                            Math.round(risk.dailyLossLimit()), risk.dailyLossReleasesLeft());
                }
            }
        }
        return new Release(List.copyOf(released), refused);
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
            status.put("capital", Math.round(settings.risk().capital()));
            status.put("startingCapital", Math.round(settings.risk().startingCapital()));
            status.put("dailyLossLimit", Math.round(risk.dailyLossLimit()));          // v16: raised by an operator release
            status.put("dailyLossReleasesLeft", risk.dailyLossReleasesLeft());
            status.put("dailyLossReleaseRaisePct", settings.risk().dailyLossReleaseRaisePct());
            status.put("dailyLossAutoRelease", settings.risk().dailyLossAutoRelease());
            status.put("budgetScale", Math.round(settings.risk().budgetScale() * 100) / 100.0);
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
            if (signals != null) {
                signals.closed(position);
            }
            log.info("{} {} {} closed: {} net ₹{}", position.closedAt().atZone(MarketTime.IST).toLocalTime().withNano(0),
                    position.underlying(), position.contract().symbol(), position.exitReason(), Math.round(position.net()));
        }

        @Override
        public void alert(String message) {
            log.error("ALERT {}", message);
        }
    }
}
