package com.autotrade.strategy.egb;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProvider;

/** Registers the five setup families with {@link com.autotrade.strategy.Strategies} (one provider per strategy key). */
public final class SetupProviders {

    private SetupProviders() {
    }

    private abstract static class Base implements StrategyProvider {
        @Override
        public StrategyFactory create(ThresholdConfig config) {
            return new SetupFactory(config);
        }
    }

    public static final class OpeningReclaimProvider extends Base {
        @Override
        public String strategyKey() {
            return "opening-reclaim";
        }
    }

    public static final class BreakRetestProvider extends Base {
        @Override
        public String strategyKey() {
            return "break-retest";
        }
    }

    public static final class FailedBreakoutProvider extends Base {
        @Override
        public String strategyKey() {
            return "failed-breakout";
        }
    }

    public static final class RangeFadeProvider extends Base {
        @Override
        public String strategyKey() {
            return "range-fade";
        }
    }

    public static final class BreakRetestRunnerProvider extends Base {
        @Override
        public String strategyKey() {
            return "break-retest-runner";
        }
    }

    public static final class TrendDayRiderProvider extends Base {
        @Override
        public String strategyKey() {
            return "trend-day-rider";
        }
    }

    public static final class MaCrossProvider extends Base {
        @Override
        public String strategyKey() {
            return "ma-cross";
        }
    }

    public static final class OpeningRangeContinuationProvider extends Base {
        @Override
        public String strategyKey() {
            return "opening-range-continuation";
        }
    }

    public static final class TrendStateProvider extends Base {
        @Override
        public String strategyKey() {
            return "trend-state";
        }
    }

    public static final class OpeningRangeBreakoutProvider extends Base {
        @Override
        public String strategyKey() {
            return "opening-range-breakout";
        }
    }

    public static final class OpeningDriveMomentumProvider extends Base {
        @Override
        public String strategyKey() {
            return "opening-drive-momentum";
        }
    }

    public static final class OiWallBreakProvider extends Base {
        @Override
        public String strategyKey() {
            return "oi-wall-break";
        }
    }

    public static final class OiFlowBreakProvider extends Base {
        @Override
        public String strategyKey() {
            return "oi-flow-break";
        }
    }

    public static final class StretchReversionProvider extends Base {
        @Override
        public String strategyKey() {
            return "stretch-reversion";
        }
    }

    public static final class ZoneBreakFlowProvider extends Base {
        @Override
        public String strategyKey() {
            return "zone-break-flow";
        }
    }

    public static final class QuietPositioningProvider extends Base {
        @Override
        public String strategyKey() {
            return "quiet-positioning";
        }
    }

    public static final class OpeningClusterBreakProvider extends Base {
        @Override
        public String strategyKey() {
            return "opening-cluster-break";
        }
    }

    public static final class CompressionBreakProvider extends Base {
        @Override
        public String strategyKey() {
            return "compression-break";
        }
    }

    public static final class AuctionPressureProvider extends Base {
        @Override
        public String strategyKey() {
            return "auction-pressure";
        }
    }
}
