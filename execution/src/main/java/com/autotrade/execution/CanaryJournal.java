package com.autotrade.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.account.BrokerAccount;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where a {@link Canary} is recorded (16 Nov 2026, D1): one {@code CANARY_STEP} row in exec.audit_log per step (its name,
 * timing, our status and the broker's raw status word), one {@code CANARY_RESULT} row with the outcome, and the whole
 * report as JSON under {@code <dir>/<account id>/<yyyy-MM-dd>/<tag>.json} (IST day). A failed write is logged and never
 * stops the canary (its cleanup matters more than its record); the report file is written atomically. Every row and the
 * file carry the run's id ({@code runId}, the admin endpoint's) so a run can be read back after a restart.
 * <p>
 * Something left at the broker ({@link Canary.Report#leftover()}: a fill not sold back, an order possibly still working
 * or of unknown outcome; review 16 Nov 2026) engages the account's kill switch (reason {@code CANARY: ...}) and writes a
 * {@code CANARY_LEFTOVER} row, which the execution alerts send on Telegram.
 */
public final class CanaryJournal implements Canary.Journal {

    private static final Logger log = LoggerFactory.getLogger(CanaryJournal.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HHmmss");
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /** The audit action of a leftover (sent by the execution alerts). */
    public static final String LEFTOVER = "CANARY_LEFTOVER";
    /** Every kill switch the canary engages has a reason starting with this. */
    public static final String REASON = "CANARY:";

    private final KillSwitches audit;
    private final BrokerAccount account;
    private final String actor;
    private final Path dir;
    private final String runId;
    private volatile Path written;

    /** @param dir where report files go (null: audit rows only) */
    public CanaryJournal(DataSource dataSource, BrokerAccount account, String actor, Path dir) {
        this(dataSource, account, actor, dir, null);
    }

    /** @param runId the run's id (null: none), in every row and the report file */
    public CanaryJournal(DataSource dataSource, BrokerAccount account, String actor, Path dir, String runId) {
        this.audit = new KillSwitches(dataSource);
        this.account = account;
        this.actor = actor;
        this.dir = dir;
        this.runId = runId;
    }

    @Override
    public void step(Canary.Step s) {
        Map<String, Object> d = view(s);
        if (runId != null) {
            d.put("runId", runId);
        }
        try {
            audit.audit(account, actor, "CANARY_STEP", d);
        } catch (RuntimeException e) {
            log.error("account {}: canary step {} not audited: {}", account.id(), s.name(), e.getMessage());
        }
    }

    /** The account switch's reason for what a canary left at the broker. */
    public static String leftoverReason(Canary.Report r) {
        List<String> what = new java.util.ArrayList<>();
        if (r.filledQuantity() > 0 && !r.soldBack()) {
            what.add("filled " + r.filledQuantity() + " of " + r.symbol() + " NOT sold back");
        }
        if (r.leftWorking()) {
            what.add("order " + r.tag() + " may still be working at the broker (or its outcome is unknown)");
        }
        return REASON + " " + String.join("; ", what) + " (" + r.instrumentKey() + "): check the broker and clear it by "
                + "hand, then release the switch";
    }

    /**
     * Something was left at the broker: the account's kill switch first (new entries stop; exits continue), then the
     * {@code CANARY_LEFTOVER} row the alerts send. Neither failure stops the other.
     */
    @Override
    public void leftover(Canary.Report r) {
        String reason = leftoverReason(r);
        try {
            audit.engageAccount(account, reason, actor);
        } catch (RuntimeException e) {
            log.error("account {}: CANARY LEFTOVER and the kill switch was NOT engaged: {}: {}", account.id(), reason,
                    e.getMessage());
        }
        Map<String, Object> d = new LinkedHashMap<>();
        if (runId != null) {
            d.put("runId", runId);
        }
        d.put("tag", r.tag());
        d.put("instrumentKey", r.instrumentKey());
        d.put("symbol", r.symbol());
        d.put("filledQuantity", r.filledQuantity());
        d.put("soldBack", r.soldBack());
        d.put("leftWorking", r.leftWorking());
        d.put("reason", reason);
        d.put("message", r.message());
        try {
            audit.audit(account, actor, LEFTOVER, d);
        } catch (RuntimeException e) {
            log.error("account {}: canary leftover not audited: {}", account.id(), e.getMessage());
        }
    }

    @Override
    public void result(Canary.Report r) {
        Path file = writeReport(r);
        Map<String, Object> d = new LinkedHashMap<>();
        if (runId != null) {
            d.put("runId", runId);
        }
        d.put("result", r.result().name());
        d.put("broker", r.broker());
        d.put("instrumentKey", r.instrumentKey());
        d.put("symbol", r.symbol());
        d.put("tag", r.tag());
        d.put("buyPrice", finite(r.buyPrice()));
        d.put("modifiedPrice", finite(r.modifiedPrice()));
        d.put("filledQuantity", r.filledQuantity());
        d.put("soldBack", r.soldBack());
        d.put("leftWorking", r.leftWorking());
        d.put("steps", r.steps().size());
        d.put("millis", r.finishedAt().toEpochMilli() - r.startedAt().toEpochMilli());
        d.put("message", r.message());
        d.put("report", file == null ? null : file.toString());
        try {
            audit.audit(account, actor, "CANARY_RESULT", d);
        } catch (RuntimeException e) {
            log.error("account {}: canary result not audited: {}", account.id(), e.getMessage());
        }
    }

    /** The report file of the last result (null: none written). */
    public Path reportFile() {
        return written;
    }

    private Path writeReport(Canary.Report r) {
        if (dir == null) {
            return null;
        }
        try {
            Path day = dir.resolve(Long.toString(account.id())).resolve(r.startedAt().atZone(IST).toLocalDate().toString());
            Files.createDirectories(day);
            String name = (r.tag() != null ? r.tag() : "canary-" + STAMP.format(r.startedAt().atZone(IST))) + "-"
                    + r.result().name().toLowerCase(java.util.Locale.ROOT) + ".json";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("account", account.id());
            body.put("accountName", account.displayName());
            body.put("actor", actor);
            body.put("runId", runId);
            body.put("report", view(r));
            Path tmp = Files.createTempFile(day, ".canary-", ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(body), StandardCharsets.UTF_8);
            Path file = Files.move(tmp, day.resolve(name), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            written = file;
            return file;
        } catch (IOException | RuntimeException e) {
            log.error("account {}: canary report not written under {}: {}", account.id(), dir, e.getMessage());
            return null;
        }
    }

    /** The report as plain values (NaN prices as null: JSON has no NaN). */
    public static Map<String, Object> view(Canary.Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", r.result().name());
        m.put("message", r.message());
        m.put("broker", r.broker());
        m.put("instrumentKey", r.instrumentKey());
        m.put("symbol", r.symbol());
        m.put("lotSize", r.lotSize());
        m.put("tag", r.tag());
        m.put("bid", finite(r.bid()));
        m.put("ask", finite(r.ask()));
        m.put("buyPrice", finite(r.buyPrice()));
        m.put("modifiedPrice", finite(r.modifiedPrice()));
        m.put("funds", finite(r.funds()));
        m.put("filledQuantity", r.filledQuantity());
        m.put("soldBack", r.soldBack());
        m.put("leftWorking", r.leftWorking());
        m.put("startedAt", r.startedAt().toString());
        m.put("finishedAt", r.finishedAt().toString());
        m.put("leftover", r.leftover());
        m.put("steps", r.steps().stream().map(CanaryJournal::view).toList());
        return m;
    }

    /** One step as plain values (a mutable map). */
    public static Map<String, Object> view(Canary.Step s) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("n", s.n());
        x.put("step", s.name());
        x.put("at", s.at().toString());
        x.put("millis", s.millis());
        x.put("ok", s.ok());
        x.put("status", s.status());
        x.put("rawStatus", s.rawStatus());
        x.put("detail", s.detail());
        return x;
    }

    private static Double finite(double v) {
        return Double.isFinite(v) ? v : null;
    }
}
