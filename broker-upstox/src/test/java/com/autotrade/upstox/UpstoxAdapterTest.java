package com.autotrade.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.instruments.Instrument;
import com.autotrade.instruments.InstrumentMaster;
import com.autotrade.instruments.UpstoxInstrumentFile;
import com.upstox.feeder.MarketUpdateV3;

class UpstoxAdapterTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 9, 28);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 9, 29);
    private static final Instant NOW = Instant.parse("2026-09-28T05:00:00Z");

    @TempDir
    Path dir;

    private final UpstoxUniverse universe = new UpstoxUniverse(master(), SESSION, List.of("NIFTY"), Map.of("NIFTY",
            List.of(new UpstoxUniverse.Constituent(new UpstoxInstrumentFile.Listing("NSE_EQ|INE040A01034", "1333",
                    "NSE_EQ", "HDFCBANK", 0.05), 12.9))));

    @Test
    void tokenExpiresAtHalfPastThreeTheNextMorning() {
        UpstoxToken morning = UpstoxToken.issued("t", "U1", Instant.parse("2026-09-28T02:30:00Z")); // 08:00 IST
        assertThat(morning.expiresAt()).isEqualTo(Instant.parse("2026-09-28T22:00:00Z")); // 29 Sep 03:30 IST
        UpstoxToken night = UpstoxToken.issued("t", "U1", Instant.parse("2026-09-28T21:00:00Z")); // 02:30 IST 29 Sep
        assertThat(night.expiresAt()).isEqualTo(Instant.parse("2026-09-28T22:00:00Z"));
        assertThat(morning.toString()).doesNotContain("t,");
    }

    @Test
    void tokenStoreIsOwnerOnlyAndDropsExpiredTokens() throws Exception {
        UpstoxTokenStore store = new UpstoxTokenStore(dir.resolve("upstox").resolve("token.json"));
        store.save(UpstoxToken.issued("secret-token", "U1", NOW));

        assertThat(Files.getPosixFilePermissions(store.file()))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(store.valid(NOW.plusSeconds(60))).get().extracting(UpstoxToken::accessToken).isEqualTo("secret-token");
        assertThat(store.valid(NOW.plusSeconds(86_400))).isEmpty();
    }

    @Test
    void loginUrlCarriesTheKeyAndStateButNoSecret() {
        UpstoxLogin login = new UpstoxLogin(new UpstoxCredentials("KEY123", "SECRET456",
                "http://127.0.0.1:5055/upstox/callback"), null, "abc");

        assertThat(login.authorizationUrl()).startsWith(UpstoxLogin.AUTHORIZE).contains("client_id=KEY123")
                .contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A5055%2Fupstox%2Fcallback").contains("state=abc")
                .doesNotContain("SECRET456");
        assertThat(UpstoxLogin.query("code=x%2By&state=abc")).containsEntry("code", "x+y").containsEntry("state", "abc");
        assertThatThrownBy(() -> new UpstoxCredentials("", "s", "http://127.0.0.1:5055/cb"))
                .hasMessageContaining("UPSTOX_API_KEY");
        assertThatThrownBy(() -> new UpstoxCredentials("k", "s", "https://example.com/cb"))
                .hasMessageContaining("127.0.0.1");
    }

    @Test
    void universeNumbersTokensPerSegmentAndBuildsTheOptionBand() {
        assertThat(universe.baseKeys()).contains("NSE_INDEX|Nifty 50", "NSE_FO|9", "NSE_EQ|INE040A01034",
                UpstoxUniverse.VIX_KEY);
        assertThat(universe.meta("NSE_INDEX|Nifty 50").token()).isEqualTo(7_000_026_000L);
        assertThat(universe.meta("NSE_EQ|INE040A01034").token()).isEqualTo(1_000_001_333L);

        assertThat(universe.optionKeys("NIFTY", 23_112, 1)).containsExactlyInAnyOrder("NSE_FO|1", "NSE_FO|2",
                "NSE_FO|3", "NSE_FO|4", "NSE_FO|5", "NSE_FO|6");
        assertThat(universe.meta("NSE_FO|3").token()).isEqualTo(3_000_000_003L);
    }

    @Test
    void mapsIndexOptionAndAuctionMessages() {
        universe.optionKeys("NIFTY", 23_112, 1);
        UpstoxEventMapper mapper = new UpstoxEventMapper(universe);

        MarketUpdateV3 update = new MarketUpdateV3();
        update.setFeeds(Map.of(
                "NSE_INDEX|Nifty 50", indexFeed(23_112.5, 23_063.1),
                "NSE_FO|3", optionFeed(),
                "NSE_EQ|INE040A01034", equityFeed()));
        List<MarketEvent> events = new ArrayList<>(mapper.map(update, NOW));

        IndexTick index = (IndexTick) events.stream().filter(IndexTick.class::isInstance).findFirst().orElseThrow();
        assertThat(index.price()).isEqualTo(23_112.5);
        assertThat(index.previousClose()).isEqualTo(23_063.1);
        assertThat(mapper.lastIndex("NIFTY")).isEqualTo(23_112.5);

        OptionTick option = (OptionTick) events.stream().filter(OptionTick.class::isInstance).findFirst().orElseThrow();
        assertThat(option.strike()).isEqualTo(23_100);
        assertThat(option.optionType()).isEqualTo(OptionTick.OptionType.CE);
        assertThat(option.lotSize()).isEqualTo(65);
        assertThat(option.bids().size()).isEqualTo(5);
        assertThat(option.bids().price(0)).isEqualTo(142.0);
        assertThat(option.asks().price(0)).isEqualTo(142.3);
        assertThat(option.depthComplete()).isTrue();
        assertThat(option.analyticsComplete()).isTrue();
        assertThat(option.delta()).isEqualTo(0.52);
        assertThat(option.receivedAt()).isEqualTo(NOW);

        assertThat(events).filteredOn(ConstituentTick.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((ConstituentTick) e).weightPercent()).isEqualTo(12.9));
        AuctionTick auction = (AuctionTick) events.stream().filter(AuctionTick.class::isInstance).findFirst()
                .orElseThrow();
        assertThat(auction.indicativePrice()).isEqualTo(955.5);
        assertThat(auction.imbalanceTotal()).isEqualTo(-12_000);
        assertThat(auction.casEligible()).isTrue();
    }

    private static MarketUpdateV3.Feed indexFeed(double ltp, double close) {
        MarketUpdateV3.LTPC ltpc = new MarketUpdateV3.LTPC();
        ltpc.setLtp(ltp);
        ltpc.setCp(close);
        ltpc.setLtt(NOW.toEpochMilli() - 100);
        MarketUpdateV3.IndexFullFeed index = new MarketUpdateV3.IndexFullFeed();
        index.setLtpc(ltpc);
        MarketUpdateV3.FullFeed full = new MarketUpdateV3.FullFeed();
        full.setIndexFF(index);
        MarketUpdateV3.Feed feed = new MarketUpdateV3.Feed();
        feed.setFullFeed(full);
        return feed;
    }

    private static MarketUpdateV3.Feed optionFeed() {
        MarketUpdateV3.MarketFullFeed market = new MarketUpdateV3.MarketFullFeed();
        MarketUpdateV3.LTPC ltpc = new MarketUpdateV3.LTPC();
        ltpc.setLtp(142.1);
        market.setLtpc(ltpc);
        List<MarketUpdateV3.Quote> quotes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MarketUpdateV3.Quote quote = new MarketUpdateV3.Quote();
            quote.setBidP(142.0 - i * 0.05);
            quote.setBidQ(650);
            quote.setAskP(142.3 + i * 0.05);
            quote.setAskQ(325);
            quotes.add(quote);
        }
        MarketUpdateV3.MarketLevel level = new MarketUpdateV3.MarketLevel();
        level.setBidAskQuote(quotes);
        market.setMarketLevel(level);
        MarketUpdateV3.OptionGreeks greeks = new MarketUpdateV3.OptionGreeks();
        greeks.setDelta(0.52);
        greeks.setGamma(0.0011);
        greeks.setTheta(-12.0);
        greeks.setVega(9.0);
        market.setOptionGreeks(greeks);
        market.setIv(0.104);
        market.setOi(1_500_000);
        market.setVtt(2_000_000);
        MarketUpdateV3.FullFeed full = new MarketUpdateV3.FullFeed();
        full.setMarketFF(market);
        MarketUpdateV3.Feed feed = new MarketUpdateV3.Feed();
        feed.setFullFeed(full);
        return feed;
    }

    private static MarketUpdateV3.Feed equityFeed() {
        MarketUpdateV3.MarketFullFeed market = new MarketUpdateV3.MarketFullFeed();
        MarketUpdateV3.LTPC ltpc = new MarketUpdateV3.LTPC();
        ltpc.setLtp(955.0);
        ltpc.setCp(950.0);
        market.setLtpc(ltpc);
        market.setIep(955.5);
        market.setRp(954.0);
        market.setIeq(250_000);
        market.setIiqTotal(-12_000);
        market.setIiqM(-3_000);
        market.setCasEligible(true);
        MarketUpdateV3.FullFeed full = new MarketUpdateV3.FullFeed();
        full.setMarketFF(market);
        MarketUpdateV3.Feed feed = new MarketUpdateV3.Feed();
        feed.setFullFeed(full);
        return feed;
    }

    private static InstrumentMaster master() {
        List<Instrument> instruments = new ArrayList<>();
        int token = 1;
        for (double strike : new double[] {23_050, 23_100, 23_150}) {
            for (String type : new String[] {"CE", "PE"}) {
                instruments.add(new Instrument("NSE_FO|" + token, String.valueOf(token), "NSE_FO", "NSE", type, "NIFTY",
                        "NSE_INDEX|Nifty 50", "NIFTY " + (int) strike + " " + type, EXPIRY, strike, 65, 0.05, 1755,
                        false));
                token++;
            }
        }
        instruments.add(new Instrument("NSE_FO|9", "9", "NSE_FO", "NSE", "FUT", "NIFTY", "NSE_INDEX|Nifty 50",
                "NIFTY FUT", EXPIRY, 0, 65, 0.10, 1755, false));
        return new InstrumentMaster(instruments);
    }
}
