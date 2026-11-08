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

import com.autotrade.upstox.orders.UpstoxInstruments;

/**
 * The morning instrument check for Upstox (as {@link KiteMappingCheck} and {@link KotakMappingCheck}): every index option
 * in our contract master (the newest ref.instrument snapshot of each exchange, expiring within
 * {@link KiteMappingCheck#DAYS} days) against the contract with the same key in Upstox's instrument file of the day.
 * <p>
 * The key needs no mapping (ours is Upstox's {@code instrument_key}, and ref.instrument is loaded from Upstox's own file),
 * but the check is not a formality: it catches a contract master that is not today's (a stale snapshot, a lot size
 * change, an exchange token reused after its contract expired, a contract added since) and a unit error in either load.
 * Expiry, strike, option type, lot size and tick must agree. Only contracts that passed stay in the list the gateway
 * trades from; one that disagrees, and every Upstox contract the check could not compare, is taken out, so any order in
 * it is refused as not listed. A wrong contract buys the wrong option, so it blocks.
 */
public final class UpstoxMappingCheck {

    /**
     * What the check found: contracts compared, ours not at Upstox, those that disagree (taken out), and how many of
     * Upstox's contracts could not be compared (taken out too).
     */
    public record Result(int checked, List<String> missing, List<String> mismatched, int unchecked) {

        public String summary() {
            return checked + " contracts checked, " + missing.size() + " not at Upstox, " + unchecked
                    + " of Upstox's not comparable (not tradable), " + mismatched.size()
                    + " disagree" + (mismatched.isEmpty() ? "" : " (blocked: " + String.join("; ",
                    mismatched.subList(0, Math.min(5, mismatched.size()))) + (mismatched.size() > 5 ? "; ..." : "") + ")");
        }
    }

    private UpstoxMappingCheck() {
    }

    public static Result apply(UpstoxInstruments upstox, JdbcTemplate jdbc, LocalDate day) {
        List<Map<String, Object>> ours = jdbc.queryForList("select i.instrument_key, i.trading_symbol, i.expiry, i.strike, "
                + "i.type, i.lot_size, i.tick_size from ref.instrument i where i.snapshot_id in (select distinct on "
                + "(split_part(s.source, '-', 1)) s.id from ref.instrument_snapshot s where s.snapshot_date <= ? order by "
                + "split_part(s.source, '-', 1), s.snapshot_date desc, s.id desc) and i.type in ('CE', 'PE') "
                + "and i.segment in ('NSE_FO', 'BSE_FO') and i.expiry between ? and ?", day, day,
                day.plusDays(KiteMappingCheck.DAYS));
        List<String> missing = new ArrayList<>(), mismatched = new ArrayList<>();
        Set<UpstoxInstruments.Contract> agreed = new HashSet<>();
        for (Map<String, Object> row : ours) {
            String key = (String) row.get("instrument_key");
            Optional<UpstoxInstruments.Contract> c = upstox.byInstrumentKey(key);
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
        for (UpstoxInstruments.Contract c : upstox.contracts()) {
            if (!agreed.contains(c)) {
                upstox.remove(c);
                removed++;
            }
        }
        return new Result(ours.size(), missing, mismatched, removed - mismatched.size());
    }

    /** Why Upstox's contract of today is not ours, or null when they agree. */
    static String difference(UpstoxInstruments.Contract c, Map<String, Object> ours) {
        LocalDate expiry = ((java.sql.Date) ours.get("expiry")).toLocalDate();
        if (!expiry.equals(c.expiry())) {
            return "expiry " + c.expiry() + " at Upstox, " + expiry + " ours";
        }
        double strike = ((Number) ours.get("strike")).doubleValue();
        if (Math.abs(strike - c.strike()) > 1e-6) {
            return "strike " + c.strike() + " at Upstox, " + strike + " ours";
        }
        if (!Objects.equals(ours.get("type"), c.type())) {
            return "type " + c.type() + " at Upstox, " + ours.get("type") + " ours";
        }
        int lot = ((Number) ours.get("lot_size")).intValue();
        if (lot != c.lotSize()) {
            return "lot " + c.lotSize() + " at Upstox, " + lot + " ours";
        }
        double tick = ((Number) ours.get("tick_size")).doubleValue();
        if (Math.abs(tick - c.tickSize()) > 1e-9) {
            return "tick " + c.tickSize() + " at Upstox, " + tick + " ours";
        }
        return null;
    }
}
