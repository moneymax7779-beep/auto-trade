package com.autotrade.exec.app;

import com.autotrade.broker.Egress;
import com.autotrade.execution.AccountEgress;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The automatic static IPs in the worker (18 Nov 2026, user: "static ip should be auto provisioned to enable LIVE").
 * The host provisioner ({@code bin/egress}) writes PROVISIONED rows to acct.broker_egress; this bean
 * <ul>
 *   <li>{@link #refresh}: every few seconds gives {@link AccountEgress} the provisioned accounts' container addresses
 *       (their broker calls bind to them from the next call on; an address not on this host yet is refused, never the
 *       default route) and the reasons each one is not ready for LIVE. A changed address is not applied under an
 *       account that is LIVE or holds a position or a working order (its broker session and registered IP are the
 *       old ones): it waits until the account is flat, except at startup, before any route opens;</li>
 *   <li>{@link #checkAll}: every few minutes, on its own thread, asks two independent echo services through each
 *       provisioned account's own address which public IP the call came from. Conclusive: every service answered (all
 *       the reserved IP = pass, any other IP = fail), the address is not on this host (fail), or no service answers
 *       through the address while one answers over the shared route (fail: the account's route is broken). A service
 *       that does not answer otherwise is inconclusive: logged, the last conclusive result stands (review 18 Nov 2026: an echo-service outage
 *       must not lock a LIVE account out). Nothing about the account is sent: a plain GET with no headers of ours.</li>
 * </ul>
 * LIVE for a provisioned account needs a conclusive pass within {@link #FRESH} and the owner's typed word that the IP is
 * registered at the broker. The application itself never changes the network.
 */
@Component
class EgressRegistry {

    private static final Logger log = LoggerFactory.getLogger(EgressRegistry.class);

    /** How old the last conclusive passing check may be for LIVE (the check runs every 5 minutes). */
    static final Duration FRESH = Duration.ofHours(12);

    /** Two echo services run by different companies; each answers the caller's public IPv4 as plain text. */
    static final List<URI> ECHO = List.of(URI.create("https://api.ipify.org"),
            URI.create("https://checkip.amazonaws.com"));

    private static final java.util.regex.Pattern IPV4 = java.util.regex.Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");

    private final JdbcTemplate jdbc;
    private final AccountEgress egress;
    private final List<URI> echo;
    /** (bound address, echo URI) → the public IP it answered; a stand-in in tests. */
    private final BiFunction<InetAddress, URI, String> ask;
    /** Whether an address is on this host ({@link Egress#assigned}; a stand-in in tests). */
    private final java.util.function.Predicate<InetAddress> assigned;
    /** The provisioned addresses in effect (what {@link #apply} last gave {@link AccountEgress}). */
    private final Map<Long, InetAddress> applied = new java.util.HashMap<>();
    private boolean started;
    /** Log lines already written (each problem once, not every 10 s). */
    private final java.util.Set<String> logged = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean checking = new java.util.concurrent.atomic.AtomicBoolean();

    @org.springframework.beans.factory.annotation.Autowired
    EgressRegistry(DataSource dataSource, AccountEgress egress) {
        this(dataSource, egress, ECHO, EgressRegistry::askEcho, Egress::assigned);
        refresh();          // before any route opens: a provisioned account never starts on the default route
    }

    EgressRegistry(DataSource dataSource, AccountEgress egress, List<URI> echo,
                   BiFunction<InetAddress, URI, String> ask, java.util.function.Predicate<InetAddress> assigned) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.egress = egress;
        this.echo = List.copyOf(echo);
        this.ask = ask;
        this.assigned = assigned;
    }

    /** {@code busy}: LIVE, or holding a position or an order still working (the address must not move under it). */
    record Row(long account, InetAddress container, String publicIp, Instant checkAt, Boolean checkOk,
               String checkIp, Instant registeredAt, boolean busy) {
    }

    List<Row> provisioned() {
        return jdbc.query("select e.broker_account_id, host(e.container_ip) container, host(e.public_ip) public_ip, "
                + "e.last_check_at, e.last_check_ok, host(e.last_check_ip) check_ip, e.registered_at, "
                + "(a.trade_mode = 'LIVE' or a.live_trading_enabled "
                + " or exists (select 1 from exec.position p where p.broker_account_id = a.id and p.status <> 'CLOSED') "
                + " or exists (select 1 from exec.order_execution o where o.broker_account_id = a.id and o.status in "
                + "  ('CREATED', 'SUBMITTED', 'ACKNOWLEDGED', 'OPEN', 'PARTIALLY_FILLED', 'CANCEL_PENDING', 'UNKNOWN', "
                + "   'RECONCILIATION_REQUIRED') and o.created_at > now() - interval '1 day')) busy "
                + "from acct.broker_egress e join acct.broker_account a on a.id = e.broker_account_id "
                + "where e.state = 'PROVISIONED' order by e.broker_account_id", (rs, i) -> new Row(
                rs.getLong("broker_account_id"), Egress.parse(rs.getString("container")), rs.getString("public_ip"),
                instant(rs.getTimestamp("last_check_at")), (Boolean) rs.getObject("last_check_ok"),
                rs.getString("check_ip"), instant(rs.getTimestamp("registered_at")), rs.getBoolean("busy")));
    }

    /** Why the row is not ready for LIVE at {@code now}, or null when it is. */
    static String notReady(Row r, Instant now) {
        if (r.checkAt() == null) {
            return "the check that its calls leave from " + r.publicIp() + " has not passed yet";
        }
        if (!Boolean.TRUE.equals(r.checkOk())) {
            return r.checkIp() == null ? "its last check failed: its calls are not leaving from " + r.publicIp()
                    + " (the reason is on its broker page)"
                    : "its calls were seen from " + r.checkIp() + ", not " + r.publicIp();
        }
        if (r.checkAt().isBefore(now.minus(FRESH))) {
            return "no conclusive check in the last " + FRESH.toHours() + " hours";
        }
        if (r.registeredAt() == null) {
            return "register " + r.publicIp() + " at the broker, then type it on its broker page (I registered it)";
        }
        return null;
    }

    @Scheduled(fixedDelayString = "${autotrade.execution.egress.refresh-ms:10000}", initialDelay = 10000)
    void refresh() {
        try {
            apply(provisioned(), Instant.now());
            logged.removeIf(l -> l.startsWith("refresh failed"));
        } catch (RuntimeException e) {
            once("refresh failed: " + e.getMessage(), false);
        }
    }

    synchronized void apply(List<Row> rows, Instant now) {
        Map<Long, InetAddress> addresses = new LinkedHashMap<>();
        Map<Long, String> pending = new LinkedHashMap<>();
        for (Row r : rows) {
            InetAddress before = applied.get(r.account());
            InetAddress use = r.container();
            if (started && r.busy() && !use.equals(before)) {
                once("account " + r.account() + ": static IP " + r.publicIp() + " (" + use.getHostAddress()
                        + ") waits until the account is not LIVE and holds no position or working order", false);
                use = before;
            }
            if (use == null) {
                continue;
            }
            addresses.put(r.account(), use);
            // held back: the old address keeps working as it did (its own checks and registration were the old row's)
            String why = use.equals(r.container()) ? notReady(r, now) : null;
            if (why != null) {
                pending.put(r.account(), why);
            }
        }
        for (String line : egress.provisioned(addresses, pending)) {
            once(line, true);
        }
        applied.clear();
        applied.putAll(addresses);
        started = true;
    }

    /** Every few minutes, on its own virtual thread (the scheduler's threads run the follower and the sessions). */
    @Scheduled(fixedDelayString = "${autotrade.execution.egress.check-ms:300000}", initialDelay = 20000)
    void scheduledCheck() {
        if (checking.compareAndSet(false, true)) {
            Thread.ofVirtual().name("egress-check").start(() -> {
                try {
                    checkAll();
                } finally {
                    checking.set(false);
                }
            });
        }
    }

    void checkAll() {
        try {
            for (Row r : provisioned()) {
                try {
                    checkOne(r);
                } catch (RuntimeException e) {
                    log.warn("egress: account {} check: {}", r.account(), e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            once("check failed: " + e.getMessage(), false);
        }
        refresh();
    }

    /**
     * One account's check. Returns true (pass), false (conclusive fail) or null (inconclusive: a service did not
     * answer and no other IP was seen; the last conclusive result stands).
     */
    Boolean checkOne(Row r) {
        if (!assigned.test(r.container())) {
            return record(r, false, null, "address " + r.container().getHostAddress()
                    + " is not on this host (Docker network not connected)");
        }
        List<String> seen = new ArrayList<>();
        String unanswered = null;
        for (URI u : echo) {
            try {
                String ip = ask.apply(r.container(), u).trim();
                seen.add(IPV4.matcher(ip).matches() ? ip : "not an IP");
            } catch (RuntimeException e) {
                unanswered = u.getHost() + ": " + e.getMessage();
            }
        }
        String other = seen.stream().filter(ip -> !ip.equals(r.publicIp())).findFirst().orElse(null);
        if (other != null) {
            return record(r, false, other.equals("not an IP") ? null : other, "seen from " + String.join(" / ", seen)
                    + ", reserved " + r.publicIp());
        }
        if (seen.isEmpty() && sharedRouteAnswers()) {
            // E0 18 Nov 2026: a broken account route (no source rule in the container, a NAT gateway gone) looks
            // exactly like an echo outage from the bound address; the shared route answering tells them apart
            return record(r, false, null, "no echo service answered through " + r.container().getHostAddress()
                    + " while the shared route did: its route is broken (" + unanswered + ")");
        }
        if (unanswered != null || seen.isEmpty()) {
            jdbc.update("update acct.broker_egress set last_error = ?, updated_at = now() where broker_account_id = ? "
                    + "and state = 'PROVISIONED'", "inconclusive check: " + unanswered, r.account());
            log.warn("egress: account {} check inconclusive: {}", r.account(), unanswered);
            return null;
        }
        return record(r, true, r.publicIp(), null);
    }

    /** Whether any echo service answers over the default route (no bound address). */
    private boolean sharedRouteAnswers() {
        for (URI u : echo) {
            try {
                if (IPV4.matcher(ask.apply(null, u).trim()).matches()) {
                    return true;
                }
            } catch (RuntimeException e) {
                // next service
            }
        }
        return false;
    }

    private Boolean record(Row r, boolean ok, String seenIp, String error) {
        jdbc.update("update acct.broker_egress set last_check_at = now(), last_check_ok = ?, last_check_ip = ?::inet, "
                + "verified_at = case when ? then coalesce(verified_at, now()) else verified_at end, last_error = ?, "
                + "updated_at = now() where broker_account_id = ? and state = 'PROVISIONED'",
                ok, seenIp, ok, error, r.account());
        if (!ok) {
            log.warn("egress: account {} check failed: {}", r.account(), error);
        }
        return ok;
    }

    private void once(String line, boolean error) {
        if (logged.add(line)) {
            if (error) {
                log.error("egress: {}", line);
            } else {
                log.warn("egress: {}", line);
            }
        }
    }

    private static String askEcho(InetAddress from, URI uri) {
        try (HttpClient client = Egress.client(Duration.ofSeconds(5), from)) {
            HttpResponse<String> res = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + res.statusCode());
            }
            String body = res.body().trim();
            return body.length() > 45 ? body.substring(0, 45) : body;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.getClass().getSimpleName() + " " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
