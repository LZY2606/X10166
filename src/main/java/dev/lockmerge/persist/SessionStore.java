package dev.lockmerge.persist;

import dev.lockmerge.merge.Decision;
import dev.lockmerge.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed session store. Every mutation increments an optimistic revision;
 * writers must present the revision they observed, otherwise
 * {@link ConcurrentModificationException} is raised.
 *
 * <p>Writes are atomic (temp file + atomic move) and guarded per session by a
 * monitor so concurrent HTTP requests serialize safely.
 */
public final class SessionStore {

    private final Path directory;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public SessionStore(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create session directory " + directory, e);
        }
    }

    public List<String> listIds() {
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list sessions", e);
        }
    }

    public Optional<Session> find(String id) {
        Path file = fileFor(id);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(deserialize(Json.parseObject(text)));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read session " + id, e);
        }
    }

    public String newId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    public Object lockFor(String id) {
        return locks.computeIfAbsent(id, k -> new Object());
    }

    public void save(Session session) {
        validateId(session.id);
        Path file = fileFor(session.id);
        Path temp = file.resolveSibling("." + session.id + ".json.tmp");
        try {
            Files.writeString(temp, Json.pretty(serialize(session)), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort
            }
            throw new IllegalStateException("cannot write session " + session.id, e);
        }
    }

    private Path fileFor(String id) {
        validateId(id);
        return directory.resolve(id + ".json");
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("invalid session id");
        }
    }

    static Map<String, Object> serialize(Session session) {
        return Map.of(
                "id", session.id,
                "createdAt", session.createdAt,
                "updatedAt", session.updatedAt,
                "revision", session.revision,
                "baseText", session.baseText,
                "leftText", session.leftText,
                "rightText", session.rightText,
                "decisions", session.decisions.stream().map(d -> Map.<String, Object>of(
                        "conflictId", d.conflictId(),
                        "choice", d.choice(),
                        "fingerprintBase", d.fingerprintBase(),
                        "fingerprintLeft", d.fingerprintLeft(),
                        "fingerprintRight", d.fingerprintRight())).toList(),
                "versions", session.versions.stream().map(v -> Map.<String, Object>of(
                        "number", v.number,
                        "publishedAt", v.publishedAt,
                        "text", v.text,
                        "fingerprintBase", v.fingerprintBase,
                        "fingerprintLeft", v.fingerprintLeft,
                        "fingerprintRight", v.fingerprintRight)).toList());
    }

    @SuppressWarnings("unchecked")
    static Session deserialize(Map<String, Object> map) {
        Session session = new Session();
        session.id = (String) map.get("id");
        session.createdAt = (String) map.get("createdAt");
        session.updatedAt = (String) map.get("updatedAt");
        session.revision = ((Number) map.getOrDefault("revision", 0L)).longValue();
        session.baseText = (String) map.getOrDefault("baseText", "");
        session.leftText = (String) map.getOrDefault("leftText", "");
        session.rightText = (String) map.getOrDefault("rightText", "");
        for (Object item : (List<Object>) map.getOrDefault("decisions", List.of())) {
            Map<String, Object> d = (Map<String, Object>) item;
            session.decisions.add(new Decision(
                    (String) d.get("conflictId"),
                    (String) d.get("choice"),
                    (String) d.get("fingerprintBase"),
                    (String) d.get("fingerprintLeft"),
                    (String) d.get("fingerprintRight")));
        }
        for (Object item : (List<Object>) map.getOrDefault("versions", List.of())) {
            Map<String, Object> v = (Map<String, Object>) item;
            Session.PublishedVersion version = new Session.PublishedVersion(
                    ((Number) v.get("number")).intValue(),
                    (String) v.get("publishedAt"),
                    (String) v.get("text"),
                    (String) v.get("fingerprintBase"),
                    (String) v.get("fingerprintLeft"),
                    (String) v.get("fingerprintRight"));
            session.versions.add(version);
        }
        return session;
    }
}
