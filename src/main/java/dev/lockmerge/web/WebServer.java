package dev.lockmerge.web;

import dev.lockmerge.merge.Decision;
import dev.lockmerge.merge.MergeService;
import dev.lockmerge.persist.Session;
import dev.lockmerge.persist.SessionStore;
import dev.lockmerge.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Tiny JDK-only HTTP server hosting the single-page UI and JSON API. */
public final class WebServer {

    private final MergeService service;
    private final SessionStore store;
    private HttpServer server;

    public WebServer(Path dataDirectory) {
        this.store = new SessionStore(dataDirectory);
        this.service = new MergeService(store);
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::route);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                serveStatic(exchange, "/web/index.html", "text/html; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && path.startsWith("/web/")) {
                serveStatic(exchange, path, "application/javascript; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && "/api/sessions".equals(path)) {
                List<Map<String, Object>> sessions = store.listIds().stream()
                        .map(id -> store.find(id).orElse(null))
                        .filter(s -> s != null)
                        .map(s -> Map.<String, Object>of(
                                "id", s.id,
                                "revision", s.revision,
                                "updatedAt", s.updatedAt == null ? "" : s.updatedAt))
                        .toList();
                writeJson(exchange, 200, Map.of("sessions", sessions));
                return;
            }
            if ("POST".equals(method) && "/api/sessions".equals(path)) {
                Session session = service.createSession();
                writeJson(exchange, 201, service.stateView(session));
                return;
            }
            if (path.startsWith("/api/sessions/")) {
                routeSession(exchange, path, method);
                return;
            }
            writeJson(exchange, 404, Map.of("error", "not found"));
        } catch (MergeService.NotFoundException e) {
            writeJson(exchange, 404, Map.of("error", e.getMessage()));
        } catch (MergeService.ConflictException e) {
            writeJson(exchange, 409, Map.of("error", e.getMessage(), "conflict", true));
        } catch (MergeService.BadRequestException | IllegalArgumentException e) {
            writeJson(exchange, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            writeJson(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
        } finally {
            exchange.close();
        }
    }

    private void routeSession(HttpExchange exchange, String path, String method)
            throws IOException {
        String rest = path.substring("/api/sessions/".length());
        int slash = rest.indexOf('/');
        String sessionId = slash < 0 ? rest : rest.substring(0, slash);
        String action = slash < 0 ? null : rest.substring(slash + 1);

        if (action == null && "GET".equals(method)) {
            writeJson(exchange, 200, service.stateView(service.require(sessionId)));
            return;
        }
        if ("inputs".equals(action) && "PUT".equals(method)) {
            Map<String, Object> body = readJsonBody(exchange);
            long revision = asLong(body.get("revision"));
            Session session = service.submitInputs(sessionId, revision,
                asString(body.get("base")), asString(body.get("left")),
                asString(body.get("right")));
            writeJson(exchange, 200, service.stateView(session));
            return;
        }
        if ("decisions".equals(action) && "POST".equals(method)) {
            Map<String, Object> body = readJsonBody(exchange);
            long revision = asLong(body.get("revision"));
            Decision decision = new Decision(
                    asString(body.get("conflictId")),
                    asString(body.get("choice")),
                    asString(body.get("fingerprintBase")),
                    asString(body.get("fingerprintLeft")),
                    asString(body.get("fingerprintRight")));
            Session session = service.addDecision(sessionId, revision, decision);
            writeJson(exchange, 200, service.stateView(session));
            return;
        }
        if (action != null && action.startsWith("decisions/") && "DELETE".equals(method)) {
            String conflictId = action.substring("decisions/".length());
            Map<String, Object> body = readJsonBody(exchange);
            long revision = asLong(body.get("revision"));
            Session session = service.clearDecision(sessionId, revision, conflictId);
            writeJson(exchange, 200, service.stateView(session));
            return;
        }
        if ("publish".equals(action) && "POST".equals(method)) {
            Map<String, Object> body = readJsonBody(exchange);
            long revision = asLong(body.get("revision"));
            Session session = service.publish(sessionId, revision);
            writeJson(exchange, 200, service.stateView(session));
            return;
        }
        if (action != null && action.startsWith("versions/") && "GET".equals(method)) {
            Session session = service.require(sessionId);
            String tail = action.substring("versions/".length());
            int versionNumber;
            try {
                versionNumber = Integer.parseInt(tail);
            } catch (NumberFormatException e) {
                writeJson(exchange, 404, Map.of("error", "unknown version"));
                return;
            }
            Session.PublishedVersion version = session.versions.stream()
                    .filter(v -> v.number == versionNumber)
                    .findFirst()
                    .orElseThrow(() -> new MergeService.NotFoundException(
                            "version " + versionNumber + " not found"));
            byte[] bytes = version.text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    "application/octet-stream; charset=utf-8");
            exchange.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"merged-v" + version.number + ".lsl\"");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            return;
        }
        if ("latest".equals(action) && "GET".equals(method)) {
            Session session = service.require(sessionId);
            if (session.versions.isEmpty()) {
                writeJson(exchange, 404, Map.of("error", "no published version"));
                return;
            }
            Session.PublishedVersion latest = session.versions.get(session.versions.size() - 1);
            byte[] bytes = latest.text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    "application/octet-stream; charset=utf-8");
            exchange.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"merged-v" + latest.number + ".lsl\"");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            return;
        }
        writeJson(exchange, 404, Map.of("error", "unknown endpoint"));
    }

    private void serveStatic(HttpExchange exchange, String resource, String contentType)
            throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                writeJson(exchange, 404, Map.of("error", "resource missing"));
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) Json.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Long.parseLong(s);
        }
        throw new IllegalArgumentException("missing revision");
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
