package com.autotrade.features;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionPhaseEvent;
import com.autotrade.core.history.ReferenceData;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.breadth.BreadthState;
import com.autotrade.features.cas.CasState;
import com.autotrade.features.config.FeatureExtensions;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.futures.FuturesState;
import com.autotrade.features.futures.VolumeProfile;
import com.autotrade.features.levels.StructureState;
import com.autotrade.features.options.OptionChainState;
import com.autotrade.features.snapshot.BookFeatures;
import com.autotrade.features.snapshot.BreadthFeatures;
import com.autotrade.features.snapshot.CasFeatures;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.FuturesFeatures;
import com.autotrade.features.snapshot.LevelFeatures;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.snapshot.PremiumFeatures;
import com.autotrade.features.snapshot.RegimeFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.features.snapshot.VolatilityFeatures;
import com.autotrade.features.time.SessionClock;
import com.autotrade.features.time.SessionPhase;
import com.autotrade.features.volatility.VolatilityState;

/**
 * Computes feature snapshots for one underlying and session from a replayed or live event stream.
 *
 * <p>Point-in-time rule: the snapshot at time T is taken before any event received at or after T is
 * applied, so it only reflects what was known before T. Snapshots are emitted on a fixed grid from
 * the session open until the derivatives close.
 *
 * <p>India VIX ticks (underlying {@link ReferenceData#INDIA_VIX}) are accepted by every engine, since
 * VIX is market-wide context for each underlying.
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
    private final FeatureExtensions ext;
    private final VolatilityState volatility;
    private final CasState cas;
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
        this.ext = config.extensions();
        this.volatility = ext == null ? null : new VolatilityState(config, underlying, session, history);
        this.cas = ext == null ? null : new CasState(ext, session);
        this.interval = Duration.ofSeconds(config.snapshotIntervalSec());
        this.nextSnapshotAt = open.plus(interval);
        this.lastSnapshotAt = session.atTime(config.derivativesClose())
                .atZone(MarketTime.IST).toInstant();
    }

    @Override
    public void accept(MarketEvent event) {
        if (event instanceof IndexTick vix && ReferenceData.INDIA_VIX.equals(vix.underlying())) {
            emitUntil(vix.receivedAt());
            if (volatility != null) {
                volatility.onVixTick(vix.receivedAt(), vix.price());
            }
            return;
        }
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
                // zt-tiger-v2 carries the indicative index on its CAS phase events; Upstox status events
                // only repeat the last index value, which its index ticks already delivered.
                if (phase.sessionPhase().startsWith("CAS")) {
                    casIndicative = phase.price();
                }
                // v3: the indicative index keeps the index feed fresh during CAS (zt stops index ticks
                // at 15:15), and the post-auction value is kept too.
                if (cas != null && (phase.sessionPhase().startsWith("CAS")
                        || phase.sessionPhase().startsWith("DERIVATIVES_POST"))) {
                    casIndicative = phase.price();
                    spotTime = phase.receivedAt();
                    cas.onIndicative(phase.receivedAt(), phase.price());
                }
            }
            case AuctionTick auction -> {
                if (cas != null && clock.phase(auction.receivedAt()).isCas()) {
                    cas.onAuction(auction);
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
            if (cas != null) {
                cas.onContinuousSpot(tick.receivedAt(), tick.price());
            }
        } else if (phase.isCas()) {
            casIndicative = tick.price();
            if (cas != null) {
                cas.onIndicative(tick.receivedAt(), tick.price());
            }
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
        double vwapProxy = futures.vwap() - futures.latestBasis();
        structure.setVwapProxy(vwapProxy);
        structure.advanceTo(time);
        SessionPhase phase = clock.phase(time);
        double reference = Double.isNaN(lastContinuousSpot) ? spot : lastContinuousSpot;
        StructureFeatures structureFeatures = structure.snapshot(time, vwapProxy);
        FuturesFeatures futuresFeatures = futures.snapshot(time, structure.atr3m());
        OptionsFeatures optionsFeatures = options.snapshot(time, reference, structure.atr1m(), structure.spotSeries());
        BreadthFeatures breadthFeatures = breadth.snapshot(time);
        RegimeFeatures regimeFeatures = regime(time, reference, optionsFeatures);
        boolean showIndicative = phase.isCas() || phase == SessionPhase.DERIVATIVES_ONLY;
        CasFeatures casFeatures;
        LevelFeatures levels = LevelFeatures.EMPTY;
        BookFeatures book = BookFeatures.EMPTY;
        PremiumFeatures premium = PremiumFeatures.EMPTY;
        VolatilityFeatures vol = VolatilityFeatures.EMPTY;
        if (ext == null) {
            casFeatures = CasFeatures.basic(casFeedPhase, showIndicative ? casIndicative : Double.NaN,
                    phase.isCas() ? casIndicative - lastContinuousSpot : Double.NaN,
                    phase.isCas() ? futures.latestPrice() - casIndicative : Double.NaN);
        } else {
            casFeatures = cas.snapshot(time, casFeedPhase, phase.isCas(), showIndicative, lastContinuousSpot, futures,
                    structureFeatures.orHigh(), structureFeatures.orLow(), breadth.weights());
            levels = structure.levelFeatures(time, futures, ext.ladderWindowMin(), ext.breakoutVolumeAverageBars(),
                    regimeFeatures.expectedMoveRemaining());
            double[] fut = OptionChainState.book(futures.imbalance(), time, ext.bookPersistenceWindowsSec());
            OptionChainState.AtmBook atm = options.atmBook(time, reference, ext.bookPersistenceWindowsSec());
            book = new BookFeatures(fut[0], fut[1], fut[2], fut[3], fut[4], fut[5],
                    atm.ce()[0], atm.ce()[1], atm.ce()[2], atm.ce()[3], atm.ce()[4], atm.ce()[5],
                    atm.pe()[0], atm.pe()[1], atm.pe()[2], atm.pe()[3], atm.pe()[4], atm.pe()[5], atm.source());
            premium = options.premium(time, reference, ext);
            vol = volatility.snapshot(time, structure.oneMinuteBars(), structure.atr1m(), optionsFeatures.atmIv(),
                    optionsFeatures.atmIvChange5m(), session.equals(options.expiry()));
        }
        return new FeatureSnapshot(
                time, underlying, session, phase.name(), reference,
                secondsSince(time, spotTime), secondsSince(time, futures.lastTime()),
                secondsSince(time, options.lastTime()),
                structureFeatures, futuresFeatures, optionsFeatures, breadthFeatures, regimeFeatures, casFeatures,
                levels, book, premium, vol, config.featuresHash(), config.exchangeHash());
    }

    private RegimeFeatures regime(Instant time, double reference, OptionsFeatures chain) {
        LocalDate expiry = options.expiry();
        if (expiry == null) {
            return new RegimeFeatures(null, -1, -1, -1, Double.NaN, Double.NaN, Double.NaN, "NONE", Double.NaN,
                    nextSessionGapDays());
        }
        int dte = clock.tradingDaysToExpiry(session, expiry);
        double dayRange = structure.dayRange();
        ExpectedMove move = expectedMove(time, reference, chain, expiry, dte);
        return new RegimeFeatures(expiry.toString(), dte, (int) ChronoUnit.DAYS.between(session, expiry),
                clock.minutesToExpiryClose(time, expiry), move.toExpiry, move.daily, move.remaining, move.method,
                move.daily > 0 ? dayRange / move.daily : Double.NaN, nextSessionGapDays());
    }

    /** Non-trading calendar days between this session and the next one (2 over a normal weekend). */
    private int nextSessionGapDays() {
        LocalDate next = session.plusDays(1);
        int gap = 0;
        while (!clock.isTradingDay(next) && gap < 14) {
            gap++;
            next = next.plusDays(1);
        }
        return gap;
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
