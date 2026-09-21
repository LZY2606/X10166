package dev.lockmerge.merge;

import dev.lockmerge.lock.LockParser;
import dev.lockmerge.lock.LockPrinter;
import dev.lockmerge.model.Diagnostic;
import dev.lockmerge.model.LockDocument;
import dev.lockmerge.model.Node;
import dev.lockmerge.model.Ref;
import dev.lockmerge.persist.Session;
import dev.lockmerge.persist.SessionStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Orchestrates parse + validate + merge + publish for one session. */
public final class MergeService {

    private final SessionStore store;
    private final Supplier<String> clock;

    public MergeService(SessionStore store) {
        this(store, () -> Instant.now().toString());
    }

    MergeService(SessionStore store, Supplier<String> clock) {
        this.store = store;
        this.clock = clock;
    }

    public static String fingerprint(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Session createSession() {
        Session session = new Session();
        session.id = store.newId();
        session.createdAt = clock.get();
        session.updatedAt = session.createdAt;
        session.revision = 1;
        store.save(session);
        return session;
    }

    /** Parses and validates one side without failing hard; errors become diagnostics. */
    private MergeEngine.SideInput buildSide(String text, String fingerprint) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        LockDocument document;
        LockParser.Result parsed = LockParser.parse(text);
        diagnostics.addAll(parsed.diagnostics);
        if (parsed.hasErrors() || parsed.document == null) {
            document = new LockDocument(List.of(), List.of());
            return new MergeEngine.SideInput(fingerprint, document, diagnostics,
                    Validator.validate(document));
        }
        document = parsed.document;
        Validator.Report report = Validator.validate(document);
        diagnostics.addAll(report.diagnostics);
        return new MergeEngine.SideInput(fingerprint, document, diagnostics, report);
    }

    public MergeEngine.Result evaluate(Session session) {
        MergeEngine.SideInput base = buildSide(session.baseText, fingerprint(session.baseText));
        MergeEngine.SideInput left = buildSide(session.leftText, fingerprint(session.leftText));
        MergeEngine.SideInput right = buildSide(session.rightText, fingerprint(session.rightText));
        MergeEngine.Inputs inputs = new MergeEngine.Inputs(base, left, right);
        return MergeEngine.compute(inputs, List.copyOf(session.decisions));
    }

    public boolean anyParseErrors(Session session) {
        for (String text : List.of(session.baseText, session.leftText, session.rightText)) {
            LockParser.Result result = LockParser.parse(text);
            if (result.hasErrors()) {
                return true;
            }
            if (result.document != null && Validator.validate(result.document).hasErrors()) {
                return true;
            }
        }
        return false;
    }

    public Session require(String sessionId) {
        return store.find(sessionId)
                .orElseThrow(() -> new NotFoundException("session not found: " + sessionId));
    }

    /** Applies inputs; clears decisions because they bind to the old fingerprints. */
    public Session submitInputs(String sessionId, long expectedRevision,
                                String baseText, String leftText, String rightText) {
        synchronized (store.lockFor(sessionId)) {
            Session session = require(sessionId);
            if (session.revision != expectedRevision) {
                throw new ConflictException("revision " + expectedRevision
                        + " is stale; current revision is " + session.revision);
            }
            session.baseText = nullToEmpty(baseText);
            session.leftText = nullToEmpty(leftText);
            session.rightText = nullToEmpty(rightText);
            // Decisions are retained on disk (the adjudication history must
            // survive), but they are bound to the old fingerprint triple and
            // therefore become stale rather than being reapplied.
            touch(session);
            store.save(session);
            return session;
        }
    }

    public Session addDecision(String sessionId, long expectedRevision, Decision decision) {
        synchronized (store.lockFor(sessionId)) {
            Session session = require(sessionId);
            if (session.revision != expectedRevision) {
                throw new ConflictException("revision " + expectedRevision
                        + " is stale; current revision is " + session.revision);
            }
            MergeEngine.Result result = evaluate(session);
            boolean known = result.conflicts.stream()
                    .anyMatch(c -> c.id().equals(decision.conflictId()));
            if (!known) {
                throw new NotFoundException(
                        "no active conflict with id " + decision.conflictId());
            }
            session.decisions.removeIf(d -> d.conflictId().equals(decision.conflictId()));
            session.decisions.add(decision);
            touch(session);
            store.save(session);
            return session;
        }
    }

    public Session clearDecision(String sessionId, long expectedRevision, String conflictId) {
        synchronized (store.lockFor(sessionId)) {
            Session session = require(sessionId);
            if (session.revision != expectedRevision) {
                throw new ConflictException("revision " + expectedRevision
                        + " is stale; current revision is " + session.revision);
            }
            session.decisions.removeIf(d -> d.conflictId().equals(conflictId));
            touch(session);
            store.save(session);
            return session;
        }
    }

    public Session publish(String sessionId, long expectedRevision) {
        synchronized (store.lockFor(sessionId)) {
            Session session = require(sessionId);
            if (session.revision != expectedRevision) {
                throw new ConflictException("revision " + expectedRevision
                        + " is stale; current revision is " + session.revision);
            }
            if (anyParseErrors(session)) {
                throw new BadRequestException("存在解析或校验错误，无法输出");
            }
            MergeEngine.Result result = evaluate(session);
            if (!result.canPublish()) {
                throw new BadRequestException("仍有未决冲突或悬空引用，无法输出");
            }
            String text = LockPrinter.print(result.resolvedGraph);
            int number = session.versions.stream()
                    .mapToInt(v -> v.number)
                    .max().orElse(0) + 1;
            session.versions.add(new Session.PublishedVersion(number, clock.get(), text,
                    fingerprint(session.baseText),
                    fingerprint(session.leftText),
                    fingerprint(session.rightText)));
            touch(session);
            store.save(session);
            return session;
        }
    }

    private static void touch(Session session) {
        session.updatedAt = Instant.now().toString();
        session.revision++;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ---- JSON views for the web layer ----------------------------------

    public Map<String, Object> stateView(Session session) {
        MergeEngine.Result result = evaluate(session);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", session.id);
        view.put("revision", session.revision);
        view.put("createdAt", session.createdAt);
        view.put("updatedAt", session.updatedAt);
        view.put("fingerprints", Map.of(
                "base", fingerprint(session.baseText),
                "left", fingerprint(session.leftText),
                "right", fingerprint(session.rightText)));
        view.put("sides", sideViews(session));
        view.put("conflicts", conflictViews(result));
        view.put("decisions", session.decisions.stream().map(d -> Map.<String, Object>of(
                "conflictId", d.conflictId(),
                "choice", d.choice(),
                "bound", result.appliedDecisions.stream()
                        .anyMatch(a -> a.conflictId().equals(d.conflictId())))).toList());
        view.put("staleDecisionIds", result.staleDecisionIds);
        view.put("removed", result.removed.stream()
                .map(r -> Map.of("id", r.id(), "reason", r.reason())).toList());
        view.put("dangling", result.dangling.stream()
                .map(MergeService::refView).toList());
        view.put("graph", graphView(result.resolvedGraph));
        view.put("canPublish", result.canPublish());
        view.put("resolvedText",
                result.canPublish() ? LockPrinter.print(result.resolvedGraph) : null);
        view.put("resolutionErrors", result.resolutionErrors);
        view.put("integrity", integrityViews(result.resolvedGraph));
        view.put("versions", session.versions.stream()
                .map(v -> Map.<String, Object>of(
                        "number", v.number,
                        "publishedAt", v.publishedAt,
                        "fingerprintBase", v.fingerprintBase,
                        "fingerprintLeft", v.fingerprintLeft,
                        "fingerprintRight", v.fingerprintRight))
                .toList());
        return view;
    }

    private List<Map<String, Object>> sideViews(Session session) {
        List<Map<String, Object>> sides = new ArrayList<>();
        for (String text : List.of(session.baseText, session.leftText, session.rightText)) {
            LockParser.Result parsed = LockParser.parse(text);
            Validator.Report report = parsed.document == null
                    ? null : Validator.validate(parsed.document);
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            for (Diagnostic d : parsed.diagnostics) {
                diagnostics.add(Map.of("severity", d.severity().name(),
                        "line", d.line(), "code", d.code(), "message", d.message()));
            }
            if (report != null) {
                for (Diagnostic d : report.diagnostics) {
                    diagnostics.add(Map.of("severity", d.severity().name(),
                            "line", d.line(), "code", d.code(), "message", d.message()));
                }
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("hasErrors", parsed.hasErrors() || (report != null && report.hasErrors()));
            view.put("diagnostics", diagnostics);
            sides.add(view);
        }
        return sides;
    }

    private List<Map<String, Object>> conflictViews(MergeEngine.Result result) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (Conflict conflict : result.conflicts) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", conflict.id());
            view.put("type", conflict.type().name());
            view.put("title", conflict.title());
            view.put("detail", conflict.detail());
            view.put("base", payloadView(conflict.base()));
            view.put("left", payloadView(conflict.left()));
            view.put("right", payloadView(conflict.right()));
            Optional<Decision> chosen = result.appliedDecisions.stream()
                    .filter(d -> d.conflictId().equals(conflict.id())).findFirst();
            chosen.ifPresent(decision -> view.put("chosen", decision.choice()));
            views.add(view);
        }
        return views;
    }

    private static Object payloadView(Object payload) {
        if (payload instanceof Node node) {
            return nodeView(node);
        }
        if (payload instanceof List<?> list && list.stream().allMatch(Ref.class::isInstance)) {
            @SuppressWarnings("unchecked")
            List<Ref> refs = (List<Ref>) list;
            return refs.stream().map(MergeService::refView).toList();
        }
        if (payload instanceof String s) {
            return s;
        }
        return null;
    }

    static Map<String, Object> refView(Ref ref) {
        return Map.of("name", ref.name(), "version", ref.version(),
                "condition", ref.condition() == null ? "" : ref.condition(),
                "key", ref.key());
    }

    static Map<String, Object> nodeView(Node node) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", node.id());
        view.put("name", node.name());
        view.put("version", node.version());
        view.put("source", node.source());
        view.put("integrity", node.integrity());
        view.put("platform", node.platform() == null ? "" : node.platform());
        view.put("children", node.children().stream().map(MergeService::refView).toList());
        return view;
    }

    private Map<String, Object> graphView(LockDocument document) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("roots", document.roots().stream().map(MergeService::refView).toList());
        view.put("nodes", document.nodes().stream()
                .sorted(java.util.Comparator.comparing(Node::name).thenComparing(Node::version))
                .map(MergeService::nodeView).toList());
        return view;
    }

    private List<Map<String, Object>> integrityViews(LockDocument document) {
        Map<String, List<Node>> grouped = new LinkedHashMap<>();
        for (Node node : document.nodes()) {
            grouped.computeIfAbsent(node.source() + "|" + node.version(), k -> new ArrayList<>())
                    .add(node);
        }
        List<Map<String, Object>> views = new ArrayList<>();
        for (Map.Entry<String, List<Node>> entry : grouped.entrySet()) {
            long distinct = entry.getValue().stream().map(Node::integrity).distinct().count();
            views.add(Map.of(
                    "source", entry.getKey().substring(0, entry.getKey().lastIndexOf('|')),
                    "version", entry.getKey().substring(entry.getKey().lastIndexOf('|') + 1),
                    "integrity", entry.getValue().get(0).integrity(),
                    "nodes", entry.getValue().stream().map(Node::id).toList(),
                    "consistent", distinct == 1));
        }
        return views;
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }
}
