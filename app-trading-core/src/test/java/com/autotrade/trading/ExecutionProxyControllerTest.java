package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Who the execution worker hears: the signed-in user (oidc) or the configured user (none), never the caller's header. */
class ExecutionProxyControllerTest {

    private HttpServer worker;
    private final AtomicReference<String> heard = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        worker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        worker.createContext("/", exchange -> {
            heard.set(exchange.getRequestHeaders().getFirst("X-Autotrade-User"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        worker.start();
    }

    @AfterEach
    void stop() {
        worker.stop(0);
    }

    private ExecutionProxyController proxy(String mode) {
        return new ExecutionProxyController("http://127.0.0.1:" + worker.getAddress().getPort(), "local-owner",
                new AuthProperties(mode, "http://kc/auth", null, null, null, "s", null, null));
    }

    private static MockHttpServletRequest call(String claimedUser) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/execution/accounts");
        request.addHeader("X-Autotrade-User", claimedUser);
        return request;
    }

    @Test
    void signedInTheWorkerHearsTheSignedInUserNotTheCallersHeader() {
        Principal satya = () -> "satyacp99";
        assertThat(proxy("oidc").forward(call("local-owner"), null, satya).getStatusCode().value()).isEqualTo(200);
        assertThat(heard.get()).isEqualTo("satyacp99");
    }

    @Test
    void signedInWithoutAUserNothingIsForwarded() {
        assertThat(proxy("oidc").forward(call("local-owner"), null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(heard.get()).isNull();
    }

    @Test
    void withoutLoginsTheWorkerHearsTheConfiguredUser() {
        Principal ignored = () -> "someone";
        assertThat(proxy("none").forward(call("intruder"), null, ignored).getStatusCode().value()).isEqualTo(200);
        assertThat(heard.get()).isEqualTo("local-owner");
    }
}
