package mergeroom.merge;

import java.util.List;
import java.util.Map;

import mergeroom.model.Lockfile;

/**
 * Result of one merge recomputation.
 *
 * @param merged         tentative merged graph (pre-cleanup)
 * @param conflicts      all conflicts, each marked active/resolved/dangling
 * @param danglingRefs   references with no matching node in the merged graph
 * @param removedNodes   nodes that reachability cleanup removes (with reasons)
 * @param finalLockfile  cleaned graph, or null while blocked
 * @param outputText     stable rendered result, or null while blocked
 * @param blockingReasons why no output is produced
 * @param autoMerged     count of automatically merged elements
 * @param integrityIssues cross-node source/digest problems
 */
public record MergeOutcome(
        Lockfile merged,
        List<Conflict> conflicts,
        List<DanglingRef> danglingRefs,
        List<RemovedNode> removedNodes,
        Lockfile finalLockfile,
        String outputText,
        List<String> blockingReasons,
        Map<String, Integer> autoMerged,
        List<String> integrityIssues) {

    public boolean blocked() {
        return outputText == null;
    }
}
