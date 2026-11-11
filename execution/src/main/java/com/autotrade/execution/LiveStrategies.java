package com.autotrade.execution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The owner's choice of LIVE strategies: globally (V33, every account without its own choice) and per account (V32).
 * The owner's choice of LIVE strategies per account (user, 9 Nov 2026: "As the owner I should be able to select or
 * deselect from all the available live strategies"). The account's risk file says what may trade LIVE
 * ({@code live.strategies}) and what does until the owner chooses ({@code live.default_strategies}); the choice is
 * {@code acct.broker_account.live_strategies} (V32, null = the defaults). What trades LIVE is the choice within the file's
 * list, so a choice never widens what the reviewed file allows. The risk engine reads it at every LIVE entry and add
 * ({@link FollowerSimulator}); exits are never stopped by it.
 *
 * <p>User, 11 Nov 2026: "if I Off the strategy shouldn't appear in Follower accounts/Demo accounts, also if choose PAPER
 * ... Follower/Demo accounts shouldn't make it LIVE, only if choose LIVE - Follower/Demo accounts should switch from
 * PAPER/LIVE". So the owner's global list, when set, is a ceiling: an account's own list ({@link #choosable}) is chosen
 * from the owner's LIVE strategies only, and what trades LIVE never goes beyond them; an OFF strategy (V35) is nowhere.
 */
public final class LiveStrategies {

    private final JdbcTemplate jdbc;

    public LiveStrategies(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** The owner's choice for the account, sorted; null when none was made (the risk file's defaults apply). */
    public List<String> chosen(long accountId) {
        List<List<String>> rows = jdbc.query("select live_strategies from acct.broker_account where id = ?",
                (rs, n) -> {
                    java.sql.Array array = rs.getArray(1);
                    if (array == null) {
                        return null;
                    }
                    List<String> out = new ArrayList<>();
                    for (Object o : (Object[]) array.getArray()) {
                        out.add(String.valueOf(o));
                    }
                    return List.copyOf(new TreeSet<>(out));
                }, accountId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Where an account's LIVE list comes from: its own choice, the global list, or its risk file's default. */
    public enum Source { ACCOUNT, GLOBAL, DEFAULT }

    /** The owner's global LIVE list (V33), sorted; null when never set. */
    public List<String> global() {
        List<List<String>> rows = jdbc.query("select strategies from acct.live_strategy_policy where id = 1",
                (rs, n) -> {
                    List<String> out = new ArrayList<>();
                    for (Object o : (Object[]) rs.getArray(1).getArray()) {
                        out.add(String.valueOf(o));
                    }
                    return List.copyOf(new TreeSet<>(out));
                });
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Where the account's LIVE list comes from now. */
    public Source source(long accountId) {
        return chosen(accountId) != null ? Source.ACCOUNT : global() != null ? Source.GLOBAL : Source.DEFAULT;
    }

    /**
     * What trades LIVE on the account now, sorted: its own choice, else the global list, else its risk file's defaults;
     * always within the risk file's list and within the owner's global LIVE list when one is set (an owner's PAPER
     * strategy never trades LIVE on any account); never an OFF strategy.
     */
    public List<String> effective(long accountId, FollowerRisk risk) {
        if (risk == null || risk.live() == null) {
            return List.of();
        }
        List<String> global = global();
        List<String> chosen = chosen(accountId);
        Set<String> picked = new TreeSet<>(chosen != null ? chosen : global != null ? global : risk.live().defaults());
        picked.retainAll(choosable(risk, global, off()));
        return List.copyOf(picked);
    }

    /**
     * What the account's own list may hold, sorted: the risk file's LIVE strategies that the owner has LIVE (the global
     * list; all of the file's when the owner has not set one) and not OFF.
     */
    public List<String> choosable(FollowerRisk risk) {
        return risk == null || risk.live() == null ? List.of() : choosable(risk, global(), off());
    }

    private static List<String> choosable(FollowerRisk risk, List<String> global, Set<String> off) {
        Set<String> out = new TreeSet<>(risk.live().strategies());
        if (global != null) {
            out.retainAll(global);
        }
        out.removeAll(off);
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------------------------------- the OFF switch (V35)

    /** The strategies the owner turned OFF (V35), sorted: no new entry or add on any paper or LIVE account. */
    public Set<String> off() {
        return java.util.Collections.unmodifiableSortedSet(
                new TreeSet<>(jdbc.queryForList("select strategy from acct.strategy_off", String.class)));
    }

    /**
     * Records the owner's whole OFF list (replacing the previous one); IllegalArgumentException for a strategy outside
     * {@code known} (what the live sessions run or some risk file lets trade LIVE). Empty = every strategy on.
     */
    public void chooseOff(Collection<String> strategies, Set<String> known, String actor) {
        Set<String> wanted = new TreeSet<>();
        for (String s : strategies) {
            if (s != null && !s.isBlank()) {
                wanted.add(s.trim());
            }
        }
        Set<String> unknown = new TreeSet<>(wanted);
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("not a running or LIVE-eligible strategy: " + unknown + " (may choose from "
                    + new TreeSet<>(known) + ")");
        }
        String array = "{" + String.join(",", wanted) + "}";
        // one statement, so the list is replaced whole or not at all; an unchanged row keeps who turned it OFF and when
        jdbc.update("with gone as (delete from acct.strategy_off where strategy <> all(?::text[])) "
                + "insert into acct.strategy_off (strategy, changed_by) select s, ? from unnest(?::text[]) s "
                + "on conflict (strategy) do nothing", array, actor, array);
    }

    /**
     * Takes {@code strategies} out of the global LIVE list (when set) and out of every account's own list (V32), so a
     * strategy turned OFF and back on is PAPER until the owner ticks LIVE again. Returns the ids of the accounts whose
     * own list changed. A risk file's defaults cannot be edited here: an account on its defaults (no global list either)
     * gets the strategy LIVE again when it is switched back on.
     */
    public List<Long> dropFromLiveLists(Collection<String> strategies, String actor) {
        String array = "{" + String.join(",", new TreeSet<>(strategies)) + "}";
        jdbc.update("update acct.live_strategy_policy set strategies = array(select s from unnest(strategies) s "
                + "where s <> all(?::text[]) order by s), changed_by = ?, changed_at = now() "
                + "where id = 1 and strategies && ?::text[]", array, actor, array);
        return jdbc.queryForList("update acct.broker_account set live_strategies = array(select s from "
                + "unnest(live_strategies) s where s <> all(?::text[]) order by s), updated_at = now() "
                + "where live_strategies && ?::text[] returning id", Long.class, array, array);
    }

    /**
     * The OFF list for a live session's entry check, read at most once per {@code ttl}. A failed read is logged and keeps
     * the last list read (none before the first); it never throws into the session.
     */
    public static java.util.function.Supplier<Set<String>> offReader(DataSource dataSource, java.time.Duration ttl) {
        LiveStrategies live = new LiveStrategies(dataSource);
        return new java.util.function.Supplier<>() {
            private Set<String> last = Set.of();
            private long readAt = Long.MIN_VALUE;

            @Override
            public synchronized Set<String> get() {
                long now = System.nanoTime();
                if (readAt == Long.MIN_VALUE || now - readAt >= ttl.toNanos()) {
                    readAt = now;
                    try {
                        last = live.off();
                    } catch (RuntimeException e) {
                        org.slf4j.LoggerFactory.getLogger(LiveStrategies.class)
                                .warn("strategy OFF list not read, keeping {}: {}", last, e.getMessage());
                    }
                }
                return last;
            }
        };
    }

    /**
     * Records the owner's global list ({@code strategies} null = not set: each account's default); IllegalArgumentException
     * for a strategy outside {@code available} (what some account's risk file lets trade LIVE). Empty = nothing LIVE.
     */
    public void chooseGlobal(Collection<String> strategies, Set<String> available, String actor) {
        if (strategies == null) {
            jdbc.update("delete from acct.live_strategy_policy where id = 1");
            return;
        }
        Set<String> wanted = new TreeSet<>();
        for (String s : strategies) {
            if (s != null && !s.isBlank()) {
                wanted.add(s.trim());
            }
        }
        Set<String> unknown = new TreeSet<>(wanted);
        unknown.removeAll(available);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("not a LIVE strategy of any account's risk file: " + unknown
                    + " (may choose from " + new TreeSet<>(available) + ")");
        }
        jdbc.update("insert into acct.live_strategy_policy (id, strategies, changed_by) values (1, ?::text[], ?) "
                + "on conflict (id) do update set strategies = excluded.strategies, changed_by = excluded.changed_by, "
                + "changed_at = now()", "{" + String.join(",", wanted) + "}", actor);
    }

    /**
     * Records the owner's choice ({@code strategies} null = back to the risk file's defaults); IllegalArgumentException
     * for a strategy the risk file does not let trade LIVE. An empty choice is allowed: nothing trades LIVE.
     */
    public void choose(long accountId, FollowerRisk risk, Collection<String> strategies) {
        if (risk == null || risk.live() == null) {
            throw new IllegalArgumentException("the account's risk file has no LIVE limits");
        }
        if (strategies == null) {
            jdbc.update("update acct.broker_account set live_strategies = null, updated_at = now() where id = ?", accountId);
            return;
        }
        Set<String> wanted = new TreeSet<>();
        for (String s : strategies) {
            if (s != null && !s.isBlank()) {
                wanted.add(s.trim());
            }
        }
        Set<String> unknown = new TreeSet<>(wanted);
        unknown.removeAll(risk.live().strategies());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("not allowed LIVE by the risk file: " + unknown + " (may choose from "
                    + new TreeSet<>(risk.live().strategies()) + ")");
        }
        Set<String> switchedOff = new TreeSet<>(wanted);
        switchedOff.retainAll(off());
        if (!switchedOff.isEmpty()) {
            throw new IllegalArgumentException("turned OFF for every account: " + switchedOff
                    + " (switch it back on under Strategies on the Accounts page first)");
        }
        Set<String> paperOnly = new TreeSet<>(wanted);
        paperOnly.removeAll(choosable(risk));
        if (!paperOnly.isEmpty()) {
            throw new IllegalArgumentException("the platform owner has " + paperOnly + " on PAPER only (may choose from "
                    + choosable(risk) + ")");
        }
        jdbc.update("update acct.broker_account set live_strategies = ?::text[], updated_at = now() where id = ?",
                "{" + String.join(",", wanted) + "}", accountId);
    }
}
