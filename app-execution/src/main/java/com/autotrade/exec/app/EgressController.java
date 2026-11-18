package com.autotrade.exec.app;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.execution.KillSwitches;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * An account's static IP (18 Nov 2026, automatic provisioning):
 * <ul>
 *   <li>{@code GET /api/execution/accounts/{id}/egress}: its state, the IP to register at the broker, the last check;</li>
 *   <li>{@code POST …/egress}: an OWNER or ADMIN asks for one (a REQUESTED row; the host provisioner picks it up within a
 *       minute). Again after FAILED or RETIRED: asks once more;</li>
 *   <li>{@code POST …/egress/registered}: the owner's word that the IP is registered at the broker (they type the IP),
 *       or that it no longer is. Audited.</li>
 * </ul>
 * Nothing here changes the network: the provisioner does, with its own GCP account.
 */
@RestController
class EgressController {

    record Registered(boolean registered, String confirm) {
    }

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final KillSwitches audit;

    EgressController(Access access, DataSource dataSource) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.audit = new KillSwitches(dataSource);
    }

    private BrokerAccount account(Access.User user, long id) {
        return accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
    }

    @GetMapping("/api/execution/accounts/{id}/egress")
    Map<String, Object> get(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id) {
        BrokerAccount a = account(access.user(subject), id);
        return view(a);
    }

    @PostMapping("/api/execution/accounts/{id}/egress")
    Map<String, Object> request(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                @PathVariable long id) {
        Access.User user = access.user(subject);
        BrokerAccount a = account(user, id);
        Access.require(user.manages(a.tenantId()), "only an OWNER or ADMIN may ask for a static IP");
        require(a.brokerType() != BrokerType.PAPER, a.displayName() + " is a paper account: it needs no static IP");
        // review 18 Nov 2026: a new address moves every broker call of the account, and its broker has the old IP
        // registered (Kite: ZERODHA-1 runs from the shared NAT): never under a LIVE account or open business
        require(a.tradeMode() != com.autotrade.account.TradeMode.LIVE && !a.liveTradingEnabled(), a.displayName()
                + " is LIVE: its orders leave from the IP registered now; switch it off LIVE first");
        Integer open = jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ? and status "
                + "<> 'CLOSED'", Integer.class, id);
        require(open == null || open == 0, a.displayName() + " holds " + open + " position(s): ask when it is flat");
        int changed = jdbc.update("insert into acct.broker_egress (broker_account_id, requested_by) values (?, ?) "
                + "on conflict (broker_account_id) do update set state = 'REQUESTED', attempts = 0, last_error = null, "
                + "requested_by = excluded.requested_by, requested_at = now(), updated_at = now() "
                + "where acct.broker_egress.state in ('FAILED', 'RETIRED')", id, user.subject());
        require(changed == 1, a.displayName() + " already has a static IP or one is on its way");
        audit.audit(a, user.subject(), "EGRESS_REQUESTED", Map.of());
        return view(a);
    }

    @PostMapping("/api/execution/accounts/{id}/egress/registered")
    Map<String, Object> registered(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                   @PathVariable long id, @RequestBody Registered body) {
        Access.User user = access.user(subject);
        BrokerAccount a = account(user, id);
        Access.require(user.manages(a.tenantId()), "only an OWNER or ADMIN may mark the static IP registered");
        List<String> ip = jdbc.queryForList("select host(public_ip) from acct.broker_egress where broker_account_id = ? "
                + "and state = 'PROVISIONED'", String.class, id);
        require(!ip.isEmpty() && ip.get(0) != null, a.displayName() + " has no provisioned static IP yet");
        if (body.registered()) {
            require(ip.get(0).equals(body.confirm() == null ? null : body.confirm().trim()),
                    "type the IP you registered at the broker (" + ip.get(0) + ") to confirm");
            jdbc.update("update acct.broker_egress set registered_at = now(), registered_by = ?, updated_at = now() "
                    + "where broker_account_id = ? and state = 'PROVISIONED'", user.subject(), id);
        } else {
            jdbc.update("update acct.broker_egress set registered_at = null, registered_by = null, updated_at = now() "
                    + "where broker_account_id = ?", id);
        }
        audit.audit(a, user.subject(), body.registered() ? "EGRESS_REGISTERED" : "EGRESS_UNREGISTERED",
                Map.of("ip", ip.get(0)));
        return view(a);
    }

    Map<String, Object> view(BrokerAccount a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", a.id());
        out.put("account", a.displayName());
        out.put("broker", a.brokerType().name());
        List<Map<String, Object>> rows = jdbc.queryForList("select state, host(public_ip) public_ip, attempts, last_error, "
                + "requested_at, provisioned_at, last_check_at, last_check_ok, host(last_check_ip) last_check_ip, "
                + "verified_at, registered_at from acct.broker_egress where broker_account_id = ?", a.id());
        if (rows.isEmpty()) {
            out.put("state", "NONE");
            return out;
        }
        Map<String, Object> r = rows.get(0);
        r.replaceAll((k, v) -> v instanceof Timestamp t ? t.toInstant().toString() : v);
        out.put("state", r.get("state"));
        out.put("publicIp", r.get("public_ip"));
        out.put("attempts", r.get("attempts"));
        out.put("error", r.get("last_error"));
        out.put("requestedAt", r.get("requested_at"));
        out.put("provisionedAt", r.get("provisioned_at"));
        out.put("checkAt", r.get("last_check_at"));
        out.put("checkOk", r.get("last_check_ok"));
        out.put("checkIp", r.get("last_check_ip"));
        out.put("verifiedAt", r.get("verified_at"));
        out.put("registeredAt", r.get("registered_at"));
        out.put("registerAt", registerHelp(a.brokerType()));
        return out;
    }

    /** Where the owner registers the IP, in the broker's words. */
    static String registerHelp(BrokerType broker) {
        return switch (broker) {
            case KOTAK_NEO -> "Kotak Neo: the Trade API section of your Kotak Neo account (static IP, primary)";
            case ZERODHA -> "Zerodha: your app on the Kite Connect developer console (IP whitelist)";
            case UPSTOX -> "Upstox: your app on the Upstox developer console (static IP; changing it logs every session "
                    + "out)";
            case GROWW -> "Groww: the Trade API keys page (whitelisted IPs)";
            default -> "";
        };
    }

    private static void require(boolean ok, String why) {
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, why);
        }
    }
}
