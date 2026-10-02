package com.autotrade.trading;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which futures-volume surges reach Telegram, and how they read. Only surges whose OI flow agrees on a direction
 * (bullish / bearish, never mixed), at least {@code minX} × the minute's normal volume and {@code minVolume}
 * contracts, inside the alert window; one per index and direction per {@code cooldownMin} unless the flow flips
 * or the volume multiple at least doubles. Context for a manual call, not a trade signal: the flow had no edge
 * over 18 sessions (docs/studies/2026-09-29-surge-oi-flow.md).
 */
final class SurgeAlertRules {

    record Settings(double minX, Map<String, Long> minVolume, int cooldownMin, LocalTime from, LocalTime until) {
    }

    /** A level known at the alert's time: a price line, or a tested zone (lo..hi). */
    record Mark(String name, double lo, double hi) {
        double mid() {
            return (lo + hi) / 2;
        }
    }

    /** Context known at the alert's time (null when unknown). */
    record Levels(List<Mark> marks, Double vwap, Double dayHigh, Double dayLow) {
    }

    private final Settings settings;
    private final Map<String, double[]> last = new HashMap<>();     // underlying|flow -> {minute, x}

    SurgeAlertRules(Settings settings) {
        this.settings = settings;
    }

    boolean accept(String underlying, Map<String, Object> s) {
        String flow = (String) s.get("flow");
        if (!"bullish".equals(flow) && !"bearish".equals(flow)) {
            return false;
        }
        double x = num(s.get("x"));
        double vol = num(s.get("vol"));
        LocalTime t = LocalTime.parse((String) s.get("t"));
        long minVolume = settings.minVolume() == null ? 0 : settings.minVolume().getOrDefault(underlying, 0L);
        if (x < settings.minX() || vol < minVolume || t.isBefore(settings.from()) || t.isAfter(settings.until())) {
            return false;
        }
        int minute = t.toSecondOfDay() / 60;
        double[] previous = last.get(underlying + "|" + flow);
        if (previous != null && minute - previous[0] < settings.cooldownMin() && x < 2 * previous[1]) {
            return false;
        }
        last.put(underlying + "|" + flow, new double[] {minute, x});
        return true;
    }

    @SuppressWarnings("unchecked")
    static String message(String underlying, Map<String, Object> s, Levels levels, String hitRate) {
        String flow = (String) s.get("flow");
        boolean bull = "bullish".equals(flow);
        Map<String, Object> fut = (Map<String, Object>) s.get("futures");
        Map<String, Object> ce = (Map<String, Object>) s.get("CE");
        Map<String, Object> pe = (Map<String, Object>) s.get("PE");
        StringBuilder m = new StringBuilder();
        m.append(bull ? "🟢 " : "🔴 ").append(underlying).append(' ').append(flow.toUpperCase(Locale.ROOT))
                .append(" surge · ").append(s.get("t")).append(" (flow known ").append(s.getOrDefault("from", "t+1")).append(")\n");
        m.append("Futures ").append(fmt(num(s.get("vol")), 0)).append(" = ").append(fmt(num(s.get("x")), 1)).append("× normal");
        if (fut != null) {
            m.append(" · OI ").append(signed(num(fut.get("pct")), 2)).append("% ").append(fut.get("label"))
                    .append(" · px ").append(signed(num(fut.get("px")), 1));
        }
        m.append('\n');
        m.append("Calls ±2 ").append(side(ce)).append(" · Puts ±2 ").append(side(pe)).append('\n');
        double spot = num(s.get("spot"));
        m.append("Index ").append(fmt(spot, 2)).append(distances(spot, levels)).append('\n');
        if (s.get("atm") != null) {
            m.append("ATM ").append(fmt(num(s.get("atm")), 0)).append(": CE ").append(quote(ce)).append(" · PE ").append(quote(pe)).append('\n');
        }
        if (hitRate != null) {
            m.append(hitRate).append('\n');
        }
        m.append("Context only, not a trade signal (no edge over 18 sessions). Follow-up in 15 min.");
        return m.toString();
    }

    static String followUp(String underlying, Map<String, Object> s) {
        boolean bull = "bullish".equals(s.get("flow"));
        Double m15 = nullable(s.get("move15"));
        String verdict = m15 == null ? "" : (m15 > 0) == bull ? " → WITH the flow" : m15 == 0 ? " → flat" : " → AGAINST the flow";
        return "↳ " + underlying + " " + s.get("t") + " " + s.get("flow") + " surge, from " + s.getOrDefault("from", "t+1")
                + ": +5m " + signedOrDash(s.get("move5")) + " · +15m " + signedOrDash(s.get("move15"))
                + " · 15m range " + signedOrDash(s.get("best15")) + " / " + signedOrDash(s.get("worst15")) + verdict;
    }

    /** Today's surges with the same flow: how often the index moved with it 15 minutes after t+1. */
    @SuppressWarnings("unchecked")
    static String hitRate(List<Object> surges, String flow, LocalTime asOf) {
        int n = 0, right = 0;
        for (Object o : surges) {
            Map<String, Object> s = (Map<String, Object>) o;
            Double m15 = nullable(s.get("move15"));
            boolean known = s.get("from") == null || !LocalTime.parse((String) s.get("from")).plusMinutes(15).isAfter(asOf);
            if (flow.equals(s.get("flow")) && m15 != null && known) {
                n++;
                if ("bullish".equals(flow) ? m15 > 0 : m15 < 0) {
                    right++;
                }
            }
        }
        return n == 0 ? null : "Today so far: " + flow + " flow right " + right + "/" + n + " at +15m";
    }

    /** The two nearest levels above and below spot, VWAP and the day's range so far. */
    private static String distances(double spot, Levels l) {
        if (l == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        List<Mark> marks = l.marks() == null ? List.of() : l.marks();
        List<Mark> above = marks.stream().filter(m -> m.lo() > spot)
                .sorted(java.util.Comparator.comparingDouble(Mark::lo)).limit(2).toList();
        List<Mark> below = marks.stream().filter(m -> m.hi() < spot)
                .sorted(java.util.Comparator.comparingDouble(Mark::hi).reversed()).limit(2).toList();
        List<Mark> inside = marks.stream().filter(m -> m.lo() <= spot && spot <= m.hi()).toList();
        if (!inside.isEmpty()) {
            b.append("\nAt: ").append(String.join(", ", inside.stream().map(m -> mark(m, spot)).toList()));
        }
        if (!above.isEmpty()) {
            b.append("\nAbove: ").append(String.join(", ", above.stream().map(m -> mark(m, spot)).toList()));
        }
        if (!below.isEmpty()) {
            b.append("\nBelow: ").append(String.join(", ", below.stream().map(m -> mark(m, spot)).toList()));
        }
        StringBuilder tail = new StringBuilder();
        if (l.vwap() != null) {
            tail.append("VWAP ").append(fmt(l.vwap(), 0)).append(" (").append(signed(spot - l.vwap(), 0)).append(")");
        }
        if (l.dayLow() != null && l.dayHigh() != null) {
            tail.append(tail.isEmpty() ? "" : " · ").append("day ").append(fmt(l.dayLow(), 0)).append("–").append(fmt(l.dayHigh(), 0));
        }
        if (!tail.isEmpty()) {
            b.append("\n").append(tail);
        }
        return b.toString();
    }

    private static String mark(Mark m, double spot) {
        String where = m.lo() == m.hi() ? fmt(m.lo(), 0) : fmt(m.lo(), 0) + "–" + fmt(m.hi(), 0);
        double edge = m.lo() > spot ? m.lo() : m.hi() < spot ? m.hi() : spot;
        return m.name() + " " + where + (edge == spot ? "" : " (" + signed(edge - spot, 0) + ")");
    }

    private static String side(Map<String, Object> side) {
        return side == null ? "–" : signed(num(side.get("pct")), 1) + "% " + side.get("label");
    }

    private static String quote(Map<String, Object> side) {
        return side == null ? "–" : fmt(num(side.get("bid")), 2) + "/" + fmt(num(side.get("ask")), 2);
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static Double nullable(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static String signedOrDash(Object o) {
        Double v = nullable(o);
        return v == null ? "–" : signed(v, 1);
    }

    private static String signed(double v, int digits) {
        if (!Double.isFinite(v)) {
            return "–";
        }
        return (v > 0 ? "+" : v < 0 ? "−" : "") + fmt(Math.abs(v), digits);
    }

    /** Indian grouping (1,23,456.78). */
    static String fmt(double v, int digits) {
        if (!Double.isFinite(v)) {
            return "–";
        }
        String plain = String.format(Locale.ROOT, "%." + digits + "f", Math.abs(v));
        String whole = plain.contains(".") ? plain.substring(0, plain.indexOf('.')) : plain;
        String frac = plain.contains(".") ? plain.substring(plain.indexOf('.')) : "";
        StringBuilder g = new StringBuilder();
        int len = whole.length();
        for (int i = 0; i < len; i++) {
            g.append(whole.charAt(i));
            int left = len - i - 1;
            if (left > 0 && (left == 3 || (left > 3 && (left - 3) % 2 == 0))) {
                g.append(',');
            }
        }
        return (v < 0 ? "-" : "") + g + frac;
    }
}
