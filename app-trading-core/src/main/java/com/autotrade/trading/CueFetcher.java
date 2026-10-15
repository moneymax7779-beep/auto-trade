package com.autotrade.trading;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.autotrade.core.history.GiftQuote;
import com.autotrade.core.history.ParticipantOi;
import com.autotrade.core.time.MarketTime;
import com.autotrade.md.store.ReferenceStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pre-open and positioning data from public exchange pages (no login, no Upstox connection):
 * <ul>
 * <li>GIFT Nifty futures quotes from NSE IX's market-rate endpoint (the one its own website calls);</li>
 * <li>NSE's participant-wise open interest, published each evening as fao_participant_oi_DDMMYYYY.csv.</li>
 * </ul>
 * Read-only GETs with a plain browser user agent; nothing is sent but the request. Parsing is static for tests.
 */
final class CueFetcher {

    static final String GIFT_URL = "https://www.nseix.com/api/market-rate?type=derivative";
    static final String PARTICIPANT_URL = "https://archives.nseindia.com/content/nsccl/fao_participant_oi_%s.csv";
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss", Locale.ENGLISH);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ReferenceStore store;

    CueFetcher(ReferenceStore store) {
        this.store = store;
    }

    /** Fetches GIFT Nifty quotes once and stores the new ones; returns how many were new. */
    int fetchGiftNifty() throws IOException, InterruptedException {
        HttpResponse<String> response = get(GIFT_URL, "https://www.nseix.com/");
        if (response.statusCode() != 200) {
            throw new IOException("NSE IX market-rate: HTTP " + response.statusCode());
        }
        return store.insertGiftQuotes(parseGift(response.body(), Instant.now()));
    }

    /**
     * Fetches and stores one session's participant OI; empty when NSE has no file for that day (a holiday, a weekend,
     * or not published yet: HTTP 404), else the rows written.
     */
    Optional<Integer> fetchParticipantOi(LocalDate session) throws IOException, InterruptedException {
        HttpResponse<String> response = get(String.format(PARTICIPANT_URL, session.format(FILE_DATE)), null);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("NSE participant OI " + session + ": HTTP " + response.statusCode());
        }
        return Optional.of(store.upsertParticipantOi(parseParticipantOi(response.body(), session)));
    }

    private HttpResponse<String> get(String url, String referer) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT).header("Accept", "application/json,text/csv,*/*");
        if (referer != null) {
            request.header("Referer", referer);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** NSE IX's derivative market rates: the NIFTY index futures (GIFT Nifty) rows, any expiry. */
    static List<GiftQuote> parseGift(String json, Instant fetchedAt) {
        List<GiftQuote> out = new ArrayList<>();
        JsonNode data = JSON.readTree(json).path("data");
        for (JsonNode row : data) {
            if (!"FUTIDX".equals(row.path("INSTRUMENTTYPE").asString()) || !"NIFTY".equals(row.path("SYMBOL").asString())) {
                continue;
            }
            LocalDate expiry = LocalDate.parse(row.path("EXPIRYDATE").asString(), EXPIRY);
            Instant at = LocalDateTime.parse(row.path("TIMESTMP").asString(), STAMP).atZone(MarketTime.IST).toInstant();
            double last = number(row.path("LASTPRICE").asString());
            if (!(last > 0)) {
                continue;
            }
            out.add(new GiftQuote(at, fetchedAt, expiry, last, number(row.path("DAYCHANGE").asString()),
                    number(row.path("PERCHANGE").asString()), row.path("CONTRACTSTRADED").asLong()));
        }
        return out;
    }

    /**
     * NSE's participant OI file: a title line, a header (column names may carry trailing spaces), one row per
     * participant type. Columns are found by name, so a reordered file still parses; a missing column fails.
     */
    static List<ReferenceStore.ParticipantRow> parseParticipantOi(String csv, LocalDate session) {
        String[] lines = csv.replace("\r", "").split("\n");
        int header = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("Client Type")) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            throw new IllegalArgumentException("participant OI " + session + ": no header line");
        }
        Map<String, Integer> col = new HashMap<>();
        String[] names = lines[header].split(",");
        for (int i = 0; i < names.length; i++) {
            col.put(names[i].trim().toLowerCase(Locale.ROOT), i);
        }
        List<ReferenceStore.ParticipantRow> out = new ArrayList<>();
        for (int i = header + 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",");
            if (cells.length < names.length || cells[0].isBlank()) {
                continue;
            }
            ParticipantOi oi = new ParticipantOi(session, cells[0].trim(), cell(cells, col, "future index long"),
                    cell(cells, col, "future index short"), cell(cells, col, "future stock long"),
                    cell(cells, col, "future stock short"), cell(cells, col, "option index call long"),
                    cell(cells, col, "option index put long"), cell(cells, col, "option index call short"),
                    cell(cells, col, "option index put short"), cell(cells, col, "total long contracts"),
                    cell(cells, col, "total short contracts"));
            out.add(new ReferenceStore.ParticipantRow(oi, cell(cells, col, "option stock call long"),
                    cell(cells, col, "option stock put long"), cell(cells, col, "option stock call short"),
                    cell(cells, col, "option stock put short")));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("participant OI " + session + ": no rows");
        }
        return out;
    }

    private static long cell(String[] cells, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null) {
            throw new IllegalArgumentException("participant OI: no column '" + name + "'");
        }
        return Long.parseLong(cells[i].trim());
    }

    private static double number(String s) {
        try {
            return s == null || s.isBlank() || "-".equals(s.trim()) ? Double.NaN : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
