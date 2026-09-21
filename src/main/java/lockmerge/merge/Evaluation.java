package lockmerge.merge;

import java.util.List;
import lockmerge.model.LockDocument;
import lockmerge.model.ParseResult;

/** The full result of a three-way semantic merge evaluation. */
public record Evaluation(ParseResult base, ParseResult left, ParseResult right,
                         List<Conflict> conflicts, List<Change> changes,
                         List<Issue> issues, List<PrunedNode> prunedNodes,
                         List<GraphNode> graphNodes, List<GraphEdge> graphEdges,
                         List<GraphRoot> graphRoots,
                         LockDocument output, String outputText,
                         String outputFingerprint, boolean blocked,
                         List<String> blockReasons) {

    public List<Conflict> pendingConflicts() {
        return conflicts.stream().filter(c -> !c.resolved()).toList();
    }
}
