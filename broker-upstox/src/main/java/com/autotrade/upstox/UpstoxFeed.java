package com.autotrade.upstox;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.md.live.LiveFeed;
import com.upstox.ApiClient;
import com.upstox.feeder.MarketDataStreamerV3;
import com.upstox.feeder.MarketUpdateV3;
import com.upstox.feeder.constants.Mode;

/**
 * Upstox Market Data Feed V3 as a {@link LiveFeed}, using one WebSocket connection. The SDK calls
 * back on its own thread; messages are queued with their receipt time and delivered on the thread
 * that runs {@link #run}. Instruments are subscribed in full mode (5-level depth, Greeks, OI, and
 * for stocks the closing-auction fields).
 */
public final class UpstoxFeed implements LiveFeed {

    private static final Logger log = LoggerFactory.getLogger(UpstoxFeed.class);

    private record Received(MarketUpdateV3 update, Instant at) {
    }

    private final UpstoxToken token;
    private final UpstoxUniverse universe;
    private final List<String> underlyings;
    private final int strikesEachSide;
    private final int recenterStrikes;
    /** The far expiries' band (next weekly and current monthly), recorded only; 0 = off. */
    private final int farStrikesEachSide;
    private volatile Consumer<OptionTick> farSink = t -> { };
    /** The far band is recorded for the traded indices only (user, 3 Nov 2026: NIFTY and SENSEX). */
    private static final Set<String> FAR_UNDERLYINGS = Set.of("NIFTY", "SENSEX");
    /**
     * Far contracts are added only while this feed's subscriptions stay at or below this. Upstox counts its limits per
     * user, not per connection (full mode 2,000 instruments, 1,500 when modes are combined; checked 3 Nov 2026), and
     * zt-tiger-v2 subscribes on the same login: the nearest expiry's band, which the strategies trade, and zt's own feed
     * must never be crowded out by recording.
     */
    static final int FAR_SUBSCRIPTION_CEILING = 800;
    private boolean farCeilingLogged;
    /** The far band follows the spot every this many strikes (the nearest band's {@code recenterStrikes} is wider). */
    static final int FAR_RECENTER_STRIKES = 2;
    private final Map<String, Double> farCentre = new HashMap<>();
    private boolean farOff;
    private final UpstoxEventMapper mapper;
    private final BlockingQueue<Received> queue = new LinkedBlockingQueue<>();
    private final Map<String, Double> bandCentre = new HashMap<>();
    private final Set<String> subscribed = new HashSet<>();
    private final AtomicLong messages = new AtomicLong();
    private volatile boolean stopped;
    private volatile String failure;
    private volatile Instant last;
    private volatile String connection = "NOT_CONNECTED";
    private MarketDataStreamerV3 streamer;

    public UpstoxFeed(UpstoxToken token, UpstoxUniverse universe, List<String> underlyings, int strikesEachSide,
                      int recenterStrikes) {
        this(token, universe, underlyings, strikesEachSide, recenterStrikes, 0);
    }

    /** @param farStrikesEachSide the far expiries' band (next weekly and current monthly), recorded only; 0 = off */
    public UpstoxFeed(UpstoxToken token, UpstoxUniverse universe, List<String> underlyings, int strikesEachSide,
                      int recenterStrikes, int farStrikesEachSide) {
        this.token = token;
        this.universe = universe;
        this.underlyings = underlyings;
        this.strikesEachSide = strikesEachSide;
        this.recenterStrikes = recenterStrikes;
        this.farStrikesEachSide = farStrikesEachSide;
        this.mapper = new UpstoxEventMapper(universe);
    }

    @Override
    public String name() {
        return "upstox-v3";
    }

    @Override
    public void run(Consumer<MarketEvent> sink) throws Exception {
        ApiClient client = new ApiClient();
        client.setAccessToken(token.accessToken());
        streamer = new MarketDataStreamerV3(client, universe.baseKeys(), Mode.FULL);
        subscribed.addAll(universe.baseKeys());
        streamer.setOnMarketUpdateListener(update -> {
            messages.incrementAndGet();
            queue.add(new Received(update, Instant.now()));
        });
        streamer.setOnOpenListener(() -> {
            connection = "OPEN";
            log.info("Upstox feed connected; {} base instruments", universe.baseKeys().size());
        });
        streamer.setOnCloseListener((code, reason) -> {
            connection = "CLOSED " + code;
            log.warn("Upstox feed closed: {} {}", code, reason);
        });
        streamer.setOnErrorListener(error -> log.error("Upstox feed error: {}", error.getMessage()));
        streamer.setOnReconnectingListener(message -> {
            connection = "RECONNECTING";
            log.warn("Upstox feed reconnecting: {}", message);
        });
        streamer.autoReconnect(true, 10, 5);
        streamer.connect();
        try {
            while (!stopped) {
                Received received = queue.poll(200, TimeUnit.MILLISECONDS);
                if (received == null) {
                    continue;
                }
                for (MarketEvent event : mapper.map(received.update(), received.at())) {
                    last = event.receivedAt();
                    route(event, universe, sink, farSink);
                }
                recentreOptions();
            }
        } finally {
            streamer.disconnect();
        }
        if (failure != null) {
            throw new IllegalStateException("Upstox feed failed: " + failure);
        }
    }

    /** A far-expiry option tick goes to {@code farSink} only (recorded, never seen by the engine); all else to {@code sink}. */
    static void route(MarketEvent event, UpstoxUniverse universe, Consumer<MarketEvent> sink, Consumer<OptionTick> farSink) {
        if (event instanceof OptionTick tick && universe.far(tick.instrumentToken())) {
            farSink.accept(tick);
        } else {
            sink.accept(event);
        }
    }

    /** Where far-expiry option ticks go (the capture); they never reach {@code run}'s sink. */
    public void onFarOption(Consumer<OptionTick> sink) {
        this.farSink = sink == null ? t -> { } : sink;
    }

    /** Stops the feed as a failure (the session ends FAILED, so the scheduler can fall back). */
    public void fail(String reason) {
        failure = reason;
        stopped = true;
    }

    /** Subscribes the option band once each index has a price, and again when it drifts too far. */
    private void recentreOptions() {
        for (String underlying : underlyings) {
            Double spot = mapper.lastIndex(underlying);
            if (spot == null) {
                continue;
            }
            double step = universe.strikeStep(underlying);
            Double centre = bandCentre.get(underlying);
            if (centre == null || Math.abs(spot - centre) >= recenterStrikes * step) {
                bandCentre.put(underlying, spot);
                Set<String> keys = new HashSet<>(universe.optionKeys(underlying, spot, strikesEachSide));
                keys.removeAll(subscribed);
                if (!keys.isEmpty()) {
                    streamer.subscribe(keys, Mode.FULL);
                    subscribed.addAll(keys);
                    log.info("{} option band centred at {}: +{} contracts ({} subscribed in total)", underlying, spot,
                            keys.size(), subscribed.size());
                }
            }
            if (FAR_UNDERLYINGS.contains(underlying) && farStrikesEachSide > 0 && !farOff) {
                recentreFar(underlying, spot, step);
            }
        }
    }

    /**
     * The far band: centred on its own (it is only {@code farStrikesEachSide} wide, so it follows the spot every
     * {@link #FAR_RECENTER_STRIKES} strikes) and subscribed in its own call after the nearest band's, so a refusal of the
     * recording never takes the traded band with it; a refused far subscription turns far recording off for the session.
     */
    private void recentreFar(String underlying, double spot, double step) {
        Double centre = farCentre.get(underlying);
        if (centre != null && Math.abs(spot - centre) < FAR_RECENTER_STRIKES * step) {
            return;
        }
        farCentre.put(underlying, spot);
        Set<String> far = new HashSet<>(universe.farOptionKeys(underlying, spot, farStrikesEachSide));
        far.removeAll(subscribed);
        if (far.isEmpty()) {
            return;
        }
        if (subscribed.size() + far.size() > FAR_SUBSCRIPTION_CEILING) {
            if (!farCeilingLogged) {
                farCeilingLogged = true;
                log.warn("{}: far-expiry contracts not added ({} subscribed, {} far; ceiling {})", underlying,
                        subscribed.size(), far.size(), FAR_SUBSCRIPTION_CEILING);
            }
            return;
        }
        try {
            streamer.subscribe(far, Mode.FULL);
            subscribed.addAll(far);
            log.info("{} far-expiry band centred at {}: +{} contracts ({} subscribed in total)", underlying, spot,
                    far.size(), subscribed.size());
        } catch (RuntimeException e) {
            farOff = true;
            log.warn("far-expiry recording off for this session: the subscription was refused ({})", e.getMessage());
        }
    }

    @Override
    public void stop() {
        stopped = true;
    }

    @Override
    public Instant lastEventTime() {
        return last;
    }

    @Override
    public boolean replay() {
        return false;
    }

    public String connection() {
        return connection;
    }

    public long messages() {
        return messages.get();
    }

    public int subscriptions() {
        return subscribed.size();
    }
}
