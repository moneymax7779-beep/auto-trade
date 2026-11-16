package com.autotrade.execution;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A saved READ_LIVE answer ({@link ReadLiveRecorder}) as a contract-test fixture (16 Nov 2026, D2): which broker and day,
 * the call it answered (method and path from the folder's {@code index.jsonl}) and the redacted body. A test either reads
 * the body, or {@link #serve()}s it on 127.0.0.1 so the broker's own gateway parses the real shape (its HTTP client
 * pointed at {@link Served#baseUri()}): every request to the recorded path is answered with the recorded body.
 * <p>
 * As a tool: {@code java -cp <execution classpath> com.autotrade.execution.ReadLiveFixture <saved file> <fixtures dir>}
 * copies the answer into the fixtures folder as {@code <broker>/<endpoint>-<day>-<seq>.json} (the fixture: broker, day,
 * method, path, query, body), ready to check in under a module's {@code src/test/resources}; {@link #fromFixture}
 * reads it back.
 */
public record ReadLiveFixture(String broker, LocalDate day, String endpoint, String method, String path, String query,
                              String body) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A saved file ({@code <root>/<broker>/<yyyy-MM-dd>/<endpoint>-<seq>.json}) with its index line. */
    public static ReadLiveFixture load(Path saved) throws IOException {
        Path dir = saved.toAbsolutePath().getParent();
        String file = saved.getFileName().toString();
        String broker = dir.getParent().getFileName().toString();
        LocalDate day = LocalDate.parse(dir.getFileName().toString());
        String body = Files.readString(saved, StandardCharsets.UTF_8);
        Path index = dir.resolve(ReadLiveRecorder.INDEX);
        if (Files.isRegularFile(index)) {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = JSON.readTree(line);
                if (file.equals(n.path("file").asString(""))) {
                    return new ReadLiveFixture(broker, day, n.path("endpoint").asString(""), n.path("method").asString("GET"),
                            n.path("path").asString(""), n.path("query").isNull() ? null : n.path("query").asString(null),
                            body);
                }
            }
        }
        throw new IOException(saved + " has no line in " + index + ": not a recorder file");
    }

    /** Writes this fixture under {@code fixtures/<broker>/<endpoint>-<day>-<n>.json}; returns the file. */
    public Path writeTo(Path fixtures, int n) throws IOException {
        Path dir = fixtures.resolve(broker);
        Files.createDirectories(dir);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("broker", broker);
        f.put("day", day.toString());
        f.put("endpoint", endpoint);
        f.put("method", method);
        f.put("path", path);
        f.put("query", query);
        JsonNode parsed;
        try {
            parsed = JSON.readTree(body);
        } catch (RuntimeException e) {
            parsed = null;
        }
        f.put("body", parsed != null && (parsed.isObject() || parsed.isArray()) ? parsed : body);
        Path out = dir.resolve(endpoint + "-" + day + "-" + n + ".json");
        Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(f), StandardCharsets.UTF_8);
        return out;
    }

    /** A fixture written by {@link #writeTo}. */
    public static ReadLiveFixture fromFixture(Path fixture) throws IOException {
        JsonNode n = JSON.readTree(Files.readString(fixture, StandardCharsets.UTF_8));
        JsonNode body = n.path("body");
        return new ReadLiveFixture(n.path("broker").asString(""), LocalDate.parse(n.path("day").asString()),
                n.path("endpoint").asString(""), n.path("method").asString("GET"), n.path("path").asString(""),
                n.path("query").isNull() ? null : n.path("query").asString(null),
                body.isString() ? body.asString() : JSON.writeValueAsString(body));
    }

    /** The body parsed as JSON. */
    public JsonNode json() {
        return JSON.readTree(body);
    }

    /** A local server answering the recorded path (any method, any query) with the recorded body; close it after. */
    public record Served(HttpServer server, URI baseUri) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Serves fixtures together (a gateway that reads several endpoints in one call): each on its own path. */
    public static Served serve(List<ReadLiveFixture> fixtures) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        for (ReadLiveFixture f : fixtures) {
            byte[] bytes = f.body().getBytes(StandardCharsets.UTF_8);
            server.createContext(f.path(), ex -> {
                if (!ex.getRequestURI().getPath().equals(f.path())) {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                    return;
                }
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bytes);
                }
            });
        }
        server.start();
        return new Served(server, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    public Served serve() throws IOException {
        return serve(List.of(this));
    }

    /** The tool: {@code <saved file> <fixtures dir>}. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: ReadLiveFixture <saved read-live file> <fixtures dir>");
            System.exit(2);
        }
        ReadLiveFixture f = load(Path.of(args[0]));
        String name = Path.of(args[0]).getFileName().toString();
        int n = Integer.parseInt(name.substring(name.lastIndexOf('-') + 1, name.length() - ".json".length()));
        System.out.println(f.writeTo(Path.of(args[1]), n));
    }
}
