package com.autotrade.core.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachingSessionHistoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);

    /** A history whose answers depend on every argument, counting the calls that reach it. */
    static final class Source implements SessionHistory {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
            calls.incrementAndGet();
            return until.getHour() == 9 ? Optional.empty()
                    : Optional.of(new DailyBar(session.minusDays(1), 100.25, 101.5, Double.NaN, -0.0));
        }

        @Override
        public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
            calls.incrementAndGet();
            List<Map<LocalTime, Long>> out = new ArrayList<>();
            for (int d = 0; d < sessions; d++) {
                Map<LocalTime, Long> m = new TreeMap<>();
                m.put(LocalTime.of(9, 15), 1000L + d);
                m.put(LocalTime.of(9, 16), Long.MAX_VALUE - d);
                out.add(m);
            }
            out.add(new TreeMap<>());   // an empty session
            return out;
        }

        @Override
        public List<List<MinuteBar>> spotMinuteBars(String underlying, LocalDate session, int sessions) {
            calls.incrementAndGet();
            List<List<MinuteBar>> out = new ArrayList<>();
            for (int d = 0; d < sessions; d++) {
                Instant start = Instant.parse("2026-10-14T03:45:00Z").minusSeconds(86_400L * d);
                out.add(List.of(new MinuteBar(start, 25_000.05 + d, 25_010.1, 24_990.123456789, Double.longBitsToDouble(0x7ff8000000000123L)),
                        new MinuteBar(start.plusSeconds(60).plusNanos(123_456_789), 1e-300, Double.MAX_VALUE, Double.MIN_VALUE, 1)));
            }
            return out;
        }

        @Override
        public List<IvSession> atmIvMinutes(String underlying, LocalDate session, int sessions) {
            calls.incrementAndGet();
            Map<LocalTime, Double> m = new HashMap<>();
            m.put(LocalTime.of(9, 20), 0.1234);
            m.put(LocalTime.of(15, 29, 59, 999_999_999), 0.5);
            return List.of(new IvSession(session.minusDays(1), true, Map.copyOf(m)),
                    new IvSession(session.minusDays(2), false, Map.of()));
        }
    }

    @Test
    void aHitReturnsExactlyWhatTheSourceReturned(@TempDir Path dir) {
        Source source = new Source();
        CachingSessionHistory first = new CachingSessionHistory(source, new CachingSessionHistory.FileStore(dir), "fp");
        var prev = first.previousSession("NIFTY", DAY, LocalTime.of(15, 30));
        var none = first.previousSession("NIFTY", DAY, LocalTime.of(9, 30));
        var vol = first.futuresMinuteVolumes("NIFTY", DAY, 3);
        var bars = first.spotMinuteBars("NIFTY", DAY, 4);
        var iv = first.atmIvMinutes("NIFTY", DAY, 2);
        assertEquals(5, source.calls.get());
        assertEquals(5, first.misses());

        // a second process: same store, same fingerprint -> nothing reaches the source
        Source again = new Source();
        CachingSessionHistory second = new CachingSessionHistory(again, new CachingSessionHistory.FileStore(dir), "fp");
        var prev2 = second.previousSession("NIFTY", DAY, LocalTime.of(15, 30));
        var none2 = second.previousSession("NIFTY", DAY, LocalTime.of(9, 30));
        var vol2 = second.futuresMinuteVolumes("NIFTY", DAY, 3);
        var bars2 = second.spotMinuteBars("NIFTY", DAY, 4);
        var iv2 = second.atmIvMinutes("NIFTY", DAY, 2);
        assertEquals(0, again.calls.get());
        assertEquals(5, second.hits());

        assertEquals(prev, prev2);
        assertEquals(none, none2);
        assertEquals(vol, vol2);
        assertInstanceOf(TreeMap.class, vol2.getFirst());
        assertEquals(bars, bars2);
        assertEquals(iv, iv2);
        // records compare doubles by bits (NaN payloads, -0.0 included)
        assertEquals(Double.doubleToRawLongBits(bars.getFirst().getFirst().close()),
                Double.doubleToRawLongBits(bars2.getFirst().getFirst().close()));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(prev2.get().close()));
    }

    @Test
    void everyArgumentAndTheFingerprintAreInTheKey(@TempDir Path dir) {
        Source source = new Source();
        CachingSessionHistory c = new CachingSessionHistory(source, new CachingSessionHistory.FileStore(dir), "fp");
        c.spotMinuteBars("NIFTY", DAY, 4);
        c.spotMinuteBars("NIFTY", DAY, 5);
        c.spotMinuteBars("SENSEX", DAY, 4);
        c.spotMinuteBars("NIFTY", DAY.plusDays(1), 4);
        c.futuresMinuteVolumes("NIFTY", DAY, 4);
        assertEquals(5, source.calls.get());
        assertEquals(0, c.hits());

        Source other = new Source();
        CachingSessionHistory changed = new CachingSessionHistory(other, new CachingSessionHistory.FileStore(dir), "fp2");
        changed.spotMinuteBars("NIFTY", DAY, 4);
        assertEquals(1, other.calls.get());
        assertEquals(0, changed.hits());
    }

    @Test
    void anUnreadableFileIsAMissAndIsReplaced(@TempDir Path dir) throws IOException {
        Source source = new Source();
        CachingSessionHistory c = new CachingSessionHistory(source, new CachingSessionHistory.FileStore(dir), "fp");
        var bars = c.spotMinuteBars("NIFTY", DAY, 2);
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Files.write(f, new byte[] {1, 2, 3});
            }
        }
        CachingSessionHistory d = new CachingSessionHistory(source, new CachingSessionHistory.FileStore(dir), "fp");
        assertEquals(bars, d.spotMinuteBars("NIFTY", DAY, 2));
        assertEquals(1, d.misses());
        assertTrue(d.storeErrors() >= 1);
        CachingSessionHistory e = new CachingSessionHistory(source, new CachingSessionHistory.FileStore(dir), "fp");
        assertEquals(bars, e.spotMinuteBars("NIFTY", DAY, 2));
        assertEquals(1, e.hits());
    }

    @Test
    void aBrokenStoreNeverFailsTheLookup() {
        Source source = new Source();
        CachingSessionHistory.Store broken = new CachingSessionHistory.Store() {
            @Override
            public byte[] get(String key) {
                throw new IllegalStateException("disk gone");
            }

            @Override
            public void put(String key, byte[] value) {
                throw new IllegalStateException("disk gone");
            }
        };
        CachingSessionHistory c = new CachingSessionHistory(source, broken, "fp");
        assertEquals(source.spotMinuteBars("NIFTY", DAY, 2), c.spotMinuteBars("NIFTY", DAY, 2));
        assertEquals(2, c.storeErrors());
    }

    @Test
    void anotherSessionThanTheFingerprintsPassesThroughUncached(@TempDir Path dir) {
        Source source = new Source();
        CachingSessionHistory c = new CachingSessionHistory(source, new CachingSessionHistory.FileStore(dir), "fp", DAY);
        c.spotMinuteBars("NIFTY", DAY, 2);
        c.spotMinuteBars("NIFTY", DAY, 2);
        assertEquals(1, c.hits());
        var other = c.spotMinuteBars("NIFTY", DAY.plusDays(1), 2);
        c.spotMinuteBars("NIFTY", DAY.plusDays(1), 2);
        assertEquals(source.spotMinuteBars("NIFTY", DAY.plusDays(1), 2), other);
        assertEquals(1, c.hits());
        assertEquals(1, c.misses());          // only the first DAY request was a miss; other days never touch the store
        assertEquals(4, source.calls.get());  // DAY once + the other day twice + the comparison call
    }
}
