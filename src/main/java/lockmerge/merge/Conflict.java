package lockmerge.merge;

import java.util.List;

/**
 * A pending or resolved three-way conflict.
 *
 * @param id            stable id ({@code node:name@version} / {@code root:name})
 * @param kind          NODE_CONTENT, NODE_DELETE_MODIFY, ROOT_CHANGED, ROOT_DELETE_MODIFY
 * @param nodeRef       node reference for node conflicts, else {@code null}
 * @param rootName      root name for root conflicts, else {@code null}
 * @param summary       human-readable one-line summary
 * @param details       lines describing how base/left/right differ
 * @param options       selectable options
 * @param resolution    chosen option id, or {@code null} while pending
 * @param resolvedBy    "auto" / "decision" / {@code null}
 * @param decisionId    id of the decision that resolved it, if any
 */
public record Conflict(String id, String kind, String nodeRef, String rootName,
                       String summary, List<String> details,
                       List<ConflictOption> options,
                       String resolution, String resolvedBy, String decisionId) {

    public boolean resolved() {
        return resolution != null;
    }

    public Conflict withResolution(String optionId, String by, String decisionId) {
        return new Conflict(id, kind, nodeRef, rootName, summary, details, options,
                optionId, by, decisionId);
    }
}
