package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/** The closing-auction lines: the options' synthetic index and the auction's indicative index, from per-bucket inputs. */
class CloseAuctionTest {

    private static final int B = CloseAuctionController.BUCKETS, FREEZE = CloseAuctionController.FREEZE_BUCKET;

    private static double[] filled(double v) {
        double[] a = new double[B];
        Arrays.fill(a, v);
        return a;
    }

    @Test
    void theSyntheticIsTheForwardLessItsPremiumOverTheIndexBefore1515() {
        double[] index = filled(Double.NaN), forward = filled(Double.NaN);
        for (int b = 0; b < FREEZE; b++) {
            index[b] = 24_500;
            forward[b] = 24_500 + (b % 2 == 0 ? 6 : 4);                     // premium 4-6, median 5
        }
        for (int b = FREEZE; b < B; b++) {
            index[b] = 24_500;                                              // frozen
            forward[b] = 24_525;                                            // the options moved on
        }
        Map<String, Object> out = CloseAuctionController.build(index, forward, 24_500, Map.of(), Map.of(), Map.of());
        assertThat(out.get("basis")).isEqualTo(5.0);
        @SuppressWarnings("unchecked") List<List<Object>> s = (List<List<Object>>) out.get("series");
        assertThat(s.getFirst().getFirst()).isEqualTo("15:10:00");          // shown from 15:10
        List<Object> last = s.getLast();
        assertThat(last.get(1)).isEqualTo(24_520.0);
        assertThat(last.get(2)).isNull();                                    // no auction data: no indicative line
    }

    @Test
    void theIndicativeMovesThe1515IndexByEachWeightedStocksAuctionPriceCarriedUntilItChanges() {
        Map<String, Double> w = Map.of("A", 60.0, "B", 40.0, "C", 5.0);     // C has no price before 15:15: left out
        Map<String, Double> p0 = Map.of("A", 100.0, "B", 200.0);
        double[] a = filled(Double.NaN), b = filled(Double.NaN);
        int at = FREEZE + 20;                                                // 15:20
        a[at] = 101;                                                         // A +1 %
        b[at + 4] = 196;                                                     // B −2 % a minute later
        Map<String, Object> out = CloseAuctionController.build(filled(Double.NaN), filled(Double.NaN), 24_000, w, p0,
                Map.of("A", a, "B", b));
        @SuppressWarnings("unchecked") List<List<Object>> s = (List<List<Object>>) out.get("series");
        List<Object> first = s.getFirst(), later = s.get(4), end = s.getLast();
        assertThat(first.getFirst()).isEqualTo("15:20:00");
        assertThat((Double) first.get(2)).isCloseTo(24_000 * (0.6 * 1.01 + 0.4), within(0.01));   // B not in the auction yet
        assertThat(first.get(3)).isEqualTo(60.0);                            // 60 % of the weight priced
        assertThat((Double) later.get(2)).isCloseTo(24_000 * (0.6 * 1.01 + 0.4 * 0.98), within(0.01));
        assertThat(end.get(2)).isEqualTo(later.get(2));                      // carried to the end
        assertThat(out.get("weightsCovered")).isEqualTo(100.0);
    }

    @Test
    void theForwardIsTheMedianOverStrikesQuotedOnBothSides() {
        Map<Double, double[]> calls = new TreeMap<>(), puts = new TreeMap<>();
        calls.put(24_400.0, filled(Double.NaN));
        puts.put(24_400.0, filled(Double.NaN));
        calls.put(24_500.0, filled(Double.NaN));
        puts.put(24_500.0, filled(Double.NaN));
        calls.get(24_400.0)[0] = 160;
        puts.get(24_400.0)[0] = 50;                                          // 24,510
        calls.get(24_500.0)[0] = 100;
        puts.get(24_500.0)[1] = 86;                                          // the put quotes a bucket later
        double[] f = CloseAuctionController.forward(calls, puts);
        assertThat(f[0]).isEqualTo(24_510.0);                                // one strike on both sides yet
        assertThat(f[1]).isEqualTo((24_510.0 + 24_514.0) / 2);               // the call mid carried forward
    }

    @Test
    void nearestStrikesAndWeightKeys() {
        assertThat(CloseAuctionController.nearest(List.of(24_300.0, 24_350.0, 24_400.0, 24_450.0, 24_500.0, 24_550.0, 24_600.0),
                24_472.0, 2)).containsExactly(24_350.0, 24_400.0, 24_450.0, 24_500.0, 24_550.0);
        Map<String, Double> sensex = Map.of("532174", 11.4);
        assertThat(CloseAuctionController.key(sensex, "ICICIBANK", 2_000_532_174L)).isEqualTo("532174");
        assertThat(CloseAuctionController.key(Map.of("ICICIBANK", 9.0), "ICICIBANK", 4963L)).isEqualTo("ICICIBANK");
        // a BSE row with the same trading symbol never counts for an NSE index
        assertThat(CloseAuctionController.key(Map.of("RELIANCE", 8.0), "RELIANCE", 2_000_500_325L)).isNull();
        assertThat(CloseAuctionController.key(sensex, "TCS", 11_536L)).isNull();
    }
}
