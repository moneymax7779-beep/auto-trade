package com.autotrade.execution;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.zerodha.KiteInstruments;

/**
 * The morning instrument check (multi-broker plan section 3.1): every index option in our contract master (the newest
 * ref.instrument snapshot of each exchange, expiring within {@link #DAYS} days) against Kite's contract with the same
 * exchange token. Expiry, strike, option type, lot size and tick must agree; a contract that disagrees is taken out of
 * Kite's map, so any order in it is refused as not listed. A wrong mapping is the one bug that buys the wrong option,
 * so it blocks; it is never "fixed" by guessing.
 */
public final class KiteMappingCheck {

    static final int DAYS = 35;

    /** What the check found: contracts compared, ours not at Kite, and those that disagree (taken out). */
    public record Result(int checked, List<String> missing, List<String> mismatched) {

        public String summary() {
            return checked + " contracts checked, " + missing.size() + " not at Kite, " + mismatched.size()
                    + " disagree" + (mismatched.isEmpty() ? "" : " (blocked: " + String.join("; ",
                    mismatched.subList(0, Math.min(5, mismatched.size()))) + (mismatched.size() > 5 ? "; ..." : "") + ")");
        }
    }

    private KiteMappingCheck() {
    }

    public static Result apply(KiteInstruments kite, JdbcTemplate jdbc, LocalDate day) {
        List<Map<String, Object>> ours = jdbc.queryForList("select i.instrument_key, i.trading_symbol, i.expiry, i.strike, "
                + "i.type, i.lot_size, i.tick_size from ref.instrument i where i.snapshot_id in (select distinct on "
                + "(split_part(s.source, '-', 1)) s.id from ref.instrument_snapshot s where s.snapshot_date <= ? order by "
                + "split_part(s.source, '-', 1), s.snapshot_date desc, s.id desc) and i.type in ('CE', 'PE') "
                + "and i.segment in ('NSE_FO', 'BSE_FO') and i.expiry between ? and ?", day, day, day.plusDays(DAYS));
        List<String> missing = new ArrayList<>(), mismatched = new ArrayList<>();
        for (Map<String, Object> row : ours) {
            String key = (String) row.get("instrument_key");
            Optional<KiteInstruments.Contract> c = kite.byInstrumentKey(key);
            if (c.isEmpty()) {
                missing.add(key);
                continue;
            }
            String why = difference(c.get(), row);
            if (why != null) {
                kite.remove(c.get());
                mismatched.add(row.get("trading_symbol") + " (" + key + "): " + why);
            }
        }
        return new Result(ours.size(), missing, mismatched);
    }

    /** Why Kite's contract is not ours, or null when they agree. */
    static String difference(KiteInstruments.Contract c, Map<String, Object> ours) {
        LocalDate expiry = ((java.sql.Date) ours.get("expiry")).toLocalDate();
        if (!expiry.equals(c.expiry())) {
            return "expiry " + c.expiry() + " at Kite, " + expiry + " ours";
        }
        double strike = ((Number) ours.get("strike")).doubleValue();
        if (Math.abs(strike - c.strike()) > 1e-6) {
            return "strike " + c.strike() + " at Kite, " + strike + " ours";
        }
        if (!Objects.equals(ours.get("type"), c.type())) {
            return "type " + c.type() + " at Kite, " + ours.get("type") + " ours";
        }
        int lot = ((Number) ours.get("lot_size")).intValue();
        if (lot != c.lotSize()) {
            return "lot " + c.lotSize() + " at Kite, " + lot + " ours";
        }
        double tick = ((Number) ours.get("tick_size")).doubleValue();
        if (Math.abs(tick - c.tickSize()) > 1e-9) {
            return "tick " + c.tickSize() + " at Kite, " + tick + " ours";
        }
        return null;
    }
}
