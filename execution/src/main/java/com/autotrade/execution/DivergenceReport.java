package com.autotrade.execution;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.TradeMode;

/**
 * Live execution L1: the daily divergence report (docs/LIVE-TRADING-PLAN.md section 1, "Divergence report"; gate L1).
 * <ol>
 *   <li><b>Signal source vs each follower.</b> Every ENTER and ADD decision of the day's signal session and what each
 *       subscribed follower did with it: an executed entry with its lots against the signal's, the entry and exit fills
 *       against the signal's reference prices and its net; an executed add counted; anything else listed with the
 *       intent's state and code.</li>
 *   <li><b>PAPER follower vs PAPER-1.</b> The follower's positions from this signal session against PAPER-1's positions
 *       of the same day for the strategies the follower subscribes to, matched by strategy, underlying, side, contract
 *       and entry time (within {@link #MATCH_WINDOW}); quantity includes adds on both sides. A divergence is a trade
 *       only one side has, or a matched trade whose quantities differ by more than {@link #SIZE_TOLERANCE}. It is
 *       <i>explained</i> by the follower's refusal or skip of that entry or of an add while it was open, by the replay
 *       depth ceiling (the follower holds exactly the signal account's fill), or by PAPER-1's risk refusal
 *       (trade.rejection); otherwise it is <i>unexplained</i>. The gate holds for the day when the follower
 *       ran and nothing is unexplained.</li>
 * </ol>
 * Reads only: exec.signal_decision / trade_batch / trade_intent / order_execution / position, acct.strategy_subscription,
 * trade.session / position / rejection. The signal session is the day's newest for the copy group; PAPER-1's session
 * is the one of the same mode and code version (the tee runs both in one process), else the day's latest PAPER-1
 * session of that mode, unless given. A follower that processed none of this signal session's intents did not run.
 */
public final class DivergenceReport {

    /** Entries of the two sides closer than this are the same trade. */
    static final Duration MATCH_WINDOW = Duration.ofSeconds(90);
    /** Matched quantities differing by more than this fraction (of the larger) are a size divergence. */
    static final double SIZE_TOLERANCE = 0.25;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(IST);

    /** One signal entry or add as one follower saw it; the fill fields are null unless an executed entry. */
    public record FollowerTrade(String strategy, String underlying, String side, String symbol, String action,
                                Instant signalAt, double signalPrice, int signalLots, String outcome, Integer lots,
                                Double entryFill, Double entrySlipPct, Instant exitAt, Double signalExitPrice,
                                Double exitFill, Double exitSlipPct, Double net, String exitReason) {
    }

    /** One follower's day against the signal source; {@code ran} false = it processed none of the day's intents. */
    public record FollowerSummary(long accountId, String account, TradeMode mode, boolean ran, int entries, int executed,
                                  int addsExecuted, Map<String, Integer> notFollowed, double net, Double medianEntrySlipPct,
                                  Double medianExitSlipPct, List<FollowerTrade> trades) {
    }

    /**
     * One row of the PAPER-1 comparison: MATCHED, SIZE (matched, quantities outside the tolerance), ONLY_PAPER1 or
     * ONLY_FOLLOWER; {@code explanation} null on a divergence = unexplained.
     */
    public record Match(String kind, String strategy, String underlying, String side, String symbol, Instant at,
                        Long paper1Quantity, Long followerQuantity, Double paper1Net, Double followerNet,
                        String explanation) {
        double difference() {
            return (followerNet == null ? 0 : followerNet) - (paper1Net == null ? 0 : paper1Net);
        }

        boolean divergence() {
            return !kind.equals("MATCHED");
        }
    }

    /** A PAPER follower against PAPER-1 for the day. */
    public record Paper1Comparison(long accountId, String account, long paper1SessionId, boolean followerRan,
                                   double paper1Net, double followerNet, int matched, int sizeDivergences, int onlyPaper1,
                                   int onlyFollower, int unexplained, double matchedDifference, double explainedDifference,
                                   double unexplainedDifference, List<Match> rows) {
        /** Gate L1 for the day: the follower ran and every divergence is explained. */
        public boolean withinGate() {
            return followerRan && unexplained == 0;
        }
    }

    public record Report(LocalDate day, long copyGroupId, Long signalSessionId, Long paper1SessionId,
                         List<FollowerSummary> followers, List<Paper1Comparison> paper1) {
    }

    private record Decision(long id, Instant at, String strategy, String underlying, String action, String side,
                            String symbol, double price, int lots, int lotSize) {
    }

    private record Intent(long id, String state, String code) {
        boolean executed() {
            return state.equals("EXECUTED");
        }

        String outcome() {
            return state + (code == null ? "" : "_" + code);
        }
    }

    private record FollowerPosition(Instant closedAt, String exitReason, double net, Long entryQuantity, Double entryFill) {
    }

    private record Position(String strategy, String underlying, String side, String symbol, Instant openedAt,
                            Instant closedAt, long quantity, double net) {
    }

    private final JdbcTemplate jdbc;
    private final AccountStore accounts;

    public DivergenceReport(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.accounts = new AccountStore(dataSource);
    }

    /** The day's report for a copy group; {@code paper1SessionId} null = chosen as the class comment says. */
    public Report build(long copyGroupId, LocalDate day, Long paper1SessionId) {
        Long signalSession = jdbc.queryForObject("select max(signal_session_id) from exec.signal_decision "
                + "where copy_group_id = ? and session_date = ?", Long.class, copyGroupId, day);
        if (signalSession == null) {
            return new Report(day, copyGroupId, null, null, List.of(), List.of());
        }
        List<Decision> decisions = decisions(copyGroupId, signalSession);
        Long paper1 = paper1SessionId != null ? paper1SessionId : paper1Session(signalSession, day).orElse(null);
        List<FollowerSummary> followers = new ArrayList<>();
        List<Paper1Comparison> paper1Rows = new ArrayList<>();
        for (BrokerAccount account : accounts.followers(copyGroupId)) {
            Map<Long, Intent> intents = intents(account.id(), signalSession);
            boolean ran = intents.values().stream().anyMatch(i -> !i.state().equals("CREATED"));
            followers.add(followerSummary(account, signalSession, decisions, intents, ran));
            if (account.tradeMode() == TradeMode.PAPER && paper1 != null) {
                paper1Rows.add(ran ? paper1Comparison(account, copyGroupId, signalSession, paper1, decisions, intents)
                        : new Paper1Comparison(account.id(), account.displayName(), paper1, false, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                0, List.of()));
            }
        }
        return new Report(day, copyGroupId, signalSession, paper1, followers, paper1Rows);
    }

    private List<Decision> decisions(long copyGroupId, long signalSession) {
        return jdbc.query("select id, decided_at, strategy_id, underlying, action, option_side, symbol, reference_price, "
                        + "coalesce(lots, 0) lots, lot_size from exec.signal_decision where copy_group_id = ? and signal_session_id = ? "
                        + "order by decided_at, priority, id",
                (rs, n) -> new Decision(rs.getLong("id"), rs.getTimestamp("decided_at").toInstant(), rs.getString("strategy_id"),
                        rs.getString("underlying"), rs.getString("action"), rs.getString("option_side"), rs.getString("symbol"),
                        rs.getDouble("reference_price"), rs.getInt("lots"), rs.getInt("lot_size")), copyGroupId, signalSession);
    }

    /** The account's intents for this signal session, by decision id (one query). */
    private Map<Long, Intent> intents(long accountId, long signalSession) {
        Map<Long, Intent> intents = new HashMap<>();
        jdbc.query("select b.signal_decision_id, i.id, i.state, i.reject_code from exec.trade_intent i "
                        + "join exec.trade_batch b on b.id = i.trade_batch_id join exec.signal_decision d on d.id = b.signal_decision_id "
                        + "where i.broker_account_id = ? and d.signal_session_id = ?",
                rs -> {
                    intents.put(rs.getLong(1), new Intent(rs.getLong(2), rs.getString(3), rs.getString(4)));
                }, accountId, signalSession);
        return intents;
    }

    private Optional<Long> paper1Session(long signalSession, LocalDate day) {
        List<Long> ids = jdbc.queryForList("select p.id from trade.session p join trade.session s on s.id = ? "
                + "where p.account = 'PAPER-1' and p.session_date = ? and p.mode = s.mode "
                + "order by (p.code_version is not distinct from s.code_version) desc, p.id desc limit 1", Long.class,
                signalSession, day);
        return ids.stream().findFirst();
    }

    // ------------------------------------------------------------------ part 1: signal source vs follower

    private FollowerSummary followerSummary(BrokerAccount account, long signalSession, List<Decision> decisions,
                                            Map<Long, Intent> intents, boolean ran) {
        if (!ran) {
            return new FollowerSummary(account.id(), account.displayName(), account.tradeMode(), false, 0, 0, 0, Map.of(), 0,
                    null, null, List.of());
        }
        Map<Long, FollowerPosition> positions = followerPositions(account.id(), signalSession);
        Map<Long, Double> exitFills = exitFills(account.id(), signalSession);
        List<FollowerTrade> trades = new ArrayList<>();
        Map<String, Integer> notFollowed = new TreeMap<>();
        double net = 0;
        int entries = 0, executed = 0, addsExecuted = 0;
        List<Double> entrySlips = new ArrayList<>(), exitSlips = new ArrayList<>();
        for (Decision d : decisions) {
            boolean add = d.action().equals("ADD");
            Intent intent = intents.get(d.id());
            if (intent == null || !(add || d.action().equals("ENTER"))) {
                continue;                                           // an exit, or a strategy the account does not follow
            }
            entries += add ? 0 : 1;
            if (!intent.executed()) {
                String outcome = (add ? "ADD_" : "") + intent.outcome();
                notFollowed.merge(outcome, 1, Integer::sum);
                trades.add(new FollowerTrade(d.strategy(), d.underlying(), d.side(), d.symbol(), d.action(), d.at(), d.price(),
                        d.lots(), outcome, null, null, null, null, null, null, null, null, null));
                continue;
            }
            if (add) {
                addsExecuted++;
                continue;
            }
            FollowerPosition p = positions.get(intent.id());
            if (p == null) {
                notFollowed.merge("EXECUTED_NO_POSITION", 1, Integer::sum);
                continue;
            }
            executed++;
            net += p.net();
            Integer lots = p.entryQuantity() == null || d.lotSize() <= 0 ? null : (int) (p.entryQuantity() / d.lotSize());
            Double entrySlip = p.entryFill() != null && d.price() > 0 ? (p.entryFill() / d.price() - 1) * 100 : null;
            Decision exit = p.closedAt() == null ? null : exitDecision(decisions, d, p.closedAt());
            Double exitFill = exit == null ? null : exitFills.get(exit.id());
            Double exitSlip = exit != null && exitFill != null && exit.price() > 0 ? (exitFill / exit.price() - 1) * 100 : null;
            if (entrySlip != null) {
                entrySlips.add(entrySlip);
            }
            if (exitSlip != null) {
                exitSlips.add(exitSlip);
            }
            trades.add(new FollowerTrade(d.strategy(), d.underlying(), d.side(), d.symbol(), d.action(), d.at(), d.price(),
                    d.lots(), "EXECUTED", lots, p.entryFill(), entrySlip, p.closedAt(), exit == null ? null : exit.price(),
                    exitFill, exitSlip, p.net(), p.exitReason()));
        }
        return new FollowerSummary(account.id(), account.displayName(), account.tradeMode(), true, entries, executed,
                addsExecuted, notFollowed, net, median(entrySlips), median(exitSlips), trades);
    }

    /** The account's positions of this signal session by entry intent, with the entry's own fill (not the average after adds). */
    private Map<Long, FollowerPosition> followerPositions(long accountId, long signalSession) {
        Map<Long, FollowerPosition> positions = new HashMap<>();
        jdbc.query("select p.entry_trade_intent_id, p.closed_at, p.exit_reason, coalesce(p.realised_gross, 0) - "
                        + "coalesce(p.charges, 0) net, e.quantity_filled, e.average_fill_price from exec.position p "
                        + "join exec.trade_intent i on i.id = p.entry_trade_intent_id join exec.trade_batch b on b.id = i.trade_batch_id "
                        + "join exec.signal_decision d on d.id = b.signal_decision_id left join lateral (select sum(quantity_filled) "
                        + "quantity_filled, sum(quantity_filled * average_fill_price) / nullif(sum(quantity_filled), 0) "
                        + "average_fill_price from exec.order_execution where trade_intent_id = p.entry_trade_intent_id "
                        + "and role = 'ENTRY') e on true where p.broker_account_id = ? and d.signal_session_id = ?",
                rs -> {
                    Timestamp closed = rs.getTimestamp("closed_at");
                    Number quantity = (Number) rs.getObject("quantity_filled");
                    Number fill = (Number) rs.getObject("average_fill_price");
                    positions.put(rs.getLong(1), new FollowerPosition(closed == null ? null : closed.toInstant(),
                            rs.getString("exit_reason"), rs.getDouble("net"), quantity == null ? null : quantity.longValue(),
                            fill == null ? null : fill.doubleValue()));
                }, accountId, signalSession);
        return positions;
    }

    /** The account's EXIT fill per exit decision of this signal session. */
    private Map<Long, Double> exitFills(long accountId, long signalSession) {
        Map<Long, Double> fills = new HashMap<>();
        jdbc.query("select b.signal_decision_id, e.average_fill_price from exec.order_execution e "
                        + "join exec.trade_intent i on i.id = e.trade_intent_id join exec.trade_batch b on b.id = i.trade_batch_id "
                        + "join exec.signal_decision d on d.id = b.signal_decision_id where i.broker_account_id = ? "
                        + "and d.signal_session_id = ? and e.role = 'EXIT' order by e.id",
                rs -> {
                    fills.putIfAbsent(rs.getLong(1), rs.getDouble(2));
                }, accountId, signalSession);
        return fills;
    }

    /** The signal's EXIT for the entry: same strategy, contract and side, at the follower's close, else the next one. */
    private static Decision exitDecision(List<Decision> decisions, Decision entry, Instant closedAt) {
        Decision next = null;
        for (Decision d : decisions) {
            if (!d.action().equals("EXIT") || !d.strategy().equals(entry.strategy()) || !d.symbol().equals(entry.symbol())
                    || !d.side().equals(entry.side()) || d.at().isBefore(entry.at())) {
                continue;
            }
            if (d.at().equals(closedAt)) {
                return d;
            }
            if (next == null && !d.at().isBefore(closedAt)) {
                next = d;
            }
        }
        return next;
    }

    // ------------------------------------------------------------------ part 2: PAPER follower vs PAPER-1

    private Paper1Comparison paper1Comparison(BrokerAccount account, long copyGroupId, long signalSession, long paper1Session,
                                              List<Decision> decisions, Map<Long, Intent> intents) {
        Set<String> strategies = Set.copyOf(jdbc.queryForList("select strategy_id from acct.strategy_subscription "
                + "where copy_group_id = ? and broker_account_id = ? and enabled", String.class, copyGroupId, account.id()));
        List<Position> paper1 = new ArrayList<>(jdbc.query("select strategy_id, underlying, option_side, symbol, opened_at, "
                        + "closed_at, coalesce(quantity, 0) quantity, coalesce(net, 0) net from trade.position where session_id = ? "
                        + "order by opened_at, id",
                (rs, n) -> position(rs), paper1Session));
        paper1.removeIf(p -> !strategies.contains(p.strategy()));
        // this signal session's positions only; quantity = entry plus adds (ENTRY-role fills of the account's intents for
        // the same strategy and contract decided while the position was open), as PAPER-1's position quantity
        List<Position> follower = jdbc.query("select p.strategy_id, p.underlying, p.option_side, p.symbol, p.opened_at, "
                        + "p.closed_at, coalesce((select sum(e.quantity_filled) from exec.order_execution e "
                        + "join exec.trade_intent ai on ai.id = e.trade_intent_id join exec.trade_batch ab on ab.id = ai.trade_batch_id "
                        + "join exec.signal_decision ad on ad.id = ab.signal_decision_id where e.role = 'ENTRY' "
                        + "and ai.broker_account_id = p.broker_account_id and ad.signal_session_id = d.signal_session_id "
                        + "and ad.strategy_id = p.strategy_id and ad.symbol = p.symbol and ad.option_side = p.option_side "
                        + "and ad.decided_at >= p.opened_at and ad.decided_at < coalesce(p.closed_at, 'infinity'::timestamptz)), 0) "
                        + "quantity, coalesce(p.realised_gross, 0) - coalesce(p.charges, 0) net from exec.position p "
                        + "join exec.trade_intent i on i.id = p.entry_trade_intent_id join exec.trade_batch b on b.id = i.trade_batch_id "
                        + "join exec.signal_decision d on d.id = b.signal_decision_id "
                        + "where p.broker_account_id = ? and d.signal_session_id = ? order by p.opened_at, p.id",
                (rs, n) -> position(rs), account.id(), signalSession);
        // the signal account's own positions: in a replay its fill is the followers' depth ceiling (FollowerSimulator)
        List<Position> signal = jdbc.query("select strategy_id, underlying, option_side, symbol, opened_at, closed_at, "
                        + "coalesce(quantity, 0) quantity, coalesce(net, 0) net from trade.position where session_id = ?",
                (rs, n) -> position(rs), signalSession);
        List<Match> rows = new ArrayList<>();
        List<Position> unmatchedFollower = new ArrayList<>(follower);
        for (Position p1 : paper1) {
            Position f = unmatchedFollower.stream().filter(x -> same(p1, x)).findFirst().orElse(null);
            if (f == null) {
                rows.add(new Match("ONLY_PAPER1", p1.strategy(), p1.underlying(), p1.side(), p1.symbol(), p1.openedAt(),
                        p1.quantity(), null, p1.net(), null, followerRefusal(decisions, intents, p1)));
                continue;
            }
            unmatchedFollower.remove(f);
            boolean sizeDiffers = Math.abs(f.quantity() - p1.quantity())
                    > SIZE_TOLERANCE * Math.max(f.quantity(), p1.quantity());
            rows.add(new Match(sizeDiffers ? "SIZE" : "MATCHED", p1.strategy(), p1.underlying(), p1.side(), p1.symbol(),
                    p1.openedAt(), p1.quantity(), f.quantity(), p1.net(), f.net(),
                    sizeDiffers ? sizeExplanation(decisions, intents, signal, f) : "matched"));
        }
        for (Position f : unmatchedFollower) {
            rows.add(new Match("ONLY_FOLLOWER", f.strategy(), f.underlying(), f.side(), f.symbol(), f.openedAt(), null,
                    f.quantity(), null, f.net(), paper1Refusal(paper1Session, f)));
        }
        rows.sort(Comparator.comparing(Match::at));
        int matched = 0, size = 0, onlyPaper1 = 0, onlyFollower = 0, unexplained = 0;
        double matchedDiff = 0, explainedDiff = 0, unexplainedDiff = 0;
        for (Match m : rows) {
            switch (m.kind()) {
                case "MATCHED" -> matched++;
                case "SIZE" -> size++;
                case "ONLY_PAPER1" -> onlyPaper1++;
                default -> onlyFollower++;
            }
            if (m.kind().equals("MATCHED") || m.kind().equals("SIZE")) {
                matchedDiff += m.difference();
            }
            if (m.divergence()) {
                if (m.explanation() == null) {
                    unexplained++;
                    unexplainedDiff += m.difference();
                } else {
                    explainedDiff += m.difference();
                }
            }
        }
        double paper1Net = paper1.stream().mapToDouble(Position::net).sum();
        double followerNet = follower.stream().mapToDouble(Position::net).sum();
        return new Paper1Comparison(account.id(), account.displayName(), paper1Session, true, paper1Net, followerNet, matched,
                size, onlyPaper1, onlyFollower, unexplained, matchedDiff, explainedDiff, unexplainedDiff, rows);
    }

    private static Position position(ResultSet rs) throws SQLException {
        Timestamp closed = rs.getTimestamp("closed_at");
        return new Position(rs.getString("strategy_id"), rs.getString("underlying"), rs.getString("option_side"),
                rs.getString("symbol"), rs.getTimestamp("opened_at").toInstant(), closed == null ? null : closed.toInstant(),
                rs.getLong("quantity"), rs.getDouble("net"));
    }

    private static boolean same(Position a, Position b) {
        return a.strategy().equals(b.strategy()) && a.underlying().equals(b.underlying()) && a.side().equals(b.side())
                && a.symbol().equals(b.symbol()) && Duration.between(a.openedAt(), b.openedAt()).abs().compareTo(MATCH_WINDOW) <= 0;
    }

    /** Why the follower has no position for PAPER-1's trade: its intent's code for the signal's same entry, else null. */
    private static String followerRefusal(List<Decision> decisions, Map<Long, Intent> intents, Position p1) {
        for (Decision d : decisions) {
            if (!d.action().equals("ENTER") || !d.strategy().equals(p1.strategy()) || !d.underlying().equals(p1.underlying())
                    || !d.side().equals(p1.side()) || !d.symbol().equals(p1.symbol())
                    || Duration.between(d.at(), p1.openedAt()).abs().compareTo(MATCH_WINDOW) > 0) {
                continue;
            }
            Intent intent = intents.get(d.id());
            if (intent != null && !intent.executed()) {
                return "follower " + intent.state() + " " + intent.code();
            }
        }
        return null;
    }

    /**
     * Why a matched position's size differs: the follower's refusal or skip of an add while it was open; else the replay
     * depth ceiling, when the follower holds exactly what the signal account was filled (it sizes without risk v17's
     * RVOL boost, so a boosted PAPER-1 entry is larger than the ceiling); else null.
     */
    private static String sizeExplanation(List<Decision> decisions, Map<Long, Intent> intents, List<Position> signal,
                                          Position f) {
        String adds = addRefusal(decisions, intents, f);
        if (adds != null) {
            return adds;
        }
        return signal.stream().filter(s -> same(s, f) && s.quantity() == f.quantity()).findFirst()
                .map(s -> "replay depth ceiling: the signal account filled " + String.format(Locale.ROOT, "%,d", s.quantity()))
                .orElse(null);
    }

    /** The follower's refusals or skips of adds while the position was open, else null. */
    private static String addRefusal(List<Decision> decisions, Map<Long, Intent> intents, Position f) {
        List<String> refused = new ArrayList<>();
        for (Decision d : decisions) {
            if (!d.action().equals("ADD") || !d.strategy().equals(f.strategy()) || !d.symbol().equals(f.symbol())
                    || !d.side().equals(f.side()) || d.at().isBefore(f.openedAt())
                    || (f.closedAt() != null && !d.at().isBefore(f.closedAt()))) {
                continue;
            }
            Intent intent = intents.get(d.id());
            if (intent != null && !intent.executed()) {
                refused.add(intent.state() + " " + intent.code() + " " + HM.format(d.at()));
            }
        }
        return refused.isEmpty() ? null : "follower ADD " + String.join(", ", refused);
    }

    /** Why PAPER-1 has no position for the follower's trade: its risk engine's refusal near that time, else null. */
    private String paper1Refusal(long paper1Session, Position f) {
        List<String> reasons = jdbc.queryForList("select reason from trade.rejection where session_id = ? and strategy_id = ? "
                        + "and underlying = ? and at between ? and ? order by at limit 1", String.class, paper1Session, f.strategy(),
                f.underlying(), Timestamp.from(f.openedAt().minus(MATCH_WINDOW)), Timestamp.from(f.openedAt().plus(MATCH_WINDOW)));
        return reasons.isEmpty() ? null : "PAPER-1 refused: " + reasons.getFirst();
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    // ------------------------------------------------------------------ rendering

    /** The report as Markdown (what the CLI prints and writes). */
    public static String markdown(Report r, String copyGroupName) {
        StringBuilder md = new StringBuilder();
        md.append("# Divergence report ").append(r.day()).append(" · copy group ").append(copyGroupName).append("\n\n");
        if (r.signalSessionId() == null) {
            return md.append("No signal decisions for this day.\n").toString();
        }
        md.append("Signal session ").append(r.signalSessionId()).append("; PAPER-1 session ")
                .append(r.paper1SessionId() == null ? "none" : r.paper1SessionId()).append(".\n\n");
        md.append("## 1. Signal source vs followers\n\n");
        md.append("| Follower | Mode | Signal entries | Executed | Adds executed | Not followed | Net | Median entry slip | "
                + "Median exit slip |\n|---|---|---|---|---|---|---|---|---|\n");
        for (FollowerSummary f : r.followers()) {
            if (!f.ran()) {
                md.append("| ").append(f.account()).append(" | ").append(f.mode())
                        .append(" | – | – | – | – | – | – | not run this day |\n");
                continue;
            }
            md.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %d | %s | %s | %s | %s |%n", f.account(), f.mode(),
                    f.entries(), f.executed(), f.addsExecuted(), f.notFollowed().isEmpty() ? "–" : f.notFollowed(),
                    rupees(f.net()), pct(f.medianEntrySlipPct()), pct(f.medianExitSlipPct())));
        }
        for (FollowerSummary f : r.followers()) {
            if (f.trades().isEmpty()) {
                continue;
            }
            md.append("\n### ").append(f.account()).append("\n\n");
            md.append("| Signal at | Strategy | Contract | Action | Signal price × lots | Outcome | Lots | Entry fill (slip) | "
                    + "Exit at | Signal exit | Exit fill (slip) | Net | Exit reason |\n");
            md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (FollowerTrade t : f.trades()) {
                md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %.2f × %d | %s | %s | %s | %s | %s | %s | %s | %s |%n",
                        HM.format(t.signalAt()), t.strategy(), t.symbol(), t.action(), t.signalPrice(), t.signalLots(),
                        t.outcome(), t.lots() == null ? "–" : t.lots(), fill(t.entryFill(), t.entrySlipPct()),
                        t.exitAt() == null ? "–" : HM.format(t.exitAt()),
                        t.signalExitPrice() == null ? "–" : String.format(Locale.ROOT, "%.2f", t.signalExitPrice()),
                        fill(t.exitFill(), t.exitSlipPct()), t.net() == null ? "–" : rupees(t.net()),
                        t.exitReason() == null ? "–" : t.exitReason()));
            }
        }
        md.append("\n## 2. PAPER followers vs PAPER-1\n\n");
        if (r.paper1().isEmpty()) {
            md.append(r.paper1SessionId() == null ? "No PAPER-1 session for this day.\n" : "No PAPER follower.\n");
            return md.toString();
        }
        md.append("| Follower | PAPER-1 net | Follower net | Difference | Matched (difference) | Size divergences | Only PAPER-1 | "
                + "Only follower | Unexplained | Gate |\n|---|---|---|---|---|---|---|---|---|---|\n");
        for (Paper1Comparison c : r.paper1()) {
            if (!c.followerRan()) {
                md.append("| ").append(c.account()).append(" | – | – | – | – | – | – | – | – | not run this day |\n");
                continue;
            }
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %d (%s) | %d | %d | %d | %d (%s) | %s |%n", c.account(),
                    rupees(c.paper1Net()), rupees(c.followerNet()), rupees(c.followerNet() - c.paper1Net()), c.matched(),
                    rupees(c.matchedDifference()), c.sizeDivergences(), c.onlyPaper1(), c.onlyFollower(), c.unexplained(),
                    rupees(c.unexplainedDifference()), c.withinGate() ? "**within**" : "**OUTSIDE**"));
        }
        for (Paper1Comparison c : r.paper1()) {
            List<Match> different = c.rows().stream().filter(m -> m.divergence()
                    || !m.paper1Quantity().equals(m.followerQuantity()) || Math.abs(m.difference()) >= 1).toList();
            if (different.isEmpty()) {
                continue;
            }
            md.append("\n### ").append(c.account()).append(": every trade that differs\n\n");
            md.append("| At | Kind | Strategy | Contract | PAPER-1 qty / net | Follower qty / net | Difference | Explanation |\n");
            md.append("|---|---|---|---|---|---|---|---|\n");
            for (Match m : different) {
                md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s | %s |%n", HM.format(m.at()), m.kind(),
                        m.strategy(), m.symbol(), side(m.paper1Quantity(), m.paper1Net()), side(m.followerQuantity(), m.followerNet()),
                        rupees(m.difference()), m.explanation() == null ? "**unexplained**" : m.explanation()));
            }
        }
        return md.toString();
    }

    private static String rupees(double v) {
        return String.format(Locale.ROOT, "%s₹%,.0f", v < 0 ? "−" : v > 0 ? "+" : "", Math.abs(v));
    }

    private static String pct(Double v) {
        return v == null ? "–" : String.format(Locale.ROOT, "%+.2f %%", v);
    }

    private static String fill(Double price, Double slip) {
        return price == null ? "–" : String.format(Locale.ROOT, "%.2f (%s)", price, pct(slip));
    }

    private static String side(Long quantity, Double net) {
        return quantity == null ? "–" : String.format(Locale.ROOT, "%,d / %s", quantity, rupees(net));
    }
}
