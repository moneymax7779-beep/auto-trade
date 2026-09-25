package com.autotrade.md.zt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.OptionTick;

/** Payloads copied from zt-tiger-v2 rows of 2 Sep 2026. */
class ZtTickParserTest {

    private static final LocalDateTime RECEIVED_IST = LocalDateTime.parse("2026-09-02T09:17:05.497885");

    private final ZtTickParser parser = new ZtTickParser("NIFTY");

    @Test
    void parsesIndexTickAndConvertsIstReceiptTime() {
        IndexTick tick = (IndexTick) parser.parse(row("CASH", null, "NIFTY",
                "{\"price\":23841.0,\"atm\":23850,\"volume\":0,\"close\":24055.8}"));

        assertThat(tick.price()).isEqualTo(23841.0);
        assertThat(tick.previousClose()).isEqualTo(24055.8);
        assertThat(tick.atmStrike()).isEqualTo(23850);
        assertThat(tick.receivedAt()).isEqualTo(Instant.parse("2026-09-02T03:47:05.497885Z"));
    }

    @Test
    void parsesFutureTick() {
        FutureTick tick = (FutureTick) parser.parse(row("FUTURE", 3000068407L, "NIFTY FUT 29 SEP 26",
                "{\"price\":23974.7,\"volume\":1364545,\"oi\":1.6512535E7,\"sessionAveragePrice\":23969.94}"));

        assertThat(tick.price()).isEqualTo(23974.7);
        assertThat(tick.cumulativeVolume()).isEqualTo(1364545L);
        assertThat(tick.openInterest()).isEqualTo(16512535.0);
        assertThat(tick.sessionVwap()).isEqualTo(23969.94);
    }

    @Test
    void parsesOptionTickWithDepthAndGreeks() {
        String payload = """
                {"instrumentToken":3000042630,"tradingSymbol":"NIFTY 23750 PE 08 SEP 26","strikePrice":23750.0,\
                "instrumentType":"PE","ltp":88.8,"volume":2093000,"oi":1423890.0,"totalBuyQty":1910025.0,\
                "totalSellQty":109915.0,"bidDepth":[{"quantity":325,"price":88.7,"orders":0},\
                {"quantity":1235,"price":88.65,"orders":0}],"askDepth":[{"quantity":1820,"price":88.9,"orders":0}],\
                "timestamp":1788320825574,"receivedTimestamp":1788320825495,"quoteSource":"UPSTOX_WEBSOCKET_FULL_V3",\
                "optionDelta":-0.3759,"optionGamma":0.0011,"optionTheta":-10.2518,"optionVega":11.8539,\
                "optionRho":-1.5528,"impliedVolatility":0.1082611083984375,\
                "derivativeAnalyticsSource":"UPSTOX_WEBSOCKET_FULL_V3","derivativeAnalyticsTimestamp":1788320825574,\
                "derivativeAnalyticsUnderlyingPrice":null,"derivativeAnalyticsUnderlyingTimestamp":0}""";
        SourceTickRow row = new SourceTickRow(18666L, "OPTION", 3000042630L, "NIFTY 23750 PE 08 SEP 26",
                1788320825574L, RECEIVED_IST, LocalDate.of(2026, 9, 8), 65, "NFO", "PE", "UPSTOX_WEBSOCKET_FULL_V3",
                true, true, sha256(payload), payload);

        OptionTick tick = (OptionTick) parser.parse(row);

        assertThat(tick.optionType()).isEqualTo(OptionTick.OptionType.PE);
        assertThat(tick.strike()).isEqualTo(23750.0);
        assertThat(tick.lotSize()).isEqualTo(65);
        assertThat(tick.bids().size()).isEqualTo(2);
        assertThat(tick.bids().price(0)).isEqualTo(88.7);
        assertThat(tick.asks().quantity(0)).isEqualTo(1820L);
        assertThat(tick.spread()).isEqualTo(88.9 - 88.7);
        assertThat(tick.delta()).isEqualTo(-0.3759);
        assertThat(tick.analyticsTime()).isEqualTo(Instant.ofEpochMilli(1788320825574L));
        assertThat(tick.exchangeTime()).isEqualTo(Instant.ofEpochMilli(1788320825574L));
        assertThat(parser.unknownKeys()).isEmpty();
    }

    @Test
    void parsesConstituentAndTracksWeights() {
        parser.parse(row("COMPONENT", 1000011532L, "ULTRACEMCO",
                "{\"weight\":1.26,\"price\":11281.0,\"close\":11400.0,\"cumulativeVolume\":2586,"
                        + "\"sessionAveragePrice\":11312.47}"));
        ConstituentTick tick = (ConstituentTick) parser.parse(row("COMPONENT", 1000011532L, "ULTRACEMCO",
                "{\"weight\":1.30,\"price\":11282.0,\"close\":11400.0,\"cumulativeVolume\":2600,"
                        + "\"sessionAveragePrice\":11312.5,\"weightSource\":\"OFFICIAL\"}"));

        assertThat(tick.weightPercent()).isEqualTo(1.30);
        assertThat(parser.weights()).containsEntry("ULTRACEMCO", 1.30);
        assertThat(parser.weightSources()).containsEntry("ULTRACEMCO", "OFFICIAL");
        assertThat(parser.weightChanges()).containsEntry("ULTRACEMCO", 1);
    }

    @Test
    void rejectsPayloadWhoseHashDoesNotMatch() {
        SourceTickRow row = new SourceTickRow(1L, "CASH", null, "NIFTY", null, RECEIVED_IST, null, null, null, null,
                null, null, null, "00".repeat(32), "{\"price\":1.0}");

        assertThatThrownBy(() -> parser.verifiedHash(row)).hasMessageContaining("payload_hash");
    }

    @Test
    void verifiesRealSourceHash() {
        SourceTickRow row = row("CASH", null, "NIFTY", "{\"price\":23841.0,\"atm\":23850,\"volume\":0,\"close\":24055.8}");

        assertThat(HexFormat.of().formatHex(parser.verifiedHash(row)))
                .isEqualTo("f7b960a71986a20b51f231cc840d617a366068ec7aee72edbb32e83200d4915e");
    }

    @Test
    void rejectsMissingRequiredFieldsAndRecordsUnknownKeys() {
        assertThatThrownBy(() -> parser.parse(row("CASH", null, "NIFTY", "{\"close\":1.0}")))
                .hasMessageContaining("missing numeric field price");

        parser.parse(row("CASH", null, "NIFTY", "{\"price\":1.0,\"iep\":2.0}"));
        assertThat(parser.unknownKeys()).containsEntry("CASH.iep", 1L);
    }

    private static SourceTickRow row(String type, Long token, String symbol, String payload) {
        return new SourceTickRow(1L, type, token, symbol, null, RECEIVED_IST, LocalDate.of(2026, 9, 29), null,
                "NFO", null, "UPSTOX_WEBSOCKET", null, null, sha256(payload), payload);
    }

    private static String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
