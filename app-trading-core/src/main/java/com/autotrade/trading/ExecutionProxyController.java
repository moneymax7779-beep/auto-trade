package com.autotrade.trading;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
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
 * (default http://127.0.0.1:8096) as one user: with {@code autotrade.auth.mode=oidc} the signed-in Keycloak user
 * ({@link SecurityConfig}), otherwise the configured {@code autotrade.ui.execution-user} (a single local user, not
 * authentication). A caller's own {@code X-Autotrade-User} is ignored (code review 24 Oct 2026: anyone past the password
 * gate could otherwise pick any user); cross-site POSTs are refused by {@link SameOriginFilter}. The workers decide what
 * that user may see and do.
 */
@RestController
class ExecutionProxyController {

    private static final String PREFIX = "/api/execution";
    private static final String USER = "X-Autotrade-User";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String target;
    private final String defaultUser;
    private final boolean signedIn;

    ExecutionProxyController(@Value("${autotrade.ui.execution-url:http://127.0.0.1:8096}") String target,
                             @Value("${autotrade.ui.execution-user:local-owner}") String defaultUser, AuthProperties auth) {
        this.target = target.endsWith("/") ? target.substring(0, target.length() - 1) : target;
        this.defaultUser = defaultUser;
        this.signedIn = auth.oidc();
    }

    @RequestMapping(value = {PREFIX, PREFIX + "/**"}, method = {RequestMethod.GET, RequestMethod.POST})
    ResponseEntity<byte[]> forward(HttpServletRequest request, @RequestBody(required = false) byte[] body,
                                   Principal principal) {
        String user = signedIn ? (principal == null ? null : principal.getName()) : defaultUser;
        if (user == null || user.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"sign in first\"}".getBytes(StandardCharsets.UTF_8));  // the chain should have refused it
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.contains("..") || path.contains("%2e") || path.contains("%2E") || !path.startsWith(PREFIX)) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"bad path\"}".getBytes(StandardCharsets.UTF_8));  // only /api/execution is forwarded
        }
        String query = request.getQueryString();
        HttpRequest.Builder out = HttpRequest.newBuilder(URI.create(target + path + (query == null ? "" : "?" + query)))
                .timeout(Duration.ofSeconds(150))                  // a flatten works orders to their end
                .header(USER, user);                                // never the caller's own header
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
