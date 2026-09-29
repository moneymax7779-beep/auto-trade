package com.autotrade.upstox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionPhaseEvent;
import com.upstox.feeder.MarketUpdateV3;

/**
 * Turns Upstox Market Data Feed V3 messages into platform events. Receipt time is the moment the
 * message reached us; the exchange time is the last trade time. Each underlying gets its own
 * increasing sequence so replay order is deterministic.
 */
public final class UpstoxEventMapper {

    private static final Map<String, List<String>> CAS_SEGMENT_UNDERLYINGS = Map.of(
            "NSE_EQ", List.of("NIFTY", "BANKNIFTY"), "BSE_EQ", List.of("SENSEX"));

    private final UpstoxUniverse universe;
    private final Map<String, Long> sequences = new HashMap<>();
    private final Map<String, Double> lastIndex = new HashMap<>();

    public UpstoxEventMapper(UpstoxUniverse universe) {
        this.universe = universe;
    }

    public List<MarketEvent> map(MarketUpdateV3 update, Instant received) {
        List<MarketEvent> events = new ArrayList<>();
        if (update.getMarketInfo() != null) {
            events.addAll(marketInfo(update.getMarketInfo(), received));
        }
        if (update.getFeeds() == null) {
            return events;
        }
        update.getFeeds().forEach((key, feed) -> {
            UpstoxUniverse.Meta meta = universe.meta(key);
            if (meta != null && feed != null) {
                events.addAll(feed(meta, feed, received));
            }
        });
        return events;
    }

    /** Latest index value seen for an underlying (for option re-centring). */
    public Double lastIndex(String underlying) {
        return lastIndex.get(underlying);
    }

    List<MarketEvent> feed(UpstoxUniverse.Meta meta, MarketUpdateV3.Feed feed, Instant received) {
        MarketUpdateV3.FullFeed full = feed.getFullFeed();
        MarketUpdateV3.MarketFullFeed market = full == null ? null : full.getMarketFF();
        MarketUpdateV3.LTPC ltpc = market != null ? market.getLtpc()
                : full != null && full.getIndexFF() != null ? full.getIndexFF().getLtpc() : feed.getLtpc();
        if (ltpc == null || !(ltpc.getLtp() > 0)) {
            return List.of();
        }
        Instant exchange = ltpc.getLtt() > 0 ? Instant.ofEpochMilli(ltpc.getLtt()) : null;
        Double close = ltpc.getCp() > 0 ? ltpc.getCp() : null;
        long sequence = sequences.merge(meta.underlying(), 1L, Long::sum);
        return switch (meta.kind()) {
            case INDEX, VIX -> {
                lastIndex.put(meta.underlying(), ltpc.getLtp());
                yield List.of(new IndexTick(received, exchange, meta.underlying(), sequence, ltpc.getLtp(), close, null));
            }
            case FUTURE -> {
                // full mode carries the future's 5-level book as it does an option's
                List<MarketUpdateV3.Quote> quotes = market == null || market.getMarketLevel() == null ? null
                        : market.getMarketLevel().getBidAskQuote();
                if (quotes == null) {
                    quotes = List.of();
                }
                yield List.of(new FutureTick(received, exchange, meta.underlying(), sequence, meta.token(),
                        meta.symbol(), meta.expiry(), ltpc.getLtp(), market == null ? null : market.getVtt(),
                        market == null ? null : market.getOi(), market == null || !(market.getAtp() > 0) ? null : market.getAtp(),
                        market == null ? null : market.getTbq(), market == null ? null : market.getTsq(),
                        side(quotes, true), side(quotes, false)));
            }
            case OPTION -> List.of(option(meta, market, ltpc, exchange, received, sequence));
            case EQUITY -> equity(meta, market, ltpc, exchange, close, received, sequence);
        };
    }

    private static OptionTick option(UpstoxUniverse.Meta meta, MarketUpdateV3.MarketFullFeed market,
                                     MarketUpdateV3.LTPC ltpc, Instant exchange, Instant received, long sequence) {
        DepthLevels bids = DepthLevels.empty();
        DepthLevels asks = DepthLevels.empty();
        Double iv = null;
        Double delta = null;
        Double gamma = null;
        Double theta = null;
        Double vega = null;
        Double rho = null;
        boolean depthComplete = false;
        boolean analytics = false;
        if (market != null) {
            List<MarketUpdateV3.Quote> quotes = market.getMarketLevel() == null ? List.of()
                    : market.getMarketLevel().getBidAskQuote();
            if (quotes == null) {
                quotes = List.of();
            }
            bids = side(quotes, true);
            asks = side(quotes, false);
            depthComplete = quotes.size() >= 5;
            MarketUpdateV3.OptionGreeks greeks = market.getOptionGreeks();
            if (greeks != null) {
                delta = greeks.getDelta();
                gamma = greeks.getGamma();
                theta = greeks.getTheta();
                vega = greeks.getVega();
                rho = greeks.getRho();
            }
            iv = market.getIv() > 0 ? market.getIv() : null;
            analytics = iv != null && delta != null && delta != 0;
        }
        return new OptionTick(received, exchange, meta.underlying(), sequence, meta.token(), meta.symbol(),
                meta.expiry(), meta.strike(), OptionTick.OptionType.valueOf(meta.optionType()), meta.lotSize(),
                ltpc.getLtp(), market == null ? null : market.getVtt(), market == null ? null : market.getOi(),
                market == null ? null : market.getTbq(), market == null ? null : market.getTsq(), bids, asks, iv, delta,
                gamma, theta, vega, rho, analytics ? received : null, analytics, depthComplete);
    }

    /** One side of the book from Upstox's combined bid/ask levels, dropping empty levels. */
    private static DepthLevels side(List<MarketUpdateV3.Quote> quotes, boolean bid) {
        List<double[]> levels = new ArrayList<>();
        for (MarketUpdateV3.Quote quote : quotes) {
            double price = bid ? quote.getBidP() : quote.getAskP();
            long quantity = bid ? quote.getBidQ() : quote.getAskQ();
            if (price > 0 && quantity > 0) {
                levels.add(new double[] {price, quantity});
            }
        }
        double[] prices = new double[levels.size()];
        long[] quantities = new long[levels.size()];
        for (int i = 0; i < levels.size(); i++) {
            prices[i] = levels.get(i)[0];
            quantities[i] = (long) levels.get(i)[1];
        }
        return DepthLevels.of(prices, quantities, new int[levels.size()]);
    }

    private static List<MarketEvent> equity(UpstoxUniverse.Meta meta, MarketUpdateV3.MarketFullFeed market,
                                            MarketUpdateV3.LTPC ltpc, Instant exchange, Double close, Instant received,
                                            long sequence) {
        List<MarketEvent> events = new ArrayList<>();
        events.add(new ConstituentTick(received, exchange, meta.underlying(), sequence, meta.token(), meta.symbol(),
                ltpc.getLtp(), close, market == null ? null : market.getVtt(),
                market == null || !(market.getAtp() > 0) ? null : market.getAtp(), meta.weightPercent()));
        if (market != null && market.getIep() > 0) {
            events.add(new AuctionTick(received, exchange, meta.underlying(), meta.token(), meta.symbol(),
                    market.getIep(), market.getRp(), market.getIeq(), market.getIiqTotal(), market.getIiqM(),
                    market.isCasEligible()));
        }
        return events;
    }

    /** Closing-auction status changes per cash segment, as session-phase events of the affected indices. */
    private List<MarketEvent> marketInfo(MarketUpdateV3.MarketInfo info, Instant received) {
        List<MarketEvent> events = new ArrayList<>();
        if (info.getCasMarketStatus() == null) {
            return events;
        }
        info.getCasMarketStatus().forEach((segment, status) -> {
            for (String underlying : CAS_SEGMENT_UNDERLYINGS.getOrDefault(segment, List.of())) {
                Double index = lastIndex.get(underlying);
                if (status != null && index != null) {
                    Instant at = status.getUpdatedTime() > 0 ? Instant.ofEpochMilli(status.getUpdatedTime()) : received;
                    events.add(new SessionPhaseEvent(received, at, underlying, "UPSTOX_" + status.getStatus(),
                            "UPSTOX_CAS_STATUS", index, false));
                }
            }
        });
        return events;
    }
}
