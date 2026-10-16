package com.autotrade.trading;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.execution.SignalDecision;
import com.autotrade.execution.SignalDecisionStore;
import com.autotrade.oms.Contract;
import com.autotrade.oms.ManagedPosition;
import com.autotrade.strategy.OptionSide;

/**
 * Live execution L1 (docs/LIVE-TRADING-PLAN.md section 1): records a signal-source session's decisions as broker-neutral
 * exec.signal_decision rows for its copy group. Attached only to the signal-source session (account SIGNAL-1, risk
 * signal-source.v1); PAPER-1 never has one. ENTER / ADD when the virtual account approved them, EXIT when its position
 * closed, for any reason. A failed write is logged and never disturbs the session. Priority is the strategy's place in
 * the session's list (the order in which the live set takes capital).
 */
final class SignalRecorder {

    private static final Logger log = LoggerFactory.getLogger(SignalRecorder.class);

    private final SignalDecisionStore store;
    private final long copyGroupId;
    private final double referenceCapital;
    private final Map<String, Integer> priorities;
    private final AtomicLong sequence = new AtomicLong();
    private long sessionId;
    private LocalDate session;

    SignalRecorder(SignalDecisionStore store, long copyGroupId, double referenceCapital, List<String> strategyOrder) {
        this.store = store;
        this.copyGroupId = copyGroupId;
        this.referenceCapital = referenceCapital;
        this.priorities = IntStream.range(0, strategyOrder.size()).boxed()
                .collect(Collectors.toMap(strategyOrder::get, Function.identity(), (a, b) -> a));
    }

    /** Called once the signal session has its id. */
    void start(long signalSessionId, LocalDate day) {
        this.sessionId = signalSessionId;
        this.session = day;
    }

    void entered(Instant at, String strategyId, String underlying, OptionSide side, Contract contract, double ask,
                 double budget, int lots, String stage, String reason, String legGroup) {
        write(at, strategyId, underlying, SignalDecision.Action.ENTER, side, contract, ask, budget, lots, stage, reason,
                legGroup);
    }

    void added(Instant at, String strategyId, String underlying, OptionSide side, Contract contract, double ask,
               int lots, String stage, String reason) {
        write(at, strategyId, underlying, SignalDecision.Action.ADD, side, contract, ask, Double.NaN, lots, stage, reason,
                null);
    }

    /** A single-leg position's leg is "" (no leg group); a straddle leg's exit is marked STRADDLE:CE / STRADDLE:PE. */
    void closed(ManagedPosition position) {
        Instant at = position.closedAt() != null ? position.closedAt() : position.openedAt();
        write(at, position.strategy(), position.underlying(), SignalDecision.Action.EXIT, position.side(),
                position.contract(), position.lastBid(), Double.NaN, position.lots(), null,
                position.exitReason() == null ? "CLOSED" : position.exitReason(),
                position.leg() == null || position.leg().isEmpty() ? null : "STRADDLE:" + position.leg());
    }

    private void write(Instant at, String strategyId, String underlying, SignalDecision.Action action, OptionSide side,
                       Contract contract, double price, double budget, int lots, String stage, String reason,
                       String legGroup) {
        if (sessionId == 0) {
            return;
        }
        try {
            long seq = sequence.incrementAndGet();
            store.insert(new SignalDecision(copyGroupId, sessionId, session, at, strategyId,
                    priorities.getOrDefault(strategyId, priorities.size()), underlying, action, side.name(), legGroup,
                    stage, reason == null ? action.name() : reason, contract.token(), contract.instrumentKey(),
                    contract.symbol(), contract.lotSize(), price, budget, referenceCapital, lots,
                    copyGroupId + ":" + sessionId + ":" + seq));
        } catch (RuntimeException e) {
            log.warn("signal decision not recorded ({} {} {} {}): {}", strategyId, underlying, action, side,
                    e.getMessage());
        }
    }
}
