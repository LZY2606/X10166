package lockmerge.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** 基于文件（每会话一个 JSON）的持久化；重启后未完成会话可继续。 */
public final class SessionStore {
    private final Path dir;

    public SessionStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Session create() {
        Session s = new Session();
        s.id = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis();
        s.createdAt = now;
        s.updatedAt = now;
        save(s);
        return s;
    }

    public synchronized Optional<Session> load(String id) {
        Path file = fileFor(id);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(fromJson(Json.parseObject(Files.readString(file, StandardCharsets.UTF_8))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void save(Session s) {
        s.updatedAt = System.currentTimeMillis();
        Path file = fileFor(s.id);
        try {
            Path tmp = dir.resolve(s.id + ".tmp");
            Files.writeString(tmp, Json.write(toJson(s)), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized List<Session> list() {
        List<Session> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    out.add(fromJson(Json.parseObject(Files.readString(f, StandardCharsets.UTF_8))));
                } catch (Exception ignored) {
                    // 跳过损坏文件
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparingLong((Session s) -> s.updatedAt).reversed());
        return out;
    }

    private Path fileFor(String id) {
        if (!id.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("非法会话 id");
        return dir.resolve(id + ".json");
    }

    private static Map<String, Object> toJson(Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id);
        m.put("baseText", s.baseText);
        m.put("leftText", s.leftText);
        m.put("rightText", s.rightText);
        m.put("fingerprint", s.fingerprint);
        m.put("version", (double) s.version);
        m.put("createdAt", (double) s.createdAt);
        m.put("updatedAt", (double) s.updatedAt);
        m.put("diagnostics", new ArrayList<>(s.diagnostics));
        List<Object> decisions = new ArrayList<>();
        for (Session.Decision d : s.decisions) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("conflictId", d.conflictId);
            dm.put("choice", d.choice);
            dm.put("fingerprint", d.fingerprint);
            dm.put("at", (double) d.at);
            decisions.add(dm);
        }
        m.put("decisions", decisions);
        List<Object> outputs = new ArrayList<>();
        for (Session.OutputVersion o : s.outputs) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("version", (double) o.version);
            om.put("fingerprint", o.fingerprint);
            om.put("decisionCount", (double) o.decisionCount);
            om.put("content", o.content);
            om.put("at", (double) o.at);
            outputs.add(om);
        }
        m.put("outputs", outputs);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Session fromJson(Map<String, Object> m) {
        Session s = new Session();
        s.id = str(m, "id");
        s.baseText = str(m, "baseText");
        s.leftText = str(m, "leftText");
        s.rightText = str(m, "rightText");
        s.fingerprint = str(m, "fingerprint");
        s.version = num(m, "version");
        s.createdAt = num(m, "createdAt");
        s.updatedAt = num(m, "updatedAt");
        for (Object d : (List<Object>) m.getOrDefault("diagnostics", List.of())) {
            s.diagnostics.add((String) d);
        }
        for (Object o : (List<Object>) m.getOrDefault("decisions", List.of())) {
            Map<String, Object> dm = (Map<String, Object>) o;
            s.decisions.add(new Session.Decision(str(dm, "conflictId"), str(dm, "choice"),
                    str(dm, "fingerprint"), num(dm, "at")));
        }
        for (Object o : (List<Object>) m.getOrDefault("outputs", List.of())) {
            Map<String, Object> om = (Map<String, Object>) o;
            s.outputs.add(new Session.OutputVersion(num(om, "version"), str(om, "fingerprint"),
                    (int) num(om, "decisionCount"), str(om, "content"), num(om, "at")));
        }
        return s;
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
