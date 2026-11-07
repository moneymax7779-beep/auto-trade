package com.autotrade.exec.app;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The few HTTP calls a broker's daily login makes (Upstox, Kotak Neo, Groww; trader workspaces, 7 Nov 2026): JSON or form
 * bodies, a 15 s timeout, and the broker's own error message on a refusal. A failure's message never carries a request
 * body (a TOTP, MPIN, key or secret), only the broker's reason and the HTTP status.
 */
final class BrokerHttp {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** A refused or failed call: the message is safe to show and to audit. */
    static final class Failure extends RuntimeException {
        final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final String broker;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    BrokerHttp(String broker) {
        this.broker = broker;
    }

    JsonNode getJson(String url, Map<String, String> headers) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), headers);
    }

    JsonNode postJson(String url, Map<String, String> headers, Object body) {
        return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))), headers);
    }

    JsonNode postForm(String url, Map<String, String> headers, Map<String, String> form) {
        String body = form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)), headers);
    }

    private JsonNode send(HttpRequest.Builder request, Map<String, String> headers) {
        headers.forEach(request::header);
        request.header("Accept", "application/json").timeout(Duration.ofSeconds(15));
        HttpResponse<String> r;
        try {
            r = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(0, broker + " not reached (interrupted)");
        } catch (Exception e) {
            throw new Failure(0, broker + " not reached (" + e.getClass().getSimpleName() + ")");
        }
        JsonNode body;
        try {
            body = r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
        } catch (RuntimeException e) {
            body = JSON.createObjectNode();
        }
        if (r.statusCode() >= 300) {
            throw new Failure(r.statusCode(), broker + " refused (HTTP " + r.statusCode() + ")" + reason(body));
        }
        return body;
    }

    /** The broker's own reason in its error shapes (Upstox errors[], Kotak error[] / message, Groww error{}). */
    static String reason(JsonNode body) {
        for (JsonNode candidate : new JsonNode[] {body.path("errors").path(0).path("message"),
                body.path("error").path(0).path("message"), body.path("error").path("displayMessage"),
                body.path("error").path("message"), body.path("message"), body.path("emsg")}) {
            String text = candidate.isString() ? candidate.asString("") : "";
            if (!text.isBlank()) {
                return ": " + (text.length() > 200 ? text.substring(0, 200) : text);
            }
        }
        return "";
    }
}
