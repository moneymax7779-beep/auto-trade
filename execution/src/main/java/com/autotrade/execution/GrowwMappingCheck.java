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

import com.autotrade.groww.GrowwInstruments;

/**
 * The morning instrument check for Groww (as {@link KiteMappingCheck}, {@link KotakMappingCheck} and
 * {@link UpstoxMappingCheck}): every index option in our contract master (the newest ref.instrument snapshot of each
 * exchange, expiring within {@link KiteMappingCheck#DAYS} days) against the contract with the same key in Groww's
 * instrument CSV of the day.
 * <p>
 * Groww's key is built ({@code NSE_FO|<exchange_token>}, {@link GrowwInstruments}), and that its {@code exchange_token} is
 * the exchange's token is UNCONFIRMED: this check is what proves it (or disproves it, by blocking). It also catches a
 * contract master that is not today's (a stale snapshot, a lot size change, an exchange token reused after its contract
 * expired) and a unit error in the CSV (strike, tick, expiry format). Expiry, strike, option type, lot size and tick must
 * agree. Only contracts that passed stay in the list the gateway trades from; one that disagrees, and every Groww
 * contract the check could not compare, is taken out, so any order in it is refused as not listed. A wrong mapping buys
 * the wrong option, so it blocks.
 */
public final class GrowwMappingCheck {

    /**
     * What the check found: contracts compared, ours not at Groww, those that disagree (taken out), and how many of
     * Groww's contracts could not be compared (taken out too).
     */
    public record Result(int checked, List<String> missing, List<String> mismatched, int unchecked) {

        public String summary() {
            return checked + " contracts checked, " + missing.size() + " not at Groww, " + unchecked
                    + " of Groww's not comparable (not tradable), " + mismatched.size()
                    + " disagree" + (mismatched.isEmpty() ? "" : " (blocked: " + String.join("; ",
                    mismatched.subList(0, Math.min(5, mismatched.size()))) + (mismatched.size() > 5 ? "; ..." : "") + ")");
        }
    }

    private GrowwMappingCheck() {
    }

    public static Result apply(GrowwInstruments groww, JdbcTemplate jdbc, LocalDate day) {
        List<Map<String, Object>> ours = jdbc.queryForList("select i.instrument_key, i.trading_symbol, i.expiry, i.strike, "
                + "i.type, i.lot_size, i.tick_size from ref.instrument i where i.snapshot_id in (select distinct on "
                + "(split_part(s.source, '-', 1)) s.id from ref.instrument_snapshot s where s.snapshot_date <= ? order by "
                + "split_part(s.source, '-', 1), s.snapshot_date desc, s.id desc) and i.type in ('CE', 'PE') "
                + "and i.segment in ('NSE_FO', 'BSE_FO') and i.expiry between ? and ?", day, day,
                day.plusDays(KiteMappingCheck.DAYS));
        List<String> missing = new ArrayList<>(), mismatched = new ArrayList<>();
        Set<GrowwInstruments.Contract> agreed = new HashSet<>();
        for (Map<String, Object> row : ours) {
            String key = (String) row.get("instrument_key");
            Optional<GrowwInstruments.Contract> c = groww.byInstrumentKey(key);
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
        for (GrowwInstruments.Contract c : groww.contracts()) {
            if (!agreed.contains(c)) {
                groww.remove(c);
                removed++;
            }
        }
        return new Result(ours.size(), missing, mismatched, removed - mismatched.size());
    }

    /** Why Groww's contract of today is not ours, or null when they agree. */
    static String difference(GrowwInstruments.Contract c, Map<String, Object> ours) {
        LocalDate expiry = ((java.sql.Date) ours.get("expiry")).toLocalDate();
        if (!expiry.equals(c.expiry())) {
            return "expiry " + c.expiry() + " at Groww, " + expiry + " ours";
        }
        double strike = ((Number) ours.get("strike")).doubleValue();
        if (Math.abs(strike - c.strike()) > 1e-6) {
            return "strike " + c.strike() + " at Groww, " + strike + " ours";
        }
        if (!Objects.equals(ours.get("type"), c.type())) {
            return "type " + c.type() + " at Groww, " + ours.get("type") + " ours";
        }
        int lot = ((Number) ours.get("lot_size")).intValue();
        if (lot != c.lotSize()) {
            return "lot " + c.lotSize() + " at Groww, " + lot + " ours";
        }
        double tick = ((Number) ours.get("tick_size")).doubleValue();
        if (Math.abs(tick - c.tickSize()) > 1e-9) {
            return "tick " + c.tickSize() + " at Groww, " + tick + " ours";
        }
        return null;
    }
}
