package mergeroom.merge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mergeroom.model.Diagnostic;
import mergeroom.model.Edge;
import mergeroom.model.Lockfile;
import mergeroom.model.PackageNode;
import mergeroom.model.RootRef;
import mergeroom.parse.LockfileParser;
import mergeroom.parse.LockfilePrinter;
import mergeroom.store.Session;
import mergeroom.store.SessionStore;

/** Stateless orchestration over {@link SessionStore} persistence. */
public final class MergeService {

    private final SessionStore store;
    private final LockfileParser parser = new LockfileParser();

    public MergeService(SessionStore store) {
        this.store = store;
    }

    public SessionStore store() {
        return store;
    }

    public Parsed parseOne(String text) {
        Lockfile lf = parser.parse(text);
        return new Parsed(lf);
    }

    /** Full snapshot used by the UI and API. */
    public Map<String, Object> snapshot(Session session) {
        Parsed base = parseOne(session.baseText);
        Parsed left = parseOne(session.leftText);
        Parsed right = parseOne(session.rightText);

        Map<String, Side> verdicts = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : session.verdicts.entrySet()) {
            verdicts.put(e.getKey(), "right".equals(e.getValue()) ? Side.RIGHT : Side.LEFT);
        }
        MergeOutcome outcome = null;
        boolean inputsParseable = !base.lockfile.hasErrors()
                && !left.lockfile.hasErrors()
                && !right.lockfile.hasErrors();
        if (inputsParseable) {
            MergeEngine engine = new MergeEngine(base.lockfile, left.lockfile, right.lockfile);
            outcome = engine.compute(verdicts);
        }

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("revision", session.revision);
        snap.put("fingerprint", session.fingerprint);
        snap.put("inputs", Map.of(
                "base", sideJson(session.baseText, base),
                "left", sideJson(session.leftText, left),
                "right", sideJson(session.rightText, right)));
        snap.put("staleVerdicts", new ArrayList<>(session.staleVerdicts.entrySet().stream()
                .map(e -> Map.of("id", e.getKey(), "side", e.getValue())).toList()));
        snap.put("staleVerdictCount", session.staleVerdicts.size());
        snap.put("inputChanged", !session.verdicts.isEmpty() && session.staleVerdicts.isEmpty()
                ? false : !session.staleVerdicts.isEmpty());
        if (!inputsParseable) {
            snap.put("merge", null);
            snap.put("blocking", List.of("请先修复三份输入中的解析错误"));
        } else {
            snap.put("merge", mergeJson(outcome, session));
            snap.put("blocking", outcome.blockingReasons());
        }
        snap.put("savedOutputVersion", session.outputText);
        snap.put("updatedAt", session.updatedAt);
        return snap;
    }

    private Map<String, Object> sideJson(String raw, Parsed parsed) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> diags = new ArrayList<>();
        for (Diagnostic d : parsed.lockfile.diagnostics()) {
            diags.add(Map.of(
                    "severity", d.severity().name().toLowerCase(),
                    "line", d.line(),
                    "message", d.message()));
        }
        m.put("diagnostics", diags);
        m.put("errorCount", parsed.lockfile.errors().size());
        m.put("rootCount", parsed.lockfile.roots().size());
        m.put("nodeCount", parsed.lockfile.nodes().size());
        m.put("bytes", raw.length());
        return m;
    }

    private Map<String, Object> mergeJson(MergeOutcome o, Session session) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Conflict c : o.conflicts()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.id());
            cm.put("kind", c.kind().name().toLowerCase());
            cm.put("subject", c.subject());
            cm.put("description", c.description());
            cm.put("leftOption", c.leftOption());
            cm.put("rightOption", c.rightOption());
            cm.put("resolution", c.resolution() == null ? null : c.resolution().label());
            cm.put("active", c.active());
            cm.put("danglingAfterResolution", c.danglingAfterResolution());
            conflicts.add(cm);
        }
        m.put("conflicts", conflicts);
        m.put("activeConflictCount", o.conflicts().stream().filter(c -> c.active() && c.unresolved()).count());
        m.put("dangling", o.danglingRefs().stream().map(d -> Map.of(
                "from", d.from(), "target", d.target(), "detail", d.detail())).toList());
        m.put("removed", o.removedNodes().stream().map(r -> Map.of(
                "key", r.key(), "reason", r.reason())).toList());
        m.put("integrityIssues", o.integrityIssues());
        m.put("autoMerged", o.autoMerged());
        m.put("blocked", o.blocked());
        m.put("graph", graphJson(o.merged()));
        m.put("output", o.outputText());
        m.put("verdicts", session.verdicts);
        return m;
    }

    private Map<String, Object> graphJson(Lockfile lf) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (PackageNode n : lf.nodes().values()) {
            List<Map<String, Object>> edges = new ArrayList<>();
            for (Edge e : n.edges().values()) {
                edges.add(Map.of("child", e.child(), "version", e.spec(),
                        "condition", e.condition(), "target", e.child() + "@" + e.spec()));
            }
            nodes.add(Map.of("key", n.key(), "name", n.name(), "version", n.version(),
                    "source", n.source(), "integrity", n.integrity(),
                    "condition", n.condition(), "edges", edges));
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (RootRef r : lf.roots()) {
            roots.add(Map.of("name", r.name(), "version", r.version(),
                    "condition", r.condition(), "target", r.targetKey()));
        }
        return Map.of("roots", roots, "nodes", nodes);
    }

    public String renderStable(Lockfile lf) {
        return new LockfilePrinter().print(lf);
    }

    public static final class Parsed {
        public final Lockfile lockfile;

        Parsed(Lockfile lockfile) {
            this.lockfile = lockfile;
        }
    }
}
