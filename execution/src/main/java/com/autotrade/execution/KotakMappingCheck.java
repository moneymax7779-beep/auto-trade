package com.autotrade.execution;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.kotak.KotakInstruments;

/**
 * The morning instrument check for Kotak Neo (as {@link KiteMappingCheck} for Kite; that class is left as it is): every
 * index option in our contract master (the newest ref.instrument snapshot of each exchange, expiring within
 * {@link KiteMappingCheck#DAYS} days) against Kotak's scrip master row with the same {@code pSymbol}. Expiry, strike,
 * option type, lot size and tick must agree. This is what proves (or disproves, by blocking) the UNCONFIRMED assumptions
 * of {@link KotakInstruments}: pSymbol = exchange token, the nse_fo expiry offset, strike and tick in paise. Only
 * contracts that passed stay in Kotak's map; one that disagrees, and every Kotak contract the check could not compare,
 * is taken out, so any order in it is refused as not listed. A wrong mapping buys the wrong option, so it blocks.
 */
public final class KotakMappingCheck {

    /**
     * What the check found: contracts compared, ours not at Kotak, those that disagree (taken out), and how many of
     * Kotak's contracts could not be compared (taken out too).
     */
    public record Result(int checked, List<String> missing, List<String> mismatched, int unchecked) {

        public String summary() {
            return checked + " contracts checked, " + missing.size() + " not at Kotak, " + unchecked
                    + " of Kotak's not comparable (not tradable), " + mismatched.size()
                    + " disagree" + (mismatched.isEmpty() ? "" : " (blocked: " + String.join("; ",
                    mismatched.subList(0, Math.min(5, mismatched.size()))) + (mismatched.size() > 5 ? "; ..." : "") + ")");
        }
    }

    private KotakMappingCheck() {
    }

    public static Result apply(KotakInstruments kotak, JdbcTemplate jdbc, LocalDate day) {
        List<Map<String, Object>> ours = jdbc.queryForList("select i.instrument_key, i.trading_symbol, i.expiry, i.strike, "
                + "i.type, i.lot_size, i.tick_size from ref.instrument i where i.snapshot_id in (select distinct on "
                + "(split_part(s.source, '-', 1)) s.id from ref.instrument_snapshot s where s.snapshot_date <= ? order by "
                + "split_part(s.source, '-', 1), s.snapshot_date desc, s.id desc) and i.type in ('CE', 'PE') "
                + "and i.segment in ('NSE_FO', 'BSE_FO') and i.expiry between ? and ?", day, day,
                day.plusDays(KiteMappingCheck.DAYS));
        List<String> missing = new ArrayList<>(), mismatched = new ArrayList<>();
        Set<KotakInstruments.Contract> agreed = new HashSet<>();
        for (Map<String, Object> row : ours) {
            String key = (String) row.get("instrument_key");
            Optional<KotakInstruments.Contract> c = kotak.byInstrumentKey(key);
            if (c.isEmpty()) {
                missing.add(key);
                continue;
            }
            String why = difference(c.get(), row);
            if (why == null) {
                agreed.add(c.get());
            } else {
                mismatched.add(row.get("trading_symbol") + " (" + key + "): " + why);
            }
        }
        int removed = 0;
        for (KotakInstruments.Contract c : kotak.contracts()) {
            if (!agreed.contains(c)) {
                kotak.remove(c);
                removed++;
            }
        }
        return new Result(ours.size(), missing, mismatched, removed - mismatched.size());
    }

    /** Why Kotak's contract is not ours, or null when they agree. */
    static String difference(KotakInstruments.Contract c, Map<String, Object> ours) {
        LocalDate expiry = ((java.sql.Date) ours.get("expiry")).toLocalDate();
        if (!expiry.equals(c.expiry())) {
            return "expiry " + c.expiry() + " at Kotak, " + expiry + " ours";
        }
        double strike = ((Number) ours.get("strike")).doubleValue();
        if (Math.abs(strike - c.strike()) > 1e-6) {
            return "strike " + c.strike() + " at Kotak, " + strike + " ours";
        }
        if (!Objects.equals(ours.get("type"), c.type())) {
            return "type " + c.type() + " at Kotak, " + ours.get("type") + " ours";
        }
        int lot = ((Number) ours.get("lot_size")).intValue();
        if (lot != c.lotSize()) {
            return "lot " + c.lotSize() + " at Kotak, " + lot + " ours";
        }
        double tick = ((Number) ours.get("tick_size")).doubleValue();
        if (Math.abs(tick - c.tickSize()) > 1e-9) {
            return "tick " + c.tickSize() + " at Kotak, " + tick + " ours";
        }
        return null;
    }
}
