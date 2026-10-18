package com.autotrade.zerodha;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kite's instrument list (GET /instruments, a CSV loaded once a day before the open), mapped from our instrument keys.
 * Our signals carry the Upstox key, {@code NSE_FO|<exchange token>} or {@code BSE_FO|<exchange token>}; Kite lists the
 * same exchange token for the same contract, so the map is exact: segment NSE_FO = Kite exchange NFO, BSE_FO = BFO.
 * A contract that does not map blocks the account's entries in it (live plan section 2a, "Instrument mapping").
 */
public final class KiteInstruments {

    /** One Kite contract: what an order names (exchange + tradingsymbol) and its own instrument token. */
    public record Contract(long instrumentToken, String exchange, long exchangeToken, String tradingSymbol, String name,
                           LocalDate expiry, double strike, String type, int lotSize, double tickSize) {
    }

    private final Map<String, Contract> byExchangeToken = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Contract> bySymbol = new java.util.concurrent.ConcurrentHashMap<>();

    public KiteInstruments(List<Contract> contracts) {
        contracts.forEach(this::add);
    }

    /** Adds a contract (the simulator's listings grow as DRY_RUN replays meet new contracts). */
    public synchronized void add(Contract c) {
        byExchangeToken.put(c.exchange() + ":" + c.exchangeToken(), c);
        bySymbol.put(c.exchange() + ":" + c.tradingSymbol(), c);
    }

    /**
     * Parses Kite's CSV: instrument_token, exchange_token, tradingsymbol, name, last_price, expiry, strike, tick_size,
     * lot_size, instrument_type, segment, exchange. Only NFO and BFO options and futures are kept.
     */
    public static KiteInstruments parse(Reader csv) throws IOException {
        BufferedReader in = new BufferedReader(csv);
        String header = in.readLine();
        if (header == null) {
            return new KiteInstruments(List.of());
        }
        Map<String, Integer> col = new HashMap<>();
        String[] names = header.split(",", -1);
        for (int i = 0; i < names.length; i++) {
            col.put(names[i].trim(), i);
        }
        List<Contract> contracts = new java.util.ArrayList<>();
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            String[] f = line.split(",", -1);
            if (f.length < names.length) {
                continue;
            }
            String exchange = f[col.get("exchange")];
            String type = f[col.get("instrument_type")];
            if (!(exchange.equals("NFO") || exchange.equals("BFO")) || !(type.equals("CE") || type.equals("PE")
                    || type.equals("FUT"))) {
                continue;
            }
            String expiry = f[col.get("expiry")];
            contracts.add(new Contract(Long.parseLong(f[col.get("instrument_token")]), exchange,
                    Long.parseLong(f[col.get("exchange_token")]), f[col.get("tradingsymbol")], unquote(f[col.get("name")]),
                    expiry.isBlank() ? null : LocalDate.parse(expiry), Double.parseDouble(f[col.get("strike")]), type,
                    Integer.parseInt(f[col.get("lot_size")]), Double.parseDouble(f[col.get("tick_size")])));
        }
        return new KiteInstruments(contracts);
    }

    /** Today's list from the broker (GET /instruments: one CSV of every exchange, a few MB; once a day). */
    public static KiteInstruments load(KiteHttp http) throws IOException {
        return parse(new java.io.StringReader(http.getText("/instruments")));
    }

    /** Our key ({@code NSE_FO|47168}) to Kite's contract; empty = not mapped (entries in it are blocked). */
    public Optional<Contract> byInstrumentKey(String instrumentKey) {
        if (instrumentKey == null) {
            return Optional.empty();
        }
        int bar = instrumentKey.indexOf('|');
        if (bar < 0) {
            return Optional.empty();
        }
        String exchange = switch (instrumentKey.substring(0, bar)) {
            case "NSE_FO" -> "NFO";
            case "BSE_FO" -> "BFO";
            default -> null;
        };
        if (exchange == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byExchangeToken.get(exchange + ":" + instrumentKey.substring(bar + 1)));
    }

    public Optional<Contract> bySymbol(String exchange, String tradingSymbol) {
        return Optional.ofNullable(bySymbol.get(exchange + ":" + tradingSymbol));
    }

    /** Kite's exchange and token back to our key (for orders and positions the broker reports). */
    public static String instrumentKey(String exchange, long exchangeToken) {
        return (exchange.equals("BFO") ? "BSE_FO" : "NSE_FO") + "|" + exchangeToken;
    }

    public int size() {
        return byExchangeToken.size();
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }
}
