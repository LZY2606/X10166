package mergeroom.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * File-backed, revision-controlled session store. Every mutation is written
 * atomically (temp file + move) and the revision is checked on update so
 * concurrent editors get a {@link ConcurrentEditException}.
 */
public final class SessionStore {

    private final Path dir;
    private final Object lock = new Object();

    public SessionStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create storage dir " + dir, e);
        }
    }

    public Session create() {
        synchronized (lock) {
            Session s = new Session();
            s.id = UUID.randomUUID().toString();
            s.revision = 1;
            s.createdAt = Instant.now().toString();
            s.updatedAt = s.createdAt;
            s.fingerprint = Fingerprints.triple("", "", "");
            persist(s);
            return s;
        }
    }

    public Optional<Session> find(String id) {
        synchronized (lock) {
            Path file = fileFor(id);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            return Optional.of(load(file));
        }
    }

    public List<String> listIds() {
        synchronized (lock) {
            List<String> ids = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .forEach(p -> ids.add(p.getFileName().toString().replace(".json", "")));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            ids.sort(String::compareTo);
            return ids;
        }
    }

    /**
     * Updates inputs. Any input change invalidates previously bound verdicts:
     * they move to staleVerdicts and are not auto-applied.
     */
    public Session updateInputs(String id, long expectedRevision,
                                String base, String left, String right)
            throws ConcurrentEditException {
        synchronized (lock) {
            Session s = require(id);
            checkRevision(s, expectedRevision);
            s.baseText = nullToEmpty(base);
            s.leftText = nullToEmpty(left);
            s.rightText = nullToEmpty(right);
            String newFp = Fingerprints.triple(s.baseText, s.leftText, s.rightText);
            if (!newFp.equals(s.fingerprint)) {
                if (!s.verdicts.isEmpty()) {
                    s.staleVerdicts.putAll(s.verdicts);
                    s.verdicts.clear();
                }
                s.fingerprint = newFp;
                s.outputText = "";
            }
            touch(s);
            persist(s);
            return s;
        }
    }

    /** Applies a verdict; rejects it if the caller's revision is stale. */
    public Session putVerdict(String id, long expectedRevision, String conflictId, String side)
            throws ConcurrentEditException {
        synchronized (lock) {
            Session s = require(id);
            checkRevision(s, expectedRevision);
            s.verdicts.put(conflictId, normalizeSide(side));
            s.staleVerdicts.remove(conflictId);
            s.outputText = "";
            touch(s);
            persist(s);
            return s;
        }
    }

    public Session removeVerdict(String id, long expectedRevision, String conflictId)
            throws ConcurrentEditException {
        synchronized (lock) {
            Session s = require(id);
            checkRevision(s, expectedRevision);
            String removed = s.verdicts.remove(conflictId);
            if (removed != null) {
                s.staleVerdicts.put(conflictId, removed);
            }
            s.outputText = "";
            touch(s);
            persist(s);
            return s;
        }
    }

    public Session clearVerdicts(String id, long expectedRevision) throws ConcurrentEditException {
        synchronized (lock) {
            Session s = require(id);
            checkRevision(s, expectedRevision);
            s.verdicts.clear();
            s.staleVerdicts.clear();
            s.outputText = "";
            touch(s);
            persist(s);
            return s;
        }
    }

    public Session saveOutput(String id, long expectedRevision, String outputText)
            throws ConcurrentEditException {
        synchronized (lock) {
            Session s = require(id);
            checkRevision(s, expectedRevision);
            s.outputText = nullToEmpty(outputText);
            touch(s);
            persist(s);
            return s;
        }
    }

    private static String normalizeSide(String side) {
        if (side == null) {
            throw new IllegalArgumentException("side required");
        }
        return switch (side.toLowerCase()) {
            case "left", "right" -> side.toLowerCase();
            default -> throw new IllegalArgumentException("side must be left or right");
        };
    }

    private Session require(String id) {
        Path file = fileFor(id);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("session not found: " + id);
        }
        return load(file);
    }

    private void checkRevision(Session s, long expected) throws ConcurrentEditException {
        if (expected != s.revision) {
            throw new ConcurrentEditException(expected, s.revision);
        }
    }

    private void touch(Session s) {
        s.revision++;
        s.updatedAt = Instant.now().toString();
    }

    private Path fileFor(String id) {
        if (!id.matches("[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("bad session id");
        }
        return dir.resolve(id + ".json");
    }

    private void persist(Session s) {
        Path file = fileFor(s.id);
        Path tmp = file.resolveSibling(s.id + ".json.tmp");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", s.id);
        root.put("revision", s.revision);
        root.put("baseText", s.baseText);
        root.put("leftText", s.leftText);
        root.put("rightText", s.rightText);
        root.put("verdicts", s.verdicts);
        root.put("staleVerdicts", s.staleVerdicts);
        root.put("fingerprint", s.fingerprint);
        root.put("outputText", s.outputText);
        root.put("createdAt", s.createdAt);
        root.put("updatedAt", s.updatedAt);
        try {
            Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("failed persisting session " + s.id, e);
        }
    }

    private Session load(Path file) {
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            Session s = new Session();
            s.id = str(root, "id");
            s.revision = ((Number) root.getOrDefault("revision", 1L)).longValue();
            s.baseText = str(root, "baseText");
            s.leftText = str(root, "leftText");
            s.rightText = str(root, "rightText");
            s.verdicts = toStringMap(root.get("verdicts"));
            s.staleVerdicts = toStringMap(root.get("staleVerdicts"));
            s.fingerprint = str(root, "fingerprint");
            s.outputText = str(root, "outputText");
            s.createdAt = str(root, "createdAt");
            s.updatedAt = str(root, "updatedAt");
            return s;
        } catch (IOException e) {
            throw new IllegalStateException("failed reading session " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> toStringMap(Object o) {
        Map<String, String> result = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return result;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
