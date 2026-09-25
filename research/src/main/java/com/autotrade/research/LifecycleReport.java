package com.autotrade.research;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import com.autotrade.core.time.MarketTime;
import com.autotrade.strategy.Stage;

/** Markdown report for a lifecycle run: episodes per lane (with costs), then the frame funnel. */
public final class LifecycleReport {

    private LifecycleReport() {
    }

    public static String render(String title, Map<String, String> header, List<Episode> episodes, FrameStats frames) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(title).append("\n\n");
        header.forEach((key, value) -> out.append("- **").append(key).append(":** ").append(value).append('\n'));
        out.append("\nEpisodes are traded campaigns (entry to exit, all tranches), net of dated costs. ")
                .append("R = net ÷ the loss the first tranche would take at its premium stop.\n\n");

        Map<String, List<Episode>> byLane = group(episodes, Episode::lane);
        out.append("## Episodes by fill model\n\n");
        out.append("| Lane | Episodes | Wins | Win % | Net ₹ | Avg ₹ | Avg R | Median R | Total R | Costs ₹ |\n");
        out.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        byLane.forEach((lane, list) -> out.append(row(lane, list)));
        for (Map.Entry<String, List<Episode>> lane : byLane.entrySet()) {
            out.append("\n### ").append(lane.getKey()).append(": by index, entry stage, exit reason, session\n\n");
            out.append("| Group | Episodes | Wins | Win % | Net ₹ | Avg ₹ | Avg R | Median R | Total R | Costs ₹ |\n");
            out.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            group(lane.getValue(), e -> e.underlying() + " " + e.side()).forEach((k, v) -> out.append(row(k, v)));
            group(lane.getValue(), e -> "entry " + e.entryStage()).forEach((k, v) -> out.append(row(k, v)));
            group(lane.getValue(), e -> "exit " + e.exitReason()).forEach((k, v) -> out.append(row(k, v)));
            group(lane.getValue(), e -> "session " + e.session()).forEach((k, v) -> out.append(row(k, v)));
        }
        out.append("\n### Episode list (").append(byLane.keySet().stream().findFirst().orElse("-")).append(")\n\n");
        out.append("| Session | Index | Side | Contract | Stages | Opened | Closed | Exit | Qty max | Avg cost | Net ₹ | R | MFE | MAE |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        String firstLane = byLane.keySet().stream().findFirst().orElse(null);
        episodes.stream().filter(e -> e.lane().equals(firstLane))
                .sorted(Comparator.comparing(Episode::session).thenComparing(e -> e.openedAt() == null ? "" : e.openedAt().toString()))
                .forEach(e -> out.append(String.format("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %.2f | %,.0f | %s | %s | %s |%n",
                        e.session(), e.underlying(), e.side(), e.symbol(), String.join("→", e.stages()), time(e.openedAt()),
                        time(e.closedAt()), e.exitReason(), lots(e), e.averageCost(), e.net(), num(e.r()),
                        num(e.maxFavourablePerUnit()), num(-e.maxAdversePerUnit()))));

        out.append("\n## Frame funnel (").append("minutes of continuous trading, one lane)\n\n");
        out.append("Frames are per-minute observations, not opportunities.\n\n");
        out.append("| Index side | IDLE | WATCH | ARMED | EARLY | CONFIRMED | RUNNER | EXITED | near-level frames | all-early frames |\n");
        out.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        frames.stageFrames.forEach((key, stages) -> out.append(String.format("| %s | %d | %d | %d | %d | %d | %d | %d | %d | %d |%n",
                key, count(stages, "IDLE"), count(stages, "WATCH"), count(stages, "ARMED"), count(stages, "EARLY_ENTRY"),
                count(stages, "CONFIRMED"), count(stages, "RUNNER"), count(stages, "EXITED"),
                frames.nearFrames.getOrDefault(key, 0), frames.allEarlyFrames.getOrDefault(key, 0))));

        out.append("\n### How often each condition held while price was near the level\n\n");
        List<String> keys = new ArrayList<>(frames.conditionHits.keySet());
        out.append("| Condition | ").append(String.join(" | ", keys)).append(" |\n");
        out.append("| --- |").append(" ---: |".repeat(keys.size())).append('\n');
        List<String> names = keys.isEmpty() ? List.of() : new ArrayList<>(frames.conditionHits.get(keys.getFirst()).keySet());
        for (String name : names) {
            out.append("| ").append(name).append(" |");
            for (String key : keys) {
                int near = frames.nearFrames.getOrDefault(key, 0);
                int[] hits = frames.conditionHits.get(key).get(name);
                out.append(String.format(" %s |", near == 0 || hits == null ? "-" : Math.round(100.0 * hits[0] / near) + "%"));
            }
            out.append('\n');
        }
        out.append("\n### Furthest stage reached per session\n\n| Index side session | Stage |\n| --- | --- |\n");
        frames.maxStage.forEach((key, stage) -> out.append("| ").append(key).append(" | ").append(stage).append(" |\n"));
        return out.toString();
    }

    private static String row(String label, List<Episode> list) {
        int wins = (int) list.stream().filter(Episode::win).count();
        double net = list.stream().mapToDouble(Episode::net).sum();
        double costs = list.stream().mapToDouble(Episode::costs).sum();
        List<Double> rs = list.stream().map(Episode::r).filter(Double::isFinite).sorted().toList();
        double total = rs.stream().mapToDouble(Double::doubleValue).sum();
        double median = rs.isEmpty() ? Double.NaN : rs.size() % 2 == 1 ? rs.get(rs.size() / 2)
                : (rs.get(rs.size() / 2 - 1) + rs.get(rs.size() / 2)) / 2;
        return String.format("| %s | %d | %d | %s | %,.0f | %s | %s | %s | %s | %,.0f |%n", label, list.size(), wins,
                list.isEmpty() ? "-" : Math.round(100.0 * wins / list.size()) + "%", net,
                list.isEmpty() ? "-" : String.format("%,.0f", net / list.size()),
                rs.isEmpty() ? "-" : num(total / rs.size()), num(median), rs.isEmpty() ? "-" : num(total), costs);
    }

    private static Map<String, List<Episode>> group(List<Episode> episodes, Function<Episode, String> key) {
        Map<String, List<Episode>> groups = new TreeMap<>();
        for (Episode episode : episodes) {
            groups.computeIfAbsent(key.apply(episode), k -> new ArrayList<>()).add(episode);
        }
        return groups;
    }

    private static int count(Map<Stage, Integer> stages, String name) {
        return stages.getOrDefault(Stage.valueOf(name), 0);
    }

    private static String lots(Episode e) {
        return e.maxQuantity() == 0 ? "0" : String.valueOf(e.maxQuantity());
    }

    private static String time(Instant instant) {
        return instant == null ? "-" : instant.atZone(MarketTime.IST).toLocalTime().withNano(0).toString();
    }

    private static String num(double value) {
        return Double.isFinite(value) ? String.format("%+.2f", value) : "-";
    }
}
