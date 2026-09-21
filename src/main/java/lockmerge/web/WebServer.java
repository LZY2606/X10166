package lockmerge.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lockmerge.json.Json;
import lockmerge.merge.ConflictException;
import lockmerge.merge.ConcurrentRevisionException;
import lockmerge.merge.Evaluation;
import lockmerge.session.SessionService;

/** Local single-user HTTP server backed by the JDK's built-in web server. */
public final class WebServer {

    private final SessionService service;
    private HttpServer server;

    public WebServer(SessionService service) {
        this.service = service;
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/api/state", new StateHandler());
        server.createContext("/api/inputs", new InputsHandler());
        server.createContext("/api/decide", new DecideHandler());
        server.createContext("/api/reset", new ResetHandler());
        server.createContext("/api/download", new DownloadHandler());
        server.setExecutor(null);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                serveResource(exchange, "web/index.html", "text/html; charset=utf-8");
            } else if (path.equals("/app.js")) {
                serveResource(exchange, "web/app.js", "application/javascript; charset=utf-8");
            } else if (path.equals("/styles.css")) {
                serveResource(exchange, "web/styles.css", "text/css; charset=utf-8");
            } else {
                sendText(exchange, 404, "not found", "text/plain; charset=utf-8");
            }
        }
    }

    private void serveResource(HttpExchange exchange, String resourcePath, String contentType)
            throws IOException {
        try (InputStream in = WebServer.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) {
                sendText(exchange, 404, "missing resource: " + resourcePath,
                        "text/plain; charset=utf-8");
                return;
            }
            byte[] body = in.readAllBytes();
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType);
            headers.set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private final class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "method not allowed", "text/plain");
                return;
            }
            Evaluation evaluation = service.evaluate();
            sendJson(exchange, 200, ApiViews.state(service.session(), evaluation));
        }
    }

    private final class InputsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "method not allowed", "text/plain");
                return;
            }
            Map<String, Object> request = readJson(exchange);
            long revision = asLong(request.get("revision"), -1);
            try {
                Evaluation evaluation = service.updateInputs(
                        asString(request.get("base")),
                        asString(request.get("left")),
                        asString(request.get("right")),
                        revision);
                sendJson(exchange, 200, ApiViews.state(service.session(), evaluation));
            } catch (ConcurrentRevisionException e) {
                sendError(exchange, 409, "CONCURRENT_EDIT", e.getMessage());
            }
        }
    }

    private final class DecideHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "method not allowed", "text/plain");
                return;
            }
            Map<String, Object> request = readJson(exchange);
            long revision = asLong(request.get("revision"), -1);
            try {
                Evaluation evaluation = service.resolveConflict(
                        asString(request.get("conflictId")),
                        asString(request.get("optionId")),
                        revision);
                sendJson(exchange, 200, ApiViews.state(service.session(), evaluation));
            } catch (ConcurrentRevisionException e) {
                sendError(exchange, 409, "CONCURRENT_EDIT", e.getMessage());
            } catch (ConflictException e) {
                sendError(exchange, 404, "CONFLICT_GONE", e.getMessage());
            }
        }
    }

    private final class ResetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "method not allowed", "text/plain");
                return;
            }
            Map<String, Object> request = readJson(exchange);
            long revision = asLong(request.get("revision"), -1);
            try {
                Evaluation evaluation = service.resetDecisions(revision);
                sendJson(exchange, 200, ApiViews.state(service.session(), evaluation));
            } catch (ConcurrentRevisionException e) {
                sendError(exchange, 409, "CONCURRENT_EDIT", e.getMessage());
            }
        }
    }

    private final class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "method not allowed", "text/plain");
                return;
            }
            Evaluation evaluation = service.evaluate();
            if (evaluation.outputText() == null) {
                sendText(exchange, 409,
                        "merged lockfile is not available yet: resolve conflicts and issues",
                        "text/plain; charset=utf-8");
                return;
            }
            byte[] body = evaluation.outputText().getBytes(StandardCharsets.UTF_8);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/octet-stream");
            headers.set("Content-Disposition",
                    "attachment; filename=\"merged.lock\"");
            headers.set("X-Output-Fingerprint",
                    evaluation.outputFingerprint() == null ? ""
                            : evaluation.outputFingerprint());
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    // ---- helpers ------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        try {
            Object parsed = Json.read(new String(body, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("expected JSON object body");
            }
            return (Map<String, Object>) parsed;
        } catch (RuntimeException e) {
            sendError(exchange, 400, "BAD_JSON", e.getMessage());
            throw new IOException("bad json", e);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String code, String message)
            throws IOException {
        sendJson(exchange, status, Json.object("error", code, "message", message));
    }

    private void sendText(HttpExchange exchange, int status, String text, String type)
            throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long asLong(Object value, long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
