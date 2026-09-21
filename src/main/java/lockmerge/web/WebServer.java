package lockmerge.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import lockmerge.merge.Conflict;
import lockmerge.merge.MergeResult;
import lockmerge.merge.PrunedNode;
import lockmerge.model.LockGraph;
import lockmerge.model.PackageNode;
import lockmerge.store.Json;
import lockmerge.store.Session;
import lockmerge.store.SessionStore;

public final class WebServer {
    private final MergeService service;
    private final SessionStore store;
    private HttpServer server;

    public WebServer(MergeService service, SessionStore store) {
        this.service = service;
        this.store = store;
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::route);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Lockfile 语义合并室已启动: http://" + host + ":" + port);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") || path.equals("/index.html")) {
                sendStatic(ex, "/static/index.html", "text/html; charset=utf-8");
            } else if (path.equals("/app.js")) {
                sendStatic(ex, "/static/app.js", "text/javascript; charset=utf-8");
            } else if (path.equals("/style.css")) {
                sendStatic(ex, "/static/style.css", "text/css; charset=utf-8");
            } else if (path.equals("/api/sessions") && method.equals("POST")) {
                handleCreate(ex);
            } else if (path.equals("/api/sessions") && method.equals("GET")) {
                handleList(ex);
            } else if (path.matches("/api/sessions/[a-zA-Z0-9-]+")) {
                handleGet(ex, path.substring("/api/sessions/".length()));
            } else if (path.matches("/api/sessions/[a-zA-Z0-9-]+/inputs") && method.equals("POST")) {
                handleInputs(ex, idOf(path, "/inputs"));
            } else if (path.matches("/api/sessions/[a-zA-Z0-9-]+/decisions") && method.equals("POST")) {
                handleDecision(ex, idOf(path, "/decisions"));
            } else if (path.matches("/api/sessions/[a-zA-Z0-9-]+/output") && method.equals("GET")) {
                handleOutput(ex, idOf(path, "/output"));
            } else {
                sendJson(ex, 404, Map.of("error", "not found"));
            }
        } catch (MergeService.OptimisticLockException e) {
            sendJson(ex, 409, Map.of("error", e.getMessage(), "kind", "conflict"));
        } catch (MergeService.BadInputException | Json.JsonException | IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", String.valueOf(e)));
        } finally {
            ex.close();
        }
    }

    private static String idOf(String path, String suffix) {
        String rest = path.substring("/api/sessions/".length());
        return rest.substring(0, rest.length() - suffix.length());
    }

    private void handleCreate(HttpExchange ex) throws IOException {
        Map<String, Object> body = Json.parseObject(readBody(ex));
        Session s = service.createSession(str(body, "base"), str(body, "left"), str(body, "right"));
        sendJson(ex, 200, snapshotJson(service.snapshot(s.id)));
    }

    private void handleList(HttpExchange ex) throws IOException {
        List<Object> items = new ArrayList<>();
        for (Session s : store.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("version", s.version);
            m.put("updatedAt", s.updatedAt);
            m.put("fingerprint", s.fingerprint);
            m.put("outputCount", s.outputs.size());
            items.add(m);
        }
        sendJson(ex, 200, Map.of("sessions", items));
    }

    private void handleGet(HttpExchange ex, String id) throws IOException {
        sendJson(ex, 200, snapshotJson(service.snapshot(id)));
    }

    private void handleInputs(HttpExchange ex, String id) throws IOException {
        Map<String, Object> body = Json.parseObject(readBody(ex));
        MergeService.Snapshot snap = service.updateInputs(id, str(body, "base"), str(body, "left"),
                str(body, "right"), num(body, "expectedVersion"));
        sendJson(ex, 200, snapshotJson(snap));
    }

    private void handleDecision(HttpExchange ex, String id) throws IOException {
        Map<String, Object> body = Json.parseObject(readBody(ex));
        MergeService.Snapshot snap = service.decide(id, str(body, "conflictId"), str(body, "choice"),
                num(body, "expectedVersion"));
        sendJson(ex, 200, snapshotJson(snap));
    }

    private void handleOutput(HttpExchange ex, String id) throws IOException {
        MergeService.Snapshot snap = service.snapshot(id);
        if (snap.output() == null) {
            sendJson(ex, 409, Map.of("error", "仍有未决冲突或校验错误，无法生成输出"));
            return;
        }
        byte[] bytes = snap.output().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        if (ex.getRequestURI().getQuery() != null && ex.getRequestURI().getQuery().contains("download=1")) {
            ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"merged-" + id + ".lock\"");
        }
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private Map<String, Object> snapshotJson(MergeService.Snapshot snap) {
        Session s = snap.session();
        MergeResult merge = snap.merge();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id);
        m.put("version", s.version);
        m.put("fingerprint", s.fingerprint);
        m.put("baseText", s.baseText);
        m.put("leftText", s.leftText);
        m.put("rightText", s.rightText);
        m.put("diagnostics", s.diagnostics);
        m.put("graph", graphJson(merge.graph));
        List<Object> conflicts = new ArrayList<>();
        for (Conflict c : merge.conflicts) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.id);
            cm.put("nodeKey", c.nodeKey);
            cm.put("field", c.field);
            cm.put("leftValue", c.leftValue);
            cm.put("rightValue", c.rightValue);
            cm.put("description", c.description);
            cm.put("choice", c.choice);
            conflicts.add(cm);
        }
        m.put("conflicts", conflicts);
        List<Object> pruned = new ArrayList<>();
        for (PrunedNode p : merge.pruned) {
            pruned.add(Map.of("key", p.key, "reason", p.reason));
        }
        m.put("pruned", pruned);
        m.put("integrityViolations", merge.integrityViolations);
        m.put("validationErrors", merge.validationErrors);
        m.put("resolved", merge.resolved());
        m.put("output", snap.output());
        List<Object> decisions = new ArrayList<>();
        for (Session.Decision d : s.decisions) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("conflictId", d.conflictId);
            dm.put("choice", d.choice);
            dm.put("fingerprint", d.fingerprint);
            dm.put("active", d.fingerprint.equals(s.fingerprint));
            decisions.add(dm);
        }
        m.put("decisions", decisions);
        List<Object> outputs = new ArrayList<>();
        for (Session.OutputVersion o : s.outputs) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("version", o.version);
            om.put("fingerprint", o.fingerprint);
            om.put("decisionCount", o.decisionCount);
            om.put("at", o.at);
            outputs.add(om);
        }
        m.put("outputs", outputs);
        return m;
    }

    private Map<String, Object> graphJson(LockGraph g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roots", g.roots);
        List<Object> pkgs = new ArrayList<>();
        for (PackageNode p : g.packages.values()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("key", p.key());
            pm.put("name", p.name);
            pm.put("version", p.version);
            pm.put("source", p.source);
            pm.put("integrity", p.integrity);
            pm.put("platform", p.platform == null ? null : p.platform.canonical());
            pm.put("deps", p.deps);
            pkgs.add(pm);
        }
        m.put("packages", pkgs);
        return m;
    }

    private void sendStatic(HttpExchange ex, String resource, String contentType) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                sendJson(ex, 404, Map.of("error", "static resource missing: " + resource));
                return;
            }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
        }
    }

    private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : (String) v;
    }

    private static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? 0L : ((Number) v).longValue();
    }
}
