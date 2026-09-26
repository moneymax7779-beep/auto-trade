package com.autotrade.core.history;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Official constituent weights from {@code config/reference/weights/<index>-<as-of>.csv}
 * ({@code symbol,weight,as_of}); the newest file dated on or before the session is used.
 */
public final class IndexWeightFiles {

    private static final Map<String, String> FILE_PREFIX = Map.of(
            "NIFTY", "nifty-50-", "BANKNIFTY", "nifty-bank-", "SENSEX", "sensex-");

    private final Path directory;

    public IndexWeightFiles(Path directory) {
        this.directory = directory;
    }

    /** Weights (percent) by symbol, or empty when no file covers {@code session}. */
    public Map<String, Double> weights(String underlying, LocalDate session) {
        String prefix = FILE_PREFIX.get(underlying);
        if (prefix == null || !Files.isDirectory(directory)) {
            return Map.of();
        }
        Optional<Path> file;
        try (Stream<Path> files = Files.list(directory)) {
            file = files.filter(f -> f.getFileName().toString().startsWith(prefix) && f.toString().endsWith(".csv"))
                    .filter(f -> !asOf(f, prefix).isAfter(session))
                    .max((a, b) -> asOf(a, prefix).compareTo(asOf(b, prefix)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (file.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> weights = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(file.get());
            for (String line : lines.subList(1, lines.size())) {
                String[] cells = line.split(",");
                if (cells.length >= 2 && !cells[0].isBlank()) {
                    weights.put(cells[0].trim(), Double.parseDouble(cells[1].trim()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return weights;
    }

    private static LocalDate asOf(Path file, String prefix) {
        String name = file.getFileName().toString();
        return LocalDate.parse(name.substring(prefix.length(), name.length() - ".csv".length()));
    }
}
