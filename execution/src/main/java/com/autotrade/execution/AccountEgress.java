package com.autotrade.execution;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.broker.BrokerException;
import com.autotrade.broker.Egress;

/**
 * Each broker account's own source address on the order host, and the static-IP LIVE gate (multi-broker production gaps
 * B1 and B2, 16 Nov 2026). Since 1 Apr 2026 an algo order must come from the IP registered for that client with the
 * broker (Kotak: the session must also be created from it, stCode 1037; Upstox: one IP per user, and changing it logs
 * every session out; Groww: orders only from a registered IP). One static IP per LIVE account (user, 2 Nov 2026, plan 3.9).
 * <ul>
 *   <li><b>Addresses</b> ({@code autotrade.execution.egress.accounts}, e.g. {@code "21=10.20.0.21,15=10.20.0.22"}):
 *       account id = an IPv4 or IPv6 literal (never a host name: no DNS lookup decides where an order leaves from).
 *       Parsed strictly at startup: a malformed entry, a repeated account or a repeated address stops the worker. Every
 *       HTTP client of such an account (reads, orders, instrument downloads, the daily login) binds to its address.</li>
 *   <li><b>Fails closed</b>: at startup ({@link #check}) and whenever a route opens or a call is made
 *       ({@link #address}), the address must be assigned to an interface of this host; if it is not, the account's
 *       route is refused with the reason, and a call is refused as a lost session, never sent from the default
 *       route.</li>
 *   <li><b>LIVE gate</b> ({@code autotrade.execution.egress.required-brokers}, default {@code KOTAK_NEO,UPSTOX,GROWW}):
 *       a LIVE connection of an account of such a broker is refused unless the account has a verified address
 *       ({@link #liveRefusal}). Zerodha is not in the default list: its pilot runs from the shared NAT today.</li>
 *   <li><b>No entry</b>: the account keeps the host's default route, exactly as before (ZERODHA-1 today).</li>
 * </ul>
 */
public final class AccountEgress {

    /** The brokers whose LIVE orders need the account's own static IP unless configured otherwise. */
    public static final Set<BrokerType> DEFAULT_REQUIRED = Collections.unmodifiableSet(
            EnumSet.of(BrokerType.KOTAK_NEO, BrokerType.UPSTOX, BrokerType.GROWW));

    /** No account has an address; the default brokers need one for LIVE (tests, and a worker without the setting). */
    public static final AccountEgress NONE = new AccountEgress(Map.of(), DEFAULT_REQUIRED, Egress::assigned);

    /** The configuration's addresses (fixed at startup). */
    private final Map<Long, InetAddress> configuredAddresses;
    /**
     * The addresses in effect (the configuration's plus the provisioned ones, {@link #provisioned}) and the provisioned
     * accounts not ready for LIVE yet with the reason, swapped together as one snapshot (review 18 Nov 2026: two
     * separate fields let a reader see a new address without its not-ready reason, and open LIVE for an instant).
     */
    private record State(Map<Long, InetAddress> byAccount, Map<Long, String> notReady) {
    }

    private volatile State state;
    private final Set<BrokerType> required;
    private final Predicate<InetAddress> assigned;

    /**
     * @param byAccount the accounts' addresses
     * @param required  the brokers whose LIVE connections need one
     * @param assigned  whether an address is on this host now ({@link Egress#assigned}; a stand-in in tests)
     */
    public AccountEgress(Map<Long, InetAddress> byAccount, Set<BrokerType> required, Predicate<InetAddress> assigned) {
        this.configuredAddresses = Map.copyOf(byAccount);
        this.state = new State(this.configuredAddresses, Map.of());
        this.required = required.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(required));
        this.assigned = assigned;
    }

    /**
     * The settings, parsed strictly (IllegalArgumentException with the bad entry; nothing is logged but the account ids
     * and addresses, which are not secrets).
     *
     * @param accounts        {@code id=address} pairs, comma-separated; blank = none
     * @param requiredBrokers broker names added to {@link #DEFAULT_REQUIRED}, comma-separated (null or blank: the default)
     */
    public static AccountEgress parse(String accounts, String requiredBrokers) {
        return new AccountEgress(parseAccounts(accounts), parseBrokers(requiredBrokers), Egress::assigned);
    }

    static Map<Long, InetAddress> parseAccounts(String accounts) {
        Map<Long, InetAddress> out = new LinkedHashMap<>();
        if (accounts == null || accounts.isBlank()) {
            return out;
        }
        Map<InetAddress, Long> owners = new java.util.HashMap<>();
        for (String raw : accounts.split(",", -1)) {
            String entry = raw.trim();
            int eq = entry.indexOf('=');
            if (entry.isEmpty() || eq <= 0 || eq == entry.length() - 1 || entry.indexOf('=', eq + 1) >= 0) {
                throw new IllegalArgumentException("autotrade.execution.egress.accounts: '" + entry
                        + "' is not account-id=ip-address");
            }
            String id = entry.substring(0, eq).trim();
            if (!id.matches("[1-9][0-9]{0,17}")) {
                throw new IllegalArgumentException("autotrade.execution.egress.accounts: '" + id
                        + "' is not an account id");
            }
            long account = Long.parseLong(id);
            InetAddress address;
            try {
                address = Egress.parse(entry.substring(eq + 1));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("autotrade.execution.egress.accounts: account " + account + ": "
                        + e.getMessage(), e);
            }
            if (out.putIfAbsent(account, address) != null) {
                throw new IllegalArgumentException("autotrade.execution.egress.accounts: account " + account
                        + " is listed twice");
            }
            Long other = owners.putIfAbsent(address, account);
            if (other != null) {
                // one static IP per account (plan 3.9): two accounts on one IP would share the broker's per-IP limits
                // and, at Upstox, one user's IP change would log the other out
                throw new IllegalArgumentException("autotrade.execution.egress.accounts: " + address.getHostAddress()
                        + " is given to accounts " + other + " and " + account);
            }
        }
        return out;
    }

    static Set<BrokerType> parseBrokers(String brokers) {
        if (brokers == null) {
            return DEFAULT_REQUIRED;
        }
        Set<BrokerType> out = EnumSet.noneOf(BrokerType.class);
        for (String raw : brokers.split(",")) {
            String name = raw.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            BrokerType b;
            try {
                b = BrokerType.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("autotrade.execution.egress.required-brokers: no broker " + name, e);
            }
            if (b == BrokerType.PAPER) {
                throw new IllegalArgumentException("autotrade.execution.egress.required-brokers: PAPER has no broker");
            }
            out.add(b);
        }
        // review 16 Nov 2026: the setting adds brokers (ZERODHA, once its pilot leaves the shared NAT), never removes the
        // ones that take orders only from a registered IP: a blank or shortened value no longer switches the gate off
        out.addAll(DEFAULT_REQUIRED);
        return out;
    }

    /**
     * The provisioned accounts from acct.broker_egress (18 Nov 2026, automatic static IPs), merged over the
     * configuration's: an account the configuration names keeps that address, and an address already used by another
     * account is skipped (one static IP per account). Returns one line per skipped entry, for the log.
     *
     * @param addresses the provisioned accounts' container addresses
     * @param pending   provisioned accounts not ready for LIVE yet, with the reason
     */
    public synchronized List<String> provisioned(Map<Long, InetAddress> addresses, Map<Long, String> pending) {
        Map<Long, InetAddress> merged = new LinkedHashMap<>(configuredAddresses);
        Map<InetAddress, Long> owners = new java.util.HashMap<>();
        configuredAddresses.forEach((id, a) -> owners.put(a, id));
        List<String> skipped = new ArrayList<>();
        addresses.forEach((id, a) -> {
            if (configuredAddresses.containsKey(id)) {
                skipped.add("account " + id + ": provisioned " + a.getHostAddress() + " ignored, the configuration gives "
                        + configuredAddresses.get(id).getHostAddress());
                return;
            }
            Long other = owners.putIfAbsent(a, id);
            if (other != null) {
                skipped.add("account " + id + ": provisioned " + a.getHostAddress() + " ignored, it is account " + other
                        + "'s");
                return;
            }
            merged.put(id, a);
        });
        Map<Long, String> waiting = new LinkedHashMap<>();
        pending.forEach((id, why) -> {
            if (merged.containsKey(id) && !configuredAddresses.containsKey(id)) {
                waiting.put(id, why);
            }
        });
        this.state = new State(Map.copyOf(merged), Map.copyOf(waiting));
        return skipped;
    }

    /** The account's configured address, assigned or not. */
    public Optional<InetAddress> configured(long accountId) {
        return Optional.ofNullable(state.byAccount().get(accountId));
    }

    /** Whether a LIVE connection of this broker needs the account's own verified address. */
    public boolean required(BrokerType broker) {
        return required.contains(broker);
    }

    public Set<BrokerType> requiredBrokers() {
        return required;
    }

    /**
     * Why the account's routes must not open (its address is configured but not on this host), or null when they may (no
     * address: the default route, as before; or an address this host has).
     */
    public String refusal(BrokerAccount account) {
        return refusal(state, account);
    }

    private String refusal(State st, BrokerAccount account) {
        InetAddress a = st.byAccount().get(account.id());
        if (a == null || assigned.test(a)) {
            return null;
        }
        return "egress address " + a.getHostAddress() + " of " + account.displayName() + " is not on this host: its "
                + "broker calls are refused, never sent from the default route";
    }

    /**
     * Why a LIVE connection of the account is refused by the static-IP gate, or null: its address is configured but not
     * on this host, or its broker needs one ({@link #required}) and it has none.
     */
    public String liveRefusal(BrokerAccount account) {
        State st = state;                                       // one snapshot for every check below
        String unassigned = refusal(st, account);
        if (unassigned != null) {
            return unassigned;
        }
        String waiting = st.notReady().get(account.id());
        if (waiting != null) {
            return account.displayName() + "'s static IP is not ready for LIVE: " + waiting;
        }
        if (required(account.brokerType()) && !st.byAccount().containsKey(account.id())) {
            return account.brokerType() + " takes orders only from the client's registered static IP, and "
                    + account.displayName() + " has no static IP yet: "
                    + "press Get a static IP on its broker page";
        }
        return null;
    }

    /**
     * The address the account's broker calls leave from now: null for the default route (no address configured), the
     * address when it is on this host. Configured but not on this host: refused as {@link BrokerException.Kind#SESSION}
     * (the account cannot reach its broker as registered; entries are refused, nothing is sent from another address).
     */
    public InetAddress address(BrokerAccount account) {
        State st = state;
        InetAddress a = st.byAccount().get(account.id());
        if (a != null && !assigned.test(a)) {
            throw new BrokerException(BrokerException.Kind.SESSION, refusal(st, account));
        }
        return a;
    }

    /** The startup check: one line per configured account, saying whether its address is on this host. */
    public List<String> check() {
        List<String> lines = new ArrayList<>();
        state.byAccount().forEach((id, a) -> lines.add("account " + id + ": egress " + a.getHostAddress()
                + (assigned.test(a) ? " is on this host" : " is NOT on this host: its routes and logins are refused")));
        return lines;
    }

    /** Whether every configured address is on this host. */
    public boolean allAssigned() {
        return state.byAccount().values().stream().allMatch(assigned);
    }

    @Override
    public String toString() {
        Map<Long, String> shown = new LinkedHashMap<>();
        state.byAccount().forEach((id, a) -> shown.put(id, a.getHostAddress()));
        return "AccountEgress[accounts=" + shown + ", required=" + required + "]";
    }
}
