package lockmerge.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lockmerge.json.Json;
import lockmerge.merge.Conflict;
import lockmerge.merge.ConflictOption;
import lockmerge.merge.Decision;
import lockmerge.merge.Evaluation;
import lockmerge.merge.GraphEdge;
import lockmerge.merge.GraphNode;
import lockmerge.merge.GraphRoot;
import lockmerge.merge.Issue;
import lockmerge.merge.PrunedNode;
import lockmerge.model.Diagnostic;
import lockmerge.model.EdgeRef;
import lockmerge.model.PackageNode;
import lockmerge.session.Session;

/** Maps domain objects to JSON-friendly view maps. */
public final class ApiViews {

    private ApiViews() {
    }

    public static Map<String, Object> state(Session session, Evaluation evaluation) {
        Map<String, Object> state = Json.object(
                "revision", session.revision(),
                "createdAt", session.createdAt().toString(),
                "updatedAt", session.updatedAt().toString(),
                "outputVersion", session.outputVersion(),
                "outputUpdatedAt", session.outputUpdatedAt() == null
                        ? null : session.outputUpdatedAt().toString(),
                "baseRaw", session.baseRaw(),
                "leftRaw", session.leftRaw(),
                "rightRaw", session.rightRaw(),
                "blocked", evaluation.blocked(),
                "blockReasons", evaluation.blockReasons(),
                "hasOutput", evaluation.outputText() != null,
                "outputText", evaluation.outputText(),
                "outputFingerprint", evaluation.outputFingerprint(),
                "pendingCount", evaluation.pendingConflicts().size(),
                "diagnostics", diagnostics(evaluation),
                "conflicts", conflicts(evaluation, session),
                "changes", changes(evaluation),
                "issues", issues(evaluation),
                "pruned", pruned(evaluation),
                "graph", graph(evaluation),
                "roots", roots(evaluation),
                "fingerprints", Json.object(
                        "base", evaluation.base().fingerprint(),
                        "left", evaluation.left().fingerprint(),
                        "right", evaluation.right().fingerprint()));
        return state;
    }

    private static List<Object> diagnostics(Evaluation evaluation) {
        List<Object> result = new ArrayList<>();
        addDiagnostics(result, "base", evaluation.base());
        addDiagnostics(result, "left", evaluation.left());
        addDiagnostics(result, "right", evaluation.right());
        return result;
    }

    private static void addDiagnostics(List<Object> result, String side,
                                       lockmerge.model.ParseResult parsed) {
        for (Diagnostic d : parsed.diagnostics()) {
            result.add(Json.object(
                    "side", side,
                    "severity", d.severity().name(),
                    "line", d.line(),
                    "code", d.code(),
                    "message", d.message()));
        }
    }

    private static List<Object> conflicts(Evaluation evaluation, Session session) {
        List<Object> result = new ArrayList<>();
        Map<String, Decision> latestByConflict = new LinkedHashMap<>();
        for (Decision decision : session.decisions()) {
            latestByConflict.put(decision.conflictId(), decision);
        }
        for (Conflict conflict : evaluation.conflicts()) {
            List<Object> options = new ArrayList<>();
            for (ConflictOption option : conflict.options()) {
                options.add(Json.object(
                        "id", option.id(),
                        "label", option.label(),
                        "side", option.side(),
                        "nodeRef", option.nodeRef(),
                        "recommended", option.recommended()));
            }
            Decision lastDecision = latestByConflict.get(conflict.id());
            boolean stale = !conflict.resolved() && lastDecision != null;
            result.add(Json.object(
                    "id", conflict.id(),
                    "kind", conflict.kind(),
                    "nodeRef", conflict.nodeRef(),
                    "rootName", conflict.rootName(),
                    "summary", conflict.summary(),
                    "details", conflict.details(),
                    "options", options,
                    "resolution", conflict.resolution(),
                    "resolvedBy", conflict.resolvedBy(),
                    "decisionId", conflict.decisionId(),
                    "staleDecision", stale,
                    "staleDecisionOption",
                    stale ? lastDecision.optionId() : null,
                    "resolved", conflict.resolved()));
        }
        return result;
    }

    private static List<Object> changes(Evaluation evaluation) {
        List<Object> result = new ArrayList<>();
        evaluation.changes().forEach(change -> result.add(Json.object(
                "kind", change.kind(),
                "side", change.side(),
                "target", change.target(),
                "details", change.details())));
        return result;
    }

    private static List<Object> issues(Evaluation evaluation) {
        List<Object> result = new ArrayList<>();
        for (Issue issue : evaluation.issues()) {
            result.add(Json.object("kind", issue.kind(), "message", issue.message()));
        }
        return result;
    }

    private static List<Object> pruned(Evaluation evaluation) {
        List<Object> result = new ArrayList<>();
        for (PrunedNode node : evaluation.prunedNodes()) {
            result.add(Json.object(
                    "nodeRef", node.nodeRef(),
                    "orphan", node.orphan(),
                    "reasons", node.reasons()));
        }
        return result;
    }

    private static Map<String, Object> graph(Evaluation evaluation) {
        List<Object> nodes = new ArrayList<>();
        for (GraphNode graphNode : evaluation.graphNodes()) {
            PackageNode node = graphNode.node();
            List<Object> requires = new ArrayList<>();
            if (node != null) {
                for (EdgeRef edge : node.requires()) {
                    requires.add(Json.object(
                            "target", edge.target().reference(),
                            "platforms", edge.platforms()));
                }
            }
            nodes.add(Json.object(
                    "ref", node == null ? null : node.key().reference(),
                    "name", node == null ? null : node.key().name(),
                    "version", node == null ? null : node.key().version(),
                    "source", node == null ? null : node.source(),
                    "integrity", node == null ? null : node.integrity(),
                    "platforms", node == null ? null : node.platforms(),
                    "requires", requires,
                    "presentIn", graphNode.presentIn(),
                    "origins", graphNode.origins(),
                    "reachable", graphNode.reachable(),
                    "pruned", graphNode.pruned(),
                    "pruneReasons", graphNode.pruneReasons()));
        }
        List<Object> edges = new ArrayList<>();
        for (GraphEdge edge : evaluation.graphEdges()) {
            edges.add(Json.object(
                    "from", edge.fromRef(),
                    "to", edge.toRef(),
                    "platforms", edge.platforms(),
                    "targetReachable", edge.targetReachable(),
                    "origins", edge.origins()));
        }
        return Json.object("nodes", nodes, "edges", edges);
    }

    private static List<Object> roots(Evaluation evaluation) {
        List<Object> result = new ArrayList<>();
        for (GraphRoot root : evaluation.graphRoots()) {
            result.add(Json.object(
                    "name", root.dep().name(),
                    "ref", root.dep().reference(),
                    "presentIn", root.presentIn(),
                    "origins", root.origins(),
                    "targetPresent", root.targetPresent()));
        }
        return result;
    }
}
