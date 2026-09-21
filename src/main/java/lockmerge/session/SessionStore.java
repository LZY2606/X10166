package lockmerge.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lockmerge.json.Json;
import lockmerge.merge.Decision;

/** File-backed, atomically written persistence for one session. */
public final class SessionStore {

    private final Path file;

    public SessionStore(Path directory, String sessionId) {
        this.file = directory.resolve("session-" + sessionId + ".json");
    }

    public Path file() {
        return file;
    }

    public synchronized void save(Session session) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = Json.write(toMap(session));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist session to " + file, e);
        }
    }

    public synchronized Session load() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return fromMap(Json.readObject(json));
        } catch (IOException e) {
            throw new IllegalStateException("failed to read session from " + file, e);
        }
    }

    private static Map<String, Object> toMap(Session session) {
        List<Object> decisions = new ArrayList<>();
        for (Decision decision : session.decisions()) {
            decisions.add(Json.object(
                    "id", decision.id(),
                    "conflictId", decision.conflictId(),
                    "optionId", decision.optionId(),
                    "inputFingerprint", decision.inputFingerprint(),
                    "baseFingerprint", decision.baseFingerprint(),
                    "leftFingerprint", decision.leftFingerprint(),
                    "rightFingerprint", decision.rightFingerprint(),
                    "chosenSummary", decision.chosenSummary(),
                    "createdAt", decision.createdAt().toString()));
        }
        return Json.object(
                "id", session.id(),
                "revision", session.revision(),
                "baseRaw", session.baseRaw(),
                "leftRaw", session.leftRaw(),
                "rightRaw", session.rightRaw(),
                "createdAt", session.createdAt().toString(),
                "updatedAt", session.updatedAt().toString(),
                "decisions", decisions,
                "lastOutputText", session.lastOutputText(),
                "lastOutputFingerprint", session.lastOutputFingerprint(),
                "outputVersion", session.outputVersion(),
                "outputUpdatedAt",
                session.outputUpdatedAt() == null ? null
                        : session.outputUpdatedAt().toString());
    }

    @SuppressWarnings("unchecked")
    private static Session fromMap(Map<String, Object> map) {
        Session session = new Session();
        session.id(asString(map.get("id"), "default"));
        session.revision(asLong(map.get("revision"), 0));
        session.raws(asString(map.get("baseRaw"), ""),
                asString(map.get("leftRaw"), ""),
                asString(map.get("rightRaw"), ""));
        session.createdAt(asInstant(map.get("createdAt")));
        session.updatedAt(asInstant(map.get("updatedAt")));
        Object decisions = map.get("decisions");
        if (decisions instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> d = (Map<String, Object>) item;
                session.decisions().add(new Decision(
                        asString(d.get("id"), ""),
                        asString(d.get("conflictId"), ""),
                        asString(d.get("optionId"), ""),
                        asString(d.get("inputFingerprint"), ""),
                        asString(d.get("baseFingerprint"), ""),
                        asString(d.get("leftFingerprint"), ""),
                        asString(d.get("rightFingerprint"), ""),
                        asString(d.get("chosenSummary"), ""),
                        asInstant(d.get("createdAt"))));
            }
        }
        session.restoreOutput(
                asString(map.get("lastOutputText"), null),
                asString(map.get("lastOutputFingerprint"), null),
                (int) asLong(map.get("outputVersion"), 0),
                asInstant(map.get("outputUpdatedAt")));
        return session;
    }

    private static String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
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

    private static Instant asInstant(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }
}
