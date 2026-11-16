package com.autotrade.execution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autotrade.account.BrokerType;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.sim.CostModel;

/**
 * Each broker's trading-day rules (config/brokers/broker-rules.vN.yaml; multi-broker gap A1, 16 Nov 2026): the product
 * our orders carry, the last LIVE entry, our end-of-day square-off, the broker's own auto square-off and the costs file
 * that charges the account's P&L. Until 16 Nov 2026 one global set served every broker (MIS hard-coded in the Kite
 * adapter, the last LIVE entry 15:10, one square-off cron at 15:20, one costs file); Upstox's intraday product is
 * {@code I}, and a broker that squares off at 15:20 would close our positions itself before a 15:20 square-off of ours.
 * <p>
 * Checked at load, so a worker with a wrong file does not start: every broker type has its rules; our square-off lies in
 * {@link #SQUARE_OFF_FROM}..{@link #SQUARE_OFF_TO} (the minute tick that runs it) and at least {@link #MIN_MARGIN}
 * before the broker's auto square-off; the last LIVE entry is before our square-off; each costs file loads.
 * PAPER (our own simulated broker) has only a square-off and costs.
 */
public record BrokerRules(String version, String contentHash, Map<BrokerType, Rule> rules) {

    /** Our square-off is at least this long before the broker's own (the plan's S7: room for a chase and a retry). */
    public static final Duration MIN_MARGIN = Duration.ofMinutes(5);
    /** The window of the square-off tick (ExecutionService: every minute, 15:00 to 15:30 IST, weekdays). */
    public static final LocalTime SQUARE_OFF_FROM = LocalTime.of(15, 0);
    public static final LocalTime SQUARE_OFF_TO = LocalTime.of(15, 30);

    /**
     * One broker's rules.
     *
     * @param product             the product code of our orders (null for PAPER)
     * @param lastLiveEntry       no LIVE entry from this time, IST (null for PAPER: never LIVE)
     * @param squareOff           our end-of-day square-off of the broker's accounts, IST
     * @param brokerAutoSquareOff the broker's own intraday auto square-off, IST (null for PAPER)
     * @param brokerTimeConfirmed whether that time was read on the broker's own site (false: UNCONFIRMED)
     * @param costsFile           the costs file as the rules name it; {@code costs} is it loaded
     */
    public record Rule(BrokerType broker, String product, LocalTime lastLiveEntry, LocalTime squareOff,
                       LocalTime brokerAutoSquareOff, boolean brokerTimeConfirmed, String costsFile, CostModel costs) {
    }

    public BrokerRules {
        rules = Map.copyOf(rules);
        Set<BrokerType> missing = EnumSet.allOf(BrokerType.class);
        missing.removeAll(rules.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(version + ": no rules for " + missing);
        }
        for (Rule r : rules.values()) {
            check(version, r);
        }
    }

    /** Each broker's intraday product code: our orders never carry another (a carry product has no auto square-off). */
    static final Map<BrokerType, String> INTRADAY = Map.of(BrokerType.ZERODHA, "MIS", BrokerType.UPSTOX, "I",
            BrokerType.KOTAK_NEO, "MIS", BrokerType.GROWW, "MIS");

    private static void check(String version, Rule r) {
        String at = version + " " + r.broker() + ": ";
        if (r.squareOff().isBefore(SQUARE_OFF_FROM) || r.squareOff().isAfter(SQUARE_OFF_TO)) {
            throw new IllegalArgumentException(at + "square_off " + r.squareOff() + " is outside " + SQUARE_OFF_FROM + "-"
                    + SQUARE_OFF_TO + " (the square-off tick runs only then)");
        }
        if (r.costs() == null) {
            throw new IllegalArgumentException(at + "no costs");
        }
        if (r.broker() == BrokerType.PAPER) {
            return;
        }
        if (r.product() == null || r.product().isBlank()) {
            throw new IllegalArgumentException(at + "no product");
        }
        String intraday = INTRADAY.get(r.broker());
        if (intraday != null && !intraday.equals(r.product())) {
            // review 16 Nov 2026: the Kite adapter takes its product from these rules, so requireProducts compared the
            // value with itself; an NRML here would send every LIVE order NRML (no broker auto square-off backstop)
            throw new IllegalArgumentException(at + "product " + r.product() + " is not " + r.broker() + "'s intraday "
                    + "product " + intraday);
        }
        if (r.lastLiveEntry() == null || !r.lastLiveEntry().isBefore(r.squareOff())) {
            throw new IllegalArgumentException(at + "last_live_entry " + r.lastLiveEntry() + " must be before square_off "
                    + r.squareOff());
        }
        if (r.brokerAutoSquareOff() == null) {
            throw new IllegalArgumentException(at + "no broker_auto_square_off");
        }
        if (r.squareOff().plus(MIN_MARGIN).isAfter(r.brokerAutoSquareOff())) {
            throw new IllegalArgumentException(at + "square_off " + r.squareOff() + " must be at least "
                    + MIN_MARGIN.toMinutes() + " minutes before the broker's auto square-off " + r.brokerAutoSquareOff());
        }
    }

    /**
     * Loads a rules file. A {@code costs_file} is read relative to the directory that holds the rules file's
     * {@code config} directory (the repository, or /app in the containers), so the same file loads from a module's tests
     * ({@code ../config/brokers/...}) and from the worker ({@code config/brokers/...}).
     */
    public static BrokerRules load(Path file) {
        ThresholdConfig c = ThresholdConfig.load(file);
        Path root = configRoot(file);
        Map<String, CostModel> costFiles = new java.util.HashMap<>();
        Map<BrokerType, Rule> rules = new EnumMap<>(BrokerType.class);
        for (String name : c.getMap("brokers").keySet()) {
            BrokerType broker;
            try {
                broker = BrokerType.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(c.version() + ": unknown broker " + name);
            }
            String p = "brokers." + name + ".";
            boolean paper = broker == BrokerType.PAPER;
            String costsFile = c.getString(p + "costs_file");
            CostModel costs = costFiles.computeIfAbsent(costsFile, f -> CostModel.from(ThresholdConfig.load(
                    root.resolve(f))));
            String status = paper ? "CONFIRMED" : c.getString(p + "broker_auto_square_off_status");
            if (!status.equals("CONFIRMED") && !status.equals("UNCONFIRMED")) {
                throw new IllegalArgumentException(c.version() + " " + name
                        + ": broker_auto_square_off_status must be CONFIRMED or UNCONFIRMED, not " + status);
            }
            rules.put(broker, new Rule(broker, paper ? null : c.getString(p + "product"),
                    paper ? null : c.getTime(p + "last_live_entry"), c.getTime(p + "square_off"),
                    paper ? null : c.getTime(p + "broker_auto_square_off"), status.equals("CONFIRMED"), costsFile, costs));
        }
        return new BrokerRules(c.version(), c.contentHash(), rules);
    }

    private static Path configRoot(Path file) {
        for (Path p = file.toAbsolutePath().normalize().getParent(); p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().equals("config") && Files.isDirectory(p)) {
                return p.getParent();
            }
        }
        return Path.of("");
    }

    public Rule rule(BrokerType broker) {
        return rules.get(broker);
    }

    /** The cost model that charges an account of this broker. */
    public CostModel costs(BrokerType broker) {
        return rules.get(broker).costs();
    }

    /** No LIVE entry from this time (IST); null for PAPER (never LIVE). */
    public LocalTime lastLiveEntry(BrokerType broker) {
        return rules.get(broker).lastLiveEntry();
    }

    public LocalTime squareOff(BrokerType broker) {
        return rules.get(broker).squareOff();
    }

    /** The brokers whose square-off time has come at {@code now} (IST). */
    public Set<BrokerType> squareOffDue(LocalTime now) {
        Set<BrokerType> due = EnumSet.noneOf(BrokerType.class);
        rules.values().stream().filter(r -> !now.isBefore(r.squareOff())).forEach(r -> due.add(r.broker()));
        return due;
    }

    /**
     * Fails unless every adapter's product is the one its broker's rules name (the worker's start): the rules are what
     * the square-off times and the reconciliation's product filter were chosen for.
     */
    public void requireProducts(Collection<? extends BrokerAdapter> adapters) {
        List<String> wrong = adapters.stream().filter(a -> !rule(a.broker()).product().equals(a.product()))
                .map(a -> a.broker() + " sends " + a.product() + ", " + version + " says " + rule(a.broker()).product())
                .toList();
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("broker products differ from the rules: " + wrong);
        }
    }

    /** One line per broker for the worker's start log. */
    public List<String> describe() {
        return rules.values().stream().sorted(java.util.Comparator.comparing(Rule::broker)).map(r -> r.broker()
                + (r.product() == null ? "" : " " + r.product())
                + (r.lastLiveEntry() == null ? "" : ", last LIVE entry " + r.lastLiveEntry())
                + ", square-off " + r.squareOff()
                + (r.brokerAutoSquareOff() == null ? "" : " (broker's " + r.brokerAutoSquareOff()
                        + (r.brokerTimeConfirmed() ? "" : " UNCONFIRMED") + ")")
                + ", costs " + r.costsFile()).toList();
    }
}
