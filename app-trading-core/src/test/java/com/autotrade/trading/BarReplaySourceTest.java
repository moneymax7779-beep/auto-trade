package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;

class BarReplaySourceTest {

    private static final Instant T0 = Instant.parse("2026-10-05T03:45:00Z");   // 09:15 IST
    private static final LocalDate EXPIRY = LocalDate.of(2026, 10, 6);

    private static BarReplaySource.Bar bar(String key, String kind, Instant start, double o, double h, double l, double c, long vol, Double oi) {
        return new BarReplaySource.Bar(key, "NIFTY", kind, key, kind.equals("EQUITY") ? "500325" : null,
                kind.equals("INDEX") || kind.equals("EQUITY") ? null : EXPIRY, kind.equals("OPTION") ? 22600 : 0,
                kind.equals("OPTION") ? "CE" : null, kind.equals("OPTION") ? 65 : null, start, o, h, l, c, vol, oi);
    }

    @Test
    void aBarBecomesFourPricesEachTwiceInOrderAndTheCloseIsKnownOnlyJustBeforeTheMinuteEnds() {
        List<MarketEvent> e = BarReplaySource.events(List.of(bar("NSE_INDEX|Nifty 50", "INDEX", T0, 22532.4, 22553.95, 22506.35, 22544.5, 0, null)),
                Map.of("NSE_INDEX|Nifty 50", 22421.95), Map.of());
        assertThat(e).hasSize(8).allMatch(x -> x instanceof IndexTick);
        List<Double> prices = e.stream().map(x -> ((IndexTick) x).price()).toList();
        // up bar: open, low, high, close; each price twice so an order placed on a tick meets the same price again
        assertThat(prices).containsExactly(22532.4, 22532.4, 22506.35, 22506.35, 22553.95, 22553.95, 22544.5, 22544.5);
        assertThat(e.get(0).receivedAt()).isEqualTo(T0.plusMillis(1000));
        assertThat(e.get(1).receivedAt()).as("the repeat comes after the 250 ms fill latency").isEqualTo(T0.plusMillis(2000));
        assertThat(e.get(7).receivedAt()).isEqualTo(T0.plusMillis(59_500));           // before the 09:16 snapshot
        assertThat(((IndexTick) e.get(0)).previousClose()).isEqualTo(22421.95);
        assertThat(e.stream().map(MarketEvent::sourceSequence).toList()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    @Test
    void futuresCarryCumulativeVolumeOpenInterestAndAVwapFromBarCloses() {
        Instant t1 = T0.plusSeconds(60);
        List<MarketEvent> e = BarReplaySource.events(List.of(
                bar("NSE_FO|F", "FUTURE", T0, 22600, 22610, 22590, 22605, 1000, 500_000.0),
                bar("NSE_FO|F", "FUTURE", t1, 22605, 22620, 22600, 22615, 3000, 501_000.0)), Map.of(), Map.of());
        List<FutureTick> closes = e.stream().filter(x -> x instanceof FutureTick f && f.cumulativeVolume() != null).map(x -> (FutureTick) x).toList();
        assertThat(closes).hasSize(2);
        assertThat(closes.get(1).cumulativeVolume()).isEqualTo(4000);
        assertThat(closes.get(1).openInterest()).isEqualTo(501_000.0);
        assertThat(closes.get(1).sessionVwap()).isCloseTo((22605 * 1000 + 22615 * 3000) / 4000.0, org.assertj.core.api.Assertions.within(0.01));
        assertThat(e.stream().filter(x -> x instanceof FutureTick f && f.cumulativeVolume() == null)).as("only the last close tick carries totals; the second bar also has its pre-open quote").hasSize(15);
    }

    @Test
    void atOneInstantOptionAndFutureTicksComeBeforeTheIndexTick() {
        List<MarketEvent> e = BarReplaySource.events(List.of(
                bar("NSE_INDEX|Nifty 50", "INDEX", T0, 22532.4, 22553.95, 22506.35, 22544.5, 0, null),
                bar("NSE_FO|O", "OPTION", T0, 100, 110, 95, 105, 65, 12_000.0),
                bar("NSE_FO|F", "FUTURE", T0, 22600, 22610, 22590, 22605, 1000, 500_000.0)), Map.of(), Map.of());
        assertThat(e.subList(0, 3)).extracting(x -> x.getClass().getSimpleName()).containsExactly("FutureTick", "OptionTick", "IndexTick");
        assertThat(e.subList(0, 3)).allMatch(x -> x.receivedAt().equals(T0.plusMillis(1000)));
    }

    @Test
    void optionsQuoteTheNextBarsOpenJustBeforeTheMinuteBoundaryExceptForTheirFirstBar() {
        Instant t1 = T0.plusSeconds(60);
        List<MarketEvent> e = BarReplaySource.events(List.of(
                bar("NSE_FO|O", "OPTION", T0, 100, 110, 95, 105, 65, 12_000.0),
                bar("NSE_FO|O", "OPTION", t1, 112, 120, 111, 118, 65, 12_100.0),
                bar("NSE_INDEX|Nifty 50", "INDEX", t1, 22544.5, 22560, 22540, 22555, 0, null)), Map.of(), Map.of());
        List<OptionTick> o = e.stream().filter(x -> x instanceof OptionTick).map(x -> (OptionTick) x).toList();
        assertThat(o).as("8 ticks for the first bar, 9 for the second").hasSize(17);
        OptionTick pre = o.get(8);
        assertThat(pre.receivedAt()).isEqualTo(t1.minusMillis(100));
        assertThat(pre.lastPrice()).isEqualTo(112);
        assertThat(pre.cumulativeVolume()).isNull();
        assertThat(e.indexOf(pre)).as("before every tick of the next minute").isLessThan(e.indexOf(e.stream()
                .filter(x -> x instanceof IndexTick).findFirst().orElseThrow()));
        assertThat(o.getFirst().receivedAt()).as("no pre-open tick for a key's first bar").isEqualTo(T0.plusMillis(1000));
    }

    @Test
    void optionsGetOneDepthLevelAndConstituentsTheirWeight() {
        List<MarketEvent> e = BarReplaySource.events(List.of(
                bar("NSE_FO|O", "OPTION", T0, 100, 110, 95, 105, 65, 12_000.0),
                bar("BSE_EQ|RELIANCE", "EQUITY", T0, 2800, 2810, 2790, 2805, 10_000, null)),
                Map.of("BSE_EQ|RELIANCE", 2790.0), Map.of("NIFTY", Map.of("500325", 9.1)));
        OptionTick o = (OptionTick) e.stream().filter(x -> x instanceof OptionTick).toList().getLast();
        assertThat(o.lastPrice()).isEqualTo(105);
        assertThat(o.bids().price(0)).isEqualTo(105);
        assertThat(o.asks().price(0)).isEqualTo(105);
        assertThat(o.spread()).isZero();
        assertThat(o.impliedVolatility()).isNull();
        assertThat(o.lotSize()).isEqualTo(65);
        ConstituentTick c = (ConstituentTick) e.stream().filter(x -> x instanceof ConstituentTick).toList().getFirst();
        assertThat(c.weightPercent()).isEqualTo(9.1);
        assertThat(c.previousClose()).isEqualTo(2790.0);
    }
}
