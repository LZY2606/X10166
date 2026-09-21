package lockmerge.merge;

import java.util.List;
import lockmerge.model.PackageNode;

/** A node in the merged candidate graph. */
public record GraphNode(PackageNode node, List<String> presentIn, List<String> origins,
                        boolean reachable, boolean pruned, List<String> pruneReasons) {
}
