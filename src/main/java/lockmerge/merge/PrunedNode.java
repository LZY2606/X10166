package lockmerge.merge;

import java.util.List;

/** A candidate node removed by root-reachability cleanup. */
public record PrunedNode(String nodeRef, boolean orphan, List<String> reasons) {
}
