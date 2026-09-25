package com.autotrade.tools.command;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionEventSource;
import com.autotrade.core.time.MarketTime;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.FillModel;
import com.autotrade.sim.LongOptionSimulator;
import com.autotrade.sim.TradePlan;
import com.autotrade.sim.TradeResult;

/**
 * Simulates buying one option contract at a given time on a replayed session, under the base and
 * stressed fill models. Stop and target are percentages of the ask seen just before the decision.
 */
final class SimulateCommand {

    record Request(LocalDate session, String underlying, double strike, OptionTick.OptionType type, LocalTime at,
                   double stopPct, double targetPct, LocalTime exitBy, int lots, Path costsFile) {
    }

    private SimulateCommand() {
    }

    static int run(SessionEventSource source, Request request) throws Exception {
        ThresholdConfig costConfig = ThresholdConfig.load(request.costsFile());
        CostModel costs = CostModel.from(costConfig);
        List<FillModel> models = List.of(FillModel.from(costConfig, "base"), FillModel.from(costConfig, "stressed"));
        Instant decision = request.session().atTime(request.at()).atZone(MarketTime.IST).toInstant();
        Instant exitBy = request.session().atTime(request.exitBy()).atZone(MarketTime.IST).toInstant();
        String exchange = request.underlying().equals("SENSEX") ? "BSE" : "NSE";

        List<LongOptionSimulator> simulators = new ArrayList<>();
        OptionTick[] reference = new OptionTick[1];
        source.replay(request.session(), List.of(request.underlying()), (MarketEvent event) -> {
            if (event instanceof OptionTick tick && tick.strike() == request.strike()
                    && tick.optionType() == request.type() && !tick.expiry().isBefore(request.session())) {
                boolean nearest = reference[0] == null || !tick.expiry().isAfter(reference[0].expiry());
                if (tick.receivedAt().isBefore(decision) && nearest) {
                    reference[0] = tick;
                } else if (simulators.isEmpty() && reference[0] != null) {
                    double ask = reference[0].asks().isEmpty() ? reference[0].lastPrice() : reference[0].asks().price(0);
                    long quantity = (long) request.lots() * reference[0].lotSize();
                    for (FillModel model : models) {
                        simulators.add(new LongOptionSimulator(new TradePlan(reference[0].instrumentToken(), exchange,
                                quantity, decision, ask * (1 - request.stopPct() / 100),
                                ask * (1 + request.targetPct() / 100), exitBy), model, costs));
                    }
                }
            }
            for (LongOptionSimulator simulator : simulators) {
                simulator.accept(event);
            }
        });
        if (reference[0] == null) {
            System.err.println("no quotes for that contract before " + request.at());
            return 1;
        }
        OptionTick ref = reference[0];
        double ask = ref.asks().isEmpty() ? ref.lastPrice() : ref.asks().price(0);
        System.out.printf("%s, %d lot(s) x %d, decision %s IST; reference ask %.2f (bid %.2f); stop %.2f (-%s%%), "
                        + "target %.2f (+%s%%), exit by %s; costs %s%n",
                ref.symbol(), request.lots(), ref.lotSize(), request.at(), ask,
                ref.bids().isEmpty() ? Double.NaN : ref.bids().price(0), ask * (1 - request.stopPct() / 100),
                trim(request.stopPct()), ask * (1 + request.targetPct() / 100), trim(request.targetPct()),
                request.exitBy(), costs.configHash().substring(0, 19));
        for (LongOptionSimulator simulator : simulators) {
            TradeResult result = simulator.result();
            System.out.printf("  %-8s %-18s %-6s entry %s @ %.2f  exit %s @ %s  gross %,.0f  costs %,.0f  net %,.0f  "
                            + "MFE %+.2f MAE %+.2f per unit, held %ds%n",
                    result.fillModel(), result.status(), result.exitReason() == null ? "-" : result.exitReason(),
                    time(result.entry() == null ? null : result.entry().time()),
                    result.entry() == null ? Double.NaN : result.entry().price(),
                    time(result.exit() == null ? null : result.exit().time()),
                    result.exit() == null ? "-" : String.format("%.2f", result.exit().price()),
                    result.grossPnl(), result.totalCosts(), result.netPnl(), result.maxFavourablePerUnit(),
                    -result.maxAdversePerUnit(), result.holdSeconds());
        }
        return 0;
    }

    private static String time(Instant instant) {
        return instant == null ? "-" : instant.atZone(MarketTime.IST).toLocalTime().toString();
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
