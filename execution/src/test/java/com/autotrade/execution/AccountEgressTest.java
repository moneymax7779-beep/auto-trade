package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.autotrade.account.AccountRole;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.broker.BrokerException;
import com.autotrade.broker.Egress;

/** Per-account egress addresses and the static-IP LIVE gate (multi-broker production gaps B1/B2, 16 Nov 2026). */
class AccountEgressTest {

    static BrokerAccount account(long id, BrokerType broker, String name) {
        return new BrokerAccount(id, 1, 1, broker, name, null, AccountRole.FOLLOWER_TRADE, TradeMode.LIVE, true, 500_000,
                EquityMode.FIXED, LocalDate.of(2026, 11, 16), "risk.yaml", 0, true);
    }

    @Test
    void theSettingIsParsedStrictly() {
        AccountEgress e = AccountEgress.parse(" 21=10.20.0.21 , 15 = 2001:db8::15 ", null);
        assertThat(e.configured(21)).contains(Egress.parse("10.20.0.21"));
        assertThat(e.configured(15)).contains(Egress.parse("2001:db8::15"));
        assertThat(e.configured(1)).isEmpty();
        assertThat(e.requiredBrokers()).containsExactlyInAnyOrder(BrokerType.KOTAK_NEO, BrokerType.UPSTOX,
                BrokerType.GROWW);
        assertThat(AccountEgress.parse("", "").requiredBrokers()).as("blank: no broker needs one").isEmpty();
        assertThat(AccountEgress.parse(null, "zerodha, KOTAK_NEO").requiredBrokers())
                .containsExactlyInAnyOrder(BrokerType.ZERODHA, BrokerType.KOTAK_NEO);
        for (String bad : new String[] {"21", "21=", "=10.0.0.1", "21=10.0.0.1=2", "x=10.0.0.1", "0=10.0.0.1",
                "-1=10.0.0.1", "21=egress.example.com", "21=10.0.0.1,", "21=10.0.0.1,,15=10.0.0.2", "21=10.0.0.1,21=10.0.0.2",
                "21=10.0.0.1,15=10.0.0.1", "21=0.0.0.0", "021=10.0.0.1"}) {
            assertThatThrownBy(() -> AccountEgress.parse(bad, null)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AccountEgress.parse("", "KOTAK")).hasMessageContaining("no broker KOTAK");
        assertThatThrownBy(() -> AccountEgress.parse("", "PAPER")).hasMessageContaining("PAPER");
    }

    @Test
    void anAddressNotOnThisHostFailsClosedAndNoEntryIsTheDefaultRoute() {
        AccountEgress e = new AccountEgress(Map.of(21L, Egress.parse("10.20.0.21"), 22L, Egress.parse("10.20.0.22")),
                AccountEgress.DEFAULT_REQUIRED, a -> a.getHostAddress().equals("10.20.0.21"));
        BrokerAccount on = account(21, BrokerType.KOTAK_NEO, "KOTAK-1");
        BrokerAccount off = account(22, BrokerType.UPSTOX, "UPSTOX-1");
        BrokerAccount none = account(23, BrokerType.ZERODHA, "ZERODHA-1");
        assertThat(e.address(on).getHostAddress()).isEqualTo("10.20.0.21");
        assertThat(e.refusal(on)).isNull();
        assertThat(e.address(none)).as("no entry: the default route, as before").isNull();
        assertThat(e.refusal(none)).isNull();
        assertThat(e.refusal(off)).contains("10.20.0.22").contains("UPSTOX-1").contains("not on this host");
        assertThatThrownBy(() -> e.address(off)).isInstanceOf(BrokerException.class)
                .satisfies(x -> assertThat(((BrokerException) x).kind()).isEqualTo(BrokerException.Kind.SESSION))
                .hasMessageContaining("never sent from the default route");
        assertThat(e.check()).containsExactlyInAnyOrder("account 21: egress 10.20.0.21 is on this host",
                "account 22: egress 10.20.0.22 is NOT on this host: its routes and logins are refused");
        assertThat(e.allAssigned()).isFalse();
    }

    @Test
    void liveNeedsAVerifiedAddressForTheRequiredBrokersOnly() {
        AccountEgress e = new AccountEgress(Map.of(21L, Egress.parse("10.20.0.21"), 22L, Egress.parse("10.20.0.22")),
                AccountEgress.DEFAULT_REQUIRED, a -> a.getHostAddress().equals("10.20.0.21"));
        assertThat(e.liveRefusal(account(21, BrokerType.KOTAK_NEO, "KOTAK-1"))).isNull();
        assertThat(e.liveRefusal(account(22, BrokerType.GROWW, "GROWW-1"))).contains("not on this host");
        assertThat(e.liveRefusal(account(30, BrokerType.UPSTOX, "UPSTOX-2"))).contains("registered static IP")
                .contains("UPSTOX-2");
        assertThat(e.liveRefusal(account(31, BrokerType.ZERODHA, "ZERODHA-1")))
                .as("Zerodha's pilot runs from the shared NAT: not in the default list").isNull();
        assertThat(AccountEgress.NONE.liveRefusal(account(31, BrokerType.ZERODHA, "ZERODHA-1"))).isNull();
        assertThat(AccountEgress.NONE.liveRefusal(account(32, BrokerType.KOTAK_NEO, "KOTAK-2"))).isNotNull();
        AccountEgress zerodhaToo = new AccountEgress(Map.of(), Set.of(BrokerType.ZERODHA), a -> true);
        assertThat(zerodhaToo.liveRefusal(account(31, BrokerType.ZERODHA, "ZERODHA-1"))).contains("static IP");
        assertThat(zerodhaToo.liveRefusal(account(32, BrokerType.KOTAK_NEO, "KOTAK-2"))).isNull();
    }

    @Test
    void theRealCheckReadsThisHostsInterfaces() throws Exception {
        AccountEgress e = AccountEgress.parse("21=127.0.0.1,22=192.0.2.77", null);
        assertThat(e.refusal(account(21, BrokerType.KOTAK_NEO, "K"))).isNull();
        assertThat(e.refusal(account(22, BrokerType.KOTAK_NEO, "K2"))).isNotNull();
        assertThat(e.address(account(21, BrokerType.KOTAK_NEO, "K"))).isEqualTo(InetAddress.getByName("127.0.0.1"));
        assertThat(e.toString()).contains("21=127.0.0.1");
    }

    /**
     * ZERODHA-1's LIVE path without an egress entry is the one of before (the same connection type and description);
     * with an address that is not on this host, or with Zerodha in the required brokers and no address, it is refused.
     */
    @Test
    void zerodhaWithoutAnEntryIsUnchangedAndWithABadOneIsRefused() {
        FollowerRisk.LiveLimits limits = new FollowerRisk.LiveLimits(1, 2, Set.of("ma-cross"), null);
        BrokerAccount zerodha1 = account(1, BrokerType.ZERODHA, "ZERODHA-1");
        String expected = "LIVE: Kite api.kite.trade, product MIS, at most 1 lot(s) a buy and 2 contracts, LIVE strategies "
                + "as chosen on the Accounts page";
        for (ZerodhaDryRunAdapter adapter : new ZerodhaDryRunAdapter[] {
                new ZerodhaDryRunAdapter(null, 1e9, BrokerSessions.NONE, com.autotrade.zerodha.KiteConfig.REAL_API),
                new ZerodhaDryRunAdapter(null, 1e9, BrokerSessions.NONE, com.autotrade.zerodha.KiteConfig.REAL_API,
                        AccountEgress.parse("21=127.0.0.1", null))}) {
            BrokerAdapter.Live live = adapter.live(zerodha1, limits);
            assertThat(live.isRefused()).isFalse();
            assertThat(live.connection()).isInstanceOf(LiveGuard.class);
            assertThat(live.description()).isEqualTo(expected);
            assertThat(adapter.egressRefusal(zerodha1)).isNull();
        }
        ZerodhaDryRunAdapter bad = new ZerodhaDryRunAdapter(null, 1e9, BrokerSessions.NONE,
                com.autotrade.zerodha.KiteConfig.REAL_API, AccountEgress.parse("1=192.0.2.77", null));
        assertThat(bad.live(zerodha1, limits).isRefused()).isTrue();
        assertThat(bad.egressRefusal(zerodha1)).contains("192.0.2.77");
        ZerodhaDryRunAdapter required = new ZerodhaDryRunAdapter(null, 1e9, BrokerSessions.NONE,
                com.autotrade.zerodha.KiteConfig.REAL_API, AccountEgress.parse("", "ZERODHA"));
        assertThat(required.live(zerodha1, limits).description()).contains("static IP");
    }
}
