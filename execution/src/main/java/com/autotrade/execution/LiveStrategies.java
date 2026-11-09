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
     * always within the risk file's list.
     */
    public List<String> effective(long accountId, FollowerRisk risk) {
        if (risk == null || risk.live() == null) {
            return List.of();
        }
        List<String> chosen = chosen(accountId);
        if (chosen == null) {
            chosen = global();
        }
        Set<String> picked = new TreeSet<>(chosen == null ? risk.live().defaults() : chosen);
        picked.retainAll(risk.live().strategies());
        return List.copyOf(picked);
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
        jdbc.update("update acct.broker_account set live_strategies = ?::text[], updated_at = now() where id = ?",
                "{" + String.join(",", wanted) + "}", accountId);
    }
}
