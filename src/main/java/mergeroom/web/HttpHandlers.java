package mergeroom.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

import mergeroom.store.Json;

final class HttpHandlers {

    private HttpHandlers() {
    }

    static void json(HttpExchange ex, int status, Object body) throws IOException {
        byte[] data = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    static void text(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    static String body(HttpExchange ex) throws IOException {
        byte[] data = ex.getRequestBody().readAllBytes();
        return new String(data, StandardCharsets.UTF_8);
    }

    static Map<String, Object> requireJsonBody(HttpExchange ex) throws IOException {
        String raw = body(ex);
        if (raw.isBlank()) {
            throw new BadRequestException("缺少 JSON 请求体");
        }
        try {
            return Json.parseObject(raw);
        } catch (RuntimeException e) {
            throw new BadRequestException("请求体不是合法 JSON: " + e.getMessage());
        }
    }

    static long revision(Map<String, Object> body) {
        Object v = body.get("revision");
        if (!(v instanceof Number n)) {
            throw new BadRequestException("需要 revision 字段用于并发控制");
        }
        return n.longValue();
    }

    static String string(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    static void staticResource(HttpExchange ex, String resourcePath, String contentType)
            throws IOException {
        Path candidate = Path.of(resourcePath);
        byte[] data;
        if (Files.exists(candidate)) {
            data = Files.readAllBytes(candidate);
        } else {
            try (var in = HttpHandlers.class.getResourceAsStream("/web/" + resourcePath)) {
                if (in == null) {
                    text(ex, 404, "text/plain; charset=utf-8", "not found: " + resourcePath);
                    return;
                }
                data = in.readAllBytes();
            }
        }
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }
}
