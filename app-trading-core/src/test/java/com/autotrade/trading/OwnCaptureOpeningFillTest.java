package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.time.MarketTime;
import com.autotrade.trading.OwnCaptureSource.OpeningFill;
import com.autotrade.trading.OwnCaptureSource.OpeningFill.Bar;

/** A-606: a capture that starts after the open gets the missing index minutes from zt's minute bars, replayed first. */
class OwnCaptureOpeningFillTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    private static IndexTick tick(String hhmmss, String underlying, double price) {
        Instant at = DAY.atTime(java.time.LocalTime.parse(hhmmss)).atZone(MarketTime.IST).toInstant();
        return new IndexTick(at, at, underlying, 1, price, 25514.3, 25450);
    }

    /** 20 Nov 2026 as zt has it: NIFTY 09:15 O 25,367.9 H 25,418.4 L 25,354.9 C 25,371.7; 09:16 one bar; SENSEX one bar. */
    private static final OpeningFill.Bars ZT = (u, from, until) -> {
        assertThat(from).isEqualTo(DAY.atTime(9, 15));
        List<Bar> all = "NIFTY".equals(u)
                ? List.of(new Bar(DAY.atTime(9, 15), 25367.9, 25418.4, 25354.9, 25371.7),
                        new Bar(DAY.atTime(9, 16), 25371.3, 25438.5, 25368.35, 25437.4))
                : List.of(new Bar(DAY.atTime(9, 15), 82479.52, 82726.27, 82479.52, 82558.74));
        return all.stream().filter(b -> b.start().isBefore(until)).toList();
    };

    private static List<MarketEvent> run(OpeningFill.Bars bars, IndexTick... events) {
        List<MarketEvent> out = new ArrayList<>();
        OpeningFill fill = new OpeningFill(bars, DAY, List.of("NIFTY", "SENSEX"), out::add);
        for (IndexTick e : events) {
            fill.accept(e);
        }
        return out;
    }

    @Test
    void aLateCaptureGetsTheOpeningMinutesFirstInTimeOrder() {
        IndexTick first = tick("09:17:02", "NIFTY", 25436.05);
        List<MarketEvent> out = run(ZT, first, tick("09:17:03", "SENSEX", 82757.85));

        // 2 NIFTY bars + 1 SENSEX bar, four ticks each, then the captured events unchanged
        assertThat(out).hasSize(14);
        assertThat(out.subList(12, 14)).containsExactly(first, out.get(13));
        List<IndexTick> nifty = out.subList(0, 12).stream().map(IndexTick.class::cast).filter(t -> t.underlying().equals("NIFTY")).toList();
        // open, the nearer extreme (low 13 away vs high 50.5), the other, close; 15 s apart from the bar start
        assertThat(nifty.subList(0, 4)).extracting(IndexTick::price).containsExactly(25367.9, 25354.9, 25418.4, 25371.7);
        assertThat(nifty.get(0).receivedAt()).isEqualTo(DAY.atTime(9, 15).atZone(MarketTime.IST).toInstant());
        assertThat(nifty.get(3).receivedAt()).isEqualTo(DAY.atTime(9, 15, 45).atZone(MarketTime.IST).toInstant());
        assertThat(nifty.get(0).atmStrike()).isEqualTo(25350);
        assertThat(out.subList(0, 12)).isSortedAccordingTo(java.util.Comparator.comparing(MarketEvent::receivedAt));
        // the 09:17 bar itself is not filled: the capture has that minute
        assertThat(out.subList(0, 12)).allSatisfy(e -> assertThat(e.receivedAt())
                .isBefore(DAY.atTime(9, 17).atZone(MarketTime.IST).toInstant()));
    }

    @Test
    void aCaptureFromTheOpenOrAfter0930IsLeftAlone() {
        OpeningFill.Bars never = (u, from, until) -> {
            throw new AssertionError("zt must not be read");
        };
        assertThat(run(never, tick("09:15:03", "NIFTY", 25367.9))).hasSize(1);
        assertThat(run(never, tick("09:07:32", "NIFTY", 25190.0))).as("pre-open ticks").hasSize(1);
        assertThat(run(never, tick("09:31:00", "NIFTY", 25450.0))).hasSize(1);
        // only the day's first event decides
        assertThat(run(never, tick("09:15:01", "NIFTY", 25367.9), tick("09:20:00", "NIFTY", 25455.6))).hasSize(2);
    }

    @Test
    void anUnreadableZtReplaysTheCaptureAsItIs() {
        OpeningFill.Bars broken = (u, from, until) -> {
            throw new java.sql.SQLException("connection refused");
        };
        IndexTick first = tick("09:17:02", "NIFTY", 25436.05);
        assertThat(run(broken, first)).containsExactly(first);
        assertThat(LocalDateTime.ofInstant(first.receivedAt(), MarketTime.IST).toLocalTime()).hasToString("09:17:02");
    }
}
