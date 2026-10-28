package com.autotrade.trading;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autotrade.core.time.MarketTime;

/**
 * Put/call open-interest ratio per minute for one index and day (display only, every recorded index): the nearest
 * expiry on or after the day, over the contracts in the own capture (the subscribed band around the money), each
 * contract's latest OI carried forward to the minute, PCR = sum of put OI / sum of call OI.
 * <p>
 * Cost control (a day holds millions of option ticks): today is computed incrementally (each call reads only the
 * ticks after the previous call); an earlier day is computed once and kept, and not at all during market hours
 * (09:00-15:50 IST weekdays) unless already kept, so it never competes with the live session for the database. A
 * complete day is also kept in md.chart_day_cache ({@link ChartDb}, 28 Oct 2026), so a restart or deploy does not read
 * its ticks again, and a kept day shows at any hour. All reads use the charts pool, never the engine's.
 * In {@code --mode=auto} a background warmer reads each recorded index's newest minute every minute of the session, so
 * today's series is always current and every read stays small.
 */
@RestController
@RequestMapping("/api")
class PcrController implements org.springframework.beans.factory.DisposableBean {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PcrController.class);

    static final LocalTime OPEN = LocalTime.of(9, 15), CLOSE = LocalTime.of(15, 31);

    /** One day and index: each contract's latest [isPut, OI], the per-minute series and how far the ticks were read. */
    static final class Day {
        final LocalDate expiry;
        final Map<Long, double[]> latest = new HashMap<>();
        final List<Object> series = new ArrayList<>();
        Instant readUntil;
        boolean complete;
        int contracts;

        Day(LocalDate expiry, Instant start) {
            this.expiry = expiry;
            this.readUntil = start;
        }
    }

    private final JdbcTemplate jdbc;
    private final ChartDb db;
    // bounded like the OI tape's cache (every day ever viewed used to stay in memory)
    private final Map<String, Day> days = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Day> eldest) {
            return size() > 16;
        }
    });
    private final List<String> underlyings;
    private final org.springframework.boot.ApplicationArguments args;
    private final MarketHours market;
    private java.util.concurrent.ScheduledExecutorService warmer;
    private volatile Instant lastWarn = Instant.EPOCH;

    PcrController(ChartDb db, TradingProperties properties, org.springframework.boot.ApplicationArguments args,
                  MarketHours market) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.underlyings = List.copyOf(properties.trading().underlyings());
        this.args = args;
        this.market = market;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void startWarmer() {
        String mode = args.containsOption("mode") && !args.getOptionValues("mode").isEmpty()
                ? args.getOptionValues("mode").getFirst() : "live";
        if (!"auto".equals(mode)) {
            return;
        }
        warmer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("pcr-warmer").daemon(true).factory());
        warmer.scheduleAtFixedRate(() -> {
            ZonedDateTime now = ZonedDateTime.now(MarketTime.IST);
            if (!market.tradingDay(now.toLocalDate()) || now.toLocalTime().isBefore(LocalTime.of(9, 16))
                    || now.toLocalTime().isAfter(LocalTime.of(15, 32))) {
                return;
            }
            for (String u : underlyings) {
                try {
                    pcr(null, u);
                } catch (Throwable e) {                               // never let one failure cancel the schedule
                    Instant at = Instant.now();
                    if (at.isAfter(lastWarn.plus(java.time.Duration.ofMinutes(10)))) {      // WARN at most every 10 min
                        lastWarn = at;
                        log.warn("pcr warmer {}: {}", u, e.toString());
                    } else {
                        log.debug("pcr warmer {}: {}", u, e.toString());
                    }
                }
            }
        }, 30, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        if (warmer != null) {
            warmer.shutdownNow();
        }
    }

    @GetMapping("/pcr")
    Map<String, Object> pcr(@RequestParam(required = false) String date, @RequestParam String underlying) {
        ZonedDateTime now = ZonedDateTime.now(MarketTime.IST);
        LocalDate today = now.toLocalDate();
        LocalDate day = date == null ? today : LocalDate.parse(date);
        String u = underlying.toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("underlying", u);
        String key = day + "|" + u;
        Day d = days.get(key);
        if (d == null) {
            d = kept(key, day, u);                          // one small read, at any hour
        }
        if (d == null && !day.equals(today) && market.busy(now)) {
            out.put("deferred", true);                      // an earlier day is computed after the close
            out.put("series", List.of());
            return out;
        }
        if (d == null) {
            List<LocalDate> expiries = jdbc.query("select min(expiry) from md.option_tick where session_date = ? and "
                    + "underlying = ? and expiry >= ? and recv_ts >= ? and recv_ts < ?",
                    (rs, i) -> rs.getObject(1, LocalDate.class), day, u, day, ts(day, OPEN), ts(day, CLOSE));
            if (expiries.isEmpty() || expiries.getFirst() == null) {
                out.put("series", List.of());
                return out;
            }
            LocalDate expiry = expiries.getFirst();
            // one Day per key even when the warmer and a request miss at once (both used to read the whole day)
            d = days.computeIfAbsent(key, k -> new Day(expiry, day.atTime(OPEN).atZone(MarketTime.IST).toInstant()));
        }
        synchronized (d) {
            if (!d.complete) {
                Instant end = day.atTime(CLOSE).atZone(MarketTime.IST).toInstant();
                // today only up to whole minutes the live capture has written (it writes every 5 s; see OiTapeController)
                Instant written = Instant.now().minus(OiTapeController.WRITE_LAG);
                Instant until = day.equals(today) && written.isBefore(end)
                        ? written.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) : end;
                read(d, day, u, until);
                d.complete = !until.isBefore(end);
                if (d.complete) {
                    db.save(KIND, day, u, encode(d));
                }
            }
            out.put("expiry", d.expiry.toString());
            out.put("contracts", d.contracts);
            out.put("series", List.copyOf(d.series));
        }
        return out;
    }

    /** A malformed {@code date} is the caller's error (400), not the server's. */
    @org.springframework.web.bind.annotation.ExceptionHandler(java.time.format.DateTimeParseException.class)
    org.springframework.http.ResponseEntity<Map<String, Object>> badDate(java.time.format.DateTimeParseException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", "bad date: " + e.getParsedString()));
    }

    /** Reads the ticks from where the day was left to {@code until} (whole minutes only) and extends the series. */
    private void read(Day d, LocalDate day, String underlying, Instant until) {
        if (!until.isAfter(d.readUntil)) {
            return;
        }
        Map<LocalTime, Map<Long, double[]>> byMinute = new TreeMap<>();
        jdbc.query("select (time_bucket('1 minute', recv_ts) at time zone 'Asia/Kolkata')::time, instrument_token, "
                        + "option_type, last(oi, recv_ts) from md.option_tick where session_date = ? and underlying = ? "
                        + "and expiry = ? and recv_ts >= ? and recv_ts < ? and oi > 0 group by 1, 2, 3",
                rs -> {
                    byMinute.computeIfAbsent(rs.getObject(1, LocalTime.class), t -> new HashMap<>())
                            .put(rs.getLong(2), new double[] {"PE".equals(rs.getString(3)) ? 1 : 0, rs.getDouble(4)});
                }, day, underlying, d.expiry, Timestamp.from(d.readUntil), Timestamp.from(until));
        for (Map.Entry<LocalTime, Map<Long, double[]>> minute : byMinute.entrySet()) {
            d.latest.putAll(minute.getValue());
            double put = 0, call = 0;
            for (double[] c : d.latest.values()) {
                if (c[0] == 1) {
                    put += c[1];
                } else {
                    call += c[1];
                }
            }
            if (call > 0) {
                d.series.add(List.of(minute.getKey().toString(), Math.round(put / call * 1000) / 1000.0, Math.round(put),
                        Math.round(call)));
            }
        }
        d.contracts = d.latest.size();
        d.readUntil = until;
    }

    static final String KIND = "pcr";
    private static final int FORMAT = 1;

    /** The kept complete day, now in memory; null when none is kept. */
    private Day kept(String key, LocalDate day, String u) {
        byte[] payload = db.load(KIND, day, u);
        Day d = payload == null ? null : decode(payload);
        return d == null ? null : days.computeIfAbsent(key, k -> d);
    }

    /** A complete day: its expiry, contract count and series (time, PCR, put OI, call OI). */
    static byte[] encode(Day d) {
        return ChartDb.encode(out -> {
            out.writeInt(FORMAT);
            out.writeLong(d.expiry.toEpochDay());
            out.writeInt(d.contracts);
            out.writeInt(d.series.size());
            for (Object row : d.series) {
                List<?> r = (List<?>) row;
                out.writeUTF((String) r.get(0));
                out.writeDouble(((Number) r.get(1)).doubleValue());
                out.writeLong(((Number) r.get(2)).longValue());
                out.writeLong(((Number) r.get(3)).longValue());
            }
        });
    }

    static Day decode(byte[] payload) {
        return ChartDb.decode(payload, in -> {
            if (in.readInt() != FORMAT) {
                return null;
            }
            Day d = new Day(LocalDate.ofEpochDay(in.readLong()), Instant.EPOCH);
            d.contracts = in.readInt();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                d.series.add(List.of(in.readUTF(), in.readDouble(), in.readLong(), in.readLong()));
            }
            d.complete = true;
            return d;
        });
    }

    private static Timestamp ts(LocalDate day, LocalTime time) {
        return Timestamp.from(day.atTime(time).atZone(MarketTime.IST).toInstant());
    }
}
