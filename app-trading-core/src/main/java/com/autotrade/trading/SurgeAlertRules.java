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

    /**
     * The trade a surge suggests: buy the ATM call (bullish) or put (bearish); the stop and the target are the nearest
     * known levels (lines, tested zones, VWAP) at least {@link #MIN_GAP} of the index away on either side, else the
     * day's extreme. Distances are from the index at the surge minute.
     */
    record Plan(boolean call, double spot, double strike, String expiry, boolean expiresToday, double premium, double bid,
                double stop, String stopName, double target, String targetName) {

        double risk() {
            return Math.abs(stop - spot);
        }

        double reward() {
            return Math.abs(target - spot);
        }
    }

    /** A stop or target closer than this share of the index is noise, not a level (about 11 NIFTY / 36 SENSEX points). */
    static final double MIN_GAP = 0.0005;
    /** Contract size per lot (NSE NIFTY 65, BSE SENSEX 20 since Sep 2026); unknown underlyings show no lot cost. */
    static final Map<String, Integer> LOT = Map.of("NIFTY", 65, "SENSEX", 20, "BANKNIFTY", 30);

    @SuppressWarnings("unchecked")
    static Plan plan(Map<String, Object> s, Levels levels, String expiry, java.time.LocalDate today) {
        boolean call = "bullish".equals(s.get("flow"));
        double spot = num(s.get("spot"));
        Map<String, Object> side = (Map<String, Object>) s.get(call ? "CE" : "PE");
        List<Mark> marks = new java.util.ArrayList<>(levels == null || levels.marks() == null ? List.of() : levels.marks());
        if (levels != null && levels.vwap() != null) {
            marks.add(new Mark("VWAP", levels.vwap(), levels.vwap()));
        }
        double gap = MIN_GAP * spot;
        // a stop sits just beyond the nearest level on the wrong side (a zone's far edge: the market must break through
        // it); a target is the near edge of the nearest level ahead (where the move first meets it). Puts mirrored.
        Mark stopMark = call
                ? marks.stream().filter(m -> m.hi() < spot && spot - m.lo() >= gap).max(java.util.Comparator.comparingDouble(Mark::lo)).orElse(null)
                : marks.stream().filter(m -> m.lo() > spot && m.hi() - spot >= gap).min(java.util.Comparator.comparingDouble(Mark::hi)).orElse(null);
        Mark targetMark = call
                ? marks.stream().filter(m -> m.lo() - spot >= gap).min(java.util.Comparator.comparingDouble(Mark::lo)).orElse(null)
                : marks.stream().filter(m -> spot - m.hi() >= gap).max(java.util.Comparator.comparingDouble(Mark::hi)).orElse(null);
        Double dayLow = levels == null ? null : levels.dayLow(), dayHigh = levels == null ? null : levels.dayHigh();
        Double wrongExtreme = call ? dayLow : dayHigh, rightExtreme = call ? dayHigh : dayLow;
        double stop = stopMark != null ? (call ? stopMark.lo() : stopMark.hi())
                : wrongExtreme != null && Math.abs(spot - wrongExtreme) >= gap ? wrongExtreme : Double.NaN;
        String stopName = stopMark != null ? stopMark.name() : Double.isFinite(stop) ? (call ? "day low" : "day high") : null;
        double target = targetMark != null ? (call ? targetMark.lo() : targetMark.hi())
                : rightExtreme != null && Math.abs(rightExtreme - spot) >= gap ? rightExtreme : Double.NaN;
        String targetName = targetMark != null ? targetMark.name() : Double.isFinite(target) ? (call ? "day high" : "day low") : null;
        return new Plan(call, spot, num(s.get("atm")), expiry, expiry != null && expiry.equals(today.toString()),
                side == null ? Double.NaN : num(side.get("ask")), side == null ? Double.NaN : num(side.get("bid")),
                stop, stopName, target, targetName);
    }

    @SuppressWarnings("unchecked")
    static String message(String underlying, Map<String, Object> s, Levels levels, String hitRate, Plan p) {
        Map<String, Object> fut = (Map<String, Object>) s.get("futures");
        Map<String, Object> ce = (Map<String, Object>) s.get("CE");
        Map<String, Object> pe = (Map<String, Object>) s.get("PE");
        String kind = p.call() ? "CALL" : "PUT";
        StringBuilder m = new StringBuilder();
        m.append(p.call() ? "🟢 BUY " : "🔴 BUY ").append(kind).append(" · ").append(underlying).append(' ')
                .append(fmt(p.strike(), 0)).append(p.call() ? " CE" : " PE");
        if (p.expiry() != null) {
            m.append(" · exp ").append(shortDate(p.expiry()));
        }
        m.append('\n');
        if (Double.isFinite(p.premium())) {
            m.append("Pay ≈ ₹").append(fmt(p.premium(), 2)).append(" (ask; bid ").append(fmt(p.bid(), 2)).append(")");
            Integer lot = LOT.get(underlying);
            if (lot != null) {
                m.append(" · 1 lot (").append(lot).append(") ≈ ₹").append(fmt(p.premium() * lot, 0));
            }
            m.append('\n');
        }
        m.append("Index ").append(fmt(p.spot(), 2)).append(" at ").append(s.get("t")).append(" · flow known ")
                .append(s.getOrDefault("from", "t+1")).append('\n');
        String against = p.call() ? "falls below " : "rises above ";
        if (Double.isFinite(p.stop())) {
            m.append("Stop: exit if index ").append(against).append(fmt(p.stop(), 0)).append(" (").append(p.stopName())
                    .append(") · ").append(signed(p.stop() - p.spot(), 0)).append(" pts\n");
        } else {
            m.append("Stop: no known level on the wrong side; set one before entering\n");
        }
        if (Double.isFinite(p.target())) {
            m.append("Target: ").append(fmt(p.target(), 0)).append(" (").append(p.targetName()).append(") · ")
                    .append(signed(p.target() - p.spot(), 0)).append(" pts");
            if (Double.isFinite(p.stop()) && p.risk() > 0) {
                m.append(" · reward:risk ").append(fmt(p.reward() / p.risk(), 1));
            }
            m.append('\n');
        } else {
            m.append("Target: no known level ahead; trail the index\n");
        }
        if (Double.isFinite(p.stop()) && Double.isFinite(p.target()) && p.reward() < p.risk()) {
            m.append("⚠ Reward smaller than risk: skip, or wait for a better price nearer the stop\n");
        }
        if (p.expiresToday()) {
            m.append("⚠ Expires today: the premium decays fast; take profit quickly or skip\n");
        }
        m.append("Why: futures ").append(fmt(num(s.get("x")), 1)).append("× normal volume");
        if (fut != null) {
            m.append(" · fut OI ").append(signed(num(fut.get("pct")), 2)).append("% ").append(fut.get("label"));
        }
        m.append(" · calls ").append(side(ce)).append(" · puts ").append(side(pe)).append('\n');
        String more = distances(p.spot(), levels);
        if (!more.isEmpty()) {
            m.append("Levels:").append(more.replace("\n", " | ").replaceFirst("^ \\| ", " ")).append('\n');
        }
        m.append("Reliability: surge flow right ~50% at 15 min (18 sessions, no edge alone)");
        if (hitRate != null) {
            m.append(" · ").append(hitRate.replace("Today so far: ", "today ").replace(" flow right ", " "));
        }
        m.append(" · PAPER, your call. Follow-up in 15 min.");
        return m.toString();
    }

    /** The surge's outcome 15 minutes on, read against its plan. */
    static String followUp(String underlying, Map<String, Object> s, Plan p) {
        String head = "↳ " + (p == null ? s.get("flow") + " surge" : "BUY " + (p.call() ? "CALL " : "PUT ") + underlying + " "
                + fmt(p.strike(), 0) + (p.call() ? " CE" : " PE")) + " (" + s.get("t") + "), index from " + s.getOrDefault("from", "t+1")
                + ": +5m " + signedOrDash(s.get("move5")) + " · +15m " + signedOrDash(s.get("move15"))
                + " · 15m best " + signedOrDash(s.get("best15")) + " / worst " + signedOrDash(s.get("worst15"));
        Double best = nullable(s.get("best15")), worst = nullable(s.get("worst15")), m15 = nullable(s.get("move15"));
        if (p == null || best == null || worst == null) {
            boolean bull = "bullish".equals(s.get("flow"));
            return head + (m15 == null ? "" : (m15 > 0) == bull ? " → WITH the flow" : m15 == 0 ? " → flat" : " → AGAINST the flow");
        }
        double favourable = p.call() ? best : -worst, adverse = p.call() ? -worst : best;
        boolean target = Double.isFinite(p.target()) && favourable >= p.reward();
        boolean stop = Double.isFinite(p.stop()) && adverse >= p.risk();
        String verdict = target && stop ? " → target and stop both touched (order unknown)" : target ? " → ✅ target reached"
                : stop ? " → ❌ stop hit" : m15 == null ? "" : " → neither yet (" + ((m15 > 0) == p.call() ? "in profit" : "under water") + ")";
        return head + verdict;
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

    /** "2026-10-13" -> "13 Oct". */
    static String shortDate(String iso) {
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(iso);
            return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
        } catch (RuntimeException e) {
            return iso;
        }
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
