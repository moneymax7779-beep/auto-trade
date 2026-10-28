package com.autotrade.trading;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.sql.DataSource;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The database as the chart endpoints use it (PCR, OI tape, premium, surge), kept apart from the trading engine's
 * (28 Oct 2026: a chart's whole-day read of md.option_tick took ~3 minutes on the GCP VM, and several at once held all
 * six connections of the shared pool, so a request waited 30 s and timed out; a live session's writes would have waited
 * the same way).
 * <ul>
 *   <li>Its own pool of {@value #POOL} connections to the same database, without parallel query workers (they compete
 *       with the live engine for the VM's two cores and its disk), so a chart can never take a connection the engine needs.</li>
 *   <li>{@link #load} / {@link #save}: a complete day's chart data kept in md.chart_day_cache (V26), so it is computed
 *       from the ticks once, not again after every restart or deploy; a day whose ACTIVE load manifest changed since is
 *       computed again. Any failure here only means computing the day again: it is logged, never thrown.</li>
 * </ul>
 */
@Component
class ChartDb implements DisposableBean {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChartDb.class);

    static final int POOL = 3;

    private final DataSource dataSource;
    private final HikariDataSource own;
    private final JdbcTemplate jdbc;

    ChartDb(DataSource target) {
        if (target instanceof HikariDataSource engine && engine.getJdbcUrl() != null) {
            HikariConfig c = new HikariConfig();
            c.setJdbcUrl(engine.getJdbcUrl());
            c.setUsername(engine.getUsername());
            c.setPassword(engine.getPassword());
            c.setPoolName("charts");
            c.setMaximumPoolSize(POOL);
            c.setMinimumIdle(0);
            c.setInitializationFailTimeout(-1);               // start even if the database is not up yet, as the engine does
            c.setConnectionInitSql("set max_parallel_workers_per_gather = 0");
            own = new HikariDataSource(c);
            dataSource = own;
        } else {
            own = null;                                       // tests: the given data source as it is
            dataSource = target;
        }
        jdbc = new JdbcTemplate(dataSource);
    }

    DataSource dataSource() {
        return dataSource;
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    /** The kept payload of {@code kind} for the day, or null when none is kept or the day was loaded again since. */
    byte[] load(String kind, LocalDate day, String underlying) {
        try {
            List<byte[]> rows = jdbc.query("select c.payload from md.chart_day_cache c join md.load_manifest m "
                            + "on m.session_date = c.session_date and m.underlying = c.underlying and m.status = 'ACTIVE' "
                            + "and m.id = c.manifest_id where c.kind = ? and c.session_date = ? and c.underlying = ?",
                    (rs, i) -> rs.getBytes(1), kind, day, underlying);
            return rows.isEmpty() ? null : rows.getFirst();
        } catch (RuntimeException e) {
            log.warn("chart cache: cannot read {} {} {}: {}", kind, day, underlying, e.toString());
            return null;
        }
    }

    /** Keeps a complete day's payload against the day's ACTIVE load manifest (none: not kept). */
    void save(String kind, LocalDate day, String underlying, byte[] payload) {
        try {
            int n = jdbc.update("insert into md.chart_day_cache (kind, session_date, underlying, manifest_id, payload) "
                            + "select ?, ?, ?, m.id, ? from md.load_manifest m where m.session_date = ? and m.underlying = ? "
                            + "and m.status = 'ACTIVE' on conflict (kind, session_date, underlying) do update set "
                            + "manifest_id = excluded.manifest_id, payload = excluded.payload, created_at = now()",
                    kind, day, underlying, payload, day, underlying);
            log.info("chart cache: {} {} {} {} ({} bytes)", n == 1 ? "kept" : "not kept (no active manifest)", kind, day,
                    underlying, payload.length);
        } catch (RuntimeException e) {
            log.warn("chart cache: cannot keep {} {} {}: {}", kind, day, underlying, e.toString());
        }
    }

    @Override
    public void destroy() {
        if (own != null) {
            own.close();
        }
    }

    // ------------------------------------------------------------------ the payload encoding

    interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    static byte[] encode(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Null when the payload cannot be read (another format): the day is then computed again. */
    static <T> T decode(byte[] payload, Reader<T> reader) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))) {
            return reader.read(in);
        } catch (IOException | RuntimeException e) {
            log.warn("chart cache: unreadable payload: {}", e.toString());
            return null;
        }
    }

    static void writeDoubles(DataOutputStream out, double[] values) throws IOException {
        out.writeInt(values.length);
        for (double v : values) {
            out.writeDouble(v);
        }
    }

    static double[] readDoubles(DataInputStream in, int expected) throws IOException {
        int n = in.readInt();
        if (n != expected) {
            throw new IOException("length " + n + ", expected " + expected);
        }
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = in.readDouble();
        }
        return values;
    }
}
