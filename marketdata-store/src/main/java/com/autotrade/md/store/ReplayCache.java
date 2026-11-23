package com.autotrade.md.store;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.sql.DataSource;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionPhaseEvent;

/**
 * Replay-only cache of a day's ordered event stream (user, 22 Nov 2026: every replay sorted the whole day's option
 * ticks inside postgres, about 4 GB on disk per run, repeated for every arm of every test). The first replay of a set
 * of loads writes the exact stream it delivered to a file while delivering it; the file is moved into place only when
 * the stream ended normally, so a cut-short run leaves nothing behind. Later replays of the same loads read the file.
 *
 * <p>The file name is a fingerprint of: the load ids in order; each load's status, finish time and loaded row counts;
 * the insert/update/delete counters of the md chunks around the session date (any reload, repair, compression or
 * decompression of that day's data changes them); and the bytes of the classes that read and order the stream and
 * of the event classes. Anything that could change the stream therefore changes the name, and an old file is simply
 * never read again. Every field of every event is stored losslessly (doubles as raw bits, nulls kept).
 */
public final class ReplayCache {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ReplayCache.class);
    private static final int FORMAT = 1;
    private static final int END = 0, INDEX = 1, FUTURE = 2, OPTION = 3, CONSTITUENT = 4, PHASE = 5, AUCTION = 6;
    private static final List<Class<?>> CODE = List.of(ReplayReader.class, EventStreams.class, EventCursor.class,
            ReorderingCursor.class, StoredSessionSource.class, LoadManifests.class, ReplayCache.class, MarketEvent.class,
            IndexTick.class, FutureTick.class, OptionTick.class, ConstituentTick.class, SessionPhaseEvent.class,
            AuctionTick.class, DepthLevels.class);

    /** The uncached stream, e.g. {@code ReplayReader::replay}. */
    public interface Source {
        long replay(List<Long> manifestIds, Consumer<MarketEvent> sink) throws Exception;
    }

    /** The cache key of a day's loads; the database form unless a test supplies one. */
    interface Fingerprinter {
        String of(LocalDate session, List<Long> sortedManifestIds) throws Exception;
    }

    private static final Duration STALE_TMP = Duration.ofHours(6);

    private final Path dir;
    private final Fingerprinter fingerprinter;

    public ReplayCache(Path dir, DataSource dataSource) {
        this(dir, (session, ids) -> fingerprint(dataSource, session, ids));
    }

    ReplayCache(Path dir, Fingerprinter fingerprinter) {
        this.dir = dir;
        this.fingerprinter = fingerprinter;
    }

    /**
     * The stream of {@code manifestIds} into {@code sink}, from the cache file or through {@code reader}. The cache never
     * stops a replay that would have run without it: a fingerprint, set-up, write or rename failure falls back to (or
     * finishes) the database stream. A cache file that cannot be read is deleted; if that happens before any event was
     * delivered the database stream is used instead, after the first event the replay fails loudly (rerun it).
     */
    public long replay(LocalDate session, List<Long> manifestIds, Source reader, Consumer<MarketEvent> sink)
            throws Exception {
        String fingerprint;
        try {
            fingerprint = fingerprinter.of(session, manifestIds.stream().sorted().toList());
        } catch (Exception e) {
            LOG.warn("tick cache off for this run: no fingerprint ({})", e.toString());
            return reader.replay(manifestIds, sink);
        }
        Path file = dir.resolve(session + "-" + fingerprint.substring(0, 32) + ".events.gz");
        if (Files.isRegularFile(file)) {
            long started = System.nanoTime();
            long[] delivered = {0};
            long n = -1;
            try {
                n = read(file, fingerprint, event -> {
                    delivered[0]++;
                    sink.accept(event);
                });
            } catch (IOException e) {
                Files.deleteIfExists(file);
                if (delivered[0] > 0) {
                    throw new IOException("tick cache " + file.getFileName() + " failed after " + delivered[0]
                            + " events (file removed; rerun the replay)", e);
                }
                LOG.warn("tick cache file {} unreadable ({}); removed, reading the database", file.getFileName(), e.toString());
            }
            if (n >= 0) {
                LOG.info("tick cache hit: {} events from {} in {} s", n, file.getFileName(),
                        (System.nanoTime() - started) / 1_000_000_000);
                return n;
            }
            if (delivered[0] == 0 && Files.isRegularFile(file)) {
                LOG.warn("tick cache file {} has another fingerprint; reading the database", file.getFileName());
            }
        }
        Path tmp;
        Writer writer;
        try {
            Files.createDirectories(dir);
            removeStaleTemps();
            tmp = Files.createTempFile(dir, session + "-", ".tmp");
            writer = new Writer(tmp, fingerprint);
        } catch (IOException | RuntimeException e) {
            LOG.warn("tick cache not written this run ({}); reading the database", e.toString());
            return reader.replay(manifestIds, sink);
        }
        long n;
        try {
            n = reader.replay(manifestIds, event -> {
                writer.write(event);
                sink.accept(event);
            });
        } catch (Exception | Error e) {
            writer.abandon();
            throw e;
        }
        if (writer.finish(n)) {
            try {
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                LOG.info("tick cache stored: {} events in {}", n, file.getFileName());
            } catch (IOException e) {
                LOG.warn("tick cache file not moved into place ({}); dropped", e.toString());
                Files.deleteIfExists(tmp);
            }
        }
        return n;
    }

    /** Temporary files left by killed runs (older than six hours); best effort. */
    private void removeStaleTemps() {
        Instant cutoff = Instant.now().minus(STALE_TMP);
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".tmp")).toList()) {
                try {
                    if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(f);
                    }
                } catch (IOException ignored) {
                    // another process may be finishing it
                }
            }
        } catch (IOException ignored) {
            // nothing to clean
        }
    }

    // ------------------------------------------------------------------------------------------ fingerprint

    /**
     * v2 (review of 24 Nov 2026): the sorted load ids, the database's start and statistics-reset times (chunk counters
     * restart from zero after a crash), and only the md chunks that overlap the session's IST day.
     */
    static String fingerprint(DataSource dataSource, LocalDate session, List<Long> manifestIds)
            throws SQLException, IOException {
        StringBuilder text = new StringBuilder("ticks-fp2|").append(session).append('|')
                .append(manifestIds).append('|');
        for (Class<?> type : CODE) {
            appendClassHash(text, type);
        }
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("select pg_postmaster_start_time()::text || ':' || coalesce("
                    + "(select stats_reset::text from pg_stat_database where datname = current_database()), '')")) {
                text.append("server=").append(one(s)).append('|');
            }
            try (PreparedStatement s = c.prepareStatement("select coalesce(string_agg(id || ':' || status || ':' || "
                    + "coalesce(finished_at::text, '') || ':' || loaded_counts::text, ';' order by id), '') "
                    + "from md.load_manifest where id = any(?)")) {
                s.setArray(1, c.createArrayOf("bigint", manifestIds.toArray()));
                text.append("loads=").append(one(s)).append('|');
            }
            try (PreparedStatement s = c.prepareStatement("select coalesce(sum(n_tup_ins + n_tup_upd + n_tup_del), 0) "
                    + "|| ':' || count(*) from pg_stat_user_tables where relid in (select format('%I.%I', chunk_schema, "
                    + "chunk_name)::regclass from timescaledb_information.chunks where hypertable_schema = 'md' "
                    + "and range_start < ((?::date + 1)::timestamp at time zone 'Asia/Kolkata') "
                    + "and range_end > (?::date::timestamp at time zone 'Asia/Kolkata'))")) {
                s.setObject(1, session);
                s.setObject(2, session);
                text.append("chunks=").append(one(s));
            }
        }
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String one(PreparedStatement s) throws SQLException {
        try (ResultSet rs = s.executeQuery()) {
            rs.next();
            return String.valueOf(rs.getString(1));
        }
    }

    /** The class's bytes and, recursively, its nested classes' (a class compiled from the same source matches). */
    private static void appendClassHash(StringBuilder text, Class<?> type) throws IOException {
        String name = type.getName();
        try (InputStream in = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            if (in == null) {
                throw new IOException("class bytes not found for " + name);
            }
            text.append(name).append('=').append(sha256(in.readAllBytes())).append('|');
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            appendClassHash(text, nested);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------ file format

    /** Streams the file into {@code sink}; -1 (nothing delivered) when its header names another fingerprint. */
    static long read(Path file, String fingerprint, Consumer<MarketEvent> sink) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file), 1 << 16), 1 << 16))) {
            if (in.readInt() != FORMAT || !in.readUTF().equals(fingerprint)) {
                return -1;
            }
            long n = 0;
            while (true) {
                int tag = in.readUnsignedByte();
                if (tag == END) {
                    long written = in.readLong();
                    if (written != n) {
                        throw new IOException("tick cache " + file + ": " + n + " events read, " + written + " written");
                    }
                    return n;
                }
                sink.accept(readEvent(tag, in));
                n++;
            }
        } catch (EOFException e) {
            throw new IOException("tick cache " + file + " ends early", e);
        }
    }

    /** Writes events as they pass; a write failure stops writing (the file is then dropped), never the replay. */
    static final class Writer {
        private final Path tmp;
        private final java.io.FileOutputStream file;
        private final FastGzip gzip;
        private DataOutputStream out;
        private long written;

        Writer(Path tmp, String fingerprint) throws IOException {
            this.tmp = tmp;
            java.io.FileOutputStream f = new java.io.FileOutputStream(tmp.toFile());
            FastGzip g = null;
            try {
                g = new FastGzip(f);
                out = new DataOutputStream(new BufferedOutputStream(g, 1 << 16));
                out.writeInt(FORMAT);
                out.writeUTF(fingerprint);
            } catch (IOException | RuntimeException e) {
                f.close();
                Files.deleteIfExists(tmp);
                throw e;
            }
            this.file = f;
            this.gzip = g;
        }

        void write(MarketEvent event) {
            if (out == null) {
                return;
            }
            try {
                writeEvent(out, event);
                written++;
            } catch (IOException | RuntimeException e) {
                LOG.warn("tick cache write failed ({}); this run's file is dropped", e.toString());
                abandon();
            }
        }

        /** Completes, syncs to disk and closes the file; true when it can be moved into place. */
        boolean finish(long delivered) {
            if (out == null) {
                return false;
            }
            if (written != delivered) {
                LOG.warn("tick cache: {} events written, {} delivered; file dropped", written, delivered);
                abandon();
                return false;
            }
            try {
                out.writeByte(END);
                out.writeLong(written);
                out.flush();
                gzip.finish();
                file.flush();
                file.getChannel().force(true);       // on disk before the rename, so a crash cannot leave a short file
                out.close();
                out = null;
                return true;
            } catch (IOException e) {
                LOG.warn("tick cache close failed ({}); file dropped", e.toString());
                abandon();
                return false;
            }
        }

        void abandon() {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // dropped anyway
                }
                out = null;
            }
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // a stray .tmp file is never read and is removed after six hours
            }
        }
    }

    /** gzip at the fastest level: the file is read back soon and often, written once. */
    private static final class FastGzip extends GZIPOutputStream {
        FastGzip(OutputStream out) throws IOException {
            super(out, 1 << 16);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }

    static void writeEvent(DataOutputStream out, MarketEvent event) throws IOException {
        switch (event) {
            case IndexTick t -> {
                out.writeByte(INDEX);
                instant(out, t.receivedAt());
                instant(out, t.exchangeTime());
                string(out, t.underlying());
                out.writeLong(t.sourceSequence());
                dbl(out, t.price());
                dbl(out, t.previousClose());
                integer(out, t.atmStrike());
            }
            case FutureTick t -> {
                out.writeByte(FUTURE);
                instant(out, t.receivedAt());
                instant(out, t.exchangeTime());
                string(out, t.underlying());
                out.writeLong(t.sourceSequence());
                out.writeLong(t.instrumentToken());
                string(out, t.symbol());
                date(out, t.expiry());
                dbl(out, t.price());
                lng(out, t.cumulativeVolume());
                dbl(out, t.openInterest());
                dbl(out, t.sessionVwap());
                dbl(out, t.totalBuyQuantity());
                dbl(out, t.totalSellQuantity());
                depth(out, t.bids());
                depth(out, t.asks());
            }
            case OptionTick t -> {
                out.writeByte(OPTION);
                instant(out, t.receivedAt());
                instant(out, t.exchangeTime());
                string(out, t.underlying());
                out.writeLong(t.sourceSequence());
                out.writeLong(t.instrumentToken());
                string(out, t.symbol());
                date(out, t.expiry());
                dbl(out, t.strike());
                out.writeByte(t.optionType() == null ? 0 : t.optionType().ordinal() + 1);
                integer(out, t.lotSize());
                dbl(out, t.lastPrice());
                lng(out, t.cumulativeVolume());
                dbl(out, t.openInterest());
                dbl(out, t.totalBuyQuantity());
                dbl(out, t.totalSellQuantity());
                depth(out, t.bids());
                depth(out, t.asks());
                dbl(out, t.impliedVolatility());
                dbl(out, t.delta());
                dbl(out, t.gamma());
                dbl(out, t.theta());
                dbl(out, t.vega());
                dbl(out, t.rho());
                instant(out, t.analyticsTime());
                out.writeBoolean(t.analyticsComplete());
                out.writeBoolean(t.depthComplete());
            }
            case ConstituentTick t -> {
                out.writeByte(CONSTITUENT);
                instant(out, t.receivedAt());
                instant(out, t.exchangeTime());
                string(out, t.underlying());
                out.writeLong(t.sourceSequence());
                out.writeLong(t.instrumentToken());
                string(out, t.symbol());
                dbl(out, t.price());
                dbl(out, t.previousClose());
                lng(out, t.cumulativeVolume());
                dbl(out, t.sessionVwap());
                dbl(out, t.weightPercent());
            }
            case SessionPhaseEvent t -> {
                out.writeByte(PHASE);
                instant(out, t.receivedAt());
                instant(out, t.eventTime());
                string(out, t.underlying());
                string(out, t.sessionPhase());
                string(out, t.priceSemantics());
                dbl(out, t.price());
                out.writeBoolean(t.officialFinal());
            }
            case AuctionTick t -> {
                out.writeByte(AUCTION);
                instant(out, t.receivedAt());
                instant(out, t.exchangeTime());
                string(out, t.underlying());
                out.writeLong(t.instrumentToken());
                string(out, t.symbol());
                dbl(out, t.indicativePrice());
                dbl(out, t.referencePrice());
                out.writeLong(t.equilibriumQuantity());
                out.writeLong(t.imbalanceTotal());
                out.writeLong(t.imbalanceMarket());
                out.writeBoolean(t.casEligible());
            }
        }
    }

    static MarketEvent readEvent(int tag, DataInputStream in) throws IOException {
        return switch (tag) {
            case INDEX -> new IndexTick(instant(in), instant(in), string(in), in.readLong(), d0(in), dblN(in),
                    integer(in));
            case FUTURE -> new FutureTick(instant(in), instant(in), string(in), in.readLong(), in.readLong(), string(in),
                    date(in), d0(in), lng(in), dblN(in), dblN(in), dblN(in), dblN(in), depth(in), depth(in));
            case OPTION -> {
                Instant received = instant(in);
                Instant exchange = instant(in);
                String underlying = string(in);
                long seq = in.readLong();
                long token = in.readLong();
                String symbol = string(in);
                LocalDate expiry = date(in);
                double strike = d0(in);
                int type = in.readUnsignedByte();
                yield new OptionTick(received, exchange, underlying, seq, token, symbol, expiry, strike,
                        type == 0 ? null : OptionTick.OptionType.values()[type - 1], integer(in), d0(in), lng(in),
                        dblN(in), dblN(in), dblN(in), depth(in), depth(in), dblN(in), dblN(in), dblN(in), dblN(in),
                        dblN(in), dblN(in), instant(in), in.readBoolean(), in.readBoolean());
            }
            case CONSTITUENT -> new ConstituentTick(instant(in), instant(in), string(in), in.readLong(), in.readLong(),
                    string(in), d0(in), dblN(in), lng(in), dblN(in), dblN(in));
            case PHASE -> new SessionPhaseEvent(instant(in), instant(in), string(in), string(in), string(in),
                    d0(in), in.readBoolean());
            case AUCTION -> new AuctionTick(instant(in), instant(in), string(in), in.readLong(), string(in),
                    d0(in), d0(in), in.readLong(), in.readLong(), in.readLong(), in.readBoolean());
            default -> throw new IOException("unknown event tag " + tag);
        };
    }

    // -------------------------------------------------------------------------- field codecs (nulls kept)

    private static void instant(DataOutputStream out, Instant t) throws IOException {
        out.writeBoolean(t != null);
        if (t != null) {
            out.writeLong(t.getEpochSecond());
            out.writeInt(t.getNano());
        }
    }

    private static Instant instant(DataInputStream in) throws IOException {
        return in.readBoolean() ? Instant.ofEpochSecond(in.readLong(), in.readInt()) : null;
    }

    private static void date(DataOutputStream out, LocalDate d) throws IOException {
        out.writeBoolean(d != null);
        if (d != null) {
            out.writeLong(d.toEpochDay());
        }
    }

    private static LocalDate date(DataInputStream in) throws IOException {
        return in.readBoolean() ? LocalDate.ofEpochDay(in.readLong()) : null;
    }

    private static void string(DataOutputStream out, String s) throws IOException {
        out.writeBoolean(s != null);
        if (s != null) {
            out.writeUTF(s);
        }
    }

    private static String string(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    private static void dbl(DataOutputStream out, double v) throws IOException {
        out.writeLong(Double.doubleToRawLongBits(v));
    }

    private static void dbl(DataOutputStream out, Double v) throws IOException {
        out.writeBoolean(v != null);
        if (v != null) {
            out.writeLong(Double.doubleToRawLongBits(v));
        }
    }

    private static double d0(DataInputStream in) throws IOException {
        return Double.longBitsToDouble(in.readLong());
    }

    private static Double dblN(DataInputStream in) throws IOException {
        return in.readBoolean() ? Double.longBitsToDouble(in.readLong()) : null;
    }

    private static void lng(DataOutputStream out, Long v) throws IOException {
        out.writeBoolean(v != null);
        if (v != null) {
            out.writeLong(v);
        }
    }

    private static Long lng(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readLong() : null;
    }

    private static void integer(DataOutputStream out, Integer v) throws IOException {
        out.writeBoolean(v != null);
        if (v != null) {
            out.writeInt(v);
        }
    }

    private static Integer integer(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readInt() : null;
    }

    private static void depth(DataOutputStream out, DepthLevels d) throws IOException {
        if (d == null) {
            out.writeInt(-1);
            return;
        }
        int n = d.size();
        out.writeInt(n);
        for (int i = 0; i < n; i++) {
            out.writeLong(Double.doubleToRawLongBits(d.price(i)));
            out.writeLong(d.quantity(i));
            out.writeInt(d.orders(i));
        }
    }

    private static DepthLevels depth(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0) {
            return null;
        }
        double[] prices = new double[n];
        long[] quantities = new long[n];
        int[] orders = new int[n];
        for (int i = 0; i < n; i++) {
            prices[i] = Double.longBitsToDouble(in.readLong());
            quantities[i] = in.readLong();
            orders[i] = in.readInt();
        }
        return DepthLevels.of(prices, quantities, orders);
    }

    /** Package view for tests: every event of a file. */
    static List<MarketEvent> readAll(Path file, String fingerprint) throws IOException {
        List<MarketEvent> events = new ArrayList<>();
        read(file, fingerprint, events::add);
        return events;
    }
}
