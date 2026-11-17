package com.autotrade.exec.app;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.TradeMode;
import com.autotrade.execution.KillSwitches;

import tools.jackson.databind.JsonNode;

/**
 * What the bot does with a message or a button tap (Telegram controls T0 + T1, plan
 * docs/plans/2026-11-16-telegram-controls-plan.md, user go-ahead 16 Nov 2026).
 * <p>
 * <b>Who:</b> a private chat only, whose sender is the chat itself (in a private chat Telegram's chat id is the user's
 * id), linked to an ACTIVE login ({@code acct.app_user.telegram_chat_id}); that login's roles come from {@link Access},
 * the same rules as the web: reading for every member, kill / flatten / exit for {@link Access.User#emergency}, the
 * GLOBAL_LIVE switch for the platform admin. Group chats are never answered.
 * <p>
 * <b>Reads:</b> {@code /help}, {@code /whoami}, {@code /status}, {@code /positions}.
 * <b>Writes:</b> {@code /kill [ACCOUNT]}, {@code /killall}, {@code /flatten [ACCOUNT]}, {@code /exit [position]}: each
 * shows a card naming the account, its mode and what will happen, with Confirm and Cancel on separate rows; nothing runs
 * until Confirm is tapped by the same person within {@code confirmSeconds}. The rights are checked again at the tap. The
 * actions are the web's own ({@link KillSwitches}, {@link ExecutionService#flatten}, {@link ExecutionService#exitPosition})
 * with the actor {@code telegram:<login>}, run one at a time off the poll thread; the card is edited with the result and a
 * reference ({@code T<id>}) that the audit log carries ({@code TELEGRAM_COMMAND}).
 * <p>
 * <b>Never here</b>, by design: releasing a kill switch, LIVE switches, entries, stops, capital or limits. A phone in
 * the wrong hands can stop trading and sell, never add risk.
 */
@Component
class TelegramCommands {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommands.class);
    private static final String NONCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int MAX_BUTTONS = 12;
    /** Older than this a message gets no answer at all (first start on a bot holding a day of messages; long outage). */
    static final long SILENT_AGE_SECONDS = 600;
    /** A chat that is not linked hears "not linked" at most this often (a stranger's flood would starve the reader). */
    static final Duration UNLINKED_REPLY_EVERY = Duration.ofMinutes(10);
    /** Threads for confirmed commands: a kill never waits behind a flatten stuck on a broker (up to 120 s each). */
    private static final int ACTION_THREADS = 4;

    /** What the commands do to the worker: the web's flatten and exit (an interface so tests need no running loop). */
    interface Actions {
        List<String> flatten(long accountId, String actor, String reason) throws Exception;

        /** {@code quantity}: whole lots of it to sell, the rest stays open; null: all of it. */
        List<String> exitPosition(long accountId, long positionId, String actor, Long quantity) throws Exception;

        /** The worker's last finished tick (null: none yet). */
        Instant lastTick();
    }

    /** The person behind a chat. */
    record Caller(long appUserId, String chat, Access.User user) {
        String actor() {
            return "telegram:" + user.subject();
        }
    }

    private final JdbcTemplate jdbc;
    /** A position's bid, P&L, exposure and warnings, as the web's book shows them (17 Nov 2026). */
    private final PositionFacts facts;
    private final Access access;
    private final AccountStore accounts;
    private final KillSwitches kills;
    private final Actions actions;
    private final TelegramBot bot;
    private final TelegramCommandProperties props;
    private final Executor runner;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> throttled = new java.util.concurrent.ConcurrentHashMap<>();
    /** The web's address for /login links (null: the paths only). */
    private String publicBaseUrl;
    /** The longest list a read sends (Telegram's message limit). */
    static final int MAX_LIST = 15;
    /** Today's start (IST midnight) as a timestamptz expression. */
    private static final String IST_DAY_START = "(date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata')";

    /** The web's public address (autotrade.execution.kite.public-base-url), for /login's links. */
    @Autowired(required = false)
    void publicBaseUrl(KiteLoginProperties kite) {
        String url = kite == null ? null : kite.publicBaseUrl();
        this.publicBaseUrl = url == null || url.isBlank() ? null : url.replaceAll("/+$", "");
    }

    @Autowired
    TelegramCommands(DataSource dataSource, Access access, ExecutionService service, TelegramNotifier notifier,
                     TelegramCommandProperties props) {
        this(dataSource, access, new Actions() {
            @Override
            public List<String> flatten(long accountId, String actor, String reason) throws Exception {
                return service.flatten(accountId, actor, reason, service.day());
            }

            @Override
            public List<String> exitPosition(long accountId, long positionId, String actor, Long quantity) throws Exception {
                return service.exitPosition(accountId, positionId, actor, service.day(), quantity);
            }

            @Override
            public Instant lastTick() {
                return service.lastTick();
            }
        }, new TelegramBot(notifier::token, notifier.api()), props, Executors.newFixedThreadPool(ACTION_THREADS,
                Thread.ofPlatform().name("telegram-actions-", 1).daemon(true).factory()));
    }

    TelegramCommands(DataSource dataSource, Access access, Actions actions, TelegramBot bot,
                     TelegramCommandProperties props, Executor runner) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.facts = new PositionFacts(dataSource);
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.kills = new KillSwitches(dataSource);
        this.actions = actions;
        this.bot = bot;
        this.props = props;
        this.runner = runner;
    }

    TelegramBot bot() {
        return bot;
    }

    /** Whether taps on this bot's buttons are read (the commands are on). */
    boolean acceptsButtons() {
        return props.commands();
    }

    /**
     * At the worker's start: a card still waiting can no longer be trusted to match what is on screen (EXPIRED), and a
     * command confirmed but not finished is never run again (FAILED): an exit may already have gone out. Its person is
     * told to check.
     */
    void recover() {
        jdbc.update("update exec.telegram_command set status = 'EXPIRED', done_at = now(), result = 'the worker restarted' "
                + "where status = 'PENDING'");
        for (Map<String, Object> r : jdbc.queryForList("update exec.telegram_command set status = 'FAILED', done_at = now(), "
                + "result = 'the worker restarted while it ran: not run again' where status = 'CONFIRMED' "
                + "returning id, chat_id, verb")) {
            bot.send(String.valueOf(r.get("chat_id")), "T" + r.get("id") + " " + r.get("verb") + ": the worker restarted while "
                    + "it ran. It is NOT run again. Check /positions and the Accounts page.", null);
        }
    }

    /** One stored update: returns a short outcome for exec.telegram_update. Never throws. */
    String handle(JsonNode update) {
        try {
            long updateId = update.path("update_id").asLong();
            if (update.has("message")) {
                return message(updateId, update.path("message"));
            }
            if (update.has("callback_query")) {
                return callback(updateId, update.path("callback_query"));
            }
            return "IGNORED";
        } catch (RuntimeException e) {
            log.warn("telegram update failed: {}", e.toString());
            return "ERROR " + e.getClass().getSimpleName();
        }
    }

    // ---------------------------------------------------------------------------------------------------- messages

    private String message(long updateId, JsonNode message) {
        JsonNode chat = message.path("chat");
        if (!"private".equals(chat.path("type").asString(""))) {
            return "NOT_PRIVATE";                                  // groups: never answered, never obeyed
        }
        String chatId = Long.toString(chat.path("id").asLong());
        String text = message.path("text").asString("").trim();
        long age = Instant.now().getEpochSecond() - message.path("date").asLong(0);
        if (age > SILENT_AGE_SECONDS) {
            return "TOO_OLD_SILENT";                               // kept (a /start still links from the table), no answer
        }
        if (text.startsWith("/start")) {
            if (firstReplyIn(chatId + ":start", Duration.ofSeconds(30))) {
                bot.send(chatId, "Code received. Press Check on the Telegram card of your Settings page to finish "
                        + "linking.", null);
            }
            return "START";
        }
        Caller caller = caller(chat, message.path("from"));
        if (caller == null) {
            if (firstReplyIn(chatId, UNLINKED_REPLY_EVERY)) {
                bot.send(chatId, "This chat is not linked to an auto-trade login. Link it from Settings in the app.", null);
            }
            return "UNLINKED";
        }
        if (age > props.maxAgeSeconds()) {
            bot.send(chatId, "Not run: this message is " + age + " s old (limit " + props.maxAgeSeconds()
                    + " s). Send it again if you still want it.", null);
            return "TOO_OLD";
        }
        if (message.has("forward_origin") || message.has("forward_date")) {
            // a forwarded "/flatten" is an old command passed on, not one typed now (its date is the forward's)
            return reply(caller, "Forwarded messages are never run. Type the command.", "FORWARDED");
        }
        String[] words = text.split("\\s+", 2);
        String verb = words[0].toLowerCase(Locale.ROOT).replaceAll("@.*$", "");
        String arg = words.length > 1 ? words[1].trim() : "";
        return switch (verb) {
            case "/whoami" -> reply(caller, whoami(caller), "WHOAMI");
            case "/status" -> reply(caller, status(caller), "STATUS");
            case "/positions" -> reply(caller, positions(caller), "POSITIONS");
            case "/orders" -> reply(caller, orders(caller), "ORDERS");
            case "/pnl" -> reply(caller, pnl(caller), "PNL");
            case "/login" -> reply(caller, login(caller), "LOGIN");
            case "/unlink" -> unlink(caller);
            case "/kill" -> kill(updateId, caller, arg);
            case "/killall" -> killAll(updateId, caller);
            case "/flatten" -> flatten(updateId, caller, arg);
            case "/flattenall" -> flattenAll(updateId, caller);
            case "/exit" -> exit(updateId, caller, arg);
            default -> reply(caller, help(), "HELP");
        };
    }

    private String reply(Caller caller, String text, String outcome) {
        bot.send(caller.chat(), text, null);
        return outcome;
    }

    /** True at most once per {@code every} for a key (and then remembers now). */
    private boolean firstReplyIn(String key, Duration every) {
        Instant now = Instant.now();
        if (throttled.size() > 10_000) {
            throttled.clear();                                // a flood of new chats: forget, never grow without end
        }
        Instant last = throttled.get(key);
        if (last != null && Duration.between(last, now).compareTo(every) < 0) {
            return false;
        }
        throttled.put(key, now);
        return true;
    }

    /** The linked, ACTIVE login of a private chat whose sender is the chat itself; null otherwise. */
    Caller caller(JsonNode chat, JsonNode from) {
        if (!"private".equals(chat.path("type").asString("")) || !chat.path("id").isNumber()
                || chat.path("id").asLong() != from.path("id").asLong(Long.MIN_VALUE)) {
            return null;
        }
        String chatId = Long.toString(chat.path("id").asLong());
        List<Map<String, Object>> users = jdbc.queryForList("select id, external_subject from acct.app_user "
                + "where telegram_chat_id = ? and status = 'ACTIVE'", chatId);
        if (users.size() != 1) {
            return null;                                            // none, or one chat on two logins: obey neither
        }
        try {
            Access.User user = access.user((String) users.getFirst().get("external_subject"));
            return new Caller(((Number) users.getFirst().get("id")).longValue(), chatId, user);
        } catch (ResponseStatusException e) {
            return null;                                            // no membership left
        }
    }

    static String help() {
        return """
                auto-trade bot
                /status   accounts, mode, session, kill switches
                /positions   open positions
                /orders   today's orders
                /pnl   today's result per account (booked + open at the bid)
                /login   accounts that still need today's broker login, with the link
                /exit [ID [LOTS]]   sell one open position now (pick it), or LOTS of it
                /flatten [ACCOUNT]   kill switch on + sell every open position
                /kill [ACCOUNT | STRATEGY]   stop new entries (nothing named: your whole workspace). Exits continue; nothing is sold
                /killall   platform admin: stop every LIVE account
                /flattenall   platform admin: kill switch on + sell every open position of every account
                /unlink   this chat stops being yours (link again from the web)
                /whoami

                Every action asks you to confirm. No answer within 10 s means it was not received: use the web or the \
                broker app. Releasing a switch, LIVE switches and entries are on the web only.""";
    }

    private String whoami(Caller c) {
        StringBuilder out = new StringBuilder(c.user().subject()).append('\n');
        for (Map.Entry<Long, String> e : c.user().roles().entrySet()) {
            out.append(tenantName(e.getKey())).append(": ").append(e.getValue()).append('\n');
        }
        return out.toString().trim();
    }

    private String status(Caller c) {
        StringBuilder out = new StringBuilder();
        Instant tick = actions.lastTick();
        out.append("Worker: ").append(tick == null ? "no tick yet"
                : "last tick " + Duration.between(tick, Instant.now()).toSeconds() + " s ago").append('\n');
        for (long tenant : c.user().tenants()) {
            for (Map<String, Object> r : jdbc.queryForList("select a.display_name, a.trade_mode, a.connection_status, "
                    + "a.enabled, (select count(*) from exec.position p where p.broker_account_id = a.id and p.status <> "
                    + "'CLOSED') open from acct.broker_account a where a.tenant_id = ? and a.account_role = 'FOLLOWER_TRADE' "
                    + "order by a.display_name", tenant)) {
                out.append(r.get("display_name")).append(" · ").append(r.get("trade_mode")).append(" · ")
                        .append(r.get("connection_status")).append(Boolean.TRUE.equals(r.get("enabled")) ? "" : " · off")
                        .append(" · open ").append(r.get("open")).append('\n');
            }
        }
        List<KillSwitches.Switch> engaged = kills.list(null).stream().filter(s -> s.tenantId() == null
                ? c.user().admin() || s.scope().equals("GLOBAL_LIVE") || s.scope().equals("INSTRUMENT")
                : c.user().sees(s.tenantId())).toList();
        out.append(engaged.isEmpty() ? "Kill switches: none" : "Kill switches:");
        for (KillSwitches.Switch s : engaged) {
            out.append("\n#").append(s.id()).append(' ').append(s.scope())
                    .append(s.accountId() == null ? "" : " " + accountName(s.accountId()))
                    .append(s.strategyId() == null ? "" : " " + s.strategyId()).append(": ").append(s.reason());
        }
        return out.toString();
    }

    private String positions(Caller c) {
        List<Map<String, Object>> open = openPositions(c, false);
        if (open.isEmpty()) {
            return "No open positions.";
        }
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> p : open) {
            out.append(positionLine(p)).append("\n  ").append(facts.line(((Number) p.get("id")).longValue())).append('\n');
        }
        return out.toString().trim();
    }

    /** Today's orders of every account the caller sees, newest first (17 Nov 2026, plan C2). */
    private String orders(Caller c) {
        Long[] tenants = c.user().tenants().toArray(Long[]::new);
        List<Map<String, Object>> rows = jdbc.queryForList("select to_char(e.created_at at time zone 'Asia/Kolkata', "
                + "'HH24:MI:SS') at, a.display_name, e.role, e.order_side, e.quantity_filled, e.quantity_requested, coalesce(i.symbol, (select p.symbol from "
                + "exec.position p where p.entry_trade_intent_id = i.id limit 1), 'contract ' || i.instrument_token) symbol, "
                + "e.status, coalesce(e.average_fill_price, e.limit_price, e.trigger_price) px from exec.order_execution e "
                + "join exec.trade_intent i on i.id = e.trade_intent_id join acct.broker_account a on a.id = e.broker_account_id "
                + "where e.tenant_id = any(?) and e.created_at >= " + IST_DAY_START + " order by e.id desc limit ?", tenants, MAX_LIST + 1);
        if (rows.isEmpty()) {
            return "No orders today.";
        }
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> r : rows.subList(0, Math.min(MAX_LIST, rows.size()))) {
            out.append(r.get("at")).append(' ').append(r.get("display_name")).append(' ').append(r.get("role")).append(' ')
                    .append(r.get("order_side")).append(' ').append(r.get("quantity_filled")).append('/')
                    .append(r.get("quantity_requested")).append(' ').append(r.get("symbol")).append(' ')
                    .append(r.get("status")).append(r.get("px") == null ? "" : " @" + round(r.get("px"))).append('\n');
        }
        if (rows.size() > MAX_LIST) {
            out.append("(the newest ").append(MAX_LIST).append("; the web has all)");
        }
        return out.toString().trim();
    }

    /** Today's result per account the caller sees: booked (closed today, partial sales) + open at the bid (plan C2). */
    private String pnl(Caller c) {
        StringBuilder out = new StringBuilder();
        double total = 0;
        for (long tenant : c.user().tenants()) {
            for (Map<String, Object> a : jdbc.queryForList("select a.id, a.display_name, a.trade_mode, a.broker_type, "
                    + "coalesce(sum(p.realised_gross - coalesce(p.charges, 0)) filter (where p.realised_gross is not null "
                    + "and coalesce(p.closed_at, p.opened_at) >= " + IST_DAY_START + "), 0)::float8 booked, count(p.id) filter (where p.status <> 'CLOSED') open, "
                    + "count(p.id) filter (where p.opened_at >= " + IST_DAY_START + " or p.status <> 'CLOSED') today from acct.broker_account a left join exec.position p "
                    + "on p.broker_account_id = a.id where a.tenant_id = ? and a.account_role = 'FOLLOWER_TRADE' "
                    + "group by a.id order by a.display_name", tenant)) {
                if (((Number) a.get("today")).intValue() == 0) {
                    continue;                                   // nothing traded today
                }
                double booked = ((Number) a.get("booked")).doubleValue(), open = 0;
                for (Long id : jdbc.queryForList("select id from exec.position where broker_account_id = ? and status "
                        + "<> 'CLOSED' order by id", Long.class, a.get("id"))) {
                    Object pnl = facts.of(id).get("pnl");
                    open += pnl == null ? 0 : ((Number) pnl).doubleValue();
                }
                total += booked + open;
                out.append(a.get("display_name")).append(" (").append("PAPER".equals(a.get("broker_type")) ? "PAPER"
                        : a.get("trade_mode")).append("): booked ").append(rupees(booked)).append(" · open ")
                        .append(rupees(open)).append(" at the bid (").append(a.get("open")).append(" open) = ")
                        .append(rupees(booked + open)).append('\n');
            }
        }
        return out.isEmpty() ? "Nothing traded today." : out.append("All: ").append(rupees(total)).toString();
    }

    /** The accounts the caller may act for that still need today's broker login, each with its link (plan C3). */
    private String login(Caller c) {
        StringBuilder out = new StringBuilder();
        for (long tenant : c.user().tenants()) {
            if (!c.user().mayAct(tenant)) {
                continue;
            }
            for (Map<String, Object> a : jdbc.queryForList("select a.id, a.display_name, a.broker_type from "
                    + "acct.broker_account a where a.tenant_id = ? and a.broker_type <> 'PAPER' and a.enabled "
                    + "and a.account_role = 'FOLLOWER_TRADE' and not exists (select 1 from acct.broker_session s where "
                    + "s.broker_account_id = a.id and s.status = 'ACTIVE' and s.expires_at > now()) order by a.display_name",
                    tenant)) {
                String broker = (String) a.get("broker_type");
                String path = switch (broker) {
                    case "ZERODHA" -> "/api/execution/accounts/" + a.get("id") + "/kite/login";
                    case "UPSTOX" -> "/api/execution/accounts/" + a.get("id") + "/upstox/login";
                    default -> "/brokers";                      // Kotak (TOTP) and Groww (token) log in on the web page
                };
                out.append(a.get("display_name")).append(" (").append(broker).append("): ")
                        .append(publicBaseUrl == null ? "on the web (" + path + ")" : publicBaseUrl + path).append('\n');
            }
        }
        return out.isEmpty() ? "Every broker account you trade is logged in for today."
                : "Needs today's login:\n" + out.toString().trim();
    }

    /** This chat stops being the caller's (plan C1): no command from it runs any more; the web links it again. */
    private String unlink(Caller c) {
        jdbc.update("update acct.app_user set telegram_chat_id = null where id = ?", c.appUserId());
        for (long tenant : c.user().tenants()) {
            kills.audit(tenant, null, c.actor(), "TELEGRAM_UNLINKED", Map.of("chat", c.chat()));
        }
        bot.send(c.chat(), "Unlinked: this chat runs nothing any more. Link it again from the web (Settings).", null);
        return "UNLINKED_BY_USER";
    }

    private static String rupees(double v) {
        long r = Math.round(v);
        return (r < 0 ? "-" : r > 0 ? "+" : "") + "Rs " + String.format(Locale.ROOT, "%,d", Math.abs(r));
    }

    // ---------------------------------------------------------------------------------------------------- writes

    private String kill(long updateId, Caller c, String arg) {
        if (!arg.isEmpty()) {
            List<BrokerAccount> named = accountsNamed(c, arg);
            if (named.isEmpty()) {
                // 17 Nov 2026 (plan C4): a strategy of the caller's workspaces stops in that workspace
                String strategy = arg.trim().toLowerCase(Locale.ROOT);
                List<Long> where = c.user().tenants().stream().filter(c.user()::emergency)
                        .filter(t -> strategyIn(t, strategy)).toList();
                if (where.size() == 1) {
                    return card(updateId, c, "KILL_STRATEGY", where.getFirst(), null, null, null, strategy);
                }
                if (where.size() > 1) {
                    List<List<TelegramBot.Button>> rows = new ArrayList<>();
                    for (long t : where.subList(0, Math.min(MAX_BUTTONS, where.size()))) {
                        rows.add(List.of(new TelegramBot.Button("Stop " + strategy + " in " + tenantName(t),
                                "s:" + t + ":" + strategy)));
                    }
                    bot.send(c.chat(), "In which workspace?", rows);
                    return "PICK";
                }
                return reply(c, "No account or strategy \"" + arg + "\" you may stop. /status lists your accounts.",
                        "NO_ACCOUNT");
            }
            if (named.size() > 1) {
                return pickAccount(c, named, "Stop ", "p:ka:");
            }
            BrokerAccount a = named.getFirst();
            return card(updateId, c, "KILL_ACCOUNT", a.tenantId(), a, null);
        }
        List<Long> tenants = c.user().tenants().stream().filter(c.user()::emergency).toList();
        if (tenants.isEmpty()) {
            return reply(c, "Your role here is read-only.", "FORBIDDEN");
        }
        if (tenants.size() == 1) {
            return card(updateId, c, "KILL_TENANT", tenants.getFirst(), null, null);
        }
        List<List<TelegramBot.Button>> rows = new ArrayList<>();
        for (long t : tenants.subList(0, Math.min(MAX_BUTTONS, tenants.size()))) {
            rows.add(List.of(new TelegramBot.Button("Stop " + tenantName(t), "p:kt:" + t)));
        }
        bot.send(c.chat(), "Whose workspace? (or /kill ACCOUNT)", rows);
        return "PICK";
    }

    /** A Book button of a level alert (PositionAlerts): the Exit card for that many lots of it (0: all of it). */
    private String bookFromAlert(long updateId, Caller c, long positionId, int lots) {
        Map<String, Object> p = openPosition(c, positionId);
        if (p == null) {
            return reply(c, "Position " + positionId + " is not open (or not yours to exit).", "NO_POSITION");
        }
        Long quantity = null;
        long lot = lotSize(positionId), open = ((Number) p.get("quantity_open")).longValue();
        if (lots > 0 && lot > 0 && lots * lot < open) {
            quantity = lots * lot;
        }
        return card(updateId, c, "EXIT", ((Number) p.get("tenant_id")).longValue(),
                accounts.byId(((Number) p.get("broker_account_id")).longValue()).orElseThrow(), positionId, quantity);
    }

    /** Whether a strategy is one the workspace's accounts take (an enabled subscription). */
    private boolean strategyIn(long tenant, String strategy) {
        return strategy.matches("[a-z0-9][a-z0-9-]{0,63}") && Boolean.TRUE.equals(jdbc.queryForObject("select exists "
                + "(select 1 from acct.strategy_subscription s join acct.broker_account a on a.id = s.broker_account_id "
                + "where a.tenant_id = ? and s.strategy_id = ? and s.enabled)", Boolean.class, tenant, strategy));
    }

    private String flattenAll(long updateId, Caller c) {
        if (!c.user().admin()) {
            return reply(c, "/flattenall needs the platform admin.", "FORBIDDEN");
        }
        return card(updateId, c, "FLATTEN_ALL", null, null, null);
    }

    private String killAll(long updateId, Caller c) {
        if (!c.user().admin()) {
            return reply(c, "/killall needs the platform admin.", "FORBIDDEN");
        }
        return card(updateId, c, "KILL_GLOBAL", null, null, null);
    }

    private String flatten(long updateId, Caller c, String arg) {
        if (!arg.isEmpty()) {
            List<BrokerAccount> named = accountsNamed(c, arg);
            if (named.isEmpty()) {
                return reply(c, "No account \"" + arg + "\" you may flatten. /status lists yours.", "NO_ACCOUNT");
            }
            if (named.size() > 1) {
                return pickAccount(c, named, "Flatten ", "p:f:");
            }
            BrokerAccount a = named.getFirst();
            return card(updateId, c, "FLATTEN", a.tenantId(), a, null);
        }
        List<BrokerAccount> actable = actable(c);
        if (actable.isEmpty()) {
            return reply(c, "No account you may flatten.", "NO_ACCOUNT");
        }
        return pickAccount(c, actable, "Flatten ", "p:f:");
    }

    /**
     * A list of accounts to pick from (an account name held in two workspaces, or every account): each button names the
     * workspace, the mode and the open positions, so the wrong one is not taken for the right one.
     */
    private String pickAccount(Caller c, List<BrokerAccount> list, String label, String data) {
        List<List<TelegramBot.Button>> rows = new ArrayList<>();
        for (BrokerAccount a : list.subList(0, Math.min(MAX_BUTTONS, list.size()))) {
            rows.add(List.of(new TelegramBot.Button(label + a.displayName() + " · " + tenantName(a.tenantId()) + " ("
                    + a.tradeMode() + ", open " + openCount(a.id()) + ")", data + a.id())));
        }
        bot.send(c.chat(), list.size() > MAX_BUTTONS ? "Which account? (the first " + MAX_BUTTONS + " of " + list.size()
                + "; the web has all)" : "Which account?", rows);
        return "PICK";
    }

    private String exit(long updateId, Caller c, String arg) {
        if (!arg.isEmpty()) {
            String[] parts = arg.trim().split("\\s+");
            Map<String, Object> p = parts.length <= 2 && parts[0].matches("#?\\d{1,18}")
                    ? openPosition(c, Long.parseLong(parts[0].replace("#", ""))) : null;
            if (p == null) {
                return reply(c, "No open position " + parts[0] + " you may exit. /exit lists them.", "NO_POSITION");
            }
            long positionId = ((Number) p.get("id")).longValue();
            Long quantity = null;
            if (parts.length == 2) {
                // 17 Nov 2026: /exit ID LOTS sells whole lots of it; as many lots as are open (or more) is all of it
                if (!parts[1].matches("\\d{1,4}") || Integer.parseInt(parts[1]) < 1) {
                    return reply(c, "LOTS is a whole number of lots, 1 or more: /exit " + positionId + " 2", "BAD_LOTS");
                }
                long lot = lotSize(positionId), open = ((Number) p.get("quantity_open")).longValue();
                if (lot <= 0) {
                    return reply(c, "The lot size of #" + positionId + " is not known: exit all of it, or part on the web.",
                            "BAD_LOTS");
                }
                long asked = Integer.parseInt(parts[1]) * lot;
                quantity = asked >= open ? null : asked;
            }
            return card(updateId, c, "EXIT", ((Number) p.get("tenant_id")).longValue(),
                    accounts.byId(((Number) p.get("broker_account_id")).longValue()).orElseThrow(), positionId, quantity);
        }
        List<Map<String, Object>> open = openPositions(c, true);
        if (open.isEmpty()) {
            return reply(c, "No open position you may exit.", "NO_POSITION");
        }
        List<List<TelegramBot.Button>> rows = new ArrayList<>();
        for (Map<String, Object> p : open.subList(0, Math.min(MAX_BUTTONS, open.size()))) {
            rows.add(List.of(new TelegramBot.Button(positionLine(p), "p:e:" + p.get("id"))));
        }
        bot.send(c.chat(), "Which position? (you confirm on the next card; /exit ID LOTS sells part of one)"
                + (open.size() > MAX_BUTTONS
                ? "\nThe first " + MAX_BUTTONS + " of " + open.size() + ": /exit ID for the others (/positions)" : ""), rows);
        return "PICK";
    }

    /** Shows the confirm card of a write command (rights checked first) and stores it PENDING. */
    private String card(long updateId, Caller c, String verb, Long tenant, BrokerAccount account, Long positionId) {
        return card(updateId, c, verb, tenant, account, positionId, null);
    }

    /** {@code quantity}: an EXIT of part of the position (whole lots); null: all of it. */
    private String card(long updateId, Caller c, String verb, Long tenant, BrokerAccount account, Long positionId,
                        Long quantity) {
        return card(updateId, c, verb, tenant, account, positionId, quantity, null);
    }

    /** {@code strategy}: the strategy a KILL_STRATEGY stops. */
    private String card(long updateId, Caller c, String verb, Long tenant, BrokerAccount account, Long positionId,
                        Long quantity, String strategy) {
        if (verb.equals("KILL_STRATEGY") && (tenant == null || !strategyIn(tenant, strategy))) {
            return reply(c, "No strategy \"" + strategy + "\" in that workspace.", "NO_STRATEGY");
        }
        String refused = refusal(c, verb, tenant, account, positionId);
        if (refused != null) {
            return reply(c, refused, "FORBIDDEN");
        }
        String nonce = nonce();
        Long botId = bot.botId();
        long id = jdbc.queryForObject("insert into exec.telegram_command (bot_id, update_id, app_user_id, chat_id, tenant_id, "
                        + "verb, broker_account_id, position_id, trade_mode, nonce, quantity, strategy_id) values (?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?, ?, ?, ?) returning id", Long.class, botId == null ? 0 : botId, updateId, c.appUserId(),
                Long.parseLong(c.chat()), tenant, verb, account == null ? null : account.id(), positionId,
                account == null ? null : account.tradeMode().name(), nonce, quantity, strategy);
        String text = "T" + id + " " + describe(verb, tenant, account, positionId, quantity, strategy) + "\n\nConfirm within "
                + props.confirmSeconds() + " s.";
        Long message = bot.send(c.chat(), text, List.of(
                List.of(new TelegramBot.Button(confirmLabel(verb, account), "c:" + id + ":" + nonce)),
                List.of(new TelegramBot.Button("Cancel", "x:" + id + ":" + nonce))));
        if (message == null) {
            jdbc.update("update exec.telegram_command set status = 'FAILED', done_at = now(), result = 'card not delivered' "
                    + "where id = ?", id);
            return "CARD_NOT_SENT";
        }
        jdbc.update("update exec.telegram_command set card_message_id = ? where id = ?", message, id);
        return "CARD T" + id;
    }

    /**
     * /flattenall (plan C5, platform admin): every account with an open position gets its kill switch, then is flattened;
     * a LIVE account is only stopped while selling on LIVE over Telegram is off.
     */
    private List<String> flattenEverything(Caller c, String reason) throws Exception {
        List<String> lines = new ArrayList<>();
        for (Long id : jdbc.queryForList("select distinct broker_account_id from exec.position where status <> 'CLOSED' "
                + "order by 1", Long.class)) {
            BrokerAccount a = accounts.byId(id).orElse(null);
            if (a == null || a.role() != com.autotrade.account.AccountRole.FOLLOWER_TRADE) {
                continue;
            }
            kills.engageAccount(a, "FLATTEN ALL by " + c.actor() + ": " + reason, c.actor());
            if (a.tradeMode() == TradeMode.LIVE && !props.liveExits()) {
                lines.add(a.displayName() + ": LIVE, stopped only (exit it on the web or in the broker's app)");
                continue;
            }
            for (String line : actions.flatten(a.id(), c.actor(), reason)) {
                lines.add(a.displayName() + ": " + line);
            }
        }
        return lines;
    }

    /** Why this person may not do this now, or null. The web's rules ({@link Access.User}). */
    private String refusal(Caller c, String verb, Long tenant, BrokerAccount account, Long positionId) {
        Access.User u = c.user();
        return switch (verb) {
            case "KILL_GLOBAL" -> u.admin() ? null : "/killall needs the platform admin.";
            case "KILL_TENANT", "KILL_STRATEGY" -> tenant != null && u.sees(tenant) && u.emergency(tenant) ? null
                    : "Your role there is read-only.";
            case "FLATTEN_ALL" -> u.admin() ? null : "/flattenall needs the platform admin.";
            case "KILL_ACCOUNT", "FLATTEN", "EXIT" -> {
                if (account == null || !u.sees(account.tenantId())) {
                    yield "No such account of yours.";
                }
                if (!u.emergency(account.tenantId())) {
                    yield "Your role for " + account.displayName() + " is read-only.";
                }
                if (verb.equals("EXIT") && (positionId == null || openPosition(c, positionId) == null)) {
                    yield "Position " + positionId + " is not open.";
                }
                // review 16 Nov 2026: the plan's LIVE gate. Nothing here sells on a LIVE account until the user turns
                // autotrade.execution.telegram.live-exits on; a kill (it only stops entries) works on LIVE either way
                if (!verb.equals("KILL_ACCOUNT") && account.tradeMode() == TradeMode.LIVE && !props.liveExits()) {
                    yield account.displayName() + " is LIVE: selling on a LIVE account over Telegram is off. /kill it here, "
                            + "then exit on the web or in your broker's app.";
                }
                yield null;
            }
            default -> "unknown command";
        };
    }

    private String describe(String verb, Long tenant, BrokerAccount a, Long positionId, Long quantity, String strategy) {
        return switch (verb) {
            case "KILL_STRATEGY" -> "KILL strategy " + strategy + " in workspace " + tenantName(tenant) + ".\nNo new entries of "
                    + strategy + " on any of its accounts. Exits continue; nothing is sold. Release on the web only.";
            case "FLATTEN_ALL" -> {
                StringBuilder s = new StringBuilder("FLATTEN ALL: on every account with an open position, its kill switch "
                        + "on, broker stops cancelled, every position auto-trade opened sold now:");
                List<Map<String, Object>> open = jdbc.queryForList("select a.display_name, a.trade_mode, count(*) n from "
                        + "exec.position p join acct.broker_account a on a.id = p.broker_account_id where p.status <> 'CLOSED' "
                        + "group by a.id order by a.display_name");
                open.forEach(r -> s.append("\n  ").append(r.get("display_name")).append(" (").append(r.get("trade_mode"))
                        .append("): ").append(r.get("n")).append(" open"));
                if (open.isEmpty()) {
                    s.append(" none open now.");
                }
                if (!props.liveExits()) {
                    s.append("\nLIVE accounts are only stopped (selling on LIVE over Telegram is off): exit them on the web.");
                }
                yield s.append("\nSold at the market's bid: the spread is a cost. Release the switches on the web.").toString();
            }
            case "KILL_GLOBAL" -> "KILL ALL LIVE ACCOUNTS (GLOBAL_LIVE).\nNew entries stop on every LIVE account. Exits "
                    + "continue; nothing is sold. Release on the web only.";
            case "KILL_TENANT" -> "KILL workspace " + tenantName(tenant) + " (" + tenantOpen(tenant) + " open position(s)).\n"
                    + "New entries stop on all its accounts. Exits continue; nothing is sold (use /flatten or /exit). "
                    + "Release on the web only.";
            case "KILL_ACCOUNT" -> "KILL " + a.displayName() + " (" + a.tradeMode() + ", " + openCount(a.id())
                    + " open position(s)), workspace " + tenantName(a.tenantId()) + ".\nNew entries stop. Exits continue; "
                    + "nothing is sold (use /flatten or /exit). Release on the web only.";
            case "FLATTEN" -> {
                StringBuilder s = new StringBuilder("FLATTEN " + a.displayName() + " (" + a.tradeMode() + "), workspace "
                        + tenantName(a.tenantId()) + ".\nKill switch on, broker stops cancelled, then every position "
                        + "auto-trade opened sold now (trades you placed yourself in the broker's app are NOT touched):");
                List<Map<String, Object>> open = jdbc.queryForList("select id, symbol, quantity_open, average_entry from "
                        + "exec.position where broker_account_id = ? and status <> 'CLOSED' order by id", a.id());
                open.forEach(p -> s.append("\n  #").append(p.get("id")).append(' ').append(p.get("symbol")).append(" qty ")
                        .append(p.get("quantity_open")).append(" avg ").append(round(p.get("average_entry"))));
                if (open.isEmpty()) {
                    s.append(" none open now.");
                }
                yield s.append("\nSold at the market's bid: the spread is a cost. Release the switch on the web.").toString();
            }
            case "EXIT" -> {
                Map<String, Object> p = jdbc.queryForMap("select id, symbol, quantity_open, average_entry, strategy_id "
                        + "from exec.position where id = ?", positionId);
                long lot = lotSize(positionId);
                String what = quantity == null ? "all of it is sold now at the bid (part: /exit " + positionId + " LOTS)"
                        : (lot > 0 ? quantity / lot + " lot(s), " : "") + quantity + " of " + p.get("quantity_open")
                                + " are sold now at the bid; the rest stays open with its broker stop placed again";
                yield "EXIT " + a.displayName() + " (" + a.tradeMode() + "): #" + p.get("id") + " " + p.get("symbol")
                        + " qty " + p.get("quantity_open") + " avg " + round(p.get("average_entry")) + " (" + p.get("strategy_id")
                        + "), workspace " + tenantName(a.tenantId()) + ".\nNow: " + facts.line(positionId)
                        + "\nIts broker stop is cancelled and " + what + ". The account keeps trading.";
            }
            default -> verb;
        };
    }

    private static String confirmLabel(String verb, BrokerAccount a) {
        String live = a != null && a.tradeMode().name().equals("LIVE") ? " (LIVE)" : "";
        return switch (verb) {
            case "KILL_GLOBAL" -> "Confirm: kill all LIVE";
            case "KILL_TENANT", "KILL_ACCOUNT" -> "Confirm kill" + live;
            case "FLATTEN" -> "Confirm flatten" + live;
            case "FLATTEN_ALL" -> "Confirm: flatten ALL";
            case "KILL_STRATEGY" -> "Confirm kill";
            case "EXIT" -> "Confirm exit" + live;
            default -> "Confirm";
        };
    }

    // ---------------------------------------------------------------------------------------------------- taps

    private String callback(long updateId, JsonNode q) {
        String id = q.path("id").asString("");
        JsonNode message = q.path("message");
        Caller caller = caller(message.path("chat"), q.path("from"));
        if (caller == null) {
            bot.answer(id, "Not linked");
            return "UNLINKED";
        }
        String[] data = q.path("data").asString("").split(":");
        try {
            if (data.length == 3 && data[0].equals("p")) {
                bot.answer(id, "");
                return pick(updateId, caller, data[1], Long.parseLong(data[2]));
            }
            if (data.length == 3 && data[0].equals("b")) {           // a level alert's Book half / Book all (0 = all)
                bot.answer(id, "");
                return bookFromAlert(updateId, caller, Long.parseLong(data[1]), Integer.parseInt(data[2]));
            }
            if (data.length == 3 && data[0].equals("s")) {           // /kill STRATEGY: the workspace picked
                bot.answer(id, "");
                return card(updateId, caller, "KILL_STRATEGY", Long.parseLong(data[1]), null, null, null, data[2]);
            }
            if (data.length == 3 && (data[0].equals("c") || data[0].equals("x"))) {
                return decide(caller, id, data[0].equals("c"), Long.parseLong(data[1]), data[2]);
            }
        } catch (NumberFormatException e) {
            // falls through
        }
        bot.answer(id, "Unknown button");
        return "BAD_DATA";
    }

    /** A pick from a list (workspace, account, position) → its confirm card. */
    private String pick(long updateId, Caller c, String what, long target) {
        return switch (what) {
            case "kt" -> card(updateId, c, "KILL_TENANT", target, null, null);
            case "ka", "f" -> {
                BrokerAccount a = accounts.byId(target).orElse(null);
                yield card(updateId, c, what.equals("f") ? "FLATTEN" : "KILL_ACCOUNT", a == null ? null : a.tenantId(), a,
                        null);
            }
            case "e" -> {
                Map<String, Object> p = openPosition(c, target);
                if (p == null) {
                    yield reply(c, "Position " + target + " is not open (or not yours).", "NO_POSITION");
                }
                yield card(updateId, c, "EXIT", ((Number) p.get("tenant_id")).longValue(),
                        accounts.byId(((Number) p.get("broker_account_id")).longValue()).orElseThrow(), target);
            }
            default -> "BAD_DATA";
        };
    }

    /** Confirm or Cancel on a card: one compare-and-set, by the card's own person, within its time. */
    private String decide(Caller c, String callbackId, boolean confirm, long commandId, String nonce) {
        List<Map<String, Object>> rows = jdbc.queryForList("update exec.telegram_command set status = ?, "
                        + "confirmed_at = case when ? then now() end, done_at = case when ? then null else now() end "
                        + "where id = ? and nonce = ? and app_user_id = ? and chat_id = ? and status = 'PENDING' "
                        + "and created_at > now() - make_interval(secs => ?) "
                        + "returning id, verb, tenant_id, broker_account_id, position_id, card_message_id, trade_mode, quantity, "
                        + "strategy_id",
                confirm ? "CONFIRMED" : "CANCELLED", confirm, confirm, commandId, nonce, c.appUserId(),
                Long.parseLong(c.chat()), props.confirmSeconds());
        if (rows.isEmpty()) {
            int expired = jdbc.update("update exec.telegram_command set status = 'EXPIRED', done_at = now() where id = ? "
                    + "and nonce = ? and app_user_id = ? and status = 'PENDING'", commandId, nonce, c.appUserId());
            bot.answer(callbackId, expired > 0 ? "Expired: not run" : "Already used");
            if (expired > 0) {
                editCard(c, commandId, "T" + commandId + " EXPIRED: not run. Send the command again.");
            }
            return expired > 0 ? "EXPIRED T" + commandId : "STALE T" + commandId;
        }
        Map<String, Object> cmd = rows.getFirst();
        if (!confirm) {
            bot.answer(callbackId, "Cancelled");
            editCard(c, commandId, "T" + commandId + " cancelled: nothing done.");
            return "CANCELLED T" + commandId;
        }
        bot.answer(callbackId, "Running");
        editCard(c, commandId, "T" + commandId + " " + cmd.get("verb") + ": running…");
        runner.execute(() -> {
            try {
                run(c, cmd);
            } catch (RuntimeException e) {                           // the result could not be stored or sent
                log.error("telegram command T{}: result not recorded", commandId, e);
                bot.send(c.chat(), "T" + commandId + ": the result could not be recorded (" + e.getClass().getSimpleName()
                        + "). It may have run. Check /positions and the Accounts page.", null);
            }
        });
        return "CONFIRMED T" + commandId;
    }

    /**
     * Lines of a flatten or exit that leave something unsold (ExecutionLoop / FollowerSimulator wording): the card then
     * says INCOMPLETE, not DONE.
     */
    static boolean incomplete(List<String> lines) {
        // a short fill reads "N of M sold (CODE reason); K stay open ..." (17 Nov 2026), a stop not re-placed "NOT placed"
        return lines.stream().map(l -> l.toLowerCase(Locale.ROOT)).anyMatch(l -> l.contains("not flattened")
                || l.contains("has not started") || l.contains("keeps its broker stop") || l.contains("unknown outcome")
                || l.contains(" sold (") || l.contains("not placed yet"));
    }

    /** Runs a confirmed command (the rights checked again) and edits its card with the result. */
    private void run(Caller tapped, Map<String, Object> cmd) {
        long id = ((Number) cmd.get("id")).longValue();
        String verb = (String) cmd.get("verb");
        Long tenant = cmd.get("tenant_id") == null ? null : ((Number) cmd.get("tenant_id")).longValue();
        Long accountId = cmd.get("broker_account_id") == null ? null : ((Number) cmd.get("broker_account_id")).longValue();
        Long positionId = cmd.get("position_id") == null ? null : ((Number) cmd.get("position_id")).longValue();
        Long quantity = cmd.get("quantity") == null ? null : ((Number) cmd.get("quantity")).longValue();
        String strategy = (String) cmd.get("strategy_id");
        String cardMode = (String) cmd.get("trade_mode");
        String status;
        String result;
        try {
            Caller c = new Caller(tapped.appUserId(), tapped.chat(), access.user(tapped.user().subject()));  // fresh rights
            BrokerAccount a = accountId == null ? null : accounts.byId(accountId).orElse(null);
            String refused = refusal(c, verb, tenant, a, positionId);
            if (refused == null && a != null && cardMode != null && !cardMode.equals(a.tradeMode().name())) {
                // the card said PAPER and the account is LIVE now (or the other way): what was confirmed is not this
                refused = a.displayName() + " is " + a.tradeMode() + " now, the card said " + cardMode
                        + ". Nothing done: send the command again.";
            }
            if (refused != null) {
                status = "REFUSED";
                result = refused;
            } else {
                String reason = "Telegram " + verb.toLowerCase(Locale.ROOT) + " T" + id + " by " + c.user().subject();
                List<String> lines = switch (verb) {
                    case "KILL_GLOBAL" -> List.of("switch #" + kills.engage(null, "GLOBAL_LIVE", null, null, null, reason,
                            c.actor()) + " engaged: every LIVE account stops new entries");
                    case "KILL_TENANT" -> List.of("switch #" + kills.engage(tenant, "TENANT", null, null, null, reason,
                            c.actor()) + " engaged on " + tenantName(tenant) + "; open positions: " + tenantOpen(tenant));
                    case "KILL_ACCOUNT" -> List.of("switch #" + kills.engage(a.tenantId(), "ACCOUNT", a.id(), null, null,
                            reason, c.actor()) + " engaged on " + a.displayName() + "; open positions: " + openCount(a.id()));
                    case "KILL_STRATEGY" -> List.of("switch #" + kills.engage(tenant, "STRATEGY", null, strategy, null,
                            reason, c.actor()) + " engaged: " + strategy + " takes no new entries in " + tenantName(tenant));
                    case "FLATTEN_ALL" -> flattenEverything(c, reason);
                    case "FLATTEN" -> {
                        // review 16 Nov 2026: the switch first, here (idempotent), so entries stop even when the worker
                        // has not started and the flatten itself can do nothing yet
                        kills.engageAccount(a, "FLATTEN by " + c.actor() + ": " + reason, c.actor());
                        yield actions.flatten(a.id(), c.actor(), reason);
                    }
                    case "EXIT" -> actions.exitPosition(a.id(), positionId, c.actor(), quantity);
                    default -> throw new IllegalStateException(verb);
                };
                status = incomplete(lines) ? "INCOMPLETE" : "DONE";
                result = lines.isEmpty() ? "nothing was open" : String.join("\n", lines);
            }
        } catch (IllegalArgumentException | ResponseStatusException e) {
            status = "REFUSED";
            result = String.valueOf(e.getMessage());
        } catch (java.util.concurrent.TimeoutException e) {
            // the account's lane did not answer within its 120 s: the sale is still queued there and may go through
            status = "INCOMPLETE";
            result = "no answer from the account within 120 s: the sale may still go through. Check /positions before "
                    + "sending it again.";
            log.error("telegram command T{} {} timed out", id, verb);
        } catch (Exception e) {
            status = "FAILED";
            result = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("telegram command T{} {} failed", id, verb, e);
        }
        jdbc.update("update exec.telegram_command set status = ?, done_at = now(), result = ? where id = ?", status, result, id);
        kills.audit(tenant, accountId, tapped.actor(), "TELEGRAM_COMMAND", Map.of("ref", "T" + id, "verb", verb,
                "status", status, "result", result.length() > 500 ? result.substring(0, 500) : result));
        String text = "T" + id + " " + verb + " " + status + "\n" + result
                + (status.equals("DONE") && verb.startsWith("KILL") ? "\nRelease on the web once it is understood." : "")
                + (status.equals("DONE") ? "" : "\nCheck /positions and the Accounts page.");
        editCard(tapped, id, text);
    }

    private void editCard(Caller c, long commandId, String text) {
        Long message = jdbc.queryForList("select card_message_id from exec.telegram_command where id = ?", Long.class,
                commandId).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (message == null || !bot.edit(c.chat(), message, text, null)) {
            bot.send(c.chat(), text, null);                          // an old card cannot always be edited
        }
    }

    // ---------------------------------------------------------------------------------------------------- lookups

    /**
     * Follower accounts the caller may stop: enabled ones, and any turned off that still hold a position (an account
     * switched off mid-day is exactly one you may need to flatten). Those holding positions first, then by name.
     */
    private List<BrokerAccount> actable(Caller c) {
        List<BrokerAccount> out = new ArrayList<>();
        Map<Long, Integer> open = new java.util.HashMap<>();
        for (long t : c.user().tenants()) {
            if (!c.user().emergency(t)) {
                continue;
            }
            for (BrokerAccount a : accounts.ofTenant(t)) {
                if (a.role().name().equals("FOLLOWER_TRADE")) {
                    int n = openCount(a.id());
                    if (a.enabled() || n > 0) {
                        out.add(a);
                        open.put(a.id(), n);
                    }
                }
            }
        }
        out.sort(java.util.Comparator.<BrokerAccount>comparingInt(a -> open.get(a.id()) > 0 ? 0 : 1)
                .thenComparing(BrokerAccount::displayName));
        return out;
    }

    /** The caller's accounts of that name: names are unique within a workspace only, so two workspaces may share one. */
    private List<BrokerAccount> accountsNamed(Caller c, String name) {
        List<BrokerAccount> out = new ArrayList<>();
        for (long t : c.user().tenants()) {
            for (BrokerAccount a : accounts.ofTenant(t)) {
                if (a.displayName().equalsIgnoreCase(name.trim())) {
                    out.add(a);
                }
            }
        }
        return out;
    }

    /** Open positions in the caller's tenants ({@code acting}: only where they may stop trouble). */
    private List<Map<String, Object>> openPositions(Caller c, boolean acting) {
        Long[] tenants = c.user().tenants().stream().filter(t -> !acting || c.user().emergency(t)).toArray(Long[]::new);
        return jdbc.queryForList("select p.id, p.tenant_id, p.broker_account_id, a.display_name, a.trade_mode, p.symbol, "
                + "p.quantity_open, p.average_entry, p.strategy_id from exec.position p join acct.broker_account a on "
                + "a.id = p.broker_account_id where p.status <> 'CLOSED' and p.tenant_id = any(?) order by p.opened_at",
                (Object) tenants);
    }

    private Map<String, Object> openPosition(Caller c, long positionId) {
        return openPositions(c, true).stream().filter(p -> ((Number) p.get("id")).longValue() == positionId).findFirst()
                .orElse(null);
    }

    private static String positionLine(Map<String, Object> p) {
        return "#" + p.get("id") + " " + p.get("display_name") + " " + p.get("trade_mode") + " " + p.get("symbol") + " qty "
                + p.get("quantity_open") + " @" + round(p.get("average_entry"));
    }

    /** The position's lot size (its entry's signal), 0 when unknown. */
    private long lotSize(long positionId) {
        return jdbc.queryForList("select d.lot_size from exec.position p join exec.trade_intent i on i.id = "
                + "p.entry_trade_intent_id join exec.trade_batch b on b.id = i.trade_batch_id join exec.signal_decision d "
                + "on d.id = b.signal_decision_id where p.id = ?", Long.class, positionId).stream().findFirst().orElse(0L);
    }

    private int openCount(long accountId) {
        return jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ? and status <> 'CLOSED'",
                Integer.class, accountId);
    }

    private int tenantOpen(Long tenant) {
        return tenant == null ? 0 : jdbc.queryForObject("select count(*) from exec.position where tenant_id = ? "
                + "and status <> 'CLOSED'", Integer.class, tenant);
    }

    private String tenantName(Long tenant) {
        return tenant == null ? "-" : jdbc.queryForList("select name from acct.tenant where id = ?", String.class, tenant)
                .stream().findFirst().orElse("#" + tenant);
    }

    private String accountName(long accountId) {
        return accounts.byId(accountId).map(BrokerAccount::displayName).orElse("#" + accountId);
    }

    private static String round(Object price) {
        return price == null ? "-" : String.format(Locale.ROOT, "%.2f", ((Number) price).doubleValue());
    }

    private String nonce() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            s.append(NONCE_ALPHABET.charAt(random.nextInt(NONCE_ALPHABET.length())));
        }
        return s.toString();
    }
}
