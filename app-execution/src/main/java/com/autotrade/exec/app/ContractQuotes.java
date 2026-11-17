package com.autotrade.exec.app;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Captured option quotes of one contract, read through md.option_tick's contract index (underlying, expiry, strike, type,
 * time; V14) with the contract taken from ref.instrument by our instrument key (17 Nov 2026, positions review): a read by
 * instrument token alone has no index and scans every option tick of the window, which the Today page's 5 s polls of
 * every member's books would do all day. A contract not in ref.instrument falls back to the token read (time-bounded).
 */
final class ContractQuotes {

    /** A contract's place in the option tick index. */
    record Contract(java.sql.Date expiry, double strike, String type) {
    }

    private final JdbcTemplate jdbc;
    /** Known contracts by instrument key (a key's contract never changes; a missing one is looked up again). */
    private final Map<String, Contract> contracts = new ConcurrentHashMap<>();

    ContractQuotes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The contract of an instrument key, or null when ref.instrument does not have it (yet). */
    Contract contract(String key) {
        if (key == null) {
            return null;
        }
        Contract known = contracts.get(key);
        if (known != null) {
            return known;
        }
        Contract found = jdbc.queryForList("select expiry, strike, type from ref.instrument where instrument_key = ? "
                        + "and type in ('CE', 'PE') order by snapshot_id desc limit 1", key).stream()
                .map(r -> new Contract((java.sql.Date) r.get("expiry"), ((Number) r.get("strike")).doubleValue(),
                        (String) r.get("type")))
                .findFirst().orElse(null);
        if (found != null) {
            if (contracts.size() > 20_000) {
                contracts.clear();                              // a bound, not a policy
            }
            contracts.put(key, found);
        }
        return found;
    }

    /** The newest quote (bid, ask, ltp, delta, recv_ts) of the last {@code minutes} with a bid, or null. */
    Map<String, Object> latest(String key, String underlying, long token, int minutes) {
        Contract c = contract(key);
        List<Map<String, Object>> last = c == null
                ? jdbc.queryForList("select bid_px[1] bid, ask_px[1] ask, bid_qty[1] bid_qty, ltp, delta, recv_ts "
                        + "from md.option_tick where instrument_token = ? and recv_ts > now() - make_interval(mins => ?) "
                        + "and bid_px[1] > 0 order by recv_ts desc limit 1", token, minutes)
                : jdbc.queryForList("select bid_px[1] bid, ask_px[1] ask, bid_qty[1] bid_qty, ltp, delta, recv_ts "
                        + "from md.option_tick where underlying = ? and expiry = ? and strike = ? and option_type = ? "
                        + "and recv_ts > now() - make_interval(mins => ?) and bid_px[1] > 0 order by recv_ts desc limit 1",
                        underlying, c.expiry(), c.strike(), c.type(), minutes);
        return last.isEmpty() ? null : last.getFirst();
    }

    /** The highest and lowest bid (and the last tick's time) after {@code from} up to {@code to} (null: now). */
    Map<String, Object> range(String key, String underlying, long token, Timestamp from, Timestamp to) {
        Contract c = contract(key);
        Timestamp until = to != null ? to : new Timestamp(System.currentTimeMillis() + 60_000);
        return c == null
                ? jdbc.queryForMap("select max(bid_px[1]) hi, min(bid_px[1]) lo, max(recv_ts) t from md.option_tick "
                        + "where instrument_token = ? and recv_ts > ? and recv_ts <= ? and bid_px[1] > 0", token, from, until)
                : jdbc.queryForMap("select max(bid_px[1]) hi, min(bid_px[1]) lo, max(recv_ts) t from md.option_tick "
                        + "where underlying = ? and expiry = ? and strike = ? and option_type = ? and recv_ts > ? "
                        + "and recv_ts <= ? and bid_px[1] > 0", underlying, c.expiry(), c.strike(), c.type(), from, until);
    }
}
