package com.autotrade.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerException;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderStatus;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.Quote;

/**
 * The morning canary (multi-broker plan 16 Nov 2026, D1; live adapters plan 8 Nov 2026 section 3 step E), broker-neutral:
 * on one account's LIVE connection (already behind its {@link LiveGuard}) it proves the order path end to end with an
 * order that can never trade, and records every step:
 * <ol>
 *   <li>FUNDS: the funds read;</li>
 *   <li>BOOK_CHECK: the broker holds none of the contract, and the account has no working order of ours and holds nothing
 *       bought by our orders in ANY contract (else REFUSED; review 16 Nov 2026: the trading LIVE guard counts the
 *       canary's working buy as a contract held, so a canary beside a real position could refuse a real entry at
 *       {@code maxOpenPositions});</li>
 *   <li>QUOTE: a two-sided quote, fresh; the buy price is {@code max(tick, bid x 0.5)} rounded down to the tick and the
 *       modify price one step lower (or one tick higher at the floor); both must stay below the ask and below the bid
 *       (review 16 Nov 2026: at the bid it joins the best bid and can fill), else REFUSED (never marketable);</li>
 *   <li>PLACE: one lot, LIMIT BUY, tagged {@code F0C<yyMMddHHmmssSSS>} (18 characters, its sell-back 19; our OMS tag scheme {@code F\d+[A-Z].*}: intent 0,
 *       role C, so the reconciler and every gateway read it back as ours, and no real intent has id 0);</li>
 *   <li>READ_BY_TAG: found in the order book by its tag (the tag round-trip), one lot, at our price;</li>
 *   <li>MODIFY, then CONFIRM_MODIFY: the book shows the new price;</li>
 *   <li>CANCEL, then CONFIRM_CANCELLED: the book shows it cancelled;</li>
 *   <li>NO_FILL: nothing filled, no trade of the order, the contract's position unchanged.</li>
 * </ol>
 * Every step's timing, the broker's raw status word and what was seen go to the {@link Journal}. Any failure after the
 * place leaves nothing behind if it can: the order is looked up by tag, cancelled when working, and a fill (it should
 * never happen) is sold back at once through the same connection with a marketable limit, then the result is FAIL with
 * the fill in its message. A lost session that keeps the order from being cancelled is reported as left working.
 * Anything left behind (a fill not sold back, an order possibly still working or of unknown outcome:
 * {@link Report#leftover()}) goes to {@link Journal#leftover} before the result (the account's kill switch and an alert).
 * <p>
 * It is never run by a scheduler (16 Nov 2026): only by the user's explicit request (the admin endpoint with its typed
 * confirmation), and {@link #run()} refuses on Spring's scheduler threads as a last stop.
 */
public final class Canary {

    private static final Logger log = LoggerFactory.getLogger(Canary.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TAG_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmssSSS");
    /** Our OMS tags (BrokerReconciler.OUR_TAG): F, the intent, the role letter. */
    static final Pattern OUR_TAG = Pattern.compile("F\\d+[A-Z].*");
    /** Spring's scheduler threads ({@code scheduling-1}): a canary is never run from one. */
    static final String SCHEDULER_THREAD = "scheduling-";

    public enum Result { PASS, FAIL, REFUSED }

    /** The contract the canary buys: our key ({@code NSE_FO|47168}), its symbol, its lot and its tick. */
    public record Contract(String instrumentKey, String symbol, int lotSize, double tickSize) {

        public Contract {
            if (instrumentKey == null || !(instrumentKey.startsWith("NSE_FO|") || instrumentKey.startsWith("BSE_FO|"))) {
                throw new IllegalArgumentException("a canary needs an option contract (NSE_FO|... or BSE_FO|...), not "
                        + instrumentKey);
            }
            if (lotSize <= 0 || !(tickSize > 0)) {
                throw new IllegalArgumentException("lot size and tick size must be positive");
            }
        }

        String exchange() {
            return instrumentKey.substring(0, instrumentKey.indexOf('|'));
        }
    }

    /**
     * How the book is read back: every {@code readEvery}, at most {@code reads} times per check; a quote older than
     * {@code maxQuoteAge} is refused.
     */
    public record Settings(Duration readEvery, int reads, Duration maxQuoteAge) {

        public static final Settings DEFAULT = new Settings(Duration.ofMillis(400), 25, Duration.ofMinutes(2));
    }

    /**
     * One step: its number, name, when it started and how long it took, whether it passed, our status and the broker's
     * raw status word for the order (null when the step read no order), and what was seen.
     */
    public record Step(int n, String name, Instant at, long millis, boolean ok, String status, String rawStatus,
                       String detail) {
    }

    /**
     * The canary's outcome. {@code filledQuantity} > 0 means a fill happened (FAIL); {@code soldBack} whether all of it
     * was sold again; {@code leftWorking} whether the order may still be working at the broker (the user must cancel it).
     */
    public record Report(Result result, String broker, String instrumentKey, String symbol, int lotSize, String tag,
                         double bid, double ask, double buyPrice, double modifiedPrice, double funds,
                         long filledQuantity, boolean soldBack, boolean leftWorking, String message, Instant startedAt,
                         Instant finishedAt, List<Step> steps) {

        /**
         * Whether the canary left something at the broker that a person must deal with: a fill not (all) sold back, or
         * the order possibly still working (a cancel that could not be confirmed, a place of unknown outcome).
         */
        public boolean leftover() {
            return leftWorking || (filledQuantity > 0 && !soldBack);
        }
    }

    /** Where steps and the result are recorded (the audit log and a report file; a test's list). */
    public interface Journal {

        void step(Step step);

        void result(Report report);

        /**
         * Something was left at the broker ({@link Report#leftover()}): called before {@link #result}, so the account is
         * stopped (its kill switch) and someone told before anything else is recorded.
         */
        default void leftover(Report report) {
        }

        Journal NONE = new Journal() {
            @Override
            public void step(Step step) {
            }

            @Override
            public void result(Report report) {
            }
        };
    }

    /** Ends the run with this result (thrown from a step, handled in {@link #run()}). */
    private static final class Stop extends RuntimeException {
        final Result result;

        Stop(Result result, String message) {
            super(message, null, false, false);
            this.result = result;
        }
    }

    private final BrokerConnection broker;
    private final Contract contract;
    private final Settings settings;
    private final Journal journal;
    private final Clock clock;
    private final List<Step> steps = new ArrayList<>();
    private String tag;
    private boolean placeSent;
    private double bid = Double.NaN, ask = Double.NaN, buyPrice = Double.NaN, modifiedPrice = Double.NaN;
    private double funds = Double.NaN;
    private long netBefore;
    private long filled;
    private boolean soldBack;
    private boolean leftWorking;
    /** Cleaning up after a failure: a fill seen then is handled (sold back), not another failure. */
    private boolean securing;
    /** The place's outcome is unknown (a timeout the gateway could not resolve by tag). */
    private boolean placeUnknown;
    /** The broker took the place (it answered with an order id the gateway keeps by tag). */
    private boolean placeReturned;
    private final List<String> notes = new ArrayList<>();

    /**
     * @param broker the account's LIVE connection, behind its {@link LiveGuard} (the caller opened it and closes it)
     */
    public Canary(BrokerConnection broker, Contract contract, Settings settings, Journal journal, Clock clock) {
        this.broker = broker;
        this.contract = contract;
        this.settings = settings == null ? Settings.DEFAULT : settings;
        this.journal = journal == null ? Journal.NONE : journal;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Whether this thread is one of Spring's scheduler threads (a canary is never run from one). */
    public static boolean onSchedulerThread() {
        return Thread.currentThread().getName().startsWith(SCHEDULER_THREAD);
    }

    /** Runs the canary once (a Canary is used once). */
    public synchronized Report run() {
        if (!steps.isEmpty()) {
            throw new IllegalStateException("a canary runs once");
        }
        Instant started = clock.instant();
        Result result;
        String message;
        try {
            if (onSchedulerThread()) {
                throw new Stop(Result.REFUSED, "a canary is never run by a scheduler: only by the user's own request");
            }
            sequence();
            result = Result.PASS;
            message = "PASS: placed " + tag + " at " + price(buyPrice) + ", read back by its tag, modified to "
                    + price(modifiedPrice) + ", cancelled; nothing filled";
        } catch (Stop s) {
            result = s.result;
            message = s.getMessage();
        } catch (BrokerException e) {
            result = Result.FAIL;
            message = "FAIL at " + lastStep() + ": " + e.kind() + " " + e.getMessage();
            placeUnknown = e.kind() == BrokerException.Kind.UNKNOWN && "PLACE".equals(lastStep());
        } catch (RuntimeException e) {
            result = Result.FAIL;
            message = "FAIL at " + lastStep() + ": " + e;
        }
        if (result != Result.PASS && placeSent) {
            secure();
            if (filled > 0) {
                result = Result.FAIL;
                message = "FILLED " + filled + " of " + tag + " (a canary must never fill): " + (soldBack
                        ? "sold back through the same connection" : "NOT SOLD BACK: sell " + filled + " of "
                        + contract.symbol() + " by hand now") + "; " + message;
            }
            if (leftWorking) {
                message = message + "; the canary order " + tag + " may still be working at the broker: cancel it by "
                        + "hand";
            }
        }
        if (!notes.isEmpty()) {
            message = message + " (" + String.join("; ", notes) + ")";
        }
        Report report = new Report(result, broker.name(), contract.instrumentKey(), contract.symbol(), contract.lotSize(),
                tag, bid, ask, buyPrice, modifiedPrice, funds, filled, soldBack, leftWorking, message, started,
                clock.instant(), List.copyOf(steps));
        if (report.leftover()) {
            try {
                journal.leftover(report);
            } catch (RuntimeException e) {
                log.error("canary {} on {}: LEFT AT THE BROKER and the leftover was not recorded: {}", tag, broker.name(),
                        e.toString());
            }
        }
        if (result == Result.PASS) {
            log.info("canary {} on {}: {}", tag, broker.name(), message);
        } else {
            log.error("canary {} on {}: {}", tag, broker.name(), message);
        }
        try {
            journal.result(report);
        } catch (RuntimeException e) {
            log.error("canary {}: the result was not recorded: {}", tag, e.toString());
        }
        return report;
    }

    // ------------------------------------------------------------------------------------------- the sequence

    private void sequence() {
        funds = step("FUNDS", () -> broker.availableFunds(), f -> new Seen(true, null, null, "available " + f));
        step("BOOK_CHECK", () -> {
            List<BrokerPosition> positions = broker.positions();
            List<BrokerOrder> orders = broker.orders();
            netBefore = net(positions);
            if (netBefore != 0) {
                throw new Stop(Result.REFUSED, "REFUSED: the broker holds " + netBefore + " of " + contract.symbol()
                        + ": the canary runs only on a contract with no position");
            }
            // any contract (review 16 Nov 2026): a working order of ours, or a contract our orders bought and hold
            List<String> working = orders.stream().filter(o -> ours(o) && !o.status().isTerminal())
                    .map(o -> o.clientTag() + " " + o.side() + " " + o.symbol() + " " + o.status()).toList();
            if (!working.isEmpty()) {
                throw new Stop(Result.REFUSED, "REFUSED: orders of ours still working (the canary runs only when none "
                        + "is, in any contract): " + working);
            }
            // what is held of every contract our orders traded today, from the whole book (a hand exit counts), as the
            // LIVE guard reads it
            java.util.Set<String> traded = new java.util.HashSet<>();
            orders.stream().filter(o -> ours(o) && o.instrumentKey() != null).forEach(o -> traded.add(o.instrumentKey()));
            java.util.Map<String, Long> held = new java.util.TreeMap<>();
            for (BrokerOrder o : orders) {
                if (o.instrumentKey() != null && traded.contains(o.instrumentKey()) && o.filledQuantity() > 0) {
                    held.merge(o.instrumentKey(), o.side() == OrderSide.SELL ? -o.filledQuantity() : o.filledQuantity(),
                            Long::sum);
                }
            }
            held.values().removeIf(q -> q == 0);
            if (!held.isEmpty()) {
                throw new Stop(Result.REFUSED, "REFUSED: the account holds contracts our orders bought " + held
                        + ": the canary runs only when we hold nothing");
            }
            return orders.size();
        }, n -> new Seen(true, null, null, "no position in " + contract.symbol() + ", no working order of ours and "
                + "nothing held by our orders in any contract (" + n + " orders in today's book)"));
        Quote q = step("QUOTE", () -> broker.quote(contract.instrumentKey()), x -> new Seen(true, null, null,
                x == null ? "no quote" : "bid " + x.bid() + " x " + x.bidQuantity() + ", ask " + x.ask() + " x "
                        + x.askQuantity() + ", last " + x.last() + ", at " + x.time()));
        priceFrom(q);
        if (!Double.isNaN(funds) && funds < buyPrice * contract.lotSize()) {
            throw new Stop(Result.REFUSED, "REFUSED: funds " + funds + " below one lot at " + price(buyPrice));
        }
        tag = "F0C" + TAG_TIME.format(clock.instant().atZone(IST));
        OrderRequest buy = new OrderRequest(tag, InstrumentKeys.token(contract.instrumentKey()), contract.instrumentKey(),
                contract.symbol(), contract.exchange(), OrderSide.BUY, OrderType.LIMIT, contract.lotSize(), buyPrice, 0,
                tag);
        step("PLACE", () -> {
            placeSent = true;
            broker.place(buy);
            placeReturned = true;
            return tag;
        }, t -> new Seen(true, null, null, "LIMIT BUY " + contract.lotSize() + " " + contract.symbol() + " at "
                + price(buyPrice) + " (bid " + bid + ", ask " + ask + ")"));
        BrokerOrder placed = await("READ_BY_TAG", o -> o != null, "the order was not found by its tag " + tag
                + ": the tag did not round-trip");
        if (placed.status() == OrderStatus.REJECTED) {
            throw new Stop(Result.FAIL, "FAIL: the broker rejected the canary buy: " + placed.rawStatus() + " "
                    + placed.message());
        }
        if (placed.side() != OrderSide.BUY || placed.quantity() != contract.lotSize()
                || !samePrice(placed.limitPrice(), buyPrice)) {
            throw new Stop(Result.FAIL, "FAIL: the book shows " + tag + " as " + placed.side() + " " + placed.quantity()
                    + " at " + placed.limitPrice() + ", not BUY " + contract.lotSize() + " at " + price(buyPrice));
        }
        if (placed.status().isTerminal()) {
            throw new Stop(Result.FAIL, "FAIL: " + tag + " is " + placed.status() + " (" + placed.rawStatus()
                    + ") before the modify");
        }
        step("MODIFY", () -> {
            broker.modify(tag, contract.lotSize(), modifiedPrice, 0);
            return tag;
        }, t -> new Seen(true, null, null, "price " + price(buyPrice) + " -> " + price(modifiedPrice)));
        BrokerOrder modified = await("CONFIRM_MODIFY", o -> o != null && (samePrice(o.limitPrice(), modifiedPrice)
                || o.status().isTerminal()), "the book never showed the new price " + price(modifiedPrice));
        if (!samePrice(modified.limitPrice(), modifiedPrice)) {
            throw new Stop(Result.FAIL, "FAIL: " + tag + " is " + modified.status() + " (" + modified.rawStatus()
                    + ") at " + modified.limitPrice() + ", the modify was not applied");
        }
        step("CANCEL", () -> {
            broker.cancel(tag);
            return tag;
        }, t -> new Seen(true, null, null, "cancel sent"));
        BrokerOrder cancelled = await("CONFIRM_CANCELLED", o -> o != null && o.status().isTerminal(),
                "the book never showed " + tag + " cancelled");
        if (cancelled.status() != OrderStatus.CANCELLED) {
            throw new Stop(Result.FAIL, "FAIL: " + tag + " ended " + cancelled.status() + " (" + cancelled.rawStatus()
                    + "), not CANCELLED");
        }
        step("NO_FILL", () -> {
            BrokerOrder o = broker.orderByTag(tag).orElse(cancelled);
            List<BrokerTrade> trades = broker.trades().stream().filter(t -> o.brokerOrderId() != null
                    && o.brokerOrderId().equals(t.brokerOrderId())).toList();
            long net = net(broker.positions());
            long traded = trades.stream().mapToLong(BrokerTrade::quantity).sum();
            if (o.filledQuantity() > 0 || traded > 0 || net != netBefore) {
                filled = Math.max(o.filledQuantity(), Math.max(traded, net - netBefore));
                throw new Stop(Result.FAIL, "FAIL: filled " + o.filledQuantity() + ", " + trades.size()
                        + " trade(s), position " + netBefore + " -> " + net);
            }
            return o;
        }, o -> new Seen(true, o.status().name(), o.rawStatus(), "filled 0, no trade of " + o.brokerOrderId()
                + ", position unchanged at " + netBefore));
    }

    /** The buy and modify prices from the quote, or REFUSED when they could cross the ask. */
    private void priceFrom(Quote q) {
        if (q == null || !q.hasBid() || !q.hasAsk()) {
            throw new Stop(Result.REFUSED, "REFUSED: no two-sided quote for " + contract.symbol() + " (bid "
                    + (q == null ? "-" : q.bid()) + ", ask " + (q == null ? "-" : q.ask()) + ")");
        }
        bid = q.bid();
        ask = q.ask();
        if (q.time() != null && q.time().isBefore(clock.instant().minus(settings.maxQuoteAge()))) {
            throw new Stop(Result.REFUSED, "REFUSED: the quote is from " + q.time() + ", older than "
                    + settings.maxQuoteAge());
        }
        if (!(bid < ask)) {
            throw new Stop(Result.REFUSED, "REFUSED: the book is locked or crossed (bid " + bid + ", ask " + ask + ")");
        }
        double tick = contract.tickSize();
        buyPrice = floorToTick(Math.max(tick, bid * 0.5));
        double lower = floorToTick(Math.max(tick, buyPrice * 0.9));
        modifiedPrice = lower < buyPrice ? lower : buyPrice - tick >= tick ? floorToTick(buyPrice - tick)
                : floorToTick(buyPrice + tick);
        for (double p : new double[] {buyPrice, modifiedPrice}) {
            if (!(p < ask) || !(p < bid)) {
                throw new Stop(Result.REFUSED, "REFUSED: the canary's prices " + price(buyPrice) + " / "
                        + price(modifiedPrice) + " would not stay below the bid " + bid + " and the ask " + ask
                        + " (choose a contract with a wider price)");
            }
        }
    }

    // --------------------------------------------------------------------------- after a failure: leave nothing

    /**
     * After a failure once the buy was sent: the order by its tag, cancelled when working (and confirmed), and any fill
     * sold back. A read or cancel that fails leaves the order marked as possibly working.
     */
    private void secure() {
        securing = true;
        Optional<BrokerOrder> order;
        try {
            // at most a few reads: a refused place left nothing, and a place of unknown outcome was already looked up by
            // the gateway itself
            order = Optional.ofNullable(awaitTag("SECURE_LOOKUP", tag, o -> o != null, null, Math.min(5,
                    settings.reads())));
        } catch (RuntimeException e) {
            leftWorking = true;
            return;
        }
        if (order.isEmpty()) {
            // the place was refused or never reached the broker (the book, read a few times, does not show it); a place of
            // unknown outcome may still show up late, so it is reported as possibly working
            notes.add("no order with tag " + tag + " in the book");
            if (placeReturned) {
                // review 16 Nov 2026: the broker took the order but its book does not show our tag (the very defect a
                // canary looks for): it was left working with nothing reported. Cancelled by the id the place answered
                // with; never confirmable by tag, so always reported as possibly working (kill switch, alert)
                try {
                    step("SECURE_CANCEL", () -> {
                        broker.cancel(tag);
                        return tag;
                    }, t -> new Seen(true, null, null, "cancel sent by the place's order id (the book does not show "
                            + "the tag)"));
                } catch (RuntimeException e) {
                    notes.add("the cancel failed: " + e.getMessage());
                }
                leftWorking = true;
            } else {
                leftWorking = placeUnknown;
            }
            return;
        }
        BrokerOrder o = order.get();
        if (!o.status().isTerminal()) {
            try {
                step("SECURE_CANCEL", () -> {
                    broker.cancel(tag);
                    return tag;
                }, t -> new Seen(true, null, null, "cancel sent"));
                o = await("SECURE_CONFIRM", x -> x != null && x.status().isTerminal(), "the book never showed " + tag
                        + " ended after the cancel");
            } catch (RuntimeException e) {
                leftWorking = true;
                try {
                    o = broker.orderByTag(tag).orElse(o);
                } catch (RuntimeException ignored) {
                    // the read failed as well: what was last seen stands
                }
            }
        }
        if (o.filledQuantity() > 0) {
            filled = Math.max(filled, o.filledQuantity());
        }
        if (filled > 0) {
            soldBack = sellBack(filled) >= filled;
        }
    }

    /** Sells {@code quantity} back with a marketable limit (below the bid) and waits for it; returns what filled. */
    private long sellBack(long quantity) {
        String sellTag = tag + "X";
        double tick = contract.tickSize();
        double reference = bid;
        try {
            Quote q = broker.quote(contract.instrumentKey());
            if (q != null && q.hasBid()) {
                reference = q.bid();
            }
        } catch (RuntimeException e) {
            notes.add("no fresh quote for the sell-back: " + e.getMessage());
        }
        double price = floorToTick(Math.max(tick, Double.isNaN(reference) ? tick : reference * 0.9));
        try {
            step("SELL_BACK", () -> {
                broker.place(new OrderRequest(sellTag, InstrumentKeys.token(contract.instrumentKey()),
                        contract.instrumentKey(), contract.symbol(), contract.exchange(), OrderSide.SELL, OrderType.LIMIT,
                        quantity, price, 0, sellTag));
                return sellTag;
            }, t -> new Seen(true, null, null, "LIMIT SELL " + quantity + " at " + price(price) + " (marketable)"));
            BrokerOrder sell = awaitTag("SELL_BACK_FILLED", sellTag, o -> o != null && o.status().isTerminal(),
                    "the sell-back " + sellTag + " did not end", settings.reads());
            return sell.filledQuantity();
        } catch (RuntimeException e) {
            notes.add("sell-back failed: " + e.getMessage());
            return 0;
        }
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private record Seen(boolean ok, String status, String rawStatus, String detail) {
    }

    private interface Call<T> {
        T call();
    }

    /** Runs one step and records it (a failure as a failed step, then rethrown). */
    private <T> T step(String name, Call<T> call, java.util.function.Function<T, Seen> seen) {
        Instant at = clock.instant();
        long t0 = System.nanoTime();
        try {
            T value = call.call();
            Seen s = seen.apply(value);
            record(new Step(steps.size() + 1, name, at, millis(t0), s.ok(), s.status(), s.rawStatus(), s.detail()));
            return value;
        } catch (Stop s) {
            record(new Step(steps.size() + 1, name, at, millis(t0), false, null, null, s.getMessage()));
            throw s;
        } catch (BrokerException e) {
            record(new Step(steps.size() + 1, name, at, millis(t0), false, null, null, e.kind() + ": " + e.getMessage()));
            throw e;
        } catch (RuntimeException e) {
            record(new Step(steps.size() + 1, name, at, millis(t0), false, null, null, e.toString()));
            throw e;
        }
    }

    private BrokerOrder await(String name, Predicate<BrokerOrder> done, String failure) {
        return awaitTag(name, tag, done, failure, settings.reads());
    }

    /**
     * Reads the order book until the order with {@code orderTag} satisfies {@code done} (null when it is not in the book
     * yet), at most {@code maxReads} times. A fill of the canary buy seen on the way ends the run (FAIL). With {@code failure} null
     * the last read (null: not found) is returned instead of failing.
     */
    private BrokerOrder awaitTag(String name, String orderTag, Predicate<BrokerOrder> done, String failure,
                                 int maxReads) {
        Instant at = clock.instant();
        long t0 = System.nanoTime();
        BrokerOrder last = null;
        int reads = 0;
        try {
            for (int i = 0; i < Math.max(1, maxReads); i++) {
                if (i > 0) {
                    sleep(settings.readEvery());
                }
                reads++;
                last = broker.orderByTag(orderTag).orElse(null);
                if (last != null && orderTag.equals(tag) && last.filledQuantity() > 0) {
                    filled = Math.max(filled, last.filledQuantity());
                    if (!securing) {
                        record(readStep(name, at, t0, false, last, reads, "FILLED " + last.filledQuantity() + " at "
                                + last.averagePrice()));
                        throw new Stop(Result.FAIL, "FAIL at " + name + ": the canary buy filled "
                                + last.filledQuantity() + " at " + last.averagePrice());
                    }
                }
                if (done.test(last)) {
                    record(readStep(name, at, t0, true, last, reads, null));
                    return last;
                }
            }
        } catch (BrokerException e) {
            record(new Step(steps.size() + 1, name, at, millis(t0), false, last == null ? null : last.status().name(),
                    last == null ? null : last.rawStatus(), e.kind() + " after " + reads + " read(s): " + e.getMessage()));
            throw e;
        }
        record(readStep(name, at, t0, failure == null, last, reads, failure == null ? "not in the book" : failure));
        if (failure == null) {
            return last;
        }
        throw new Stop(Result.FAIL, "FAIL at " + name + ": " + failure + " (" + reads + " reads)");
    }

    private Step readStep(String name, Instant at, long t0, boolean ok, BrokerOrder o, int reads, String note) {
        String detail = o == null ? "not in the book" : o.side() + " " + o.quantity() + " at " + o.limitPrice()
                + ", filled " + o.filledQuantity() + ", broker id " + o.brokerOrderId() + ", product " + o.product()
                + (o.message() == null || o.message().isBlank() ? "" : ", message " + o.message());
        return new Step(steps.size() + 1, name, at, millis(t0), ok, o == null ? null : o.status().name(),
                o == null ? null : o.rawStatus(), detail + " (" + reads + " read(s))" + (note == null ? "" : "; " + note));
    }

    private void record(Step s) {
        steps.add(s);
        try {
            journal.step(s);
        } catch (RuntimeException e) {
            log.error("canary {}: step {} was not recorded: {}", tag, s.name(), e.toString());
        }
    }

    private String lastStep() {
        return steps.isEmpty() ? "start" : steps.getLast().name();
    }

    private static boolean ours(BrokerOrder o) {
        return o.clientTag() != null && OUR_TAG.matcher(o.clientTag()).matches();
    }

    private long net(List<BrokerPosition> positions) {
        return positions.stream().filter(p -> contract.instrumentKey().equals(p.instrumentKey()))
                .mapToLong(BrokerPosition::netQuantity).sum();
    }

    private boolean samePrice(double a, double b) {
        return Math.abs(a - b) < contract.tickSize() / 2;
    }

    private double floorToTick(double value) {
        BigDecimal tick = BigDecimal.valueOf(contract.tickSize());
        return BigDecimal.valueOf(value).divide(tick, 0, RoundingMode.FLOOR).multiply(tick).doubleValue();
    }

    private static String price(double p) {
        return BigDecimal.valueOf(p).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static long millis(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerException(BrokerException.Kind.RETRYABLE, "interrupted");
        }
    }
}
