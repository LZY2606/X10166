package mergeroom.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import mergeroom.merge.MergeService;
import mergeroom.store.ConcurrentEditException;
import mergeroom.store.Json;
import mergeroom.store.Session;
import mergeroom.store.SessionStore;

/**
 * Local web UI + JSON API backed by the JDK's embedded HTTP server.
 */
public final class WebServer {

    private final MergeService service;
    private final SessionStore sessions;
    private HttpServer server;

    public WebServer(Path storageDir) {
        this.sessions = new SessionStore(storageDir);
        this.service = new MergeService(sessions);
    }

    public int start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::route);
        server.setExecutor(null);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            switch (path) {
                case "/" -> HttpHandlers.staticResource(ex, "index.html",
                        "text/html; charset=utf-8");
                case "/app.js" -> HttpHandlers.staticResource(ex, "app.js",
                        "application/javascript; charset=utf-8");
                case "/styles.css" -> HttpHandlers.staticResource(ex, "styles.css",
                        "text/css; charset=utf-8");
                case "/api/sessions", "/api/sessions/" -> {
                    if ("POST".equals(method)) {
                        createSession(ex);
                    } else {
                        listSessions(ex);
                    }
                }
                case "/api/health" -> HttpHandlers.json(ex, 200, Map.of("ok", true));
                default -> routeSession(ex, path, method);
            }
        } catch (HttpHandlers.BadRequestException e) {
            HttpHandlers.json(ex, 400, Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            HttpHandlers.json(ex, 404, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            HttpHandlers.json(ex, 500, Map.of("error", e.toString()));
        } finally {
            ex.close();
        }
    }

    private void routeSession(HttpExchange ex, String path, String method) throws IOException {
        String[] parts = path.split("/");
        // /api/sessions/{id}/...
        if (parts.length < 4 || !"api".equals(parts[1]) || !"sessions".equals(parts[2])) {
            HttpHandlers.json(ex, 404, Map.of("error", "unknown path " + path));
            return;
        }
        String id = parts[3];
        Session session = sessions.find(id).orElseThrow(
                () -> new IllegalArgumentException("会话不存在: " + id));
        if (parts.length == 4) {
            if ("GET".equals(method)) {
                HttpHandlers.json(ex, 200, service.snapshot(session));
            } else if ("PUT".equals(method)) {
                updateInputs(ex, session);
            } else {
                methodNotAllowed(ex, method);
            }
            return;
        }
        String action = parts[4];
        switch (action) {
            case "verdicts" -> {
                if ("PUT".equals(method)) {
                    putVerdict(ex, session);
                } else if ("DELETE".equals(method)) {
                    deleteVerdict(ex, session);
                } else {
                    methodNotAllowed(ex, method);
                }
            }
            case "clear-verdicts" -> clearVerdicts(ex, session);
            case "output" -> {
                if ("GET".equals(method)) {
                    downloadOutput(ex, session);
                } else if ("POST".equals(method)) {
                    saveOutput(ex, session);
                } else {
                    methodNotAllowed(ex, method);
                }
            }
            default -> HttpHandlers.json(ex, 404, Map.of("error", "unknown action " + action));
        }
    }

    private void createSession(HttpExchange ex) throws IOException {
        Session s = sessions.create();
        HttpHandlers.json(ex, 201, service.snapshot(s));
    }

    private void listSessions(HttpExchange ex) throws IOException {
        HttpHandlers.json(ex, 200, Map.of("sessions", sessions.listIds()));
    }

    private void updateInputs(HttpExchange ex, Session session) throws IOException {
        Map<String, Object> body = HttpHandlers.requireJsonBody(ex);
        long rev = HttpHandlers.revision(body);
        Map<String, Object> inputs = asMap(body.get("inputs"));
        Map<String, Object> b = asMap(inputs.get("base"));
        Map<String, Object> l = asMap(inputs.get("left"));
        Map<String, Object> r = asMap(inputs.get("right"));
        try {
            Session updated = sessions.updateInputs(session.id, rev,
                    HttpHandlers.string(b, "text"),
                    HttpHandlers.string(l, "text"),
                    HttpHandlers.string(r, "text"));
            HttpHandlers.json(ex, 200, service.snapshot(updated));
        } catch (ConcurrentEditException e) {
            conflict(ex, e);
        }
    }

    private void putVerdict(HttpExchange ex, Session session) throws IOException {
        Map<String, Object> body = HttpHandlers.requireJsonBody(ex);
        long rev = HttpHandlers.revision(body);
        String conflictId = HttpHandlers.string(body, "conflictId");
        String side = HttpHandlers.string(body, "side");
        if (conflictId.isEmpty()) {
            throw new HttpHandlers.BadRequestException("需要 conflictId");
        }
        try {
            Session updated = sessions.putVerdict(session.id, rev, conflictId, side);
            HttpHandlers.json(ex, 200, service.snapshot(updated));
        } catch (ConcurrentEditException e) {
            conflict(ex, e);
        }
    }

    private void deleteVerdict(HttpExchange ex, Session session) throws IOException {
        Map<String, Object> body = HttpHandlers.requireJsonBody(ex);
        long rev = HttpHandlers.revision(body);
        String conflictId = HttpHandlers.string(body, "conflictId");
        try {
            Session updated = sessions.removeVerdict(session.id, rev, conflictId);
            HttpHandlers.json(ex, 200, service.snapshot(updated));
        } catch (ConcurrentEditException e) {
            conflict(ex, e);
        }
    }

    private void clearVerdicts(HttpExchange ex, Session session) throws IOException {
        Map<String, Object> body = HttpHandlers.requireJsonBody(ex);
        long rev = HttpHandlers.revision(body);
        try {
            Session updated = sessions.clearVerdicts(session.id, rev);
            HttpHandlers.json(ex, 200, service.snapshot(updated));
        } catch (ConcurrentEditException e) {
            conflict(ex, e);
        }
    }

    private void saveOutput(HttpExchange ex, Session session) throws IOException {
        Map<String, Object> body = HttpHandlers.requireJsonBody(ex);
        long rev = HttpHandlers.revision(body);
        // Recompute to make sure the stored output is the current clean merge.
        var snap = service.snapshot(session);
        Object mergeObj = snap.get("merge");
        if (!(mergeObj instanceof Map<?, ?> merge) || merge.get("output") == null) {
            HttpHandlers.json(ex, 409, Map.of("error", "当前合并仍被阻塞，无法保存输出版本"));
            return;
        }
        String output = String.valueOf(merge.get("output"));
        try {
            Session updated = sessions.saveOutput(session.id, rev, output);
            HttpHandlers.json(ex, 200, service.snapshot(updated));
        } catch (ConcurrentEditException e) {
            conflict(ex, e);
        }
    }

    private void downloadOutput(HttpExchange ex, Session session) throws IOException {
        var snap = service.snapshot(session);
        Object mergeObj = snap.get("merge");
        String output = "";
        if (mergeObj instanceof Map<?, ?> merge && merge.get("output") != null) {
            output = String.valueOf(merge.get("output"));
        } else if (!session.outputText.isEmpty()) {
            output = session.outputText;
        } else {
            HttpHandlers.json(ex, 409, Map.of("error", "没有可下载的合并结果"));
            return;
        }
        byte[] data = output.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().add("Content-Disposition",
                "attachment; filename=\"merged.lock\"");
        ex.sendResponseHeaders(200, data.length);
        try (var os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private void conflict(HttpExchange ex, ConcurrentEditException e) throws IOException {
        HttpHandlers.json(ex, 409, Map.of(
                "error", e.getMessage(),
                "expectedRevision", e.expected,
                "actualRevision", e.actual));
    }

    private void methodNotAllowed(HttpExchange ex, String method) throws IOException {
        HttpHandlers.json(ex, 405, Map.of("error", "method not allowed: " + method));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?>) {
            return (Map<String, Object>) o;
        }
        throw new HttpHandlers.BadRequestException("期望对象字段");
    }
}
