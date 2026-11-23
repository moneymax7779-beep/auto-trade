package com.autotrade.md.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionPhaseEvent;

class ReplayCacheTest {

    private static final Instant T = Instant.parse("2026-11-17T03:45:00.123456789Z");
    private static final double ODD_NAN = Double.longBitsToDouble(0x7ff8000000000abcL);

    static List<MarketEvent> events() {
        DepthLevels book = DepthLevels.of(new double[] {101.05, 101.0, ODD_NAN}, new long[] {75, 1500, Long.MAX_VALUE},
                new int[] {1, 12, 0});
        List<MarketEvent> e = new ArrayList<>();
        e.add(new IndexTick(T, null, "NIFTY", 7, 25_412.35, null, null));
        e.add(new IndexTick(T, T.plusNanos(1), "SENSEX", Long.MIN_VALUE, -0.0, 82_000.5, 82_000));
        e.add(new FutureTick(T, T, "NIFTY", 8, 123L, "NIFTY25NOVFUT", LocalDate.of(2026, 11, 25), 25_450.0,
                99L, 1.5e7, 25_430.1, 1e5, 2e5, book, DepthLevels.empty()));
        e.add(new FutureTick(T, null, "NIFTY", 9, 123L, null, null, ODD_NAN, null, null, null));
        e.add(new OptionTick(T, T, "NIFTY", 10, 456L, "NIFTY 25400 CE", LocalDate.of(2026, 11, 18), 25_400, OptionTick.OptionType.CE,
                65, 120.5, 1_000L, 2.5e6, 3e4, 4e4, book, book, 0.11, 0.52, 0.001, -12.5, 9.1, null, T, true, false));
        e.add(new OptionTick(T, null, "SENSEX", 11, 789L, null, null, 82_000, OptionTick.OptionType.PE,
                null, 0.05, null, null, null, null, null, DepthLevels.empty(), null, null, null, null, null, null, null,
                false, true));
        e.add(new OptionTick(T, T, "SENSEX", 12, 790L, "x", null, 1, null, 20, 1, 0L, 0.0, 0.0, 0.0,
                DepthLevels.empty(), null, ODD_NAN, Double.NaN, Double.NEGATIVE_INFINITY, 0.0, -0.0, Double.MIN_VALUE, null,
                false, false));
        e.add(new ConstituentTick(T, T, "NIFTY", 13, 2885L, "RELIANCE", 1_450.2, null, 5_000_000L, 1_449.9, 9.87));
        e.add(new ConstituentTick(T, null, "NIFTY", 14, 2886L, null, 0, 1.0, null, null, null));
        e.add(new SessionPhaseEvent(T, T, "SENSEX", "CAS_STOP", "INDICATIVE", 83_021.75, true));
        e.add(new SessionPhaseEvent(T, null, null, null, null, ODD_NAN, false));
        e.add(new AuctionTick(T, T, "SENSEX", 500325L, "RELIANCE", 1_451.0, 1_450.0, 12_345L, -5L, 0L, true));
        e.add(new AuctionTick(T, null, "NIFTY", 0L, null, ODD_NAN, -0.0, Long.MAX_VALUE, Long.MIN_VALUE, 1L, false));
        return e;
    }

    @Test
    void everyEventRoundTripsExactly(@TempDir Path dir) throws IOException {
        Path tmp = dir.resolve("a.tmp");
        ReplayCache.Writer w = new ReplayCache.Writer(tmp, "fp");
        List<MarketEvent> in = events();
        in.forEach(w::write);
        assertTrue(w.finish(in.size()));
        List<MarketEvent> out = ReplayCache.readAll(tmp, "fp");
        assertEquals(in, out);   // records: doubles by Double.compare; DepthLevels by array contents
        // raw bits too (NaN payloads, -0.0), field by field via the codec of the copy
        for (int i = 0; i < in.size(); i++) {
            assertEquals(in.get(i).toString(), out.get(i).toString());
        }
        assertEquals(Double.doubleToRawLongBits(ODD_NAN),
                Double.doubleToRawLongBits(((FutureTick) out.get(3)).price()));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(((IndexTick) out.get(1)).price()));
        assertEquals(null, ((OptionTick) out.get(6)).asks());          // a null book stays null
        assertTrue(((OptionTick) out.get(6)).bids().isEmpty());
    }

    @Test
    void anotherFingerprintIsNotRead(@TempDir Path dir) throws IOException {
        Path tmp = dir.resolve("b.tmp");
        ReplayCache.Writer w = new ReplayCache.Writer(tmp, "fp1");
        events().forEach(w::write);
        assertTrue(w.finish(events().size()));
        List<MarketEvent> seen = new ArrayList<>();
        assertEquals(-1, ReplayCache.read(tmp, "fp2", seen::add));
        assertTrue(seen.isEmpty());
    }

    @Test
    void aShortFileFailsLoudlyAndAnAbandonedRunLeavesNoFile(@TempDir Path dir) throws IOException {
        Path tmp = dir.resolve("c.tmp");
        ReplayCache.Writer w = new ReplayCache.Writer(tmp, "fp");
        events().forEach(w::write);
        assertFalse(w.finish(events().size() + 1));      // fewer written than delivered: dropped
        assertFalse(Files.exists(tmp));

        Path cut = dir.resolve("d.tmp");
        ReplayCache.Writer v = new ReplayCache.Writer(cut, "fp");
        events().forEach(v::write);
        assertTrue(v.finish(events().size()));
        byte[] bytes = Files.readAllBytes(cut);
        Files.write(cut, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        assertThrows(IOException.class, () -> ReplayCache.read(cut, "fp", e -> { }));

        Path gone = dir.resolve("e.tmp");
        ReplayCache.Writer x = new ReplayCache.Writer(gone, "fp");
        x.write(events().getFirst());
        x.abandon();
        assertFalse(Files.exists(gone));
    }

    /** A source that replays {@link #events()} and counts its calls. */
    private static final class FakeSource implements ReplayCache.Source {
        int calls;

        @Override
        public long replay(List<Long> ids, java.util.function.Consumer<MarketEvent> sink) {
            calls++;
            events().forEach(sink);
            return events().size();
        }
    }

    private static final LocalDate DAY = LocalDate.of(2026, 11, 17);

    @Test
    void theFirstRunStoresTheStreamAndTheSecondReadsIt(@TempDir Path dir) throws Exception {
        List<List<Long>> seen = new ArrayList<>();
        ReplayCache cache = new ReplayCache(dir, (d, ids) -> {
            seen.add(ids);
            return "f".repeat(64);
        });
        FakeSource src = new FakeSource();
        List<MarketEvent> first = new ArrayList<>();
        List<MarketEvent> second = new ArrayList<>();
        assertEquals(events().size(), cache.replay(DAY, List.of(130L, 128L), src, first::add));
        assertEquals(events().size(), cache.replay(DAY, List.of(128L, 130L), src, second::add));
        assertEquals(1, src.calls);                       // the second run came from the file
        assertEquals(first, second);
        assertEquals(List.of(List.of(128L, 130L), List.of(128L, 130L)), seen);   // the key ignores the load order
    }

    @Test
    void anUnreadableFileIsRemovedAndTheDatabaseIsUsed(@TempDir Path dir) throws Exception {
        ReplayCache cache = new ReplayCache(dir, (d, ids) -> "a".repeat(64));
        FakeSource src = new FakeSource();
        cache.replay(DAY, List.of(1L), src, e -> { });
        Path file;
        try (var files = Files.list(dir)) {
            file = files.filter(f -> f.toString().endsWith(".events.gz")).findFirst().orElseThrow();
        }
        Files.write(file, new byte[] {1, 2, 3, 4});           // not gzip: fails before any event
        List<MarketEvent> got = new ArrayList<>();
        assertEquals(events().size(), cache.replay(DAY, List.of(1L), src, got::add));
        assertEquals(events(), got);
        assertEquals(2, src.calls);
        assertTrue(Files.exists(file));                      // stored again by this run
    }

    @Test
    void aFileThatFailsMidStreamIsRemovedAndTheReplayFailsLoudly(@TempDir Path dir) throws Exception {
        ReplayCache cache = new ReplayCache(dir, (d, ids) -> "b".repeat(64));
        cache.replay(DAY, List.of(1L), new FakeSource(), e -> { });
        Path file;
        try (var files = Files.list(dir)) {
            file = files.filter(f -> f.toString().endsWith(".events.gz")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 12));   // the gzip trailer gone
        List<MarketEvent> got = new ArrayList<>();
        assertThrows(IOException.class, () -> cache.replay(DAY, List.of(1L), new FakeSource(), got::add));
        assertFalse(Files.exists(file));
    }

    @Test
    void anUnusableCacheDirectoryNeverStopsTheReplay(@TempDir Path dir) throws Exception {
        Path notADir = dir.resolve("plain-file");
        Files.writeString(notADir, "x");
        ReplayCache cache = new ReplayCache(notADir, (d, ids) -> "c".repeat(64));
        FakeSource src = new FakeSource();
        List<MarketEvent> got = new ArrayList<>();
        assertEquals(events().size(), cache.replay(DAY, List.of(1L), src, got::add));
        assertEquals(events(), got);
        ReplayCache noKey = new ReplayCache(dir, (d, ids) -> {
            throw new java.sql.SQLException("database down");
        });
        assertEquals(events().size(), noKey.replay(DAY, List.of(1L), src, e -> { }));
    }
}
