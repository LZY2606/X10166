package lockmerge.web;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import lockmerge.merge.MergeResult;
import lockmerge.merge.Merger;
import lockmerge.model.LockGraph;
import lockmerge.parse.LockfileParser;
import lockmerge.parse.LockfilePrinter;
import lockmerge.parse.ParseResult;
import lockmerge.store.Session;
import lockmerge.store.SessionStore;

/** 会话编排：解析 → 合并 → 裁决 → 输出版本。裁决绑定三输入指纹，输入变化后旧裁决不自动应用。 */
public final class MergeService {
    private final SessionStore store;

    public MergeService(SessionStore store) {
        this.store = store;
    }

    public static final class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) {
            super(message);
        }
    }

    public static final class BadInputException extends RuntimeException {
        public BadInputException(String message) {
            super(message);
        }
    }

    public record Snapshot(Session session, MergeResult merge, String output) {
    }

    public Session createSession(String base, String left, String right) {
        Session s = store.create();
        applyInputs(s, base, left, right);
        store.save(s);
        return s;
    }

    public Snapshot snapshot(String sessionId) {
        Session s = mustLoad(sessionId);
        MergeResult merge = computeMerge(s);
        String output = merge.resolved() ? LockfilePrinter.print(merge.graph) : null;
        return new Snapshot(s, merge, output);
    }

    /** 更新输入；expectedVersion 不匹配时抛乐观锁异常。输入变化后旧裁决自动失效。 */
    public Snapshot updateInputs(String sessionId, String base, String left, String right,
                                 long expectedVersion) {
        Session s = mustLoad(sessionId);
        checkVersion(s, expectedVersion);
        applyInputs(s, base, left, right);
        s.version++;
        store.save(s);
        return snapshot(sessionId);
    }

    /** 裁决某项冲突；裁决记录绑定当前输入指纹。 */
    public Snapshot decide(String sessionId, String conflictId, String choice, long expectedVersion) {
        if (!"left".equals(choice) && !"right".equals(choice)) {
            throw new BadInputException("choice 必须是 left 或 right");
        }
        Session s = mustLoad(sessionId);
        checkVersion(s, expectedVersion);
        MergeResult merge = computeMerge(s);
        boolean known = merge.conflicts.stream().anyMatch(c -> c.id.equals(conflictId));
        if (!known) throw new BadInputException("未知冲突: " + conflictId);
        s.decisions.removeIf(d -> d.conflictId.equals(conflictId));
        s.decisions.add(new Session.Decision(conflictId, choice, s.fingerprint, System.currentTimeMillis()));
        s.version++;
        maybeAppendOutput(s);
        store.save(s);
        return snapshot(sessionId);
    }

    private void applyInputs(Session s, String base, String left, String right) {
        s.baseText = base == null ? "" : base;
        s.leftText = left == null ? "" : left;
        s.rightText = right == null ? "" : right;
        s.fingerprint = fingerprint(s.baseText, s.leftText, s.rightText);
        s.diagnostics.clear();
        collectDiagnostics("base", s.baseText, s);
        collectDiagnostics("left", s.leftText, s);
        collectDiagnostics("right", s.rightText, s);
        maybeAppendOutput(s);
    }

    private void collectDiagnostics(String side, String text, Session s) {
        ParseResult r = LockfileParser.parse(text);
        for (var d : r.diagnostics) {
            s.diagnostics.add("[" + side + "] " + d);
        }
    }

    /** 解析三份输入并合并；只应用指纹与当前输入匹配的裁决。 */
    private MergeResult computeMerge(Session s) {
        ParseResult pb = LockfileParser.parse(s.baseText);
        ParseResult pl = LockfileParser.parse(s.leftText);
        ParseResult pr = LockfileParser.parse(s.rightText);
        LockGraph base = pb.ok() ? pb.graph : LockGraph.empty();
        LockGraph left = pl.ok() ? pl.graph : LockGraph.empty();
        LockGraph right = pr.ok() ? pr.graph : LockGraph.empty();
        Map<String, String> effective = new LinkedHashMap<>();
        for (Session.Decision d : s.decisions) {
            if (d.fingerprint.equals(s.fingerprint)) {
                effective.put(d.conflictId, d.choice);
            }
        }
        return Merger.merge(base, left, right, effective);
    }

    /** 合并完全解决时记录一个稳定排序的输出版本（内容去重）。 */
    private void maybeAppendOutput(Session s) {
        MergeResult merge = computeMerge(s);
        if (!merge.resolved()) return;
        String content = LockfilePrinter.print(merge.graph);
        if (!s.outputs.isEmpty()) {
            Session.OutputVersion last = s.outputs.get(s.outputs.size() - 1);
            if (last.content.equals(content)) return;
        }
        s.outputs.add(new Session.OutputVersion(s.outputs.size() + 1, s.fingerprint,
                (int) s.decisions.stream().filter(d -> d.fingerprint.equals(s.fingerprint)).count(),
                content, System.currentTimeMillis()));
    }

    private Session mustLoad(String sessionId) {
        return store.load(sessionId)
                .orElseThrow(() -> new BadInputException("会话不存在: " + sessionId));
    }

    private void checkVersion(Session s, long expectedVersion) {
        if (s.version != expectedVersion) {
            throw new OptimisticLockException("会话已被并发修改: 期望版本 " + expectedVersion
                    + "，当前版本 " + s.version);
        }
    }

    public static String fingerprint(String base, String left, String right) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{base, left, right}) {
                md.update(part.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
