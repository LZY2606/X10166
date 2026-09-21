package lockmerge.merge;

import java.util.List;
import lockmerge.model.LockGraph;

public final class MergeResult {
    public final LockGraph graph; // 清理后的图（未决冲突时采用左側临时值，仅供预览）
    public final List<Conflict> conflicts;
    public final List<PrunedNode> pruned;
    public final List<String> integrityViolations;
    public final List<String> validationErrors;

    public MergeResult(LockGraph graph, List<Conflict> conflicts, List<PrunedNode> pruned,
                       List<String> integrityViolations, List<String> validationErrors) {
        this.graph = graph;
        this.conflicts = List.copyOf(conflicts);
        this.pruned = List.copyOf(pruned);
        this.integrityViolations = List.copyOf(integrityViolations);
        this.validationErrors = List.copyOf(validationErrors);
    }

    public boolean resolved() {
        return conflicts.stream().allMatch(c -> c.choice != null)
                && integrityViolations.isEmpty()
                && validationErrors.isEmpty();
    }
}
