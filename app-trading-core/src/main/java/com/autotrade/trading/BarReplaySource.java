package com.autotrade.trading;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionEventSource;
import com.autotrade.core.history.IndexWeightFiles;
import com.autotrade.core.time.MarketTime;

/**
 * Replays a session from the one-minute bars in {@code hist.candle} (Upstox history) by synthesising ticks: for every
 * bar the open, then the high and low in the bar's direction, then the close just before the minute ends, so the
 * minute bars the features build match the stored bars and nothing is known before the bar closed. Each of the four
 * prices is emitted twice, one second apart: an order placed on a tick then meets the same price again after the fill
 * latency, so a marketable order fills at the price it was placed on (plus the fill model's adverse move) instead of
 * at the bar's next extreme or not at all within the entry timeout (the A-045 first-round artefact: half the bar
 * entries never filled). Futures carry a
 * cumulative volume, open interest and a VWAP built from bar closes; options one depth level at the last price (no
 * spread), no IV or greeks (the features model them); constituents the weight of the day. No order book, no closing
 * auction, no session-phase events: a weaker evidence class than the tick capture (docs/studies/2026-10-06-bar-replay.md).
 */
final class BarReplaySource implements SessionEventSource {

    private static final Logger log = LoggerFactory.getLogger(BarReplaySource.class);
    static final String VIX = "INDIA_VIX";
    /** Seconds into the minute at which the open, the first and second extreme, and the close are emitted. */
    static final double[] OFFSETS = {1.0, 20.0, 40.0, 58.5};
    /** Each price is repeated this many seconds after its first tick (longer than the fill latency, 250 ms). */
    static final double REPEAT_AFTER = 1.0;
    /** Options and futures also quote a bar's open this long before the bar starts (not for their first bar). */
    static final long PRE_OPEN_MS = 100;
    /** The one synthetic depth level's quantity: deep enough never to be the binding constraint. */
    static final long DEPTH_QUANTITY = 100_000;

    /** One stored bar. */
    record Bar(String key, String underlying, String kind, String symbol, String exchangeToken, LocalDate expiry, double strike,
               String optionType, Integer lotSize, Instant start, double open, double high, double low, double close,
               long volume, Double oi) {
    }

    private final JdbcTemplate jdbc;
    private final IndexWeightFiles weights;

    BarReplaySource(DataSource target, IndexWeightFiles weights) {
        this.jdbc = new JdbcTemplate(target);
        this.weights = weights;
    }

    @Override
    public String name() {
        return "bars";
    }

    @Override
    public ReplayResult replay(LocalDate session, List<String> underlyings, Consumer<MarketEvent> sink) {
        List<String> all = new ArrayList<>(underlyings);
        if (!all.contains(VIX)) {
            all.add(VIX);
        }
        List<Bar> bars = load(session, all);
        if (bars.isEmpty()) {
            throw new IllegalStateException("no bars in hist.candle for " + session + " " + underlyings
                    + " (run --mode=fetch-bars first)");
        }
        Map<String, Double> previousClose = previousCloses(session, all);
        Map<String, Map<String, Double>> weightsByUnderlying = new HashMap<>();
        for (String u : underlyings) {
            weightsByUnderlying.put(u, weights == null ? Map.of() : weights.weights(u, session));
        }
        List<MarketEvent> events = events(bars, previousClose, weightsByUnderlying);
        long n = 0;
        for (MarketEvent event : events) {
            sink.accept(event);
            n++;
        }
        log.info("bars {}: {} bars -> {} synthetic ticks", session, bars.size(), n);
        return new ReplayResult(n, 0);
    }

    /** The day's bars: index and VIX, the nearest-expiry future and options with bars that day, the constituents. */
    private List<Bar> load(LocalDate session, List<String> underlyings) {
        Timestamp from = Timestamp.from(session.atStartOfDay(MarketTime.IST).toInstant());
        Timestamp to = Timestamp.from(session.plusDays(1).atStartOfDay(MarketTime.IST).toInstant());
        String in = String.join(",", underlyings.stream().map(u -> "?").toList());
        List<Object> args = new ArrayList<>(underlyings);
        args.add(from);
        args.add(to);
        // one expiry per underlying and kind: the nearest at or after the session that has bars that day
        String sql = "with day as (select * from hist.candle where underlying in (" + in + ") and bar_start >= ? and bar_start < ?), "
                + "near as (select underlying, kind, min(expiry) expiry from day where expiry is not null and expiry >= ?::date group by 1, 2) "
                + "select d.* from day d left join near n on n.underlying = d.underlying and n.kind = d.kind "
                + "where d.expiry is null or d.expiry = n.expiry order by d.bar_start, d.kind, d.instrument_key";
        args.add(session);
        return jdbc.query(sql, (rs, i) -> new Bar(rs.getString("instrument_key"), rs.getString("underlying"), rs.getString("kind"),
                rs.getString("symbol"), rs.getString("exchange_token"), rs.getObject("expiry", LocalDate.class),
                rs.getDouble("strike"), rs.getString("option_type"), (Integer) rs.getObject("lot_size"),
                rs.getTimestamp("bar_start").toInstant(), rs.getDouble("open"), rs.getDouble("high"), rs.getDouble("low"),
                rs.getDouble("close"), rs.getLong("volume"), (Double) rs.getObject("oi")), args.toArray());
    }

    /** The last close of the previous stored session per instrument key (index and constituents). */
    private Map<String, Double> previousCloses(LocalDate session, List<String> underlyings) {
        String in = String.join(",", underlyings.stream().map(u -> "?").toList());
        List<Object> args = new ArrayList<>(underlyings);
        args.add(Timestamp.from(session.atStartOfDay(MarketTime.IST).toInstant()));
        Map<String, Double> out = new HashMap<>();
        jdbc.query("select distinct on (instrument_key) instrument_key, close from hist.candle where underlying in (" + in + ") "
                + "and kind in ('INDEX', 'VIX', 'EQUITY') and bar_start < ? order by instrument_key, bar_start desc",
                rs -> {
                    out.put(rs.getString(1), rs.getDouble(2));
                }, args.toArray());
        return out;
    }

    /** The synthetic ticks of a day's bars, in time order (pure: tested without a database). */
    static List<MarketEvent> events(List<Bar> bars, Map<String, Double> previousClose, Map<String, Map<String, Double>> weights) {
        List<MarketEvent> out = new ArrayList<>(bars.size() * 8);
        Map<String, long[]> running = new HashMap<>();          // key -> {cumulative volume, cumulative price*volume x100}
        List<Bar> ordered = new ArrayList<>(bars);
        // at each instant the index (and VIX) tick comes last: the strategies decide on index ticks, so the option and
        // futures quotes they see are this minute's, not the previous bar's close (else a marketable entry limit sits
        // below a gapped open and never fills; A-045 round 2)
        ordered.sort(Comparator.comparing(Bar::start).thenComparingInt(BarReplaySource::kindRank).thenComparing(Bar::key));
        long sequence = 0;
        for (Bar b : ordered) {
            boolean quoted = b.kind().equals("OPTION") || b.kind().equals("FUTURE");
            long[] run = running.get(b.key());
            if (quoted && run != null) {
                // the quote at the minute boundary: the snapshot at :00 is taken from the state before any event at or
                // after :00, so an option's quote there would be the previous bar's close while the entry fills at this
                // bar's open; a tick-built stream quotes about the next open at the boundary (A-045 rounds 1-3)
                double before = run[0] > 0 ? run[1] / 100.0 / run[0] : Double.NaN;
                out.add(tick(b, b.start().minusMillis(PRE_OPEN_MS), b.open(), null, null, Double.isNaN(before) ? null : before,
                        previousClose.get(b.key()), weights, ++sequence));
            }
            if (run == null) {
                run = new long[2];
                running.put(b.key(), run);
            }
            run[0] += b.volume();
            run[1] += Math.round(b.close() * 100) * b.volume();
            double vwap = run[0] > 0 ? run[1] / 100.0 / run[0] : Double.NaN;
            boolean up = b.close() >= b.open();
            double[] prices = {b.open(), up ? b.low() : b.high(), up ? b.high() : b.low(), b.close()};
            for (int i = 0; i < prices.length; i++) {
                for (int repeat = 0; repeat < 2; repeat++) {
                    Instant at = b.start().plusMillis((long) ((OFFSETS[i] + repeat * REPEAT_AFTER) * 1000));
                    boolean last = i == prices.length - 1 && repeat == 1;
                    out.add(tick(b, at, prices[i], last ? run[0] : null, last ? b.oi() : null, Double.isNaN(vwap) ? null : vwap,
                            previousClose.get(b.key()), weights, ++sequence));
                }
            }
        }
        out.sort(Comparator.comparing(MarketEvent::receivedAt).thenComparingLong(MarketEvent::sourceSequence));
        return out;
    }

    private static MarketEvent tick(Bar b, Instant at, double price, Long cumulativeVolume, Double oi, Double vwap,
                                    Double previousClose, Map<String, Map<String, Double>> weights, long sequence) {
        return switch (b.kind()) {
            case "INDEX", "VIX" -> new IndexTick(at, at, b.underlying(), sequence, price, previousClose, null);
            case "FUTURE" -> new FutureTick(at, at, b.underlying(), sequence, token(b.key()), b.symbol(), b.expiry(), price,
                    cumulativeVolume, oi, vwap);
            case "OPTION" -> {
                DepthLevels level = DepthLevels.of(new double[] {price}, new long[] {DEPTH_QUANTITY}, new int[] {1});
                yield new OptionTick(at, at, b.underlying(), sequence, token(b.key()), b.symbol(), b.expiry(), b.strike(),
                        OptionTick.OptionType.valueOf(b.optionType()), b.lotSize(), price, cumulativeVolume, oi, null, null,
                        level, level, null, null, null, null, null, null, null, false, true);
            }
            case "EQUITY" -> {
                Map<String, Double> w = weights.getOrDefault(b.underlying(), Map.of());
                Double weight = w.containsKey(b.symbol()) ? w.get(b.symbol()) : w.get(b.exchangeToken());
                yield new ConstituentTick(at, at, b.underlying(), sequence, token(b.key()), b.symbol(), price, previousClose,
                        cumulativeVolume, vwap, weight);
            }
            default -> throw new IllegalStateException("unknown bar kind " + b.kind());
        };
    }

    /** Emission order of the kinds at one instant: quotes before the index tick that triggers decisions. */
    static int kindRank(Bar b) {
        return switch (b.kind()) {
            case "EQUITY" -> 0;
            case "FUTURE" -> 1;
            case "OPTION" -> 2;
            case "INDEX" -> 3;
            default -> 4;
        };
    }

    /** A stable token for a key (the features key option state by token and symbol; Upstox keys are strings). */
    static long token(String key) {
        long h = 1125899906842597L;
        for (int i = 0; i < key.length(); i++) {
            h = 31 * h + key.charAt(i);
        }
        return Math.abs(h % 9_000_000_000L) + 1;
    }
}
