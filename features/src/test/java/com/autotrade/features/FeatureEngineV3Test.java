package com.autotrade.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionPhaseEvent;
import com.autotrade.core.history.DailyBar;
import com.autotrade.core.history.IvSession;
import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.history.ReferenceData;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.FeatureSnapshot;

/** Features v3 end to end through the engine: VIX, IV percentile, book imbalance, premium, CAS. */
class FeatureEngineV3Test {

    private static final LocalDate SESSION = LocalDate.of(2026, 9, 25);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 9, 29);

    @Test
    void v2FilesLeaveTheNewSectionsEmpty() {
        List<FeatureSnapshot> snapshots = new ArrayList<>();
        FeatureEngine engine = new FeatureEngine("NIFTY", SESSION, TestConfig.load(), history(), snapshots::add);
        engine.accept(index("09:15:02", 23000));
        engine.accept(index("09:17:00", 23001));
        assertThat(snapshots.getLast().volatility().vixSource()).isEqualTo("NONE");
        assertThat(snapshots.getLast().levels().ladderLevelsAbove()).isZero();
    }

    @Test
    void vixFromMinuteBarsOnlyOnceTheyHaveEndedThenFromTicks() {
        List<FeatureSnapshot> snapshots = new ArrayList<>();
        FeatureEngine engine = new FeatureEngine("NIFTY", SESSION, TestConfig.loadV3(), history(), snapshots::add);
        engine.accept(index("09:15:02", 23000));
        engine.accept(index("09:40:30", 23010));

        FeatureSnapshot at0940 = snapshots.getLast();
        assertThat(at0940.time()).isEqualTo(at("09:40:00"));
        // bars 09:15..09:39 have ended by 09:40 (close = 12 + minute index × 0.01); the 09:40 bar has not
        assertThat(at0940.volatility().vix()).isCloseTo(12.24, within(1e-9));
        assertThat(at0940.volatility().vixChange5m()).isCloseTo(0.05, within(1e-9));
        assertThat(at0940.volatility().vixChangeDay()).isCloseTo(12.24 - 11.0, within(1e-9));
        assertThat(at0940.volatility().vixPercentile60d()).isEqualTo(100.0); // above every earlier close
        assertThat(at0940.volatility().vixSource()).isEqualTo("MINUTE");

        engine.accept(new IndexTick(at("09:40:40"), null, ReferenceData.INDIA_VIX, 1, 13.5, null, null));
        engine.accept(index("09:41:10", 23011));
        assertThat(snapshots.getLast().volatility().vix()).isEqualTo(13.5);
        assertThat(snapshots.getLast().volatility().vixSource()).isEqualTo("TICK");
    }

    @Test
    void ivPercentileComparesTheSameMinuteOfSameKindSessions() {
        List<FeatureSnapshot> snapshots = new ArrayList<>();
        FeatureEngine engine = new FeatureEngine("NIFTY", SESSION, TestConfig.loadV3(), history(), snapshots::add);
        engine.accept(index("09:15:02", 23000));
        for (String time : List.of("09:59:00", "09:59:30")) {
            engine.accept(option(time, 23000, OptionTick.OptionType.CE, 100, 0.13, 1000.0, 500.0));
            engine.accept(option(time, 23000, OptionTick.OptionType.PE, 95, 0.13, 400.0, 600.0));
            engine.accept(option(time, 23050, OptionTick.OptionType.CE, 75, 0.125, 100.0, 100.0));
            engine.accept(option(time, 23050, OptionTick.OptionType.PE, 120, 0.135, 100.0, 100.0));
        }
        engine.accept(index("10:00:01", 23001));

        FeatureSnapshot at1000 = snapshots.getLast();
        // earlier non-expiry sessions had 0.10 .. 0.14 at 09:59 (three below 0.13); the expiry-day 0.30 is excluded
        assertThat(at1000.options().atmIv()).isCloseTo(0.13, within(1e-9));
        assertThat(at1000.volatility().ivHistorySessions()).isEqualTo(5);
        assertThat(at1000.volatility().atmIvPercentile()).isEqualTo(60.0);
        // book: calls bid (1000 vs 500), puts offered (400 vs 600), held over the whole window
        assertThat(at1000.book().atmCe()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(at1000.book().atmPe()).isCloseTo(-0.2, within(1e-9));
        assertThat(at1000.book().atmCeMinShort()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(at1000.book().optionSource()).isEqualTo("TOTAL_QTY");
        assertThat(at1000.premium().otmCeIv()).isCloseTo(0.125, within(1e-9));
        assertThat(at1000.premium().itmPeIv()).isCloseTo(0.135, within(1e-9));
    }

    @Test
    void futuresBookImbalanceNeedsBookTotals() {
        List<FeatureSnapshot> snapshots = new ArrayList<>();
        FeatureEngine engine = new FeatureEngine("NIFTY", SESSION, TestConfig.loadV3(), history(), snapshots::add);
        engine.accept(index("09:15:02", 23000));
        engine.accept(future("09:30:00", 23050, 10L, 3000.0, 1000.0));
        engine.accept(future("09:30:30", 23051, 20L, 3000.0, 1000.0));
        engine.accept(index("09:31:01", 23001));
        FeatureSnapshot at0931 = snapshots.getLast();
        assertThat(at0931.book().futures()).isEqualTo(0.5);
        assertThat(at0931.book().futuresMinLong()).isEqualTo(0.5);
    }

    @Test
    void closingAuctionFeaturesFromTheIndicativeIndexFuturesAndStocks() {
        List<FeatureSnapshot> snapshots = new ArrayList<>();
        FeatureEngine engine = new FeatureEngine("NIFTY", SESSION, TestConfig.loadV3(), history(), snapshots::add);
        engine.accept(index("09:15:02", 22900));
        engine.accept(stock("09:20:00", "HDFCBANK", 13.0, 1000));
        engine.accept(stock("09:20:00", "RELIANCE", 9.0, 1400));
        engine.accept(stock("09:20:00", "ITC", 3.0, 400));
        for (int s = 0; s < 900; s += 10) { // 15:00:00 .. 15:14:50 at 23000
            engine.accept(index(LocalTime.of(15, 0).plusSeconds(s).toString(), 23000));
        }
        engine.accept(future("15:14:00", 23040, 100L, null, null));
        engine.accept(phase("15:16:30", 23010));
        engine.accept(future("15:16:30", 23050, 200L, null, null));
        engine.accept(phase("15:17:30", 23030));
        engine.accept(future("15:17:30", 23070, 300L, null, null));
        engine.accept(auction("15:17:40", "HDFCBANK", 1003, 1000, 5000, 1000));  // +0.3%, buyers
        engine.accept(auction("15:17:40", "RELIANCE", 1402.8, 1400, 4000, 500)); // +0.2%, buyers
        engine.accept(auction("15:17:40", "ITC", 399, 400, 2000, -300));         // -0.25%, sellers
        engine.accept(phase("15:18:05", 23046));

        FeatureSnapshot at1518 = snapshots.getLast();
        assertThat(at1518.time()).isEqualTo(at("15:18:00"));
        assertThat(at1518.phase()).startsWith("CAS");
        assertThat(at1518.cas().referenceIndex()).isEqualTo(23000);
        assertThat(at1518.cas().indicativeIndex()).isEqualTo(23030);
        assertThat(at1518.cas().indicativeReturnPct()).isCloseTo(100 * 30 / 23000.0, within(1e-9));
        assertThat(at1518.cas().indicativeChangeMid()).isEqualTo(20);
        assertThat(at1518.cas().futuresChange1m()).isEqualTo(20);
        assertThat(at1518.cas().futuresFollowRatio()).isEqualTo(1.0);
        assertThat(at1518.cas().futuresVsIndicative()).isEqualTo(40);
        assertThat(at1518.cas().auctionStocks()).isEqualTo(3);
        assertThat(at1518.cas().constituentCoveragePct()).isEqualTo(100);
        assertThat(at1518.cas().casBreadthPct()).isCloseTo(100 * 22 / 25.0, within(1e-9));
        assertThat(at1518.cas().weightedIepReturnPct())
                .isCloseTo(100 * (13 * 0.003 + 9 * 0.002 - 3 * 0.0025) / 25, within(1e-9));
        assertThat(at1518.cas().weightedImbalance()).isGreaterThan(0);
        assertThat(at1518.cas().topConcentration()).isEqualTo(1.0); // only two stocks on the dominant side
        assertThat(at1518.secondsSinceSpot()).isLessThan(60); // the indicative feed keeps the index fresh in CAS
    }

    // ------------------------------------------------------------------ fixtures

    private static SessionHistory history() {
        SessionHistory market = new SessionHistory() {
            @Override
            public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
                return Optional.of(new DailyBar(session.minusDays(1), 22950, 23100, 22900, 23060));
            }

            @Override
            public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
                return List.of();
            }

            @Override
            public List<IvSession> atmIvMinutes(String underlying, LocalDate session, int sessions) {
                List<IvSession> ivs = new ArrayList<>();
                double[] values = {0.10, 0.11, 0.12, 0.135, 0.14};
                for (int i = 0; i < values.length; i++) {
                    ivs.add(new IvSession(session.minusDays(i + 1), false, Map.of(LocalTime.of(9, 59), values[i])));
                }
                ivs.add(new IvSession(session.minusDays(8), true, Map.of(LocalTime.of(9, 59), 0.30)));
                return ivs;
            }
        };
        ReferenceData vix = new ReferenceData() {
            @Override
            public List<DailyBar> dailyBars(String symbol, LocalDate session, int sessions) {
                List<DailyBar> bars = new ArrayList<>();
                for (int i = 0; i < 60; i++) {
                    double close = 11.0 - i * 0.01;
                    bars.add(new DailyBar(session.minusDays(i + 1), close, close, close, close));
                }
                return bars;
            }

            @Override
            public List<MinuteBar> intradayBars(String symbol, LocalDate session) {
                List<MinuteBar> bars = new ArrayList<>();
                for (int i = 0; i < 375; i++) {
                    Instant start = at("09:15:00").plusSeconds(60L * i);
                    double close = 12 + i * 0.01;
                    bars.add(new MinuteBar(start, close, close, close, close));
                }
                return bars;
            }
        };
        return market.withReference(vix);
    }

    private static IndexTick index(String time, double price) {
        return new IndexTick(at(time), null, "NIFTY", 1, price, 23063.1, null);
    }

    private static FutureTick future(String time, double price, Long volume, Double buy, Double sell) {
        return new FutureTick(at(time), null, "NIFTY", 1, 99, "NIFTY FUT", EXPIRY, price, volume, 1_000_000.0, null,
                buy, sell);
    }

    private static OptionTick option(String time, double strike, OptionTick.OptionType type, double price, double iv,
                                     Double buy, Double sell) {
        DepthLevels bid = DepthLevels.of(new double[] {price - 0.5}, new long[] {75}, new int[] {1});
        DepthLevels ask = DepthLevels.of(new double[] {price + 0.5}, new long[] {75}, new int[] {1});
        return new OptionTick(at(time), null, "NIFTY", 1, (long) strike * 10 + type.ordinal(), "NIFTY " + strike + type,
                EXPIRY, strike, type, 75, price, 1000L, 10000.0, buy, sell, bid, ask, iv, type == OptionTick.OptionType.CE
                ? 0.5 : -0.5, 0.001, -5.0, 10.0, 1.0, at(time), true, true);
    }

    private static ConstituentTick stock(String time, String symbol, double weight, double price) {
        return new ConstituentTick(at(time), null, "NIFTY", 1, symbol.hashCode(), symbol, price, price, 0L, price, weight);
    }

    private static AuctionTick auction(String time, String symbol, double iep, double reference, long quantity,
                                       long imbalance) {
        return new AuctionTick(at(time), null, "NIFTY", symbol.hashCode(), symbol, iep, reference, quantity, imbalance,
                0, true);
    }

    private static SessionPhaseEvent phase(String time, double price) {
        return new SessionPhaseEvent(at(time), at(time), "NIFTY", "CAS_MARKET_AND_LIMIT_ENTRY",
                "CAS_INDICATIVE_INDEX_UNVERIFIED", price, false);
    }

    private static Instant at(String time) {
        return SESSION.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant();
    }
}
