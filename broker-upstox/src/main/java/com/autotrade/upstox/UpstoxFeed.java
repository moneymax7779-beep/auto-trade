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
    private final UpstoxEventMapper mapper;
    private final BlockingQueue<Received> queue = new LinkedBlockingQueue<>();
    private final Map<String, Double> bandCentre = new HashMap<>();
    private final Set<String> subscribed = new HashSet<>();
    private final AtomicLong messages = new AtomicLong();
    private volatile boolean stopped;
    private volatile Instant last;
    private volatile String connection = "NOT_CONNECTED";
    private MarketDataStreamerV3 streamer;

    public UpstoxFeed(UpstoxToken token, UpstoxUniverse universe, List<String> underlyings, int strikesEachSide,
                      int recenterStrikes) {
        this.token = token;
        this.universe = universe;
        this.underlyings = underlyings;
        this.strikesEachSide = strikesEachSide;
        this.recenterStrikes = recenterStrikes;
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
                    sink.accept(event);
                }
                recentreOptions();
            }
        } finally {
            streamer.disconnect();
        }
    }

    /** Subscribes the option band once each index has a price, and again when it drifts too far. */
    private void recentreOptions() {
        for (String underlying : underlyings) {
            Double spot = mapper.lastIndex(underlying);
            if (spot == null) {
                continue;
            }
            Double centre = bandCentre.get(underlying);
            double step = universe.strikeStep(underlying);
            if (centre != null && Math.abs(spot - centre) < recenterStrikes * step) {
                continue;
            }
            Set<String> keys = new HashSet<>(universe.optionKeys(underlying, spot, strikesEachSide));
            keys.removeAll(subscribed);
            bandCentre.put(underlying, spot);
            if (!keys.isEmpty()) {
                streamer.subscribe(keys, Mode.FULL);
                subscribed.addAll(keys);
                log.info("{} option band centred at {}: +{} contracts ({} subscribed in total)", underlying, spot,
                        keys.size(), subscribed.size());
            }
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
