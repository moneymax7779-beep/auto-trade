package com.autotrade.upstox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.instruments.InstrumentMaster;
import com.autotrade.instruments.UpstoxInstrumentFile;

/** Builds the Upstox feed for a session: index, VIX, futures, weighted constituents and an option band. */
public final class UpstoxFeeds {

    private static final Logger log = LoggerFactory.getLogger(UpstoxFeeds.class);

    private UpstoxFeeds() {
    }

    /**
     * @param weights index weights (percent) per constituent symbol for an underlying
     */
    public static UpstoxFeed build(UpstoxToken token, InstrumentMaster instruments, LocalDate session,
                                   List<String> underlyings, Path instrumentDir,
                                   Function<String, Map<String, Double>> weights, int strikesEachSide,
                                   int recenterStrikes) throws IOException {
        if (instruments.size() == 0) {
            throw new IllegalStateException("the Upstox feed needs a contract master: bin/autotrade instruments --file ...");
        }
        Map<String, List<UpstoxUniverse.Constituent>> constituents = new LinkedHashMap<>();
        for (String underlying : underlyings) {
            String segment = underlying.equals("SENSEX") ? "BSE_EQ" : "NSE_EQ";
            Path file = newest(instrumentDir, underlying.equals("SENSEX") ? "BSE-" : "NSE-");
            Map<String, Double> w = weights.apply(underlying);
            List<UpstoxUniverse.Constituent> list = new ArrayList<>();
            for (UpstoxInstrumentFile.Listing listing : UpstoxInstrumentFile.readEquities(file, segment, w.keySet())) {
                // NIFTY weights are keyed by trading symbol, SENSEX weights by BSE scrip code (exchange token)
                Double weight = w.containsKey(listing.tradingSymbol()) ? w.get(listing.tradingSymbol())
                        : w.get(listing.exchangeToken());
                list.add(new UpstoxUniverse.Constituent(listing, weight));
            }
            log.info("{}: {} of {} constituents found in {}", underlying, list.size(), w.size(), file.getFileName());
            constituents.put(underlying, list);
        }
        UpstoxUniverse universe = new UpstoxUniverse(instruments, session, underlyings, constituents);
        return new UpstoxFeed(token, universe, underlyings, strikesEachSide, recenterStrikes);
    }

    private static Path newest(Path directory, String prefix) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(f -> f.getFileName().toString().startsWith(prefix)
                            && f.getFileName().toString().endsWith(".json.gz"))
                    .max(Comparator.naturalOrder())
                    .orElseThrow(() -> new IllegalStateException("no " + prefix + "*.json.gz in " + directory));
        }
    }
}
