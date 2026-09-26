package com.autotrade.research;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionEventSource;
import com.autotrade.core.history.ReferenceData;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.features.FeatureEngine;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.FillModel;
import com.autotrade.sim.OptionPositionSimulator;
import com.autotrade.sim.Side;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/**
 * Replays one session: events → feature engine → strategy (once per snapshot) → simulated option
 * orders on the replayed quotes. Each fill model is a separate lane with its own strategy instance,
 * because a stop that fills in one lane changes that lane's later decisions.
 *
 * <p>Per event: the option book learns contracts; the feature engine emits any snapshots due
 * (strictly before the event), the strategies decide and submit orders; only then do the
 * simulators see the event, so an order decided at T can only fill on quotes after T + latency.
 */
public final class LifecycleReplay {

    public record Settings(FeatureConfig features, SessionHistory history, StrategyFactory strategies,
                           List<FillModel> fillModels, CostModel costs, double premiumStopPct) {
    }

    public record SessionResult(LocalDate session, long events, List<Episode> episodes) {
    }

    private final Settings settings;

    public LifecycleReplay(Settings settings) {
        this.settings = settings;
    }

    public SessionResult run(SessionEventSource source, LocalDate session, List<String> underlyings, FrameSink frames)
            throws Exception {
        Map<String, OptionBook> books = new HashMap<>();
        List<Lane> lanes = new ArrayList<>();
        for (FillModel model : settings.fillModels()) {
            lanes.add(new Lane(model, session, underlyings));
        }
        Map<String, FeatureEngine> engines = new LinkedHashMap<>();
        for (String underlying : underlyings) {
            books.put(underlying, new OptionBook(session));
            engines.put(underlying, new FeatureEngine(underlying, session, settings.features(), settings.history(),
                    snapshot -> {
                        for (Lane lane : lanes) {
                            lane.onSnapshot(snapshot, books.get(snapshot.underlying()), frames);
                        }
                    }));
        }
        SessionEventSource.ReplayResult result = source.replay(session, underlyings, event -> {
            if (event instanceof OptionTick tick) {
                OptionBook underlyingBook = books.get(tick.underlying());
                if (underlyingBook != null) {
                    underlyingBook.accept(tick);
                }
            }
            if (ReferenceData.INDIA_VIX.equals(event.underlying())) {
                engines.values().forEach(engine -> engine.accept(event));
            } else {
                FeatureEngine engine = engines.get(event.underlying());
                if (engine != null) {
                    engine.accept(event);
                }
            }
            for (Lane lane : lanes) {
                lane.onEvent(event);
            }
        });
        engines.values().forEach(FeatureEngine::finish);
        List<Episode> episodes = new ArrayList<>();
        for (Lane lane : lanes) {
            lane.closeOut();
            episodes.addAll(lane.episodes);
        }
        return new SessionResult(session, result.delivered(), episodes);
    }

    /** One fill model's view of the session: strategies, open positions, finished episodes. */
    private final class Lane {

        private final FillModel model;
        private final LocalDate session;
        private final Map<String, Strategy> strategies = new HashMap<>();
        private final Map<String, Campaign> open = new HashMap<>();
        private final List<Episode> episodes = new ArrayList<>();

        Lane(FillModel model, LocalDate session, List<String> underlyings) {
            this.model = model;
            this.session = session;
            for (String underlying : underlyings) {
                strategies.put(underlying, settings.strategies().create(underlying, session));
            }
        }

        void onSnapshot(FeatureSnapshot snapshot, OptionBook book, FrameSink frames) {
            String underlying = snapshot.underlying();
            Campaign campaign = open.get(underlying);
            PositionView view = campaign == null || !campaign.simulator.isOpen() ? PositionView.FLAT
                    : new PositionView(true, campaign.side, (int) (campaign.simulator.quantity() / campaign.lotSize),
                    campaign.simulator.averageCost(), campaign.openedAt);
            Decision decision = strategies.get(underlying).decide(snapshot, view);
            frames.frame(model.name(), snapshot, decision);
            for (OrderIntent order : decision.orders()) {
                switch (order.action()) {
                    case ENTER -> enter(snapshot, book, order);
                    case ADD -> {
                        if (campaign != null) {
                            campaign.simulator.buy(snapshot.time(), (long) order.lots() * campaign.lotSize,
                                    order.stage().name());
                            campaign.stages.add(order.stage().name());
                        }
                    }
                    case EXIT -> {
                        if (campaign != null) {
                            campaign.simulator.exitAll(snapshot.time(), order.reason());
                        }
                    }
                }
            }
        }

        private void enter(FeatureSnapshot snapshot, OptionBook book, OrderIntent order) {
            if (open.containsKey(snapshot.underlying()) || Double.isNaN(snapshot.options().atmStrike())) {
                return;
            }
            OptionBook.Contract contract = book.find(order.strike(snapshot.options().atmStrike(),
                    snapshot.options().strikeStep()), order.side());
            if (contract == null) {
                return;
            }
            String exchange = snapshot.underlying().equals("SENSEX") ? "BSE" : "NSE";
            OptionPositionSimulator simulator = new OptionPositionSimulator(contract.token(), contract.symbol(),
                    exchange, session, model, settings.costs(), order.stopPctOr(settings.premiumStopPct()));
            simulator.buy(snapshot.time(), (long) order.lots() * contract.lotSize(), order.stage().name());
            Campaign campaign = new Campaign(snapshot.underlying(), order.side(), contract.lotSize(), simulator,
                    snapshot.time());
            campaign.stages.add(order.stage().name());
            open.put(snapshot.underlying(), campaign);
        }

        void onEvent(MarketEvent event) {
            Campaign campaign = open.get(event.underlying());
            if (campaign == null) {
                return;
            }
            campaign.simulator.accept(event);
            if (campaign.simulator.closed()) {
                episodes.add(campaign.toEpisode(model.name(), session));
                open.remove(event.underlying());
            }
        }

        /** Positions still open when the data ends are marked to the last bid. */
        void closeOut() {
            for (Campaign campaign : open.values()) {
                episodes.add(campaign.toEpisode(model.name(), session));
            }
            open.clear();
        }
    }

    private static final class Campaign {
        final String underlying;
        final OptionSide side;
        final int lotSize;
        final OptionPositionSimulator simulator;
        final Instant openedAt;
        final List<String> stages = new ArrayList<>();

        Campaign(String underlying, OptionSide side, int lotSize, OptionPositionSimulator simulator, Instant openedAt) {
            this.underlying = underlying;
            this.side = side;
            this.lotSize = lotSize;
            this.simulator = simulator;
            this.openedAt = openedAt;
        }

        Episode toEpisode(String lane, LocalDate session) {
            List<OptionPositionSimulator.Leg> legs = simulator.legs();
            long maxQuantity = 0;
            long held = 0;
            double firstPrice = Double.NaN;
            long firstQuantity = 0;
            int tranches = 0;
            for (OptionPositionSimulator.Leg leg : legs) {
                if (leg.side() == Side.BUY) {
                    held += leg.fill().quantity();
                    tranches++;
                    if (Double.isNaN(firstPrice)) {
                        firstPrice = leg.fill().price();
                        firstQuantity = leg.fill().quantity();
                    }
                } else {
                    held = 0;
                }
                maxQuantity = Math.max(maxQuantity, held);
            }
            double gross = simulator.realised() + simulator.unrealised();
            double net = gross - simulator.costs();
            double risk = Double.isNaN(firstPrice) ? Double.NaN
                    : firstPrice * firstQuantity * simulator.stopFraction();
            String exitReason = simulator.closed() ? simulator.exitReason() : "OPEN_AT_END";
            Instant closedAt = legs.isEmpty() ? null : legs.getLast().fill().time();
            return new Episode(lane, session, underlying, side.name(), simulator.symbol(),
                    stages.isEmpty() ? null : stages.getFirst(), List.copyOf(stages),
                    legs.isEmpty() ? "NOT_FILLED" : exitReason, tranches, maxQuantity, simulator.averageCost(), gross,
                    simulator.costs(), net, risk, risk > 0 ? net / risk : Double.NaN,
                    simulator.maxFavourablePerUnit(), simulator.maxAdversePerUnit(), openedAt, closedAt, legs);
        }
    }
}
