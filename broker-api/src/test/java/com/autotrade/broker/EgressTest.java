package com.autotrade.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** A broker account's egress address (multi-broker production gaps B1, 16 Nov 2026). */
class EgressTest {

    private HttpServer server;
    private final AtomicReference<InetAddress> seen = new AtomicReference<>();

    @BeforeEach
    void server() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", e -> {
            seen.set(e.getRemoteAddress().getAddress());
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @Test
    void onlyIpLiteralsAreAccepted() {
        assertThat(Egress.parse(" 10.20.0.21 ").getHostAddress()).isEqualTo("10.20.0.21");
        assertThat(Egress.parse("2001:db8::21").getHostAddress()).isEqualTo("2001:db8:0:0:0:0:0:21");
        assertThat(Egress.parse("::1").isLoopbackAddress()).isTrue();
        for (String bad : new String[] {"", "  ", "localhost", "api.kite.trade", "10.20.0", "10.20.0.256", "010.20.0.21",
                "10.20.0.21:80", "0.0.0.0", "::", "224.0.0.1", "255.255.255.255", "fe80::1%en0", "[::1]", "2001:db8::zz",
                "1.2.3.4 5"}) {
            assertThatThrownBy(() -> Egress.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Egress.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAddressIsAssignedOnlyWhenAnInterfaceOfThisHostHasIt() {
        assertThat(Egress.assigned(Egress.parse("127.0.0.1"))).isTrue();
        assertThat(Egress.assigned(Egress.parse("192.0.2.77"))).as("TEST-NET-1: never on this host").isFalse();
        assertThat(Egress.assigned(null)).isFalse();
        assertThat(Egress.describe(null)).isEqualTo("default route");
        assertThat(Egress.describe(Egress.parse("10.20.0.21"))).isEqualTo("10.20.0.21");
    }

    @Test
    void withoutAnEgressTheClientIsTheDefaultOneAndWithOneItLeavesFromIt() throws Exception {
        HttpClient plain = Egress.client(Duration.ofSeconds(2), null);
        assertThat(plain.connectTimeout()).contains(Duration.ofSeconds(2));
        assertThat(send(plain)).isEqualTo(200);
        HttpClient bound = Egress.client(Duration.ofSeconds(2), Egress.parse("127.0.0.1"));
        assertThat(send(bound)).isEqualTo(200);
        assertThat(seen.get().getHostAddress()).isEqualTo("127.0.0.1");
    }

    /**
     * The bind is real: a client bound to the IPv6 loopback cannot reach an IPv4-only server (the default client can), so
     * the address is used and nothing falls back to the default route.
     */
    @Test
    void aBoundClientNeverFallsBackToTheDefaultRoute() throws Exception {
        InetAddress six = Egress.parse("::1");
        assumeTrue(Egress.assigned(six), "this host has no IPv6 loopback");
        HttpClient bound = Egress.client(Duration.ofSeconds(2), six);
        seen.set(null);
        assertThatThrownBy(() -> send(bound)).isInstanceOf(IOException.class);
        assertThat(seen.get()).as("the request never reached the server").isNull();
        assertThat(send(Egress.client(Duration.ofSeconds(2), null))).isEqualTo(200);
    }

    @Test
    void aClientBoundToAnAddressThisHostDoesNotHaveCannotConnect() {
        HttpClient bound = Egress.client(Duration.ofSeconds(2), Egress.parse("192.0.2.77"));
        assertThatThrownBy(() -> send(bound)).isInstanceOf(IOException.class);
        assertThat(seen.get()).isNull();
    }

    private int send(HttpClient client) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri()).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
