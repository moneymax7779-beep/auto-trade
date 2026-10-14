package com.autotrade.trading;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autotrade.core.history.ParticipantOi;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.cues.CueState;
import com.autotrade.features.snapshot.CueFeatures;
import com.autotrade.md.store.ReferenceStore;

/**
 * Pre-open and positioning cues for the UI (feature/missing-data): the v13 cue features as of now (or the end of
 * {@code date}), and participant-wise index futures positioning of the last published sessions.
 */
@RestController
@RequestMapping("/api")
class CuesController {

    private final ReferenceStore store;
    private final CueScheduler scheduler;

    CuesController(DataSource target, CueScheduler scheduler) {
        this.store = new ReferenceStore(target);
        this.scheduler = scheduler;
    }

    @GetMapping("/cues")
    Map<String, Object> cues(@RequestParam(required = false) String date) {
        LocalDate today = LocalDate.now(MarketTime.IST);
        LocalDate session = date == null ? today : LocalDate.parse(date);
        Instant asOf = session.equals(today) ? Instant.now()
                : session.atTime(15, 30).atZone(MarketTime.IST).toInstant();
        CueFeatures f = new CueState(session, store.participantOi(session, 2), () -> store.giftNiftyQuotes(session))
                .features(asOf);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", session.toString());
        out.put("asOf", asOf.toString());
        Map<String, Object> features = new LinkedHashMap<>();          // NaN (unknown) becomes null: valid JSON
        for (var c : CueFeatures.class.getRecordComponents()) {
            try {
                double v = (double) c.getAccessor().invoke(f);
                features.put(c.getName(), Double.isFinite(v) ? Math.round(v * 100) / 100.0 : null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        out.put("features", features);
        List<Map<String, Object>> positioning = new ArrayList<>();
        for (ParticipantOi p : store.participantOi(session.plusDays(1), 10)) {
            if ("TOTAL".equals(p.clientType())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("session", p.session().toString());
            row.put("type", p.clientType());
            row.put("futureIndexLong", p.futureIndexLong());
            row.put("futureIndexShort", p.futureIndexShort());
            row.put("futureIndexLongPct", Math.round(p.futureIndexLongPct() * 10) / 10.0);
            row.put("optionIndexCallNet", p.optionIndexCallNet());
            row.put("optionIndexPutNet", p.optionIndexPutNet());
            positioning.add(row);
        }
        out.put("positioning", positioning);
        out.put("collector", scheduler.status());
        return out;
    }
}
