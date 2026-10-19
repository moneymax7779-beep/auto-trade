package com.autotrade.trading;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The UI's Accounts page talks to the execution workers (app-execution) through this same-origin pass-through:
 * {@code /api/execution/**} is forwarded as is (GET and POST, query and JSON body) to {@code autotrade.ui.execution-url}
 * (default http://127.0.0.1:8096). The caller's {@code X-Autotrade-User} goes along; when the request has none, the
 * configured {@code autotrade.ui.execution-user} is used (a single local user until Keycloak is in front, L3: it is
 * not authentication). The workers decide what that user may see and do.
 */
@RestController
class ExecutionProxyController {

    private static final String PREFIX = "/api/execution";
    private static final String USER = "X-Autotrade-User";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String target;
    private final String defaultUser;

    ExecutionProxyController(@Value("${autotrade.ui.execution-url:http://127.0.0.1:8096}") String target,
                             @Value("${autotrade.ui.execution-user:local-owner}") String defaultUser) {
        this.target = target.endsWith("/") ? target.substring(0, target.length() - 1) : target;
        this.defaultUser = defaultUser;
    }

    @RequestMapping(value = {PREFIX, PREFIX + "/**"}, method = {RequestMethod.GET, RequestMethod.POST})
    ResponseEntity<byte[]> forward(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.contains("..") || path.contains("%2e") || path.contains("%2E") || !path.startsWith(PREFIX)) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"bad path\"}".getBytes(StandardCharsets.UTF_8));  // only /api/execution is forwarded
        }
        String query = request.getQueryString();
        String user = request.getHeader(USER);
        HttpRequest.Builder out = HttpRequest.newBuilder(URI.create(target + path + (query == null ? "" : "?" + query)))
                .timeout(Duration.ofSeconds(150))                  // a flatten works orders to their end
                .header(USER, user == null || user.isBlank() ? defaultUser : user);
        if ("POST".equals(request.getMethod())) {
            out.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        } else {
            out.GET();
        }
        try {
            HttpResponse<byte[]> response = client.send(out.build(), HttpResponse.BodyHandlers.ofByteArray());
            String type = response.headers().firstValue("Content-Type").orElse(MediaType.APPLICATION_JSON_VALUE);
            return ResponseEntity.status(response.statusCode()).contentType(MediaType.parseMediaType(type))
                    .body(response.body());
        } catch (IOException e) {
            return unavailable("the execution worker at " + target + " is not reachable (" + e.getClass().getSimpleName()
                    + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable("interrupted");
        }
    }

    private static ResponseEntity<byte[]> unavailable(String message) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                .body(("{\"error\":\"" + message.replace("\"", "'") + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
