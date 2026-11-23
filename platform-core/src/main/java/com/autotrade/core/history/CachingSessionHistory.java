package com.autotrade.core.history;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A replay-only cache of another history's answers (user, 22 Nov 2026: every replay rebuilt the same earlier-session
 * minute bars, futures volume and ATM IV from raw ticks before trading, 1-4 minutes a run, repeated for every arm and
 * day). Each answer is stored under the exact request (method, underlying, session, depth or cut-off time) together
 * with a fingerprint of the code and of every table the answer is read from, so a hit returns exactly what the wrapped
 * history returned when it was stored, and any change to that data or code gives a new fingerprint and a miss.
 * Doubles are stored as raw bits and the containers are rebuilt as the sources build them (lists in order, futures
 * volume as {@link TreeMap}, IV minutes via {@link Map#copyOf}). The OI baseline and reference series (India VIX,
 * auction turnover) pass through uncached. A store that fails to read or write counts as a miss, never an error.
 */
public final class CachingSessionHistory implements SessionHistory {

    /** Where answers live; implementations must be safe for several processes at once. */
    public interface Store {
        /** The stored bytes, or null when there are none. */
        byte[] get(String key);

        void put(String key, byte[] value);
    }

    private static final int FORMAT = 1;

    private final SessionHistory delegate;
    private final Store store;
    private final String fingerprint;
    /** The session the fingerprint describes (v2: data before it only); null = every session. */
    private final LocalDate validFor;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong storeErrors = new AtomicLong();

    public CachingSessionHistory(SessionHistory delegate, Store store, String fingerprint) {
        this(delegate, store, fingerprint, null);
    }

    /**
     * With a fingerprint of the data before {@code validFor} only: requests for that session are cached, requests for
     * any other session (none in a replay) pass through to {@code delegate} uncached.
     */
    public CachingSessionHistory(SessionHistory delegate, Store store, String fingerprint, LocalDate validFor) {
        this.delegate = delegate;
        this.store = store;
        this.fingerprint = fingerprint;
        this.validFor = validFor;
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public long storeErrors() {
        return storeErrors.get();
    }

    @Override
    public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
        return cached("previousSession", underlying, session, String.valueOf(until),
                () -> delegate.previousSession(underlying, session, until),
                CachingSessionHistory::writeDailyBar, CachingSessionHistory::readDailyBar);
    }

    @Override
    public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
        return cached("futuresMinuteVolumes", underlying, session, String.valueOf(sessions),
                () -> delegate.futuresMinuteVolumes(underlying, session, sessions),
                CachingSessionHistory::writeVolumes, CachingSessionHistory::readVolumes);
    }

    @Override
    public List<List<MinuteBar>> spotMinuteBars(String underlying, LocalDate session, int sessions) {
        return cached("spotMinuteBars", underlying, session, String.valueOf(sessions),
                () -> delegate.spotMinuteBars(underlying, session, sessions),
                CachingSessionHistory::writeBars, CachingSessionHistory::readBars);
    }

    @Override
    public List<IvSession> atmIvMinutes(String underlying, LocalDate session, int sessions) {
        return cached("atmIvMinutes", underlying, session, String.valueOf(sessions),
                () -> delegate.atmIvMinutes(underlying, session, sessions),
                CachingSessionHistory::writeIv, CachingSessionHistory::readIv);
    }

    @Override
    public OiBaseline oiBaseline(String underlying) {
        return delegate.oiBaseline(underlying);
    }

    @Override
    public ReferenceData reference() {
        return delegate.reference();
    }

    // ------------------------------------------------------------------------------------------ caching

    private interface Writer<T> {
        void write(DataOutputStream out, T value) throws IOException;
    }

    private interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    private interface Loader<T> {
        T load();
    }

    private <T> T cached(String method, String underlying, LocalDate session, String arg, Loader<T> loader,
                         Writer<T> writer, Reader<T> reader) {
        if (validFor != null && !validFor.equals(session)) {
            return loader.load();
        }
        String request = fingerprint + "|" + method + "|" + underlying + "|" + session + "|" + arg;
        String key = sha256(request);
        byte[] bytes;
        try {
            bytes = store.get(key);
        } catch (RuntimeException e) {
            storeErrors.incrementAndGet();
            bytes = null;
        }
        if (bytes != null) {
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
                if (in.readInt() == FORMAT && in.readUTF().equals(request)) {
                    T value = reader.read(in);
                    hits.incrementAndGet();
                    return value;
                }
            } catch (IOException | RuntimeException e) {
                storeErrors.incrementAndGet();          // unreadable: treat as a miss and overwrite below
            }
        }
        T value = loader.load();
        misses.incrementAndGet();
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(buffer))) {
                out.writeInt(FORMAT);
                out.writeUTF(request);
                writer.write(out, value);
            }
            store.put(key, buffer.toByteArray());
        } catch (IOException | RuntimeException e) {
            storeErrors.incrementAndGet();
        }
        return value;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------ codecs

    private static void writeInstant(DataOutputStream out, Instant t) throws IOException {
        out.writeLong(t.getEpochSecond());
        out.writeInt(t.getNano());
    }

    private static Instant readInstant(DataInputStream in) throws IOException {
        return Instant.ofEpochSecond(in.readLong(), in.readInt());
    }

    private static void writeDouble(DataOutputStream out, double v) throws IOException {
        out.writeLong(Double.doubleToRawLongBits(v));
    }

    private static double readDouble(DataInputStream in) throws IOException {
        return Double.longBitsToDouble(in.readLong());
    }

    private static void writeTime(DataOutputStream out, LocalTime t) throws IOException {
        out.writeLong(t.toNanoOfDay());
    }

    private static LocalTime readTime(DataInputStream in) throws IOException {
        return LocalTime.ofNanoOfDay(in.readLong());
    }

    private static void writeDailyBar(DataOutputStream out, Optional<DailyBar> value) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isPresent()) {
            DailyBar bar = value.get();
            out.writeLong(bar.session().toEpochDay());
            writeDouble(out, bar.open());
            writeDouble(out, bar.high());
            writeDouble(out, bar.low());
            writeDouble(out, bar.close());
        }
    }

    private static Optional<DailyBar> readDailyBar(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return Optional.empty();
        }
        return Optional.of(new DailyBar(LocalDate.ofEpochDay(in.readLong()), readDouble(in), readDouble(in),
                readDouble(in), readDouble(in)));
    }

    private static void writeVolumes(DataOutputStream out, List<Map<LocalTime, Long>> value) throws IOException {
        out.writeInt(value.size());
        for (Map<LocalTime, Long> day : value) {
            out.writeInt(day.size());
            for (Map.Entry<LocalTime, Long> e : day.entrySet()) {
                writeTime(out, e.getKey());
                out.writeLong(e.getValue());
            }
        }
    }

    private static List<Map<LocalTime, Long>> readVolumes(DataInputStream in) throws IOException {
        int days = in.readInt();
        List<Map<LocalTime, Long>> value = new ArrayList<>(days);
        for (int d = 0; d < days; d++) {
            int n = in.readInt();
            Map<LocalTime, Long> day = new TreeMap<>();
            for (int i = 0; i < n; i++) {
                day.put(readTime(in), in.readLong());
            }
            value.add(day);
        }
        return value;
    }

    private static void writeBars(DataOutputStream out, List<List<MinuteBar>> value) throws IOException {
        out.writeInt(value.size());
        for (List<MinuteBar> day : value) {
            out.writeInt(day.size());
            for (MinuteBar bar : day) {
                writeInstant(out, bar.start());
                writeDouble(out, bar.open());
                writeDouble(out, bar.high());
                writeDouble(out, bar.low());
                writeDouble(out, bar.close());
            }
        }
    }

    private static List<List<MinuteBar>> readBars(DataInputStream in) throws IOException {
        int days = in.readInt();
        List<List<MinuteBar>> value = new ArrayList<>(days);
        for (int d = 0; d < days; d++) {
            int n = in.readInt();
            List<MinuteBar> day = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                day.add(new MinuteBar(readInstant(in), readDouble(in), readDouble(in), readDouble(in), readDouble(in)));
            }
            value.add(List.copyOf(day));
        }
        return value;
    }

    private static void writeIv(DataOutputStream out, List<IvSession> value) throws IOException {
        out.writeInt(value.size());
        for (IvSession iv : value) {
            out.writeLong(iv.session().toEpochDay());
            out.writeBoolean(iv.expiryDay());
            out.writeInt(iv.atmIvByMinute().size());
            for (Map.Entry<LocalTime, Double> e : iv.atmIvByMinute().entrySet()) {
                writeTime(out, e.getKey());
                writeDouble(out, e.getValue());
            }
        }
    }

    private static List<IvSession> readIv(DataInputStream in) throws IOException {
        int days = in.readInt();
        List<IvSession> value = new ArrayList<>(days);
        for (int d = 0; d < days; d++) {
            LocalDate session = LocalDate.ofEpochDay(in.readLong());
            boolean expiry = in.readBoolean();
            int n = in.readInt();
            Map<LocalTime, Double> minutes = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                minutes.put(readTime(in), readDouble(in));
            }
            value.add(new IvSession(session, expiry, Map.copyOf(minutes)));
        }
        return value;
    }

    // ------------------------------------------------------------------------------------------ file store

    /** One file per answer under {@code dir}, written to a temporary file and moved into place (atomic). */
    public static final class FileStore implements Store {

        private final Path dir;

        public FileStore(Path dir) {
            this.dir = dir;
        }

        private Path path(String key) {
            return dir.resolve(key.substring(0, 2)).resolve(key + ".bin");
        }

        @Override
        public byte[] get(String key) {
            try {
                return Files.readAllBytes(path(key));
            } catch (NoSuchFileException e) {
                return null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void put(String key, byte[] value) {
            Path target = path(key);
            try {
                Files.createDirectories(target.getParent());
                Path tmp = Files.createTempFile(target.getParent(), key.substring(0, 8), ".tmp");
                try {
                    Files.write(tmp, value);
                    try {
                        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(tmp);           // gone after a move; removed when the write or move failed
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
