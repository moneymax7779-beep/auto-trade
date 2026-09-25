package com.autotrade.features;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;

import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionPhaseEvent;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.breadth.BreadthState;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.futures.FuturesState;
import com.autotrade.features.futures.VolumeProfile;
import com.autotrade.features.levels.StructureState;
import com.autotrade.features.options.OptionChainState;
import com.autotrade.features.snapshot.BreadthFeatures;
import com.autotrade.features.snapshot.CasFeatures;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.FuturesFeatures;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.snapshot.RegimeFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.features.time.SessionClock;
import com.autotrade.features.time.SessionPhase;

/**
 * Computes feature snapshots for one underlying and session from a replayed or live event stream.
 *
 * <p>Point-in-time rule: the snapshot at time T is taken before any event received at or after T is
 * applied, so it only reflects what was known before T. Snapshots are emitted on a fixed grid from
 * the session open until the derivatives close.
 */
public final class FeatureEngine implements Consumer<MarketEvent> {

    private final String underlying;
    private final LocalDate session;
    private final FeatureConfig config;
    private final SessionClock clock;
    private final Consumer<FeatureSnapshot> sink;
    private final StructureState structure;
    private final FuturesState futures;
    private final OptionChainState options;
    private final BreadthState breadth;
    private final Duration interval;
    private final Instant lastSnapshotAt;

    private Instant nextSnapshotAt;
    private double spot = Double.NaN;
    private Instant spotTime;
    private double lastContinuousSpot = Double.NaN;
    private double casIndicative = Double.NaN;
    private String casFeedPhase;

    public FeatureEngine(String underlying, LocalDate session, FeatureConfig config, SessionHistory history,
                         Consumer<FeatureSnapshot> sink) {
        this.underlying = underlying;
        this.session = session;
        this.config = config;
        this.clock = new SessionClock(config);
        this.sink = sink;
        Instant open = clock.sessionOpen(session);
        this.structure = new StructureState(config, open, history.previousSession(underlying, session,
                config.pdhPdlUntil()));
        this.futures = new FuturesState(config, underlying, session, open, new VolumeProfile(
                history.futuresMinuteVolumes(underlying, session, config.rvolLookbackSessions())));
        this.options = new OptionChainState(config, clock, session);
        this.breadth = new BreadthState(config);
        this.interval = Duration.ofSeconds(config.snapshotIntervalSec());
        this.nextSnapshotAt = open.plus(interval);
        this.lastSnapshotAt = session.atTime(config.derivativesClose())
                .atZone(MarketTime.IST).toInstant();
    }

    @Override
    public void accept(MarketEvent event) {
        if (!event.underlying().equals(underlying)) {
            return;
        }
        emitUntil(event.receivedAt());
        switch (event) {
            case IndexTick tick -> onIndex(tick);
            case FutureTick tick -> futures.onFuture(tick, spot);
            case OptionTick tick -> options.onOption(tick);
            case ConstituentTick tick -> breadth.onConstituent(tick);
            case SessionPhaseEvent phase -> {
                casFeedPhase = phase.sessionPhase();
                if (phase.sessionPhase().startsWith("CAS")) {
                    casIndicative = phase.price();
                }
            }
        }
    }

    /** Emits the remaining snapshots up to the derivatives close (call after the stream ends). */
    public void finish() {
        emitUntil(lastSnapshotAt);
    }

    private void onIndex(IndexTick tick) {
        spot = tick.price();
        spotTime = tick.receivedAt();
        SessionPhase phase = clock.phase(tick.receivedAt());
        if (phase.isContinuous()) {
            lastContinuousSpot = tick.price();
            structure.onSpot(tick.receivedAt(), tick.price(), tick.previousClose());
        } else if (phase.isCas()) {
            casIndicative = tick.price();
        }
    }

    /**
     * Emits every grid snapshot at or before {@code time}. Called with an event's receipt time before
     * the event is applied, so a snapshot at T never includes an event received at T or later.
     */
    private void emitUntil(Instant time) {
        while (!nextSnapshotAt.isAfter(lastSnapshotAt) && !nextSnapshotAt.isAfter(time)) {
            sink.accept(snapshot(nextSnapshotAt));
            nextSnapshotAt = nextSnapshotAt.plus(interval);
        }
    }

    /** The snapshot at {@code time} from the current state (no event at or after {@code time} applied yet). */
    public FeatureSnapshot snapshot(Instant time) {
        structure.advanceTo(time);
        SessionPhase phase = clock.phase(time);
        double reference = Double.isNaN(lastContinuousSpot) ? spot : lastContinuousSpot;
        double vwapProxy = futures.vwap() - futures.latestBasis();
        StructureFeatures structureFeatures = structure.snapshot(time, vwapProxy);
        FuturesFeatures futuresFeatures = futures.snapshot(time, structure.atr3m());
        OptionsFeatures optionsFeatures = options.snapshot(time, reference, structure.atr1m(), structure.spotSeries());
        BreadthFeatures breadthFeatures = breadth.snapshot(time);
        return new FeatureSnapshot(
                time, underlying, session, phase.name(), reference,
                secondsSince(time, spotTime), secondsSince(time, futures.lastTime()),
                secondsSince(time, options.lastTime()),
                structureFeatures, futuresFeatures, optionsFeatures, breadthFeatures,
                regime(time, reference, optionsFeatures),
                new CasFeatures(casFeedPhase, phase.isCas() || phase == SessionPhase.DERIVATIVES_ONLY
                        ? casIndicative : Double.NaN,
                        phase.isCas() ? casIndicative - lastContinuousSpot : Double.NaN,
                        phase.isCas() ? futures.latestPrice() - casIndicative : Double.NaN),
                config.featuresHash(), config.exchangeHash());
    }

    private RegimeFeatures regime(Instant time, double reference, OptionsFeatures chain) {
        LocalDate expiry = options.expiry();
        if (expiry == null) {
            return new RegimeFeatures(null, -1, -1, -1, Double.NaN, Double.NaN, Double.NaN, "NONE", Double.NaN);
        }
        int dte = clock.tradingDaysToExpiry(session, expiry);
        double dayRange = structure.dayRange();
        ExpectedMove move = expectedMove(time, reference, chain, expiry, dte);
        return new RegimeFeatures(expiry.toString(), dte, (int) ChronoUnit.DAYS.between(session, expiry),
                clock.minutesToExpiryClose(time, expiry), move.toExpiry, move.daily, move.remaining, move.method,
                move.daily > 0 ? dayRange / move.daily : Double.NaN);
    }

    private record ExpectedMove(double toExpiry, double daily, double remaining, String method) {
    }

    /**
     * STRADDLE: the ATM straddle's time value prices the expected absolute move to expiry, which is
     * sqrt(2/pi) of one standard deviation; that move is spread over the trading minutes left to
     * the expiry close (today's remaining minutes plus a full day per trading day in between).
     * IV_TRADING_MINUTES reproduces features v1 (calendar-year IV applied per trading minute).
     */
    private ExpectedMove expectedMove(Instant time, double reference, OptionsFeatures chain, LocalDate expiry,
                                      int dte) {
        double perDay = config.tradingMinutesPerDay();
        if ("IV_TRADING_MINUTES".equals(config.expectedMoveMethod())) {
            double daily = reference * chain.atmIv() / Math.sqrt(252);
            double remaining = reference * chain.atmIv()
                    * Math.sqrt(clock.continuousMinutesLeft(time) / (252 * perDay));
            return new ExpectedMove(Double.NaN, daily, remaining, "IV_TRADING_MINUTES");
        }
        double todayLeft = Math.min(perDay, Math.max(0, clock.minutesToExpiryClose(time, session)));
        double totalMinutes = todayLeft + perDay * dte;
        if (totalMinutes <= 0) {
            return new ExpectedMove(Double.NaN, Double.NaN, 0, "NONE");
        }
        double timeValue = chain.straddle() - Math.abs(reference - chain.atmStrike());
        double toExpiry;
        String method;
        if (timeValue > 0) {
            toExpiry = timeValue * Math.sqrt(Math.PI / 2);
            method = "STRADDLE";
        } else if (chain.atmIv() > 0) {
            toExpiry = reference * chain.atmIv() * Math.sqrt(clock.yearsToExpiry(time, expiry));
            method = "IV";
        } else {
            return new ExpectedMove(Double.NaN, Double.NaN, Double.NaN, "NONE");
        }
        return new ExpectedMove(toExpiry, toExpiry * Math.sqrt(Math.min(perDay, totalMinutes) / totalMinutes),
                toExpiry * Math.sqrt(todayLeft / totalMinutes), method);
    }

    private static double secondsSince(Instant now, Instant then) {
        return then == null ? Double.NaN : (now.toEpochMilli() - then.toEpochMilli()) / 1000.0;
    }
}
