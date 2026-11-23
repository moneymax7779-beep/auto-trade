package com.autotrade.trading.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.filter.ForwardedHeaderFilter;

class ClientAddressTest {

    /** As on GCP: our proxies plus the HTTPS load balancer's own address. */
    private final ClientAddress gcp = new ClientAddress(withLb());

    private static List<String> withLb() {
        List<String> t = new ArrayList<>(ClientAddress.DEFAULT_TRUSTED);
        t.add("136.82.45.194");
        return t;
    }

    @Test
    void theClientIsTheRightmostAddressThatIsNotOurs() {
        // [what the browser sent], client, load balancer, Google front end (appended by host nginx); TCP peer = nginx
        assertThat(gcp.resolve("1.2.3.4, 49.36.10.20, 136.82.45.194, 35.191.9.8", "127.0.0.1")).isEqualTo("49.36.10.20");
        assertThat(gcp.resolve("49.36.10.20, 136.82.45.194, 35.191.9.8", "127.0.0.1")).isEqualTo("49.36.10.20");
    }

    @Test
    void aMadeUpLeftEntryNeverWins() {
        assertThat(gcp.resolve("6.6.6.6, 8.8.8.8, 136.82.45.194, 130.211.0.5", "127.0.0.1")).isEqualTo("8.8.8.8");
    }

    @Test
    void withoutTheLoadBalancerTrustedItsAddressWouldBeTaken() {
        ClientAddress plain = new ClientAddress(null);
        assertThat(plain.resolve("49.36.10.20, 136.82.45.194, 35.191.9.8", "127.0.0.1")).isEqualTo("136.82.45.194");
    }

    @Test
    void aDirectClientIsTheTcpPeerAndItsHeaderIsIgnored() {
        assertThat(gcp.resolve("9.9.9.9", "49.36.10.20")).isEqualTo("49.36.10.20");
        assertThat(gcp.resolve(null, "49.36.10.20")).isEqualTo("49.36.10.20");
    }

    @Test
    void allTrustedGivesTheLeftmostAndRubbishIsSkipped() {
        assertThat(gcp.resolve("10.0.0.5, 127.0.0.1", "127.0.0.1")).isEqualTo("10.0.0.5");
        assertThat(gcp.resolve("unknown, 49.36.10.20:5555, , dead.beef, 35.191.1.1", "127.0.0.1")).isEqualTo("49.36.10.20");
        assertThat(gcp.resolve("[2405:201:abcd::1]:443, 35.191.1.1", "::1")).isEqualTo("2405:201:abcd:0:0:0:0:1");
    }

    @Test
    void namesAreNeverLookedUp() {
        assertThat(ClientAddress.literal("example.com")).isNull();
        assertThat(ClientAddress.literal("dead.beef")).isNull();
        assertThat(ClientAddress.literal("abc")).isNull();
        assertThat(ClientAddress.literal("300.1.1.1")).isNull();
        assertThat(ClientAddress.literal("49.36.10.20")).isNotNull();
    }

    @Test
    void badTrustedEntriesAreRefused() {
        assertThatThrownBy(() -> new ClientAddress(List.of("example.com"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientAddress(List.of("10.0.0.0/33"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRawHeaderIsReadUnderSpringsForwardedWrapper() throws Exception {
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/api/status");
        raw.setRemoteAddr("127.0.0.1");
        raw.addHeader("X-Forwarded-For", "49.36.10.20, 136.82.45.194, 35.191.9.8");
        jakarta.servlet.http.HttpServletRequest[] seen = new jakarta.servlet.http.HttpServletRequest[1];
        new ForwardedHeaderFilter().doFilter(raw, new org.springframework.mock.web.MockHttpServletResponse(),
                (req, res) -> seen[0] = (jakarta.servlet.http.HttpServletRequest) req);
        assertThat(seen[0].getHeader("X-Forwarded-For")).as("the wrapper hides it").isNull();
        assertThat(gcp.resolve(seen[0])).isEqualTo("49.36.10.20");
        assertThat(ClientAddress.chain(seen[0])).isEqualTo("49.36.10.20, 136.82.45.194, 35.191.9.8");
    }
}
